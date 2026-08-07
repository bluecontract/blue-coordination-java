package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationCommittedDelivery;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Named Round 3 proof for bounded compute and canonical publication. */
final class ParallelRootDispatchTest {

    @Test
    void shouldMatchSerialSemanticsAtTwoAndFourConfiguredWorkers()
            throws Exception {
        // given
        List<Integer> configuredLimits = Arrays.asList(2, 4);

        // when
        List<DispatchProof> proofs = new ArrayList<>();
        for (int configuredLimit : configuredLimits) {
            proofs.add(dispatchWithControlledCompletion(configuredLimit));
        }

        // then
        for (DispatchProof proof : proofs) {
            assertEquals(3, proof.parallelSnapshot().plan().targetCount());
            assertEquals(1, proof.parallelIndexQueries());
            assertEquals(Math.min(3, proof.configuredLimit()),
                    proof.peakPreparations());
            assertTrue(proof.peakPreparations() <= proof.configuredLimit());
            assertNotEquals(proof.completionOrder(), proof.commitOrder());
            assertEquals(Arrays.asList("root-a", "root-b", "root-c"),
                    proof.commitOrder());
            assertEquals(proof.serialStates(), proof.parallelStates());
            assertEquals(proof.serialReceiptSignatures(),
                    proof.parallelReceiptSignatures());
            assertEquals(proof.serialCommitOrder(), proof.commitOrder());
        }
    }

    private static DispatchProof dispatchWithControlledCompletion(
            int configuredLimit) throws Exception {
        StoredCoordinationEvent event = ParallelRootAcceptanceSupport.event(
                "parallel-dispatch-" + configuredLimit);
        ParallelRootAcceptanceSupport.FixedIndex parallelIndex =
                new ParallelRootAcceptanceSupport.FixedIndex(
                        ParallelRootAcceptanceSupport
                                .threeTargetsOutOfOrder());
        InMemoryCoordinationDispatchLedger parallelLedger =
                new InMemoryCoordinationDispatchLedger();
        ControlledTwoPhaseExecutor twoPhase =
                new ControlledTwoPhaseExecutor();
        ExecutorService preparationPool = Executors.newFixedThreadPool(
                configuredLimit);
        ExecutorService dispatchCaller = Executors.newSingleThreadExecutor();
        BoundedCoordinationRootScheduler<Prepared> scheduler =
                new BoundedCoordinationRootScheduler<>(
                        preparationPool,
                        twoPhase,
                        new CoordinationParallelismPolicy(
                                configuredLimit, true),
                        CoordinationRootPreparationObserver.none());
        InMemoryCoordinationFanout parallel =
                InMemoryCoordinationFanout.parallel(
                        parallelIndex,
                        parallelLedger,
                        scheduler,
                        CoordinationCommittedDeliveryProbe.none());
        CoordinationDispatchSnapshot parallelSnapshot;
        try {
            Future<CoordinationDispatchSnapshot> running =
                    dispatchCaller.submit(() -> parallel.dispatch(
                            event,
                            Collections.singletonList("actor:alice"),
                            "ownerChannel",
                            3,
                            PrefetchPolicy.MINIMUM_ROUND_TRIPS));
            twoPhase.awaitStarted("root-a");
            twoPhase.awaitStarted("root-b");
            if (configuredLimit >= 3) {
                twoPhase.awaitStarted("root-c");
            }
            twoPhase.releaseAndAwaitCompletion("root-b");
            twoPhase.awaitStarted("root-c");
            twoPhase.releaseAndAwaitCompletion("root-c");
            twoPhase.releaseAndAwaitCompletion("root-a");
            parallelSnapshot = running.get(5L, TimeUnit.SECONDS);
        } finally {
            dispatchCaller.shutdownNow();
            preparationPool.shutdownNow();
            assertTrue(dispatchCaller.awaitTermination(
                    5L, TimeUnit.SECONDS));
            assertTrue(preparationPool.awaitTermination(
                    5L, TimeUnit.SECONDS));
        }

        ParallelRootAcceptanceSupport.FixedIndex serialIndex =
                new ParallelRootAcceptanceSupport.FixedIndex(
                        ParallelRootAcceptanceSupport
                                .threeTargetsOutOfOrder());
        ParallelRootAcceptanceSupport.SerialSemanticExecutor serialExecutor =
                new ParallelRootAcceptanceSupport.SerialSemanticExecutor();
        CoordinationDispatchSnapshot serialSnapshot =
                new InMemoryCoordinationFanout(
                        serialIndex,
                        new InMemoryCoordinationDispatchLedger(),
                        serialExecutor).dispatch(
                                event,
                                Collections.singletonList("actor:alice"),
                                "ownerChannel",
                                3,
                                PrefetchPolicy.MINIMUM_ROUND_TRIPS);

        assertTrue(parallelSnapshot.complete());
        assertTrue(serialSnapshot.complete());
        assertTrue(scheduler.isQuiescent());
        return new DispatchProof(
                configuredLimit,
                scheduler.peakPreparationCount(),
                parallelIndex.queryCount(),
                parallelSnapshot,
                twoPhase.completionOrder(),
                twoPhase.commitOrder(),
                twoPhase.states(),
                serialExecutor.commits(),
                serialExecutor.states(),
                ParallelRootAcceptanceSupport.receiptSignatures(
                        parallelSnapshot),
                ParallelRootAcceptanceSupport.receiptSignatures(
                        serialSnapshot));
    }

