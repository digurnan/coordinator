package coordinator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stands in for the real external resource — a billing ledger, a document
 * store, an inventory record. Modeled here as a per-entity integer balance
 * (e.g. "amount charged"), which is enough to make corruption ("double
 * charge") directly observable as a wrong number.
 *
 * The important thing about this class: when fencingEnforced is true, IT
 * enforces the safety property, not DistributedLock. A write is only applied
 * if its fencing token is strictly greater than the highest token this
 * entity has ever seen applied. This is what catches a stale worker that
 * still believes it holds the lock.
 *
 * A real resource would implement this the same way conceptually: the write
 * path (e.g. an UPDATE ... WHERE fencing_token < :token, or a document store
 * that stores last_applied_token per document) must be fencing-aware. If the
 * resource itself cannot compare tokens, fencing cannot help you, and you
 * are fully dependent on TTL/liveness assumptions holding — see the design
 * note.
 */
public class ProtectedResource {

    public static final class RejectedWrite {
        public final String worker;
        public final String entityKey;
        public final long token;
        public final long currentMaxToken;

        RejectedWrite(String worker, String entityKey, long token, long currentMaxToken) {
            this.worker = worker;
            this.entityKey = entityKey;
            this.token = token;
            this.currentMaxToken = currentMaxToken;
        }

        @Override
        public String toString() {
            return "REJECTED stale write: worker=" + worker + " entity=" + entityKey
                    + " token=" + token + " (current max=" + currentMaxToken + ")";
        }
    }

    private final Map<String, Long> lastAppliedToken = new HashMap<>();
    private final Map<String, Integer> balance = new HashMap<>();
    private final List<RejectedWrite> rejectedWrites = new ArrayList<>();
    private final ReentrantLock mu = new ReentrantLock();

    /**
     * Apply a write of `delta` to entityKey, attributed to `worker`, carrying
     * fencing token `token`. Returns true iff the write was applied.
     *
     * If fencingEnforced is false, this reproduces the *inherited* design:
     * "the lock says I'm allowed, so I just write." That is the bug.
     */
    public boolean apply(String entityKey, long token, String worker, int delta, boolean fencingEnforced) {
        mu.lock();
        try {
            long currentMax = lastAppliedToken.getOrDefault(entityKey, 0L);
            if (fencingEnforced && token <= currentMax) {
                rejectedWrites.add(new RejectedWrite(worker, entityKey, token, currentMax));
                return false;
            }
            lastAppliedToken.put(entityKey, Math.max(currentMax, token));
            balance.merge(entityKey, delta, Integer::sum);
            return true;
        } finally {
            mu.unlock();
        }
    }

    public int getBalance(String entityKey) {
        mu.lock();
        try {
            return balance.getOrDefault(entityKey, 0);
        } finally {
            mu.unlock();
        }
    }

    public List<RejectedWrite> getRejectedWrites() {
        mu.lock();
        try {
            return new ArrayList<>(rejectedWrites);
        } finally {
            mu.unlock();
        }
    }
}
