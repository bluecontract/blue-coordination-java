package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.language.model.Node;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.TentativeFinalization;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused wiring proof for non-semantic closure execution evidence. */
final class ContractsClosureExecutionMetricsObserverTest {
    private static final DocumentId A = DocumentId.of("metrics-cycle-a");
    private static final DocumentId B = DocumentId.of("metrics-cycle-b");
    private static final String LANGUAGE_SPEC = sha('a');
    private static final String CONTRACTS_SPEC = sha('b');
    private static final long ENTRY_TIME = 1_950_000_000_000_001L;

    @Test
    void observerIgnoresNullEvidenceWithoutPublishingDiagnostics() {
        ContractsClosureExecutionMetricsObserver observer =
                new ContractsClosureExecutionMetricsObserver(
                        new EngineMetrics());

        assertDoesNotThrow(() -> observer.onExecutionEvidence(null));
        assertTrue(observer.lastEvidence().isEmpty());
    }

    @Test
    void admissionAndProcessPublishExactEvidenceAndMatchingRawMetrics() {
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(
                             new Contracts10Configuration(
                                     LANGUAGE_SPEC,
                                     CONTRACTS_SPEC,
                                     Set.of(A)))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            CoordinationTestControl control =
                    CoordinationTestControl.attach(publicEngine);
            Contracts10ScenarioBuilder builder = scenario(engine);

            CoordinationTestControl.MetricsSnapshot beforeAdmission =
                    control.metricsSnapshot();
            ContractsClosureAdmissionReceipt admission = builder
                    .admitTo(publicEngine)
                    .admissionReceipt();
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admission.publicationOutcome());
            ClosureImplementationEvidence admissionEvidence = control
                    .lastClosureAdmissionEvidence()
                    .orElseThrow();
            assertTrue(admissionEvidence.complete());
            assertEvidenceMetrics(
                    beforeAdmission,
                    control.metricsSnapshot(),
                    admissionEvidence);
            assertFalse(admissionEvidence.tentativeFinalizations().isEmpty());

            CoordinationTestControl.MetricsSnapshot beforeProcess =
                    control.metricsSnapshot();
            Timeline timeline = publicEngine.registerTimeline(
                    "metrics/cycle", "alice");
            publicEngine.appendAt(
                    timeline,
                    Operation.yaml("advance", "ownerChannel", "{}"),
                    ENTRY_TIME);
            ProcessingDrainReceipt drained = publicEngine.drain();
            assertTrue(drained.quiescent());
            assertFalse(drained.processedEntries().isEmpty());

            ClosureImplementationEvidence processEvidence = control
                    .lastClosureProcessEvidence()
                    .orElseThrow();
            assertTrue(processEvidence.complete());
            CoordinationTestControl.MetricsSnapshot afterProcess =
                    control.metricsSnapshot();
            assertEvidenceMetrics(
                    beforeProcess,
                    afterProcess,
                    processEvidence);
            assertTrue(phaseDelta(
                    beforeProcess,
                    afterProcess,
                    ContractsClosureAdapter.PROCESSOR_PHASE) > 0L);
            assertTrue(phaseDelta(
                    beforeProcess,
                    afterProcess,
                    ContractsClosureAdapter.RESULT_VALIDATION_PHASE) > 0L);
            assertTrue(phaseDelta(
                    beforeProcess,
                    afterProcess,
                    ContractsClosureAdapter.PUBLICATION_PHASE) > 0L);
            assertFalse(processEvidence.workTrace().isEmpty());
            assertFalse(processEvidence.tentativeFinalizations().isEmpty());
            assertSame(
                    admissionEvidence,
                    control.lastClosureAdmissionEvidence().orElseThrow());
            assertSame(
                    processEvidence,
                    control.lastClosureProcessEvidence().orElseThrow());
        }
    }

    private static Contracts10ScenarioBuilder scenario(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(A, publicDocument())
                .document(B, new Node().properties(
                        "phase", new Node().value("initial")))
                .processEmbeddedPath(A, "/b", B)
                .processEmbeddedPath(B, "/a", A)
                .publicRoot(A)
                .expectedComponent(A, B)
                .admissionLabel("contracts-closure-metrics-observer");
    }

    private static String publicDocument() {
        return """
                documentId: metrics-cycle-a
                phase: initial
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: metrics/cycle
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  advance:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                          - $return: true
                """;
    }

    private static void assertEvidenceMetrics(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            ClosureImplementationEvidence evidence) {
        assertEquals(
                evidence.workTrace().size(),
                delta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .ACCEPTED_WORK_OCCURRENCES));
        assertEquals(
                evidence.documentStepTrace().size(),
                delta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .ISOLATED_DOCUMENT_STEPS));
        assertEquals(
                evidence.tentativeFinalizations().size(),
                delta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .TENTATIVE_COMPONENT_FINALIZATIONS));
        assertEquals(
                canonicalBytes(evidence),
                delta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .CANONICAL_CYCLIC_BYTES));
        assertEquals(
                evidence.managedDocumentStepInclusiveNanos(),
                phaseDelta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .MANAGED_DOCUMENT_STEP_INCLUSIVE_PHASE));
        assertEquals(
                evidence.managedDocumentStepExclusiveNanos(),
                phaseDelta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .MANAGED_DOCUMENT_STEP_EXCLUSIVE_PHASE));
        assertEquals(
                evidence.componentFinalizationProofNanos(),
                phaseDelta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .COMPONENT_FINALIZATION_PROOF_PHASE));
        assertEquals(
                evidence.successfulResultAssemblyNanos(),
                phaseDelta(
                        before,
                        after,
                        ContractsClosureExecutionMetricsObserver
                                .SUCCESSFUL_RESULT_ASSEMBLY_PHASE));
    }

    private static long canonicalBytes(
            ClosureImplementationEvidence evidence) {
        long result = 0L;
        for (TentativeFinalization finalization
                : evidence.tentativeFinalizations()) {
            result = Math.addExact(result, finalization.canonicalBytes());
        }
        return result;
    }

    private static long delta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String name) {
        return after.counters().getOrDefault(name, 0L)
                - before.counters().getOrDefault(name, 0L);
    }

    private static long phaseDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String name) {
        return after.phaseNanos().getOrDefault(name, 0L)
                - before.phaseNanos().getOrDefault(name, 0L);
    }

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }
}
