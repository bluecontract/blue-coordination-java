package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.CoordinationDeliveryStatus;
import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named Round 3 proof for exact parallel failure and resume semantics. */
final class ParallelRootFailureResumeTest {

    @Test
    void shouldResumeOnlyFailedAndPendingRootsAtExactAttemptCounts()
            throws Exception {
        // given
        List<FailureBoundary> boundaries = Arrays.asList(
                FailureBoundary.BEFORE_PROCESS,
                FailureBoundary.AFTER_TRANSITION_PREPARATION);

        // when
        List<ResumeProof> proofs = new ArrayList<>();
        for (FailureBoundary boundary : boundaries) {
            proofs.add(exerciseFailureAndResume(boundary));
        }

        // then
        for (ResumeProof proof : proofs) {
            assertEquals(Arrays.asList(
                            CoordinationDeliveryStatus.COMMITTED,
                            CoordinationDeliveryStatus.FAILED,
                            CoordinationDeliveryStatus.PENDING),
                    statuses(proof.failed()));
            assertEquals(Arrays.asList(1, 1, 0),
                    attempts(proof.failed()));
            assertEquals(Arrays.asList(1, 2, 1),
                    attempts(proof.resumed()));
            assertTrue(proof.resumed().complete());
            assertEquals(Integer.valueOf(1),
                    proof.commitApplications().get("root-a"));
            assertEquals(Integer.valueOf(1),
                    proof.commitApplications().get("root-b"));
            assertEquals(Integer.valueOf(1),
                    proof.commitApplications().get("root-c"));
            assertEquals(Integer.valueOf(1),
                    proof.prepareCalls().get("root-a"),
                    "a committed Root must not be prepared again");
            assertEquals(Integer.valueOf(1),
                    proof.commitCalls().get("root-a"),
                    "a committed Root must not be published again");
            assertEquals(1, proof.indexQueries(),
                    "resume must consume the frozen target plan");
            assertFalse(hasInFlightReceipt(proof.failed()));
            assertFalse(hasInFlightReceipt(proof.resumed()));
            assertTrue(proof.schedulerQuiescent());
        }
    }

    @Test
    void shouldReconcileAnAuthoritativeCasBeforeTheHostReceipt()
            throws Exception {
        // given
        StoredCoordinationEvent event =
                ParallelRootAcceptanceSupport.event("parallel-after-cas");
        ScriptedExecutor executor = new ScriptedExecutor(
                FailureBoundary.AFTER_AUTHORITATIVE_CAS);

        // when
        FanoutRun run = dispatch(event, executor, (stored, session) ->
                Optional.ofNullable(executor.authoritative(
                        session.value())));

        // then
        try {
            assertTrue(run.snapshot().complete());
            assertEquals(Arrays.asList(1, 1, 1),
                    attempts(run.snapshot()));
            assertEquals(Integer.valueOf(1),
                    executor.commitCalls().get("root-b"));
            assertEquals(Integer.valueOf(1),
                    executor.commitApplications().get("root-b"));
            assertEquals(1, run.index().queryCount());
            assertFalse(hasInFlightReceipt(run.snapshot()));
            assertTrue(run.scheduler().isQuiescent());
        } finally {
            close(run.pool());
        }
    }

    @Test
    void shouldRejectStalePreparedTransitionsWithoutPublishingThem()
            throws Exception {
        // given
        ExecutorService pool = Executors.newSingleThreadExecutor();
        StaleCheckingExecutor executor = new StaleCheckingExecutor();
        BoundedCoordinationRootScheduler<Prepared> scheduler =
                new BoundedCoordinationRootScheduler<>(
                        pool,
                        executor,
                        new CoordinationParallelismPolicy(1, true),
                        CoordinationRootPreparationObserver.none());
        BoundedCoordinationRootScheduler.Result<Prepared> result =
                scheduler.schedule(
                        ParallelRootAcceptanceSupport.event(
                                "parallel-stale"),
                        Collections.singletonList(
                                ParallelRootAcceptanceSupport.target(
                                        "root-a")),
                        PrefetchPolicy.MINIMUM_BYTES).get(0);
        result.awaitPrepared();

        // when
        executor.advanceAuthoritativeEpoch();
        IllegalStateException stale = assertThrows(
                IllegalStateException.class, result::commit);
        result.discard();

        // then
        try {
            assertTrue(stale.getMessage().contains("stale"));
            assertTrue(executor.published().isEmpty());
            assertEquals(1, executor.discards());
            assertTrue(scheduler.isQuiescent());
        } finally {
            close(pool);
        }
    }

