package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.FinalizedComponentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-engine acceptance for authored three-member Contracts cycles. */
final class ContractsPublicThreeMemberCycleTest {
    private static final DocumentId A = DocumentId.of("three-ring-a");
    private static final DocumentId B = DocumentId.of("three-ring-b");
    private static final DocumentId C = DocumentId.of("three-ring-c");
    private static final List<DocumentId> MEMBERS = List.of(A, B, C);
    private static final List<String> MEMBER_VALUES = MEMBERS.stream()
            .map(DocumentId::value)
            .toList();
    private static final String ADMISSION_POLICY =
            "contracts-top-level-admission-v1";
    private static final String FINITE_ADMISSION_LABEL =
            "coordination-public-three-ring-finite";
    private static final String DIRECT_ADMISSION_LABEL =
            "coordination-public-three-ring-direct";
    private static final String LOOP_ADMISSION_LABEL =
            "coordination-public-three-ring-loop";
    private static final long ENTRY_TIME = 1_900_000_000_000_001L;

    @Test
    void literalContainmentRingRoutesChildEventsToContainingDocuments() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = literalFiniteScenario(engine);

            // when
            Contracts10ScenarioBuilder.Scenario scenario = builder.scenario();

            // Process Embedded declares containment. Events travel from an
            // embedded child to its containing document. Consequently the
            // literal A/b->B, B/c->C, C/a->A ring flows A,C,B,A. The reverse
            // containment ring used below is what realizes business flow
            // A,B,C,A without relabeling documents or inventing routing.

            // then
            assertEquals(Map.of(
                    A, List.of(B),
                    B, List.of(C),
                    C, List.of(A)), scenario.adjacency());
            long admissionProofsBefore = counter(
                    engine, "wholeObjectStore.cyclicProofsRetained");
            long admissionBodiesBefore = counter(
                    engine,
                    "wholeObjectStore.cyclicProviderRepresentationsRetained");
            builder.admitTo(publicEngine);
            assertEquals(1L, counter(
                    engine, "wholeObjectStore.cyclicProofsRetained")
                    - admissionProofsBefore);
            assertEquals(3L, counter(
                    engine,
                    "wholeObjectStore.cyclicProviderRepresentationsRetained")
                    - admissionBodiesBefore);

