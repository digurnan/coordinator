package coordinator;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Identical scenarios to Simulation.java, run against a real Redis instance
 * via RedisCoordinationService instead of the in-memory stand-in. This is
 * the thing to run to confirm the design holds against the actual backend,
 * not just the stand-in.
 *
 * Usage:
 *   docker compose up -d          # start Redis on localhost:6379
 *   mvn compile exec:java         # runs this class
 *
 * or point at a different host/port:
 *   mvn compile exec:java -Dexec.args="myredishost 6380"
 *
 * Each run uses a random UUID prefix on every entity key so repeated runs
 * never collide with leftover state from a previous run.
 */
public class RedisSimulation {

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 6379;
        String runPrefix = UUID.randomUUID().toString().substring(0, 8);

        System.out.println("Connecting to Redis at " + host + ":" + port
                + " (run prefix: " + runPrefix + ")");

        try (RedisCoordinationService svc = new RedisCoordinationService(host, port)) {
            // Sanity check the connection before running anything else, so a
            // connection failure produces a clear message instead of a wall
            // of thread-pool stack traces from every worker failing at once.
            svc.get(runPrefix + ":connectivity-check");
            System.out.println("Connected OK.\n");

            System.out.println("=== Scenario 1: normal contention, no stalls (real Redis) ===");
            normalContention(svc, runPrefix);

            System.out.println();
            System.out.println("=== Scenario 2: stalled worker, fencing DISABLED (real Redis) ===");
            stalledWorkerScenario(svc, runPrefix, false);

            System.out.println();
            System.out.println("=== Scenario 3: stalled worker, fencing ENABLED (real Redis) ===");
            stalledWorkerScenario(svc, runPrefix, true);
        } catch (Exception e) {
            System.err.println("Failed to connect or run against Redis at " + host + ":" + port);
            System.err.println("Is Redis running? Try: docker compose up -d");
            throw e;
        }
    }

    static void normalContention(CoordinationBackend svc, String runPrefix) throws InterruptedException {
        DistributedLock lock = new DistributedLock(svc);
        ProtectedResource resource = new ProtectedResource();

        final int workers = 20;
        final int opsPerWorker = 50;
        final String entity = runPrefix + ":account-1";
        AtomicBoolean sawUnexpectedRejection = new AtomicBoolean(false);
        AtomicBoolean sawFailedAcquire = new AtomicBoolean(false);
        AtomicBoolean sawFailedRelease = new AtomicBoolean(false);

        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int id = i;
            threads[i] = new Thread(() -> {
                for (int op = 0; op < opsPerWorker; op++) {
                    long deadline = System.nanoTime() + 10_000_000_000L; // 10s (network round trips are slower than in-memory)
                    Optional<LockGrant> grant = lock.acquire(entity, 500, deadline);
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
            throw new AssertionError("MUTUAL EXCLUSION VIOLATED against real Redis: expected " + expected + " got " + actual);
        }
        System.out.println("  PASS: no corruption under normal contention (real Redis).");
    }

    static void stalledWorkerScenario(CoordinationBackend svc, String runPrefix, boolean fencingEnforced) throws InterruptedException {
        DistributedLock lock = new DistributedLock(svc);
        ProtectedResource resource = new ProtectedResource();
        final String entity = runPrefix + ":invoice-42-" + fencingEnforced;
        final long ttlMs = 300; // longer than the in-memory demo: real network round trips add latency

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
            sleepMs(800); // simulated GC pause, well past the TTL
            appliedA[0] = resource.apply(entity, g.get().fencingToken, "A", 100, fencingEnforced);
            releasedA[0] = lock.release(g.get());
        });

        Thread workerB = new Thread(() -> {
            sleepMs(50);
            long deadline = System.nanoTime() + 5_000_000_000L;
            Optional<LockGrant> g = lock.acquire(entity, ttlMs, deadline);
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
            System.out.println("  CONFIRMED against real Redis: TTL lock alone double-charges under a stall.");
        } else {
            if (balance != 100) {
                throw new AssertionError("SAFETY VIOLATED with fencing enabled against real Redis: balance=" + balance);
            }
            System.out.println("  PASS: fencing token caught the stale write against real Redis; single charge only.");
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
