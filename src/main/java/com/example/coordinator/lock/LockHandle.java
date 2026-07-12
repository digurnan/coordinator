package com.example.coordinator.lock;

import java.time.Duration;

/**
 * Opaque proof of lock ownership returned by {@link EntityLock#tryAcquire}.
 * The fence token monotonically increases per entity and lets the protected
 * resource reject stale writes after TTL expiry or partition healing.
 */
public record LockHandle(
        String entityKey,
        String token,
        long fenceToken,
        Duration ttl
) {}