            Timeline timeline = publicEngine.registerTimeline(
                    "three-ring/literal", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("start", "aliceChannel", "{}"),
                    ENTRY_TIME);
            long processProofsBefore = counter(
                    engine, "wholeObjectStore.cyclicProofsRetained");
            long processBodiesBefore = counter(
                    engine,
                    "wholeObjectStore.cyclicProviderRepresentationsRetained");
            ProcessingDrainReceipt drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(3L, drained.committedProcessTransitions());
            ClosureProcessResult result = onlyProcessResult(engine);
            assertEquals(List.of(A.value(), C.value(), B.value(), A.value()),
                    dequeuedDocumentIds(result));
            assertEquals("done", property(publicEngine, A, "phase"));
            assertEquals("relayed-z", property(publicEngine, B, "phase"));
            assertEquals("relayed-y", property(publicEngine, C, "phase"));
            assertEquals(1L, counter(
                    engine, "wholeObjectStore.cyclicProofsRetained")
                    - processProofsBefore);
            assertEquals(3L, counter(
                    engine,
                    "wholeObjectStore.cyclicProviderRepresentationsRetained")
                    - processBodiesBefore);
            assertVerifiedThreeMemberComponent(
                    result.resultingComponents().get(0));
        }
    }

    @Test
    void componentEvidencePreflightCannotLeakAPartialCyclicRefresh() {
        // given
        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            ContractsClosureAdmissionReceipt admitted = literalFiniteScenario(
                    engine).admitTo(publicEngine).admissionReceipt();
            ClosureProcessResult result = admitted.attempt().processResult();
            ComponentSnapshot component = result.resultingComponents().get(0);
            WholeObjectStore isolated = new WholeObjectStore(
                    new EngineMetrics());
            for (int index = 0; index < MEMBERS.size() - 1; index++) {
                DocumentId member = MEMBERS.get(index);
                isolated.put(
                        ExactValue.fromVerifiedClosureResult(result, member),
                        "preflight-fixture");
            }

            // when
            IllegalStateException rejected = assertThrows(
                    IllegalStateException.class,
                    () -> isolated.retainVerifiedClosureComponentEvidence(
                            result));

            // then
            assertTrue(rejected.getMessage().contains(
                    "no matching retained component member"));
            for (String blueId : component.orderedMemberBlueIds()) {
                assertEquals(NodeProviderOutcome.NOT_FOUND,
                        isolated.cyclicSetProofFor(blueId).outcome());
                assertTrue(isolated.fetchByBlueId(blueId).isEmpty());
            }
        }
    }

    @Test
    void finiteReverseContainmentRingExecutesRequestedBusinessFlow() {
        // given
        FiniteVariant variant = FiniteVariant.BASELINE;

        // when
        FiniteEvidence evidence = runFinite(variant);

        // then
        assertEquals(List.of(A.value(), B.value(), C.value(), A.value()),
                evidence.dequeueOrder());
        assertEquals(4, evidence.dequeueWorkIds().size());
        assertEquals(4, Set.copyOf(evidence.dequeueWorkIds()).size());
        assertEquals(MEMBER_VALUES, evidence.changedDocuments());
        assertEquals(List.of(1L, 1L, 1L), evidence.finalEpochs());
        assertEquals(1, evidence.journalEntries());
        assertEquals(1L, evidence.entriesStoredDelta());
        assertEquals(1L, evidence.routeLookupDelta());
        assertEquals(List.of(A.value()), evidence.publicEventRoots());
        assertEquals(List.of("ring-x"), evidence.publicEventKinds());
        assertEquals(List.of(List.of(A.value(), B.value(), C.value())),
                evidence.componentPartition());
        assertEquals(3, evidence.proofBodies().size());
        assertEquals(Set.of("/"), Set.copyOf(evidence.workScopePaths()));
        assertEquals(Set.of(0L),
                Set.copyOf(evidence.workActivationGenerations()));
    }

    @Test
    void canonicalAdmissionAndDiscoveryIgnoreEveryAuthoredOrderVariant() {
        // given

        FiniteEvidence baseline = runFinite(FiniteVariant.BASELINE);

        // when
        for (FiniteVariant variant : List.of(
                FiniteVariant.REQUESTED_C_B_A,
                FiniteVariant.REQUESTED_B_A_C,
                FiniteVariant.REVERSED_OCCURRENCES,
                FiniteVariant.REVERSED_BODY_MAP,
                FiniteVariant.MATERIALIZED_REFERENCES)) {

            // then
            assertEquals(baseline, runFinite(variant), variant.name());
        }
    }

    @Test
    void sameEntryUsesCanonicalDirectSeedsAndClosesEachContinuation() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A, B, C))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = directScenario(engine);

            // when
            ContractsClosureAdmissionReceipt admitted =
                    builder.admitTo(publicEngine).admissionReceipt();

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());

            Timeline timeline = publicEngine.registerTimeline(
                    "three-ring/direct", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("start", "sharedChannel", "{}"),
                    ENTRY_TIME);
            assertEquals(3, publicEngine.routeTargetCount(entry));

            ProcessingDrainReceipt drained = publicEngine.drain();
            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(3L, drained.committedProcessTransitions());
            assertEquals(MEMBERS, drained.outcomesFor(entry.blueId()).stream()
                    .map(outcome -> outcome.documentId())
                    .toList());

            ClosureProcessResult result = onlyProcessResult(engine);
            assertEquals(List.of(
                            A.value(), B.value(),
                            B.value(), C.value(),
                            C.value(), A.value()),
                    dequeuedDocumentIds(result));
            List<String> workIds = dequeuedWorkIds(result);
            assertEquals(6, workIds.size());
            assertEquals(6, Set.copyOf(workIds).size());
            assertEquals(List.of(A.value(), B.value(), C.value()),
                    publicEventRoots(result));
            assertEquals(List.of("from-a", "from-b", "from-c"),
                    publicEventKinds(result));
            assertEquals("reacted-c", property(publicEngine, A, "phase"));
            assertEquals("direct-b", property(publicEngine, B, "phase"));
            assertEquals("direct-c", property(publicEngine, C, "phase"));
            assertVerifiedThreeMemberComponent(
                    result.resultingComponents().get(0));
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P2.3.three-direct-seeds",
                        engine,
                        result,
                        drained,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "entryBlueId", entry.blueId(),
                                "routeTargetCount", 3,
                                "directSeedOrder", MEMBER_VALUES));
            }
        }
    }

    @Test
    void threeMemberLoopRollbackIsIdenticalAcrossFreshEngineRuns() {
        // given

        LoopEvidence first = runLoopAttempt();

        // when
        LoopEvidence second = runLoopAttempt();

        // then
        assertEquals(first, second);
        assertTrue(first.gasEntries() > 0);
        assertTrue(first.rejectedWorkOrdinal() > 0L);
        assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED.name(),
                first.applicableCap());
    }

    private static FiniteEvidence runFinite(FiniteVariant variant) {
        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            AuthoredScenario authored = finiteScenario(engine, variant);
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            authored.admission(),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertEquals(MEMBERS, admitted.documentIds());
            assertEquals(List.of(List.of(A, B, C)),
                    authored.scenario().componentMembers());
            assertEquals(authored.scenario().components().get(0)
                            .masterBlueId(),
                    authored.scenario().independentlyVerifiedMaster(A));

            CoordinationMetrics before = publicEngine.metrics();
            Timeline timeline = publicEngine.registerTimeline(
                    "three-ring/finite", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("start", "aliceChannel", "{}"),
                    ENTRY_TIME);
            ProcessingDrainReceipt drained = publicEngine.drain();
            CoordinationMetrics after = publicEngine.metrics();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertEquals(3L, drained.committedProcessTransitions());
            List<DocumentId> changed = drained.outcomesFor(entry.blueId())
                    .stream()
                    .map(outcome -> outcome.documentId())
                    .toList();
            assertEquals(MEMBERS, changed);
            assertEquals("done", property(publicEngine, A, "phase"));
            assertEquals("relayed-y", property(publicEngine, B, "phase"));
            assertEquals("relayed-z", property(publicEngine, C, "phase"));

            ClosureProcessResult result = onlyProcessResult(engine);
            assertEquals(List.of(A.value(), B.value(), C.value(), A.value()),
                    dequeuedDocumentIds(result));
            ComponentSnapshot component =
                    result.resultingComponents().get(0);
            assertVerifiedThreeMemberComponent(component);
            Map<String, String> memberMapping = memberMapping(component);
            assertEquals(new HashSet<>(MEMBER_VALUES),
                    memberMapping.keySet());

            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P2.1.finite-three-member-ring",
                        engine,
                        result,
                        drained,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "entryBlueId", entry.blueId(),
                                "admissionPublicationIdentity",
                                admitted.publicationIdentity(),
                                "changedDocuments", changed,
                                "workOrder", dequeuedDocumentIds(result),
                                "finalEpochs", List.of(
                                        publicEngine.document(A).epoch(),
                                        publicEngine.document(B).epoch(),
                                        publicEngine.document(C).epoch())));
            }

            return new FiniteEvidence(
                    admitted.publicationIdentity(),
                    entry.blueId(),
                    result.invocationIdentity(),
                    result.outputClosureIdentity(),
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    component.masterBlueId(),
                    component.cyclicProofIdentity(),
                    component.completeCyclicProof()
                            .declaredPlaceholderSet().stream()
                            .map(NodeWireForm::get)
                            .toList(),
                    List.of(MEMBER_VALUES),
                    memberMapping,
                    List.of(
                            publicEngine.document(A).blueId(),
                            publicEngine.document(B).blueId(),
                            publicEngine.document(C).blueId()),
                    dequeuedDocumentIds(result),
                    dequeuedWorkIds(result),
                    result.gasTraceIdentity(),
                    result.publicEventsIdentity(),
                    result.publicEvents().stream()
                            .map(PublicEventOccurrence::eventBlueId)
                            .toList(),
                    publicEventRoots(result),
                    publicEventKinds(result),
                    changed.stream().map(DocumentId::value).toList(),
                    List.of(
                            publicEngine.document(A).epoch(),
                            publicEngine.document(B).epoch(),
                            publicEngine.document(C).epoch()),
                    after.journalEntryCount(),
                    after.counter(CoordinationMetrics.Counter
                                    .ENTRIES_STORED_WHOLE)
                            - before.counter(CoordinationMetrics.Counter
                                    .ENTRIES_STORED_WHOLE),
                    after.counter(CoordinationMetrics.Counter
                                    .ROUTE_INDEX_LOOKUPS)
                            - before.counter(CoordinationMetrics.Counter
                                    .ROUTE_INDEX_LOOKUPS),
                    dequeueEntries(result).stream()
                            .map(GasTraceEntry::scopePath)
                            .toList(),
                    dequeueEntries(result).stream()
                            .map(GasTraceEntry::activationGeneration)
                            .toList(),
                    result.totalGas());
        }
    }

    private static LoopEvidence runLoopAttempt() {
        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = loopScenario(engine);
            Contracts10ScenarioBuilder.Scenario scenario = builder.scenario();
            ContractsClosureAdmissionReceipt admitted =
                    builder.admitTo(publicEngine).admissionReceipt();
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());

            List<String> beforeHeads = headBlueIds(publicEngine);
            String beforeMaster = master(beforeHeads.get(0));
            assertEquals(List.of(beforeMaster, beforeMaster, beforeMaster),
                    beforeHeads.stream().map(
                            ContractsPublicThreeMemberCycleTest::master)
                            .toList());
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();

            Timeline timeline = publicEngine.registerTimeline(
                    "three-ring/loop", "alice");
            TimelineEntry entry = publicEngine.appendAt(
                    timeline,
                    Operation.yaml("startLoop", "source", "{}"),
                    ENTRY_TIME);
            ProcessingDrainReceipt drained = publicEngine.drain();

            assertTrue(drained.quiescent());
            assertFalse(drained.paused());
            assertEquals(List.of(entry), drained.processedEntries());
            assertTrue(drained.outcomes().isEmpty());
            assertEquals(0L, drained.committedProcessTransitions());
            assertEquals(beforeHeads, headBlueIds(publicEngine));
            assertEquals(List.of(0L, 0L, 0L), List.of(
                    publicEngine.document(A).epoch(),
                    publicEngine.document(B).epoch(),
                    publicEngine.document(C).epoch()));

            ClosureProcessResult result = onlyProcessResult(engine);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    result.status());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertNotNull(result.rejectedWorkOccurrence());
            assertNotNull(result.rejectedCharge());
            assertNull(result.platformCommitCompanion());
            assertTrue(result.graphChanges().isEmpty());
            assertTrue(result.subscriptionDeltas().isEmpty());
            assertTrue(result.checkpointWrites().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(
                    RejectedCharge.ApplicableCap.Kind.SHARED,
                    result.rejectedCharge().applicableCap().kind());
            assertEquals(
                    scenario.admission().executionPolicy().sharedLimit(),
                    result.totalGas()
                            + result.rejectedCharge()
                                    .remainingBeforeCharge());
            assertTrue(result.rejectedCharge().subtotal()
                    > result.rejectedCharge().remainingBeforeCharge());
            assertEquals(RejectedCharge.Owner.Kind.WORK,
                    result.rejectedCharge().owner().kind());
            assertEquals(result.rejectedWorkOccurrence().workIdentity(),
                    result.rejectedCharge().owner()
                            .workOccurrenceIdentity());
            String rejectedWorkIdentity = result.rejectedWorkOccurrence()
                    .workIdentity();
            // The bound runtime exhausts the default budget at the handler
            // call, after testing its candidate and before executing its body.
            assertEquals("handlerCall", result.rejectedCharge().counter());
            assertEquals(List.of(
                            "closureWorkOccurrenceEnqueued",
                            "closureWorkOccurrenceDequeued",
                            "scopeOpened",
                            "contractHeaderRecognized",
                            "contractHeaderRecognized",
                            "contractHeaderRecognized",
                            "embeddedPathEntryRead",
                            "embeddedPathSegmentValidated",
                            "embeddedEventDelivered",
                            "handlerCandidateTested"),
                    result.gasTrace().stream()
                            .filter(entryGas -> rejectedWorkIdentity.equals(
                                    entryGas.workOccurrenceId()))
                            .map(GasTraceEntry::counter)
                            .toList());
            GasTraceEntry lastAdmitted = result.gasTrace().get(
                    result.gasTrace().size() - 1);
            assertEquals("handlerCandidateTested", lastAdmitted.counter());
            assertEquals(rejectedWorkIdentity,
                    lastAdmitted.workOccurrenceId());
            assertEquals(result.totalGas(), result.gasTrace().stream()
                    .mapToLong(GasTraceEntry::subtotal)
                    .sum());
            assertTrue(result.totalGas()
                    + result.rejectedCharge().subtotal()
                    > scenario.admission().executionPolicy().sharedLimit());

            InMemoryDocumentStore.PublicationSnapshot after = engine
                    .documents().publicationSnapshot();
            assertEquals(before.occurrenceInventoryGeneration(),
                    after.occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration(),
                    after.componentIndexGeneration());
            assertEquals(bindingIdentities(before), bindingIdentities(after));
            assertEquals(subscriptionIdentities(before),
                    subscriptionIdentities(after));
            assertEquals(before.outbox(), after.outbox());
            assertEquals(before.checkpointEvidence(),
                    after.checkpointEvidence());
            assertEquals(beforeMaster,
                    after.componentStates().get(0).masterBlueId());
            assertEquals(1, publicEngine.metrics().journalEntryCount());

            ProcessingDrainReceipt terminal = publicEngine.drain();
            assertTrue(terminal.processedEntries().isEmpty());
            assertEquals(0L, terminal.committedProcessTransitions());

            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P2.4.shared-gas-rollback",
                        engine,
                        result,
                        drained,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "entryBlueId", entry.blueId(),
                                "admissionPublicationIdentity",
                                admitted.publicationIdentity(),
                                "beforeHeadBlueIds", beforeHeads,
                                "beforeMasterBlueId", beforeMaster,
                                "rejectedCounter",
                                result.rejectedCharge().counter(),
                                "rejectedWorkIdentity",
                                result.rejectedWorkOccurrence()
                                        .workIdentity()));
            }

            return new LoopEvidence(
                    admitted.publicationIdentity(),
                    entry.blueId(),
                    result.invocationIdentity(),
                    result.inputClosureIdentity(),
                    result.outputClosureIdentity(),
                    result.totalGas(),
                    result.gasTrace().size(),
                    result.gasTraceIdentity(),
                    gasTraceShape(result),
                    result.rejectedWorkOccurrence().ordinal(),
                    result.rejectedWorkOccurrence().targetDocumentId()
                            .value(),
                    result.rejectedWorkOccurrence().workIdentity(),
                    result.rejectedCharge().rejectedChargeIdentity(),
                    result.rejectedCharge().counter(),
                    result.rejectedCharge().remainingBeforeCharge(),
                    result.rejectedCharge().applicableCap().kind().name(),
                    beforeHeads,
                    beforeMaster);
        }
    }

    private static Contracts10ScenarioBuilder literalFiniteScenario(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(A, literalA())
                .document(B, literalB())
                .document(C, literalC())
                .processEmbeddedPath(A, "/b", B)
                .processEmbeddedPath(B, "/c", C)
                .processEmbeddedPath(C, "/a", A)
                .publicRoot(A)
                .expectedComponent(A, B, C)
                .admissionLabel("coordination-three-ring-literal-flow");
    }

    private static AuthoredScenario finiteScenario(
            DefaultCoordinationEngine engine,
            FiniteVariant variant) {
        Contracts10ScenarioBuilder builder = new Contracts10ScenarioBuilder(
                engine);
        for (DocumentId documentId : variant.documentOrder()) {
            builder.document(documentId, finiteDocument(documentId));
        }
        builder.processEmbeddedPath(B, "/a", A)
                .processEmbeddedPath(C, "/b", B)
                .processEmbeddedPath(A, "/c", C)
                .publicRoot(A)
                .expectedComponent(A, B, C)
                .admissionLabel(FINITE_ADMISSION_LABEL)
                .occurrenceOrder(variant.occurrenceOrder())
                .representation(variant.representation());
        Contracts10ScenarioBuilder.Scenario scenario = builder.scenario();
        ClosureInvocationInput admission = variant.reverseBodyMap()
                ? reverseBodyMapAdmission(engine, scenario)
                : scenario.admission();
        return new AuthoredScenario(scenario, admission);
    }

    private static Contracts10ScenarioBuilder directScenario(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(A, directA())
                .document(B, directB())
                .document(C, directC())
                .processEmbeddedPath(B, "/a", A)
                .processEmbeddedPath(C, "/b", B)
                .processEmbeddedPath(A, "/c", C)
                .publicRoot(A)
                .publicRoot(B)
                .publicRoot(C)
                .expectedComponent(A, B, C)
                .admissionLabel(DIRECT_ADMISSION_LABEL);
    }

    private static Contracts10ScenarioBuilder loopScenario(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(A, loopA())
                .document(B, loopB())
                .document(C, loopC())
                .processEmbeddedPath(B, "/a", A)
                .processEmbeddedPath(C, "/b", B)
                .processEmbeddedPath(A, "/c", C)
                .publicRoot(A)
                .expectedComponent(A, B, C)
                .admissionLabel(LOOP_ADMISSION_LABEL);
    }

    private static ClosureInvocationInput reverseBodyMapAdmission(
            DefaultCoordinationEngine engine,
            Contracts10ScenarioBuilder.Scenario scenario) {
        List<blue.language.processor.closure.DocumentId> closureIds =
                MEMBERS.stream().map(
                        ContractsPublicThreeMemberCycleTest::closureId)
                        .toList();
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                closureIds, scenario.bindings());
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = new LinkedHashMap<>();
        MEMBERS.forEach(documentId -> generations.put(
                closureId(documentId), 1L));
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                reversedBodies = new LinkedHashMap<>();
        List.of(C, B, A).forEach(documentId -> reversedBodies.put(
                closureId(documentId), scenario.document(documentId)));
        ComponentFinalizationResult exact =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                generations,
                                reversedBodies,
                                scenario.bindings()));
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (DocumentId documentId : MEMBERS) {
            blue.language.processor.closure.DocumentId closureId =
                    closureId(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    closureId,
                    exact.document(closureId).blueId(),
                    exact.document(closureId).document(),
                    false,
                    false,
                    documentId.equals(A),
                    0L,
                    exact.document(closureId).componentGeneration()));
        }
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        1L,
                        documents,
                        exact.finalizedGraph().bindings(),
                        exact.components().stream()
                                .map(FinalizedComponentEvidence::component)
                                .toList(),
                        List.of(closureId(A)));
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        FINITE_ADMISSION_LABEL,
                        null,
                        null,
                        ADMISSION_POLICY),
                null,
                engine.contractsClosureAdmissionAdapter().executionPolicy(),
                engine.contractsClosureAdmissionAdapter().environment());
    }

    private static void assertVerifiedThreeMemberComponent(
            ComponentSnapshot component) {
        assertEquals(ComponentKind.CYCLIC, component.kind());
        assertEquals(MEMBER_VALUES,
                component.orderedMemberDocumentIds().stream()
                        .map(blue.language.processor.closure.DocumentId::value)
                        .toList());
        assertNotNull(component.masterBlueId());
        assertNotNull(component.cyclicProofIdentity());
        assertNotNull(component.completeCyclicProof());
        assertEquals(3, component.completeCyclicProof()
                .declaredPlaceholderSet().size());
        List<String> independentlyCalculated =
                CircularSetIdentityCalculator.calculateCircularSetBlueIds(
                        component.completeCyclicProof()
                                .declaredPlaceholderSet());
        assertEquals(component.masterBlueId(),
                BlueIds.cyclicSetMasterBlueId(
                        independentlyCalculated.get(0)));
        assertEquals(new HashSet<>(component.orderedMemberBlueIds()),
                new HashSet<>(independentlyCalculated));
    }

    private static Map<String, String> memberMapping(
            ComponentSnapshot component) {
        TreeMap<String, String> result = new TreeMap<>();
        for (int index = 0;
             index < component.orderedMemberDocumentIds().size(); index++) {
            result.put(
                    component.orderedMemberDocumentIds().get(index).value(),
                    component.orderedMemberBlueIds().get(index));
        }
        return Map.copyOf(result);
    }

    private static ClosureProcessResult onlyProcessResult(
            DefaultCoordinationEngine engine) {
        Map<String, ContractsClosurePublicationReceipt> receipts = engine
                .documents().publicationSnapshot()
                .closurePublicationReceipts();
        assertEquals(1, receipts.size());
        return receipts.values().iterator().next()
                .attempt().processResult();
    }

    private static long counter(
            DefaultCoordinationEngine engine,
            String name) {
        return engine.metricsSnapshot().counters().getOrDefault(name, 0L);
    }

    private static List<GasTraceEntry> dequeueEntries(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued"
                        .equals(entry.counter()))
                .toList();
    }

    private static List<String> dequeuedDocumentIds(
            ClosureProcessResult result) {
        return dequeueEntries(result).stream()
                .map(entry -> entry.documentId().value())
                .toList();
    }

    private static List<String> dequeuedWorkIds(ClosureProcessResult result) {
        return dequeueEntries(result).stream()
                .map(GasTraceEntry::workOccurrenceId)
                .toList();
    }

    private static List<String> publicEventRoots(
            ClosureProcessResult result) {
        return result.publicEvents().stream()
                .map(event -> event.publicRootDocumentId().value())
                .toList();
    }

    private static List<String> publicEventKinds(
            ClosureProcessResult result) {
        return result.publicEvents().stream()
                .map(event -> String.valueOf(event.event()
                        .getProperties().get("kind").getValue()))
                .toList();
    }

    private static Object property(
            CoordinationEngine engine,
            DocumentId documentId,
            String name) {
        return engine.document(documentId).current().copyNode()
                .getProperties().get(name).getValue();
    }

    private static List<String> headBlueIds(CoordinationEngine engine) {
        return MEMBERS.stream()
                .map(documentId -> engine.document(documentId).blueId())
                .toList();
    }

    private static List<String> bindingIdentities(
            InMemoryDocumentStore.PublicationSnapshot snapshot) {
        return snapshot.occurrenceInventory().rows().stream()
                .map(binding -> binding.occurrenceIdentity()
                        + ":" + binding.bindingIdentity())
                .toList();
    }

    private static List<String> subscriptionIdentities(
            InMemoryDocumentStore.PublicationSnapshot snapshot) {
        return snapshot.closureSubscriptions().states().stream()
                .map(state -> state.subscriptionIdentity())
                .toList();
    }

    private static List<String> gasTraceShape(ClosureProcessResult result) {
        return result.gasTrace().stream()
                .map(entry -> entry.sequence()
                        + ":" + entry.namespace()
                        + ":" + entry.counter()
                        + ":" + entry.subtotal()
                        + ":" + entry.documentId()
                        + ":" + entry.contractKey()
                        + ":" + entry.logicalPath()
                        + ":" + entry.workOccurrenceId())
                .toList();
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(publicRoots));
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static String master(String memberBlueId) {
        return memberBlueId.substring(0, memberBlueId.lastIndexOf('#'));
    }

    private static String finiteDocument(DocumentId documentId) {
        if (documentId.equals(A)) {
            return finiteA();
        }
        if (documentId.equals(B)) {
            return finiteB();
        }
        if (documentId.equals(C)) {
            return finiteC();
        }
        throw new IllegalArgumentException("Unknown ring document");
    }

    private static String finiteA() {
        return """
                documentId: three-ring-a
                phase: initial
                contracts:
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: three-ring/finite
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
                              kind: ring-x
                          - $return: true
                  fromC:
                    type:
                      blueId: %s
                    sourcePath: /c
                    event: {type: Coordination/Event, kind: ring-z}
                  onZ:
                    type: Coordination/Sequential Workflow
                    channel: fromC
                    event: {type: Coordination/Event, kind: ring-z}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: done
                          - $return: true
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String finiteB() {
        return """
                documentId: three-ring-b
                phase: initial
                contracts:
                  fromA:
                    type:
                      blueId: %s
                    sourcePath: /a
                    event: {type: Coordination/Event, kind: ring-x}
                  onX:
                    type: Coordination/Sequential Workflow
                    channel: fromA
                    event: {type: Coordination/Event, kind: ring-x}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed-y
                          - $appendEvent:
                              type: Coordination/Event
                              kind: ring-y
                          - $return: true
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String finiteC() {
        return """
                documentId: three-ring-c
                phase: initial
                contracts:
                  fromB:
                    type:
                      blueId: %s
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: ring-y}
                  onY:
                    type: Coordination/Sequential Workflow
                    channel: fromB
                    event: {type: Coordination/Event, kind: ring-y}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: relayed-z
                          - $appendEvent:
                              type: Coordination/Event
                              kind: ring-z
                          - $return: true
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String literalA() {
        return finiteA()
                .replace("three-ring/finite", "three-ring/literal")
                .replace("sourcePath: /c", "sourcePath: /b")
                .replace("ring-z", "literal-z");
    }

    private static String literalB() {
        return finiteC()
                .replace("documentId: three-ring-c",
                        "documentId: three-ring-b")
                .replace("sourcePath: /b", "sourcePath: /c")
                .replace("ring-y", "literal-y")
                .replace("ring-z", "literal-z");
    }

    private static String literalC() {
        return finiteB()
                .replace("documentId: three-ring-b",
                        "documentId: three-ring-c")
                .replace("sourcePath: /a", "sourcePath: /a")
                .replace("ring-x", "ring-x")
                .replace("ring-y", "literal-y");
    }

    private static String directA() {
        return directDocument(
                A, "/c", "fromC", "from-c", "reacted-c");
    }

    private static String directB() {
        return directDocument(
                B, "/a", "fromA", "from-a", "reacted-a");
    }

    private static String directC() {
        return directDocument(
                C, "/b", "fromB", "from-b", "reacted-b");
    }

    private static String directDocument(
            DocumentId documentId,
            String peerPath,
            String peerChannel,
            String receivedKind,
            String reactedPhase) {
        String emittedKind = "from-" + documentId.value()
                .substring(documentId.value().lastIndexOf('-') + 1);
        String directPhase = "direct-" + documentId.value()
                .substring(documentId.value().lastIndexOf('-') + 1);
        return """
                documentId: %s
                phase: initial
                contracts:
                  sharedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: three-ring/direct
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
                              val: %s
                          - $appendEvent:
                              type: Coordination/Event
                              kind: %s
                          - $return: true
                  %s:
                    type:
                      blueId: %s
                    sourcePath: %s
                    event: {type: Coordination/Event, kind: %s}
                  onPeer:
                    type: Coordination/Sequential Workflow
                    channel: %s
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /phase
                              val: %s
                          - $return: true
                """.formatted(
                documentId.value(),
                directPhase,
                emittedKind,
                peerChannel,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                peerPath,
                receivedKind,
                peerChannel,
                receivedKind,
                reactedPhase);
    }

    private static String loopA() {
        return loopDocument(A, "/c", "fromC", true);
    }

    private static String loopB() {
        return loopDocument(B, "/a", "fromA", false);
    }

    private static String loopC() {
        return loopDocument(C, "/b", "fromB", false);
    }

    private static String loopDocument(
            DocumentId documentId,
            String peerPath,
            String channelKey,
            boolean externalRoot) {
        String external = externalRoot ? """
                  source:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: three-ring/loop
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: source
                    request: {}
                    steps:
                      - type: Coordination/Trigger Event
                        event:
                          type: Coordination/Event
                          kind: LOOP
                """ : "";
        return """
                documentId: %s
                phase: initial
                contracts:
                  %s:
                    type:
                      blueId: %s
                    sourcePath: %s
                    event: {type: Coordination/Event, kind: LOOP}
                  onPeerLoop:
                    type: Coordination/Sequential Workflow
                    channel: %s
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event:
                          type: Coordination/Event
                          kind: LOOP
                %s
                """.formatted(
                documentId.value(),
                channelKey,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                peerPath,
                channelKey,
                external.stripTrailing());
    }

    private enum FiniteVariant {
        BASELINE(
                List.of(A, B, C),
                Contracts10ScenarioBuilder.OccurrenceOrder.DECLARED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .REFERENCE_ONLY,
                false),
        REQUESTED_C_B_A(
                List.of(C, B, A),
                Contracts10ScenarioBuilder.OccurrenceOrder.DECLARED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .REFERENCE_ONLY,
                false),
        REQUESTED_B_A_C(
                List.of(B, A, C),
                Contracts10ScenarioBuilder.OccurrenceOrder.DECLARED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .REFERENCE_ONLY,
                false),
        REVERSED_OCCURRENCES(
                List.of(A, B, C),
                Contracts10ScenarioBuilder.OccurrenceOrder.REVERSED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .REFERENCE_ONLY,
                false),
        REVERSED_BODY_MAP(
                List.of(A, B, C),
                Contracts10ScenarioBuilder.OccurrenceOrder.DECLARED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .REFERENCE_ONLY,
                true),
        MATERIALIZED_REFERENCES(
                List.of(A, B, C),
                Contracts10ScenarioBuilder.OccurrenceOrder.DECLARED,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        .MATERIALIZED,
                false);

        private final List<DocumentId> documentOrder;
        private final Contracts10ScenarioBuilder.OccurrenceOrder
                occurrenceOrder;
        private final Contracts10ScenarioBuilder.ReferenceRepresentation
                representation;
        private final boolean reverseBodyMap;

        FiniteVariant(
                List<DocumentId> documentOrder,
                Contracts10ScenarioBuilder.OccurrenceOrder occurrenceOrder,
                Contracts10ScenarioBuilder.ReferenceRepresentation
                        representation,
                boolean reverseBodyMap) {
            this.documentOrder = List.copyOf(documentOrder);
            this.occurrenceOrder = occurrenceOrder;
            this.representation = representation;
            this.reverseBodyMap = reverseBodyMap;
        }

        List<DocumentId> documentOrder() {
            return documentOrder;
        }

        Contracts10ScenarioBuilder.OccurrenceOrder occurrenceOrder() {
            return occurrenceOrder;
        }

        Contracts10ScenarioBuilder.ReferenceRepresentation representation() {
            return representation;
        }

        boolean reverseBodyMap() {
            return reverseBodyMap;
        }
    }

    private record AuthoredScenario(
            Contracts10ScenarioBuilder.Scenario scenario,
            ClosureInvocationInput admission) {
    }

    private record FiniteEvidence(
            String admissionPublicationIdentity,
            String entryBlueId,
            String invocationIdentity,
            String outputClosureIdentity,
            String componentIdentity,
            String componentStateIdentity,
            String masterBlueId,
            String cyclicProofIdentity,
            List<Object> proofBodies,
            List<List<String>> componentPartition,
            Map<String, String> memberMapping,
            List<String> finalBlueIds,
            List<String> dequeueOrder,
            List<String> dequeueWorkIds,
            String gasTraceIdentity,
            String publicEventsIdentity,
            List<String> publicEventBlueIds,
            List<String> publicEventRoots,
            List<String> publicEventKinds,
            List<String> changedDocuments,
            List<Long> finalEpochs,
            int journalEntries,
            long entriesStoredDelta,
            long routeLookupDelta,
            List<String> workScopePaths,
            List<Long> workActivationGenerations,
            long totalGas) {
        private FiniteEvidence {
            proofBodies = List.copyOf(proofBodies);
            componentPartition = componentPartition.stream()
                    .map(List::copyOf)
                    .toList();
            memberMapping = Map.copyOf(memberMapping);
            finalBlueIds = List.copyOf(finalBlueIds);
            dequeueOrder = List.copyOf(dequeueOrder);
            dequeueWorkIds = List.copyOf(dequeueWorkIds);
            publicEventBlueIds = List.copyOf(publicEventBlueIds);
            publicEventRoots = List.copyOf(publicEventRoots);
            publicEventKinds = List.copyOf(publicEventKinds);
            changedDocuments = List.copyOf(changedDocuments);
            finalEpochs = List.copyOf(finalEpochs);
            workScopePaths = List.copyOf(workScopePaths);
            workActivationGenerations =
                    List.copyOf(workActivationGenerations);
        }
    }

    private record LoopEvidence(
            String admissionPublicationIdentity,
            String entryBlueId,
            String invocationIdentity,
            String inputClosureIdentity,
            String outputClosureIdentity,
            long totalGas,
            int gasEntries,
            String gasTraceIdentity,
            List<String> gasTraceShape,
            long rejectedWorkOrdinal,
            String rejectedDocumentId,
            String rejectedWorkIdentity,
            String rejectedChargeIdentity,
            String rejectedCounter,
            long remainingBeforeRejectedCharge,
            String applicableCap,
            List<String> beforeHeads,
            String beforeMaster) {
        private LoopEvidence {
            gasTraceShape = List.copyOf(gasTraceShape);
            beforeHeads = List.copyOf(beforeHeads);
        }
    }
}
