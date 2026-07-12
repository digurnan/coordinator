package coordinator;

import java.util.Optional;
import java.util.UUID;

/**
 * A lock on a single entity key, built on top of a CoordinationBackend
 * (in-memory stand-in, or real Redis via RedisCoordinationService — this
 * class doesn't know or care which).
 *
 * This class alone gives you MUTUAL EXCLUSION OF ACQUISITION: at most one
 * caller can hold "lock:{entityKey}" at any instant the coordination
 * service's clock is concerned with. It does NOT give you mutual exclusion
 * of the *critical section's real-world effects* — a worker that stalls
 * (GC pause, blocked syscall, CPU steal) past the TTL can resume and act as
 * if it still holds the lock, while a second worker has legitimately
 * acquired it. See the design note, "the failure you can't prevent at the
 * lock."
 *
 * The fencing token is how the real safety property gets restored: it's
 * handed to the protected resource on every write, and the resource itself
 * (not the lock) rejects any write whose token is not the highest one it has
 * seen for that entity. The lock's job is to make contention *rare*, not to
 * be the sole source of truth for correctness.
 */
public class DistributedLock {

    private final CoordinationBackend svc;

    public DistributedLock(CoordinationBackend svc) {
        this.svc = svc;
    }

    /**
     * Try once to acquire the lock on entityKey with the given TTL.
     * Returns empty if someone else currently holds it.
     */
    public Optional<LockGrant> tryAcquire(String entityKey, long ttlMs) {
        String ownerId = UUID.randomUUID().toString();
        boolean got = svc.setNxPx(lockKey(entityKey), ownerId, ttlMs);
        if (!got) {
            return Optional.empty();
        }
        // Fencing counter is independent of the lock key: it never expires,
        // never resets, and keeps climbing even across TTL-driven takeovers.
        long token = svc.incr(fencingKey(entityKey));
        return Optional.of(new LockGrant(entityKey, ownerId, token));
    }

    /**
     * Block (with polling backoff) until the lock is acquired or deadlineNanos
     * (System.nanoTime()-based) passes. Returns empty on timeout.
     */
    public Optional<LockGrant> acquire(String entityKey, long ttlMs, long deadlineNanos) {
        while (true) {
            Optional<LockGrant> grant = tryAcquire(entityKey, ttlMs);
            if (grant.isPresent()) {
                return grant;
            }
            if (System.nanoTime() > deadlineNanos) {
                return Optional.empty();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    /**
     * Release a grant. Returns true iff this call actually removed the lock
     * this worker holds. Returns false if the lock had already expired
     * and/or been taken over by someone else — that is NOT an error to throw
     * on; it is exactly the signal that this worker's view of "I hold the
     * lock" was stale, and it must not have trusted its own writes without
     * fencing (see ProtectedResource).
     *
     * Critically: release NEVER does an unconditional DEL. An unconditional
     * DEL would let a stalled worker delete a lock that a different, live
     * worker currently and legitimately holds — a second, independent bug on
     * top of the staleness problem.
     */
    public boolean release(LockGrant grant) {
        return svc.compareDelete(lockKey(grant.entityKey), grant.ownerId);
    }

    private static String lockKey(String entityKey) {
        return "lock:" + entityKey;
    }

    private static String fencingKey(String entityKey) {
        return "fencing:" + entityKey;
    }
}
