package coordinator;

/**
 * A successful lock acquisition. Carries both:
 *   - ownerId: a random value unique to this specific acquisition, used only
 *              to safely release (compare-delete) THIS grant and no other.
 *   - fencingToken: a monotonically increasing number for this entity key,
 *              handed to the protected resource on every write. See
 *              DistributedLock for why this exists — it's the actual
 *              mechanism that makes the system safe, not the lock itself.
 */
public final class LockGrant {
    public final String entityKey;
    public final String ownerId;
    public final long fencingToken;

    public LockGrant(String entityKey, String ownerId, long fencingToken) {
        this.entityKey = entityKey;
        this.ownerId = ownerId;
        this.fencingToken = fencingToken;
    }

    @Override
    public String toString() {
        return "LockGrant{entity=" + entityKey + ", owner=" + ownerId.substring(0, 8)
                + ", fencingToken=" + fencingToken + "}";
    }
}
