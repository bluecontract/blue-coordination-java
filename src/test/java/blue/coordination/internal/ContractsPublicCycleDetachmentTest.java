package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public Contracts proof for cyclic detachment and exact reactivation. */
final class ContractsPublicCycleDetachmentTest {
    private static final long ENTRY_TIME = 2_200_000_000_000_001L;

    private static final BranchingIds BRANCHING = new BranchingIds(
            DocumentId.of("detach-a"),
            DocumentId.of("detach-b1"),
            DocumentId.of("detach-b2"),
            DocumentId.of("detach-c1"),
            DocumentId.of("detach-c2"));
    private static final List<DocumentId> BRANCHING_DOCUMENTS = List.of(
            BRANCHING.a(),
            BRANCHING.b1(),
            BRANCHING.b2(),
            BRANCHING.c1(),
            BRANCHING.c2());
    private static final DocumentId FROZEN_A =
            DocumentId.of("frozen-a");
    private static final DocumentId FROZEN_B =
            DocumentId.of("frozen-b");

    @Test
    void splitDissolveAndReaddChangeRealCausalityAndLineage() {
        // given

        Set<DocumentId> publicRoots = Set.of(
                BRANCHING.a(), BRANCHING.c1(), BRANCHING.c2());

        try (CoordinationEngine publicEngine = engine(publicRoots)) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder = branchingBuilder(engine);

            // when
            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    builder.admitTo(publicEngine);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
            assertEquals(List.of(BRANCHING_DOCUMENTS),
                    admitted.scenario().componentMembers());
            ComponentSnapshot admissionComponent = admitted.scenario()
                    .components().get(0);
            assertVerifiedCycle(admissionComponent, BRANCHING_DOCUMENTS, 1L);

            ManagedOccurrenceBinding admittedC1Root = row(
                    engine, BRANCHING.c1(), "/root");
            assertTrue(admittedC1Root.active());
            assertEquals(1L, admittedC1Root.activationGeneration());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.initial-five-member-cycle",
                        engine,
                        admitted.admissionReceipt().attempt().processResult(),
                        null,
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "occurrenceIdentity",
                                admittedC1Root.occurrenceIdentity(),
                                "bindingIdentity",
                                admittedC1Root.bindingIdentity(),
                                "activationGeneration",
                                admittedC1Root.activationGeneration()));
            }

            Timeline signalTimeline = publicEngine.registerTimeline(
                    "detachment/signal", "alice");
            Invocation initialProbe = invoke(
                    publicEngine,
                    engine,
                    signalTimeline,
                    Operation.yaml("probe", "signalChannel", "{}"),
                    ENTRY_TIME);
            assertSuccess(initialProbe, 1);
            assertEquals(List.of(
                            BRANCHING.a().value(),
                            BRANCHING.c1().value(),
                            BRANCHING.c2().value()),
                    dequeuedDocumentIds(initialProbe.result()));
            assertEquals(1L, numberProperty(
                    publicEngine, BRANCHING.c1(), "pings"));
            assertEquals(1L, numberProperty(
                    publicEngine, BRANCHING.c2(), "pings"));
            // Contracts checkpoint writes are external-source evidence. The
            // embedded occurrence has no separately observable checkpoint
            // write; its non-reuse is therefore proved below by occurrence,
            // binding, and work identities.
            assertTrue(initialProbe.result().checkpointWrites().stream()
                    .noneMatch(write -> "pingFromRootOne".equals(
                            write.rawChannelKey())));

            ComponentSnapshot beforeDetachComponent = onlyCyclicComponent(
                    initialProbe.result());
            assertVerifiedCycle(
                    beforeDetachComponent, BRANCHING_DOCUMENTS, 1L);
            String oldMaster = beforeDetachComponent.masterBlueId();
            ManagedOccurrenceBinding activeBeforeDetach = row(
                    engine, BRANCHING.c1(), "/root");
            assertTrue(activeBeforeDetach.active());
            assertEquals(1L, activeBeforeDetach.activationGeneration());

            Map<DocumentId, HeadState> beforeLoop = heads(publicEngine);
            List<String> beforeLoopBindings = bindingIdentities(engine);
            List<String> beforeLoopComponents = componentStates(engine);
            Invocation rejectedLoop = invoke(
                    publicEngine,
                    engine,
                    signalTimeline,
                    Operation.yaml("startLoop", "signalChannel", "{}"),
                    ENTRY_TIME + 1L);
            assertEquals(1, rejectedLoop.routeTargetCount());
            assertTrue(rejectedLoop.drain().quiescent());
            assertFalse(rejectedLoop.drain().paused());
            assertTrue(rejectedLoop.drain().outcomes().isEmpty());
            assertEquals(0L,
                    rejectedLoop.drain().committedProcessTransitions());
            ClosureProcessResult rejected = rejectedLoop.result();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    rejected.status());
            assertTrue(rejected.rollbackToInput());
            assertTrue(rejected.atomic());
            assertEquals(rejected.inputClosureIdentity(),
                    rejected.outputClosureIdentity());
            assertNotNull(rejected.rejectedCharge());
            ContractsGasFailureAssertions.assertEnteredLoopAndRejectedExactOwner(rejected,
                    engine.contractsClosureAdapter().lastExecutionEvidence().orElseThrow(),
                    engine.contractsClosureAdmissionAdapter().executionPolicy().sharedLimit());
            assertNull(rejected.platformCommitCompanion());
            assertTrue(rejected.graphChanges().isEmpty());
            assertTrue(rejected.checkpointWrites().isEmpty());
            assertTrue(rejected.publicEvents().isEmpty());
            assertEquals(beforeLoop, heads(publicEngine));
            assertEquals(0L, numberProperty(
                    publicEngine, BRANCHING.a(), "loopStarts"));
            assertEquals(beforeLoopBindings, bindingIdentities(engine));
            assertEquals(beforeLoopComponents, componentStates(engine));
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.pre-detach-gas-rollback",
                        engine,
                        rejected,
                        rejectedLoop.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "routeTargetCount",
                                rejectedLoop.routeTargetCount(),
                                "beforeMasterBlueId", oldMaster,
                                "beforeBindingIdentities",
                                beforeLoopBindings));
            }

            Timeline controlTimeline = publicEngine.registerTimeline(
                    "detachment/control", "alice");
            Invocation partial = invoke(
                    publicEngine,
                    engine,
                    controlTimeline,
                    Operation.yaml("detachOne", "controlChannel", "{}"),
                    ENTRY_TIME + 2L);
            assertSuccess(partial, 2);
            assertEquals(5L,
                    partial.drain().committedProcessTransitions());
            assertEquals(BRANCHING_DOCUMENTS,
                    changedDocuments(partial));
            assertEquals(List.of(
                            BRANCHING.c1().value(),
                            BRANCHING.c2().value()),
                    dequeuedDocumentIds(partial.result()));
            assertFinalizedBeforeSecondDirectSeed(partial.result());
            assertEquals(2L, partial.result().graphGeneration());
            assertEquals(List.of(
                            List.of(BRANCHING.c1()),
                            List.of(BRANCHING.b1()),
                            List.of(
                                    BRANCHING.a(),
                                    BRANCHING.b2(),
                                    BRANCHING.c2())),
                    componentPartition(partial.result()));
            assertEquals(Map.of(
                            BRANCHING.a(), 2L,
                            BRANCHING.b1(), 2L,
                            BRANCHING.b2(), 2L,
                            BRANCHING.c1(), 2L,
                            BRANCHING.c2(), 2L),
                    componentGenerations(partial.result()));

            ComponentSnapshot remainingCycle = partial.result()
                    .resultingComponents().get(2);
            assertVerifiedCycle(
                    remainingCycle,
                    List.of(
                            BRANCHING.a(),
                            BRANCHING.b2(),
                            BRANCHING.c2()),
                    2L);
            assertNotEquals(oldMaster, remainingCycle.masterBlueId());
            assertOrdinary(publicEngine, BRANCHING.b1());
            assertOrdinary(publicEngine, BRANCHING.c1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.a(),
                    "/branches/b1",
                    BRANCHING.b1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.b1(),
                    "/child",
                    BRANCHING.c1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.a(),
                    "/branches/b2",
                    BRANCHING.b2());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.b2(),
                    "/child",
                    BRANCHING.c2());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.c2(),
                    "/root",
                    BRANCHING.a());
            assertNull(NodePathEditor.getOrNull(
                    current(publicEngine, BRANCHING.c1()), "/root"));
            assertNoMasterReference(publicEngine, oldMaster);
            assertOneGraphChange(
                    partial.result(),
                    GraphChange.Kind.REMOVE,
                    BRANCHING.c1(),
                    "/root");

            ManagedOccurrenceBinding inactiveAfterPartial = row(
                    engine, BRANCHING.c1(), "/root");
            assertFalse(inactiveAfterPartial.active());
            assertEquals(2L,
                    inactiveAfterPartial.activationGeneration());
            assertNotEquals(
                    activeBeforeDetach.occurrenceIdentity(),
                    inactiveAfterPartial.occurrenceIdentity());
            assertNotEquals(
                    activeBeforeDetach.bindingIdentity(),
                    inactiveAfterPartial.bindingIdentity());
            assertTrue(partial.result().checkpointWrites().stream()
                    .noneMatch(write -> "pingFromRootOne".equals(
                            write.rawChannelKey())));
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.1.partial-detach",
                        engine,
                        partial.result(),
                        partial.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "oldMasterBlueId", oldMaster,
                                "retiredOccurrenceIdentity",
                                inactiveAfterPartial.occurrenceIdentity(),
                                "retiredBindingIdentity",
                                inactiveAfterPartial.bindingIdentity(),
                                "retiredActivationGeneration",
                                inactiveAfterPartial.activationGeneration()));
            }

            Invocation full = invoke(
                    publicEngine,
                    engine,
                    controlTimeline,
                    Operation.yaml("detachTwo", "controlChannel", "{}"),
                    ENTRY_TIME + 3L);
            assertSuccess(full, 1);
            assertEquals(3L, full.drain().committedProcessTransitions());
            assertEquals(List.of(
                            BRANCHING.a(),
                            BRANCHING.b2(),
                            BRANCHING.c2()),
                    changedDocuments(full));
            assertEquals(List.of(BRANCHING.c2().value()),
                    dequeuedDocumentIds(full.result()));
            assertEquals(3L, full.result().graphGeneration());
            assertEquals(List.of(
                            List.of(BRANCHING.c1()),
                            List.of(BRANCHING.b1()),
                            List.of(BRANCHING.c2()),
                            List.of(BRANCHING.b2()),
                            List.of(BRANCHING.a())),
                    componentPartition(full.result()));
            assertEquals(Map.of(
                            BRANCHING.a(), 3L,
                            BRANCHING.b1(), 2L,
                            BRANCHING.b2(), 3L,
                            BRANCHING.c1(), 2L,
                            BRANCHING.c2(), 3L),
                    componentGenerations(full.result()));
            assertTrue(full.result().resultingComponents().stream()
                    .allMatch(component ->
                            component.kind() == ComponentKind.ACYCLIC
                                    && component.masterBlueId() == null
                                    && component.cyclicProofIdentity() == null
                                    && component.completeCyclicProof() == null));
            for (DocumentId documentId : BRANCHING_DOCUMENTS) {
                assertOrdinary(publicEngine, documentId);
            }
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.a(),
                    "/branches/b1",
                    BRANCHING.b1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.b1(),
                    "/child",
                    BRANCHING.c1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.a(),
                    "/branches/b2",
                    BRANCHING.b2());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.b2(),
                    "/child",
                    BRANCHING.c2());
            assertNull(NodePathEditor.getOrNull(
                    current(publicEngine, BRANCHING.c1()), "/root"));
            assertNull(NodePathEditor.getOrNull(
                    current(publicEngine, BRANCHING.c2()), "/root"));
            assertOneGraphChange(
                    full.result(),
                    GraphChange.Kind.REMOVE,
                    BRANCHING.c2(),
                    "/root");
            assertNoMasterReference(publicEngine, oldMaster);
            assertNoMasterReference(
                    publicEngine, remainingCycle.masterBlueId());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.2.full-dissolution",
                        engine,
                        full.result(),
                        full.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "oldMasterBlueId", oldMaster,
                                "partialMasterBlueId",
                                remainingCycle.masterBlueId()));
            }

            long c1PingsBeforeDetachedWork = numberProperty(
                    publicEngine, BRANCHING.c1(), "pings");
            long c2PingsBeforeDetachedWork = numberProperty(
                    publicEngine, BRANCHING.c2(), "pings");
            Invocation acceptedLoop = invoke(
                    publicEngine,
                    engine,
                    signalTimeline,
                    Operation.yaml("startLoop", "signalChannel", "{}"),
                    ENTRY_TIME + 4L);
            assertSuccess(acceptedLoop, 1);
            assertTrue(acceptedLoop.drain().quiescent());
            assertEquals(List.of(BRANCHING.a().value()),
                    dequeuedDocumentIds(acceptedLoop.result()));
            assertEquals(Set.of(0L), new HashSet<>(
                    dequeueEntries(acceptedLoop.result()).stream()
                            .map(GasTraceEntry::activationGeneration)
                            .toList()));
            assertTrue(acceptedLoop.result().totalGas()
                    < builder.scenario().admission().executionPolicy()
                            .sharedLimit());
            assertEquals(3L, acceptedLoop.result().graphGeneration());
            assertEquals(1L, numberProperty(
                    publicEngine, BRANCHING.a(), "loopStarts"));
            assertEquals(c1PingsBeforeDetachedWork, numberProperty(
                    publicEngine, BRANCHING.c1(), "pings"));
            assertEquals(c2PingsBeforeDetachedWork, numberProperty(
                    publicEngine, BRANCHING.c2(), "pings"));
            assertTrue(acceptedLoop.result().checkpointWrites().stream()
                    .noneMatch(write -> "loopFromRootOne".equals(
                            write.rawChannelKey())
                            || "loopFromRootTwo".equals(
                            write.rawChannelKey())));
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.3.post-detach-gas-success",
                        engine,
                        acceptedLoop.result(),
                        acceptedLoop.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "sharedLimit", builder.scenario().admission()
                                        .executionPolicy().sharedLimit(),
                                "acceptedGas",
                                acceptedLoop.result().totalGas()));
            }

            ManagedOccurrenceBinding inactiveBeforeReadd = row(
                    engine, BRANCHING.c1(), "/root");
            assertFalse(inactiveBeforeReadd.active());
            assertEquals(2L,
                    inactiveBeforeReadd.activationGeneration());
            ExactValue request = publicEngine.referenceRequest(
                    "root", publicEngine.document(BRANCHING.a()).current());
            Invocation readded = invoke(
                    publicEngine,
                    engine,
                    controlTimeline,
                    Operation.exact(
                            "readdOne", "controlChannel", request),
                    ENTRY_TIME + 5L);
            assertSuccess(readded, 1);
            assertEquals(3L,
                    readded.drain().committedProcessTransitions());
            assertEquals(List.of(
                            BRANCHING.a(),
                            BRANCHING.b1(),
                            BRANCHING.c1()),
                    changedDocuments(readded));
            assertEquals(4L, readded.result().graphGeneration());
            assertEquals(List.of(
                            List.of(BRANCHING.c2()),
                            List.of(BRANCHING.b2()),
                            List.of(
                                    BRANCHING.a(),
                                    BRANCHING.b1(),
                                    BRANCHING.c1())),
                    componentPartition(readded.result()));
            assertEquals(Map.of(
                            BRANCHING.a(), 4L,
                            BRANCHING.b1(), 4L,
                            BRANCHING.b2(), 3L,
                            BRANCHING.c1(), 4L,
                            BRANCHING.c2(), 3L),
                    componentGenerations(readded.result()));
            ComponentSnapshot reformed = readded.result()
                    .resultingComponents().get(2);
            assertVerifiedCycle(
                    reformed,
                    List.of(
                            BRANCHING.a(),
                            BRANCHING.b1(),
                            BRANCHING.c1()),
                    4L);
            assertNotEquals(oldMaster, reformed.masterBlueId());
            assertNotEquals(
                    remainingCycle.masterBlueId(),
                    reformed.masterBlueId());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.c1(),
                    "/root",
                    BRANCHING.a());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.b1(),
                    "/child",
                    BRANCHING.c1());
            assertCurrentReference(
                    publicEngine,
                    BRANCHING.a(),
                    "/branches/b1",
                    BRANCHING.b1());
            assertOneGraphChange(
                    readded.result(),
                    GraphChange.Kind.ADD,
                    BRANCHING.c1(),
                    "/root");

            ManagedOccurrenceBinding activeAfterReadd = row(
                    engine, BRANCHING.c1(), "/root");
            assertTrue(activeAfterReadd.active());
            assertEquals(2L, activeAfterReadd.activationGeneration());
            assertEquals(
                    inactiveBeforeReadd.occurrenceIdentity(),
                    activeAfterReadd.occurrenceIdentity());
            assertNotEquals(
                    activeBeforeDetach.occurrenceIdentity(),
                    activeAfterReadd.occurrenceIdentity());
            assertNotEquals(
                    activeBeforeDetach.bindingIdentity(),
                    activeAfterReadd.bindingIdentity());
            assertNotEquals(
                    inactiveBeforeReadd.bindingIdentity(),
                    activeAfterReadd.bindingIdentity());
            assertEquals(
                    publicEngine.document(BRANCHING.a()).blueId(),
                    activeAfterReadd.expectedTargetBlueId());
            assertDisjointWorkIds(
                    List.of(
                            initialProbe.result(),
                            rejectedLoop.result(),
                            partial.result(),
                            full.result(),
                            acceptedLoop.result()),
                    readded.result());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.5.re-add-retired-edge",
                        engine,
                        readded.result(),
                        readded.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "oldActiveOccurrenceIdentity",
                                activeBeforeDetach.occurrenceIdentity(),
                                "inactiveOccurrenceIdentity",
                                inactiveBeforeReadd.occurrenceIdentity(),
                                "readdedOccurrenceIdentity",
                                activeAfterReadd.occurrenceIdentity(),
                                "oldActiveBindingIdentity",
                                activeBeforeDetach.bindingIdentity(),
                                "inactiveBindingIdentity",
                                inactiveBeforeReadd.bindingIdentity(),
                                "readdedBindingIdentity",
                                activeAfterReadd.bindingIdentity(),
                                "activationGeneration",
                                activeAfterReadd.activationGeneration()));
            }

            Invocation reformedProbe = invoke(
                    publicEngine,
                    engine,
                    signalTimeline,
                    Operation.yaml("probe", "signalChannel", "{}"),
                    ENTRY_TIME + 6L);
            assertSuccess(reformedProbe, 1);
            assertEquals(List.of(
                            BRANCHING.a().value(),
                            BRANCHING.c1().value()),
                    dequeuedDocumentIds(reformedProbe.result()));
            assertEquals(c1PingsBeforeDetachedWork + 1L, numberProperty(
                    publicEngine, BRANCHING.c1(), "pings"));
            assertEquals(c2PingsBeforeDetachedWork, numberProperty(
                    publicEngine, BRANCHING.c2(), "pings"));
            assertTrue(reformedProbe.result().checkpointWrites().stream()
                    .noneMatch(write -> "pingFromRootOne".equals(
                            write.rawChannelKey())));
            assertDisjointWorkIds(
                    List.of(initialProbe.result()),
                    reformedProbe.result());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.5.reformed-cycle-probe",
                        engine,
                        reformedProbe.result(),
                        reformedProbe.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "reformedMasterBlueId",
                                reformed.masterBlueId()));
            }
        }
    }

    @Test
    void retiredEdgeStillServesItsAlreadyFrozenSecondDelivery() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(FROZEN_B))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(FROZEN_A, frozenA())
                            .document(FROZEN_B, frozenB())
                            .processEmbeddedPath(
                                    FROZEN_A, "/b", FROZEN_B)
                            .processEmbeddedPath(
                                    FROZEN_B, "/a", FROZEN_A)
                            .publicRoot(FROZEN_B)
                            .expectedComponent(FROZEN_A, FROZEN_B)
                            .admissionLabel(
                                    "contracts-public-frozen-edge-removal");

            // when
            Contracts10ScenarioBuilder.ScenarioRuntime admitted =
                    builder.admitTo(publicEngine);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.admissionReceipt().publicationOutcome());
            assertVerifiedCycle(
                    admitted.scenario().components().get(0),
                    List.of(FROZEN_A, FROZEN_B),
                    1L);
            ManagedOccurrenceBinding initial = row(
                    engine, FROZEN_A, "/b");
            assertTrue(initial.active());
            assertEquals(1L, initial.activationGeneration());

            Timeline timeline = publicEngine.registerTimeline(
                    "detachment/frozen", "alice");
            Invocation frozen = invoke(
                    publicEngine,
                    engine,
                    timeline,
                    Operation.yaml("freeze", "frozenChannel", "{}"),
                    ENTRY_TIME + 100L);
            assertSuccess(frozen, 1);
            assertEquals(2L,
                    frozen.drain().committedProcessTransitions());
            assertEquals(List.of(
                            FROZEN_B.value(),
                            FROZEN_A.value(),
                            FROZEN_A.value()),
                    dequeuedDocumentIds(frozen.result()));
            assertEquals(List.of(
                            "frozenChannel",
                            "aRetireFromB",
                            "zObserveFromB"),
                    dequeueEntries(frozen.result()).stream()
                            .map(GasTraceEntry::contractKey)
                            .toList());
            assertEquals(3, Set.copyOf(
                    dequeuedWorkIds(frozen.result())).size());
            assertEquals(1L, numberProperty(
                    publicEngine, FROZEN_A, "embeddedSeen"));
            assertEquals(0L, numberProperty(
                    publicEngine, FROZEN_A, "laterSeen"));
            assertEquals(2L, frozen.result().graphGeneration());
            assertEquals(List.of(
                            List.of(FROZEN_A),
                            List.of(FROZEN_B)),
                    componentPartition(frozen.result()));
            assertTrue(frozen.result().resultingComponents().stream()
                    .allMatch(component ->
                            component.kind() == ComponentKind.ACYCLIC
                                    && component.componentGeneration() == 2L
                                    && component.masterBlueId() == null
                                    && component.completeCyclicProof() == null));
            assertOrdinary(publicEngine, FROZEN_A);
            assertOrdinary(publicEngine, FROZEN_B);
            assertCurrentReference(
                    publicEngine, FROZEN_B, "/a", FROZEN_A);
            assertNull(NodePathEditor.getOrNull(
                    current(publicEngine, FROZEN_A), "/b"));
            assertOneGraphChange(
                    frozen.result(),
                    GraphChange.Kind.REMOVE,
                    FROZEN_A,
                    "/b");

            ManagedOccurrenceBinding retired = row(
                    engine, FROZEN_A, "/b");
            assertFalse(retired.active());
            assertEquals(2L, retired.activationGeneration());
            assertNotEquals(
                    initial.occurrenceIdentity(),
                    retired.occurrenceIdentity());
            assertNotEquals(
                    initial.bindingIdentity(),
                    retired.bindingIdentity());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.4.frozen-edge-delivery",
                        engine,
                        frozen.result(),
                        frozen.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "initialOccurrenceIdentity",
                                initial.occurrenceIdentity(),
                                "initialBindingIdentity",
                                initial.bindingIdentity(),
                                "retiredOccurrenceIdentity",
                                retired.occurrenceIdentity(),
                                "retiredBindingIdentity",
                                retired.bindingIdentity()));
            }

            Invocation later = invoke(
                    publicEngine,
                    engine,
                    timeline,
                    Operation.yaml("later", "frozenChannel", "{}"),
                    ENTRY_TIME + 101L);
            assertSuccess(later, 1);
            assertEquals(List.of(FROZEN_B.value()),
                    dequeuedDocumentIds(later.result()));
            assertEquals(List.of("frozenChannel"),
                    dequeueEntries(later.result()).stream()
                            .map(GasTraceEntry::contractKey)
                            .toList());
            assertEquals(1L, numberProperty(
                    publicEngine, FROZEN_A, "embeddedSeen"));
            assertEquals(0L, numberProperty(
                    publicEngine, FROZEN_A, "laterSeen"));
            assertEquals(2L, later.result().graphGeneration());
            assertTrue(later.result().checkpointWrites().stream()
                    .noneMatch(write -> "laterFromB".equals(
                            write.rawChannelKey())));
            assertFalse(row(engine, FROZEN_A, "/b").active());
            assertDisjointWorkIds(
                    List.of(frozen.result()), later.result());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P4.4.later-occurrence-uses-new-graph",
                        engine,
                        later.result(),
                        later.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "retiredOccurrenceIdentity",
                                row(engine, FROZEN_A, "/b")
                                        .occurrenceIdentity(),
                                "retiredBindingIdentity",
                                row(engine, FROZEN_A, "/b")
                                        .bindingIdentity()));
            }
        }
    }

    private static Contracts10ScenarioBuilder branchingBuilder(
            DefaultCoordinationEngine engine) {
        return new Contracts10ScenarioBuilder(engine)
                .document(BRANCHING.a(), branchingA())
                .document(BRANCHING.b1(), branchingB(BRANCHING.b1()))
                .document(BRANCHING.b2(), branchingB(BRANCHING.b2()))
                .document(BRANCHING.c1(), branchingC1())
                .document(BRANCHING.c2(), branchingC2())
                .processEmbeddedCollectionMember(
                        BRANCHING.a(),
                        "/branches",
                        "b1",
                        BRANCHING.b1())
                .processEmbeddedPath(
                        BRANCHING.b1(), "/child", BRANCHING.c1())
                .processEmbeddedPath(
                        BRANCHING.c1(), "/root", BRANCHING.a())
                .processEmbeddedCollectionMember(
                        BRANCHING.a(),
                        "/branches",
                        "b2",
                        BRANCHING.b2())
                .processEmbeddedPath(
                        BRANCHING.b2(), "/child", BRANCHING.c2())
                .processEmbeddedPath(
                        BRANCHING.c2(), "/root", BRANCHING.a())
                .publicRoot(BRANCHING.a())
                .publicRoot(BRANCHING.c1())
                .publicRoot(BRANCHING.c2())
                .expectedComponent(
                        BRANCHING.a(),
                        BRANCHING.b1(),
                        BRANCHING.b2(),
                        BRANCHING.c1(),
                        BRANCHING.c2())
                .admissionLabel("contracts-public-cycle-detachment");
    }

    private static String branchingA() {
        return """
                documentId: detach-a
                phase: initial
                loopStarts: 0
                contracts:
                  signalChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: detachment/signal
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: signalChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /loopStarts
                              val: {$add: [{$document: /loopStarts}, 1]}
                          - $appendEvent: {type: Coordination/Event, kind: LOOP}
                          - $return: true
                  probe:
                    type: Coordination/Sequential Workflow Operation
                    channel: signalChannel
                    request: {}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: PING}
                  loopFromBranchOne:
                    type: {blueId: %s}
                    sourcePath: /branches/b1
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoopOne:
                    type: Coordination/Sequential Workflow
                    channel: loopFromBranchOne
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                  loopFromBranchTwo:
                    type: {blueId: %s}
                    sourcePath: /branches/b2
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoopTwo:
                    type: Coordination/Sequential Workflow
                    channel: loopFromBranchTwo
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String branchingB(DocumentId documentId) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  loopFromChild:
                    type: {blueId: %s}
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: loopFromChild
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(
                documentId.value(),
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String branchingC1() {
        return """
                documentId: detach-c1
                phase: initial
                pings: 0
                contracts:
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: detachment/control
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  detachOne:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /root}
                          - $appendChange: {op: replace, path: /phase, val: detached}
                          - $return: true
                  readdOne:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request:
                      root: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /root
                              val: {$binding: event/message/request/root}
                          - $appendChange: {op: replace, path: /phase, val: readded}
                          - $return: true
                  loopFromRootOne:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: loopFromRootOne
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                  pingFromRootOne:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: PING}
                  observePing:
                    type: Coordination/Sequential Workflow
                    channel: pingFromRootOne
                    event: {type: Coordination/Event, kind: PING}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /pings
                              val: {$add: [{$document: /pings}, 1]}
                          - $return: true
                """.formatted(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String branchingC2() {
        return """
                documentId: detach-c2
                phase: initial
                pings: 0
                contracts:
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: detachment/control
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  detachOne:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                  detachTwo:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /root}
                          - $appendChange: {op: replace, path: /phase, val: detached}
                          - $return: true
                  loopFromRootTwo:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: loopFromRootTwo
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                  pingFromRootTwo:
                    type: {blueId: %s}
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: PING}
                  observePing:
                    type: Coordination/Sequential Workflow
                    channel: pingFromRootTwo
                    event: {type: Coordination/Event, kind: PING}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /pings
                              val: {$add: [{$document: /pings}, 1]}
                          - $return: true
                """.formatted(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String frozenA() {
        return """
                documentId: frozen-a
                phase: initial
                embeddedSeen: 0
                laterSeen: 0
                contracts:
                  aRetireFromB:
                    type: {blueId: %s}
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: FROZEN-X}
                  retireEdge:
                    type: Coordination/Sequential Workflow
                    channel: aRetireFromB
                    event: {type: Coordination/Event, kind: FROZEN-X}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /b}
                          - $appendChange: {op: replace, path: /phase, val: retired}
                          - $return: true
                  zObserveFromB:
                    type: {blueId: %s}
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: FROZEN-X}
                  observeFrozen:
                    type: Coordination/Sequential Workflow
                    channel: zObserveFromB
                    event: {type: Coordination/Event, kind: FROZEN-X}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /embeddedSeen
                              val: {$add: [{$document: /embeddedSeen}, 1]}
                          - $return: true
                  laterFromB:
                    type: {blueId: %s}
                    sourcePath: /b
                    event: {type: Coordination/Event, kind: LATER-X}
                  observeLater:
                    type: Coordination/Sequential Workflow
                    channel: laterFromB
                    event: {type: Coordination/Event, kind: LATER-X}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /laterSeen
                              val: {$add: [{$document: /laterSeen}, 1]}
                          - $return: true
                """.formatted(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
    }

    private static String frozenB() {
        return """
                documentId: frozen-b
                phase: initial
                contracts:
                  frozenChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: detachment/frozen
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  freeze:
                    type: Coordination/Sequential Workflow Operation
                    channel: frozenChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: emitted}
                          - $appendEvent: {type: Coordination/Event, kind: FROZEN-X}
                          - $return: true
                  later:
                    type: Coordination/Sequential Workflow Operation
                    channel: frozenChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: later}
                          - $appendEvent: {type: Coordination/Event, kind: LATER-X}
                          - $return: true
                """;
    }

    private static Invocation invoke(
            CoordinationEngine publicEngine,
            DefaultCoordinationEngine engine,
            Timeline timeline,
            Operation operation,
            long eventTime) {
        Set<String> beforeReceipts = Set.copyOf(engine.documents()
                .publicationSnapshot().closurePublicationReceipts()
                .keySet());
        TimelineEntry entry = publicEngine.appendAt(
                timeline, operation, eventTime);
        int routeTargetCount = publicEngine.routeTargetCount(entry);
        ProcessingDrainReceipt drain = publicEngine.drain();
        List<ContractsClosurePublicationReceipt> added = engine.documents()
                .publicationSnapshot().closurePublicationReceipts()
                .entrySet().stream()
                .filter(receipt -> !beforeReceipts.contains(
                        receipt.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        assertEquals(1, added.size());
        assertEquals(List.of(entry), drain.processedEntries());
        return new Invocation(
                entry,
                drain,
                added.get(0).attempt().processResult(),
                routeTargetCount);
    }

    private static void assertSuccess(
            Invocation invocation,
            int routeTargetCount) {
        assertEquals(routeTargetCount, invocation.routeTargetCount());
        assertTrue(invocation.drain().quiescent());
        assertFalse(invocation.drain().paused());
        assertEquals(ProcessorStatus.SUCCESS, invocation.result().status());
        assertTrue(invocation.result().commits());
        assertTrue(invocation.result().atomic());
        assertNotNull(invocation.result().platformCommitCompanion());
        assertEquals(invocation.result().outputClosureIdentity(),
                invocation.result().platformCommitCompanion()
                        .outputClosureIdentity());
    }

    private static void assertFinalizedBeforeSecondDirectSeed(
            ClosureProcessResult result) {
        List<GasTraceEntry> dequeues = dequeueEntries(result);
        assertEquals(2, dequeues.size());
        assertEquals(1L, dequeues.get(0).componentGeneration());
        assertEquals(2L, dequeues.get(1).componentGeneration());
        long secondSequence = dequeues.get(1).sequence();
        List<GasTraceEntry> firstBoundaryFinalization = result.gasTrace()
                .stream()
                .filter(entry -> entry.sequence() < secondSequence)
                .filter(entry -> "tentativeComponentFinalization".equals(
                        entry.counter())
                        || "cyclicMemberFinalized".equals(
                        entry.counter()))
                .toList();
        assertTrue(firstBoundaryFinalization.stream().anyMatch(entry ->
                "tentativeComponentFinalization".equals(
                        entry.counter())));
        assertEquals(2L, firstBoundaryFinalization.stream()
                .filter(entry -> "tentativeComponentFinalization".equals(
                        entry.counter()))
                .count());
        assertEquals(6L, firstBoundaryFinalization.stream()
                .filter(entry -> "cyclicMemberFinalized".equals(
                        entry.counter()))
                .count());
        assertTrue(firstBoundaryFinalization.stream()
                .allMatch(entry -> entry.workOccurrenceId().equals(
                        dequeues.get(0).workOccurrenceId())));
    }

    private static void assertVerifiedCycle(
            ComponentSnapshot component,
            List<DocumentId> members,
            long generation) {
        assertEquals(ComponentKind.CYCLIC, component.kind());
        assertEquals(members.stream().map(DocumentId::value).toList(),
                component.orderedMemberDocumentIds().stream()
                        .map(blue.language.processor.closure.DocumentId::value)
                        .toList());
        assertEquals(generation, component.componentGeneration());
        assertNotNull(component.masterBlueId());
        assertNotNull(component.cyclicProofIdentity());
        assertNotNull(component.completeCyclicProof());
        assertEquals(members.size(), component.completeCyclicProof()
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

    private static void assertOrdinary(
            CoordinationEngine engine,
            DocumentId documentId) {
        ExactValue value = engine.document(documentId).current();
        assertFalse(value.isCyclicMember());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(value.copyNode()),
                value.blueId());
    }

    private static void assertCurrentReference(
            CoordinationEngine engine,
            DocumentId source,
            String path,
            DocumentId target) {
        Node reference = NodePathEditor.getOrNull(current(engine, source), path);
        assertNotNull(reference);
        assertEquals(engine.document(target).blueId(), reference.getBlueId());
    }

    private static void assertNoMasterReference(
            CoordinationEngine engine,
            String staleMaster) {
        for (DocumentId documentId : BRANCHING_DOCUMENTS) {
            LinkedHashSet<String> references = new LinkedHashSet<>();
            collectBlueIds(current(engine, documentId), references);
            assertTrue(references.stream().noneMatch(blueId ->
                    blueId.equals(staleMaster)
                            || blueId.startsWith(staleMaster + "#")),
                    () -> documentId + " retains stale MASTER "
                            + staleMaster + " in " + references);
        }
    }

    private static void collectBlueIds(Node node, Set<String> result) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null) {
            result.add(node.getBlueId());
        }
        collectBlueIds(node.getType(), result);
        collectBlueIds(node.getItemType(), result);
        collectBlueIds(node.getKeyType(), result);
        collectBlueIds(node.getValueType(), result);
        collectBlueIds(node.getBlue(), result);
        collectBlueIds(node.getContracts(), result);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> collectBlueIds(item, result));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child ->
                    collectBlueIds(child, result));
        }
    }

    private static void assertOneGraphChange(
            ClosureProcessResult result,
            GraphChange.Kind kind,
            DocumentId source,
            String path) {
        List<GraphChange> matches = result.graphChanges().stream()
                .filter(change -> change.changeKind() == kind)
                .filter(change -> change.sourceDocumentId().value()
                        .equals(source.value()))
                .filter(change -> change.sourcePath().equals(path))
                .toList();
        assertEquals(1, matches.size());
    }

    private static void assertDisjointWorkIds(
            List<ClosureProcessResult> oldResults,
            ClosureProcessResult newer) {
        Set<String> old = oldResults.stream()
                .flatMap(result -> dequeuedWorkIds(result).stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> fresh = Set.copyOf(dequeuedWorkIds(newer));
        assertTrue(old.stream().noneMatch(fresh::contains));
    }

    private static ManagedOccurrenceBinding row(
            DefaultCoordinationEngine engine,
            DocumentId source,
            String path) {
        return engine.documents().publicationSnapshot()
                .occurrenceInventory().row(source, path);
    }

    private static ComponentSnapshot onlyCyclicComponent(
            ClosureProcessResult result) {
        List<ComponentSnapshot> cyclic = result.resultingComponents().stream()
                .filter(component -> component.kind()
                        == ComponentKind.CYCLIC)
                .toList();
        assertEquals(1, cyclic.size());
        return cyclic.get(0);
    }

    private static List<GasTraceEntry> dequeueEntries(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued".equals(
                        entry.counter()))
                .toList();
    }

    private static List<String> dequeuedDocumentIds(
            ClosureProcessResult result) {
        return dequeueEntries(result).stream()
                .map(entry -> entry.documentId().value())
                .toList();
    }

    private static List<String> dequeuedWorkIds(
            ClosureProcessResult result) {
        return dequeueEntries(result).stream()
                .map(GasTraceEntry::workOccurrenceId)
                .toList();
    }

    private static List<DocumentId> changedDocuments(Invocation invocation) {
        return invocation.drain().outcomesFor(invocation.entry().blueId())
                .stream()
                .map(outcome -> outcome.documentId())
                .toList();
    }

    private static List<List<DocumentId>> componentPartition(
            ClosureProcessResult result) {
        return result.resultingComponents().stream()
                .map(component -> component.orderedMemberDocumentIds().stream()
                        .map(documentId -> DocumentId.of(documentId.value()))
                        .toList())
                .toList();
    }

    private static Map<DocumentId, Long> componentGenerations(
            ClosureProcessResult result) {
        LinkedHashMap<DocumentId, Long> generations = new LinkedHashMap<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            generations.put(
                    DocumentId.of(document.documentId().value()),
                    document.componentGeneration());
        }
        return Map.copyOf(generations);
    }

    private static Map<DocumentId, HeadState> heads(
            CoordinationEngine engine) {
        LinkedHashMap<DocumentId, HeadState> result = new LinkedHashMap<>();
        for (DocumentId documentId : BRANCHING_DOCUMENTS) {
            result.put(documentId, new HeadState(
                    engine.document(documentId).blueId(),
                    engine.document(documentId).epoch()));
        }
        return Map.copyOf(result);
    }

    private static List<String> bindingIdentities(
            DefaultCoordinationEngine engine) {
        return engine.documents().publicationSnapshot()
                .occurrenceInventory().rows().stream()
                .map(binding -> binding.occurrenceIdentity()
                        + ":" + binding.bindingIdentity()
                        + ":" + binding.active())
                .toList();
    }

    private static List<String> componentStates(
            DefaultCoordinationEngine engine) {
        return engine.documents().publicationSnapshot()
                .componentStates().stream()
                .map(component -> component.componentIdentity()
                        + ":" + component.componentStateIdentity()
                        + ":" + component.componentGeneration()
                        + ":" + component.masterBlueId())
                .toList();
    }

    private static Node current(
            CoordinationEngine engine,
            DocumentId documentId) {
        return engine.document(documentId).current().copyNode();
    }

    private static long numberProperty(
            CoordinationEngine engine,
            DocumentId documentId,
            String name) {
        Object value = current(engine, documentId)
                .getProperties().get(name).getValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static CoordinationEngine engine(Set<DocumentId> publicRoots) {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(publicRoots));
    }

    private record BranchingIds(
            DocumentId a,
            DocumentId b1,
            DocumentId b2,
            DocumentId c1,
            DocumentId c2) {
    }

    private record Invocation(
            TimelineEntry entry,
            ProcessingDrainReceipt drain,
            ClosureProcessResult result,
            int routeTargetCount) {
    }

    private record HeadState(String blueId, long epoch) {
    }
}
