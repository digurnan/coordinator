package coordinator;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;

/**
 * Real Redis implementation of CoordinationBackend, via Jedis.
 *
 * This is a mechanical mapping — no new logic versus InMemoryCoordinationService,
 * just real Redis commands instead of an in-process map:
 *
 *   setNxPx(key, value, ttlMs)
 *     -> SET key value NX PX ttlMs
 *        A single Redis command; NX+PX together are atomic on the server
 *        side, so there's no separate "check then set" race to worry about.
 *
 *   compareDelete(key, expectedValue)
 *     -> EVAL of a 2-line Lua script that does GET-then-DEL as one atomic
 *        server-side operation. This is the standard "safe unlock" pattern
 *        recommended in Redis's own distributed-locking documentation —
 *        doing this as two separate round trips (GET, then DEL) from the
 *        client would reintroduce exactly the race this method exists to
 *        prevent (another worker's lock could be deleted in the gap between
 *        the two calls).
 *
 *   incr(key)
 *     -> INCR key
 *        Redis's INCR is atomic and needs no TTL argument, which is exactly
 *        the fencing-counter semantics we need: it never expires, never
 *        resets, keeps climbing across TTL-driven lock handovers.
 *
 * Requires a running Redis instance (redis-server locally, or the
 * docker-compose.yml in this repo: `docker compose up -d`) and the Jedis
 * client on the classpath, pulled in via pom.xml. Uses a JedisPool rather
 * than a single Jedis connection because Jedis connections are NOT
 * thread-safe, and DistributedLock is used concurrently by many worker
 * threads in the simulation.
 *
 * NOTE ON THIS SUBMISSION'S TESTING: this class could not be executed in
 * the sandbox this was authored in (no network access to fetch the Jedis
 * jar, no local redis-server binary available, and package installation is
 * blocked). The command mappings above are standard, well-documented Redis
 * operations and the logic is identical to InMemoryCoordinationService,
 * which WAS compiled and run (see Simulation.java, all three scenarios
 * pass repeatedly). This file should be treated as reviewed-but-not-yet-
 * run-against-real-Redis by me; running `mvn compile exec:java` against a
 * local Redis (e.g. via the provided docker-compose.yml) is the next step,
 * and I'd do that before calling this production-ready.
 */
public class RedisCoordinationService implements CoordinationBackend, AutoCloseable {

    private static final String COMPARE_DELETE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
          + "return redis.call('del', KEYS[1]) "
          + "else return 0 end";

    private final JedisPool pool;

    public RedisCoordinationService(String host, int port) {
        this.pool = new JedisPool(host, port);
    }

    public RedisCoordinationService(JedisPool pool) {
        this.pool = pool;
    }

    @Override
    public boolean setNxPx(String key, String value, long ttlMs) {
        try (Jedis jedis = pool.getResource()) {
            SetParams params = SetParams.setParams().nx().px(ttlMs);
            String result = jedis.set(key, value, params);
            return "OK".equals(result);
        }
    }

    @Override
    public boolean compareDelete(String key, String expectedValue) {
        try (Jedis jedis = pool.getResource()) {
            Object result = jedis.eval(
                    COMPARE_DELETE_SCRIPT,
                    Collections.singletonList(key),
                    Collections.singletonList(expectedValue));
            return (result instanceof Long) && (Long) result == 1L;
        }
    }

    @Override
    public long incr(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incr(key);
        }
    }

    @Override
    public String get(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    @Override
    public void close() {
        pool.close();
    }
}
