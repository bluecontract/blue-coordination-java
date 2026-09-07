package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Compute → BEX → Contracts settlement, not merely an expression classifier test. */
class ComputeAuthoredOutputFailureTest {
    @ParameterizedTest(name = "per-keyword reference={0}")
    @ValueSource(booleans = {false, true})
    void schemaReferencesDoNotBecomeAuthoredFailures(boolean keywordReference) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var minimum = new blue.language.model.Node().value(new java.math.BigDecimal("0.5"));
            var exact = blue.coordination.api.ExactValue.verified(keywordReference ? minimum
                    : new blue.language.model.Node().properties("minimum", minimum));
            String reference = "{$objectSet: {object: {$emptyObject: true}, key: blueId, val: " + exact.blueId() + "}}";
            String schema = keywordReference ? "{$objectSet: {object: {$emptyObject: true}, key: minimum, val: " + reference + "}}" : reference;
            var authored = f.authored("""
                    name: exact numeric schema output
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
                                    object: {$objectSet: {object: {$emptyObject: true}, key: value, val: 1}}
                                    key: schema
                                    val: %s
                    """.formatted(schema), Map.of());
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 20)));
            var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var before = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var input = CanonicalSourceHistoryTest.input("execute exact schema", 15, "account");
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var evidence = f.evidence(before, List.of(input), 20);
            if (keywordReference) {
                // Direct identity supports numeric keyword references, but the
                // current semantic resolver does not acquire individual keyword
                // values. Preserve that pre-existing noncommitting behavior.
                assertThrows(blue.language.processor.UnclassifiedProcessingException.class,
                        () -> f.core.evaluate(intent, evidence));
                f.nodes.put(exact.blueId(), exact.copyNode());
                assertThrows(blue.language.processor.UnclassifiedProcessingException.class,
                        () -> f.core.evaluate(intent, evidence));
                assertEquals(BigInteger.ZERO, before.document().getProperties().get("counter").getValue());
                assertFalse(f.exactReads.contains(exact.blueId()));
                return;
            }
            // This generic output-runtime port reports missing provider content
            // operationally, without promising a typed acquisition-need result.
            assertThrows(blue.language.processor.UnclassifiedProcessingException.class,
                    () -> f.core.evaluate(intent, evidence));
            assertTrue(f.exactReads.contains(exact.blueId()));
            assertEquals(BigInteger.ZERO, before.document().getProperties().get("counter").getValue());
            f.nodes.put(exact.blueId(), exact.copyNode());
            var resumed = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(intent, evidence));
            var succeeded = resumed.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, succeeded.result().status());
            assertEquals(CoordinationCore.Disposition.CONSUMED, succeeded.disposition());
            assertEquals(input.order(), succeeded.input().orElseThrow().order());
            assertEquals(BigInteger.valueOf(99), succeeded.projections().get(0).result().document().get("/counter"));
            assertEquals(1, succeeded.result().events().size());
        }
    }

    @ParameterizedTest(name = "{0}={1}, nested={2}")
    @CsvSource({"required, bogus, false", "uniqueItems, bogus, false",
            "minItems, bogus, false", "maxLength, bogus, false", "minimum, bogus, false",
            "minFields, 0.5, false", "maximum, bogus, true"})
    void malformedSchemaOutputConsumesFailureAndPermitsNextInput(String keyword, String value, boolean nested) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            String invalid = "{$objectSet: {object: {$emptyObject: true}, key: schema, val: "
                    + "{$objectSet: {object: {$emptyObject: true}, key: " + keyword + ", val: " + value + "}}}}";
            if (nested) invalid = "{$objectSet: {object: {$emptyObject: true}, key: child, val: " + invalid + "}}";
            var authored = f.authored("""
                    name: malformed dynamic schema settlement
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
                                    - $appendEvent: %s
                                  else:
                                    - $appendEvent: valid output
                    """.formatted(invalid), Map.of());
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    f.evidence(authored, List.of(), 20)));
            var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var before = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var invalidInput = CanonicalSourceHistoryTest.input("invalid", 15, "account");
            var validInput = CanonicalSourceHistoryTest.input("valid", 16, "account");
            var evidence = f.evidence(before, List.of(invalidInput, validInput), 20);
            var attempt = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence));
            assertEquals(1, attempt.operations().size());
            var failed = attempt.operations().get(0);
            assertEquals(CoordinationCore.Disposition.CONSUMED, failed.disposition());
            assertEquals(invalidInput.order(), failed.input().orElseThrow().order());
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.result().status());
            assertTrue(failed.result().events().isEmpty());
            assertEquals(before.blueId(), failed.projections().get(0).afterBlueId());
            assertEquals(before.epoch(), failed.projections().get(0).afterEpoch());
            assertEquals(BigInteger.ZERO, failed.projections().get(0).result().document().get("/counter"));
            assertTrue(failed.result().totalGas() > 0);
            assertTrue(failed.result().failure().isPresent());
            assertTrue(failed.result().gasTrace().stream().anyMatch(charge ->
                    charge.namespace() == blue.language.processor.closure.GasTraceEntry.Namespace.RUNTIME));
            var retried = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence));
            assertEquals(failed.operationId(), retried.operations().get(0).operationId());
            assertEquals(blue.language.processor.closure.GasTraceEntry.identityOfTrace(failed.result().gasTrace()),
                    blue.language.processor.closure.GasTraceEntry.identityOfTrace(retried.operations().get(0).result().gasTrace()));
            assertEquals(failed.result().totalGas(), retried.operations().get(0).result().totalGas());

            var failedReceipt = OperationReceiptCodec.encode(failed, f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var afterFailure = OperationReceiptCodec.restoreState(failedReceipt.receiptIdentity(), before.documentId(), true, 0,
                    f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
            var nextEvidence = f.evidence(afterFailure, List.of(invalidInput, validInput), 20);
            nextEvidence = new CoordinationCore.EvaluationEvidence(nextEvidence.snapshot(), nextEvidence.relevantTimelines(),
                    nextEvidence.prefixes(), Optional.of(invalidInput.order()), nextEvidence.fences(),
                    Map.of(before.documentId(), failed.operationId())).withOperationFences(nextEvidence.operationFences());
            var next = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), nextEvidence));
            assertEquals(1, next.operations().size());
            var succeeded = next.operations().get(0);
            assertEquals(CoordinationCore.Disposition.CONSUMED, succeeded.disposition());
            assertEquals(validInput.order(), succeeded.input().orElseThrow().order());
            assertEquals(ProcessorStatus.SUCCESS, succeeded.result().status());
            assertEquals(BigInteger.valueOf(99), succeeded.projections().get(0).result().document().get("/counter"));
            assertEquals(1, succeeded.result().events().size());
        }
    }

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
