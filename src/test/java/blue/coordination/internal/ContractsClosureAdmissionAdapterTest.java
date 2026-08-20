package blue.coordination.internal;

import blue.coordination.api.Contracts10Configuration;
import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-engine proof for genuine all-new Contracts closure admission. */
final class ContractsClosureAdmissionAdapterTest {
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");
    private static final DocumentId C = DocumentId.of("c");
    private static final String SHA_A = sha('a');
    private static final String SHA_B = sha('b');

    @Test
    void admitsC01CycleAtomicallyReplaysReceiptAndDrainsAfterAdmission() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = cyclicAdmission(engine, A, B);

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertTrue(admitted.attempt().isComplete());
            assertTrue(admitted.attempt().processResult().commits());
            assertEquals(List.of(A, B), admitted.documentIds());
            assertEquals(List.of(A, B),
                    new ContractsClosureAdmissionReceipt(
                            admitted.attempt(),
                            "canonical-member-check",
                            ContractsClosureAdmissionReceipt.PublicationOutcome
                                    .PUBLISHED,
                            List.of(B, A)).documentIds());
            assertThrows(IllegalArgumentException.class,
                    () -> new ContractsClosureAdmissionReceipt(
                            admitted.attempt(),
                            "duplicate-member-check",
                            ContractsClosureAdmissionReceipt.PublicationOutcome
                                    .PUBLISHED,
                            List.of(A, A)));
            assertEquals(0L, publicEngine.document(A).epoch());
            assertEquals(0L, publicEngine.document(B).epoch());
            assertTrue(publicEngine.document(A).current().isCyclicMember());
            assertTrue(publicEngine.document(B).current().isCyclicMember());
            assertEquals(1, publicEngine.document(A).physicalObjectCount());
            assertEquals(1, publicEngine.document(B).physicalObjectCount());
            assertEquals(Set.of("/"),
                    publicEngine.document(A).physicalObjects().keySet());
            assertEquals(Set.of("/"),
                    publicEngine.document(B).physicalObjects().keySet());
            assertTrue(engine.documents().require(A).layout()
                    .directOccurrences().isEmpty());
            assertTrue(engine.documents().require(B).layout()
                    .directOccurrences().isEmpty());
            assertTrue(engine.documents().require(A).layout().plan()
                    .rulesByScope().isEmpty());
            assertTrue(engine.documents().require(B).layout().plan()
                    .rulesByScope().isEmpty());
            InMemoryDocumentStore.PublicationSnapshot publication = engine
                    .documents().publicationSnapshot();
            assertEquals(2, publication.occurrenceInventory()
                    .activeRows().size());
            assertEquals(ComponentKind.CYCLIC,
                    publication.componentStates().get(0).kind());
            assertEquals(1L,
                    publication.graphGenerations().require(A));
            assertEquals(1L,
                    publication.graphGenerations().require(B));
            assertTrue(publication.admissionReceipts().containsKey(
                    admitted.publicationIdentity()));

