package blue.coordination.external;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import java.util.*;

/** Actual initialized Core/Compute control; no altered providers or forged exact handles. */
public final class ComputeCyclicSchemaProbe {
    public static void main(String[] args) {
        String member = DirectBlueIdCalculator.calculateBlueId(new Node().value("nonexistent-set")) + "#0";
        for (String field : List.of("nested", "type", "schema")) try (var fixture = new CanonicalSourceHistoryTest.Fixture()) {
            String reference = "{$objectSet: {object: {$emptyObject: true}, key: blueId, val: '" + member + "'}}";
            String expression = "{$objectSet: {object: {$emptyObject: true}, key: " + field + ", val: " + reference + "}}";
            var authored = fixture.authored("""
                    name: cyclic schema review probe
                    counter: 0
                    contracts:
                      input:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      run:
                        type: Coordination/Sequential Workflow
                        channel: input
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: 99}
                              - $appendEvent: %s
                    """.formatted(expression), Map.of());
            var birth = (CoordinationCore.PreparedOperation) fixture.core.evaluate(new CoordinationCore.WorkIntent(
                    authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION), fixture.evidence(authored, List.of(), 20));
            var receipt = OperationReceiptCodec.encode(birth, fixture.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var before = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0,
                    fixture.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var input = CanonicalSourceHistoryTest.input("execute", 15, "account");
            var next = CanonicalSourceHistoryTest.input("later", 16, "account");
            var evidence = fixture.evidence(before, List.of(input, next), 20);
            for (int retry = 0; retry < 2; retry++) try {
                var result = fixture.core.evaluate(new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence);
                if (result instanceof CoordinationCore.PreparedOperations operations) {
                    var operation = operations.operations().get(0);
                    System.out.println(field + " retry=" + retry + " SETTLED " + operation.result().status()
                            + " disposition=" + operation.disposition() + " events=" + operation.result().events().size()
                            + " counter=" + operation.projections().get(0).result().document().get("/counter"));
                } else System.out.println(field + " retry=" + retry + " RESULT " + result);
            } catch (RuntimeException failure) {
                System.out.println(field + " retry=" + retry + " ESCAPED " + failure + " beforeCounter=" + before.document().getProperties().get("counter").getValue());
                Throwable cause = failure;
                for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause())
                    System.out.println("  cause=" + cause.getClass().getName() + ": " + cause.getMessage());
            }
        }
    }
}
