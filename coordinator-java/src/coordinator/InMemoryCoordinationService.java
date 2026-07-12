package coordinator;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An in-memory stand-in for a Redis-like coordination service.
 *
 * ASSUMPTION (stated explicitly): the real coordination backend is Redis
 * (single primary, or a Redis Cluster with equivalent single-writer-per-key
 * semantics). This class exposes exactly the primitives you'd get from real
 * Redis commands, with the same atomicity guarantees:
 *
 *   SET key value NX PX ttlMs                          -> setNxPx()
 *   (GET key; if value == expected: DEL key), atomic    -> compareDelete()
 *   INCR key (no TTL, never resets)                     -> incr()
 *
 * Everything here is guarded by a single process-wide lock purely to
 * simulate "the backend executes each of these commands atomically" — this
 * is NOT the distributed lock itself. Do not confuse this internal mutex
 * with DistributedLock, built on top of it. In real Redis this atomicity
 * comes from Redis being single-threaded per command, or from a Lua script
 * (EVAL) executing atomically — not from a client-side mutex.
 *
 * Deliberately NOT modeled: Redis Cluster/Sentinel failover, replication
 * lag, or split-brain between a primary and a promoted replica. That's a
 * real gap — see the design note ("what you'd do with more time").
 */
public class InMemoryCoordinationService implements CoordinationBackend {

    private static final class Entry {
        final String value;
        final Long expiresAtNanos; // null = no TTL (used for fencing counters)

        Entry(String value, Long expiresAtNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    private final Map<String, Entry> store = new HashMap<>();
    private final ReentrantLock mu = new ReentrantLock();

    private boolean isExpired(Entry e, long nowNanos) {
        return e.expiresAtNanos != null && e.expiresAtNanos <= nowNanos;
    }

    /** Atomic SET key value NX PX ttlMs. Returns true iff the key was set. */
    @Override
    public boolean setNxPx(String key, String value, long ttlMs) {
        long now = System.nanoTime();
        mu.lock();
        try {
            Entry existing = store.get(key);
            if (existing != null && !isExpired(existing, now)) {
                return false;
            }
            long expiresAt = now + ttlMs * 1_000_000L;
            store.put(key, new Entry(value, expiresAt));
            return true;
        } finally {
            mu.unlock();
        }
    }

    /**
     * Atomic "if GET(key) == expectedValue: DEL(key)".
     * This is the safe-unlock primitive: a worker must only ever delete a
     * lock it still owns, never whatever lock happens to be sitting there
     * now (which might belong to someone else after TTL expiry + re-acquire).
     * In real Redis this is a single Lua EVAL, not a GET followed by a DEL.
     */
    @Override
    public boolean compareDelete(String key, String expectedValue) {
        long now = System.nanoTime();
        mu.lock();
        try {
            Entry existing = store.get(key);
            if (existing == null || isExpired(existing, now)) {
                return false;
            }
            if (!existing.value.equals(expectedValue)) {
                return false;
            }
            store.remove(key);
            return true;
        } finally {
            mu.unlock();
        }
    }

    @Override
    public String get(String key) {
        long now = System.nanoTime();
        mu.lock();
        try {
            Entry existing = store.get(key);
            if (existing == null || isExpired(existing, now)) {
                return null;
            }
            return existing.value;
        } finally {
            mu.unlock();
        }
    }

    /**
     * Atomic INCR. Used for fencing tokens. Deliberately has no ttlMs
     * parameter — fencing counters must never expire and must never go
     * backwards, independent of whatever happens to any given lock key.
     */
    @Override
    public long incr(String key) {
        mu.lock();
        try {
            Entry existing = store.get(key);
            long newVal = (existing == null) ? 1L : Long.parseLong(existing.value) + 1L;
            store.put(key, new Entry(Long.toString(newVal), null));
            return newVal;
        } finally {
            mu.unlock();
        }
    }
}
