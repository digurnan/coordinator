package coordinator;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Three scenarios, run against the same CoordinationBackend + DistributedLock:
 *
 *  1. normalContention   - many workers hammering one entity under ordinary
 *                          conditions (no stalls). Demonstrates plain mutual
 *                          exclusion holds.
 *
 *  2. stalledWorker(false) - one worker takes a GC-pause-like stall AFTER
 *                          acquiring the lock, long enough for its TTL to
 *                          expire and a second worker to take over.
 *                          fencingEnforced=false reproduces the inherited
 *                          design exactly ("gets the lock, does the work,
 *                          releases") and produces a real double charge.
 *
 *  3. stalledWorker(true)  - identical timing, but the resource enforces
 *                          fencing tokens. The stale write is rejected and
 *                          the balance stays correct. Also shows the stale
 *                          worker's own release() call safely no-ops instead
 *                          of deleting the live worker's lock.
 *
 * All timing uses real wall-clock sleeps (short, millisecond-scale) rather
 * than a mocked clock, so the race is a genuine race, not a scripted one.
 */
public class Simulation {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Scenario 1: normal contention, no stalls ===");
        normalContention();

        System.out.println();
        System.out.println("=== Scenario 2: stalled worker, fencing DISABLED (inherited design) ===");
        stalledWorkerScenario(false);

        System.out.println();
        System.out.println("=== Scenario 3: stalled worker, fencing ENABLED ===");
        stalledWorkerScenario(true);
    }

    /** Scenario 1: many workers, one entity, no pathological timing. */
    static void normalContention() throws InterruptedException {
        CoordinationBackend svc = new InMemoryCoordinationService();
        DistributedLock lock = new DistributedLock(svc);
        ProtectedResource resource = new ProtectedResource();

        final int workers = 20;
        final int opsPerWorker = 50;
        final String entity = "account-1";
        AtomicBoolean sawUnexpectedRejection = new AtomicBoolean(false);
        AtomicBoolean sawFailedAcquire = new AtomicBoolean(false);
        AtomicBoolean sawFailedRelease = new AtomicBoolean(false);

        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                for (int op = 0; op < opsPerWorker; op++) {
                    long deadline = System.nanoTime() + 5_000_000_000L; // 5s
                    Optional<LockGrant> grant = lock.acquire(entity, 200, deadline);
                    if (grant.isEmpty()) {
                        sawFailedAcquire.set(true);
                        continue;
                    }
                    LockGrant g = grant.get();
                    sleepMs(ThreadLocalRandom.current().nextInt(0, 3));
                    boolean applied = resource.apply(entity, g.fencingToken, "w" + id, 1, true);
                    if (!applied) {
                        sawUnexpectedRejection.set(true);
                    }
                    boolean released = lock.release(g);
                    if (!released) {
                        sawFailedRelease.set(true);
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        int expected = workers * opsPerWorker;
        int actual = resource.getBalance(entity);
        System.out.println("  final balance = " + actual + " (expected " + expected + ")");
        System.out.println("  saw unexpected rejection: " + sawUnexpectedRejection.get());
        System.out.println("  saw failed acquire (contention timeout): " + sawFailedAcquire.get());
        System.out.println("  saw failed release (lost own lock): " + sawFailedRelease.get());
        if (actual != expected) {
            throw new AssertionError("MUTUAL EXCLUSION VIOLATED: expected " + expected + " got " + actual);
        }
        System.out.println("  PASS: no corruption under normal contention.");
    }

    /**
     * Scenario 2/3: Worker A acquires the lock on "invoice-42", then stalls
     * (simulated GC pause) for 400ms — longer than the 150ms TTL — BEFORE
     * doing its write. While it's stalled, Worker B acquires the now-expired
     * lock, does its write, and releases. Worker A then wakes up, unaware
     * time passed, and does its write using the fencing token it obtained
     * before it ever stalled.
     */
    static void stalledWorkerScenario(boolean fencingEnforced) throws InterruptedException {
        CoordinationBackend svc = new InMemoryCoordinationService();
        DistributedLock lock = new DistributedLock(svc);
        ProtectedResource resource = new ProtectedResource();
        final String entity = "invoice-42";
        final long ttlMs = 150;

        final Optional<LockGrant>[] grantA = new Optional[1];
        final Optional<LockGrant>[] grantB = new Optional[1];
        final boolean[] appliedA = new boolean[1];
        final boolean[] appliedB = new boolean[1];
        final boolean[] releasedA = new boolean[1];
        final boolean[] releasedB = new boolean[1];

        Thread workerA = new Thread(() -> {
            Optional<LockGrant> g = lock.tryAcquire(entity, ttlMs);
            grantA[0] = g;
            if (g.isEmpty()) return;
            // Simulated GC pause / blocked syscall / CPU steal, AFTER
            // acquiring the lock, BEFORE touching the resource. This is the
            // exact case the operating conditions describe: "it wakes up
            // and keeps going exactly where it left off, with no awareness
            // that time passed."
            sleepMs(400);
            appliedA[0] = resource.apply(entity, g.get().fencingToken, "A", 100, fencingEnforced);
            releasedA[0] = lock.release(g.get());
        });

        Thread workerB = new Thread(() -> {
            sleepMs(20); // start slightly after A so A gets the lock first
            long deadline = System.nanoTime() + 2_000_000_000L;
            Optional<LockGrant> g = lock.acquire(entity, ttlMs, deadline); // will wait out A's TTL
            grantB[0] = g;
            if (g.isEmpty()) return;
            appliedB[0] = resource.apply(entity, g.get().fencingToken, "B", 100, fencingEnforced);
            releasedB[0] = lock.release(g.get());
        });

        workerA.start();
        workerB.start();
        workerA.join();
        workerB.join();

        System.out.println("  A: " + describe(grantA[0]) + " applied=" + appliedA[0] + " released=" + releasedA[0]);
        System.out.println("  B: " + describe(grantB[0]) + " applied=" + appliedB[0] + " released=" + releasedB[0]);
        int balance = resource.getBalance(entity);
        System.out.println("  final balance for " + entity + " = " + balance
                + "  (100 = correct single charge, 200 = double charge / corruption)");
        for (ProtectedResource.RejectedWrite r : resource.getRejectedWrites()) {
            System.out.println("  " + r);
        }

        if (!fencingEnforced) {
            if (balance != 200) {
                throw new AssertionError("expected to reproduce the double-charge bug but balance=" + balance);
            }
            System.out.println("  CONFIRMED: inherited design (TTL lock, no fencing) double-charges under a stall.");
        } else {
            if (balance != 100) {
                throw new AssertionError("SAFETY VIOLATED with fencing enabled: balance=" + balance);
            }
            System.out.println("  PASS: fencing token caught the stale write; single charge only.");
        }
    }

    private static String describe(Optional<LockGrant> g) {
        return g.map(LockGrant::toString).orElse("NO GRANT");
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
