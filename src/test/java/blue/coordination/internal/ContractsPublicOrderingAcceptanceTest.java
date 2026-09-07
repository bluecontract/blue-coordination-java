package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-facade acceptance for topology changes and frozen work ordering. */
final class ContractsPublicOrderingAcceptanceTest {
    private static final int UNRELATED_ADMISSION_BATCH_SIZE = 25;
    private static final DocumentId A = DocumentId.of("ordering-a");
    private static final DocumentId B = DocumentId.of("ordering-b");
    private static final String SHA_A = sha('a');
    private static final String SHA_B = sha('b');
    private static final long ENTRY_TIME = 1_900_000_000_000_001L;

    @Test
    void publicDrainFormsCycleFromAcyclicBToAWithoutReplayingDirectWork() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(B));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            dynamicCycleAdmission(engine),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(List.of(A, B), admitted.documentIds());
            assertEquals(2, engine.documents().publicationSnapshot()
                    .componentStates().size());
            assertTrue(engine.documents().publicationSnapshot()
                    .componentStates().stream()
                    .allMatch(component -> component.kind()
                            == ComponentKind.ACYCLIC));
            assertEquals(1, engine.documents().publicationSnapshot()
                    .occurrenceInventory().activeRows().size());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .occurrenceInventory().rows().size());
            assertEquals(B.value(), engine.documents().publicationSnapshot()
                    .occurrenceInventory().activeRows().get(0)
                    .sourceDocumentId().value());
            assertEquals(A.value(), engine.documents().publicationSnapshot()
                    .occurrenceInventory().activeRows().get(0)
                    .targetDocumentId().value());
            String beforeA = publicEngine.document(A).blueId();
            String beforeB = publicEngine.document(B).blueId();
            ExactValue request = publicEngine.referenceRequest(
                    "b", publicEngine.document(B).current());
            Timeline timeline = publicEngine.registerTimeline(
                    "ordering/dynamic", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.exact("start", "aliceChannel", request),
                    ENTRY_TIME);

            assertEquals(1, publicEngine.routeTargetCount(entry));
            ContractsClosureAdapter.FrozenBatch initial = engine
                    .contractsClosureAdapter().capture(entry);
            assertEquals(1, initial.invocations().size());
            assertEquals(List.of(A, B), initial.invocations().get(0).members());
            assertEquals(1, initial.invocations().get(0).input().snapshot()
                    .occurrences().size());
            ProcessingDrainReceipt drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(2L, drained.committedProcessTransitions());
            assertEquals(List.of(A, B), drained.outcomesFor(entry.blueId())
                    .stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals("done", property(publicEngine, A, "phase"));
            assertEquals("relayed", property(publicEngine, B, "phase"));
            assertEquals(1L, publicEngine.document(A).epoch());
            assertEquals(1L, publicEngine.document(B).epoch());
            assertNotEquals(beforeA, publicEngine.document(A).blueId());
            assertNotEquals(beforeB, publicEngine.document(B).blueId());
            assertEquals(master(publicEngine.document(A).blueId()),
                    master(publicEngine.document(B).blueId()));

            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            assertEquals(1L, receipt.automaticRetryCount());
            ClosureProcessResult result = receipt.attempt().processResult();
            assertTrue(result.commits());
            assertEquals(List.of(A.value(), B.value(), A.value()),
                    dequeuedDocumentIds(result));
            assertEquals(3, dequeuedWorkIds(result).size());
            assertEquals(3, Set.copyOf(dequeuedWorkIds(result)).size());
            assertEquals(1L, result.graphChanges().stream()
                    .filter(change -> change.changeKind()
                            == GraphChange.Kind.ADD)
                    .filter(change -> change.sourceDocumentId().value()
                            .equals(A.value()))
                    .filter(change -> change.sourcePath().equals("/b"))
                    .count());
            assertEquals(2, result.occurrenceBindings().stream()
                    .filter(ManagedOccurrenceBinding::active)
                    .count());
            assertEquals(1, result.resultingComponents().size());
            ComponentSnapshot component = result.resultingComponents().get(0);
            assertCompleteCyclicComponent(component);
            assertEquals(List.of(A.value(), B.value()), component
                    .orderedMemberDocumentIds().stream()
                    .map(documentId -> documentId.value())
                    .toList());
            assertEquals(component.orderedMemberBlueIds(), result
                    .resultingDocuments().stream()
                    .map(document -> document.afterBlueId())
                    .toList());

            Map<String, Long> finalizationsByWork = result.gasTrace().stream()
                    .filter(gas -> "cyclicMemberFinalized".equals(
                            gas.counter()))
                    .filter(gas -> gas.workOccurrenceId() != null)
                    .collect(Collectors.groupingBy(
                            gas -> gas.workOccurrenceId(),
                            LinkedHashMap::new,
                            Collectors.counting()));
            assertEquals(Set.copyOf(dequeuedWorkIds(result)),
                    finalizationsByWork.keySet());
            assertEquals(List.of(4L, 2L, 2L),
                    new ArrayList<>(finalizationsByWork.values()));
            assertEquals(2L, result.gasTrace().stream()
                    .filter(gas -> "cyclicMemberFinalized".equals(
                            gas.counter()))
                    .filter(gas -> gas.workOccurrenceId() == null)
                    .count());
            assertNotNull(result.platformCommitCompanion());
            assertEquals(result.outputClosureIdentity(),
                    result.platformCommitCompanion()
                            .outputClosureIdentity());
            assertEquals(2, publicEngine.history(A).size());
            assertEquals(2, publicEngine.history(B).size());

            Timeline control = publicEngine.registerTimeline(
                    "ordering/dynamic-control", "alice");
            TimelineEntry detach = publicEngine.appendAt(
                    control,
                    Operation.yaml("detachA", "controlChannel", "{}"),
                    ENTRY_TIME + 1L);
            ProcessingDrainReceipt detached = publicEngine.drain();
            assertEquals(List.of(detach), detached.processedEntries());
            assertTrue(engine.documents().publicationSnapshot()
                    .componentStates().stream()
                    .allMatch(state -> state.kind() == ComponentKind.ACYCLIC));
            assertFalse(engine.documents().occurrenceInventory()
                    .row(B, "/a").active());
            assertTrue(engine.documents().occurrenceInventory()
                    .row(A, "/b").active());
            assertFalse(publicEngine.document(A).current().isCyclicMember());
            assertFalse(publicEngine.document(B).current().isCyclicMember());

            TimelineEntry touch = publicEngine.appendAt(
                    control,
                    Operation.yaml("touch", "controlChannel", "{}"),
                    ENTRY_TIME + 2L);
            ProcessingDrainReceipt usable = publicEngine.drain();
            assertEquals(List.of(touch), usable.processedEntries());
            assertEquals("post-detach", property(publicEngine, B, "phase"));
            assertEquals(4, publicEngine.history(A).size());
            assertEquals(4, publicEngine.history(B).size());

            ProcessingDrainReceipt replay = publicEngine.drain();
            assertTrue(replay.quiescent());
            assertTrue(replay.processedEntries().isEmpty());
            assertEquals(0L, replay.committedProcessTransitions());
        }
    }

    @Test
    void automaticCycleFormationGasFailureRollsBackEveryExpandedMember() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(B));
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            publicEngine.admitContractsClosure(
                    dynamicLoopAdmission(engine),
                    CoordinationEngine.AdmissionPolicy.FROM_NOW,
                    null);
            String beforeA = publicEngine.document(A).blueId();
            String beforeB = publicEngine.document(B).blueId();
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            Timeline timeline = publicEngine.registerTimeline(
                    "ordering/dynamic-loop", "alice");
            ExactValue request = publicEngine.referenceRequest(
                    "b", publicEngine.document(B).current());
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.exact("startLoop", "loopChannel", request),
                    ENTRY_TIME);

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();

            // then
            assertEquals(List.of(entry), drained.processedEntries());
            assertTrue(drained.outcomes().isEmpty());
            assertEquals(0L, drained.committedProcessTransitions());
            assertEquals(beforeA, publicEngine.document(A).blueId());
            assertEquals(beforeB, publicEngine.document(B).blueId());
            assertEquals(before.occurrenceInventory().rows(), engine
                    .documents().occurrenceInventory().rows());
            assertEquals(before.componentStates(), engine.documents()
                    .publicationSnapshot().componentStates());
            assertTrue(engine.documents().occurrenceInventory()
                    .find(A, "/b").isEmpty());

            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            assertEquals(1L, receipt.automaticRetryCount());
            ClosureProcessResult result = receipt.attempt().processResult();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, result.status());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertNotNull(result.rejectedWorkOccurrence());
            assertNotNull(result.rejectedCharge());
            assertEquals(
                    engine.contractsClosureAdmissionAdapter().executionPolicy()
                            .sharedLimit() - result.totalGas(),
                    result.rejectedCharge().remainingBeforeCharge());
            assertEquals(1L, engine.documents().metrics().counter(
                    AutomaticOccurrenceResolutionCoordinator.RETRIES));
        }
    }

    @Test
    void operationOnEmbeddedBConnectsCurrentAAndClosesTheCycle() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            aEmbedsBAdmission(engine),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(1, engine.documents().occurrenceInventory()
                    .activeRows().size());
            ManagedOccurrenceBinding aToB = engine.documents()
                    .occurrenceInventory().row(A, "/b");
            assertTrue(aToB.active());
            assertEquals(B.value(), aToB.targetDocumentId().value());
            assertTrue(engine.documents().publicationSnapshot()
                    .componentStates().stream()
                    .allMatch(component -> component.kind()
                            == ComponentKind.ACYCLIC));

            ExactValue request = publicEngine.referenceRequest(
                    "a", publicEngine.document(A).current());
            Timeline timeline = publicEngine.registerTimeline(
                    "ordering/connect-a", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.exact("connectA", "ownerChannel", request),
                    ENTRY_TIME);
            ContractsClosureAdapter.FrozenBatch initial = engine
                    .contractsClosureAdapter().capture(entry);
            assertEquals(1, initial.invocations().size());
            assertEquals(List.of(A, B), initial.invocations().get(0).members());
            assertEquals(List.of(aToB), initial.invocations().get(0).input().snapshot()
                    .occurrences());
            assertEquals(List.of(B.value(), A.value()), initial.invocations().get(0)
                    .input().snapshot().components().stream()
                    .flatMap(component -> component
                            .orderedMemberDocumentIds().stream())
                    .map(blue.language.processor.closure.DocumentId::value)
                    .toList());
            EngineMetrics.MetricsSnapshot metricsBefore = engine
                    .engineMetrics().snapshot();

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();
            EngineMetrics.MetricsSnapshot metricsAfter = engine
                    .engineMetrics().snapshot();

            // then
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(2L, drained.committedProcessTransitions());
            assertEquals(List.of(A, B), drained.outcomesFor(entry.blueId())
                    .stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals("connected", property(publicEngine, B, "phase"));
            ManagedOccurrenceBinding bToA = engine.documents()
                    .occurrenceInventory().row(B, "/a");
            assertTrue(bToA.active());
            assertEquals(A.value(), bToA.targetDocumentId().value());
            assertEquals(1L, bToA.activationGeneration());
            assertEquals(2, engine.documents().occurrenceInventory()
                    .activeRows().size());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .componentStates().size());
            ComponentSnapshot component = engine.documents()
                    .publicationSnapshot().componentStates().get(0);
            assertCompleteCyclicComponent(component);
            assertEquals(List.of(A.value(), B.value()), component
                    .orderedMemberDocumentIds().stream()
                    .map(blue.language.processor.closure.DocumentId::value)
                    .toList());
            assertEquals(master(publicEngine.document(A).blueId()),
                    master(publicEngine.document(B).blueId()));

            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            assertEquals(1L, receipt.automaticRetryCount());
            assertEquals(2L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.ATTEMPTS));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.TYPED_DEMANDS));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.RETRIES));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ManagedOccurrenceResolver.INDEX_LOOKUPS));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ManagedOccurrenceResolver.RESOLVED_CURRENT));
            assertEquals(2L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.DOCUMENT_OPENS));
            assertEquals(0L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS));
            // Both active members are captured in each of the two attempts.
            assertEquals(4L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.COMPONENT_STATES_READ));
            // The connected active parent contributes one reverse occurrence row.
            assertEquals(5L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED));
            ClosureProcessResult result = receipt.attempt().processResult();
            assertTrue(result.commits());
            assertEquals(1L, result.graphChanges().stream()
                    .filter(change -> change.changeKind()
                            == GraphChange.Kind.ADD)
                    .filter(change -> change.sourceDocumentId().value()
                            .equals(B.value()))
                    .filter(change -> change.sourcePath().equals("/a"))
                    .count());
            assertEquals(2, publicEngine.history(A).size());
            assertEquals(2, publicEngine.history(B).size());
        }
    }

    @Test
    void dynamicRetryDoesNotOpenOrLoadProofForOneThousandUnrelatedSessions() {
        // given
        LinkedHashSet<DocumentId> publicRoots = new LinkedHashSet<>();
        publicRoots.add(A);
        publicRoots.addAll(unrelatedAdmissionRoots(1_000));
        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, publicRoots);
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            publicEngine.admitContractsClosure(
                    aEmbedsBAdmission(engine),
                    CoordinationEngine.AdmissionPolicy.FROM_NOW,
                    null);
            admitUnrelatedDocuments(engine, publicEngine, 1_000);

            ExactValue request = publicEngine.referenceRequest(
                    "a", publicEngine.document(A).current());
            Timeline timeline = publicEngine.registerTimeline(
                    "ordering/connect-a", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.exact("connectA", "ownerChannel", request),
                    ENTRY_TIME);
            ContractsClosureAdapter.FrozenBatch initial = engine
                    .contractsClosureAdapter().capture(entry);
            assertEquals(List.of(A, B), initial.invocations().get(0).members());
            EngineMetrics.MetricsSnapshot metricsBefore = engine
                    .engineMetrics().snapshot();

            // when
            ProcessingDrainReceipt drained = publicEngine.drain();
            EngineMetrics.MetricsSnapshot metricsAfter = engine
                    .engineMetrics().snapshot();

            // then
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(2L, drained.committedProcessTransitions());
            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            assertEquals(1L, receipt.automaticRetryCount());
            assertEquals(2L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.ATTEMPTS));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.TYPED_DEMANDS));
            assertEquals(1L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    AutomaticOccurrenceResolutionCoordinator.RETRIES));
            assertEquals(2L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.DOCUMENT_OPENS));
            assertEquals(0L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.UNRELATED_DOCUMENT_OPENS));
            // Both active members are captured in each of the two attempts.
            assertEquals(4L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.COMPONENT_STATES_READ));
            // The connected active parent contributes one reverse occurrence row.
            assertEquals(5L, metricDelta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED));
            assertEquals("connected", property(publicEngine, B, "phase"));
            assertEquals(ComponentKind.CYCLIC, engine.documents()
                    .publicationSnapshot().componentStates().stream()
                    .filter(component -> component
                            .orderedMemberDocumentIds().stream()
                            .anyMatch(documentId -> documentId.value()
                                    .equals(A.value())))
                    .findFirst()
                    .orElseThrow()
                    .kind());
        }
    }

    @Test
    void canonicalResultIgnoresEverySupportedConstructionOrder() {
        // given
        List<OrderingVariant> alternativeOrders = List.of(
                OrderingVariant.REVERSED_DOCUMENT_ADMISSION,
                OrderingVariant.REVERSED_BODY_MAP,
                OrderingVariant.REVERSED_OCCURRENCES,
                OrderingVariant.REVERSED_CYCLIC_INPUT);

        // when
        OrderingEvidence baseline = runSameEntry(OrderingVariant.BASELINE);

        // then
        alternativeOrders.forEach(variant ->
                assertEquals(baseline, runSameEntry(variant)));
    }

    @Test
    void sameEntryUsesCanonicalDirectSeedOrderAndClosesCausedWork() {
        // given
        OrderingVariant variant = OrderingVariant.BASELINE;

        // when
        OrderingEvidence evidence = runSameEntry(variant);

        // then
        assertEquals(2, evidence.directTargetCount());
        assertEquals(List.of(
                        A.value(),
                        B.value(),
                        B.value(),
                        A.value()),
                evidence.dequeueOrder());
        assertEquals(4, evidence.dequeueWorkIds().size());
        assertEquals(4, Set.copyOf(evidence.dequeueWorkIds()).size());
        assertEquals(2, evidence.publishedEventBlueIds().size());
        assertEquals(List.of("from-a", "from-b"),
                evidence.publishedEventKinds());
        assertTrue(evidence.totalGas() > 0L);
    }

    private static OrderingEvidence runSameEntry(OrderingVariant variant) {
        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A, B));
        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            sameEntryAdmission(engine, variant),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(List.of(A, B), admitted.documentIds());

            Timeline timeline = publicEngine.registerTimeline(
                    "ordering/shared", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("start", "sharedChannel", "{}"),
                    ENTRY_TIME);
            int directTargets = publicEngine.routeTargetCount(entry);
            assertEquals(2, directTargets);

            ProcessingDrainReceipt drained = publicEngine.drain();
            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(2L, drained.committedProcessTransitions());
            assertEquals(List.of(A, B), drained.outcomesFor(entry.blueId())
                    .stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals("reacted-a", property(publicEngine, A, "phase"));
            // A's first direct event causes B before B's already-frozen
            // direct seed; B's direct transition therefore settles last.
            assertEquals("direct-b", property(publicEngine, B, "phase"));
            assertEquals(1L, publicEngine.document(A).epoch());
            assertEquals(1L, publicEngine.document(B).epoch());

            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            ClosureProcessResult result = receipt.attempt().processResult();
            assertTrue(result.commits());
            assertNotNull(result.platformCommitCompanion());
            assertEquals(List.of(
                            A.value(),
                            B.value(),
                            B.value(),
                            A.value()),
                    dequeuedDocumentIds(result));
            ComponentSnapshot component = result.resultingComponents().get(0);
            assertCompleteCyclicComponent(component);

            return new OrderingEvidence(
                    admitted.publicationIdentity(),
                    receipt.publicationIdentity(),
                    entry.blueId(),
                    directTargets,
                    publicEngine.document(A).blueId(),
                    publicEngine.document(B).blueId(),
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    component.masterBlueId(),
                    component.cyclicProofIdentity(),
                    component.completeCyclicProof()
                            .declaredPlaceholderSet().stream()
                            .map(NodeWireForm::get)
                            .toList(),
                    dequeuedDocumentIds(result),
                    dequeuedWorkIds(result),
                    result.gasTraceIdentity(),
                    result.publicEventsIdentity(),
                    result.publicEvents().stream()
                            .map(PublicEventOccurrence::eventBlueId)
                            .toList(),
                    result.publicEvents().stream()
                            .map(event -> String.valueOf(event.event()
                                    .getProperties().get("kind").getValue()))
                            .toList(),
                    result.outputClosureIdentity(),
                    result.platformCommitCompanion()
                            .companionIdentity(),
                    result.totalGas());
        }
    }

    private static ClosureInvocationInput dynamicCycleAdmission(
            DefaultCoordinationEngine engine) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        ExactValue exactA = engine.exactValue(dynamicA());
        ExactValue exactB = engine.exactValue(dynamicB(exactA.blueId()));
        blue.language.processor.closure.DocumentId closureA = closureId(A);
        blue.language.processor.closure.DocumentId closureB = closureId(B);
        ManagedOccurrenceBinding bToA = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureB,
                ScopeAddress.embedded("/a", 1L),
                closureA,
                exactA.blueId(),
                true,
                null);
        LinkedHashMap<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, exactA.copyNode());
        bodies.put(B, exactB.copyNode());
        return finalizedAdmission(
                engine,
                List.of(A, B),
                bodies,
                List.of(bToA),
                List.of(B),
                "coordination-public-dynamic-cycle");
    }

    private static ClosureInvocationInput dynamicLoopAdmission(
            DefaultCoordinationEngine engine) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        ExactValue exactA = engine.exactValue(dynamicLoopA());
        ExactValue exactB = engine.exactValue(dynamicLoopB(exactA.blueId()));
        ManagedOccurrenceBinding bToA = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(B),
                ScopeAddress.embedded("/a", 1L),
                closureId(A),
                exactA.blueId(),
                true,
                null);
        LinkedHashMap<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, exactA.copyNode());
        bodies.put(B, exactB.copyNode());
        return finalizedAdmission(
                engine,
                List.of(A, B),
                bodies,
                List.of(bToA),
                List.of(B),
                "coordination-public-dynamic-loop");
    }

    private static ClosureInvocationInput aEmbedsBAdmission(
            DefaultCoordinationEngine engine) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        ExactValue exactB = engine.exactValue(connectableB());
        ExactValue exactA = engine.exactValue(aEmbeddingB(exactB.blueId()));
        ManagedOccurrenceBinding aToB = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(A),
                ScopeAddress.embedded("/b", 1L),
                closureId(B),
                exactB.blueId(),
                true,
                null);
        LinkedHashMap<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(A, exactA.copyNode());
        bodies.put(B, exactB.copyNode());
        return finalizedAdmission(
                engine,
                List.of(A, B),
                bodies,
                List.of(aToB),
                List.of(A),
                "coordination-public-a-embeds-b");
    }

    private static ClosureInvocationInput sameEntryAdmission(
            DefaultCoordinationEngine engine,
            OrderingVariant variant) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        List<DocumentId> cyclicInput = variant.reverseCyclicInput()
                ? List.of(B, A)
                : List.of(A, B);
        int aIndex = cyclicInput.indexOf(A);
        int bIndex = cyclicInput.indexOf(B);
        Node placeholderA = engine.exactValue(sameEntryA())
                .copyNode()
                .properties("b", new Node().blueId("this#" + bIndex));
        placeholderA.getContracts().getProperties().put(
                "embedded", processEmbedded("/b"));
        Node placeholderB = engine.exactValue(sameEntryB())
                .copyNode()
                .properties("a", new Node().blueId("this#" + aIndex));
        placeholderB.getContracts().getProperties().put(
                "embedded", processEmbedded("/a"));
        Map<DocumentId, Node> placeholders = Map.of(
                A, placeholderA,
                B, placeholderB);
        CyclicSetFinalization language = new CircularSetIdentityCalculator()
                .finalizeCyclicSet(cyclicInput.stream()
                        .map(placeholders::get)
                        .toList());
        List<String> canonicalBlueIds = language.membersInCanonicalOrder()
                .stream()
                .map(CyclicMemberFinalization::finalBlueId)
                .toList();
        LinkedHashMap<DocumentId, Node> finalizedBodies =
                new LinkedHashMap<>();
        LinkedHashMap<DocumentId, String> finalizedBlueIds =
                new LinkedHashMap<>();
        for (int index = 0; index < cyclicInput.size(); index++) {
            DocumentId documentId = cyclicInput.get(index);
            CyclicMemberFinalization member =
                    language.membersInInputOrder().get(index);
            Node body = member.canonicalMemberBody();
            materializeCanonicalReferences(body, canonicalBlueIds);
            finalizedBodies.put(documentId, body);
            finalizedBlueIds.put(documentId, member.finalBlueId());
        }

        ManagedOccurrenceBinding aToB = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(A),
                ScopeAddress.embedded("/b", 1L),
                closureId(B),
                finalizedBlueIds.get(B),
                true,
                null);
        ManagedOccurrenceBinding bToA = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(B),
                ScopeAddress.embedded("/a", 1L),
                closureId(A),
                finalizedBlueIds.get(A),
                true,
                null);
        List<ManagedOccurrenceBinding> bindings = variant.reverseOccurrences()
                ? List.of(bToA, aToB)
                : List.of(aToB, bToA);

        LinkedHashMap<DocumentId, Node> bodyMap = new LinkedHashMap<>();
        List<DocumentId> bodyOrder = variant.reverseBodyMap()
                ? List.of(B, A)
                : List.of(A, B);
        bodyOrder.forEach(documentId -> bodyMap.put(
                documentId, finalizedBodies.get(documentId)));
        List<DocumentId> admissionOrder = variant.reverseDocumentAdmission()
                ? List.of(B, A)
                : List.of(A, B);
        return finalizedAdmission(
                engine,
                admissionOrder,
                bodyMap,
                bindings,
                List.of(A, B),
                "coordination-public-order-invariance");
    }

    private static ClosureInvocationInput finalizedAdmission(
            DefaultCoordinationEngine engine,
            List<DocumentId> memberInputOrder,
            Map<DocumentId, Node> bodyInputOrder,
            List<ManagedOccurrenceBinding> bindingInputOrder,
            List<DocumentId> publicRoots,
            String causeIdentity) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExecutionPolicy policy = admission.executionPolicy();
        LinkedHashMap<DocumentId,
                blue.language.processor.closure.DocumentId> closureIds =
                new LinkedHashMap<>();
        memberInputOrder.forEach(member -> closureIds.put(
                member, closureId(member)));
        List<blue.language.processor.closure.DocumentId> graphMembers =
                memberInputOrder.stream().map(closureIds::get).toList();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                graphMembers, bindingInputOrder);
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        Map<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodyInputOrder.forEach((documentId, body) -> {
            generations.put(closureIds.get(documentId), 1L);
            bodies.put(closureIds.get(documentId), body);
        });
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph,
                        generations,
                        bodies,
                        bindingInputOrder));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (DocumentId member : memberInputOrder) {
            blue.language.processor.closure.DocumentId closureId =
                    closureIds.get(member);
            documents.add(new ManagedDocumentSnapshot(
                    closureId,
                    exact.document(closureId).blueId(),
                    exact.document(closureId).document(),
                    false,
                    false,
                    publicRoots.contains(member),
                    0L,
                    1L));
        }
        List<blue.language.processor.closure.DocumentId> closureRoots =
                publicRoots.stream().map(closureIds::get).toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        exact.finalizedGraph().bindings(),
                        exact.components().stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        closureRoots);
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        causeIdentity,
                        null,
                        null,
                        "contracts-top-level-admission-v1"),
                null,
                policy,
                environment);
    }

    private static String dynamicA() {
        return """
                documentId: ordering-a
                phase: initial
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /b
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/dynamic
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: aliceChannel
                    request:
                      b: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /b
                              val: {$binding: event/message/request/b}
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: started
                          - $appendEvent:
                              type: Coordination/Event
                              kind: dynamic-x
                          - $return: true
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: dynamic-y}
                  onY:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: dynamic-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                          - $return: true
                """.formatted(
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String dynamicB(String aBlueId) {
        return """
                documentId: ordering-b
                phase: initial
                a:
                  blueId: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /a
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/dynamic-control
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  detachA:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: remove
                              path: /a
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: detached
                          - $return: true
                  touch:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: post-detach
                          - $return: true
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: dynamic-x}
                  onX:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: dynamic-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: dynamic-y
                          - $return: true
                """.formatted(
                aBlueId,
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String dynamicLoopA() {
        return """
                documentId: ordering-a
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /b
                  loopChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/dynamic-loop
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: loopChannel
                    request:
                      b: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /b
                              val: {$binding: event/message/request/b}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: dynamic-loop
                          - $return: true
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: dynamic-loop}
                  repeatFromB:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: dynamic-loop}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: dynamic-loop
                          - $return: true
                """.formatted(
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String dynamicLoopB(String aBlueId) {
        return """
                documentId: ordering-b
                a:
                  blueId: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /a
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: dynamic-loop}
                  repeatFromA:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: dynamic-loop}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: dynamic-loop
                          - $return: true
                """.formatted(
                aBlueId,
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String aEmbeddingB(String bBlueId) {
        return """
                documentId: ordering-a
                b:
                  blueId: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /b
                """.formatted(
                bBlueId,
                RuntimeBlueIds.PROCESS_EMBEDDED);
    }

    private static String connectableB() {
        return """
                documentId: ordering-b
                phase: initial
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /a
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/connect-a
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  connectA:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      a: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /a
                              val: {$binding: event/message/request/a}
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: connected
                          - $return: true
                """.formatted(RuntimeBlueIds.PROCESS_EMBEDDED);
    }

    private static String sameEntryA() {
        return """
                documentId: ordering-a
                phase: initial
                contracts:
                  sharedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/shared
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sharedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: direct-a
                          - $appendEvent:
                              type: Coordination/Event
                              kind: from-a
                          - $return: true
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: from-b}
                  onB:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: from-b}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: reacted-a
                          - $return: true
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String sameEntryB() {
        return """
                documentId: ordering-b
                phase: initial
                contracts:
                  sharedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: ordering/shared
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sharedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: direct-b
                          - $appendEvent:
                              type: Coordination/Event
                              kind: from-b
                          - $return: true
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: from-a}
                  onA:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: from-a}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: reacted-b
                          - $return: true
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static void assertCompleteCyclicComponent(
            ComponentSnapshot component) {
        assertEquals(ComponentKind.CYCLIC, component.kind());
        assertNotNull(component.masterBlueId());
        assertNotNull(component.cyclicProofIdentity());
        assertNotNull(component.completeCyclicProof());
        assertEquals(component.orderedMemberDocumentIds().size(),
                component.completeCyclicProof()
                        .declaredPlaceholderSet().size());
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(
                        new Node().value(path)));
    }

    private static void materializeCanonicalReferences(
            Node node,
            List<String> memberBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null && blueId.startsWith("this#")) {
            node.blueId(memberBlueIds.get(
                    Integer.parseInt(blueId.substring(5))));
        }
        materializeCanonicalReferences(node.getType(), memberBlueIds);
        materializeCanonicalReferences(node.getItemType(), memberBlueIds);
        materializeCanonicalReferences(node.getKeyType(), memberBlueIds);
        materializeCanonicalReferences(node.getValueType(), memberBlueIds);
        materializeCanonicalReferences(node.getBlue(), memberBlueIds);
        materializeCanonicalReferences(node.getContracts(), memberBlueIds);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> materializeCanonicalReferences(
                    item, memberBlueIds));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child ->
                    materializeCanonicalReferences(child, memberBlueIds));
        }
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static ContractsClosurePublicationReceipt onlyProcessReceipt(
            DefaultCoordinationEngine engine) {
        Map<String, ContractsClosurePublicationReceipt> receipts = engine
                .documents().publicationSnapshot()
                .closurePublicationReceipts();
        assertEquals(1, receipts.size());
        return receipts.values().iterator().next();
    }

    private static List<String> dequeuedDocumentIds(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued"
                        .equals(entry.counter()))
                .map(entry -> entry.documentId().value())
                .toList();
    }

    private static List<String> dequeuedWorkIds(ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued"
                        .equals(entry.counter()))
                .map(entry -> entry.workOccurrenceId())
                .toList();
    }

    private static Object property(
            CoordinationEngine engine,
            DocumentId documentId,
            String name) {
        return engine.document(documentId).current().copyNode()
                .getProperties().get(name).getValue();
    }

    private static long metricDelta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            String name) {
        return Math.subtractExact(
                after.counters().getOrDefault(name, 0L),
                before.counters().getOrDefault(name, 0L));
    }

    private static void admitUnrelatedDocuments(
            DefaultCoordinationEngine engine,
            CoordinationEngine publicEngine,
            int count) {
        List<DocumentId> unrelated = unrelatedIds(count);
        for (int start = 0; start < unrelated.size();
                start += UNRELATED_ADMISSION_BATCH_SIZE) {
            int end = Math.min(
                    start + UNRELATED_ADMISSION_BATCH_SIZE,
                    unrelated.size());
            Contracts10ScenarioBuilder batch =
                    new Contracts10ScenarioBuilder(engine);
            for (DocumentId documentId : unrelated.subList(start, end)) {
                batch.document(documentId, """
                        documentId: %s
                        phase: unrelated
                        """.formatted(documentId.value()))
                        .expectedComponent(documentId);
            }
            Contracts10ScenarioBuilder.ScenarioRuntime admitted = batch
                    .publicRoot(unrelated.get(start))
                    .admissionLabel("ordering-unrelated-" + start)
                    .admitTo(publicEngine);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
        }
    }

    private static List<DocumentId> unrelatedIds(int count) {
        ArrayList<DocumentId> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(DocumentId.of(
                    "ordering-unrelated-%04d".formatted(index)));
        }
        return List.copyOf(result);
    }

    private static List<DocumentId> unrelatedAdmissionRoots(int count) {
        List<DocumentId> unrelated = unrelatedIds(count);
        ArrayList<DocumentId> result = new ArrayList<>();
        for (int index = 0; index < unrelated.size();
                index += UNRELATED_ADMISSION_BATCH_SIZE) {
            result.add(unrelated.get(index));
        }
        return List.copyOf(result);
    }

    private static String master(String memberBlueId) {
        return memberBlueId.substring(0, memberBlueId.lastIndexOf('#'));
    }

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private record OrderingVariant(
            boolean reverseDocumentAdmission,
            boolean reverseBodyMap,
            boolean reverseOccurrences,
            boolean reverseCyclicInput) {
        private static final OrderingVariant BASELINE =
                new OrderingVariant(false, false, false, false);
        private static final OrderingVariant REVERSED_DOCUMENT_ADMISSION =
                new OrderingVariant(true, false, false, false);
        private static final OrderingVariant REVERSED_BODY_MAP =
                new OrderingVariant(false, true, false, false);
        private static final OrderingVariant REVERSED_OCCURRENCES =
                new OrderingVariant(false, false, true, false);
        private static final OrderingVariant REVERSED_CYCLIC_INPUT =
                new OrderingVariant(false, false, false, true);
    }

    private record OrderingEvidence(
            String admissionPublicationIdentity,
            String processPublicationIdentity,
            String entryBlueId,
            int directTargetCount,
            String finalABlueId,
            String finalBBlueId,
            String componentIdentity,
            String componentStateIdentity,
            String masterBlueId,
            String cyclicProofIdentity,
            List<Object> cyclicProofBodies,
            List<String> dequeueOrder,
            List<String> dequeueWorkIds,
            String gasTraceIdentity,
            String publicEventsIdentity,
            List<String> publishedEventBlueIds,
            List<String> publishedEventKinds,
            String outputClosureIdentity,
            String commitCompanionIdentity,
            long totalGas) {
        private OrderingEvidence {
            cyclicProofBodies = List.copyOf(cyclicProofBodies);
            dequeueOrder = List.copyOf(dequeueOrder);
            dequeueWorkIds = List.copyOf(dequeueWorkIds);
            publishedEventBlueIds = List.copyOf(publishedEventBlueIds);
            publishedEventKinds = List.copyOf(publishedEventKinds);
        }
    }
}
