package blue.coordination.external;

import blue.language.processor.closure.*;
import java.util.*;

/** Review diagnostic only. Uses the frozen actual Core and existing compiled test fixture. */
public final class InitializationPolicyProbe {
    public static void main(String[] args) {
        run(false);
        run(true);
    }

    private static void run(boolean independentPolicy) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var sourceCore = independentPolicy
                    ? new CoordinationCore(f.processor, f.core.environment(),
                        ClosureEvidenceFactory.executionPolicy(5_000, Map.of(), "independent-source-5000"))
                    : f.core;
            var authored = f.source();
            f.nodes.put(authored.blueId(), authored.document());
            var birth = (CoordinationCore.PreparedOperation) sourceCore.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 21));
            var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var source = birth.projections().get(0).result();
            var initialized = new ManagedDocumentSnapshot(authored.documentId(), source.afterBlueId(),
                    source.document(), true, false, true, 0, 0);
            var parent = f.initializingObserver("Independent parent", authored.blueId(), "agreement-updated");
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(parent, initialized),
                    List.of(f.binding(parent, authored)),
                    List.of(ClosureEvidenceFactory.acyclicComponent(initialized), ClosureEvidenceFactory.acyclicComponent(parent)),
                    List.of(parent.documentId(), initialized.documentId()),
                    List.of(ManagedReadPin.fromExactEvidence(authored.documentId(), authored.blueId(), authored.document(), null)));
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(),
                    List.of(), Map.of(authored.documentId(), birth.operationId()))
                    .withExpectedSourceBases(Map.of(authored.documentId(), SourceExecutionBasis.identity(
                            authored.documentId(), sourceCore.environment(), sourceCore.executionPolicy())));
            var intent = new CoordinationCore.WorkIntent(parent.documentId(), CoordinationCore.OperationKind.INITIALIZATION);
            System.out.println("independentPolicy=" + independentPolicy + ", sourceStatus=" + birth.result().status()
                    + ", sourceGas=" + birth.result().totalGas() + ", sourceLimit=" + sourceCore.executionPolicy().sharedLimit());
            for (boolean cold : List.of(false, true)) {
                var capability = cold
                        ? OperationReceiptCodec.restoreSourceInitialization(receipt.receiptIdentity(), f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults())
                        : SourceInitialization.fromProgram(birth.sourceProgram().orElseThrow());
                try {
                    var result = f.core.evaluate(intent, evidence.withSourceInitializations(List.of(capability)));
                    System.out.println("cold=" + cold + ", parent=" + (result instanceof CoordinationCore.PreparedOperation op
                            ? op.result().status() + ", gas=" + op.result().totalGas() : result));
                } catch (RuntimeException failure) {
                    System.out.println("cold=" + cold + ", parent=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    for (var frame : failure.getStackTrace()) {
                        if (frame.getClassName().startsWith("blue.")) System.out.println("  at " + frame);
                    }
                }
            }
        }
    }
}
