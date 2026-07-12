# The Coordinator — submission

## Layout

```
src/coordinator/
  CoordinationBackend.java         interface: the 3 atomic primitives DistributedLock needs
  InMemoryCoordinationService.java in-process stand-in implementing CoordinationBackend
  RedisCoordinationService.java    real Redis implementation, via Jedis (implements CoordinationBackend)
  LockGrant.java                   value object returned by a successful acquire
  DistributedLock.java             acquire/release + fencing token issuance — backend-agnostic
  ProtectedResource.java           simulated external resource; enforces fencing tokens
  Simulation.java                  3 scenarios against the in-memory backend — no dependencies
  RedisSimulation.java             same 3 scenarios against real Redis — needs Jedis + a Redis instance
pom.xml                            Maven config, only needed for the Redis path
docker-compose.yml                 one-command local Redis for the Redis path
DESIGN_NOTE.md
DISTRIBUTED_LOCKING_APPROACHES.md
```

`DistributedLock` and `ProtectedResource` depend only on the `CoordinationBackend`
interface, so the lock/fencing logic is identical in both paths below — only
which class implements `CoordinationBackend` changes.

## Path A: no dependencies (in-memory backend)

```
javac -d out src/coordinator/CoordinationBackend.java \
             src/coordinator/InMemoryCoordinationService.java \
             src/coordinator/LockGrant.java \
             src/coordinator/DistributedLock.java \
             src/coordinator/ProtectedResource.java \
             src/coordinator/Simulation.java
java -cp out coordinator.Simulation
```

(Don't use a `*.java` wildcard here — that will also try to compile the
Redis-dependent files below, which need Jedis on the classpath.)

## Path B: real Redis, via Jedis + Maven

```
docker compose up -d          # starts Redis on localhost:6379
mvn compile exec:java         # compiles everything (incl. Jedis-based classes) and runs RedisSimulation
```

To point at a different host/port:
```
mvn compile exec:java -Dexec.args="myredishost 6380"
```

**Honesty note:** Path B was written carefully against the standard, stable
Jedis 5.x API (`SET NX PX` via `SetParams`, a Lua `EVAL` for the atomic
compare-and-delete unlock, plain `INCR` for fencing) but could not be
executed in the sandbox this was built in — no network access to fetch the
Jedis jar or a Redis package, and no Redis binary pre-installed. Path A
(the in-memory backend) *was* compiled and run repeatedly and is the one
the design note's claims are verified against. Path B is the same logic,
mechanically mapped to real Redis commands — I'd run it against the
docker-compose Redis before calling it production-ready, and would
recommend you do the same before trusting it further than "the code
reviews as correct."

## What it demonstrates (both paths, identical scenarios)

1. **Normal contention** — 20 workers × 50 ops against one entity, no
   pathological timing. Asserts the final balance exactly matches expected.
2. **Stalled worker, fencing disabled** — reproduces the inherited design
   ("acquire TTL lock, do work, release") under a GC-pause-like stall that
   outlives the TTL. Reproduces a real double charge.
3. **Stalled worker, fencing enabled** — identical timing, but the resource
   rejects the stale write using its fencing token. Balance stays correct.

See `DESIGN_NOTE.md` for the required design-note sections, and
`DISTRIBUTED_LOCKING_APPROACHES.md` for how this compares against
Sentinel/Cluster, Redlock, and ZooKeeper/etcd as backend choices.
