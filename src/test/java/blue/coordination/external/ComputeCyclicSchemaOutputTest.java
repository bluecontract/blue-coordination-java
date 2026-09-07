package blue.coordination.external;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.GasTraceEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Real Compute export, Contracts rollback, cold receipt restoration and next-input selection. */
class ComputeCyclicSchemaOutputTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @ParameterizedTest(name = "transient {0} through {1}")
    @CsvSource({"nested, identity", "nested, event", "nested, patch",
            "type, identity", "type, event", "type, patch",
            "schema, identity", "schema, event", "schema, patch"})
    void transientCyclicOutputConsumesRollbackAndThenExecutesValidInput(String field, String export) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            String member = DirectBlueIdCalculator.calculateBlueId(new Node().value("not-a-cyclic-set")) + "#0";
            String reference = "{$objectSet: {object: {$emptyObject: true}, key: blueId, val: '" + member + "'}}";
            String value = "{$objectSet: {object: {$emptyObject: true}, key: " + field + ", val: " + reference + "}}";
            String statement = "identity".equals(export) ? "$appendEvent: {$nodeBlueId: " + value + "}"
                    : "event".equals(export) ? "$appendEvent: " + value
                    : "$appendChange: {op: replace, path: /counter, val: " + value + "}";
            var authored = f.authored("""
                    name: transient cyclic schema output settlement
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
                              - $if:
                                  cond: {$eq: [{$event: /message}, invalid]}
                                  then:
                                    - %s
                                  else:
                                    - $appendEvent: valid output
                    """.formatted(statement), Map.of());
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 20)));
            assertEquals(ProcessorStatus.SUCCESS, birth.result().status());
            var born = OperationReceiptCodec.encode(birth, f.blobs::put, LIMITS);
            var before = OperationReceiptCodec.restoreState(born.receiptIdentity(), authored.documentId(), true, 0, f.blobs::get, LIMITS);
            var invalid = CanonicalSourceHistoryTest.input("invalid", 15, "account");
            var valid = CanonicalSourceHistoryTest.input("valid", 16, "account");
            var evidence = f.evidence(before, List.of(invalid, valid), 20);
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var attempt = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(intent, evidence));
            assertEquals(1, attempt.operations().size());
            var failed = attempt.operations().get(0);
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.result().status());
            assertEquals(CoordinationCore.Disposition.CONSUMED, failed.disposition());
            assertEquals(invalid.order(), failed.input().orElseThrow().order());
            assertEquals(before.blueId(), failed.projections().get(0).afterBlueId());
            assertEquals(before.epoch(), failed.projections().get(0).afterEpoch());
            assertEquals(BigInteger.ZERO, failed.projections().get(0).result().document().get("/counter"));
            assertTrue(failed.result().events().isEmpty());
            assertTrue(failed.result().totalGas() > 0);
            assertTrue(failed.result().gasTrace().stream().anyMatch(charge -> charge.namespace() == GasTraceEntry.Namespace.RUNTIME));
            assertFalse(f.exactReads.contains(member), "Transient output must reject before acquiring invented cyclic content");
            var repeated = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(intent, evidence)).operations().get(0);
            assertEquals(failed.operationId(), repeated.operationId());
            assertEquals(failed.result().gasTraceIdentity(), repeated.result().gasTraceIdentity());
            assertEquals(failed.result().totalGas(), repeated.result().totalGas());

            var receipt = OperationReceiptCodec.encode(failed, f.blobs::put, LIMITS);
            var restored = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), before.documentId(), true, 0, f.blobs::get, LIMITS);
            var next = f.evidence(restored, List.of(invalid, valid), 20);
            next = new CoordinationCore.EvaluationEvidence(next.snapshot(), next.relevantTimelines(), next.prefixes(),
                    Optional.of(invalid.order()), next.fences(), Map.of(before.documentId(), failed.operationId()))
                    .withOperationFences(next.operationFences());
            var progressed = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(intent, next));
            assertEquals(1, progressed.operations().size());
            var succeeded = progressed.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, succeeded.result().status());
            assertEquals(CoordinationCore.Disposition.CONSUMED, succeeded.disposition());
            assertEquals(valid.order(), succeeded.input().orElseThrow().order());
            assertEquals(before.epoch() + 1, succeeded.projections().get(0).afterEpoch());
            assertEquals(BigInteger.valueOf(99), succeeded.projections().get(0).result().document().get("/counter"));
            assertEquals(1, succeeded.result().events().size());
            assertEquals("valid output", succeeded.result().events().get(0).event().getValue());
        }
    }
}
