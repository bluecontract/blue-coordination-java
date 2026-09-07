package blue.coordination.external;

import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import java.util.List;
import java.util.Map;

/** Read-only evaluator diagnostic: no host publication or production/test modification. */
public final class MetadataFenceWitness {
    public static void main(String[] args) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = f.source();
            var birth = (CoordinationCore.PreparedOperation) f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 20));
            var encoded = OperationReceiptCodec.encode(birth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var before = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), authored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var input = CanonicalSourceHistoryTest.input("ignored actor", 15, "someone-else");
            var base = f.evidence(before, List.of(input), 20);
            var attributedOnly = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                    base.handledThrough(), List.of(), Map.of()).withOperationFences(base.operationFences());
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var legacy = (CoordinationCore.MetadataProgress) f.core.evaluate(intent, base);
            var attributed = (CoordinationCore.MetadataProgress) f.core.evaluate(intent, attributedOnly);
            System.out.println("input=" + input.order());
            System.out.println("attributedOwnerFenceCount=" + attributedOnly.operationFences().get(before.documentId()).size());
            System.out.println("legacyMetadataFenceCount=" + legacy.fences().size());
            System.out.println("attributedOnlyMetadataFenceCount=" + attributed.fences().size());
            System.out.println("sameInput=" + legacy.input().equals(attributed.input()));
        }
    }
}
