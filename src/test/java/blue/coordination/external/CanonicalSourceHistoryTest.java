package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.coordination.processor.*;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSourceHistoryTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void realSourcePrefixRetainsChronologyAndObserversSelectHistoryWithoutRebirthingSource() {
        try (Fixture f = new Fixture()) {
            ManagedDocumentSnapshot authored = f.source();
            var e15 = input("source-change", 15, "account");
            var ignored17 = input("not-addressed-to-source", 17, "other-account");
            var future25 = input("future-change", 25, "account");
            List<CoordinationCore.TimelineInput> inputs = List.of(e15, ignored17, future25);
            ExternalOrderKey t20 = input("order-attaches", 20, "account").order();
            var history = new CanonicalSourceHistory(f.core);
            var prepared = f.prepare(history, authored, t20, inputs, 31);
            assertEquals(3, prepared.steps.size(), "initialization, E15 operation, unmatched E17 disposition");
            assertEquals(2, prepared.boundary.cursor().operations());
            assertEquals(3, prepared.boundary.cursor().records());
            assertInstanceOf(CoordinationCore.MetadataProgress.class, prepared.steps.get(2).evaluation());
            var sourceOperation = singletonGroup(prepared.steps.get(1).evaluation());
            assertEquals(sourceOperation.operationId(), prepared.boundary.cursor().semanticPredecessor().orElseThrow(),
                    "unmatched input does not invent a semantic predecessor");
            assertEquals(ignored17.order(), prepared.boundary.cursor().handledThrough().orElseThrow());
            assertEquals(BigInteger.valueOf(5), f.restore(prepared.boundary.cursor().successfulView().orElseThrow())
                    .document().getProperties().get("counter").getValue());
            assertEquals(1, sourceOperation.result().events().size());

            var full = assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fullHistory(t20), prepared.boundary.cursor(), Optional.empty()));
            var now = assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromNow(t20), prepared.boundary.cursor(), Optional.of(prepared.boundary)));
            assertEquals(full.canonicalSourceBasis(), now.canonicalSourceBasis());
            assertEquals(BigInteger.ZERO, f.restore(full.installedView()).document().getProperties().get("counter").getValue());
            assertEquals(BigInteger.valueOf(5), f.restore(now.installedView()).document().getProperties().get("counter").getValue());
            assertTrue(full.historicalLane().orElseThrow().includes(e15.order()));
            assertFalse(full.historicalLane().orElseThrow().includes(future25.order()));
            assertTrue(now.historicalLane().isEmpty());

            // Both observers use actual Coordination initialization. Only FULL_HISTORY consumes E15 afterward.
            var historicalObserver = f.observer("historical-order", full.installedView());
            var liveObserver = f.observer("live-order", now.installedView());
            assertEquals(BigInteger.ZERO, historicalObserver.before.document().getProperties().get("seen").getValue());
            assertEquals(BigInteger.ZERO, liveObserver.before.document().getProperties().get("seen").getValue());
            var sourceProgram = OperationReceiptCodec.restoreSourceProgram(prepared.steps.get(1).receiptIdentity(), f.blobs::get, LIMITS);
            var sourceBirth = assertInstanceOf(CoordinationCore.PreparedOperation.class, prepared.steps.get(0).evaluation());
            // Transport fixture: the host supplies an already committed occurrence's selected lane basis.
            var lane = ManagedImportLane.Descriptor.fromPlan(historicalObserver.binding,
                    ManagedImportLane.digest("test-committed-creation", historicalObserver.before.blueId()),
                    ManagedImportLane.digest("test-creation-seed", historicalObserver.before.blueId()),
                    ManagedImportLane.digest("test-creation-site", historicalObserver.before.blueId()), full,
                    ManagedImportLane.Header.fromOperation(sourceBirth, authored.documentId(), full.canonicalSourceBasis()));
            var due = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane),
                    ManagedImportLane.Header.fromOperation(sourceOperation, authored.documentId(), full.canonicalSourceBasis()),
                    Optional.of(ManagedImportLane.PrefixAuthority.fromBoundary(lane, prepared.boundary)));
            var imported = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new ManagedImportSelection(List.of(due)),
                    f.observerEvidence(historicalObserver, f.restore(full.installedView()), List.of(e15), List.of(sourceProgram), Optional.empty())));
            assertEquals(ProcessorStatus.SUCCESS, imported.result().status());
            assertEquals(BigInteger.valueOf(5), imported.projections().get(0).result().document().getProperties().get("seen").getValue());
            assertEquals(List.of(sourceOperation.operationId()), imported.consumedSourceOperations());
            assertEquals(1, imported.projections().size(), "the source is not re-executed or re-published by its observer");
            assertEquals(historicalObserver.before.epoch() + 1, imported.projections().get(0).afterEpoch());
            var liveNoReplay = f.core.evaluate(new CoordinationCore.WorkIntent(liveObserver.before.documentId(),
                            CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    f.observerEvidence(liveObserver, f.restore(now.installedView()), List.of(e15), List.of(sourceProgram), Optional.of(now.liveAfter())));
            assertInstanceOf(CoordinationCore.Idle.class, liveNoReplay);

            // Different preparation cut/physical fences produce byte-identical intrinsic initialization and E15 receipts.
            ExternalOrderKey t21 = input("another-attachment", 21, "account").order();
            var replay = f.prepare(new CanonicalSourceHistory(f.core), authored, t21, inputs, 31);
            assertEquals(prepared.steps.stream().map(CanonicalSourceHistory.Step::receiptIdentity).toList(),
                    replay.steps.stream().map(CanonicalSourceHistory.Step::receiptIdentity).toList());
            assertEquals(prepared.boundary.cursor().recordIdentity(), replay.boundary.cursor().recordIdentity());
            assertEquals(sourceOperation.result().totalGas(), singletonGroup(replay.steps.get(1).evaluation()).result().totalGas());
            assertEquals(2, replay.boundary.cursor().operations(), "E25 remains unprocessed, not skipped into this prefix");
        }
    }

    @Test
    void boundedColdResumeReadsOneHeadAndDoesNotRepeatAlreadyHandledWork() {
        try (Fixture f = new Fixture()) {
            var authored = f.source(); var e15 = input("change", 15, "account");
            ExternalOrderKey cut = input("attach", 20, "account").order();
            var history = new CanonicalSourceHistory(f.core);
            var prepared = f.prepare(history, authored, cut, List.of(e15), 21);
            AtomicInteger reads = new AtomicInteger();
            var resumed = new CanonicalSourceHistory(f.core).resume(authored.documentId(),
                    prepared.boundary.cursor().recordIdentity().orElseThrow(), key -> { reads.incrementAndGet(); return f.blobs.get(key); }, LIMITS);
            assertEquals(1, reads.get(), "resuming the prefix head must not walk old receipts/programs");
            assertEquals(prepared.boundary.cursor().semanticPredecessor(), resumed.semanticPredecessor());
            assertEquals(prepared.boundary.cursor().publications(), resumed.publications());
            assertEquals(CanonicalSourceHistory.RecordKind.SEMANTIC_OPERATION, resumed.recordKind().orElseThrow());
            assertInstanceOf(CanonicalSourceHistory.Complete.class, history.prepareNext(new CanonicalSourceHistory.Request(authored.documentId(), cut),
                    resumed, f.evidence(f.restore(resumed.successfulView().orElseThrow()), List.of(e15), 21), f.blobs::put, LIMITS));
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(
                    new CanonicalSourceHistory.Request(authored.documentId(), input("earlier", 10, "account").order()), resumed,
                    f.evidence(f.restore(resumed.successfulView().orElseThrow()), List.of(e15), 21), f.blobs::put, LIMITS));
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(
                    new CanonicalSourceHistory.Request(authored.documentId(), cut), resumed,
                    f.evidence(authored, List.of(e15), 21), f.blobs::put, LIMITS));
            var changedPolicy = new CanonicalSourceHistory(new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(90_000, Map.of(), "other-fixed-policy")));
            assertThrows(InvalidExecutionEvidenceException.class, () -> changedPolicy.resume(authored.documentId(),
                    resumed.recordIdentity().orElseThrow(), f.blobs::get, LIMITS));
        }
    }

    @Test
    void fullHistoryCreatorCanStartAtInitializationWhileFromNowWaitsForExactCompleteBoundary() {
        try (Fixture f = new Fixture()) {
            var authored = f.source(); ExternalOrderKey cut = input("attach", 20, "account").order();
            var history = new CanonicalSourceHistory(f.core);
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(authored.documentId(), cut), history.start(authored.documentId()),
                    f.evidence(authored, List.of(), 20), f.blobs::put, LIMITS));
            var full = ObserverAttachmentPlan.select(ObserverAttachmentPlan.Selection.fullHistory(cut), birth.after(), Optional.empty());
            assertInstanceOf(ObserverAttachmentPlan.Ready.class, full);
            assertInstanceOf(ObserverAttachmentPlan.Await.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromNow(cut), birth.after(), Optional.empty()));
            var wait = assertInstanceOf(CanonicalSourceHistory.Await.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(authored.documentId(), cut), birth.after(),
                    f.evidence(f.restore(birth.after().successfulView().orElseThrow()), List.of(), 20), f.blobs::put, LIMITS));
            assertEquals(List.of("timeline-complete-after:timeline:20"), wait.keys());
            var complete = assertInstanceOf(CanonicalSourceHistory.Complete.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(authored.documentId(), cut), birth.after(),
                    f.evidence(f.restore(birth.after().successfulView().orElseThrow()), List.of(), 21), f.blobs::put, LIMITS));
            assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromNow(cut), birth.after(), Optional.of(complete.boundary())));
            ExternalOrderKey later = input("later", 30, "account").order();
            var frontier = assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromFrontier(later, cut), birth.after(), Optional.of(complete.boundary())));
            assertFalse(frontier.historicalLane().orElseThrow().includes(cut));
            assertTrue(frontier.historicalLane().orElseThrow().includes(input("between", 25, "account").order()));
            assertThrows(InvalidExecutionEvidenceException.class, () -> ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromNow(later), birth.after(), Optional.of(complete.boundary())));
        }
    }

    @Test
    void failedCanonicalInitializationCannotAdvanceOrBeReinterpretedAsUsableHistory() {
        try (Fixture f = new Fixture()) {
            var source = f.source();
            var history = new CanonicalSourceHistory(new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(0, Map.of(), "source-zero-gas")));
            var cursor = history.start(source.documentId());
            var failed = assertInstanceOf(CanonicalSourceHistory.Blocked.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(source.documentId(), input("attach", 20, "account").order()), cursor,
                    f.evidence(source, List.of(), 21), f.blobs::put, LIMITS));
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.attempt().result().status());
            assertEquals(0, cursor.records()); assertTrue(cursor.successfulView().isEmpty());
            assertInstanceOf(ObserverAttachmentPlan.Await.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fullHistory(input("attach", 20, "account").order()), cursor, Optional.empty()));
            assertFalse(OperationReceiptCodec.decode(failed.receiptIdentity(), f.blobs::get, LIMITS).sourceProgramIdentity().isPresent());
        }
    }

    @Test
    void canonicalBirthCannotInitializeAnotherBodyUnderAnExistingAuthoredLineage() {
        try (Fixture f = new Fixture()) {
            var source = f.source(); var other = ExactValue.verified(new Node().name("another authored document"));
            var history = new CanonicalSourceHistory(f.core);
            var request = new CanonicalSourceHistory.Request(source.documentId(), input("attach", 20, "account").order());
            var cursor = history.start(source.documentId());
            var substituted = new ManagedDocumentSnapshot(source.documentId(), other.blueId(), other.copyNode(), false, false, true, 0, 0);
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(request, cursor,
                    f.evidence(substituted, List.of(), 21), f.blobs::put, LIMITS));
            var wrongEpoch = new ManagedDocumentSnapshot(source.documentId(), source.blueId(), source.document(), false, false, true, 1, 0);
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(request, cursor,
                    f.evidence(wrongEpoch, List.of(), 21), f.blobs::put, LIMITS));
            assertTrue(f.blobs.isEmpty(), "Invalid birth cannot stage a semantic receipt");
        }
    }

    @Test
    void authoredParentEmbeddingUsesPreparedCanonicalChildInitialization() {
        try (Fixture f = new Fixture()) {
            var childAuthored = f.source(); f.nodes.put(childAuthored.blueId(), childAuthored.document());
            ExternalOrderKey cut = input("attach", 20, "account").order();
            var history = new CanonicalSourceHistory(f.core);
            var childBirth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(childAuthored.documentId(), cut), history.start(childAuthored.documentId()),
                    f.evidence(childAuthored, List.of(), 21), f.blobs::put, LIMITS));
            var child = f.restore(childBirth.after().successfulView().orElseThrow());
            Node body = f.runtime.yamlToNode("""
                    name: Authored parent
                    contracts:
                      embedded:
                        type: {blueId: %s}
                        paths: [/child]
                    """.formatted(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED))
                    .properties("child", new Node().blueId(childAuthored.blueId()));
            body = f.runtime.resolveToSnapshotPreservingPaths(body, List.of("/child")).canonicalRoot();
            var exact = ExactValue.verified(body); var id = new DocumentId(exact.blueId());
            var parent = new ManagedDocumentSnapshot(id, exact.blueId(), body, false, false, true, 0, 0);
            var binding = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), id,
                    ScopeAddress.embedded("/child", 1), child.documentId(), childAuthored.blueId(), true, null);
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(parent, child), List.of(binding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(child), ClosureEvidenceFactory.acyclicComponent(parent)),
                    List.of(child.documentId(), parent.documentId()), List.of(
                            blue.language.processor.closure.ManagedReadPin.fromExactEvidence(child.documentId(),
                                    childAuthored.blueId(), childAuthored.document(), null)));
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of());
            var missing = assertInstanceOf(CoordinationCore.NeedEvidence.class,
                    f.core.evaluate(new CoordinationCore.WorkIntent(id, CoordinationCore.OperationKind.INITIALIZATION), evidence));
            assertEquals(List.of("canonical-initialization:" + child.documentId().value()), missing.keys());
            evidence = evidence.withSourceInitializations(List.of(OperationReceiptCodec.restoreSourceInitialization(
                    childBirth.receiptIdentity(), f.blobs::get, LIMITS)));
            var childAdmission = OriginalSourceInputTestSupport.admit(f.core, childBirth.after(),
                    new CanonicalSourceHistory.Request(child.documentId(), cut),
                    f.evidence(child, List.of(input("later source input", 15, "account")), 21), SameOriginAttachmentPolicy.empty(), f.blobs);
            var laterChild = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                    childAdmission.request(), childBirth.after(), childAdmission.evidence(), f.blobs::put, LIMITS));
            var aheadChild = f.restore(laterChild.after().successfulView().orElseThrow());
            var aheadSnapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(parent, aheadChild), List.of(binding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(aheadChild), ClosureEvidenceFactory.acyclicComponent(parent)),
                    List.of(child.documentId(), parent.documentId()), snapshot.readPins());
            var aheadEvidence = new CoordinationCore.EvaluationEvidence(aheadSnapshot, Set.of(), List.of(), Optional.empty(),
                    List.of(new CoordinationCore.ReadFence("physical-child", "later-host-head")), Map.of())
                    .withSourceInitializations(evidence.sourceInitializations());
            var needLogicalView = assertInstanceOf(CoordinationCore.NeedEvidence.class,
                    f.core.evaluate(new CoordinationCore.WorkIntent(id, CoordinationCore.OperationKind.INITIALIZATION), aheadEvidence));
            assertEquals(List.of("canonical-initialization-view:" + child.documentId().value() + ":" + child.blueId()), needLogicalView.keys());
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(new CanonicalSourceHistory.Request(id, cut),
                    history.start(id), evidence, f.blobs::put, LIMITS));
            var operation = assertInstanceOf(CoordinationCore.PreparedOperation.class, birth.evaluation());
            assertEquals(ProcessorStatus.SUCCESS, operation.result().status());
            assertEquals(List.of(id), operation.projections().stream().map(CoordinationCore.LineageProjection::lineage).toList());
            assertEquals(child.blueId(), operation.projections().get(0).result().document().getProperties().get("child").getBlueId());
        }
    }

    record Prepared(List<CanonicalSourceHistory.Step> steps, CanonicalSourceHistory.Boundary boundary) { }

    @Test
    void nestedCanonicalInitializationBorrowsColdProgramsBeforeEachParentsOwnLifecycle() {
        try (Fixture f = new Fixture()) {
            var history = new CanonicalSourceHistory(f.core);
            ExternalOrderKey cut = input("attach nested", 20, "account").order();
            ManagedDocumentSnapshot zAuthored = f.authored("""
                    name: Z
                    counter: 0
                    contracts:
                      lifecycle:
                        type: {blueId: %s}
                      initialize:
                        type: Coordination/Sequential Workflow
                        channel: lifecycle
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: replace, path: /counter, val: 1}]
                          - type: Coordination/Trigger Event
                            event: z-ready
                    """.formatted(blue.language.processor.registry.RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL), Map.of());
            var zBirth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(zAuthored.documentId(), cut), history.start(zAuthored.documentId()),
                    f.evidence(zAuthored, List.of(), 21), f.blobs::put, LIMITS));
            ManagedDocumentSnapshot z = f.restore(zBirth.after().initialView().orElseThrow());
            assertEquals(BigInteger.ONE, z.document().get("/counter"));
            var zCapability = OperationReceiptCodec.restoreSourceInitialization(zBirth.receiptIdentity(), f.blobs::get, LIMITS);
            ManagedDocumentSnapshot yAuthored = f.initializingObserver("Y", zAuthored.blueId(), "z-ready");
            var yBinding = f.binding(yAuthored, zAuthored);
            var zPin = ManagedReadPin.fromExactEvidence(zAuthored.documentId(), zAuthored.blueId(), zAuthored.document(), null);
            var ySnapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(yAuthored, z), List.of(yBinding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(z), ClosureEvidenceFactory.acyclicComponent(yAuthored)),
                    List.of(yAuthored.documentId(), z.documentId()), List.of(zPin));
            var yEvidence = new CoordinationCore.EvaluationEvidence(ySnapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of())
                    .withSourceInitializations(List.of(zCapability));
            var yBirth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(
                    new CanonicalSourceHistory.Request(yAuthored.documentId(), cut), history.start(yAuthored.documentId()),
                    yEvidence, f.blobs::put, LIMITS));
            ManagedDocumentSnapshot y = f.restore(yBirth.after().initialView().orElseThrow());
            assertEquals(BigInteger.ONE, y.document().get("/seen"));
            assertEquals(BigInteger.ONE, y.document().get("/seenAtInit"), "Child initialization observations precede Y lifecycle");
            var yOperation = assertInstanceOf(CoordinationCore.PreparedOperation.class, yBirth.evaluation());
            var hotY = yOperation.sourceProgram().orElseThrow();
            assertEquals(List.of(zCapability.program().invocationIdentity()), hotY.borrowedPrograms().stream()
                    .map(SourceObservationProgram::invocationIdentity).toList());
            var coldY = OperationReceiptCodec.restoreSourceInitialization(yBirth.receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(1, coldY.program().borrowedPrograms().size());
            var retainedZ = coldY.program().borrowedPrograms().get(0);
            assertTrue(retainedZ.ownedDocumentIds().contains(zAuthored.documentId()));
            var retainedZOrigin = retainedZ.sourcePredecessors().stream().filter(state -> state.documentId().equals(zAuthored.documentId())).findFirst().orElseThrow();
            assertEquals(zAuthored.blueId(), retainedZOrigin.blueId());
            assertEquals(zAuthored.blueId(), ExactValue.verified(retainedZOrigin.document()).blueId(),
                    "Borrowed Z authenticates its exact authored body without copying optional host pin inventory");

            ManagedDocumentSnapshot xAuthored = f.initializingObserver("X", yAuthored.blueId(), "Y-reacted");
            List<ManagedOccurrenceBinding> xBindings = new ArrayList<>(yOperation.ownedOccurrenceBindings());
            xBindings.add(f.binding(xAuthored, yAuthored));
            var xSnapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(xAuthored, y, z), xBindings,
                    List.of(ClosureEvidenceFactory.acyclicComponent(z), ClosureEvidenceFactory.acyclicComponent(y),
                            ClosureEvidenceFactory.acyclicComponent(xAuthored)),
                    List.of(xAuthored.documentId(), y.documentId(), z.documentId()), List.of(zPin,
                            ManagedReadPin.fromExactEvidence(yAuthored.documentId(), yAuthored.blueId(), yAuthored.document(), null)));
            var xEvidence = new CoordinationCore.EvaluationEvidence(xSnapshot, Set.of(), List.of(), Optional.empty(), List.of(), Map.of());
            var intent = new CoordinationCore.WorkIntent(xAuthored.documentId(), CoordinationCore.OperationKind.INITIALIZATION);
            var hot = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(intent,
                    xEvidence.withSourceInitializations(List.of(SourceInitialization.fromProgram(hotY)))));
            var cold = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(intent,
                    xEvidence.withSourceInitializations(List.of(coldY))));
            assertEquals(ProcessorStatus.SUCCESS, cold.result().status());
            assertEquals(List.of(xAuthored.documentId()), cold.projections().stream().map(CoordinationCore.LineageProjection::lineage).toList());
            Node x = cold.projections().get(0).result().document();
            assertEquals(BigInteger.ONE, x.get("/seen"));
            assertEquals(BigInteger.ONE, x.get("/seenAtInit"), "Borrowed Z causes Y reaction before X lifecycle even on cold replay");
            assertEquals(hot.operationId(), cold.operationId());
            assertEquals(hot.result().gasTraceIdentity(), cold.result().gasTraceIdentity());
            assertEquals(hot.projections().get(0).afterBlueId(), cold.projections().get(0).afterBlueId());
        }
    }
    record Observer(ManagedDocumentSnapshot before, ManagedOccurrenceBinding binding) { }

    @Test
    void sourcePrefixRetainsEveryFreshIndependentPublicationAndColdOrderedLinkage() {
        try (Fixture f = new Fixture()) {
            var setup = sourceWithFreshChild(f, false);
            var step = assertInstanceOf(CanonicalSourceHistory.Step.class, setup.history.prepareNext(setup.request,
                    setup.birth.after(), setup.input, f.blobs::put, LIMITS));
            var groups = assertInstanceOf(CoordinationCore.PreparedOperations.class, step.evaluation());
            assertEquals(2, groups.operations().size()); assertEquals(2, step.publications().size());
            assertTrue(groups.targetProgress().isEmpty());
            assertEquals(Set.of(setup.child.documentId()), step.publications().get(0).ownedLineages());
            assertEquals(Set.of(setup.parent.documentId()), step.publications().get(1).ownedLineages());
            assertEquals(step.publications().get(1).receiptIdentity(), step.receiptIdentity());
            assertEquals(List.of(step.publications().get(0).operationIdentity()), groups.operations().get(1).consumedSourceOperations());
            for (var publication : step.publications()) {
                var receipt = OperationReceiptCodec.decode(publication.receiptIdentity(), f.blobs::get, LIMITS);
                assertEquals(publication.operationIdentity(), receipt.operationId());
                assertEquals(publication.ownedLineages(), receipt.states().stream().map(OperationReceiptCodec.OwnedState::lineage).collect(java.util.stream.Collectors.toSet()));
                assertTrue(receipt.input().isPresent(), "Group publication retains the same authenticated Timeline Entry");
            }
            assertEquals(List.of(new CoordinationCore.ReadFence("parent", "p0")), groups.operations().get(1).fences());
            AtomicInteger reads = new AtomicInteger();
            var cold = setup.history.resume(setup.parent.documentId(), step.after().recordIdentity().orElseThrow(),
                    key -> { reads.incrementAndGet(); return f.blobs.get(key); }, LIMITS);
            assertEquals(1, reads.get()); assertEquals(step.publications(), cold.publications());
            assertEquals(CanonicalSourceHistory.RecordKind.SEMANTIC_OPERATION, cold.recordKind().orElseThrow());
            assertThrows(InvalidExecutionEvidenceException.class, () -> new CanonicalSourceHistory.Step(step.before(), step.after(),
                    step.evaluation(), step.receiptIdentity(), List.of(step.publications().get(1))));
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) OperationReceiptCodec.json(f.blobs.get(step.after().recordIdentity().orElseThrow()));
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("publications")).remove(0);
            String changed = retainPrefix(root, f.blobs);
            assertThrows(InvalidExecutionEvidenceException.class, () -> setup.history.resume(setup.parent.documentId(), changed, f.blobs::get, LIMITS));
        }
    }

    @Test
    void failedFreshProducerPublishesIndependentlyWhileTargetOnlyAdvancesMetadataAndKeepsSuccessfulReceipt() {
        try (Fixture f = new Fixture()) {
            var setup = sourceWithFreshChild(f, true);
            var step = assertInstanceOf(CanonicalSourceHistory.Step.class, setup.history.prepareNext(setup.request,
                    setup.birth.after(), setup.input, f.blobs::put, LIMITS));
            var groups = assertInstanceOf(CoordinationCore.PreparedOperations.class, step.evaluation());
            assertEquals(1, groups.operations().size()); assertEquals(Set.of(setup.child.documentId()), groups.operations().get(0).ownedLineages());
            assertEquals(ProcessorStatus.RUNTIME_FATAL, groups.operations().get(0).result().status());
            assertEquals(setup.parent.documentId(), groups.targetProgress().orElseThrow().lineage());
            assertEquals(CanonicalSourceHistory.RecordKind.METADATA_PROGRESS, step.after().recordKind().orElseThrow());
            assertEquals(setup.birth.after().successfulView(), step.after().successfulView());
            assertEquals(setup.birth.after().semanticPredecessor(), step.after().semanticPredecessor());
            assertEquals(setup.birth.after().operations(), step.after().operations());
            assertEquals(setup.birth.after().records() + 1, step.after().records());
            assertEquals(1, step.publications().size()); assertNotEquals(step.publications().get(0).receiptIdentity(), step.receiptIdentity());
            assertEquals(groups.operations().get(0).operationId(), OperationReceiptCodec.restoreSourceFailure(
                    step.publications().get(0).receiptIdentity(), f.blobs::get, LIMITS).invocationIdentity());
            var cold = setup.history.resume(setup.parent.documentId(), step.after().recordIdentity().orElseThrow(), f.blobs::get, LIMITS);
            assertEquals(step.after().successfulView(), cold.successfulView()); assertEquals(step.publications(), cold.publications());
            assertEquals(CanonicalSourceHistory.RecordKind.METADATA_PROGRESS, cold.recordKind().orElseThrow());
            var metadata = OperationReceiptCodec.json(f.blobs.get(step.receiptIdentity()));
            assertEquals(groups.operations().get(0).operationId(), metadata.get("consumedSourceOperations").get(0).textValue());
            assertFalse(metadata.has("operation")); assertFalse(metadata.has("gas"));
        }
    }

    private record FreshChild(CanonicalSourceHistory history, CanonicalSourceHistory.Request request, CanonicalSourceHistory.Step birth,
                              ManagedDocumentSnapshot child, ManagedDocumentSnapshot parent, CoordinationCore.EvaluationEvidence input) { }

    private static FreshChild sourceWithFreshChild(Fixture f, boolean fails) {
        var childAuthored = fails ? f.authored("""
                name: B fails without publishing its tentative patch
                counter: 0
                contracts:
                  ingress:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                    actor: {type: MyOS/Principal Actor, accountId: account}
                  update:
                    type: Coordination/Sequential Workflow
                    channel: ingress
                    steps:
                      - type: Coordination/Update Document
                        changeset: [{op: replace, path: /counter, val: 99}]
                      - type: Coordination/Update Document
                        changeset: [{op: remove, path: /counter/scalar-child}]
                """, Map.of()) : f.source();
        f.nodes.put(childAuthored.blueId(), childAuthored.document());
        var history = new CanonicalSourceHistory(f.core); var cut = input("attachment", 20, "account").order();
        var childBirth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(new CanonicalSourceHistory.Request(childAuthored.documentId(), cut),
                history.start(childAuthored.documentId()), f.evidence(childAuthored, List.of(), 21), f.blobs::put, LIMITS));
        var child = f.restore(childBirth.after().successfulView().orElseThrow());
        var parentAuthored = fails ? f.authored("""
                name: S with no business delivery after B failure
                contracts:
                  embedded:
                    type: {blueId: %s}
                    paths: [/child]
                """.formatted(blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED), Map.of("child", childAuthored.blueId()))
                : f.initializingObserver("S observer", childAuthored.blueId(), "agreement-updated");
        var binding = f.binding(parentAuthored, childAuthored);
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(child, parentAuthored), List.of(binding),
                List.of(ClosureEvidenceFactory.acyclicComponent(child), ClosureEvidenceFactory.acyclicComponent(parentAuthored)),
                List.of(child.documentId(), parentAuthored.documentId()), List.of(ManagedReadPin.fromExactEvidence(child.documentId(), childAuthored.blueId(), childAuthored.document(), null)));
        var birthEvidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(), List.of(),
                Map.of(child.documentId(), childBirth.after().semanticPredecessor().orElseThrow())).withSourceInitializations(List.of(
                OperationReceiptCodec.restoreSourceInitialization(childBirth.receiptIdentity(), f.blobs::get, LIMITS)));
        var request = new CanonicalSourceHistory.Request(parentAuthored.documentId(), cut);
        var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request, history.start(parentAuthored.documentId()), birthEvidence, f.blobs::put, LIMITS));
        var parent = f.restore(birth.after().successfulView().orElseThrow());
        var initialized = assertInstanceOf(CoordinationCore.PreparedOperation.class, birth.evaluation());
        var before = ClosureEvidenceFactory.affectedClosure(0, List.of(child, parent), initialized.ownedOccurrenceBindings(),
                List.of(ClosureEvidenceFactory.acyclicComponent(child), ClosureEvidenceFactory.acyclicComponent(parent)),
                List.of(child.documentId(), parent.documentId()), initialized.result().readPins());
        var childFence = new CoordinationCore.ReadFence("child", "b0"); var parentFence = new CoordinationCore.ReadFence("parent", "p0");
        var external = new CoordinationCore.EvaluationEvidence(before, Set.of("timeline"), List.of(new CoordinationCore.TimelinePrefix("timeline", 21,
                List.of(input("fresh B input", 15, "account")))), Optional.empty(), List.of(childFence, parentFence),
                Map.of(child.documentId(), childBirth.after().semanticPredecessor().orElseThrow(), parent.documentId(), birth.after().semanticPredecessor().orElseThrow()))
                .withOperationFences(Map.of(child.documentId(), List.of(childFence), parent.documentId(), List.of(parentFence)));
        var admitted = OriginalSourceInputTestSupport.admit(f.core, birth.after(), request, external, SameOriginAttachmentPolicy.empty(), f.blobs);
        return new FreshChild(history, admitted.request(), birth, child, parent, admitted.evidence());
    }

    private static String retainPrefix(com.fasterxml.jackson.databind.node.ObjectNode value, Map<String, byte[]> blobs) {
        byte[] bytes = OperationReceiptCodec.bytes(value); String key = FrozenNodeEvidenceCodec.digest(bytes); blobs.put(key, bytes); return key;
    }

    static CoordinationCore.TimelineInput input(String text, long micros, String account) {
        var entry = ExactValue.verified(new Node().type(new Node().blueId(blue.repo.coordination.TimelineEntry.blueId()))
                .properties("timeline", new Node().type(new Node().blueId(blue.repo.myos.MyOSTimeline.blueId())).properties("timelineId", new Node().value("timeline")))
                .properties("timestamp", new Node().value(BigInteger.valueOf(micros))).properties("message", new Node().value(text))
                .properties("actor", new Node().type(new Node().blueId(blue.repo.myos.PrincipalActor.blueId())).properties("accountId", new Node().value(account)))
                .properties("source", new Node().value("test")));
        return new CoordinationCore.TimelineInput("timeline", micros, entry, entry, List.of());
    }

    static final class Fixture implements AutoCloseable {
        final Map<String, Node> nodes = new HashMap<>();
        final Set<String> forbiddenExactReads = new HashSet<>();
        final List<String> exactReads = new ArrayList<>();
        final Map<String, byte[]> blobs = new HashMap<>();
        final CoordinationTestRuntime runtime;
        final DocumentProcessor processor;
        final CoordinationCore core;
        Fixture() {
            runtime = CoordinationTestRuntime.create(blue.repo.BlueRepository.current(),
                    key -> {
                        exactReads.add(key);
                        if (forbiddenExactReads.contains(key)) throw new AssertionError("Application body was reopened: " + key);
                        return nodes.containsKey(key) ? List.of(nodes.get(key).clone()) : List.of();
                    }, blue.language.api.BlueCachePolicy.disabled());
            var options = CoordinationProcessorOptions.builder().language(runtime.language()).build();
            var registry = CoordinationProcessors.configure(ContractProcessorRegistryBuilder.create().registerDefaults(), options).build();
            processor = DocumentProcessor.builder().runtimeAccess(runtime.contracts().runtimeAccess()).scanContractTypes("blue.repo").runtimeRegistry(registry)
                    .runtimeRegistryIdentity(registry.generationIdentity()).build();
            var environment = ClosureEvidenceFactory.environment(processor, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "content-lineage", "exact-binding", "test-exact-provider", "micros-entry-text", "local-portable-limits", GasSchedule.contracts10().portableLimits());
            core = new CoordinationCore(processor, environment, ClosureEvidenceFactory.executionPolicy(100_000, Map.of(), "fixed-source-policy"));
        }
        ManagedDocumentSnapshot authored(String yaml, Map<String, String> references) {
            Node body = runtime.yamlToNode(yaml);
            references.forEach((path, blueId) -> body.properties(path, new Node().blueId(blueId)));
            Node resolved = runtime.resolveToSnapshotPreservingPaths(body, references.keySet().stream().map(path -> "/" + path).toList()).canonicalRoot();
            var exact = ExactValue.verified(resolved); nodes.put(exact.blueId(), resolved);
            return new ManagedDocumentSnapshot(new DocumentId(exact.blueId()), exact.blueId(), resolved, false, false, true, 0, 0);
        }
        ManagedOccurrenceBinding binding(ManagedDocumentSnapshot parent, ManagedDocumentSnapshot authoredChild) {
            return ManagedOccurrenceBinding.derived(core.environment().managedBindingPolicyIdentity(), parent.documentId(),
                    ScopeAddress.embedded("/child", 1), authoredChild.documentId(), authoredChild.blueId(), true, null);
        }
        ManagedDocumentSnapshot initializingObserver(String name, String child, String observedEvent) {
            return authored("""
                    name: %s
                    seen: 0
                    seenAtInit: 0
                    contracts:
                      embedded:
                        type: {blueId: %s}
                        paths: [/child]
                      fromChild:
                        type: {blueId: %s}
                        sourcePath: /child
                      react:
                        type: Coordination/Sequential Workflow
                        channel: fromChild
                        event: %s
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: replace
                                  path: /seen
                                  val: {$add: [{$document: /seen}, 1]}
                          - type: Coordination/Trigger Event
                            event: %s-reacted
                      lifecycle:
                        type: {blueId: %s}
                      initialize:
                        type: Coordination/Sequential Workflow
                        channel: lifecycle
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange:
                                  op: replace
                                  path: /seenAtInit
                                  val: {$document: /seen}
                    """.formatted(name, blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED,
                    blue.language.processor.registry.RuntimeBlueIds.EMBEDDED_NODE_CHANNEL, observedEvent, name,
                    blue.language.processor.registry.RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL), Map.of("child", child));
        }
        ManagedDocumentSnapshot source() {
            Node node = runtime.resolveToSnapshot(runtime.yamlToNode("""
                    name: Canonical agreement
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      update:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: replace, path: /counter, val: 5}]
                          - type: Coordination/Trigger Event
                            event: agreement-updated
                    """)).canonicalRoot();
            var exact = ExactValue.verified(node);
            return new ManagedDocumentSnapshot(new DocumentId(exact.blueId()), exact.blueId(), node, false, false, true, 0, 0);
        }
        CoordinationCore.EvaluationEvidence evidence(ManagedDocumentSnapshot state, List<CoordinationCore.TimelineInput> inputs, long completeBefore) {
            var snapshot = ClosureEvidenceFactory.affectedClosure(0L, List.of(state), List.of(),
                    List.of(ClosureEvidenceFactory.acyclicComponent(state)), List.of(state.documentId()));
            var fences = List.of(new CoordinationCore.ReadFence("source", UUID.randomUUID().toString()));
            return new CoordinationCore.EvaluationEvidence(snapshot, Set.of("timeline"),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", completeBefore, inputs)), Optional.empty(),
                    fences, Map.of()).withOperationFences(Map.of(state.documentId(), fences));
        }
        Prepared prepare(CanonicalSourceHistory history, ManagedDocumentSnapshot authored, ExternalOrderKey cut,
                         List<CoordinationCore.TimelineInput> inputs, long completeBefore) {
            var cursor = history.start(authored.documentId()); var state = authored;
            List<CanonicalSourceHistory.Step> steps = new ArrayList<>();
            for (int iteration = 0; iteration < 10; iteration++) {
                var admitted = OriginalSourceInputTestSupport.admit(core, cursor,
                        new CanonicalSourceHistory.Request(authored.documentId(), cut), evidence(state, inputs, completeBefore),
                        SameOriginAttachmentPolicy.empty(), blobs);
                var result = history.prepareNext(admitted.request(), cursor, admitted.evidence(), blobs::put, LIMITS);
                if (result instanceof CanonicalSourceHistory.Complete complete) return new Prepared(List.copyOf(steps), complete.boundary());
                var step = assertInstanceOf(CanonicalSourceHistory.Step.class, result);
                steps.add(step); cursor = step.after(); state = restore(cursor.successfulView().orElseThrow());
            }
            throw new AssertionError("Source preparation did not finish its finite prefix");
        }
        ManagedDocumentSnapshot restore(CanonicalSourceHistory.View view) {
            var state = OperationReceiptCodec.restoreState(view.receiptIdentity(), view.source(), true, 0, blobs::get, LIMITS);
            nodes.put(state.blueId(), state.document()); return state;
        }
        Observer observer(String name, CanonicalSourceHistory.View installed) {
            ManagedDocumentSnapshot source = restore(installed);
            Node body = runtime.yamlToNode("""
                    name: %s
                    seen: 0
                    contracts:
                      embedded:
                        type: {blueId: %s}
                        paths: [/source]
                      fromSource:
                        type: {blueId: %s}
                        sourcePath: /source
                      observe:
                        type: Coordination/Sequential Workflow
                        channel: fromSource
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: replace, path: /seen, val: 5}]
                    """.formatted(name, blue.language.processor.registry.RuntimeBlueIds.PROCESS_EMBEDDED,
                    blue.language.processor.registry.RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                    .properties("source", new Node().blueId(source.blueId()));
            body = runtime.resolveToSnapshotPreservingPaths(body, List.of("/source")).canonicalRoot();
            var exact = ExactValue.verified(body); var id = new DocumentId(exact.blueId());
            var initial = new ManagedDocumentSnapshot(id, exact.blueId(), body, false, false, true, 0, 0);
            var binding = ManagedOccurrenceBinding.derived(core.environment().managedBindingPolicyIdentity(), id,
                    ScopeAddress.embedded("/source", 1L), source.documentId(), source.blueId(), true, null);
            var operation = assertInstanceOf(CoordinationCore.PreparedOperation.class, core.evaluate(
                    new CoordinationCore.WorkIntent(id, CoordinationCore.OperationKind.INITIALIZATION),
                    observerEvidence(new Observer(initial, binding), source, List.of(), List.of(), Optional.empty())));
            assertEquals(ProcessorStatus.SUCCESS, operation.result().status());
            var encoded = OperationReceiptCodec.encode(operation, blobs::put, LIMITS);
            return new Observer(OperationReceiptCodec.restoreState(encoded.receiptIdentity(), id, true, 0, blobs::get, LIMITS), binding);
        }
        CoordinationCore.EvaluationEvidence observerEvidence(Observer observer, ManagedDocumentSnapshot source,
                List<CoordinationCore.TimelineInput> inputs, List<SourceObservationProgram> programs, Optional<ExternalOrderKey> handled) {
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(observer.before, source), List.of(observer.binding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(source), ClosureEvidenceFactory.acyclicComponent(observer.before)),
                    List.of(source.documentId(), observer.before.documentId()));
            Map<DocumentId, String> originalBases = new TreeMap<>();
            // These fixtures produce their source operations under this fixed original Core configuration.
            for (var program : programs) for (DocumentId member : program.ownedDocumentIds())
                originalBases.put(member, SourceExecutionBasis.identity(member, core.environment(), core.executionPolicy()));
            return new CoordinationCore.EvaluationEvidence(snapshot, Set.of("timeline"),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 31, inputs)), handled, List.of(), Map.of(), programs)
                    .withExpectedSourceBases(originalBases);
        }
        @Override public void close() { processor.close(); runtime.close(); }
    }
    private static CoordinationCore.PreparedGroupOperation singletonGroup(CoordinationCore.EvaluationResult result) {
        var groups = assertInstanceOf(CoordinationCore.PreparedOperations.class, result);
        assertEquals(1, groups.operations().size()); assertTrue(groups.targetProgress().isEmpty()); return groups.operations().get(0);
    }
}
