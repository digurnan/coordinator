# Distributed Locking — Design Document: Approaches, Problems, Solutions

This document surveys the realistic design space for coordinating access to
a shared external resource across a worker fleet, states what problem each
approach actually solves, and where each one breaks. It ends with the
approach used in the submitted implementation and why.

---

## 0. The problem, precisely

Multiple workers pull jobs from a shared queue. Some jobs touch an entity
(an invoice, a document, an inventory row) where concurrent action from two
workers causes real corruption — double charges, lost writes, duplicated
shipments. We need: **at most one worker's effect lands on a given entity at
a time**, in a world where:

- workers can stall arbitrarily long (GC pause, blocked syscall, CPU steal)
  and resume with no idea time passed,
- the network can delay or reorder messages,
- clocks drift and step,
- job duration is bimodal — mostly fast, with a slow tail that's long enough
  to collide with any TTL you'd pick.

Every approach below is judged against that world, not the easy world where
workers never pause and clocks never drift.

---

## 1. Single-instance Redis (`SET NX PX` + TTL)

**How it works:** one Redis node. Acquire = `SET lock:{key} owner NX PX ttl`.
Release = compare-and-delete on `owner`. This is the inherited design from
the exercise brief.

**What it solves:** true mutual exclusion *of acquisition*, cheaply and
simply, as long as the single node is up. No consensus overhead — one round
trip to acquire, one to release.

**What it doesn't solve:**
- **Single point of failure.** If that Redis node goes down, every worker is
  blocked (can't acquire) or, worse, has no way to know if a lock it thinks
  it holds is still authoritative.
- **No durability guarantee on restart.** If Redis is configured for
  performance (no `fsync` per write, or async AOF), a crash-restart can lose
  a lock that was just granted, letting a second worker acquire while the
  first is still legitimately working — a much more mundane version of the
  fencing problem below.
- **Doesn't touch the core exercise problem at all.** Even with the node
  perfectly healthy, a worker that stalls past the TTL still hands its
  "slot" to someone else with no warning. Single-instance-vs-cluster is
  orthogonal to that; it's purely an availability upgrade, not a safety one.

**Verdict:** fine as the acquisition primitive, never sufficient by itself.

---

## 2. Redis with replication + Sentinel (primary/replica, automatic failover)

**How it works:** a primary Redis node replicates asynchronously to one or
more replicas. Sentinel monitors the primary and promotes a replica to
primary on failure.

**What it improves:** fixes the SPOF from §1 — the service stays available
across a node failure.

**What it doesn't solve — and this is the important part:** **replication
is asynchronous.** Sequence that actually happens in production:

1. Worker A `SET`s the lock on the primary. Primary acks A immediately —
   replication to the replica hasn't happened yet (it's async, that's the
   whole point of it being fast).
2. Primary crashes before the write replicates.
3. Sentinel promotes the replica to primary. The replica has never seen A's
   lock key.
4. Worker B calls `SET NX` on the new primary and succeeds — it has no idea
   A is still working.

Now A and B both believe they hold the lock, with **no TTL expiry or stall
involved at all** — this is a pure consistency failure introduced by
failover, layered on top of everything else. This is not a hypothetical;
it's the standard, acknowledged failure mode of Redis Sentinel/replica
promotion, and it's why Redis's own documentation does not claim Sentinel
gives you safe distributed locking on its own.

**Verdict:** solves availability, actively reintroduces a safety hole that
single-instance Redis didn't have (single instance is at least
*consistent*, just not *available*).

---

## 3. Redis Cluster

**How it works:** shards keys across multiple primaries (each with its own
replica), so no single node holds all the data and throughput scales
horizontally.

**What it improves:** throughput and horizontal scaling for a large key
space (many entities).

**What it doesn't solve:** exactly the §2 problem, per shard. Each shard is
still a primary/replica pair with async replication and the same
failover-loses-the-latest-write hole. Sharding changes *which* keys are
vulnerable, not *whether* they're vulnerable. It also adds cluster-specific
failure modes (resharding mid-operation, cross-slot operations) that are out
of scope here but worth knowing about if this were the chosen backend.

**Verdict:** a scaling answer to a question we didn't ask. Same consistency
gap as §2.

---

## 4. Redlock (multi-instance quorum on independent Redis nodes)

**How it works:** run N (typically 5) *independent* Redis instances — no
replication between them. To acquire, a client tries to `SET NX PX` the same
key/value on all N, using a tight timeout per attempt. If it succeeds on a
majority (⌈N/2⌉+1) within a bounded total time, and the remaining validity
time (TTL minus time spent acquiring) is still positive, it holds the lock.
This is proposed specifically to fix the §2/§3 failover problem by removing
replication from the picture entirely.

**What it improves over §2/§3:** no single failover event can silently lose
the lock, because no single node's state is authoritative — a majority must
agree, and majority agreement isn't lost by one node's async-replication gap.

**Drawbacks — this is the one people should know about, and it's the
reason I didn't pick it:**