            long gasBeforeReplay = engine.history(A.value()).get(0)
                    .processingGas()
                    + engine.history(B.value()).get(0).processingGas();
            ContractsClosureAdmissionReceipt replay = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .ALREADY_PUBLISHED,
                    replay.publicationOutcome());
            assertTrue(replay.attempt() == admitted.attempt());
            assertEquals(2, engine.documentCount());
            assertEquals(gasBeforeReplay,
                    engine.history(A.value()).get(0).processingGas()
                            + engine.history(B.value()).get(0).processingGas());

            String beforeA = publicEngine.document(A).blueId();
            String beforeB = publicEngine.document(B).blueId();
            Timeline timeline = publicEngine.registerTimeline(
                    "a/alice", "alice");
            blue.coordination.api.TimelineEntry entry = publicEngine.append(
                    timeline,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));
            ContractsRootFeederCoordinator.EventProgress processed = engine
                    .contractsFeederCoordinator().process(entry);

            assertTrue(processed.terminal());
            assertEquals(1, processed.batch().invocations().size());
            ContractsClosureAdapter.CohortInvocation invocation = processed
                    .batch().invocations().get(0);
            assertEquals(List.of(A, B), invocation.members());
            assertEquals(2, invocation.directDeliveries().size());
            assertEquals(1, processed.cohorts().size());
            ContractsClosureAdapter.CohortOutcome outcome = processed
                    .cohorts().get(0).outcome();
            assertTrue(outcome.published());
            assertTrue(outcome.attempt().isComplete());
            assertTrue(outcome.attempt().processResult().commits());
            assertEquals(2, outcome.attempt().processResult()
                    .resultingDocuments().size());
            assertEquals(BigInteger.valueOf(3L),
                    publicEngine.document(A).current().copyNode()
                            .getProperties().get("counter").getValue());
            assertEquals(BigInteger.valueOf(3L),
                    publicEngine.document(B).current().copyNode()
                            .getProperties().get("counter").getValue());
            assertEquals(1L, publicEngine.document(A).epoch());
            assertEquals(1L, publicEngine.document(B).epoch());
            assertTrue(publicEngine.document(A).current().isCyclicMember());
            assertTrue(publicEngine.document(B).current().isCyclicMember());
            assertNotEquals(beforeA, publicEngine.document(A).blueId());
            assertNotEquals(beforeB, publicEngine.document(B).blueId());
            assertEquals(master(publicEngine.document(A).blueId()),
                    master(publicEngine.document(B).blueId()));
            assertEquals(2, engine.history(A.value()).size());
            assertEquals(2, engine.history(B.value()).size());
            assertEquals(entry.blueId(), engine.history(A.value()).get(1)
                    .sourceEntry().orElseThrow().blueId());
            assertEquals(entry.blueId(), engine.history(B.value()).get(1)
                    .sourceEntry().orElseThrow().blueId());

            InMemoryDocumentStore.PublicationSnapshot afterProcess = engine
                    .documents().publicationSnapshot();
            assertEquals(ComponentKind.CYCLIC,
                    afterProcess.componentStates().get(0).kind());
            assertEquals(List.of(A.value(), B.value()),
                    afterProcess.componentStates().get(0)
                            .orderedMemberDocumentIds().stream()
                            .map(blue.language.processor.closure.DocumentId
                                    ::value)
                            .toList());
            assertEquals(2, afterProcess.occurrenceInventory()
                    .activeRows().size());
            assertEquals(afterProcess.graphGenerations().require(A),
                    afterProcess.graphGenerations().require(B));

            engine.restartFromStores();
            assertEquals(1L, publicEngine.document(A).epoch());
            assertEquals(1L, publicEngine.document(B).epoch());
            assertTrue(publicEngine.document(A).current().isCyclicMember());
            assertTrue(publicEngine.document(B).current().isCyclicMember());
            assertEquals(Set.of("/"),
                    publicEngine.document(A).physicalObjects().keySet());
            assertEquals(Set.of("/"),
                    publicEngine.document(B).physicalObjects().keySet());
        }
    }

    @Test
    void publicDrainProcessesFiniteCycleInExactAThenBThenAOrder() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            finiteCycleAdmission(engine, A, B),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertTrue(admitted.attempt().isComplete());
            assertTrue(admitted.attempt().processResult().commits());
            assertEquals(List.of(A, B), admitted.documentIds());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .componentStates().size());
            assertEquals(ComponentKind.CYCLIC, engine.documents()
                    .publicationSnapshot().componentStates().get(0).kind());
            assertEquals(List.of(A.value(), B.value()), engine.documents()
                    .publicationSnapshot().componentStates().get(0)
                    .orderedMemberDocumentIds().stream()
                    .map(blue.language.processor.closure.DocumentId::value)
                    .toList());

            Timeline timeline = publicEngine.registerTimeline(
                    "a/finite-cycle", "alice");
            TimelineEntry entry = publicEngine.append(
                    timeline,
                    Operation.yaml("start", "aliceChannel", "{}"));
            assertEquals(1, publicEngine.routeTargetCount(entry));

            var drained = publicEngine.drain();

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
            assertEquals(1, publicEngine.routeTargetCount(entry));
            assertEquals(1L, publicEngine.document(A).epoch());
            assertEquals(1L, publicEngine.document(B).epoch());
            assertTrue(publicEngine.document(A).current().isCyclicMember());
            assertTrue(publicEngine.document(B).current().isCyclicMember());
            assertEquals(master(publicEngine.document(A).blueId()),
                    master(publicEngine.document(B).blueId()));

            ContractsClosurePublicationReceipt receipt = onlyProcessReceipt(
                    engine);
            assertTrue(receipt.commits());
            assertEquals(List.of("a", "b", "a"),
                    dequeuedDocumentIds(receipt));
            assertEquals(3L, receipt.attempt().processResult().gasTrace()
                    .stream()
                    .filter(entryGas -> "closureWorkOccurrenceDequeued"
                            .equals(entryGas.counter()))
                    .map(entryGas -> entryGas.workOccurrenceId())
                    .distinct()
                    .count());
            assertTrue(receipt.attempt().processResult().totalGas() > 0L);
            assertEquals(1, engine.documents().publicationSnapshot()
                    .componentStates().size());
            assertEquals(ComponentKind.CYCLIC, engine.documents()
                    .publicationSnapshot().componentStates().get(0).kind());
            assertEquals(List.of(A.value(), B.value()), engine.documents()
                    .publicationSnapshot().componentStates().get(0)
                    .orderedMemberDocumentIds().stream()
                    .map(blue.language.processor.closure.DocumentId::value)
                    .toList());

            var replayDrain = publicEngine.drain();
            assertTrue(replayDrain.quiescent());
            assertTrue(replayDrain.processedEntries().isEmpty());
            assertEquals(0L, replayDrain.committedProcessTransitions());
        }
    }

    @Test
    void publicDrainPreservesOrdinaryThreeDocumentAcyclicChain() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(C));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            acyclicThreeStepAdmission(engine, A, B, C),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(List.of(A, B, C), admitted.documentIds());
            assertEquals(3, engine.documents().publicationSnapshot()
                    .componentStates().size());
            assertTrue(engine.documents().publicationSnapshot()
                    .componentStates().stream()
                    .allMatch(component -> component.kind()
                            == ComponentKind.ACYCLIC));

            Timeline timeline = publicEngine.registerTimeline(
                    "a/acyclic-chain", "alice");
            TimelineEntry entry = publicEngine.append(
                    timeline,
                    Operation.yaml("start", "aliceChannel", "{}"));
            assertEquals(1, publicEngine.routeTargetCount(entry));

            var drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(3L, drained.committedProcessTransitions());
            assertEquals(List.of(A, B, C), drained.outcomesFor(entry.blueId())
                    .stream()
                    .map(outcome -> outcome.documentId())
                    .toList());
            assertEquals("started", property(publicEngine, A, "phase"));
            assertEquals("relayed", property(publicEngine, B, "phase"));
            assertEquals("done", property(publicEngine, C, "phase"));
            assertEquals(1, publicEngine.routeTargetCount(entry));
            assertEquals(List.of("a", "b", "c"),
                    dequeuedDocumentIds(onlyProcessReceipt(engine)));
            assertFalse(publicEngine.document(A).current().isCyclicMember());
            assertFalse(publicEngine.document(B).current().isCyclicMember());
            assertFalse(publicEngine.document(C).current().isCyclicMember());
            assertEquals(2, publicEngine.history(A).size());
            assertEquals(2, publicEngine.history(B).size());
            assertEquals(2, publicEngine.history(C).size());
            assertEquals(3, engine.documents().publicationSnapshot()
                    .componentStates().size());
            assertTrue(engine.documents().publicationSnapshot()
                    .componentStates().stream()
                    .allMatch(component -> component.kind()
                            == ComponentKind.ACYCLIC));
        }
    }

    @Test
    void rollsBackEveryNewLineageWhenFailureOccursBeforeSwap() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = cyclicAdmission(engine, A, B);

            // when
            engine.contractsClosureAdmissionAdapter().onFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint
                        .BEFORE_SWAP) {
                    throw new IllegalStateException("before-swap");
                }
            });

            // then
            assertThrows(IllegalStateException.class, () -> publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null));

            InMemoryDocumentStore.PublicationSnapshot after = engine.documents()
                    .publicationSnapshot();
            assertTrue(after.documentHeads().isEmpty());
            assertTrue(after.occurrenceInventory().rows().isEmpty());
            assertTrue(after.componentStates().isEmpty());
            assertTrue(after.admissionReceipts().isEmpty());
            assertEquals(0, engine.routeRowCount());

            engine.contractsClosureAdmissionAdapter().onFailurePoint(
                    ignored -> { });
            ContractsClosureAdmissionReceipt retried = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    retried.publicationOutcome());
            assertEquals(2, engine.documentCount());
        }
    }

    @Test
    void needsResourcesIsRetryableAndMutatesNoCoordinationState() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Node timelineResource = new Node()
                    .type(new Node().blueId(
                            "5VAQp5thYLkzp3FbvYGmVvmdLqqu6pV5vhNgD14XJwpX"))
                    .properties("timelineId", new Node().value("a/alice"));
            String missingBlueId = DirectBlueIdCalculator.calculateBlueId(
                    timelineResource);
            ClosureInvocationInput input = cyclicAdmission(
                    engine, A, B, missingBlueId);
            engine.objects().forceProviderUnavailable(missingBlueId);
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            int objectsBefore = engine.objects().size();

            // when
            ContractsClosureAdmissionReceipt suspended = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .NOT_PUBLISHED,
                    suspended.publicationOutcome());
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES,
                    suspended.attempt().kind());
            assertEquals(List.of(missingBlueId),
                    suspended.attempt().requiredExactBlueIds());
            InMemoryDocumentStore.PublicationSnapshot afterSuspension = engine
                    .documents().publicationSnapshot();
            assertEquals(before.documentHeads(),
                    afterSuspension.documentHeads());
            assertEquals(before.occurrenceInventoryGeneration(),
                    afterSuspension.occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration(),
                    afterSuspension.componentIndexGeneration());
            assertTrue(afterSuspension.admissionReceipts().isEmpty());
            assertTrue(afterSuspension.publicationReceipts().isEmpty());
            assertEquals(0, engine.routeRowCount());
            assertEquals(objectsBefore, engine.objects().size());

            engine.objects().restoreProviderAvailability(missingBlueId);
            engine.objects().put(timelineResource, "admission-test-resource");
            ContractsClosureAdmissionReceipt retried = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    retried.publicationOutcome());
        }
    }

    @Test
    void rejectsMixedExistingAndNewMembersAndStalePublicationIdentity() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput original = cyclicAdmission(engine, A, B);
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            original,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // when
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();

            // then
            assertThrows(IllegalStateException.class, () -> publicEngine
                    .admitContractsClosure(
                            original,
                            CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                            null));
            ClosureInvocationInput mixed = cyclicAdmission(engine, A, C);
            assertThrows(UnsupportedOperationException.class, () -> publicEngine
                    .admitContractsClosure(
                            mixed,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null));

            InMemoryDocumentStore.PublicationSnapshot after = engine
                    .documents().publicationSnapshot();
            assertEquals(before.documentHeads(), after.documentHeads());
            assertEquals(before.admissionReceipts(), after.admissionReceipts());
            assertEquals(admitted.publicationIdentity(), after
                    .admissionReceipts().keySet().iterator().next());
        }
    }

    @Test
    void durableReceiptRecoversRoutePublicationFailureOnExactReplay() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = cyclicAdmission(engine, A, B);

            // when
            engine.contractsClosureAdmissionAdapter()
                    .onPublicationFailurePoint(point -> {
                        if (point == ContractsClosureAdmissionAdapter
                                .PublicationFailurePoint
                                .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH) {
                            throw new IllegalStateException("route-publish");
                        }
                    });

            // then
            assertThrows(IllegalStateException.class, () -> publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null));
            assertEquals(2, engine.documentCount());
            assertEquals(1, engine.documents().publicationSnapshot()
                    .admissionReceipts().size());
            assertEquals(0, engine.routeRowCount());

            engine.contractsClosureAdmissionAdapter()
                    .onPublicationFailurePoint(ignored -> { });
            ContractsClosureAdmissionReceipt recovered = publicEngine
                    .admitContractsClosure(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .ALREADY_PUBLISHED,
                    recovered.publicationOutcome());
            assertTrue(engine.routeRowCount() > 0);
        }
    }

    @Test
    void responseLossAfterAtomicProcessSwapReconcilesWithoutNewDocumentSteps() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            finiteCycleAdmission(engine, A, B),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());

            Timeline timeline = publicEngine.registerTimeline(
                    "a/finite-cycle", "alice");
            TimelineEntry entry = publicEngine.append(
                    timeline,
                    Operation.yaml("start", "aliceChannel", "{}"));
            assertEquals(1, publicEngine.routeTargetCount(entry));
            List<String> headsBeforeProcess = List.of(
                    publicEngine.document(A).blueId(),
                    publicEngine.document(B).blueId());
            AtomicInteger postSwapPublications = new AtomicInteger();
            engine.contractsClosureAdapter().onPublicationFailurePoint(point -> {
                if (point == ContractsClosureAdapter.PublicationFailurePoint
                        .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH
                        && postSwapPublications.incrementAndGet() == 1) {
                    throw new IllegalStateException("lost-process-response");
                }
            });

            assertThrows(CoordinationException.class, publicEngine::drain);

            assertEquals(1, publicEngine.metrics().journalEntryCount());
            assertEquals(1, postSwapPublications.get());
            ContractsClosurePublicationReceipt original = onlyProcessReceipt(
                    engine);
            assertTrue(original.commits());
            ClosureCommitCompanion originalCompanion = original.attempt()
                    .processResult().platformCommitCompanion();
            assertNotNull(originalCompanion);
            assertEquals(original.attempt().processResult()
                            .outputClosureIdentity(),
                    originalCompanion.outputClosureIdentity());
            List<String> headsAfterLostResponse = List.of(
                    publicEngine.auditDocument(A).blueId(),
                    publicEngine.auditDocument(B).blueId());
            assertNotEquals(headsBeforeProcess, headsAfterLostResponse);
            assertEquals(headsAfterLostResponse,
                    originalCompanion.resultingDocuments().stream()
                            .map(ClosureCommitCompanion.DocumentDelta
                                    ::afterBlueId)
                            .toList());
            List<Integer> historiesAfterLostResponse = List.of(
                    publicEngine.history(A).size(),
                    publicEngine.history(B).size());

            var recovered = publicEngine.drain();

            assertTrue(recovered.quiescent());
            assertFalse(recovered.paused());
            assertEquals(List.of(entry), recovered.processedEntries());
            assertTrue(recovered.outcomes().isEmpty());
            assertEquals(0L, recovered.committedProcessTransitions());
            assertEquals(1, publicEngine.metrics().journalEntryCount());
            assertEquals(1, postSwapPublications.get());
            ContractsClosurePublicationReceipt reconciled =
                    onlyProcessReceipt(engine);
            assertSame(original.attempt(), reconciled.attempt());
            ClosureCommitCompanion reconciledCompanion = reconciled.attempt()
                    .processResult().platformCommitCompanion();
            assertSame(originalCompanion, reconciledCompanion);
            assertEquals(originalCompanion.companionIdentity(),
                    reconciledCompanion.companionIdentity());
            assertEquals(headsAfterLostResponse, List.of(
                    publicEngine.auditDocument(A).blueId(),
                    publicEngine.auditDocument(B).blueId()));
            assertEquals(historiesAfterLostResponse, List.of(
                    publicEngine.history(A).size(),
                    publicEngine.history(B).size()));
        }
    }

    @Test
    void publicationIdentityFramesTupleShapeAndScalarKind() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ClosureInvocationInput input = cyclicAdmission(engine, A, B);

            String splitText = ContractsClosureAdmissionAdapter
                    .publicationIdentity(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                            ExternalOrderKey.of(List.of("a", "b")));
            String joinedText = ContractsClosureAdmissionAdapter
                    .publicationIdentity(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                            ExternalOrderKey.of(List.of("a, b")));
            String integer = ContractsClosureAdmissionAdapter
                    .publicationIdentity(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                            ExternalOrderKey.of(List.of(1L)));

            // when
            String text = ContractsClosureAdmissionAdapter
                    .publicationIdentity(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                            ExternalOrderKey.of(List.of("1")));

            // then
            assertNotEquals(splitText, joinedText);
            assertNotEquals(integer, text);
            assertEquals(splitText, ContractsClosureAdmissionAdapter
                    .publicationIdentity(
                            input,
                            CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                            ExternalOrderKey.of(List.of("a", "b"))));
        }
    }

    @Test
    void retiresThenLaterReactivatesExactInactiveSuccessorAcrossRestart() {
        // given

        Contracts10Configuration configuration = new Contracts10Configuration(
                SHA_A, SHA_B, Set.of(A));

        try (CoordinationEngine publicEngine =
                     CoordinationEngine.inMemoryContracts10(configuration)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            acyclicAdmission(engine, A, B),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());

            ManagedOccurrenceBinding initial = engine.documents()
                    .publicationSnapshot().occurrenceInventory()
                    .row(A, "/peer");
            assertTrue(initial.active());
            assertEquals(1L, initial.activationGeneration());

            Timeline timeline = publicEngine.registerTimeline(
                    "a/c35", "alice");
            TimelineEntry remove = publicEngine.append(
                    timeline,
                    Operation.yaml("removePeer", "ownerChannel", "{}"));
            ContractsRootFeederCoordinator.EventProgress removed = engine
                    .contractsFeederCoordinator().process(remove);

            assertTrue(removed.terminal());
            assertEquals(1, removed.batch().invocations().size());
            assertEquals(List.of(A, B), removed.batch().invocations().get(0)
                    .members());
            assertEquals(1, removed.batch().invocations().get(0)
                    .directDeliveries().size());
            assertTrue(removed.cohorts().get(0).outcome().published());
            ManagedOccurrenceBinding inactive = engine.documents()
                    .publicationSnapshot().occurrenceInventory()
                    .row(A, "/peer");
            assertFalse(inactive.active());
            assertEquals(2L, inactive.activationGeneration());
            assertEquals(initial.sourceDocumentId(),
                    inactive.sourceDocumentId());
            assertEquals(initial.sourcePath(), inactive.sourcePath());
            assertEquals(initial.targetDocumentId(),
                    inactive.targetDocumentId());
            assertNotEquals(initial.occurrenceIdentity(),
                    inactive.occurrenceIdentity());
            assertNotEquals(initial.bindingIdentity(),
                    inactive.bindingIdentity());
            assertEquals(publicEngine.document(B).blueId(),
                    inactive.expectedTargetBlueId());
            assertEquals("removed", publicEngine.document(A).current()
                    .copyNode().getProperties().get("state").getValue());
            assertFalse(publicEngine.document(A).current().copyNode()
                    .getProperties().containsKey("peer"));
            assertEquals(2, engine.documents().publicationSnapshot()
                    .componentStates().size());

            engine.restartFromStores();
            ManagedOccurrenceBinding refetchedInactive = engine.documents()
                    .publicationSnapshot().occurrenceInventory()
                    .row(A, "/peer");
            assertEquals(inactive.occurrenceIdentity(),
                    refetchedInactive.occurrenceIdentity());
            assertEquals(inactive.activationGeneration(),
                    refetchedInactive.activationGeneration());
            assertFalse(refetchedInactive.active());

            ExactValue request = publicEngine.referenceRequest(
                    "peer", publicEngine.document(B).current());
            TimelineEntry readd = publicEngine.append(
                    timeline,
                    Operation.exact("readdPeer", "ownerChannel", request));
            ContractsRootFeederCoordinator.EventProgress readded = engine
                    .contractsFeederCoordinator().process(readd);

            assertTrue(readded.terminal());
            assertEquals(1, readded.batch().invocations().size());
            ContractsClosureAdapter.CohortInvocation readdInvocation = readded
                    .batch().invocations().get(0);
            assertEquals(List.of(A, B), readdInvocation.members());
            assertEquals(1, readdInvocation.directDeliveries().size());
            assertEquals(2, readdInvocation.input().snapshot()
                    .components().size());
            assertTrue(
                    readded.cohorts().get(0).outcome().published(),
                    () -> describe(readded.cohorts().get(0).outcome()));
            ManagedOccurrenceBinding active = engine.documents()
                    .publicationSnapshot().occurrenceInventory()
                    .row(A, "/peer");
            assertTrue(active.active());
            assertEquals(inactive.activationGeneration(),
                    active.activationGeneration());
            assertEquals(inactive.occurrenceIdentity(),
                    active.occurrenceIdentity());
            assertEquals(inactive.bindingIdentity(),
                    active.bindingIdentity());
            assertEquals(publicEngine.document(B).blueId(),
                    active.expectedTargetBlueId());
            assertEquals("readded", publicEngine.document(A).current()
                    .copyNode().getProperties().get("state").getValue());
            assertEquals(publicEngine.document(B).blueId(),
                    publicEngine.document(A).current().copyNode()
                            .getProperties().get("peer").getBlueId());
            assertEquals(2L, publicEngine.document(A).epoch());
            assertEquals(0L, publicEngine.document(B).epoch());

            engine.restartFromStores();
            ManagedOccurrenceBinding refetchedActive = engine.documents()
                    .publicationSnapshot().occurrenceInventory()
                    .row(A, "/peer");
            assertTrue(refetchedActive.active());
            assertEquals(inactive.occurrenceIdentity(),
                    refetchedActive.occurrenceIdentity());
            assertEquals(2L, refetchedActive.activationGeneration());
            assertEquals(publicEngine.document(B).blueId(),
                    publicEngine.document(A).current().copyNode()
                            .getProperties().get("peer").getBlueId());
        }
    }

    private static ClosureInvocationInput cyclicAdmission(
            DefaultCoordinationEngine engine,
            DocumentId first,
            DocumentId second) {
        return cyclicAdmission(engine, first, second, null);
    }

    private static ClosureInvocationInput cyclicAdmission(
            DefaultCoordinationEngine engine,
            DocumentId first,
            DocumentId second,
            String timelineReference) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExecutionPolicy policy = admission.executionPolicy();
        Node placeholderA = counterTemplate(engine, first, "a/alice")
                .properties("b", new Node().blueId("this#1"));
        placeholderA.getContracts().getProperties().put(
                "embedded", processEmbedded("/b"));
        Node placeholderB = counterTemplate(engine, second, "a/alice")
                .properties("a", new Node().blueId("this#0"));
        if (timelineReference != null) {
            replaceTimelineWithReference(placeholderA, timelineReference);
            replaceTimelineWithReference(placeholderB, timelineReference);
        }
        placeholderB.getContracts().getProperties().put(
                "embedded", processEmbedded("/a"));

        CyclicSetFinalization language = new CircularSetIdentityCalculator()
                .finalizeCyclicSet(Arrays.asList(placeholderA, placeholderB));
        List<String> canonicalBlueIds = language.membersInCanonicalOrder()
                .stream()
                .map(CyclicMemberFinalization::finalBlueId)
                .toList();
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeCanonicalReferences(bodyA, canonicalBlueIds);
        materializeCanonicalReferences(bodyB, canonicalBlueIds);
        String blueA = language.membersInInputOrder().get(0).finalBlueId();
        String blueB = language.membersInInputOrder().get(1).finalBlueId();
        blue.language.processor.closure.DocumentId closureA =
                new blue.language.processor.closure.DocumentId(first.value());
        blue.language.processor.closure.DocumentId closureB =
                new blue.language.processor.closure.DocumentId(second.value());
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(List.of(
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureA,
                        ScopeAddress.embedded("/b", 1L),
                        closureB,
                        blueB,
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureB,
                        ScopeAddress.embedded("/a", 1L),
                        closureA,
                        blueA,
                        true,
                        null)));
        bindings.sort(null);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                List.of(closureA, closureB), bindings);
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        generations.put(closureA, 1L);
        generations.put(closureB, 1L);
        Map<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodies.put(closureA, bodyA);
        bodies.put(closureB, bodyB);
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph, generations, bodies, bindings));
        ComponentSnapshot cyclic = exact.components().get(0).component();
        assertEquals(Set.copyOf(cyclic.orderedMemberBlueIds()), Set.copyOf(
                CircularSetIdentityCalculator.calculateCircularSetBlueIds(
                        cyclic.completeCyclicProof()
                                .declaredPlaceholderSet())));
        assertEquals(
                exact.components().get(0).cyclicFinalization()
                        .canonicalMemberBodies().stream()
                        .map(NodeWireForm::get)
                        .toList(),
                cyclic.completeCyclicProof().declaredPlaceholderSet().stream()
                        .map(NodeWireForm::get)
                        .toList());
        List<ManagedDocumentSnapshot> documents = List.of(
                new ManagedDocumentSnapshot(
                        closureA,
                        exact.document(closureA).blueId(),
                        exact.document(closureA).document(),
                        false,
                        false,
                        true,
                        0L,
                        1L),
                new ManagedDocumentSnapshot(
                        closureB,
                        exact.document(closureB).blueId(),
                        exact.document(closureB).document(),
                        false,
                        false,
                        false,
                        0L,
                        1L));
        List<ComponentSnapshot> components = exact.components().stream()
                .map(FinalizedComponentEvidence::component)
                .toList();
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        exact.finalizedGraph().bindings(),
                        components,
                        List.of(closureA));
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        "coordination-c01",
                        null,
                        null,
                        "contracts-top-level-admission-v1"),
                null,
                policy,
                environment);
    }

    private static ClosureInvocationInput acyclicAdmission(
            DefaultCoordinationEngine engine,
            DocumentId parent,
            DocumentId child) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExecutionPolicy policy = admission.executionPolicy();
        ExactValue exactChild = engine.exactValue("""
                documentId: %s
                state: stable
                """.formatted(child.value()));
        ExactValue exactParent = engine.exactValue("""
                documentId: %s
                state: initial
                peer:
                  blueId: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /peer
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: a/c35
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  removePeer:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: remove
                              path: /peer
                          - $appendChange:
                              op: replace
                              path: /state
                              val: removed
                          - $return: true
                  readdPeer:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      peer: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /peer
                              val: {$binding: event/message/request/peer}
                          - $appendChange:
                              op: replace
                              path: /state
                              val: readded
                          - $return: true
                """.formatted(
                parent.value(),
                exactChild.blueId(),
                RuntimeBlueIds.PROCESS_EMBEDDED));
        blue.language.processor.closure.DocumentId closureParent =
                new blue.language.processor.closure.DocumentId(parent.value());
        blue.language.processor.closure.DocumentId closureChild =
                new blue.language.processor.closure.DocumentId(child.value());
        ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureParent,
                ScopeAddress.embedded("/peer", 1L),
                closureChild,
                exactChild.blueId(),
                true,
                null);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                List.of(closureParent, closureChild), List.of(binding));
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        generations.put(closureParent, 1L);
        generations.put(closureChild, 1L);
        Map<blue.language.processor.closure.DocumentId, Node> bodies =
                new LinkedHashMap<>();
        bodies.put(closureParent, exactParent.copyNode());
        bodies.put(closureChild, exactChild.copyNode());
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph, generations, bodies, List.of(binding)));
        List<ManagedDocumentSnapshot> documents = List.of(
                new ManagedDocumentSnapshot(
                        closureParent,
                        exact.document(closureParent).blueId(),
                        exact.document(closureParent).document(),
                        false,
                        false,
                        true,
                        0L,
                        1L),
                new ManagedDocumentSnapshot(
                        closureChild,
                        exact.document(closureChild).blueId(),
                        exact.document(closureChild).document(),
                        false,
                        false,
                        false,
                        0L,
                        1L));
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        exact.finalizedGraph().bindings(),
                        exact.components().stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        List.of(closureParent));
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        "coordination-c35",
                        null,
                        null,
                        "contracts-top-level-admission-v1"),
                null,
                policy,
                environment);
    }

    private static ClosureInvocationInput finiteCycleAdmission(
            DefaultCoordinationEngine engine,
            DocumentId first,
            DocumentId second) {
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        Node placeholderA = finiteCycleFirstTemplate(
                engine, first, "a/finite-cycle")
                .properties("b", new Node().blueId("this#1"));
        placeholderA.getContracts().getProperties().put(
                "embedded", processEmbedded("/b"));
        Node placeholderB = finiteCycleSecondTemplate(engine, second)
                .properties("a", new Node().blueId("this#0"));
        placeholderB.getContracts().getProperties().put(
                "embedded", processEmbedded("/a"));

        CyclicSetFinalization language = new CircularSetIdentityCalculator()
                .finalizeCyclicSet(Arrays.asList(placeholderA, placeholderB));
        List<String> canonicalBlueIds = language.membersInCanonicalOrder()
                .stream()
                .map(CyclicMemberFinalization::finalBlueId)
                .toList();
        Node bodyA = language.membersInInputOrder().get(0)
                .canonicalMemberBody();
        Node bodyB = language.membersInInputOrder().get(1)
                .canonicalMemberBody();
        materializeCanonicalReferences(bodyA, canonicalBlueIds);
        materializeCanonicalReferences(bodyB, canonicalBlueIds);
        String blueA = language.membersInInputOrder().get(0).finalBlueId();
        String blueB = language.membersInInputOrder().get(1).finalBlueId();
        blue.language.processor.closure.DocumentId closureA =
                new blue.language.processor.closure.DocumentId(first.value());
        blue.language.processor.closure.DocumentId closureB =
                new blue.language.processor.closure.DocumentId(second.value());
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(List.of(
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureA,
                        ScopeAddress.embedded("/b", 1L),
                        closureB,
                        blueB,
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureB,
                        ScopeAddress.embedded("/a", 1L),
                        closureA,
                        blueA,
                        true,
                        null)));
        bindings.sort(null);
        LinkedHashMap<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(first, bodyA);
        bodies.put(second, bodyB);
        return finalizedAdmission(
                engine,
                List.of(first, second),
                bodies,
                bindings,
                List.of(first),
                "coordination-public-finite-cycle");
    }

    private static ClosureInvocationInput acyclicThreeStepAdmission(
            DefaultCoordinationEngine engine,
            DocumentId first,
            DocumentId second,
            DocumentId third) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExactValue exactFirst = engine.exactValue("""
                documentId: %s
                phase: initial
                contracts:
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: a/acyclic-chain
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: aliceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: started
                          - $appendEvent:
                              type: Coordination/Event
                              kind: acyclic-x
                          - $return: true
                """.formatted(first.value()));
        ExactValue exactSecond = engine.exactValue("""
                documentId: %s
                phase: initial
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
                    event: {type: Coordination/Event, kind: acyclic-x}
                  onX:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: acyclic-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: acyclic-y
                          - $return: true
                """.formatted(
                second.value(),
                exactFirst.blueId(),
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL));
        ExactValue exactThird = engine.exactValue("""
                documentId: %s
                phase: initial
                b:
                  blueId: %s
                contracts:
                  embedded:
                    type:
                      blueId: %s
                    paths:
                      - /b
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: acyclic-y}
                  onY:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: acyclic-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                          - $return: true
                """.formatted(
                third.value(),
                exactSecond.blueId(),
                RuntimeBlueIds.PROCESS_EMBEDDED,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL));
        blue.language.processor.closure.DocumentId closureFirst =
                new blue.language.processor.closure.DocumentId(first.value());
        blue.language.processor.closure.DocumentId closureSecond =
                new blue.language.processor.closure.DocumentId(second.value());
        blue.language.processor.closure.DocumentId closureThird =
                new blue.language.processor.closure.DocumentId(third.value());
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(List.of(
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureSecond,
                        ScopeAddress.embedded("/a", 1L),
                        closureFirst,
                        exactFirst.blueId(),
                        true,
                        null),
                ManagedOccurrenceBinding.derived(
                        environment.managedBindingPolicyIdentity(),
                        closureThird,
                        ScopeAddress.embedded("/b", 1L),
                        closureSecond,
                        exactSecond.blueId(),
                        true,
                        null)));
        bindings.sort(null);
        LinkedHashMap<DocumentId, Node> bodies = new LinkedHashMap<>();
        bodies.put(first, exactFirst.copyNode());
        bodies.put(second, exactSecond.copyNode());
        bodies.put(third, exactThird.copyNode());
        return finalizedAdmission(
                engine,
                List.of(first, second, third),
                bodies,
                bindings,
                List.of(third),
                "coordination-public-acyclic-chain");
    }

    private static ClosureInvocationInput finalizedAdmission(
            DefaultCoordinationEngine engine,
            List<DocumentId> members,
            Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> bindings,
            List<DocumentId> publicRoots,
            String causeIdentity) {
        ContractsClosureAdmissionAdapter admission = engine
                .contractsClosureAdmissionAdapter();
        ClosureEnvironment environment = admission.environment();
        ExecutionPolicy policy = admission.executionPolicy();
        LinkedHashMap<DocumentId,
                blue.language.processor.closure.DocumentId> closureIds =
                new LinkedHashMap<>();
        members.forEach(member -> closureIds.put(
                member,
                new blue.language.processor.closure.DocumentId(
                        member.value())));
        List<blue.language.processor.closure.DocumentId> graphMembers =
                members.stream().map(closureIds::get).toList();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                graphMembers, bindings);
        Map<blue.language.processor.closure.DocumentId, Long> generations =
                new LinkedHashMap<>();
        Map<blue.language.processor.closure.DocumentId, Node> closureBodies =
                new LinkedHashMap<>();
        for (DocumentId member : members) {
            blue.language.processor.closure.DocumentId closureId =
                    closureIds.get(member);
            generations.put(closureId, 1L);
            closureBodies.put(closureId, bodies.get(member));
        }
        ComponentFinalizationResult exact = new ComponentFinalizationKernel()
                .finalizeComponents(new ComponentFinalizationInput(
                        graph, generations, closureBodies, bindings));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (DocumentId member : members) {
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

    private static Node finiteCycleFirstTemplate(
            DefaultCoordinationEngine engine,
            DocumentId documentId,
            String timelineId) {
        return engine.exactValue("""
                documentId: %s
                phase: initial
                contracts:
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: aliceChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: started
                          - $appendEvent:
                              type: Coordination/Event
                              kind: cycle-x
                          - $return: true
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: cycle-y}
                  onY:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: cycle-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                          - $return: true
                """.formatted(
                documentId.value(),
                timelineId,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)).copyNode();
    }

    private static Node finiteCycleSecondTemplate(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        return engine.exactValue("""
                documentId: %s
                phase: initial
                contracts:
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: cycle-x}
                  onX:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: cycle-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: cycle-y
                          - $return: true
                """.formatted(
                documentId.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)).copyNode();
    }

    private static Node counterTemplate(
            DefaultCoordinationEngine engine,
            DocumentId documentId,
            String timelineId) {
        return engine.exactValue("""
                documentId: %s
                name: Counter
                counter: 0
                contracts:
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: aliceChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $add:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(documentId.value(), timelineId)).copyNode();
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(
                        new Node().value(path)));
    }

    private static void replaceTimelineWithReference(
            Node document,
            String timelineBlueId) {
        document.getContracts().getProperties().get("aliceChannel")
                .getProperties().put(
                        "timeline", new Node().blueId(timelineBlueId));
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

    private static String sha(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }

    private static String master(String memberBlueId) {
        return memberBlueId.substring(0, memberBlueId.lastIndexOf('#'));
    }

    private static Object property(
            CoordinationEngine engine,
            DocumentId documentId,
            String key) {
        return engine.document(documentId).current().copyNode()
                .getProperties().get(key).getValue();
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
            ContractsClosurePublicationReceipt receipt) {
        return receipt.attempt().processResult().gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued"
                        .equals(entry.counter()))
                .map(entry -> entry.documentId().value())
                .toList();
    }

    private static String describe(ContractsClosureAdapter.CohortOutcome value) {
        if (!value.attempt().isComplete()) {
            return "needs " + value.attempt().requiredExactBlueIds();
        }
        var result = value.attempt().processResult();
        var diagnostic = result.diagnostic();
        return result.status() + " " + (diagnostic == null
                ? "no diagnostic"
                : diagnostic.category() + " " + diagnostic.message() + " "
                        + diagnostic.details());
    }
}
