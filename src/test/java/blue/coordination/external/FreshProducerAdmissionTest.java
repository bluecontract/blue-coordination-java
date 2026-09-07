package blue.coordination.external;

import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Authenticated original input context, not the observer's reconstruction defaults, owns each seed. */
class FreshProducerAdmissionTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void freshIndependentDifferentPolicyNeedsItsOwnResultAndAuthenticFailureRemainsMetadata() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = f.authored("""
                    name: source with expensive external work
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
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$size: {$split: {text: {$event: /message}, separator: ','}}}}
                              - $return: true
                          - type: Coordination/Trigger Event
                            event: agreement-updated
                    """, Map.of());
            var entry = CanonicalSourceHistoryTest.input("item,".repeat(3000), 15, "account");
            var producer = new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(5000, Map.of(), "independent-producer-5000"));
            var history = new CanonicalSourceHistory(producer);
            var request = new CanonicalSourceHistory.Request(authored.documentId(), entry.order());
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request, history.start(authored.documentId()),
                    f.evidence(authored, List.of(entry), 20), f.blobs::put, LIMITS));
            assertEquals(ProcessorStatus.SUCCESS, assertInstanceOf(CoordinationCore.PreparedOperation.class, birth.evaluation()).result().status());
            var before = f.restore(birth.after().successfulView().orElseThrow());
            var observer = f.observer("independent policy observer", birth.after().successfulView().orElseThrow());
            var base = f.observerEvidence(observer, before, List.of(entry), List.of(), Optional.empty());
            var previous = Map.of(before.documentId(), birth.after().semanticPredecessor().orElseThrow());
            var ownBasis = SourceExecutionBasis.identity(before.documentId(), producer.environment(), producer.executionPolicy());
            var uncached = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                    base.handledThrough(), List.of(), previous).withExpectedSourceBases(Map.of(before.documentId(), ownBasis));
            var intent = new CoordinationCore.WorkIntent(observer.before().documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            String expectedNeed = "canonical-source-operation:" + before.documentId().value() + ":" + entry.entry().blueId()
                    + ":" + ownBasis + ":" + previous.get(before.documentId());
            assertEquals(List.of(expectedNeed), assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent, uncached)).keys());
            var admission = admission(producer, before.documentId(), entry, previous, SameOriginAttachmentPolicy.empty(), f.blobs);
            var withOriginal = uncached.withSourceInputAdmissions(List.of(admission))
                    .withOriginalSourceInputRoots(Map.of(before.documentId(), admission.identity()));
            assertEquals(List.of(expectedNeed), assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent, withOriginal)).keys());

            var producerInput = new CoordinationCore.EvaluationEvidence(f.evidence(before, List.of(entry), 20).snapshot(),
                    base.relevantTimelines(), base.prefixes(), Optional.empty(), List.of(), previous);
            var failed = assertInstanceOf(CoordinationCore.PreparedOperations.class, producer.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), producerInput)).operations().get(0);
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.result().status());
            assertEquals(5000, failed.result().totalGas());
            assertEquals(producer.executionPolicy().identity(), failed.invocation().executionPolicy().identity());
            var encoded = OperationReceiptCodec.encode(failed, f.blobs::put, LIMITS);
            var failure = OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(List.of(), failure.originalAttachmentSelections().orElseThrow().get(before.documentId()));
            var cached = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(), base.handledThrough(),
                    List.of(), previous, List.of(), Map.of(), List.of(failure)).withExpectedSourceBases(Map.of(before.documentId(), ownBasis));
            var metadata = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, cached));
            assertEquals(List.of(failed.operationId()), metadata.consumedSourceOperations());
            assertEquals(entry.order(), metadata.input().order());
            assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, cached.withSourceInputAdmissions(List.of(admission))
                    .withOriginalSourceInputRoots(Map.of(before.documentId(), admission.identity()))));
        }
    }

    @Test
    void originalDescendantFrontierSurvivesColdNeedsAndFullCutWithoutContaminatingSeedIdentity() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var y = f.source(); var e10 = CanonicalSourceHistoryTest.input("advance Y", 10, "account");
            var f5 = CanonicalSourceHistoryTest.input("frontier", 5, "other");
            var e30 = CanonicalSourceHistoryTest.input("create Y", 30, "creator");
            var yh = new CanonicalSourceHistory(f.core);
            var yfull = f.prepare(yh, y, e30.order(), List.of(e10, e30), 100);
            var yfront = f.prepare(yh, y, f5.order(), List.of(e10), 100);
            var yhead = f.restore(yfull.boundary().cursor().successfulView().orElseThrow());
            var xa = f.authored("""
                    name: X with its own original frontier
                    counter: -1
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: creator}
                      create:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        order: 0
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: add, path: /contracts/embedded, val: {type: Process Embedded, paths: [/child]}}]
                      read:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        order: 1
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$document: /child/counter}}
                              - $return: true
                          - type: Coordination/Trigger Event
                            event: agreement-updated
                    """, Map.of("child", yhead.blueId(), "spare", yhead.blueId()));
            var history = new CanonicalSourceHistory(f.core);
            var xr = new CanonicalSourceHistory.Request(xa.documentId(), e30.order());
            var xb = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(xr, history.start(xa.documentId()),
                    evidence(List.of(xa), List.of(), List.of(), Map.of()), f.blobs::put, LIMITS));
            var x = f.restore(xb.after().successfulView().orElseThrow());
            var xe10 = evidence(List.of(x), List.of(), List.of(e10, e30), Map.of(x.documentId(), xb.after().semanticPredecessor().orElseThrow()));
            var xmInput = OriginalSourceInputTestSupport.admit(f.core, xb.after(), xr, xe10, SameOriginAttachmentPolicy.empty(), f.blobs);
            var xm = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(xmInput.request(), xb.after(), xmInput.evidence(), f.blobs::put, LIMITS));
            assertInstanceOf(CoordinationCore.MetadataProgress.class, xm.evaluation());
            var xy = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), x.documentId(), ScopeAddress.embedded("/child", 1),
                    y.documentId(), yhead.blueId(), false, null);
            var choice = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER, x.documentId(), xy.occurrenceIdentity(),
                    y.documentId(), yhead.blueId(), f5.order());
            var spare = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), x.documentId(), ScopeAddress.embedded("/spare", 1),
                    y.documentId(), yhead.blueId(), false, null);
            var unusedChoice = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, x.documentId(), spare.occurrenceIdentity(),
                    y.documentId(), yhead.blueId());
            var frontier = SourceFrontierSelection.fromBoundary(choice, yfront.boundary(), yfront.steps().get(0).receiptIdentity(), f.blobs::get, LIMITS);
            var xe = evidence(List.of(x, yhead), List.of(xy, spare), List.of(e10, e30), Map.of(x.documentId(), xm.after().semanticPredecessor().orElseThrow()));
            var admittedX = OriginalSourceInputTestSupport.admit(f.core, xm.after(), xr, xe.withSourceFrontiers(List.of(frontier))
                            .withExpectedSourceBases(f.expectedBases(y.documentId())),
                    new SameOriginAttachmentPolicy(List.of(choice, unusedChoice)), f.blobs);
            var authentic = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(admittedX.request(), xm.after(), admittedX.evidence(), f.blobs::put, LIMITS));
            var authenticX = group(authentic.evaluation(), x.documentId());
            assertEquals(BigInteger.ZERO, authenticX.projections().get(0).result().document().get("/counter"));

            var aa = f.initializingObserver("A reconstructs X", x.blueId(), "agreement-updated");
            var ar = new CanonicalSourceHistory.Request(aa.documentId(), e30.order());
            var ab = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(ar, history.start(aa.documentId()),
                    evidence(List.of(aa, x), List.of(f.binding(aa, x)), List.of(), Map.of(x.documentId(), xb.after().semanticPredecessor().orElseThrow())), f.blobs::put, LIMITS));
            var a = f.restore(ab.after().successfulView().orElseThrow());
            var rows = new ArrayList<>(assertInstanceOf(CoordinationCore.PreparedOperation.class, ab.evaluation()).ownedOccurrenceBindings()); rows.add(xy); rows.add(spare);
            var ae = evidence(List.of(a, x, yhead), rows, List.of(e10, e30), Map.of(a.documentId(), ab.after().semanticPredecessor().orElseThrow(),
                    x.documentId(), xb.after().semanticPredecessor().orElseThrow()));
            var amInput = OriginalSourceInputTestSupport.admit(f.core, ab.after(), ar, ae, SameOriginAttachmentPolicy.empty(), f.blobs);
            var am = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(amInput.request(), ab.after(), amInput.evidence(), f.blobs::put, LIMITS));
            assertInstanceOf(CoordinationCore.MetadataProgress.class, am.evaluation());
            var admittedA = OriginalSourceInputTestSupport.admit(f.core, am.after(), ar, ae, SameOriginAttachmentPolicy.empty(), f.blobs);
            var coldCursor = history.resume(a.documentId(), am.after().recordIdentity().orElseThrow(), f.blobs::get, LIMITS);
            String originalXRoot = admittedX.admission().identity();
            var offeredOnly = admittedA.evidence().withSourceInputAdmissions(List.of(admittedA.admission(), admittedX.admission()));
            var rootNeed = assertInstanceOf(CanonicalSourceHistory.Await.class, history.prepareNext(admittedA.request(), coldCursor,
                    offeredOnly, f.blobs::put, LIMITS));
            assertEquals(List.of("source-input-admission:" + x.documentId().value() + ":" + e30.entry().blueId()), rootNeed.keys());
            String extraNamedRoot = SourceInputAdmission.encodeCandidate(e30, Map.of(a.documentId(), coldCursor.basisIdentity(),
                    x.documentId(), xb.after().basisIdentity()), ae.precedingOperations(), SameOriginAttachmentPolicy.empty(), f.blobs::put, LIMITS);
            var extraNamed = SourceInputAdmission.restore(extraNamedRoot, f.blobs::get, LIMITS);
            var extraNeed = assertInstanceOf(CanonicalSourceHistory.Await.class, history.prepareNext(new CanonicalSourceHistory.Request(a.documentId(),
                    e30.order(), Map.of(e30.entry().blueId(), extraNamedRoot)), coldCursor,
                    admittedA.evidence().withSourceInputAdmissions(List.of(extraNamed)), f.blobs::put, LIMITS));
            assertEquals(rootNeed.keys(), extraNeed.keys(), "Mentioning a downstream producer does not independently authenticate its original input");
            var originalRoots = Map.of(x.documentId(), originalXRoot);
            var rooted = admittedA.evidence().withOriginalSourceInputRoots(originalRoots);
            assertEquals(List.of("source-input-admission-record:" + originalXRoot), assertInstanceOf(CanonicalSourceHistory.Await.class,
                    history.prepareNext(admittedA.request(), coldCursor, rooted, f.blobs::put, LIMITS)).keys());
            var complete = rooted.withSourceInputAdmissions(List.of(admittedA.admission(), SourceInputAdmission.restore(originalXRoot, f.blobs::get, LIMITS)))
                    .withSourceFrontiers(List.of(frontier)).withSourceInitializations(List.of()).withExpectedSourceBases(f.expectedBases(y.documentId()))
                    .withOperationFences(Map.of());
            assertEquals(originalRoots, complete.originalSourceInputRoots(), "Every evidence copy retains original-root authority");
            var fresh = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(admittedA.request(), coldCursor, complete, f.blobs::put, LIMITS));
            var freshX = group(fresh.evaluation(), x.documentId());
            assertEquals(authenticX.operationId(), freshX.operationId(), "Another producer's input choices never enter X's seed");
            assertEquals(authenticX.result().gasTraceIdentity(), freshX.result().gasTraceIdentity());
            assertEquals(authenticX.result().totalGas(), freshX.result().totalGas());
            assertEquals(BigInteger.ZERO, freshX.projections().get(0).result().document().get("/counter"));
            var coldX = OperationReceiptCodec.restoreSourceProgram(authentic.receiptIdentity(), f.blobs::get, LIMITS);
            var cached = new CoordinationCore.EvaluationEvidence(ae.snapshot(), ae.relevantTimelines(), ae.prefixes(), ae.handledThrough(),
                    List.of(), ae.precedingOperations(), List.of(coldX)).withSourceInputAdmissions(List.of(admittedA.admission()))
                    .withExpectedSourceBases(Map.of(x.documentId(), xb.after().basisIdentity(), y.documentId(), yfront.boundary().cursor().basisIdentity()));
            var warm = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(admittedA.request(), coldCursor, cached, f.blobs::put, LIMITS));
            assertEquals(group(fresh.evaluation(), a.documentId()).operationId(), group(warm.evaluation(), a.documentId()).operationId());
            assertEquals(List.of(authenticX.operationId()), group(warm.evaluation(), a.documentId()).consumedSourceOperations());
            var rootedCached = cached.withOriginalSourceInputRoots(originalRoots)
                    .withSourceInputAdmissions(List.of(admittedA.admission(), admittedX.admission()));
            assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(admittedA.request(), coldCursor, rootedCached, f.blobs::put, LIMITS));
            assertEquals(authenticX.sourceProgram().orElseThrow().originalAttachmentSelections(), coldX.originalAttachmentSelections());
            assertEquals(2, coldX.originalAttachmentSelections().orElseThrow().get(x.documentId()).size(),
                    "The original unused choice is retained as well as the actually accepted frontier");
            String sourceManifest = OperationReceiptCodec.decode(authentic.receiptIdentity(), f.blobs::get, LIMITS).sourceProgramIdentity().orElseThrow();
            var changed = (com.fasterxml.jackson.databind.node.ObjectNode) OperationReceiptCodec.json(f.blobs.get(sourceManifest));
            ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("originalAttachmentSelections")).remove(x.documentId().value());
            byte[] changedBytes = OperationReceiptCodec.bytes(changed);
            assertThrows(InvalidExecutionEvidenceException.class, () -> SourceObservationProgramCodec.decode(sourceManifest,
                    key -> key.equals(sourceManifest) ? changedBytes : f.blobs.get(key), LIMITS));
            String malformed = FrozenNodeEvidenceCodec.digest(changedBytes); f.blobs.put(malformed, changedBytes);
            assertThrows(IllegalArgumentException.class, () -> SourceObservationProgramCodec.decode(malformed, f.blobs::get, LIMITS));
            var directX = new CoordinationCore.EvaluationEvidence(xe.snapshot(), xe.relevantTimelines(), xe.prefixes(), Optional.of(e10.order()),
                    List.of(), xe.precedingOperations()).withSourceFrontiers(List.of(frontier)).withExpectedSourceBases(f.expectedBases(y.documentId()));
            var alternateX = group(f.core.evaluate(new CoordinationCore.WorkIntent(x.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    directX, new SameOriginAttachmentPolicy(List.of(choice))), x.documentId());
            assertEquals(BigInteger.ZERO, alternateX.projections().get(0).result().document().get("/counter"));
            assertNotEquals(authenticX.operationId(), alternateX.operationId(), "Unused original choices still bind the seed");
            var alternateReceipt = OperationReceiptCodec.encode(alternateX, f.blobs::put, LIMITS);
            var alternateProgram = OperationReceiptCodec.restoreSourceProgram(alternateReceipt.receiptIdentity(), f.blobs::get, LIMITS);
            var contradiction = new CoordinationCore.EvaluationEvidence(ae.snapshot(), ae.relevantTimelines(), ae.prefixes(), ae.handledThrough(),
                    List.of(), ae.precedingOperations(), List.of(alternateProgram)).withOriginalSourceInputRoots(originalRoots)
                    .withSourceInputAdmissions(List.of(admittedA.admission(), admittedX.admission()))
                    .withExpectedSourceBases(f.expectedBases(y.documentId()));
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(admittedA.request(), coldCursor, contradiction, f.blobs::put, LIMITS));

            var wrongEntry = admission(f.core, x.documentId(), e10, Map.of(x.documentId(), xb.after().semanticPredecessor().orElseThrow()),
                    SameOriginAttachmentPolicy.empty(), f.blobs);
            var wrong = offeredOnly.withSourceInputAdmissions(List.of(admittedA.admission(), wrongEntry))
                    .withOriginalSourceInputRoots(Map.of(x.documentId(), wrongEntry.identity()));
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(admittedA.request(), coldCursor, wrong, f.blobs::put, LIMITS));
            var wrongPrevious = admission(f.core, x.documentId(), e30, Map.of(x.documentId(), "sha256:" + "0".repeat(64)),
                    SameOriginAttachmentPolicy.empty(), f.blobs);
            var mismatchedPrevious = admittedA.evidence().withSourceInputAdmissions(List.of(admittedA.admission(), wrongPrevious))
                    .withOriginalSourceInputRoots(Map.of(x.documentId(), wrongPrevious.identity()));
            assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(admittedA.request(), coldCursor, mismatchedPrevious, f.blobs::put, LIMITS));
        }
    }

    static SourceInputAdmission admission(CoordinationCore core, DocumentId source, CoordinationCore.TimelineInput input,
            Map<DocumentId, String> previous, SameOriginAttachmentPolicy choices, Map<String, byte[]> blobs) {
        String root = SourceInputAdmission.encodeCandidate(input, Map.of(source, SourceExecutionBasis.identity(source, core.environment(), core.executionPolicy())),
                previous, choices, blobs::put, LIMITS);
        return SourceInputAdmission.restore(root, blobs::get, LIMITS);
    }

    private static CoordinationCore.PreparedGroupOperation group(CoordinationCore.EvaluationResult result, DocumentId owner) {
        return assertInstanceOf(CoordinationCore.PreparedOperations.class, result).operations().stream()
                .filter(group -> group.ownedLineages().contains(owner)).findFirst().orElseThrow();
    }

    private static CoordinationCore.EvaluationEvidence evidence(List<ManagedDocumentSnapshot> docs, List<ManagedOccurrenceBinding> rows,
            List<CoordinationCore.TimelineInput> entries, Map<DocumentId, String> previous) {
        Map<DocumentId, ManagedDocumentSnapshot> byId = new TreeMap<>(); docs.forEach(doc -> byId.put(doc.documentId(), doc));
        var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), rows)).stream()
                .map(ids -> ClosureEvidenceFactory.acyclicComponent(byId.get(ids.get(0)))).toList();
        return new CoordinationCore.EvaluationEvidence(ClosureEvidenceFactory.affectedClosure(0, docs, rows, components,
                docs.stream().map(ManagedDocumentSnapshot::documentId).toList()), entries.isEmpty() ? Set.of() : Set.of("timeline"),
                entries.isEmpty() ? List.of() : List.of(new CoordinationCore.TimelinePrefix("timeline", 100, entries)), Optional.empty(), List.of(), previous);
    }
}
