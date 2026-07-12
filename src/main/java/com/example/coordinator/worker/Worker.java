package com.example.coordinator.worker;

import com.example.coordinator.lock.EntityLock;
import com.example.coordinator.lock.LockHandle;
import com.example.coordinator.resource.ProtectedResource;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker that acquires an entity lock, optionally renews it for long jobs, touches
 * the protected resource with a fence token, then releases.
 */
public class Worker {

    private final String workerId;
    private final EntityLock entityLock;
    private final ProtectedResource resource;
    private final ScheduledExecutorService renewExecutor;

    public Worker(
            String workerId,
            EntityLock entityLock,
            ProtectedResource resource,
            ScheduledExecutorService renewExecutor
    ) {
        this.workerId = workerId;
        this.entityLock = entityLock;
        this.resource = resource;
        this.renewExecutor = renewExecutor;
    }

    public String workerId() {
        return workerId;
    }

    /**
     * @return true if the mutation was applied under a valid lock
     */
    public boolean processJob(
            String entityKey,
            long delta,
            Duration workDuration,
            Duration acquireWait,
            boolean renewWhileWorking
    ) {
        Optional<LockHandle> maybeLock = entityLock.tryAcquire(entityKey, acquireWait);
        if (maybeLock.isEmpty()) {
            return false;
        }

        LockHandle lock = maybeLock.get();
        ScheduledFuture<?> renewal = null;
        AtomicBoolean lockLost = new AtomicBoolean(false);

        if (renewWhileWorking) {
            long renewIntervalMs = Math.max(1L, lock.ttl().toMillis() / 3);
            renewal = renewExecutor.scheduleAtFixedRate(
                    () -> {
                        if (!entityLock.renew(lock)) {
                            lockLost.set(true);
                        }
                    },
                    renewIntervalMs,
                    renewIntervalMs,
                    TimeUnit.MILLISECONDS
            );
        }

        try {
            if (!workDuration.isZero()) {
                sleepQuietly(workDuration);
            }
            if (lockLost.get()) {
                return false;
            }
            return resource.mutate(entityKey, lock.fenceToken(), delta);
        } finally {
            if (renewal != null) {
                renewal.cancel(true);
            }
            entityLock.release(lock);
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