    private static final class DispatchProof {
        private final int configuredLimit;
        private final int peakPreparations;
        private final int parallelIndexQueries;
        private final CoordinationDispatchSnapshot parallelSnapshot;
        private final List<String> completionOrder;
        private final List<String> commitOrder;
        private final Map<String,
                ParallelRootAcceptanceSupport.SemanticState> parallelStates;
        private final List<String> serialCommitOrder;
        private final Map<String,
                ParallelRootAcceptanceSupport.SemanticState> serialStates;
        private final List<ParallelRootAcceptanceSupport.ReceiptSignature>
                parallelReceiptSignatures;
        private final List<ParallelRootAcceptanceSupport.ReceiptSignature>
                serialReceiptSignatures;

        private DispatchProof(
                int configuredLimit,
                int peakPreparations,
                int parallelIndexQueries,
                CoordinationDispatchSnapshot parallelSnapshot,
                List<String> completionOrder,
                List<String> commitOrder,
                Map<String, ParallelRootAcceptanceSupport.SemanticState>
                        parallelStates,
                List<String> serialCommitOrder,
                Map<String, ParallelRootAcceptanceSupport.SemanticState>
                        serialStates,
                List<ParallelRootAcceptanceSupport.ReceiptSignature>
                        parallelReceiptSignatures,
                List<ParallelRootAcceptanceSupport.ReceiptSignature>
                        serialReceiptSignatures) {
            this.configuredLimit = configuredLimit;
            this.peakPreparations = peakPreparations;
            this.parallelIndexQueries = parallelIndexQueries;
            this.parallelSnapshot = parallelSnapshot;
            this.completionOrder = completionOrder;
            this.commitOrder = commitOrder;
            this.parallelStates = parallelStates;
            this.serialCommitOrder = serialCommitOrder;
            this.serialStates = serialStates;
            this.parallelReceiptSignatures = parallelReceiptSignatures;
            this.serialReceiptSignatures = serialReceiptSignatures;
        }

        int configuredLimit() { return configuredLimit; }
        int peakPreparations() { return peakPreparations; }
        int parallelIndexQueries() { return parallelIndexQueries; }
        CoordinationDispatchSnapshot parallelSnapshot() {
            return parallelSnapshot;
        }
        List<String> completionOrder() { return completionOrder; }
        List<String> commitOrder() { return commitOrder; }
        Map<String, ParallelRootAcceptanceSupport.SemanticState>
                parallelStates() { return parallelStates; }
        List<String> serialCommitOrder() { return serialCommitOrder; }
        Map<String, ParallelRootAcceptanceSupport.SemanticState>
                serialStates() { return serialStates; }
        List<ParallelRootAcceptanceSupport.ReceiptSignature>
                parallelReceiptSignatures() {
            return parallelReceiptSignatures;
        }
        List<ParallelRootAcceptanceSupport.ReceiptSignature>
                serialReceiptSignatures() {
            return serialReceiptSignatures;
        }
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

        StoredCoordinationEvent event() { return event; }
        IndexedSessionCandidates target() { return target; }
    }

    private static final class ControlledTwoPhaseExecutor
            implements CoordinationTwoPhaseDeliveryExecutor<Prepared> {
        private final Map<String, CountDownLatch> started = latches();
        private final Map<String, CountDownLatch> releases = latches();
        private final Map<String, CountDownLatch> completed = latches();
        private final List<String> completionOrder =
                Collections.synchronizedList(new ArrayList<>());
        private final List<String> commitOrder =
                Collections.synchronizedList(new ArrayList<>());
        private final Map<String, ParallelRootAcceptanceSupport.SemanticState>
                states = Collections.synchronizedMap(new LinkedHashMap<>());

        @Override
        public Prepared prepare(
                StoredCoordinationEvent event,
                IndexedSessionCandidates target,
                PrefetchPolicy prefetchPolicy) {
            String session = target.sessionId().value();
            started.get(session).countDown();
            await(releases.get(session), "release " + session);
            completionOrder.add(session);
            completed.get(session).countDown();
            return new Prepared(event, target);
        }

        @Override
        public CoordinationCommittedDelivery commit(Prepared prepared) {
            String session = prepared.target().sessionId().value();
            commitOrder.add(session);
            states.put(session,
                    ParallelRootAcceptanceSupport.semanticState(session));
            return ParallelRootAcceptanceSupport.committed(
                    prepared.event(), prepared.target());
        }

        void awaitStarted(String session) {
            await(started.get(session), "start " + session);
        }

        void releaseAndAwaitCompletion(String session) {
            releases.get(session).countDown();
            await(completed.get(session), "complete " + session);
        }

        List<String> completionOrder() {
            synchronized (completionOrder) {
                return Collections.unmodifiableList(
                        new ArrayList<String>(completionOrder));
            }
        }

        List<String> commitOrder() {
            synchronized (commitOrder) {
                return Collections.unmodifiableList(
                        new ArrayList<String>(commitOrder));
            }
        }

        Map<String, ParallelRootAcceptanceSupport.SemanticState> states() {
            synchronized (states) {
                return Collections.unmodifiableMap(
                        new LinkedHashMap<>(states));
            }
        }

        private static Map<String, CountDownLatch> latches() {
            Map<String, CountDownLatch> result = new LinkedHashMap<>();
            result.put("root-a", new CountDownLatch(1));
            result.put("root-b", new CountDownLatch(1));
            result.put("root-c", new CountDownLatch(1));
            return result;
        }

        private static void await(CountDownLatch latch, String boundary) {
            try {
                if (!latch.await(5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting for " + boundary);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
