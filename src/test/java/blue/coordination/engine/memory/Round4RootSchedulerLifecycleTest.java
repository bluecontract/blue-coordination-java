package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic Round-4 bounds and lifecycle proofs over production APIs. */
final class Round4RootSchedulerLifecycleTest {

    private static final String PREPARATION_THREAD_PREFIX =
            "blue-coordination-prepare-";

    @Test
    void oneWorkerAndOnePreparationPermitMatchCanonicalSerialSemantics() {
        int workerBaseline = ownedPreparationThreadCount();
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open();
             InMemoryCoordinationEnvironment environment = environment(
                     runtime, 1, 8, "round4-one-worker")) {
            StoredCoordinationEvent event =
                    ParallelRootAcceptanceSupport.event(
                            "round4-one-worker-event");
            CanonicalTwoPhaseExecutor parallelExecutor =
                    new CanonicalTwoPhaseExecutor();
            BoundedCoordinationRootScheduler<Prepared> scheduler =
                    environment.parallelScheduler(
                            parallelExecutor,
                            new CoordinationParallelismPolicy(1, true),
                            CoordinationRootPreparationObserver.none());
            ParallelRootAcceptanceSupport.FixedIndex parallelIndex =
                    new ParallelRootAcceptanceSupport.FixedIndex(
                            ParallelRootAcceptanceSupport
                                    .threeTargetsOutOfOrder());
            CoordinationDispatchSnapshot parallel =
                    InMemoryCoordinationFanout.parallel(
                            parallelIndex,
                            new InMemoryCoordinationDispatchLedger(),
                            scheduler,
                            CoordinationCommittedDeliveryProbe.none())
                            .dispatch(
                                    event,
                                    Collections.singletonList(
                                            "actor:alice"),
                                    "ownerChannel",
                                    3,
                                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);

            ParallelRootAcceptanceSupport.FixedIndex serialIndex =
                    new ParallelRootAcceptanceSupport.FixedIndex(
                            ParallelRootAcceptanceSupport
                                    .threeTargetsOutOfOrder());
            ParallelRootAcceptanceSupport.SerialSemanticExecutor serialWork =
                    new ParallelRootAcceptanceSupport
                            .SerialSemanticExecutor();
            CoordinationDispatchSnapshot serial =
                    new InMemoryCoordinationFanout(
                            serialIndex,
                            new InMemoryCoordinationDispatchLedger(),
                            serialWork)
                            .dispatch(
                                    event,
                                    Collections.singletonList(
                                            "actor:alice"),
                                    "ownerChannel",
                                    3,
                                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);

            assertTrue(parallel.complete());
            assertTrue(serial.complete());
            assertEquals(Arrays.asList("root-a", "root-b", "root-c"),
                    parallelExecutor.commitOrder());
            assertEquals(serialWork.commits(),
                    parallelExecutor.commitOrder());
            assertEquals(serialWork.states(), parallelExecutor.states());
            assertEquals(
                    ParallelRootAcceptanceSupport.receiptSignatures(serial),
                    ParallelRootAcceptanceSupport.receiptSignatures(
                            parallel));
            assertEquals(1, parallelIndex.queryCount());
            assertEquals(1, scheduler.peakPreparationCount());
            assertTrue(scheduler.isQuiescent());

            CoordinationRootPreparationPoolSnapshot pool =
                    environment.rootPreparationPoolSnapshot();
            assertEquals(1, pool.configuredParallelism());
            assertEquals(1, pool.largestPoolSize());
            assertEquals(0, pool.activeThreads());
            assertEquals(0, pool.queuedTasks());
            environment.close();
            assertPoolTerminated(environment.rootPreparationPoolSnapshot());
        }
        assertWorkerBaselineRestored(workerBaseline);
    }

    @Test
    void saturatedQueueRunsInCallerAndStillCommitsCanonically()
            throws Exception {
        int workerBaseline = ownedPreparationThreadCount();
        ExecutorService dispatchCaller = Executors.newSingleThreadExecutor(
                namedThreadFactory("round4-dispatch-caller"));
        BackpressureTwoPhaseExecutor work =
                new BackpressureTwoPhaseExecutor();
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open();
             InMemoryCoordinationEnvironment environment = environment(
                     runtime, 1, 1, "round4-caller-runs")) {
            BoundedCoordinationRootScheduler<Prepared> scheduler =
                    environment.parallelScheduler(
                            work,
                            new CoordinationParallelismPolicy(2, true),
                            CoordinationRootPreparationObserver.none());
            List<IndexedSessionCandidates> supplied = Arrays.asList(
                    ParallelRootAcceptanceSupport.target("root-d"),
                    ParallelRootAcceptanceSupport.target("root-b"),
                    ParallelRootAcceptanceSupport.target("root-a"),
                    ParallelRootAcceptanceSupport.target("root-c"));
            ParallelRootAcceptanceSupport.FixedIndex index =
                    new ParallelRootAcceptanceSupport.FixedIndex(supplied);
            InMemoryCoordinationFanout fanout =
                    InMemoryCoordinationFanout.parallel(
                            index,
                            new InMemoryCoordinationDispatchLedger(),
                            scheduler,
                            CoordinationCommittedDeliveryProbe.none());
            StoredCoordinationEvent event =
                    ParallelRootAcceptanceSupport.event(
                            "round4-caller-runs-event");
            Future<CoordinationDispatchSnapshot> running =
                    dispatchCaller.submit(() -> fanout.dispatch(
                            event,
                            Collections.singletonList("actor:alice"),
                            "ownerChannel",
                            4,
                            PrefetchPolicy.MINIMUM_ROUND_TRIPS));
            try {
                work.awaitStarted("root-a");
                work.awaitStarted("root-c");
                CoordinationRootPreparationPoolSnapshot saturated =
                        environment.rootPreparationPoolSnapshot();
                assertEquals(1, saturated.activeThreads());
                assertEquals(1, saturated.poolSize());
                assertEquals(1, saturated.queuedTasks());
                assertEquals(1, saturated.largestPoolSize());
                assertEquals(2, scheduler.activePreparationCount());

                work.release("root-c");
                work.awaitStarted("root-d");
                work.release("root-d");
                work.release("root-a");
                work.awaitStarted("root-b");
                work.release("root-b");
                CoordinationDispatchSnapshot completed = running.get(
                        10L, TimeUnit.SECONDS);

                assertTrue(completed.complete());
                assertEquals(
                        Arrays.asList(
                                "root-c", "root-d", "root-a", "root-b"),
                        work.completionOrder());
                assertEquals(
                        Arrays.asList(
                                "root-a", "root-b", "root-c", "root-d"),
                        work.commitOrder());
                assertEquals("round4-dispatch-caller",
                        work.preparationThread("root-c"));
                assertEquals("round4-dispatch-caller",
                        work.preparationThread("root-d"));
                assertTrue(work.preparationThread("root-a")
                        .startsWith(PREPARATION_THREAD_PREFIX));
                assertTrue(work.preparationThread("root-b")
                        .startsWith(PREPARATION_THREAD_PREFIX));
                assertEquals(2, scheduler.peakPreparationCount());
                assertTrue(scheduler.isQuiescent());

                CoordinationRootPreparationPoolSnapshot drained =
                        environment.rootPreparationPoolSnapshot();
                assertEquals(0, drained.activeThreads());
                assertEquals(0, drained.queuedTasks());
                assertEquals(1, drained.poolSize());
                assertEquals(2L, drained.completedTasks(),
                        "the other two preparations ran in the caller");
            } finally {
                work.releaseAll();
                running.cancel(true);
            }
            environment.close();
            assertPoolTerminated(environment.rootPreparationPoolSnapshot());
        } finally {
            work.releaseAll();
            dispatchCaller.shutdownNow();
            assertTrue(dispatchCaller.awaitTermination(
                    5L, TimeUnit.SECONDS));
        }
        assertWorkerBaselineRestored(workerBaseline);
    }

    @Test
    void closeDrainsQueuedPreparationAndRestoresOwnedWorkers()
            throws Exception {
        int workerBaseline = ownedPreparationThreadCount();
        ExecutorService closeCaller = Executors.newSingleThreadExecutor(
                namedThreadFactory("round4-close-caller"));
        ClosingTwoPhaseExecutor work = new ClosingTwoPhaseExecutor();
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open();
             InMemoryCoordinationEnvironment environment = environment(
                     runtime, 1, 4, "round4-close-drain")) {
            BoundedCoordinationRootScheduler<Prepared> scheduler =
                    environment.parallelScheduler(
                            work,
                            new CoordinationParallelismPolicy(1, true),
                            CoordinationRootPreparationObserver.none());
            List<BoundedCoordinationRootScheduler.Result<Prepared>> results =
                    scheduler.schedule(
                            ParallelRootAcceptanceSupport.event(
                                    "round4-close-drain-event"),
                            ParallelRootAcceptanceSupport
                                    .threeTargetsOutOfOrder(),
                            PrefetchPolicy.MINIMUM_BYTES);
            work.awaitFirstStarted();
            CoordinationRootPreparationPoolSnapshot queued =
                    environment.rootPreparationPoolSnapshot();
            assertEquals(1, queued.activeThreads());
            assertEquals(2, queued.queuedTasks());

            Future<?> closing = closeCaller.submit(environment::close);
            work.releaseAll();
            closing.get(10L, TimeUnit.SECONDS);

            CoordinationRootPreparationPoolSnapshot closed =
                    environment.rootPreparationPoolSnapshot();
            assertPoolTerminated(closed);
            assertEquals(3L, closed.completedTasks());
            assertEquals(0, scheduler.activePreparationCount());
            assertEquals(3, scheduler.outstandingResultCount(),
                    "completed Result handles remain caller-owned");
            for (BoundedCoordinationRootScheduler.Result<Prepared> result
                    : results) {
                result.discard();
            }
            assertEquals(3, work.discardCount());
            assertEquals(0, scheduler.outstandingResultCount());
            assertTrue(scheduler.isQuiescent());
            assertThrows(IllegalStateException.class, () ->
                    scheduler.schedule(
                            ParallelRootAcceptanceSupport.event(
                                    "after-close"),
                            Collections.singletonList(
                                    ParallelRootAcceptanceSupport.target(
                                            "root-a")),
                            PrefetchPolicy.MINIMUM_BYTES));
        } finally {
            work.releaseAll();
            closeCaller.shutdownNow();
            assertTrue(closeCaller.awaitTermination(5L, TimeUnit.SECONDS));
        }
        assertWorkerBaselineRestored(workerBaseline);
    }

    @Test
    void tenThousandOperationsRespectCacheAndWorkerLifecycleBounds() {
        final int operationCount = 10_000;
        final int maximumEntries = 64;
        final long maximumWeight = 512L;
        final int failedIteration = 5_000;
        int workerBaseline = ownedPreparationThreadCount();
        BoundedSingleFlightCache<Integer, CacheArtifact> cache =
                new BoundedSingleFlightCache<Integer, CacheArtifact>(
                        maximumEntries,
                        maximumWeight,
                        artifact -> artifact.weight);
        AtomicInteger cacheLoads = new AtomicInteger();
        CountingTwoPhaseExecutor work = new CountingTwoPhaseExecutor();

        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open();
             InMemoryCoordinationEnvironment environment = environment(
                     runtime, 1, 8, "round4-ten-thousand-lifecycle")) {
            BoundedCoordinationRootScheduler<Prepared> scheduler =
                    environment.parallelScheduler(
                            work,
                            new CoordinationParallelismPolicy(1, true),
                            CoordinationRootPreparationObserver.none());
            IndexedSessionCandidates target =
                    ParallelRootAcceptanceSupport.target("root-a");

            for (int iteration = 0; iteration < operationCount; iteration++) {
                if (iteration == failedIteration) {
                    BoundedSingleFlightCache.Snapshot beforeFailure =
                            cache.metrics();
                    assertThrows(IllegalStateException.class, () ->
                            cache.compute(
                                    Integer.valueOf(-1),
                                    ignored -> {
                                        cacheLoads.incrementAndGet();
                                        throw new IllegalStateException(
                                                "injected cache failure");
                                    }));
                    BoundedSingleFlightCache.Snapshot afterFailure =
                            cache.metrics();
                    assertEquals(beforeFailure.entries(),
                            afterFailure.entries());
                    assertEquals(beforeFailure.retainedWeight(),
                            afterFailure.retainedWeight());
                } else {
                    CacheArtifact artifact = cache.compute(
                            Integer.valueOf(iteration),
                            key -> {
                                cacheLoads.incrementAndGet();
                                return new CacheArtifact(
                                        key.intValue(),
                                        1L + key.intValue() % 16L);
                            });
                    assertEquals(iteration, artifact.identity);
                }

                BoundedCoordinationRootScheduler.Result<Prepared> result =
                        scheduler.schedule(
                                ParallelRootAcceptanceSupport.event(
                                        "round4-soak-" + iteration),
                                Collections.singletonList(target),
                                PrefetchPolicy.MINIMUM_BYTES).get(0);
                result.awaitPrepared();
                result.commit();

                if ((iteration & 255) == 0) {
                    assertCacheBounds(cache.metrics());
                    assertEquals(0, scheduler.activePreparationCount());
                    assertEquals(0, scheduler.outstandingResultCount());
                }
            }

            CacheArtifact recovered = cache.compute(
                    Integer.valueOf(-1),
                    ignored -> {
                        cacheLoads.incrementAndGet();
                        return new CacheArtifact(-1, 8L);
                    });
            assertEquals(-1, recovered.identity);
            BoundedSingleFlightCache.Snapshot retained = cache.metrics();
            assertCacheBounds(retained);
            assertEquals(maximumEntries, retained.maximumEntries());
            assertEquals(maximumWeight, retained.maximumWeight());
            assertTrue(retained.peakEntries() > 0);
            assertTrue(retained.peakRetainedWeight() > 0L);
            assertEquals(10_001L, retained.loads());
            assertEquals(1L, retained.failures());
            assertEquals(10_001, cacheLoads.get());
            assertTrue(retained.evictions() > 0L);
            assertEquals(operationCount, work.prepareCount());
            assertEquals(operationCount, work.commitCount());
            assertEquals(0, scheduler.activePreparationCount());
            assertEquals(0, scheduler.outstandingResultCount());
            assertTrue(scheduler.isQuiescent());

            CoordinationRootPreparationPoolSnapshot beforeClose =
                    environment.rootPreparationPoolSnapshot();
            assertEquals(operationCount, beforeClose.completedTasks());
            assertEquals(0, beforeClose.activeThreads());
            assertEquals(0, beforeClose.queuedTasks());
            assertEquals(1, beforeClose.poolSize());
            assertEquals(1, beforeClose.largestPoolSize());

            cache.clear();
            assertEquals(0, cache.size());
            assertEquals(0L, cache.retainedWeight());
            environment.close();
            assertPoolTerminated(environment.rootPreparationPoolSnapshot());
        }
        assertWorkerBaselineRestored(workerBaseline);
    }

    private static InMemoryCoordinationEnvironment environment(
            RepositoryIndependentCoordinationTestRuntime runtime,
            int parallelism,
            int queueCapacity,
            String identity) {
        return InMemoryCoordinationEnvironment.builder()
                .contracts(runtime.contracts())
                .documentProcessor(runtime.platformProcessor())
                .environmentIdentity(identity)
                .rootPreparationParallelism(parallelism)
                .rootPreparationQueueCapacity(queueCapacity)
                .build();
    }

    private static void assertCacheBounds(
            BoundedSingleFlightCache.Snapshot snapshot) {
        assertTrue(snapshot.entries() <= snapshot.maximumEntries());
        assertTrue(snapshot.retainedWeight() <= snapshot.maximumWeight());
        assertTrue(snapshot.peakEntries() <= snapshot.maximumEntries());
        assertTrue(snapshot.peakRetainedWeight()
                <= snapshot.maximumWeight());
        long toleratedEntries =
                (snapshot.maximumEntries() * 115L + 99L) / 100L;
        long toleratedWeight =
                (snapshot.maximumWeight() * 115L + 99L) / 100L;
        assertTrue(snapshot.entries() <= toleratedEntries);
        assertTrue(snapshot.retainedWeight() <= toleratedWeight);
    }

    private static void assertPoolTerminated(
            CoordinationRootPreparationPoolSnapshot snapshot) {
        assertEquals(0, snapshot.activeThreads());
        assertEquals(0, snapshot.poolSize());
        assertEquals(0, snapshot.queuedTasks());
    }

    private static ThreadFactory namedThreadFactory(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                return new Thread(task, name);
            }
        };
    }

    private static int ownedPreparationThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && thread.getName().startsWith(
                            PREPARATION_THREAD_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private static void assertWorkerBaselineRestored(int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        int actual = ownedPreparationThreadCount();
        while (actual != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException(
                        "Interrupted while awaiting worker shutdown");
            }
            actual = ownedPreparationThreadCount();
        }
        assertEquals(expected, actual,
                "environment-owned preparation workers leaked");
    }

    private static final class Prepared {
        private final StoredCoordinationEvent event;
        private final IndexedSessionCandidates target;

        private Prepared(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target) {
            this.event = event;
            this.target = target;
        }
    }

    private static class CanonicalTwoPhaseExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private final List<String> commits =
                Collections.synchronizedList(new ArrayList<String>());
        private final Map<String, ParallelRootAcceptanceSupport.SemanticState>
                states = Collections.synchronizedMap(
                        new LinkedHashMap<String,
                                ParallelRootAcceptanceSupport.SemanticState>());

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            return new Prepared(event, target);
        }

        @Override
        public CoordinationCommittedDelivery commit(Prepared prepared) {
            String session = prepared.target.sessionId().value();
            commits.add(session);
            states.put(session,
                    ParallelRootAcceptanceSupport.semanticState(session));
            return ParallelRootAcceptanceSupport.committed(
                    prepared.event, prepared.target);
        }

        List<String> commitOrder() {
            synchronized (commits) {
                return Collections.unmodifiableList(
                        new ArrayList<String>(commits));
            }
        }

        Map<String, ParallelRootAcceptanceSupport.SemanticState> states() {
            synchronized (states) {
                return Collections.unmodifiableMap(
                        new LinkedHashMap<String,
                                ParallelRootAcceptanceSupport.SemanticState>(
                                        states));
            }
        }
    }

    private static final class BackpressureTwoPhaseExecutor
            extends CanonicalTwoPhaseExecutor {
        private final Map<String, CountDownLatch> started = latches();
        private final Map<String, CountDownLatch> releases = latches();
        private final List<String> completions =
                Collections.synchronizedList(new ArrayList<String>());
        private final Map<String, String> preparationThreads =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, String>());

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            preparationThreads.put(
                    session, Thread.currentThread().getName());
            started.get(session).countDown();
            await(releases.get(session), "release " + session);
            completions.add(session);
            return super.prepare(event, target, prefetchPolicy);
        }

        void awaitStarted(String session) {
            await(started.get(session), "start " + session);
        }

        void release(String session) {
            releases.get(session).countDown();
        }

        void releaseAll() {
            for (CountDownLatch release : releases.values()) {
                release.countDown();
            }
        }

        String preparationThread(String session) {
            return preparationThreads.get(session);
        }

        List<String> completionOrder() {
            synchronized (completions) {
                return Collections.unmodifiableList(
                        new ArrayList<String>(completions));
            }
        }
    }

    private static final class ClosingTwoPhaseExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger discarded = new AtomicInteger();

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            firstStarted.countDown();
            await(release, "close-drain release");
            return new Prepared(event, target);
        }

        @Override
        public CoordinationCommittedDelivery commit(Prepared prepared) {
            return ParallelRootAcceptanceSupport.committed(
                    prepared.event, prepared.target);
        }

        @Override
        public void discard(Prepared prepared) {
            discarded.incrementAndGet();
        }

        void awaitFirstStarted() {
            await(firstStarted, "first queued preparation");
        }

        void releaseAll() {
            release.countDown();
        }

        int discardCount() {
            return discarded.get();
        }
    }

    private static final class CountingTwoPhaseExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            prepares.incrementAndGet();
            return new Prepared(event, target);
        }

        @Override
        public CoordinationCommittedDelivery commit(Prepared prepared) {
            commits.incrementAndGet();
            return ParallelRootAcceptanceSupport.committed(
                    prepared.event, prepared.target);
        }

        int prepareCount() { return prepares.get(); }

        int commitCount() { return commits.get(); }
    }

    private static final class CacheArtifact {
        private final int identity;
        private final long weight;

        private CacheArtifact(int identity, long weight) {
            this.identity = identity;
            this.weight = weight;
        }
    }

    private static Map<String, CountDownLatch> latches() {
        Map<String, CountDownLatch> result =
                new LinkedHashMap<String, CountDownLatch>();
        result.put("root-a", new CountDownLatch(1));
        result.put("root-b", new CountDownLatch(1));
        result.put("root-c", new CountDownLatch(1));
        result.put("root-d", new CountDownLatch(1));
        return result;
    }

    private static void await(CountDownLatch latch, String boundary) {
        try {
            assertTrue(latch.await(5L, TimeUnit.SECONDS),
                    "timed out waiting for " + boundary);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while awaiting " + boundary,
                    interrupted);
        }
    }
}
