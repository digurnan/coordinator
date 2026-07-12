package coordinator;

/**
 * The three atomic primitives DistributedLock needs from whatever
 * coordination backend is underneath it. Implemented by:
 *   - InMemoryCoordinationService: in-process stand-in, used in the
 *     original submission and in the no-Redis-required test scenarios.
 *   - RedisCoordinationService: real Redis, via Jedis. See that class for
 *     the exact command each method maps to.
 *
 * DistributedLock and ProtectedResource depend only on this interface, so
 * swapping backends never touches lock/fencing logic — that's the point of
 * having it.
 */
public interface CoordinationBackend {

    /** Atomic SET key value NX PX ttlMs. Returns true iff the key was set. */
    boolean setNxPx(String key, String value, long ttlMs);

    /**
     * Atomic "if GET(key) == expectedValue: DEL(key)". Must be a single
     * atomic operation on the backend (a Lua EVAL on real Redis) — never a
     * GET followed by a separate DEL from the client.
     */
    boolean compareDelete(String key, String expectedValue);

    /**
     * Atomic INCR. No TTL parameter on purpose: fencing counters must never
     * expire and must never go backwards, independent of what happens to
     * any given lock key.
     */
    long incr(String key);

    /** Plain GET, for inspection/debugging. */
    String get(String key);
}
