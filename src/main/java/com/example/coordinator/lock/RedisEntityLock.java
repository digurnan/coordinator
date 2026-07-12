package com.example.coordinator.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed entity lock using SET NX PX, token-verified release/renewal,
 * and a monotonic fence counter per entity.
 *
 * <p>Assumption: single Redis primary (or Redis Cluster with hash-tag on entity key).
 * We do not implement full Redlock across independent masters.
 */
public class RedisEntityLock implements EntityLock {

    private static final String KEY_PREFIX = "lock:";

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            """
            local lockKey = KEYS[1]
            local fenceKey = lockKey .. ':fence'
            if redis.call('GET', lockKey) then
                return {0, 0}
            end
            local fence = redis.call('INCR', fenceKey)
            local acquired = redis.call('SET', lockKey, ARGV[1], 'NX', 'PX', ARGV[2])
            if acquired then
                return {1, fence}
            end
            return {0, 0}
            """,
            List.class
    );

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate redis;
    private final Duration lockTtl;

    public RedisEntityLock(StringRedisTemplate redis) {
        this(redis, Duration.ofSeconds(2));
    }

    public RedisEntityLock(StringRedisTemplate redis, Duration lockTtl) {
        this.redis = redis;
        this.lockTtl = lockTtl;
    }

    @Override
    public Optional<LockHandle> tryAcquire(String entityKey, Duration waitTimeout) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        do {
            Optional<LockHandle> acquired = attemptAcquire(entityKey);
            if (acquired.isPresent()) {
                return acquired;
            }
            if (waitTimeout.isZero()) {
                return Optional.empty();
            }
            sleepQuietly(25);
        } while (System.nanoTime() < deadline);
        return Optional.empty();
    }

    @Override
    public boolean renew(LockHandle handle) {
        Long renewed = redis.execute(
                RENEW_SCRIPT,
                List.of(lockKey(handle.entityKey())),
                handle.token(),
                String.valueOf(handle.ttl().toMillis())
        );
        return renewed != null && renewed == 1L;
    }

    @Override
    public boolean release(LockHandle handle) {
        Long released = redis.execute(
                RELEASE_SCRIPT,
                List.of(lockKey(handle.entityKey())),
                handle.token()
        );
        return released != null && released == 1L;
    }

    Duration lockTtl() {
        return lockTtl;
    }

    private Optional<LockHandle> attemptAcquire(String entityKey) {
        String token = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        List<Long> result = redis.execute(
                ACQUIRE_SCRIPT,
                List.of(lockKey(entityKey)),
                token,
                String.valueOf(lockTtl.toMillis())
        );
        if (result == null || result.size() < 2 || result.get(0) != 1L) {
            return Optional.empty();
        }
        return Optional.of(new LockHandle(entityKey, token, result.get(1), lockTtl));
    }

    private String lockKey(String entityKey) {
        return KEY_PREFIX + entityKey;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
