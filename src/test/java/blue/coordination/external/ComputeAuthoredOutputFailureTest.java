package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Compute → BEX → Contracts settlement, not merely an expression classifier test. */
class ComputeAuthoredOutputFailureTest {
    @Test void dynamicallyInvalidEventSettlesSemanticFailureAndRollsBackEarlierBufferedPatch() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = f.authored("""
                    name: invalid dynamic output settlement
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
                              - $appendEvent:
                                  $objectSet:
                                    object: {$emptyObject: true}
                                    key: blueId
                                    val: "0"
                    """, Map.of());
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 20)));
            var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var before = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var input = CanonicalSourceHistoryTest.input("execute invalid output", 15, "account");
            var attempt = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    f.evidence(before, List.of(input), 20)));
            assertEquals(1, attempt.operations().size()); var failed = attempt.operations().get(0);
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.result().status());
            assertTrue(failed.result().events().isEmpty());
            assertEquals(before.blueId(), failed.projections().get(0).afterBlueId());
            assertEquals(before.epoch(), failed.projections().get(0).afterEpoch());
            assertEquals(BigInteger.ZERO, failed.projections().get(0).result().document().get("/counter"));
            assertTrue(failed.result().totalGas() > 0); assertFalse(failed.result().gasTrace().isEmpty());
            assertTrue(failed.result().failure().isPresent());
            assertTrue(failed.result().gasTrace().stream().anyMatch(charge ->
                    charge.namespace() == blue.language.processor.closure.GasTraceEntry.Namespace.RUNTIME),
                    "The failed BEX invocation must settle its admitted child ledger");
        }
    }
}
