package com.example.coordinator.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Mutual exclusion on a single entity key before touching a shared resource.
 */
public interface EntityLock {

    /**
     * Attempt to acquire the lock, optionally waiting up to {@code waitTimeout}.
     */
    Optional<LockHandle> tryAcquire(String entityKey, Duration waitTimeout);

    /**
     * Extend TTL while the caller still holds a valid lock. Returns false if the
     * lock was lost (expired, stolen, or never held).
     */
    boolean renew(LockHandle handle);

    /**
     * Release the lock if this handle still owns it.
     */
    boolean release(LockHandle handle);
}
