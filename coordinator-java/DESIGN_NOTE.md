# The Coordinator — Design Note

## Assumption on the coordination backend

I assumed **Redis** (single primary, or a Redis Cluster with equivalent
single-writer-per-key semantics), accessed via `SET NX PX`, a Lua-script
compare-and-delete for unlock, and `INCR` for fencing counters. `CoordinationService`
in this submission is an in-memory stand-in that implements exactly those
primitives with the same atomicity contract, so swapping it for a real Redis
client is a drop-in change to one class, not a redesign.

## The guarantee

**What I actually guarantee:** at most one worker's write is ever *applied* to
a given entity for a given "round" of work, **provided the protected resource
itself enforces fencing tokens** (see `ProtectedResource.apply`). This is a
guarantee about the write path, not about lock possession.

**What the lock alone guarantees, and no more:** at most one worker holds
`lock:{entity}` in the coordination service at any clock instant the
coordination service is aware of. This is standard mutual exclusion *of
acquisition*, and it holds unconditionally — it doesn't depend on worker
liveness, pause behavior, or clock skew, because it's enforced entirely
inside the coordination service's own atomic operations.

**What that does NOT guarantee**, and this is the crux of the exercise: that
the *only* worker acting on the resource at any moment is the one who
currently holds the lock. That stronger claim additionally depends on:

- worker pauses (GC, blocked syscall, CPU steal) never exceeding the TTL, and
- the coordination service's clock and each worker's sense of elapsed time
  staying reasonably in sync (bounded clock skew).

The exercise's own "operating conditions" section states neither of those
holds. So I do not make that stronger claim. Instead, the actual safety
property — no double charge, no lost write — is enforced one layer down, at
the resource, using fencing tokens, and does **not** depend on the two
assumptions above. That's the guarantee I'll actually defend: **the resource
never applies a write from a stale grant, regardless of how long any worker
pauses or how the lock's TTL clock drifts**, because every grant carries a
token from a strictly-monotonic counter, and the resource rejects any token
that isn't higher than the last one it applied for that entity.

## The failure you can't prevent at the lock

The lock cannot prevent two workers from *simultaneously believing* they
hold it. Concretely: Worker A acquires the lock, then stalls (GC pause,
scheduler preemption, whatever) for longer than the TTL. The coordination
service — correctly, per its own contract — lets the TTL expire and hands
the lock to Worker B, which does its work and releases. Worker A then wakes
up. It has no signal that any of this happened; from its perspective it
still holds a valid lock, because the TTL check only happens inside the
coordination service, not inside the paused thread. If Worker A now acts on
the resource, it is acting without exclusivity, and the lock did nothing to
stop it — the lock's job was already done (grant, expire, re-grant), all
correctly, by the time A resumes.

**This is caught at the resource, not the lock**, via the fencing token: A's
token is lower than the token B already applied, so the resource rejects A's
write. Scenario 2 vs. 3 in the simulation demonstrates this directly — same
stall, same timing, only the presence of fencing enforcement at the resource
differs, and it's the difference between a $200 double charge and a correct
$100 charge.

