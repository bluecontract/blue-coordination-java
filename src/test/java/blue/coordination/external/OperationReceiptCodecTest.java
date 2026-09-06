package blue.coordination.external;

import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.closure.*;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OperationReceiptCodecTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void failedInitializationRetainsExactGasWithoutPublishingProgramOrNewState() {
        var operation = initialize("gas failure", "worker", 0L);
        assertEquals(blue.language.processor.ProcessorStatus.GAS_LIMIT_EXCEEDED, operation.result().status());
        Map<String, byte[]> store = new HashMap<>();
        var encoded = OperationReceiptCodec.encode(operation, store::put, LIMITS);
        var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS);
        assertEquals(CoordinationCore.Disposition.BLOCKED, receipt.disposition());
        assertTrue(receipt.sourceProgramIdentity().isEmpty()); assertTrue(receipt.events().isEmpty());
        var state = receipt.states().get(0);
        assertEquals(state.beforeBlueId(), state.afterBlueId()); assertEquals(state.beforeEpoch(), state.afterEpoch());
        var gas = OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS);
        assertEquals(operation.result().rejectedCharge().rejectedChargeIdentity(), gas.rejectedCharge().orElseThrow().rejectedChargeIdentity());
        assertEquals(operation.result().totalGas(), gas.trace().stream().mapToLong(GasTraceEntry::subtotal).sum());
    }

    @Test
    void actualInitializationRestoresOnlyFromReceiptAndFragments() {
        var operation = initialize("receipt source", "worker-one");
        Map<String, byte[]> store = new HashMap<>();
        var encoded = OperationReceiptCodec.encode(operation, store::put, LIMITS);
        var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS);
        assertEquals(operation.operationId(), receipt.operationId());
        assertEquals(operation.result().totalGas(), receipt.gas().total());
        assertEquals(operation.result().gasTraceIdentity(), receipt.gas().traceIdentity());
        var restoredGas = OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS);
        assertEquals(operation.result().gasTrace().size(), restoredGas.trace().size());
        assertEquals(operation.result().totalGas(), restoredGas.trace().stream().mapToLong(GasTraceEntry::subtotal).sum());
        assertTrue(restoredGas.rejectedCharge().isEmpty());
        assertEquals(encoded.sourceProgramIdentity(), receipt.sourceProgramIdentity());
        assertEquals(1, receipt.states().size());
        var expected = operation.projections().get(0);
        var restored = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), expected.lineage(), false, 73L, store::get, LIMITS);
        assertEquals(expected.afterBlueId(), restored.blueId());
        assertEquals(expected.afterEpoch(), restored.epoch());
        assertFalse(restored.publicRoot());
        assertEquals(73L, restored.componentGeneration());
        assertTrue(FrozenNode.fromResolvedNode(expected.result().document()).sameResolvedStructure(FrozenNode.fromResolvedNode(restored.document())));
        var program = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, LIMITS);
        assertEquals(operation.operationId(), program.invocationIdentity());
        assertEquals(Set.of(expected.lineage()), program.ownedDocumentIds());
        assertEquals(encoded.sourceProgramIdentity().orElseThrow(), SourceObservationProgramCodec.encode(program, store::put, LIMITS));
        String manifest = new String(store.get(encoded.receiptIdentity()), StandardCharsets.UTF_8);
        assertFalse(manifest.contains("worker-one"));
        assertFalse(manifest.contains("publicRoot"));
    }

    @Test
    void metadataReadsAreLazyButMissingOrCorruptOwnedBodyCannotRestore() {
        var operation = initialize("lazy source", "worker");
        Map<String, byte[]> store = new HashMap<>();
        var encoded = OperationReceiptCodec.encode(operation, store::put, LIMITS);
        Set<String> reads = new HashSet<>();
        var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), id -> { reads.add(id); return store.get(id); }, LIMITS);
        assertEquals(Set.of(encoded.receiptIdentity()), reads);
        var state = receipt.states().get(0);
        byte[] body = store.remove(state.bodyIdentity());
        assertNotNull(OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS));
        assertThrows(ExecutionEvidenceUnavailableException.class, () -> OperationReceiptCodec.restoreState(
                encoded.receiptIdentity(), state.lineage(), true, 0L, store::get, LIMITS));
        byte[] corrupt = body.clone(); corrupt[0] ^= 1; store.put(state.bodyIdentity(), corrupt);
        assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreState(
                encoded.receiptIdentity(), state.lineage(), true, 0L, store::get, LIMITS));
        store.put(state.bodyIdentity(), body);
        assertNotNull(OperationReceiptCodec.restoreState(encoded.receiptIdentity(), state.lineage(), true, 0L, store::get, LIMITS));
    }

    @Test
    void programFromAnotherValidOperationCannotBeSubstitutedIntoReceipt() throws Exception {
        Map<String, byte[]> store = new HashMap<>();
        var first = OperationReceiptCodec.encode(initialize("first", "worker"), store::put, LIMITS);
        var second = OperationReceiptCodec.encode(initialize("second", "worker"), store::put, LIMITS);
        ObjectMapper json = new ObjectMapper();
        ObjectNode modified = (ObjectNode) json.readTree(store.get(first.receiptIdentity()));
        modified.put("sourceProgram", second.sourceProgramIdentity().orElseThrow());
        byte[] bytes = json.writeValueAsBytes(modified);
        String identity = FrozenNodeEvidenceCodec.digest(bytes); store.put(identity, bytes);
        assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSourceProgram(identity, store::get, LIMITS));
    }

    private static CoordinationCore.PreparedOperation initialize(String name, String fence) {
        return initialize(name, fence, 100_000L);
    }

    private static CoordinationCore.PreparedOperation initialize(String name, String fence, long gasLimit) {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            var exact = ExactValue.verified(new Node().name(name));
            var id = new DocumentId(exact.blueId());
            var document = new ManagedDocumentSnapshot(id, exact.blueId(), exact.copyNode(), false, false, true, 0L, 0L);
            var snapshot = ClosureEvidenceFactory.affectedClosure(0L, List.of(document), List.of(),
                    List.of(ClosureEvidenceFactory.acyclicComponent(document)), List.of(id));
            var environment = ClosureEvidenceFactory.environment(processor, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "content-lineage", "exact-binding", "test-provider", "micros-entry-text", "local-limits", GasSchedule.contracts10().portableLimits());
            var core = new CoordinationCore(processor, environment, ClosureEvidenceFactory.executionPolicy(gasLimit, Map.of(), "local-source-policy"));
            return assertInstanceOf(CoordinationCore.PreparedOperation.class, core.evaluate(
                    new CoordinationCore.WorkIntent(id, CoordinationCore.OperationKind.INITIALIZATION),
                    new CoordinationCore.EvaluationEvidence(snapshot, Set.of(), List.of(), Optional.empty(),
                            List.of(new CoordinationCore.ReadFence("lineage", fence)), Map.of())));
        }
    }
}
