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
import blue.language.model.NodeWireForm;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.CheckpointWrite;
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
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

/** Public Contracts proof for component merge, split, and dissolution. */
final class ContractsPublicComponentMergeSplitTest {
    private static final long ENTRY_TIME = 2_300_000_000_000_001L;
    private static final DocumentId A = DocumentId.of("merge-split-a");
    private static final DocumentId B = DocumentId.of("merge-split-b");
    private static final DocumentId C = DocumentId.of("merge-split-c");
    private static final DocumentId D = DocumentId.of("merge-split-d");
    private static final List<DocumentId> FOUR = List.of(A, B, C, D);

    @Test
    void twoTwoMemberCyclesMergeIntoOneFourMemberCycle() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            ContractsClosureAdmissionReceipt admitted = publicEngine
                    .admitContractsClosure(
                            mergeAdmission(engine),
                            CoordinationEngine.AdmissionPolicy.FROM_NOW,
                            null);

            // then
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    admitted.publicationOutcome());
            assertPartition(
                    engine.documents().publicationSnapshot().componentStates(),
                    List.of(List.of(A, B), List.of(C, D)));
            assertVerifiedCycle(component(engine, A), List.of(A, B), 1L);
            assertVerifiedCycle(component(engine, C), List.of(C, D), 1L);
            assertCurrentReference(publicEngine, D, "/a", A);
            ManagedOccurrenceBinding prospective = row(engine, A, "/c");
            assertFalse(prospective.active());

            ExactValue request = publicEngine.referenceRequest(
                    "c", publicEngine.document(C).current());
            Invocation merged = invoke(
                    publicEngine,
                    engine,
                    "merge-split/merge",
                    Operation.exact("merge", "controlChannel", request),
                    ENTRY_TIME);

            assertSuccess(merged, 1, FOUR);
            assertEquals(4L,
                    merged.drain().committedProcessTransitions());
            assertPartition(merged.result().resultingComponents(),
                    List.of(FOUR));
            ComponentSnapshot cycle = merged.result()
                    .resultingComponents().get(0);
            assertVerifiedCycle(cycle, FOUR, 2L);
            assertGraphChanges(merged.result(), List.of(
                    new GraphDelta(GraphChange.Kind.REBIND, A, "/b", B),
                    new GraphDelta(GraphChange.Kind.ADD, A, "/c", C),
                    new GraphDelta(GraphChange.Kind.REBIND, B, "/a", A),
                    new GraphDelta(GraphChange.Kind.REBIND, C, "/d", D),
                    new GraphDelta(GraphChange.Kind.REBIND, D, "/a", A),
                    new GraphDelta(GraphChange.Kind.REBIND, D, "/c", C)));
            assertCurrentReference(publicEngine, A, "/b", B);
            assertCurrentReference(publicEngine, A, "/c", C);
            assertCurrentReference(publicEngine, B, "/a", A);
            assertCurrentReference(publicEngine, C, "/d", D);
            assertCurrentReference(publicEngine, D, "/c", C);
            assertCurrentReference(publicEngine, D, "/a", A);
            ManagedOccurrenceBinding activated = row(engine, A, "/c");
            assertTrue(activated.active());
            assertEquals(prospective.occurrenceIdentity(),
                    activated.occurrenceIdentity());
            assertEquals(prospective.activationGeneration(),
                    activated.activationGeneration());
            assertEquals(publicEngine.document(C).blueId(),
                    activated.expectedTargetBlueId());
            assertCommittedEvidence(merged);
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P5.1.merge-two-cycles",
                        engine,
                        merged.result(),
                        merged.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "prospectiveOccurrenceIdentity",
                                prospective.occurrenceIdentity(),
                                "prospectiveBindingIdentity",
                                prospective.bindingIdentity(),
                                "activatedOccurrenceIdentity",
                                activated.occurrenceIdentity(),
                                "activatedBindingIdentity",
                                activated.bindingIdentity(),
                                "mergedMasterBlueId", cycle.masterBlueId()));
            }
        }
    }

    @Test
    void oneFourMemberCycleSplitsIntoTwoTwoMemberCycles() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, splitFourA())
                            .document(B, plain(B))
                            .document(C, plain(C))
                            .document(D, plain(D))
                            .processEmbeddedPath(A, "/b", B)
                            .processEmbeddedPath(A, "/c", C)
                            .processEmbeddedPath(A, "/d", D)
                            .processEmbeddedPath(B, "/a", A)
                            .processEmbeddedPath(C, "/d", D)
                            .processEmbeddedPath(C, "/a", A)
                            .processEmbeddedPath(D, "/c", C)
                            .publicRoot(A)
                            .expectedComponent(A, B, C, D)
                            .admissionLabel("contracts-public-split-four");

            // then
            assertPublished(builder.admitTo(publicEngine));
            assertVerifiedCycle(component(engine, A), FOUR, 1L);
            String oldMaster = component(engine, A).masterBlueId();

            Invocation split = invoke(
                    publicEngine,
                    engine,
                    "merge-split/split-four",
                    Operation.yaml("split", "controlChannel", "{}"),
                    ENTRY_TIME + 1L);

            assertSuccess(split, 1, FOUR);
            assertEquals(4L,
                    split.drain().committedProcessTransitions());
            List<List<DocumentId>> expected = List.of(
                    List.of(A, B), List.of(C, D));
            assertPartition(split.result().resultingComponents(), expected);
            assertVerifiedCycle(
                    split.result().resultingComponents().get(0),
                    List.of(A, B),
                    2L);
            assertVerifiedCycle(
                    split.result().resultingComponents().get(1),
                    List.of(C, D),
                    2L);
            assertNotEquals(oldMaster, split.result()
                    .resultingComponents().get(0).masterBlueId());
            assertNotEquals(oldMaster, split.result()
                    .resultingComponents().get(1).masterBlueId());
            assertGraphChanges(split.result(), List.of(
                    new GraphDelta(GraphChange.Kind.REBIND, A, "/b", B),
                    new GraphDelta(GraphChange.Kind.REMOVE, A, "/c", C),
                    new GraphDelta(GraphChange.Kind.REMOVE, A, "/d", D),
                    new GraphDelta(GraphChange.Kind.REBIND, B, "/a", A),
                    new GraphDelta(GraphChange.Kind.REBIND, C, "/a", A),
                    new GraphDelta(GraphChange.Kind.REBIND, C, "/d", D),
                    new GraphDelta(GraphChange.Kind.REBIND, D, "/c", C)));
            assertNull(NodePathEditor.getOrNull(current(publicEngine, A),
                    "/c"));
            assertNull(NodePathEditor.getOrNull(current(publicEngine, A),
                    "/d"));
            assertCurrentReference(publicEngine, A, "/b", B);
            assertCurrentReference(publicEngine, B, "/a", A);
            assertCurrentReference(publicEngine, C, "/d", D);
            assertCurrentReference(publicEngine, D, "/c", C);
            // This containing reference crosses the final SCC boundary.
            assertCurrentReference(publicEngine, C, "/a", A);
            assertFalse(row(engine, A, "/c").active());
            assertFalse(row(engine, A, "/d").active());
            assertCommittedEvidence(split);
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P5.2.split-four-member-cycle",
                        engine,
                        split.result(),
                        split.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "oldMasterBlueId", oldMaster,
                                "newMasterBlueIds", split.result()
                                        .resultingComponents().stream()
                                        .map(ComponentSnapshot::masterBlueId)
                                        .toList()));
            }
        }
    }

    @Test
    void oneTwoMemberCycleSplitsIntoTwoOrdinarySingletons() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, splitPairA())
                            .document(B, plain(B))
                            .processEmbeddedPath(A, "/b", B)
                            .processEmbeddedPath(B, "/a", A)
                            .publicRoot(A)
                            .expectedComponent(A, B)
                            .admissionLabel("contracts-public-split-pair");

            // then
            assertPublished(builder.admitTo(publicEngine));
            assertVerifiedCycle(component(engine, A), List.of(A, B), 1L);

            Invocation split = invoke(
                    publicEngine,
                    engine,
                    "merge-split/split-pair",
                    Operation.yaml("split", "controlChannel", "{}"),
                    ENTRY_TIME + 2L);

            assertSuccess(split, 1, List.of(A, B));
            assertEquals(2L,
                    split.drain().committedProcessTransitions());
            assertPartition(split.result().resultingComponents(), List.of(
                    List.of(A), List.of(B)));
            assertOrdinaryComponent(
                    publicEngine,
                    split.result().resultingComponents().get(0),
                    A,
                    2L);
            assertOrdinaryComponent(
                    publicEngine,
                    split.result().resultingComponents().get(1),
                    B,
                    2L);
            assertGraphChanges(split.result(), List.of(
                    new GraphDelta(GraphChange.Kind.REMOVE, A, "/b", B),
                    new GraphDelta(GraphChange.Kind.REBIND, B, "/a", A)));
            assertNull(NodePathEditor.getOrNull(current(publicEngine, A),
                    "/b"));
            // The one-way containing reference survives and is exact.
            assertCurrentReference(publicEngine, B, "/a", A);
            assertFalse(row(engine, A, "/b").active());
            assertCommittedEvidence(split);
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P5.3.split-to-ordinary-singletons",
                        engine,
                        split.result(),
                        split.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "retiredOccurrenceIdentity",
                                row(engine, A, "/b").occurrenceIdentity(),
                                "retiredBindingIdentity",
                                row(engine, A, "/b").bindingIdentity()));
            }
        }
    }

    @Test
    void selfCycleDissolvesIntoOneOrdinaryDocument() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, dissolveSelfA())
                            .processEmbeddedPath(A, "/self", A)
                            .publicRoot(A)
                            .expectedComponent(A)
                            .admissionLabel("contracts-public-dissolve-self");

            // then
            assertPublished(builder.admitTo(publicEngine));
            assertVerifiedCycle(component(engine, A), List.of(A), 1L);

            Invocation dissolved = invoke(
                    publicEngine,
                    engine,
                    "merge-split/dissolve-self",
                    Operation.yaml("dissolve", "controlChannel", "{}"),
                    ENTRY_TIME + 3L);

            assertSuccess(dissolved, 1, List.of(A));
            assertEquals(1L,
                    dissolved.drain().committedProcessTransitions());
            assertPartition(dissolved.result().resultingComponents(),
                    List.of(List.of(A)));
            assertOrdinaryComponent(
                    publicEngine,
                    dissolved.result().resultingComponents().get(0),
                    A,
                    2L);
            assertGraphChanges(dissolved.result(), List.of(
                    new GraphDelta(
                            GraphChange.Kind.REMOVE, A, "/self", A)));
            assertNull(NodePathEditor.getOrNull(current(publicEngine, A),
                    "/self"));
            assertFalse(row(engine, A, "/self").active());
            assertCommittedEvidence(dissolved);
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P5.4.dissolve-self-cycle",
                        engine,
                        dissolved.result(),
                        dissolved.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "retiredOccurrenceIdentity",
                                row(engine, A, "/self").occurrenceIdentity(),
                                "retiredBindingIdentity",
                                row(engine, A, "/self").bindingIdentity()));
            }
        }
    }

    @Test
    void laterHandlerFailureRollsBackAlreadyStagedSplitExactly() {
        // given

        try (CoordinationEngine publicEngine = engine(Set.of(A, B))) {
            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) publicEngine;

            // when
            Contracts10ScenarioBuilder builder =
                    new Contracts10ScenarioBuilder(engine)
                            .document(A, failingSplitA())
                            .document(B, failingSplitB())
                            .processEmbeddedPath(A, "/b", B)
                            .processEmbeddedPath(B, "/a", A)
                            .publicRoot(A)
                            .publicRoot(B)
                            .expectedComponent(A, B)
                            .admissionLabel(
                                    "contracts-public-failing-split");

            // then
            assertPublished(builder.admitTo(publicEngine));
            InMemoryDocumentStore.PublicationSnapshot before = engine
                    .documents().publicationSnapshot();
            ComponentSnapshot oldComponent = component(engine, A);
            assertVerifiedCycle(oldComponent, List.of(A, B), 1L);

            Invocation rejected = invoke(
                    publicEngine,
                    engine,
                    "merge-split/failing-split",
                    Operation.yaml("split", "controlChannel", "{}"),
                    ENTRY_TIME + 4L);

            assertEquals(2, rejected.routeTargetCount());
            assertTrue(rejected.drain().quiescent());
            assertFalse(rejected.drain().paused());
            assertTrue(rejected.drain().outcomes().isEmpty());
            assertEquals(0L,
                    rejected.drain().committedProcessTransitions());
            ClosureProcessResult result = rejected.result();
            assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
            assertFalse(result.commits());
            assertTrue(result.atomic());
            assertTrue(result.rollbackToInput());
            assertEquals(result.inputClosureIdentity(),
                    result.outputClosureIdentity());
            assertNull(result.platformCommitCompanion());
            assertNotNull(result.diagnostic());
            assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                    result.diagnostic().category());
            assertEquals(List.of(A.value(), B.value()),
                    dequeuedDocumentIds(result));
            assertSplitStagedBeforeLaterFailure(result);
            assertTrue(result.graphChanges().isEmpty());
            assertTrue(result.subscriptionDeltas().isEmpty());
            assertTrue(result.checkpointWrites().isEmpty());
            assertTrue(result.publicEvents().isEmpty());

            InMemoryDocumentStore.PublicationSnapshot after = rejected.after();
            assertRollbackState(before, after);
            assertCurrentReference(publicEngine, A, "/b", B);
            assertCurrentReference(publicEngine, B, "/a", A);
            assertTrue(row(engine, A, "/b").active());
            assertEquals(componentProjection(oldComponent),
                    componentProjection(component(engine, A)));
            // Only the terminal non-commit receipt is atomically added.
            assertEquals(1, rejected.addedReceipts().size());
            assertEquals(before.publicationReceipts().size() + 1,
                    after.publicationReceipts().size());
            if (CyclicTopologyIdentityEvidenceTest.isActive()) {
                CyclicTopologyIdentityEvidenceTest.capture(
                        "P5.5.late-failure-rollback",
                        engine,
                        result,
                        rejected.drain(),
                        CyclicTopologyIdentityEvidenceTest.facts(
                                "oldComponentIdentity",
                                oldComponent.componentIdentity(),
                                "oldComponentStateIdentity",
                                oldComponent.componentStateIdentity(),
                                "oldMasterBlueId",
                                oldComponent.masterBlueId(),
                                "durableOccurrenceIdentity",
                                row(engine, A, "/b").occurrenceIdentity(),
                                "durableBindingIdentity",
                                row(engine, A, "/b").bindingIdentity()));
            }
        }
    }

    private static ClosureInvocationInput mergeAdmission(
            DefaultCoordinationEngine engine) {
        Contracts10ScenarioBuilder.Scenario initial =
                new Contracts10ScenarioBuilder(engine)
                        .document(A, mergeA())
                        .document(B, plain(B))
                        .document(C, plain(C))
                        .document(D, plain(D))
                        .processEmbeddedPath(A, "/b", B)
                        .processEmbeddedPath(B, "/a", A)
                        .processEmbeddedPath(C, "/d", D)
                        .processEmbeddedPath(D, "/c", C)
                        .processEmbeddedPath(D, "/a", A)
                        .publicRoot(A)
                        .expectedComponent(A, B)
                        .expectedComponent(C, D)
                        .admissionLabel("contracts-public-merge-four")
                        .scenario();

        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                bodies = new LinkedHashMap<>();
        for (DocumentId documentId : FOUR) {
            bodies.put(closureId(documentId), initial.document(documentId));
        }
        Node paths = bodies.get(closureId(A)).getContracts()
                .getProperties().get("embedded")
                .getProperties().get("paths");
        paths.getItems().add(new Node().value("/c"));

        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                generations = generations(FOUR, 1L);
        ManagedDocumentGraph activeGraph = ManagedDocumentGraph.fromBindings(
                FOUR.stream().map(
                        ContractsPublicComponentMergeSplitTest::closureId)
                        .toList(),
                initial.bindings());
        ComponentFinalizationResult active =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                activeGraph,
                                generations,
                                bodies,
                                initial.bindings()));
        ClosureEnvironment environment = engine
                .contractsClosureAdmissionAdapter().environment();
        ManagedOccurrenceBinding inactive = ManagedOccurrenceBinding.derived(
                environment.managedBindingPolicyIdentity(),
                closureId(A),
                ScopeAddress.embedded("/c", 1L),
                closureId(C),
                active.document(closureId(C)).blueId(),
                false,
                null);
        List<ManagedOccurrenceBinding> completeRows = new ArrayList<>(
                active.finalizedGraph().bindings());
        completeRows.add(inactive);
        ManagedDocumentGraph completeGraph = ManagedDocumentGraph.fromBindings(
                FOUR.stream().map(
                        ContractsPublicComponentMergeSplitTest::closureId)
                        .toList(),
                completeRows);
        LinkedHashMap<blue.language.processor.closure.DocumentId, Node>
                activeBodies = new LinkedHashMap<>();
        active.documents().forEach((documentId, document) ->
                activeBodies.put(documentId, document.document()));
        ComponentFinalizationResult exact =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                completeGraph,
                                generations,
                                activeBodies,
                                completeRows));

        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (FinalizedDocumentEvidence document
                : exact.documents().values()) {
            documents.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    document.document(),
                    false,
                    false,
                    document.documentId().equals(closureId(A)),
                    0L,
                    document.componentGeneration()));
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
        ExecutionPolicy policy = engine.contractsClosureAdmissionAdapter()
                .executionPolicy();
        return ClosureEvidenceFactory.admitClosure(
                snapshot,
                ClosureEvidenceFactory.admissionCause(
                        AdmissionKind.TOP_LEVEL_ADMISSION,
                        "contracts-public-merge-four",
                        null,
                        null,
                        "contracts-top-level-admission-v1"),
                null,
                policy,
                environment);
    }

    private static Invocation invoke(
            CoordinationEngine publicEngine,
            DefaultCoordinationEngine engine,
            String timelineId,
            Operation operation,
            long eventTime) {
        InMemoryDocumentStore.PublicationSnapshot before = engine
                .documents().publicationSnapshot();
        Timeline timeline = publicEngine.registerTimeline(
                timelineId, "alice");
        TimelineEntry entry = publicEngine.appendAt(
                timeline, operation, eventTime);
        int routeTargetCount = publicEngine.routeTargetCount(entry);
        ProcessingDrainReceipt drain = publicEngine.drain();
        InMemoryDocumentStore.PublicationSnapshot after = engine
                .documents().publicationSnapshot();
        List<ContractsClosurePublicationReceipt> added = after
                .closurePublicationReceipts().entrySet().stream()
                .filter(receipt -> !before.closurePublicationReceipts()
                        .containsKey(receipt.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        assertEquals(1, added.size(), () -> "routeTargets="
                + routeTargetCount + ", processed="
                + drain.processedEntries().size() + ", outcomes="
                + drain.outcomes().size() + ", quiescent="
                + drain.quiescent() + ", paused=" + drain.paused());
        assertEquals(List.of(entry), drain.processedEntries());
        return new Invocation(
                entry,
                drain,
                added.get(0).attempt().processResult(),
                routeTargetCount,
                before,
                after,
                added);
    }

    private static void assertSuccess(
            Invocation invocation,
            int routeTargets,
            List<DocumentId> receiptDocuments) {
        assertEquals(routeTargets, invocation.routeTargetCount());
        assertTrue(invocation.drain().quiescent());
        assertFalse(invocation.drain().paused());
        assertEquals(ProcessorStatus.SUCCESS, invocation.result().status());
        assertTrue(invocation.result().commits());
        assertTrue(invocation.result().atomic());
        assertFalse(invocation.result().rollbackToInput());
        assertNotNull(invocation.result().platformCommitCompanion());
        assertEquals(receiptDocuments,
                invocation.addedReceipts().get(0).documentIds());
        assertEquals(receiptDocuments,
                invocation.drain().outcomesFor(invocation.entry().blueId())
                        .stream()
                        .map(outcome -> outcome.documentId())
                        .toList());
        assertEquals(invocation.result().outputClosureIdentity(),
                invocation.result().platformCommitCompanion()
                        .outputClosureIdentity());
    }

    private static void assertCommittedEvidence(Invocation invocation) {
        ClosureProcessResult result = invocation.result();
        InMemoryDocumentStore.PublicationSnapshot before =
                invocation.before();
        InMemoryDocumentStore.PublicationSnapshot after = invocation.after();
        assertEquals(before.publicationReceipts().size() + 1,
                after.publicationReceipts().size());
        assertTrue(after.publicationReceipts().contains(
                invocation.addedReceipts().get(0).publicationIdentity()));
        assertEquals(before.componentIndexGeneration() + 1,
                after.componentIndexGeneration());
        assertEquals(before.occurrenceInventoryGeneration() + 1,
                after.occurrenceInventoryGeneration());
        assertEquals(before.graphGenerations().require(A) + 1,
                result.graphGeneration());
        for (blue.language.processor.closure.ResultingDocument document
                : result.resultingDocuments()) {
            DocumentId id = DocumentId.of(document.documentId().value());
            assertEquals(document.afterBlueId(),
                    after.requireHead(id).blueId());
            assertEquals(before.requireHead(id).epoch() + 1L,
                    after.requireHead(id).epoch());
            assertEquals(document.epoch(), after.requireHead(id).epoch());
            assertEquals(result.graphGeneration(),
                    after.graphGenerations().require(id));
        }
        assertEquals(
                result.resultingComponents().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::componentProjection)
                        .toList(),
                after.componentStates().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::componentProjection)
                        .toList());
        assertEquals(List.of("controlChannel"),
                result.checkpointWrites().stream()
                        .map(CheckpointWrite::rawChannelKey)
                        .toList());
        assertEquals(List.of(SubscriptionDelta.Operation.REPLACE),
                result.subscriptionDeltas().stream()
                        .map(SubscriptionDelta::operation)
                        .toList());
        assertCheckpointAppend(before, after, result);
        assertSubscriptionApplication(before, after, result);
    }

    private static void assertCheckpointAppend(
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after,
            ClosureProcessResult result) {
        List<CheckpointProjection> expected = new ArrayList<>(
                before.checkpointEvidence().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::checkpointProjection)
                        .toList());
        expected.addAll(result.checkpointWrites().stream()
                .map(ContractsPublicComponentMergeSplitTest
                        ::checkpointProjection)
                .toList());
        assertEquals(expected, after.checkpointEvidence().stream()
                .map(ContractsPublicComponentMergeSplitTest
                        ::checkpointProjection)
                .toList());
        for (int index = 0;
                index < result.checkpointWrites().size(); index++) {
            assertEquals(index, result.checkpointWrites().get(index)
                    .checkpointWriteOrdinal());
        }
    }

    private static void assertSubscriptionApplication(
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after,
            ClosureProcessResult result) {
        LinkedHashSet<String> expected = new LinkedHashSet<>(
                subscriptionIdentities(before));
        for (int index = 0;
                index < result.subscriptionDeltas().size(); index++) {
            SubscriptionDelta delta = result.subscriptionDeltas().get(index);
            assertEquals(index, delta.subscriptionDeltaOrdinal());
            if (delta.beforeSubscriptionIdentity() != null) {
                assertTrue(expected.remove(
                        delta.beforeSubscriptionIdentity()));
            }
            if (delta.afterSubscriptionIdentity() != null) {
                assertTrue(expected.add(
                        delta.afterSubscriptionIdentity()));
            }
        }
        assertFalse(result.subscriptionDeltas().isEmpty());
        assertEquals(expected, new LinkedHashSet<>(
                subscriptionIdentities(after)));
    }

    private static void assertRollbackState(
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after) {
        assertEquals(before.documentHeads(), after.documentHeads());
        assertEquals(before.occurrenceInventoryGeneration(),
                after.occurrenceInventoryGeneration());
        assertEquals(before.componentIndexGeneration(),
                after.componentIndexGeneration());
        assertEquals(occurrenceProjections(before),
                occurrenceProjections(after));
        assertEquals(before.graphGenerations().require(A),
                after.graphGenerations().require(A));
        assertEquals(before.graphGenerations().require(B),
                after.graphGenerations().require(B));
        assertEquals(before.componentStates().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::componentProjection)
                        .toList(),
                after.componentStates().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::componentProjection)
                        .toList());
        assertEquals(subscriptionIdentities(before),
                subscriptionIdentities(after));
        assertEquals(before.outbox().stream()
                        .map(event -> event.eventOccurrenceIdentity())
                        .toList(),
                after.outbox().stream()
                        .map(event -> event.eventOccurrenceIdentity())
                        .toList());
        assertEquals(before.checkpointEvidence().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::checkpointProjection)
                        .toList(),
                after.checkpointEvidence().stream()
                        .map(ContractsPublicComponentMergeSplitTest
                                ::checkpointProjection)
                        .toList());
    }

    private static void assertSplitStagedBeforeLaterFailure(
            ClosureProcessResult result) {
        List<GasTraceEntry> dequeues = result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued".equals(
                        entry.counter()))
                .toList();
        assertEquals(2, dequeues.size());
        long secondWork = dequeues.get(1).sequence();
        List<String> stagedCounters = result.gasTrace().stream()
                .filter(entry -> entry.sequence() < secondWork)
                .map(GasTraceEntry::counter)
                .toList();
        int removed = stagedCounters.indexOf("patchRemove");
        int partitioned = stagedCounters.indexOf(
                "componentPartitionChanged");
        assertTrue(removed >= 0);
        assertTrue(partitioned > removed);
        assertTrue(stagedCounters.subList(partitioned,
                        stagedCounters.size()).contains(
                                "componentMemberPartitioned"));
        assertTrue(stagedCounters.subList(partitioned,
                        stagedCounters.size()).contains(
                                "componentEdgePartitioned"));
    }

    private static void assertGraphChanges(
            ClosureProcessResult result,
            List<GraphDelta> expected) {
        List<GraphDelta> actual = result.graphChanges().stream()
                .map(change -> new GraphDelta(
                        change.changeKind(),
                        DocumentId.of(change.sourceDocumentId().value()),
                        change.sourcePath(),
                        DocumentId.of((change.afterTargetDocumentId() == null
                                ? change.beforeTargetDocumentId()
                                : change.afterTargetDocumentId()).value())))
                .toList();
        assertEquals(expected, actual);
        for (int index = 0;
                index < result.graphChanges().size(); index++) {
            assertEquals(index, result.graphChanges().get(index)
                    .graphChangeOrdinal());
        }
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
        assertNotNull(component.componentIdentity());
        assertNotNull(component.componentStateIdentity());
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

    private static void assertOrdinaryComponent(
            CoordinationEngine engine,
            ComponentSnapshot component,
            DocumentId documentId,
            long generation) {
        assertEquals(ComponentKind.ACYCLIC, component.kind());
        assertEquals(List.of(documentId.value()),
                component.orderedMemberDocumentIds().stream()
                        .map(blue.language.processor.closure.DocumentId::value)
                        .toList());
        assertEquals(generation, component.componentGeneration());
        assertNotNull(component.componentIdentity());
        assertNotNull(component.componentStateIdentity());
        assertNull(component.masterBlueId());
        assertNull(component.cyclicProofIdentity());
        assertNull(component.completeCyclicProof());
        ExactValue current = engine.document(documentId).current();
        assertFalse(current.isCyclicMember());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(current.copyNode()),
                current.blueId());
        assertEquals(List.of(current.blueId()),
                component.orderedMemberBlueIds());
    }

    private static void assertPartition(
            List<ComponentSnapshot> components,
            List<List<DocumentId>> expected) {
        assertEquals(expected, components.stream()
                .map(component -> component.orderedMemberDocumentIds().stream()
                        .map(documentId -> DocumentId.of(documentId.value()))
                        .toList())
                .toList());
    }

    private static void assertCurrentReference(
            CoordinationEngine engine,
            DocumentId source,
            String path,
            DocumentId target) {
        Node reference = NodePathEditor.getOrNull(current(engine, source),
                path);
        assertNotNull(reference);
        assertEquals(engine.document(target).blueId(), reference.getBlueId());
    }

    private static Node current(
            CoordinationEngine engine,
            DocumentId documentId) {
        return engine.document(documentId).current().copyNode();
    }

    private static ComponentSnapshot component(
            DefaultCoordinationEngine engine,
            DocumentId member) {
        return engine.documents().publicationSnapshot().componentStates()
                .stream()
                .filter(component -> component.orderedMemberDocumentIds()
                        .contains(closureId(member)))
                .findFirst()
                .orElseThrow();
    }

    private static ManagedOccurrenceBinding row(
            DefaultCoordinationEngine engine,
            DocumentId source,
            String path) {
        return engine.documents().publicationSnapshot()
                .occurrenceInventory().row(source, path);
    }

    private static void assertPublished(
            Contracts10ScenarioBuilder.ScenarioRuntime admitted) {
        assertEquals(
                ContractsClosureAdmissionReceipt.PublicationOutcome
                        .PUBLISHED,
                admitted.admissionReceipt().publicationOutcome());
    }

    private static List<String> dequeuedDocumentIds(
            ClosureProcessResult result) {
        return result.gasTrace().stream()
                .filter(entry -> "closureWorkOccurrenceDequeued".equals(
                        entry.counter()))
                .map(entry -> entry.documentId().value())
                .toList();
    }

    private static List<String> subscriptionIdentities(
            InMemoryDocumentStore.PublicationSnapshot snapshot) {
        return snapshot.closureSubscriptions().states().stream()
                .map(state -> state.subscriptionIdentity())
                .toList();
    }

    private static List<OccurrenceProjection> occurrenceProjections(
            InMemoryDocumentStore.PublicationSnapshot snapshot) {
        return snapshot.occurrenceInventory().rows().stream()
                .map(row -> new OccurrenceProjection(
                        row.occurrenceIdentity(),
                        row.bindingIdentity(),
                        row.sourceDocumentId().value(),
                        row.sourcePath(),
                        row.activationGeneration(),
                        row.targetDocumentId().value(),
                        row.expectedTargetBlueId(),
                        row.active(),
                        row.pendingHistoricalEpoch()))
                .toList();
    }

    private static ComponentProjection componentProjection(
            ComponentSnapshot component) {
        return new ComponentProjection(
                component.componentIdentity(),
                component.componentStateIdentity(),
                component.componentGeneration(),
                component.kind(),
                component.orderedMemberDocumentIds().stream()
                        .map(blue.language.processor.closure.DocumentId::value)
                        .toList(),
                component.orderedMemberBlueIds(),
                component.masterBlueId(),
                component.cyclicProofIdentity(),
                component.completeCyclicProof() == null
                        ? List.of()
                        : component.completeCyclicProof()
                                .declaredPlaceholderSet().stream()
                                .map(NodeWireForm::get)
                                .toList());
    }

    private static CheckpointProjection checkpointProjection(
            CheckpointWrite checkpoint) {
        return new CheckpointProjection(
                checkpoint.checkpointWriteOrdinal(),
                checkpoint.targetManagedScopeIdentity(),
                checkpoint.rawChannelKey(),
                checkpoint.beforeDomainBlueId(),
                checkpoint.beforeSubjectBlueId(),
                checkpoint.afterDomainBlueId(),
                checkpoint.afterSubjectBlueId());
    }

    private static LinkedHashMap<
            blue.language.processor.closure.DocumentId, Long> generations(
                    List<DocumentId> members,
                    long generation) {
        LinkedHashMap<blue.language.processor.closure.DocumentId, Long>
                result = new LinkedHashMap<>();
        for (DocumentId member : members) {
            result.put(closureId(member), generation);
        }
        return result;
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static CoordinationEngine engine(Set<DocumentId> roots) {
        return CoordinationEngine.inMemoryContracts10(
                BundledContracts10Release.configuration(
                        new LinkedHashSet<>(roots)));
    }

    private static String mergeA() {
        return controlDocument(
                A,
                "merge-split/merge",
                """
                  merge:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request:
                      c: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /c
                              val: {$binding: event/message/request/c}
                          - $appendChange: {op: replace, path: /phase, val: merged}
                          - $return: true
                """);
    }

    private static String splitFourA() {
        return controlDocument(
                A,
                "merge-split/split-four",
                """
                  split:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /c}
                          - $appendChange: {op: remove, path: /d}
                          - $appendChange: {op: replace, path: /phase, val: split}
                          - $return: true
                """);
    }

    private static String splitPairA() {
        return controlDocument(
                A,
                "merge-split/split-pair",
                """
                  split:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /b}
                          - $appendChange: {op: replace, path: /phase, val: split}
                          - $return: true
                """);
    }

    private static String dissolveSelfA() {
        return controlDocument(
                A,
                "merge-split/dissolve-self",
                """
                  dissolve:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /self}
                          - $appendChange: {op: replace, path: /phase, val: ordinary}
                          - $return: true
                """);
    }

    private static String failingSplitA() {
        return controlDocument(
                A,
                "merge-split/failing-split",
                """
                  split:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /b}
                          - $appendChange: {op: replace, path: /phase, val: staged-split}
                          - $return: true
                """);
    }

    private static String failingSplitB() {
        return controlDocument(
                B,
                "merge-split/failing-split",
                """
                  split:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /does-not-exist}
                          - $return: true
                """);
    }

    private static String controlDocument(
            DocumentId documentId,
            String timelineId,
            String operationContract) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                %s
                """.formatted(
                documentId.value(),
                timelineId,
                operationContract.stripTrailing());
    }

    private static String plain(DocumentId documentId) {
        return """
                documentId: %s
                phase: initial
                contracts: {}
                """.formatted(documentId.value());
    }

    private record Invocation(
            TimelineEntry entry,
            ProcessingDrainReceipt drain,
            ClosureProcessResult result,
            int routeTargetCount,
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after,
            List<ContractsClosurePublicationReceipt> addedReceipts) {
        private Invocation {
            addedReceipts = List.copyOf(addedReceipts);
        }
    }

    private record GraphDelta(
            GraphChange.Kind kind,
            DocumentId source,
            String path,
            DocumentId target) {
    }

    private record OccurrenceProjection(
            String occurrenceIdentity,
            String bindingIdentity,
            String source,
            String path,
            long activationGeneration,
            String target,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
    }

    private record ComponentProjection(
            String componentIdentity,
            String componentStateIdentity,
            long generation,
            ComponentKind kind,
            List<String> members,
            List<String> memberBlueIds,
            String masterBlueId,
            String proofIdentity,
            List<Object> proofMembers) {
        private ComponentProjection {
            members = List.copyOf(members);
            memberBlueIds = List.copyOf(memberBlueIds);
            proofMembers = List.copyOf(proofMembers);
        }
    }

    private record CheckpointProjection(
            long ordinal,
            String managedScopeIdentity,
            String rawChannelKey,
            String beforeDomainBlueId,
            String beforeSubjectBlueId,
            String afterDomainBlueId,
            String afterSubjectBlueId) {
    }
}
