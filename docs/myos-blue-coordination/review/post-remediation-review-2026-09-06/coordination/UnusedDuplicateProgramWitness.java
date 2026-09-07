package blue.coordination.external;

import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Diagnostic only: valid optional source inventory must not acquire unrelated application fragments. */
public final class UnusedDuplicateProgramWitness {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();
    public static void main(String[] args) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = born(f, f.source());
            var entry = CanonicalSourceHistoryTest.input("unrelated source entry", 15, "account");
            var sourceOperation = ((CoordinationCore.PreparedOperations) f.core.evaluate(
                    new CoordinationCore.WorkIntent(source.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    f.evidence(source, List.of(entry), 20))).operations().get(0);
            var receipt = OperationReceiptCodec.encode(sourceOperation, f.blobs::put, LIMITS);
            boolean[] unavailable = {false};
            List<String> newReads = new ArrayList<>();
            FrozenNodeEvidenceCodec.Reader reader = id -> {
                if (unavailable[0]) { newReads.add(id); return null; }
                return f.blobs.get(id);
            };
            var coldA = OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), reader, LIMITS);
            var coldB = OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), reader, LIMITS);
            var target = born(f, f.authored("""
                    name: Ignoring independent root
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: another-actor}
                    """, Map.of()));
            var base = f.evidence(target, List.of(entry), 20);
            var intent = new CoordinationCore.WorkIntent(target.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            unavailable[0] = true;
            for (var offered : List.of(List.of(coldA), List.of(coldA, coldA), List.of(coldA, coldB))) {
                var evidence = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                        base.handledThrough(), base.fences(), base.precedingOperations(), offered);
                newReads.clear();
                try {
                    var result = f.core.evaluate(intent, evidence);
                    System.out.println("copies=" + offered.size() + ",sameObject=" + (offered.size() < 2 || offered.get(0) == offered.get(1))
                            + ",result=" + result.getClass().getSimpleName() + ",newReads=" + newReads.size());
                } catch (RuntimeException failure) {
                    System.out.println("copies=" + offered.size() + ",sameObject=false,result=" + failure.getClass().getSimpleName()
                            + ",newReads=" + newReads.size() + ",message=" + failure.getMessage());
                }
            }
        }
    }
    private static ManagedDocumentSnapshot born(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot authored) {
        var birth = (CoordinationCore.PreparedOperation) f.core.evaluate(
                new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                f.evidence(authored, List.of(), 20));
        var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, LIMITS);
        return OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0, f.blobs::get, LIMITS);
    }
}
