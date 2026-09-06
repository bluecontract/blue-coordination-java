package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A real terminal source receipt closes future live selection, including after cold restore. */
class TerminalSourceSelectionTest {
    @Test
    void actualTerminalReceiptProducesNoLaterHandlerOrEpoch() {
        try (var fixture = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = fixture.authored("""
                    name: Actual terminal source
                    counter: 0
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      finish:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps:
                          - type: Coordination/Compute
                            do:
                              - $appendChange: {op: replace, path: /counter, val: {$add: [{$document: /counter}, 1]}}
                              - $return: true
                          - type: Coordination/Terminate Processing
                            reason: real-terminal-source
                    """, Map.of());
            var limits = FrozenNodeEvidenceCodec.Limits.defaults();
            var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, fixture.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                    fixture.evidence(authored, List.of(), 1)));
            var birthReceipt = OperationReceiptCodec.encode(birth, fixture.blobs::put, limits);
            var initialized = OperationReceiptCodec.restoreState(birthReceipt.receiptIdentity(), authored.documentId(),
                    true, 0, fixture.blobs::get, limits);
            var first = CanonicalSourceHistoryTest.input("finish", 10, "account");
            var prepared = assertInstanceOf(CoordinationCore.PreparedOperations.class, fixture.core.evaluate(
                    new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    fixture.evidence(initialized, List.of(first), 11)));
            assertEquals(1, prepared.operations().size());
            var terminal = prepared.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, terminal.result().status());
            assertTrue(terminal.projections().get(0).result().terminated());
            assertEquals(1L, terminal.projections().get(0).result().epoch());
            var receipt = OperationReceiptCodec.encode(terminal, fixture.blobs::put, limits);
            var cold = OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(),
                    true, 0, fixture.blobs::get, limits);
            assertTrue(cold.terminated());
            assertEquals(BigInteger.ONE, cold.document().getNode("/counter").getValue());
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(cold), List.of(),
                    List.of(ClosureEvidenceFactory.acyclicComponent(cold)), List.of(cold.documentId()));
            var later = CanonicalSourceHistoryTest.input("later-input", 20, "account");
            try (var contracts = new BlueClosureContracts(fixture.processor)) {
                assertTrue(contracts.selectDirectDeliveries(snapshot, later.event().copyNode()).isEmpty());
            }
            var evidence = new CoordinationCore.EvaluationEvidence(snapshot, Set.of(),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 21, List.of(later))),
                    Optional.of(first.order()), List.of(), Map.of(cold.documentId(), terminal.operationId()));
            assertInstanceOf(CoordinationCore.Idle.class, fixture.core.evaluate(
                    new CoordinationCore.WorkIntent(cold.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence));
            assertEquals(1L, snapshot.managedDocument(cold.documentId()).epoch());
            assertEquals(cold.blueId(), snapshot.managedDocument(cold.documentId()).blueId());
        }
    }
}
