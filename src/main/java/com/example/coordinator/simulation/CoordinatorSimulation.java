package com.example.coordinator.simulation;

import com.example.coordinator.lock.EntityLock;
import com.example.coordinator.lock.RedisEntityLock;
import com.example.coordinator.resource.InMemoryLedger;
import com.example.coordinator.resource.ProtectedResource;
import com.example.coordinator.worker.Worker;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the lock under contention, zombie workers, and long-running jobs.
 * Run via {@code mvn -q exec:java} or the JUnit harness in
 * {@link CoordinatorSimulationTest}.
 */
public final class CoordinatorSimulation {

    private CoordinatorSimulation() {
    }

    public static SimulationReport run(StringRedisTemplate redis) {
        return run(new RedisEntityLock(redis, Duration.ofMillis(800)));
    }

    public static SimulationReport run(EntityLock entityLock) {
        InMemoryLedger ledger = new InMemoryLedger();
        SimulationReport report = new SimulationReport();

        report.add(runMutualExclusion(entityLock, ledger));
        ledger.reset();

        report.add(runZombieWorker(entityLock, ledger));
        ledger.reset();

        report.add(runLongJobWithRenewal(entityLock, ledger));

        return report;
    }

    /**
     * Many workers hammer the same entity; balance must equal successful applies.
     */
    static ScenarioResult runMutualExclusion(EntityLock entityLock, InMemoryLedger ledger) {
        String entityKey = "invoice-1001";
        int workerCount = 32;
        int jobsPerWorker = 5;
        Duration lockTtl = entityLock instanceof RedisEntityLock redisLock
                ? redisLock.lockTtl()
                : Duration.ofSeconds(2);

        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger applied = new AtomicInteger();

        try {
            List<Worker> pool = new ArrayList<>();
            for (int i = 0; i < workerCount; i++) {
                pool.add(new Worker("worker-" + i, entityLock, ledger, renewals));
            }

            for (Worker worker : pool) {
                workers.submit(() -> {
                    for (int job = 0; job < jobsPerWorker; job++) {
                        boolean ok = worker.processJob(
                                entityKey,
                                1L,
                                Duration.ofMillis(20),
                                Duration.ofMillis(500),
                                false
                        );
                        if (ok) {
                            applied.incrementAndGet();
                        }
                    }
                });
            }

            workers.shutdown();
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            renewals.shutdownNow();
        }

        boolean passed = ledger.corruptionEvents() == 0
                && ledger.maxConcurrentAccess() <= 1
                && ledger.balance(entityKey) == applied.get();

        return new ScenarioResult(
                "mutual_exclusion",
                passed,
                "workers=%d jobsEach=%d applied=%d balance=%d corruption=%d maxConcurrent=%d"
                        .formatted(workerCount, jobsPerWorker, applied.get(), ledger.balance(entityKey),
                                ledger.corruptionEvents(), ledger.maxConcurrentAccess())
        );
    }

    /**
     * Worker stalls longer than TTL without renewal; a second worker takes over.
     * The zombie's late write must be rejected by fencing.
     */
    static ScenarioResult runZombieWorker(EntityLock entityLock, InMemoryLedger ledger) {
        String entityKey = "invoice-2002";
        Duration lockTtl = entityLock instanceof RedisEntityLock redisLock
                ? redisLock.lockTtl()
                : Duration.ofSeconds(2);

        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();
        Worker zombie = new Worker("zombie", entityLock, ledger, renewals);
        Worker successor = new Worker("successor", entityLock, ledger, renewals);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            var zombieFuture = pool.submit(() -> zombie.processJob(
                    entityKey,
                    50L,
                    lockTtl.multipliedBy(2),
                    Duration.ofMillis(200),
                    false
            ));

            // Let the lock expire while the zombie is still paused.
            sleepQuietly(lockTtl.plusMillis(100).toMillis());

            var successorFuture = pool.submit(() -> successor.processJob(
                    entityKey,
                    7L,
                    Duration.ofMillis(30),
                    Duration.ofMillis(500),
                    false
            ));

            boolean zombieApplied = zombieFuture.get(10, TimeUnit.SECONDS);
            boolean successorApplied = successorFuture.get(10, TimeUnit.SECONDS);

            boolean passed = !zombieApplied
                    && successorApplied
                    && ledger.corruptionEvents() == 0
                    && ledger.rejectedStaleWrites() >= 1
                    && ledger.balance(entityKey) == 7L;

            return new ScenarioResult(
                    "zombie_after_ttl",
                    passed,
                    "zombieApplied=%s successorApplied=%s balance=%d rejectedStale=%d corruption=%d"
                            .formatted(zombieApplied, successorApplied, ledger.balance(entityKey),
                                    ledger.rejectedStaleWrites(), ledger.corruptionEvents())
            );
        } catch (Exception ex) {
            return new ScenarioResult(
                    "zombie_after_ttl",
                    false,
                    "exception=" + ex.getMessage()
            );
        } finally {
            pool.shutdownNow();
            renewals.shutdownNow();
        }
    }

    /**
     * Long job with renewal heartbeat keeps the lock; no other worker can corrupt state.
     */
    static ScenarioResult runLongJobWithRenewal(EntityLock entityLock, InMemoryLedger ledger) {
        String entityKey = "invoice-3003";
        Duration lockTtl = entityLock instanceof RedisEntityLock redisLock
                ? redisLock.lockTtl()
                : Duration.ofSeconds(2);

        ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor();
        Worker longWorker = new Worker("long-worker", entityLock, ledger, renewals);
        Worker contender = new Worker("contender", entityLock, ledger, renewals);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger contenderSuccesses = new AtomicInteger();

        try {
            pool.submit(() -> longWorker.processJob(
                    entityKey,
                    11L,
                    lockTtl.multipliedBy(3),
                    Duration.ofMillis(200),
                    true
            ));

            pool.submit(() -> {
                for (int i = 0; i < 10; i++) {
                    if (contender.processJob(entityKey, 1L, Duration.ofMillis(10), Duration.ofMillis(50), false)) {
                        contenderSuccesses.incrementAndGet();
                    }
                    sleepQuietly(100);
                }
            });

            pool.shutdown();
            pool.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            renewals.shutdownNow();
        }

        boolean passed = ledger.corruptionEvents() == 0
                && contenderSuccesses.get() == 0
                && ledger.balance(entityKey) == 11L;

        return new ScenarioResult(
                "long_job_with_renewal",
                passed,
                "balance=%d contenderSuccesses=%d corruption=%d"
                        .formatted(ledger.balance(entityKey), contenderSuccesses.get(), ledger.corruptionEvents())
        );
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record ScenarioResult(String name, boolean passed, String details) {}

    public static final class SimulationReport {
        private final List<ScenarioResult> scenarios = new ArrayList<>();

        void add(ScenarioResult result) {
            scenarios.add(result);
        }

        public List<ScenarioResult> scenarios() {
            return List.copyOf(scenarios);
        }

        public boolean allPassed() {
            return scenarios.stream().allMatch(ScenarioResult::passed);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Coordinator simulation report\n");
            for (ScenarioResult scenario : scenarios) {
                sb.append(scenario.passed() ? "[PASS] " : "[FAIL] ")
                        .append(scenario.name())
                        .append(" — ")
                        .append(scenario.details())
                        .append('\n');
            }
            sb.append(allPassed() ? "ALL SCENARIOS PASSED" : "SOME SCENARIOS FAILED");
            return sb.toString();
        }
    }
}