    @Test
    void shouldLeaveNoClaimsWhenThePreparationExecutorRejectsWork()
            throws Exception {
        // given
        StoredCoordinationEvent event =
                ParallelRootAcceptanceSupport.event("parallel-rejected");
        ParallelRootAcceptanceSupport.FixedIndex index =
                new ParallelRootAcceptanceSupport.FixedIndex(
                        ParallelRootAcceptanceSupport
                                .threeTargetsOutOfOrder());
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        ExecutorService rejectedPool = Executors.newSingleThreadExecutor();
        rejectedPool.shutdownNow();
        BoundedCoordinationRootScheduler<Prepared> scheduler =
                new BoundedCoordinationRootScheduler<>(
                        rejectedPool,
                        new StaleCheckingExecutor(),
                        new CoordinationParallelismPolicy(2, true),
                        CoordinationRootPreparationObserver.none());
        InMemoryCoordinationFanout fanout =
                InMemoryCoordinationFanout.parallel(
                        index,
                        ledger,
                        scheduler,
                        CoordinationCommittedDeliveryProbe.none());

        // when
        assertThrows(RejectedExecutionException.class, () ->
                fanout.dispatch(
                        event,
                        Collections.singletonList("actor:alice"),
                        "ownerChannel",
                        3,
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS));
        CoordinationDispatchSnapshot rejected = ledger.find(
                event.eventBlueId()).orElseThrow(() ->
                        new AssertionError("sealed dispatch is missing"));

        // then
        assertEquals(Arrays.asList(
                        CoordinationDeliveryStatus.PENDING,
                        CoordinationDeliveryStatus.PENDING,
                        CoordinationDeliveryStatus.PENDING),
                statuses(rejected));
        assertFalse(hasInFlightReceipt(rejected));
        assertEquals(0, scheduler.activePreparationCount());
        assertEquals(0, scheduler.outstandingResultCount());
        assertTrue(scheduler.isQuiescent());
        assertEquals(1, index.queryCount());
        assertTrue(rejectedPool.awaitTermination(5L, TimeUnit.SECONDS));
    }

    private static ResumeProof exerciseFailureAndResume(
            FailureBoundary boundary) throws Exception {
        StoredCoordinationEvent event = ParallelRootAcceptanceSupport.event(
                "parallel-resume-" + boundary.name().toLowerCase(
                        java.util.Locale.ROOT));
        ScriptedExecutor executor = new ScriptedExecutor(boundary);
        ParallelRootAcceptanceSupport.FixedIndex index =
                new ParallelRootAcceptanceSupport.FixedIndex(
                        ParallelRootAcceptanceSupport
                                .threeTargetsOutOfOrder());
        InMemoryCoordinationDispatchLedger ledger =
                new InMemoryCoordinationDispatchLedger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BoundedCoordinationRootScheduler<Prepared> scheduler =
                new BoundedCoordinationRootScheduler<>(
                        pool,
                        executor,
                        new CoordinationParallelismPolicy(2, true),
                        CoordinationRootPreparationObserver.none());
        InMemoryCoordinationFanout fanout =
                InMemoryCoordinationFanout.parallel(
                        index,
                        ledger,
                        scheduler,
                        CoordinationCommittedDeliveryProbe.none());
        try {
            CoordinationFanoutException failure = assertThrows(
                    CoordinationFanoutException.class,
                    () -> fanout.dispatch(
                            event,
                            Collections.singletonList("actor:alice"),
                            "ownerChannel",
                            3,
                            PrefetchPolicy.MINIMUM_ROUND_TRIPS));
            executor.allowRetry();
            CoordinationDispatchSnapshot resumed = fanout.resume(
                    event.eventBlueId(),
                    PrefetchPolicy.MINIMUM_ROUND_TRIPS);
            return new ResumeProof(
                    failure.dispatch(),
                    resumed,
                    executor.prepareCalls(),
                    executor.commitCalls(),
                    executor.commitApplications(),
                    index.queryCount(),
                    scheduler.isQuiescent());
        } finally {
            close(pool);
        }
    }

