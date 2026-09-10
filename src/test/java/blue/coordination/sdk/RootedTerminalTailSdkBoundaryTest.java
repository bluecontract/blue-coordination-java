package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ManagedRepresentationTransition;
import blue.language.processor.closure.ManagedRevisionCause;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine public source/consumer sequences; private controls only read inputs or invoke the exact negative admission boundary. */
final class RootedTerminalTailSdkBoundaryTest {
    @Test void numberedSuccessorGasBoundaryRollsBackExactProgressAndSurvivesRestart() throws Exception {
        // given
        long gas;
        // when
        try (var calibration = new RootedTerminalTailSdkScenario(100_000L, false, 25)) {
            var input = calibration.f.control.captureRegisteredOwnedHistory(calibration.consumer.id());
            var historicalProof = ((ManagedRevisionCause) input.cause()).successorRepresentationCause()
                    .orElseThrow().transition().originalInput().snapshot();
            // then
            assertTrue(historicalProof.managedDocuments().stream().anyMatch(document -> document.blueId()
                    .equals("G2Nwyqm7RXu15tudRxqa6fyh5XRWwZsxKioYdKP8F3Ff")),
                    "The exact demand from the empty reference store is already authenticated by the supplied proof");
            var reference = RootedCalculationFixture.materializedReference(input);
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic()));
            assertTrue(((ManagedRevisionCause) input.cause()).successorRepresentationCause().isPresent());
            gas = reference.totalGas(); assertTrue(gas > 1L && gas < 100_000L);
        }
        for (long delta : List.of(-1L, 0L, 1L)) {
            try (var scenario = new RootedTerminalTailSdkScenario(gas + delta, false, 25)) {
                var f = scenario.f;
                var work = f.control.registeredOwnedHistory(scenario.consumer.id());
                var input = f.control.captureRegisteredOwnedHistory(scenario.consumer.id());
                assertEquals(gas + delta, input.executionPolicy().sharedLimit());
                var reference = RootedCalculationFixture.materializedReference(input);
                var beforeSource = scenario.sourceState(); var beforeConsumer = scenario.consumerProgress();
                var drained = f.blue.processing().processNext(scenario.consumer);
                assertTrue(drained.entries().isEmpty()); assertTrue(drained.rootedRetainedApplications().isEmpty());
                assertEquals(1, drained.managedEpochApplicationAttempts().size());
                var attempt = drained.managedEpochApplicationAttempts().get(0);
                assertEquals(work.workIdentity(), attempt.work().workIdentity());
                assertTrue(attempt.work().representationStep().isEmpty());
                assertTrue(attempt.work().successorRepresentationStep().isPresent());
                assertTrue(attempt.attempt().isComplete());
                var actual = attempt.attempt().processResult();
                assertEquals(reference.status().name(), actual.status().name());
                assertEquals(reference.totalGas(), actual.totalGas());
                assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity());
                assertEquals(input.snapshot().closureIdentity(), actual.inputClosureIdentity());
                assertEquals(beforeSource, scenario.sourceState());
                assertFalse(f.blue.advanced().auditManagedDocumentReadiness(scenario.consumer.id()).orElseThrow().ready());
                if (delta < 0) {
                    assertEquals(ManagedEpochApplicationAttempt.Status.GAS_LIMIT_EXCEEDED, actual.status());
                    assertFalse(attempt.published()); assertTrue(attempt.receipt().isEmpty());
                    assertTrue(actual.rollbackToInput()); assertEquals(actual.inputClosureIdentity(), actual.outputClosureIdentity());
                    assertTrue(actual.publicEvents().isEmpty());
                    assertEquals(reference.rejectedCharge().rejectedChargeIdentity(), actual.rejectedCharge().rejectedChargeIdentity());
                    assertEquals(beforeConsumer, scenario.consumerProgress());
                    assertEquals(work.workIdentity(), f.control.registeredOwnedHistory(scenario.consumer.id()).workIdentity());
                    CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                    var again = f.blue.processing().processNext(scenario.consumer).managedEpochApplicationAttempts().get(0);
                    assertEquals(work.workIdentity(), again.work().workIdentity());
                    assertEquals(actual.gasTraceIdentity(), again.attempt().processResult().gasTraceIdentity());
                    assertFalse(again.published()); assertTrue(again.receipt().isEmpty());
                    assertEquals(beforeConsumer, scenario.consumerProgress());
                } else {
                    assertTrue(attempt.published()); assertTrue(actual.commits());
                    assertEquals(gas, actual.totalGas());
                    var receipt = attempt.receipt().orElseThrow();
                    assertTrue(receipt.representationCauseIdentity().isEmpty());
                    var successor = work.successorRepresentationCause().orElseThrow();
                    assertEquals(successor.causeIdentity(), receipt.successorRepresentationCauseIdentity().orElseThrow());
                    var selected = f.control.selectedView(scenario.consumer.id());
                    var occurrence = selected.occurrences().stream().filter(row -> row.occurrenceIdentity()
                            .equals(work.targetOccurrenceIdentity())).findFirst().orElseThrow();
                    assertFalse(occurrence.active());
                    assertEquals(new ManagedRepresentationCursor(work.sourceReceiptIdentity(), work.sourceReceiptIdentity(),
                            successor.targetPositionIdentity(), null), occurrence.pendingRepresentationCursor());
                    assertEquals(Long.valueOf(work.sourceEpoch()), occurrence.pendingHistoricalEpoch());
                    var completedAnchor = scenario.consumerProgress();
                    var next = f.control.registeredOwnedHistory(scenario.consumer.id());
                    assertTrue(next.isRepresentationApplication());
                    assertEquals(scenario.positions.get(0).positionIdentity(), next.representationCause().orElseThrow().transition().positionIdentity());
                    CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                    assertEquals(completedAnchor, scenario.consumerProgress());
                    assertEquals(next.workIdentity(), f.control.registeredOwnedHistory(scenario.consumer.id()).workIdentity());
                }
                assertEquals(beforeSource, scenario.sourceState());
            }
        }
    }

    @Test void recomputedEarlierAndFutureGoalsCannotReplaceTheCanonicalFrozenWork() throws Exception {
        // given
        try (var scenario = new RootedTerminalTailSdkScenario(100_000L, true, 1)) {
            // when
            var f = scenario.f;
            var actual = f.control.registeredOwnedHistory(scenario.consumer.id());
            var successor = actual.successorRepresentationCause().orElseThrow();
            var unchangedSource = scenario.sourceState(); var unchangedConsumer = scenario.consumerProgress();
            // then
            assertEquals(3, scenario.positions.size(), "The future target is an actual committed500 position");
            assertEquals(scenario.positions.get(1).positionIdentity(), successor.targetPositionIdentity());
            var controls = CoordinationTestControl.attach(f.blue.advanced().rawEngine());
            long processingCalls = controls.metricsSnapshot().counters().getOrDefault("managedEpoch.catchUp.processCalls", 0L);
            var coordinates = coordinates(actual, actual.sourceReceiptIdentity());
            for (var target : List.of(scenario.positions.get(0), scenario.positions.get(2))) {
                f.control.verifySuppliedRepresentationPosition(target);
                var forgedCause = new ManagedRepresentationCause(actual.targetOccurrenceIdentity(), successor.transition(),
                        target.positionIdentity(), null, successor.afterCyclicProof().orElse(null));
                var forged = blue.coordination.api.ManagedEpochApplicationWork.identifiedWithSuccessorRepresentationCause(coordinates, forgedCause);
                assertNotEquals(actual.workIdentity(), forged.workIdentity());
                var failure = assertThrows(RuntimeException.class, () -> f.control.rejectNoncanonicalOwnedHistoryCandidate(forged));
                assertTrue(failure.getMessage().contains("outside canonical due order"), failure.toString());
                assertEquals(processingCalls, controls.metricsSnapshot().counters().getOrDefault("managedEpoch.catchUp.processCalls", 0L).longValue(),
                        "Recomputed noncanonical work must reject before PROCESS");
                assertEquals(actual.workIdentity(), f.control.registeredOwnedHistory(scenario.consumer.id()).workIdentity());
                assertEquals(unchangedSource, scenario.sourceState()); assertEquals(unchangedConsumer, scenario.consumerProgress());
            }
            var emptyGoal = new ManagedRepresentationCause(actual.targetOccurrenceIdentity(), successor.transition(),
                    actual.sourceReceiptIdentity(), null, successor.afterCyclicProof().orElse(null));
            assertThrows(IllegalArgumentException.class, () -> blue.coordination.api.ManagedEpochApplicationWork
                    .identifiedWithSuccessorRepresentationCause(coordinates, emptyGoal));
            String wrongAnchor = "sha256:" + "f".repeat(64);
            assertNotEquals(actual.sourceReceiptIdentity(), wrongAnchor);
            assertThrows(IllegalArgumentException.class, () -> blue.coordination.api.ManagedEpochApplicationWork
                    .identifiedWithSuccessorRepresentationCause(coordinates(actual, wrongAnchor), successor));
            var original = successor.transition();
            var forgedPosition = new ManagedRepresentationTransition(original.documentId(), original.epoch(),
                    wrongAnchor, wrongAnchor, original.originalInput(), original.originalResult(),
                    original.transitionReceipt().transitionReceiptIdentity());
            assertThrows(IllegalArgumentException.class, () -> f.control.verifySuppliedRepresentationPosition(forgedPosition));
            assertEquals(unchangedSource, scenario.sourceState()); assertEquals(unchangedConsumer, scenario.consumerProgress());
            scenario.finishExactlyTwoPositions();
        }
    }

    private static blue.coordination.api.ManagedEpochApplicationWork coordinates(
            blue.coordination.api.ManagedEpochApplicationWork work, String sourceReceipt) {
        return blue.coordination.api.ManagedEpochApplicationWork.identified(work.planIdentity(), work.barrierIdentity(), sourceReceipt,
                work.sourceDocumentId(), work.sourceEpoch(), work.consumerDocumentId(), work.targetOccurrenceIdentity(), work.targetPath(),
                work.activationGeneration(), work.expectedConsumerCommittedEpoch(), work.expectedConsumerCommittedBlueId(), work.expectedGraphGeneration());
    }
}