- **It still depends on the same bounded-pause and bounded-clock-drift
  assumptions this whole exercise says we can't rely on.** Redlock's
  "validity time" reasoning (client computes remaining lock time as
  `TTL - elapsed`) assumes each client's local clock and its actual elapsed
  wall-clock time track each other closely, and that no client pauses for a
  long time *between* acquiring the lock and using it. A GC pause after
  acquisition breaks Redlock's safety argument exactly the same way it
  breaks single-instance Redis's — Redlock fixes the *failover* problem, not
  the *stalled worker* problem, which is the specific failure mode this
  exercise centers on.
- **Clock jumps break it directly.** If any node's system clock is stepped
  forward (NTP correction, manual admin action) between grant and check, the
  TTL can appear to expire early or late relative to real elapsed time on
  that node, undermining the quorum's agreement on "is this still valid."
- **This is a known, publicly litigated disagreement**, not just my opinion:
  Martin Kleppmann published a detailed critique arguing Redlock is neither
  as safe as a consensus system nor as simple/fast as single-instance Redis
  — it inherits real-world timing assumptions without the formal guarantees
  a consensus protocol provides in exchange. Redis's own author (antirez)
  published a rebuttal defending it as "good enough" for many use cases. I'm
  not taking a side on who's right in general — I'm noting that the
  disagreement itself is evidence Redlock does **not** give the crisp
  guarantee we need for a billing ledger, and reasonable experts dispute
  exactly how strong its guarantee is. For this exercise's stated operating
  conditions, I don't rely on it.
- **Higher cost, more moving parts, for a guarantee weaker than
  consensus-based options (§5).** 5 independent nodes to run and monitor, a
  more complex client-side quorum algorithm, and you still end up needing
  fencing tokens on top of it for the reasons above — so if you're adding
  fencing tokens anyway, Redlock's extra complexity buys less than it looks
  like.

**Verdict:** a real improvement over async-replicated Redis for the
failover-loses-a-write problem specifically, but it does not solve — and
was never designed to solve — the stalled-worker problem this exercise is
actually about, and it's contested even for what it does claim to solve.

---

## 5. Distributed coordination service — ZooKeeper / etcd

**How it works:** these are consensus-based systems (ZooKeeper uses Zab,
etcd uses Raft) — a cluster of nodes agrees on a single, linearizable,
replicated log of state changes, with real durability (a write isn't
acknowledged until a majority of nodes have durably persisted it). Locking
is built as a pattern on top of that primitive, not a single command:

- **ZooKeeper pattern:** each worker creates an **ephemeral, sequential**
  znode under `/locks/{entity}/`. The worker with the lowest sequence number
  holds the lock. Everyone else sets a watch on the next-lowest znode and
  waits. "Ephemeral" means the znode is tied to the client's **session** —
  if the client's session expires (heartbeat timeout, i.e. the client
  appears dead to the ensemble), the znode is automatically removed and the
  next worker in line is notified via its watch.