    private static FanoutRun dispatch(
            StoredCoordinationEvent event,
            ScriptedExecutor executor,
            CoordinationCommittedDeliveryProbe probe) {
        ParallelRootAcceptanceSupport.FixedIndex index =
                new ParallelRootAcceptanceSupport.FixedIndex(
                        ParallelRootAcceptanceSupport
                                .threeTargetsOutOfOrder());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BoundedCoordinationRootScheduler<Prepared> scheduler =
                new BoundedCoordinationRootScheduler<>(
                        pool,
                        executor,
                        new CoordinationParallelismPolicy(2, true),
                        CoordinationRootPreparationObserver.none());
        InMemoryCoordinationFanout fanout =
                InMemoryCoordinationFanout.parallel(
                        index,
                        new InMemoryCoordinationDispatchLedger(),
                        scheduler,
                        probe);
        CoordinationDispatchSnapshot snapshot = fanout.dispatch(
                event,
                Collections.singletonList("actor:alice"),
                "ownerChannel",
                3,
                PrefetchPolicy.MINIMUM_ROUND_TRIPS);
        return new FanoutRun(snapshot, index, scheduler, pool);
    }

    private static List<CoordinationDeliveryStatus> statuses(
            CoordinationDispatchSnapshot snapshot) {
        return snapshot.receipts().stream()
                .map(receipt -> receipt.status())
                .collect(java.util.stream.Collectors.toList());
    }

    private static List<Integer> attempts(
            CoordinationDispatchSnapshot snapshot) {
        return snapshot.receipts().stream()
                .map(receipt -> receipt.attemptCount())
                .collect(java.util.stream.Collectors.toList());
    }

    private static boolean hasInFlightReceipt(
            CoordinationDispatchSnapshot snapshot) {
        return snapshot.receipts().stream().anyMatch(receipt ->
                receipt.status() == CoordinationDeliveryStatus.IN_FLIGHT);
    }

