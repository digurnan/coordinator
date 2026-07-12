package com.example.coordinator.resource;

/**
 * A shared external resource where concurrent mutation on the same entity
 * causes visible corruption (double charges, lost writes, etc.).
 */
public interface ProtectedResource {

    /**
     * Apply a mutation guarded by the lock fence token. Returns false when the
     * token is stale — the write must not be committed.
     */
    boolean mutate(String entityKey, long fenceToken, long delta);

    /** Total successful mutations applied. */
    long appliedMutations();

    /** Writes rejected because the fence token was too old. */
    long rejectedStaleWrites();

    /** Times two workers touched the entity concurrently (should stay 0). */
    long corruptionEvents();

    /** Peak concurrent accessors observed (should stay at 1). */
    int maxConcurrentAccess();

    /** Current balance for an entity (for test assertions). */
    long balance(String entityKey);
}
