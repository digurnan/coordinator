package com.example.coordinator.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisEntityLockTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisEntityLock entityLock;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        template.getConnectionFactory().getConnection().serverCommands().flushAll();
        entityLock = new RedisEntityLock(template, Duration.ofMillis(500));
    }

    @Test
    void secondAcquireFailsWhileLockHeld() {
        Optional<LockHandle> first = entityLock.tryAcquire("entity-a", Duration.ZERO);
        Optional<LockHandle> second = entityLock.tryAcquire("entity-a", Duration.ZERO);

        assertTrue(first.isPresent());
        assertFalse(second.isPresent());
        assertTrue(entityLock.release(first.get()));
    }

    @Test
    void releaseUsesTokenVerification() {
        Optional<LockHandle> handle = entityLock.tryAcquire("entity-b", Duration.ZERO);
        assertTrue(handle.isPresent());

        LockHandle forged = new LockHandle(handle.get().entityKey(), "wrong-token", handle.get().fenceToken(), handle.get().ttl());
        assertFalse(entityLock.release(forged));
        assertTrue(entityLock.release(handle.get()));
    }

    @Test
    void renewExtendsOwnership() throws InterruptedException {
        Optional<LockHandle> handle = entityLock.tryAcquire("entity-c", Duration.ZERO);
        assertTrue(handle.isPresent());

        Thread.sleep(300);
        assertTrue(entityLock.renew(handle.get()));

        Thread.sleep(300);
        Optional<LockHandle> contender = entityLock.tryAcquire("entity-c", Duration.ZERO);
        assertFalse(contender.isPresent());

        assertTrue(entityLock.release(handle.get()));
    }

    @Test
    void fenceTokensIncreaseMonotonically() {
        Optional<LockHandle> first = entityLock.tryAcquire("entity-d", Duration.ZERO);
        assertTrue(first.isPresent());
        assertTrue(entityLock.release(first.get()));

        Optional<LockHandle> second = entityLock.tryAcquire("entity-d", Duration.ZERO);
        assertTrue(second.isPresent());
        assertTrue(second.get().fenceToken() > first.get().fenceToken());
        assertTrue(entityLock.release(second.get()));
    }
}