There's a second, smaller failure in the same family that's easy to miss:
if `release()` is implemented as an unconditional `DEL` instead of a
compare-and-delete, a stalled Worker A can delete the lock Worker B is
*currently, legitimately* holding, opening the door to a third worker
sneaking in concurrently with B. I avoid this by having every grant carry a
random `ownerId` and having release only delete if the stored value still
matches that owner (see Scenario 2/3's `released=false` for A — its release
correctly no-ops instead of clobbering B's lock).

**What fencing does *not* catch:** if the resource is not fencing-aware —
e.g. it's a third-party API that just takes "charge $100" with no way to
attach or check a token — none of this helps, and you're fully back to
depending on the TTL/liveness assumptions holding, or you need an idempotency
key plus a downstream reconciliation/dedup step instead. I flag this because
it's the single biggest assumption baked into "fencing tokens solve this":
**they only solve it if the resource enforces them.** The lock service alone
never guarantees exclusivity of *effect*, only of *acquisition*.

## The TTL decision

There is no TTL value that is simultaneously safe against a dead worker and
generous enough for the slowest legitimate job, because "dead" and "just
slow" are indistinguishable from the outside — that's stated directly in the
operating conditions, and I don't think there's a clever way around it. So I
made TTL a *liveness/availability* knob, not a *safety* knob, and moved
safety entirely onto fencing. That reframes the question from "what TTL is
correct" (no such value exists) to "what TTL gives good throughput without
making dead-worker recovery too slow" — a much easier tradeoff:

- **Too short** relative to the job: legitimate long jobs lose the lock
  mid-flight, another worker starts redundant work, and the fencing-token
  reject means the *slower* worker's write gets discarded — wasted work, not
  corruption. Annoying, not dangerous.
- **Too long**: a genuinely dead worker holds an entity hostage for that long,
  which is a real availability cost (delayed invoice, stuck shipment) — but
  again, not a correctness cost.

Given that framing, I'd set TTL to roughly **3–5x the p99 duration of jobs
touching that entity type**, measured per job class rather than globally
(a billing-ledger job and a document-store job have very different tails).
I'd also add **lock renewal** (a heartbeat that extends the TTL while the
worker is verifiably still making progress) for the minority of genuinely
long jobs, rather than picking one static TTL that has to cover both the
common fast case and the rare slow case — that renewal step, plus per-class
TTL tuning from real p99 data, is exactly the kind of thing I'd want
production metrics for before committing to a number; I have not built it
here (see below).

## What I'd do with more time / in production

Cut corners, stated plainly:

- **No lock renewal / heartbeating.** A long-running job either finishes
  inside one static TTL or loses its lock. In production I'd add a
  background thread per held lock that extends the TTL (via a
  compare-and-set on `ownerId`, same safety property as release) as long as
  the worker is alive and making progress, so the TTL can be set tight
  without punishing legitimate long jobs.
- **No retry/backoff tuning or jitter.** `acquire()` polls every 10ms flat.
  Under real contention at scale this is thundering-herd-prone; I'd want
  exponential backoff with jitter, or a `BLPOP`-style blocking wait if the
  backend supports it.
- **Single coordination-service instance, no failover modeled.** I explicitly
  did not model Redis Sentinel/Cluster failover or a primary promotion. A
  failover where an unacknowledged write to the old primary is lost, and a
  worker's lock silently vanishes and gets re-granted elsewhere, is a real
  gap this design doesn't address — the standard mitigation (per Kleppmann's
  critique of Redlock) is to rely on fencing at the resource anyway, which I
  already do, so I believe the design degrades to "occasionally reduced
  availability" rather than "occasionally unsafe" even under that failure —
  but I have not tested that claim, only reasoned about it.
- **In-memory `ProtectedResource`, not a real datastore.** A real ledger
  would need the fencing check and the write to be one atomic operation
  (e.g. `UPDATE ledger SET balance = balance + :delta, last_token = :token
  WHERE entity = :id AND last_token < :token`), not two separate calls the
  way a naive integration might do it. I modeled that atomicity with a mutex
  here; a real implementation needs it enforced by the datastore itself.
- **No dedup/idempotency key as a second line of defense.** Fencing prevents
  a *stale* write from applying, but I haven't added an idempotency key to
  guard against a worker retrying its *own current* write after a network
  blip (send, timeout, resend) — a different problem from the one this
  exercise is about, but a real system touching a billing ledger would want
  both.
- **Simulation, not load test.** The harness proves the specific failure mode
  the exercise describes and its fix, plus ordinary contention at a small
  scale (20 workers × 50 ops). I did not push it to the scale or duration
  that would surface, e.g., HashMap resize contention artifacts in my
  in-memory stand-in — that's an artifact of the stand-in, not something I'd
  expect against real Redis, but I haven't proven that.