    private static void close(ExecutorService pool)
            throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS));
    }

    private enum FailureBoundary {
        BEFORE_PROCESS,
        AFTER_TRANSITION_PREPARATION,
        AFTER_AUTHORITATIVE_CAS
    }

    private static final class Prepared {
        private final StoredCoordinationEvent event;
        private final IndexedSessionCandidates target;
        private final long plannedEpoch;

        private Prepared(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                long plannedEpoch) {
            this.event = event;
            this.target = target;
            this.plannedEpoch = plannedEpoch;
        }

        StoredCoordinationEvent event() { return event; }
        IndexedSessionCandidates target() { return target; }
        long plannedEpoch() { return plannedEpoch; }
    }

    private static final class ResumeProof {
        private final CoordinationDispatchSnapshot failed;
        private final CoordinationDispatchSnapshot resumed;
        private final Map<String, Integer> prepareCalls;
        private final Map<String, Integer> commitCalls;
        private final Map<String, Integer> commitApplications;
        private final int indexQueries;
        private final boolean schedulerQuiescent;

        private ResumeProof(
                CoordinationDispatchSnapshot failed,
                CoordinationDispatchSnapshot resumed,
                Map<String, Integer> prepareCalls,
                Map<String, Integer> commitCalls,
                Map<String, Integer> commitApplications,
                int indexQueries,
                boolean schedulerQuiescent) {
            this.failed = failed;
            this.resumed = resumed;
            this.prepareCalls = prepareCalls;
            this.commitCalls = commitCalls;
            this.commitApplications = commitApplications;
            this.indexQueries = indexQueries;
            this.schedulerQuiescent = schedulerQuiescent;
        }

        CoordinationDispatchSnapshot failed() { return failed; }
        CoordinationDispatchSnapshot resumed() { return resumed; }
        Map<String, Integer> prepareCalls() { return prepareCalls; }
        Map<String, Integer> commitCalls() { return commitCalls; }
        Map<String, Integer> commitApplications() {
            return commitApplications;
        }
        int indexQueries() { return indexQueries; }
        boolean schedulerQuiescent() { return schedulerQuiescent; }
    }

    private static final class FanoutRun {
        private final CoordinationDispatchSnapshot snapshot;
        private final ParallelRootAcceptanceSupport.FixedIndex index;
        private final BoundedCoordinationRootScheduler<Prepared> scheduler;
        private final ExecutorService pool;

        private FanoutRun(
                CoordinationDispatchSnapshot snapshot,
                ParallelRootAcceptanceSupport.FixedIndex index,
                BoundedCoordinationRootScheduler<Prepared> scheduler,
                ExecutorService pool) {
            this.snapshot = snapshot;
            this.index = index;
            this.scheduler = scheduler;
            this.pool = pool;
        }

        CoordinationDispatchSnapshot snapshot() { return snapshot; }
        ParallelRootAcceptanceSupport.FixedIndex index() { return index; }
        BoundedCoordinationRootScheduler<Prepared> scheduler() {
            return scheduler;
        }
        ExecutorService pool() { return pool; }
    }

    private static final class ScriptedExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private final FailureBoundary boundary;
        private final Map<String, Integer> prepareCalls =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, Integer> commitCalls =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, Integer> commitApplications =
                Collections.synchronizedMap(new LinkedHashMap<>());
        private final Map<String, CoordinationCommittedDelivery>
                authoritative = Collections.synchronizedMap(
                        new LinkedHashMap<>());
        private volatile boolean retryAllowed;

        private ScriptedExecutor(FailureBoundary boundary) {
            this.boundary = boundary;
        }

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            increment(prepareCalls, session);
            if (!retryAllowed
                    && boundary == FailureBoundary.BEFORE_PROCESS
                    && "root-b".equals(session)) {
                throw new IllegalStateException(
                        "injected before PROCESS");
            }
            return new Prepared(event, target, target.plannedEpoch());
        }

        @Override
        public CoordinationCommittedDelivery commit(Prepared prepared) {
            String session = prepared.target().sessionId().value();
            increment(commitCalls, session);
            if (!retryAllowed
                    && boundary
                    == FailureBoundary.AFTER_TRANSITION_PREPARATION
                    && "root-b".equals(session)) {
                throw new IllegalStateException(
                        "injected after transition preparation");
            }
            CoordinationCommittedDelivery committed =
                    ParallelRootAcceptanceSupport.committed(
                            prepared.event(), prepared.target());
            increment(commitApplications, session);
            authoritative.put(session, committed);
            if (!retryAllowed
                    && boundary == FailureBoundary.AFTER_AUTHORITATIVE_CAS
                    && "root-b".equals(session)) {
                throw new IllegalStateException(
                        "injected after authoritative Root CAS");
            }
            return committed;
        }

        void allowRetry() {
            retryAllowed = true;
        }

        CoordinationCommittedDelivery authoritative(String session) {
            return authoritative.get(session);
        }

        Map<String, Integer> prepareCalls() {
            return copy(prepareCalls);
        }

        Map<String, Integer> commitCalls() {
            return copy(commitCalls);
        }

        Map<String, Integer> commitApplications() {
            return copy(commitApplications);
        }

        private static void increment(
                Map<String, Integer> values,
                String session) {
            synchronized (values) {
                values.put(session, Integer.valueOf(
                        values.getOrDefault(session, Integer.valueOf(0))
                                .intValue() + 1));
            }
        }

        private static Map<String, Integer> copy(
                Map<String, Integer> source) {
            synchronized (source) {
                return Collections.unmodifiableMap(
                        new LinkedHashMap<>(source));
            }
        }
    }

    private static final class StaleCheckingExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private long authoritativeEpoch;
        private final List<String> published = new ArrayList<>();
        private int discards;

        @Override
        public synchronized Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            return new Prepared(event, target, authoritativeEpoch);
        }

        @Override
        public synchronized CoordinationCommittedDelivery commit(
                Prepared prepared) {
            if (prepared.plannedEpoch() != authoritativeEpoch) {
                throw new IllegalStateException(
                        "stale prepared transition");
            }
            published.add(prepared.target().sessionId().value());
            return ParallelRootAcceptanceSupport.committed(
                    prepared.event(), prepared.target());
        }

        @Override
        public synchronized void discard(Prepared prepared) {
            discards++;
        }

        synchronized void advanceAuthoritativeEpoch() {
            authoritativeEpoch++;
        }

        synchronized List<String> published() {
            return Collections.unmodifiableList(
                    new ArrayList<String>(published));
        }

        synchronized int discards() {
            return discards;
        }
    }
}