- **etcd pattern:** similar, via **leases** — a lock key is attached to a
  lease with a TTL; the lease must be kept alive with heartbeats (`KeepAlive`)
  or it expires and the key is removed, releasing the lock to the next
  waiter (etcd's `concurrency` package implements this directly).

**What it improves over Redis-family approaches:**
- **No failover-loses-a-write gap.** Because writes require majority
  durable acknowledgment before they're considered committed, there's no
  equivalent of "the primary acked but the replica never got it" — that
  scenario is what consensus protocols are specifically built to prevent.
  This closes the §2/§3 hole completely, not probabilistically.
- **Built-in "am I still the leader" signal without polling a TTL by hand.**
  The watch/notification model means the *next* worker finds out promptly
  when a lock is released or a session dies, rather than everyone polling.
- **Sessions are still time-based, so this does not remove the fundamental
  stalled-worker ambiguity** — a worker whose session times out because it
  stalled (not because it died) is treated exactly the same as a dead
  worker: its ephemeral node is removed and someone else proceeds. If the
  stalled worker resumes and acts as though it still holds the lock, you are
  back to needing the same fix as every approach above.

**Drawbacks:**
- **Latency.** Every lock operation is a consensus round trip across a
  quorum, not a single-node in-memory operation — noticeably slower than
  Redis for high-frequency, short-held locks.
- **Operational overhead.** Running and monitoring a ZooKeeper or etcd
  ensemble (typically 3 or 5 nodes) is a heavier operational commitment than
  a Redis instance, with its own tuning surface (session timeouts, snapshot/
  compaction behavior, quorum sizing).
- **Throughput ceiling.** Because every write goes through consensus,
  throughput is bounded by that, not by however many Redis instances you're
  willing to shard across.
- **Still needs fencing for the same reason as everything above.** The
  session-expiry mechanism is a more robust, better-engineered version of a
  TTL, but it is still a *time-based liveness heuristic*, and the exercise's
  operating conditions apply to it exactly as they apply to a Redis TTL: a
  session can time out for a worker that is merely slow, not dead.

**Verdict:** the strongest consistency guarantee of the backend options —
worth it if lock throughput requirements are modest and you want to
eliminate the failover-consistency gap entirely, not just reduce it. Does
**not** by itself solve the stalled-worker problem; still needs §6.

---

## 6. Fencing tokens — orthogonal to all of the above

This is the actual fix for the problem the exercise is built around, and
it's independent of which backend (§1–§5) issues the lock.

**The idea:** every successful lock acquisition returns not just "you have
it" but a **strictly monotonically increasing integer** — a fencing token.
- Redis: a separate `INCR fencing:{entity}` key that never expires and is
  bumped on every grant (this submission's approach).
- ZooKeeper: the znode's own sequence number *is* a fencing token for free —
  no extra key needed, since ephemeral-sequential nodes are already assigned
  a monotonically increasing sequence by the ensemble.
- etcd: the revision number returned with the lease/key serves the same
  purpose.

**The fix this enables:** the token is passed to the *resource* on every
write, not just used to decide who gets to attempt a write. The resource —
not the lock — keeps track of the highest token it has ever accepted per
entity, and **rejects any write bearing a token that isn't strictly higher.**

Worked through the exercise's own failure mode:
1. Worker A acquires the lock, gets token 5, then stalls past TTL/session
   expiry.
2. Worker B acquires the lock (TTL/session recovery, whichever backend),
   gets token 6, writes, releases.
3. Worker A resumes, unaware, and attempts its write carrying token 5.
4. The resource sees `5 <= 6` (its current high-water mark) and **rejects**
   the write. No corruption, regardless of how long A was stalled or how
   the lock backend arrived at handing the lock to B.

**The one hard requirement this creates:** the resource itself must be
fencing-aware — able to store and compare a token per entity as part of the
same atomic operation as the write (e.g.
`UPDATE ... WHERE last_token < :token`, all in one statement/transaction).
If the resource is a third-party system you don't control and can't attach
a token to, fencing can't be bolted on, and you're fully back to depending
on whichever backend's liveness assumptions — this is the single biggest
caveat on the whole approach, and it's worth flagging early in any real
integration, because it determines whether §1–§5 need to be "safe enough on
their own" or can lean on §6.

---

## 7. Comparison summary

| Approach | Fixes SPOF? | Fixes failover-loses-write? | Fixes stalled-worker (needs §6 regardless)? | Ops cost | Latency |
|---|---|---|---|---|---|
| §1 Single Redis | No | N/A (no replicas) | No | Lowest | Lowest |
| §2/§3 Redis + Sentinel/Cluster | Yes | **No** — async replication gap | No | Low–Med | Low |
| §4 Redlock | Yes | Yes (majority quorum) | **No** — same clock/pause assumptions | Medium | Medium |
| §5 ZooKeeper/etcd | Yes | Yes (consensus-durable) | No — session expiry is still time-based | High | Higher |
| §6 Fencing tokens | — (not a lock backend) | — | **Yes**, if the resource enforces it | Low (one extra field/comparison) | Negligible |

The pattern across every row above the last one: **no lock backend, however
sophisticated, removes the need for §6.** They differ in what they fix
*around* fencing (availability, failover consistency, ops complexity,
latency) — none of them, including ZooKeeper, removes the fundamental
ambiguity between "dead" and "paused," because that ambiguity isn't a bug in
any particular backend, it's inherent to any system that infers liveness
from elapsed time.

---

## 8. What this submission uses, and why

**Backend assumption:** single-instance Redis-like primitive (`SET NX PX`,
compare-and-delete, `INCR`) — §1. I did not implement Sentinel/Cluster
failover or a ZooKeeper/etcd ensemble in the submission; that's a scope cut,
stated in the design note, not a claim that §1 is sufficient in production.

**Reasoning for that scope cut specifically:** the exercise's operating
conditions are about worker liveness and clock imperfection, not backend
failover — §2 through §5 differ from each other mainly in how they handle
*backend* failure and consistency, which is a real but separate axis from
the worker-stall problem the brief centers on. Solving §6 correctly is what
the brief is actually testing, and §6 works identically regardless of which
backend from §1–§5 sits underneath it. If I had to name a production
backend today, I'd lean **ZooKeeper or etcd** for a billing-ledger-grade use
case specifically because it removes the failover-consistency gap
(§2/§3's real weakness) entirely rather than probabilistically (§4), and
its ephemeral-sequential-node sequence number gives you a fencing token for
free — but that's a separate, defensible choice from the one this exercise
is grading, which is §6.

**The one thing that doesn't move regardless of backend choice:** the
*resource* must enforce the fencing token. That's implemented in
`ProtectedResource.apply()` in the submission, and it's backend-agnostic by
construction — swapping `CoordinationService` for a real Redis client, or
replacing `DistributedLock` with a ZooKeeper-ephemeral-sequential-node
implementation, changes nothing about how the resource validates tokens.
