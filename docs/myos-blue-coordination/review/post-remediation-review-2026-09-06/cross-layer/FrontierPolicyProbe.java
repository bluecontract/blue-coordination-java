package blue.coordination.external;

import blue.language.processor.closure.*;
import java.util.*;

/** Review diagnostic only: independent source policy at an actual FROM_FRONTIER creator site. */
public final class FrontierPolicyProbe {
    public static void main(String[] args) {
        run(false);
        run(true);
    }

    private static void run(boolean independentPolicy) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var sourceCore = independentPolicy ? new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(5_000, Map.of(), "independent-source-5000")) : f.core;
            var sourceAuthored = f.source(); f.nodes.put(sourceAuthored.blueId(), sourceAuthored.document());
            var frontierOrder = CanonicalSourceHistoryTest.input("frontier", 5, "other").order();
            var history = new CanonicalSourceHistory(sourceCore);
            var request = new CanonicalSourceHistory.Request(sourceAuthored.documentId(), frontierOrder);
            var birth = (CanonicalSourceHistory.Step) history.prepareNext(request, history.start(sourceAuthored.documentId()),
                    f.evidence(sourceAuthored, List.of(), 100), f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var source = f.restore(birth.after().successfulView().orElseThrow());
            var boundary = ((CanonicalSourceHistory.Complete) history.prepareNext(request, birth.after(),
                    f.evidence(source, List.of(), 100), f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults())).boundary();
            var authored = f.authored("""
                    name: Parent creates a frontier placement
                    counter: -1
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: creator}
                      create:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Update Document
                            changeset: [{op: add, path: /contracts/embedded, val: {type: Process Embedded, paths: [/child]}}]
                    """, Map.of("child", source.blueId()));
            var parentBirth = (CoordinationCore.PreparedOperation) f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 100));
            var parentReceipt = OperationReceiptCodec.encode(parentBirth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var parent = OperationReceiptCodec.restoreState(parentReceipt.receiptIdentity(), authored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var binding = ManagedOccurrenceBinding.derived(f.core.environment().managedBindingPolicyIdentity(), parent.documentId(),
                    ScopeAddress.embedded("/child", 1), source.documentId(), source.blueId(), false, null);
            var choice = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER,
                    parent.documentId(), binding.occurrenceIdentity(), source.documentId(), source.blueId(), frontierOrder);
            var frontier = SourceFrontierSelection.fromBoundary(choice, boundary, birth.receiptIdentity(), f.blobs::get,
                    FrozenNodeEvidenceCodec.Limits.defaults());
            var documents = List.of(parent, source); var rows = List.of(binding);
            Map<DocumentId, ManagedDocumentSnapshot> byId = new TreeMap<>(); documents.forEach(doc -> byId.put(doc.documentId(), doc));
            var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), rows)).stream()
                    .map(ids -> ClosureEvidenceFactory.acyclicComponent(byId.get(ids.get(0)))).toList();
            var entry = CanonicalSourceHistoryTest.input("attach", 20, "creator");
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, documents, rows, components, List.copyOf(byId.keySet()));
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of("timeline"),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 100, List.of(entry))), Optional.empty(), List.of(),
                    Map.of(parent.documentId(), parentBirth.operationId(), source.documentId(), birth.after().semanticPredecessor().orElseThrow()))
                    .withSourceFrontiers(List.of(frontier)).withExpectedSourceBases(Map.of(source.documentId(), boundary.cursor().basisIdentity()));
            System.out.println("independentPolicy=" + independentPolicy + ", authenticatedSourceLimit=" + sourceCore.executionPolicy().sharedLimit());
            try {
                var result = f.core.evaluate(new CoordinationCore.WorkIntent(parent.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                        evidence, new SameOriginAttachmentPolicy(List.of(choice)));
                System.out.println("parent=" + (result instanceof CoordinationCore.PreparedOperations operations
                        ? operations.operations().get(0).result().status() + ", gas=" + operations.operations().get(0).result().totalGas() : result));
            } catch (RuntimeException failure) {
                System.out.println("parent=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                for (var frame : failure.getStackTrace()) if (frame.getClassName().startsWith("blue.")) System.out.println("  at " + frame);
            }
        }
    }
}
