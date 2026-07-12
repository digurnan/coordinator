# The Coordinator — Design Note

**Assumption:** Redis 7 single primary (or Redis Cluster with hash-tagged entity keys). Workers call a small lock component before touching a shared external resource. The resource stores the last accepted **fence token** per entity and rejects writes with an older token.

---

## The guarantee

**What we guarantee:** For a given entity key, at most one worker holds the Redis lock at any time, and the protected resource accepts mutations only when accompanied by a fence token strictly greater than the last committed token for that entity.

**Under these assumptions:**

1. Redis `SET key token NX PX ttl` and our Lua release/renew scripts execute atomically on the primary.
2. The protected resource checks the fence token on every write (not just at lock acquisition).
3. Workers stop writing once they observe lock loss (renewal failure) or receive a stale-write rejection.

**What follows:** No two workers mutate the same entity concurrently. A worker that loses its lock (TTL expiry, GC pause, partition) cannot corrupt committed state — its late write is dropped at the resource boundary.

**What we do *not* guarantee:** Exactly-once job execution. A worker may run work twice (retry after apparent failure). We guarantee **serializable, non-corrupting** access, not idempotency of side effects.

---

## The failure you can't prevent at the lock

**Worst case:** A worker holds the lock, mutates the resource, then dies **before** release. Until TTL expires, no other worker can proceed on that entity. If TTL is long, this is **availability loss** (not corruption).

**Second worst case (partially mitigated):** Worker A's lock expires while A is still running. Worker B acquires and commits. Worker A finishes and attempts a write. **Caught at the resource:** A's fence token ≤ B's accepted token → write rejected. Without fencing, this is the classic double-charge bug.

**Uncaught at the lock alone:** External side effects outside the fenced resource (email sent, webhook fired, non-idempotent third-party API). The lock does not roll those back. Production mitigation: idempotency keys on outbound calls, transactional outbox, or compensating actions — enforced **above** the lock layer.

**Network reordering:** A stale request can arrive late at the resource. Fencing catches it if the token is old. If the resource ignores fencing, the lock is insufficient — stated explicitly because this is a common gap.

---

## The TTL decision

There is no TTL that simultaneously minimizes (a) zombie lock duration and (b) long-job false expiry. We choose based on **job duration distribution** and **acceptable stall time**.

| Extreme | What breaks |
|---|---|
| TTL too short | Long jobs lose the lock mid-flight; without renewal, another worker can start → corruption **unless** fencing + renewal |
| TTL too long | Crashed worker blocks the entity for the full TTL → throughput collapse on hot keys |

**What we actually do:**

1. **Base TTL** ≈ p99 job duration + small buffer (here: 800 ms for the harness; production would come from metrics).
2. **Renewal heartbeat** every TTL/3 while work is in progress — long jobs keep the lock without inflating zombie hold time to "max job ever."
3. **Fence tokens** as the backstop when renewal fails or a zombie wakes up after TTL.

**It depends on:**

- **Max acceptable pause** on hot entities → caps upper bound of TTL if renewal is disabled.
- **p99.9 job length** → sets minimum safe TTL when renewal is unavailable.
- **Resource cost of a stale retry** → if cheap, prefer shorter TTL + fencing; if expensive, invest in reliable renewal and lock-loss detection in the worker.

If renewal itself is unavailable (resource cannot support periodic extend), the honest answer is: pick TTL for the dead-worker case and accept that jobs longer than TTL require **splitting work** or **checkpointing** — the lock alone cannot solve both tensions.

---

## What we'd do with more time / in production

**Cut corners in this submission:**

- Single Redis node, not Redlock across independent masters.
- In-memory ledger stand-in instead of a real document store / payment API.
- Renewal runs in-process; no watchdog tied to JVM safepoints or distributed lease tables.
- No metrics, alerting, or lock-contention dashboards.

**Production next steps:**

1. **Observability:** lock acquire latency, renewal failures, stale-write rejections, hold time histogram — alert on renewal failure rate.
2. **Redlock or etcd leases** if Redis failover could grant two holders; document the split-brain window.
3. **Idempotent job IDs** at the worker layer so retries do not double-charge even when fencing is mis-implemented downstream.
4. **Lock wait queues** with backoff/jitter instead of hot-spin on contested keys.
5. **Explicit lock-loss handling** in workers: abort in-flight work, do not flush buffers after `renew()` fails.
6. **Runbooks** for "entity stuck" — manual lock inspection/delete with audit, only when fence confirms no live holder.

---

## Component map

| Piece | Role |
|---|---|
| `RedisEntityLock` | `SET NX PX` acquire, token-verified release/renew, monotonic fence via `INCR lock:{entity}:fence` |
| `Worker` | acquire → optional renewal → fenced mutate → release |
| `InMemoryLedger` | Simulated resource; tracks corruption, rejects stale fence tokens |
| `CoordinatorSimulation` | Contention, zombie-after-TTL, long-job-with-renewal scenarios |

Run the harness:

```bash
mvn test -Dtest=CoordinatorSimulationTest,RedisEntityLockTest
```

Requires Docker (Testcontainers Redis).
