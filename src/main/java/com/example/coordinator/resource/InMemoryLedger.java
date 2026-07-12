package com.example.coordinator.resource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory billing ledger used by the simulation harness. Tracks the highest
 * fence token accepted per entity so a zombie worker cannot corrupt state after
 * losing its lock.
 */
public class InMemoryLedger implements ProtectedResource {

    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAcceptedFence = new ConcurrentHashMap<>();
    private final AtomicLong appliedMutations = new AtomicLong();
    private final AtomicLong rejectedStaleWrites = new AtomicLong();
    private final AtomicLong corruptionEvents = new AtomicLong();
    private final AtomicInteger concurrentAccess = new AtomicInteger();
    private final AtomicInteger maxConcurrentAccess = new AtomicInteger();

    @Override
    public boolean mutate(String entityKey, long fenceToken, long delta) {
        int active = concurrentAccess.incrementAndGet();
        maxConcurrentAccess.updateAndGet(current -> Math.max(current, active));
        if (active > 1) {
            corruptionEvents.incrementAndGet();
        }

        try {
            long lastFence = lastAcceptedFence.getOrDefault(entityKey, 0L);
            if (fenceToken <= lastFence) {
                rejectedStaleWrites.incrementAndGet();
                return false;
            }

            balances.merge(entityKey, delta, Long::sum);
            lastAcceptedFence.put(entityKey, fenceToken);
            appliedMutations.incrementAndGet();
            return true;
        } finally {
            concurrentAccess.decrementAndGet();
        }
    }

    @Override
    public long appliedMutations() {
        return appliedMutations.get();
    }

    @Override
    public long rejectedStaleWrites() {
        return rejectedStaleWrites.get();
    }

    @Override
    public long corruptionEvents() {
        return corruptionEvents.get();
    }

    @Override
    public int maxConcurrentAccess() {
        return maxConcurrentAccess.get();
    }

    @Override
    public long balance(String entityKey) {
        return balances.getOrDefault(entityKey, 0L);
    }

    public void reset() {
        balances.clear();
        lastAcceptedFence.clear();
        appliedMutations.set(0);
        rejectedStaleWrites.set(0);
        corruptionEvents.set(0);
        concurrentAccess.set(0);
        maxConcurrentAccess.set(0);
    }
}
