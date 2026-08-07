package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BoundedCoordinationRootSchedulerTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void closePool() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(5L, TimeUnit.SECONDS));
    }

    @Test
    void preparationsOverlapAndPublicationRemainsCanonical() {
        CyclicBarrier bothPreparing = new CyclicBarrier(2);
        RecordingExecutor executor = new RecordingExecutor(
                bothPreparing, null);
        BoundedCoordinationRootScheduler<FakePrepared> scheduler = scheduler(
                executor);

        List<BoundedCoordinationRootScheduler.Result<FakePrepared>> scheduled =
                scheduler.schedule(
                        event("event-overlap"),
                        Arrays.asList(target("root-b"), target("root-a")),
                        PrefetchPolicy.BALANCED);

        assertEquals(Arrays.asList("root-a", "root-b"),
                sessionValues(scheduled));
        for (BoundedCoordinationRootScheduler.Result<FakePrepared> result
                : scheduled) {
            result.awaitPrepared();
            result.commit();
        }

        assertEquals(Arrays.asList("root-a", "root-b"), executor.commits);
        assertEquals(2, executor.prepares.size());
        assertEquals(2, scheduler.peakPreparationCount());
        assertTrue(scheduler.isQuiescent());
        assertTrue(executor.prepares.contains("root-a"));
        assertTrue(executor.prepares.contains("root-b"));
    }

    @Test
    void laterPreparationFailureDoesNotUndoEarlierCanonicalCommit() {
        CyclicBarrier firstTwoPreparing = new CyclicBarrier(2);
        RecordingExecutor executor = new RecordingExecutor(
                firstTwoPreparing, "root-b");
        BoundedCoordinationRootScheduler<FakePrepared> scheduler = scheduler(
                executor);

        List<BoundedCoordinationRootScheduler.Result<FakePrepared>> scheduled =
                scheduler.schedule(
                        event("event-failure"),
                        Arrays.asList(
                                target("root-c"),
                                target("root-b"),
                                target("root-a")),
                        PrefetchPolicy.MINIMUM_ROUND_TRIPS);

        scheduled.get(0).awaitPrepared();
        scheduled.get(0).commit();

        CoordinationParallelPreparationException failure = assertThrows(
                CoordinationParallelPreparationException.class,
                scheduled.get(1)::awaitPrepared);
        assertEquals(DocumentSessionId.of("root-b"), failure.sessionId());
        scheduled.get(1).discard();
        scheduled.get(2).discard();

        assertEquals(Collections.singletonList("root-a"), executor.commits);
        assertEquals(0, scheduler.outstandingResultCount());
    }

    @Test
    void preparedValueIsSingleUse() {
        RecordingExecutor executor = new RecordingExecutor(null, null);
        BoundedCoordinationRootScheduler.Result<FakePrepared> scheduled =
                scheduler(executor).schedule(
                        event("event-single-use"),
                        Collections.singletonList(target("root-a")),
                        PrefetchPolicy.MINIMUM_BYTES).get(0);

        scheduled.awaitPrepared();
        scheduled.commit();
        assertThrows(IllegalStateException.class, scheduled::commit);
        assertThrows(IllegalStateException.class, scheduled::discard);
    }

    @Test
    void rejectsCommittedEvidenceForAnotherEvent() {
        CoordinationTwoPhaseDeliveryExecutor<FakePrepared> wrongEvidence =
                new CoordinationTwoPhaseDeliveryExecutor<FakePrepared>() {
                    @Override
                    public FakePrepared prepare(
                            StoredCoordinationEvent event,
                            IndexedSessionCandidates target,
                            PrefetchPolicy prefetchPolicy) {
                        return new FakePrepared(event, target);
                    }

                    @Override
                    public CoordinationCommittedDelivery commit(
                            FakePrepared prepared) {
                        return new CoordinationCommittedDelivery(
                                "another-event",
                                prepared.target.sessionId(),
                                prepared.target.plannedEpoch(),
                                prepared.target.plannedRootBlueId(),
                                prepared.target.plannedEpoch() + 1L,
                                "root-after",
                                "transition",
                                Collections.<String>emptyList());
                    }
                };
        BoundedCoordinationRootScheduler<FakePrepared> scheduler =
                new BoundedCoordinationRootScheduler<FakePrepared>(
                        pool,
                        wrongEvidence,
                        new CoordinationParallelismPolicy(1, true),
                        CoordinationRootPreparationObserver.none());
        BoundedCoordinationRootScheduler.Result<FakePrepared> result =
                scheduler.schedule(
                        event("expected-event"),
                        Collections.singletonList(target("root-a")),
                        PrefetchPolicy.MINIMUM_BYTES).get(0);

        result.awaitPrepared();
        assertThrows(IllegalStateException.class, result::commit);
        result.discard();
    }

    private BoundedCoordinationRootScheduler<FakePrepared> scheduler(
            RecordingExecutor executor) {
        return new BoundedCoordinationRootScheduler<FakePrepared>(
                pool,
                executor,
                new CoordinationParallelismPolicy(2, true),
                CoordinationRootPreparationObserver.none());
    }

    private static StoredCoordinationEvent event(String blueId) {
        return new StoredCoordinationEvent(
                blueId,
                blueId + "-inventory",
                ExternalOrderKey.of(Arrays.<Object>asList(1L, blueId)));
    }

    private static IndexedSessionCandidates target(String session) {
        return new IndexedSessionCandidates(
                DocumentSessionId.of(session),
                Collections.singletonList("occurrence-" + session),
                1,
                0L,
                "root-before-" + session,
                "subscriptions-" + session);
    }

    private static List<String> sessionValues(
            List<BoundedCoordinationRootScheduler.Result<FakePrepared>>
                    results) {
        List<String> values = new ArrayList<String>(results.size());
        for (BoundedCoordinationRootScheduler.Result<FakePrepared> result
                : results) {
            values.add(result.target().sessionId().value());
        }
        return values;
    }

    private static final class FakePrepared {
        private final StoredCoordinationEvent event;
        private final IndexedSessionCandidates target;

        private FakePrepared(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target) {
            this.event = event;
            this.target = target;
        }
    }

    private static final class RecordingExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<FakePrepared> {
        private final CyclicBarrier barrier;
        private final String failingSession;
        private final List<String> prepares =
                Collections.synchronizedList(new ArrayList<String>());
        private final List<String> commits =
                Collections.synchronizedList(new ArrayList<String>());

        private RecordingExecutor(
                CyclicBarrier barrier,
                String failingSession) {
            this.barrier = barrier;
            this.failingSession = failingSession;
        }

        @Override
        public FakePrepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            prepares.add(session);
            if (barrier != null && ("root-a".equals(session)
                    || "root-b".equals(session))) {
                awaitBarrier(barrier);
            }
            if (session.equals(failingSession)) {
                throw new IllegalStateException("injected " + session);
            }
            return new FakePrepared(event, target);
        }

        @Override
        public CoordinationCommittedDelivery commit(FakePrepared prepared) {
            String session = prepared.target.sessionId().value();
            commits.add(session);
            return new CoordinationCommittedDelivery(
                    prepared.event.eventBlueId(),
                    prepared.target.sessionId(),
                    prepared.target.plannedEpoch(),
                    prepared.target.plannedRootBlueId(),
                    prepared.target.plannedEpoch() + 1L,
                    "root-after-" + session,
                    "transition-" + session,
                    Collections.<String>emptyList());
        }

        private static void awaitBarrier(CyclicBarrier barrier) {
            try {
                barrier.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } catch (BrokenBarrierException | TimeoutException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
