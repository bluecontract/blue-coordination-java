package blue.coordination.external;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real facade groups; custom test contracts perform no substitute Coordination semantics. */
class SameOriginReceiptCodecTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();
    private static final DocumentId A = new DocumentId("a"), B = new DocumentId("b");
    private static final Node CHANNEL = new Node().name("Receipt codec channel"), HANDLER = new Node().name("Receipt codec handler");
    private static final String CHANNEL_ID = DirectBlueIdCalculator.calculateBlueId(CHANNEL), HANDLER_ID = DirectBlueIdCalculator.calculateBlueId(HANDLER);

    @Test void realIndependentGroupsRetainOnlyOwnedStateEffectsAndOriginalSeedIdentity() {
        try (Fixture fixture = new Fixture()) {
            var groups = fixture.evaluate(Map.of(), 1);
            assertEquals(2, groups.size()); assertEquals(2, fixture.calls.get());
            Map<String, byte[]> store = new HashMap<>();
            for (var group : groups) {
                var encoded = OperationReceiptCodec.encode(group, store::put, LIMITS);
                Set<String> reads = new HashSet<>();
                var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), key -> { reads.add(key); return store.get(key); }, LIMITS);
                assertEquals(Set.of(encoded.receiptIdentity()), reads, "Metadata must not restore the program or original global snapshot");
                assertEquals(group.operationIdentity(), receipt.operationId());
                assertEquals(group.ownedDocumentIds(), new HashSet<>(receipt.states().stream().map(OperationReceiptCodec.OwnedState::lineage).toList()));
                assertEquals(group.totalGas(), receipt.gas().total()); assertTrue(receipt.resultIdentities().isEmpty(), "No invented global closure result");
                var restored = OperationReceiptCodec.restoreSameOrigin(encoded.receiptIdentity(), store::get, LIMITS);
                assertEquals(group.operationIdentity(), restored.group().identity());
                assertEquals(group.originalSeedByMember(), restored.group().originalSeedByMember());
                assertTrue(restored.group().admissions().isEmpty()); assertTrue(restored.rejectedAdmission().isEmpty());
                var program = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, LIMITS);
                assertEquals(group.operationIdentity(), program.invocationIdentity()); assertEquals(group.ownedDocumentIds(), program.ownedDocumentIds());
                var state = receipt.states().get(0);
                assertEquals(state.beforeEpoch() + 1, state.afterEpoch());
                assertEquals(state.afterBlueId(), OperationReceiptCodec.restoreState(encoded.receiptIdentity(), state.lineage(), false, 701, store::get, LIMITS).blueId());
                var effects = OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, LIMITS);
                assertEquals(1, effects.checkpoints().size()); assertEquals(state.lineage(), effects.checkpoints().get(0).targetManagedScopeKey().documentId());
                var gas = OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS);
                assertEquals(group.totalGas(), gas.trace().stream().mapToLong(GasTraceEntry::subtotal).sum());
                assertTrue(gas.sameOriginRejectedCharge().isEmpty()); assertTrue(gas.rejectedAdmission().isEmpty());
                assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreState(encoded.receiptIdentity(), state.lineage().equals(A) ? B : A, true, 0, store::get, LIMITS));
            }
            assertEquals(2, fixture.calls.get(), "Codec and cold readers must never execute handlers");
        }
    }

    @Test void realFailedGroupRestoresTypedFailureAndOrdinaryRejectionWithoutSuccessfulProgram() {
        try (Fixture fixture = new Fixture()) {
            long cost = fixture.evaluate(Map.of(), 1).stream().filter(group -> group.ownedDocumentIds().contains(A)).findFirst().orElseThrow().totalGas();
            var groups = fixture.evaluate(Map.of(A, cost - 1), 1);
            var failed = groups.stream().filter(group -> group.ownedDocumentIds().contains(A)).findFirst().orElseThrow();
            var valid = groups.stream().filter(group -> group.ownedDocumentIds().contains(B)).findFirst().orElseThrow();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status()); assertEquals(ProcessorStatus.SUCCESS, valid.status());
            Map<String, byte[]> store = new HashMap<>();
            var encoded = OperationReceiptCodec.encode(failed, store::put, LIMITS);
            var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS);
            assertTrue(receipt.sourceProgramIdentity().isEmpty()); assertTrue(receipt.events().isEmpty());
            assertEquals(Set.of(A), new HashSet<>(receipt.states().stream().map(OperationReceiptCodec.OwnedState::lineage).toList()));
            var sourceFailure = OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(failed.operationIdentity(), sourceFailure.invocationIdentity());
            assertEquals(Set.of(A), sourceFailure.ownedDocumentIds());
            assertEquals(List.of(A), sourceFailure.sourcePredecessors().stream().map(SourceObservationProgram.SourceState::documentId).toList());
            var gas = OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS);
            assertTrue(gas.rejectedCharge().isEmpty(), "No fabricated legacy rejected-charge constructor");
            var rejected = gas.sameOriginRejectedCharge().orElseThrow();
            assertEquals(sourceFailure.rejectedChargeIdentity(), rejected.identity()); assertTrue(gas.rejectedAdmission().isEmpty());
            assertEquals(failed.totalGas(), gas.trace().stream().mapToLong(GasTraceEntry::subtotal).sum());
            assertEquals(receipt.states().get(0).beforeBlueId(), receipt.states().get(0).afterBlueId());
            assertEquals(receipt.states().get(0).beforeEpoch(), receipt.states().get(0).afterEpoch());
            assertTrue(OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, LIMITS).checkpoints().isEmpty());
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, LIMITS));
            var cold = SourceOperationFailureCodec.decode(receipt.sourceFailureIdentity().orElseThrow(), store::get, LIMITS);
            assertEquals(sourceFailure.rejectedChargeIdentity(), cold.rejectedChargeIdentity());
            byte[] body = store.remove(receipt.states().get(0).bodyIdentity());
            assertNotNull(OperationReceiptCodec.restoreSameOrigin(encoded.receiptIdentity(), store::get, LIMITS));
            assertThrows(ExecutionEvidenceUnavailableException.class, () -> OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), store::get, LIMITS));
            store.put(receipt.states().get(0).bodyIdentity(), body);
        }
    }

    @Test void groupEvidenceOrSuccessfulProgramFromAnotherRealOperationCannotSubstitute() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var groups = fixture.evaluate(Map.of(), 1); Map<String, byte[]> store = new HashMap<>();
            var first = OperationReceiptCodec.encode(groups.get(0), store::put, LIMITS);
            var second = OperationReceiptCodec.encode(groups.get(1), store::put, LIMITS);
            ObjectMapper json = new ObjectMapper();
            ObjectNode wrongGroup = (ObjectNode) json.readTree(store.get(first.receiptIdentity()));
            wrongGroup.set("sameOrigin", json.readTree(store.get(second.receiptIdentity())).get("sameOrigin"));
            String changed = retain(json.writeValueAsBytes(wrongGroup), store);
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSameOrigin(changed, store::get, LIMITS));
            ObjectNode wrongProgram = (ObjectNode) json.readTree(store.get(first.receiptIdentity()));
            wrongProgram.put("sourceProgram", second.sourceProgramIdentity().orElseThrow());
            String substituted = retain(json.writeValueAsBytes(wrongProgram), store);
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSourceProgram(substituted, store::get, LIMITS));
        }
    }

    @Test void failedChargeConstructorIsClosedAndPhysicalGenerationIndependent() {
        try (Fixture fixture = new Fixture()) {
            long cost = fixture.evaluate(Map.of(), 1).get(0).totalGas();
            var first = fixture.evaluate(Map.of(A, cost - 1), 1).stream().filter(group -> group.ownedDocumentIds().contains(A)).findFirst().orElseThrow();
            var second = fixture.evaluate(Map.of(A, cost - 1), 97).stream().filter(group -> group.ownedDocumentIds().contains(A)).findFirst().orElseThrow();
            var rejected = SameOriginRejectedChargeEvidence.fromOperation(first);
            assertEquals(rejected.identity(), SameOriginRejectedChargeEvidence.fromOperation(second).identity());
            Map<String, Object> altered = new TreeMap<>(rejected.canonicalValue()); altered.put("quantity", 0L);
            assertThrows(IllegalArgumentException.class, () -> SameOriginRejectedChargeEvidence.fromExactEvidence(rejected.identity(), altered));
            altered.put("quantity", rejected.canonicalValue().get("quantity")); altered.put("physicalWorker", "not-semantic");
            assertThrows(IllegalArgumentException.class, () -> SameOriginRejectedChargeEvidence.fromExactEvidence(rejected.identity(), altered));
            assertThrows(UnsupportedOperationException.class, () -> rejected.canonicalValue().put("worker", "x"));
        }
    }

    @Test void gasRestoreRejectsAChangedTraceEvenWhenItsArithmeticStillAddsUp() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var group = fixture.evaluate(Map.of(), 1).get(0); Map<String, byte[]> store = new HashMap<>();
            var encoded = OperationReceiptCodec.encode(group, store::put, LIMITS);
            ObjectMapper json = new ObjectMapper(); ObjectNode root = (ObjectNode) json.readTree(store.get(encoded.receiptIdentity()));
            var trace = root.get("gas").get("trace"); assertFalse(trace.isEmpty());
            ObjectNode row = (ObjectNode) json.readTree(store.get(trace.get(0).textValue())); row.put("counter", "different-semantic-work");
            ((com.fasterxml.jackson.databind.node.ArrayNode) trace).set(0, json.getNodeFactory().textNode(retain(json.writeValueAsBytes(row), store)));
            String changed = retain(json.writeValueAsBytes(root), store);
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreGas(changed, store::get, LIMITS));
        }
    }

    @Test void failedOwnerPreservesItsHistoricalReferenceWithoutSerializingOptionalDependencyCaches() {
        try (Fixture fixture = new Fixture()) {
            var failed = fixture.historicalFailure();
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.status()); assertEquals(Set.of(A), failed.ownedDocumentIds());
            assertTrue(failed.consumedSourceOperations().isEmpty());
            var historical = failed.origin().snapshot().readPins().get(0);
            assertNotEquals(historical.blueId(), failed.origin().snapshot().managedDocument(B).blueId());
            Map<String, byte[]> store = new HashMap<>(); var encoded = OperationReceiptCodec.encode(failed, store::put, LIMITS);
            var restored = OperationReceiptCodec.restoreReadPins(encoded.receiptIdentity(), store::get, LIMITS);
            assertTrue(restored.isEmpty(), "No accepted installation: foreign body residency is an acquisition detail");
            var owner = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), A, true, 1, store::get, LIMITS);
            assertEquals(historical.blueId(), owner.document().getProperties().get("b").getBlueId());
            var receipt = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(List.of(A), receipt.states().stream().map(OperationReceiptCodec.OwnedState::lineage).toList());
            assertEquals(List.of(A), OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), store::get, LIMITS).sourcePredecessors().stream()
                    .map(SourceObservationProgram.SourceState::documentId).toList());
            var withOptionalCache = fixture.historicalFailure(true);
            assertEquals(failed.operationIdentity(), withOptionalCache.operationIdentity());
            assertEquals(encoded.receiptIdentity(), OperationReceiptCodec.encode(withOptionalCache, store::put, LIMITS).receiptIdentity(),
                    "Unreferenced newer exact bodies of the same target are physical cache inventory, not failed operation evidence");
        }
    }

    @Test void groupEventsRetainCanonicalOrdinalAndIntrinsicIdentityWithoutInventingGlobalEncounterIndex() {
        try (Fixture fixture = new Fixture(true)) {
            Map<String, byte[]> store = new HashMap<>();
            for (var group : fixture.evaluate(Map.of(), 1)) {
                assertEquals(1, group.events().size());
                var encoded = OperationReceiptCodec.encode(group, store::put, LIMITS);
                var event = OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS).events().get(0);
                assertEquals(group.events().get(0).ordinal(), event.ordinal());
                assertEquals(group.events().get(0).occurrenceIdentity(), event.occurrenceIdentity());
                assertNull(event.occurrenceOrdinal(), "Whole-attempt encounter indices are not stable atomic-group authority");
            }
        }
    }

    private static String retain(byte[] value, Map<String, byte[]> store) { String key = FrozenNodeEvidenceCodec.digest(value); store.put(key, value); return key; }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final DocumentProcessor owner;
        Fixture() { this(false); }
        Fixture(boolean emit) {
            var registry = ContractProcessorRegistryBuilder.create().register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler contract, ProcessorExecutionContext context) {
                            calls.incrementAndGet(); if (emit) context.emitEvent(new Node().name("group event"));
                        }
                    }).build();
            owner = DocumentProcessor.builder().runtimeRegistry(registry).build();
        }
        List<SameOriginOperationResult> evaluate(Map<DocumentId, Long> localLimits, long physicalGeneration) {
            var environment = ClosureEvidenceFactory.environment(owner, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "group-lineage", "group-binding", "group-provider", "group-order", "group-limits", GasSchedule.contracts10().portableLimits());
            List<ManagedDocumentSnapshot> states = new ArrayList<>();
            for (DocumentId id : List.of(A, B)) {
                Node body = new Node().name("Group " + id.value()).contracts(new Node().properties("source", typed(CHANNEL_ID))
                        .properties("handler", typed(HANDLER_ID).properties("channel", new Node().value("source"))));
                body.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                        .properties("document", new Node().blueId(DirectBlueIdCalculator.calculateBlueId(body))));
                states.add(new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, true, 7, physicalGeneration));
            }
            var snapshot = ClosureEvidenceFactory.affectedClosure(physicalGeneration, states, List.of(), states.stream().map(ClosureEvidenceFactory::acyclicComponent).toList(), List.of(A, B));
            Node event = new Node().name("group-input");
            var cause = ClosureEvidenceFactory.externalCause(event, DirectBlueIdCalculator.calculateBlueId(event), ExternalOrderKey.of(List.of(1L, "source")), environment.externalOrderPolicyIdentity());
            var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0),
                    new DirectLogicalDelivery(ManagedScopeKey.root(B), "source", "logical", 1)), ClosureEvidenceFactory.executionPolicy(100_000, localLimits, "group-policy"), environment);
            input = ClosureEvidenceFactory.withSemanticPredecessors(input, Map.of(A, "sha256:" + "1".repeat(64), B, "sha256:" + "2".repeat(64)));
            try (var contracts = new BlueClosureContracts(owner)) { var result = contracts.processSameOrigin(input); assertTrue(result.complete()); return result.operations(); }
        }
        SameOriginOperationResult historicalFailure() {
            return historicalFailure(false);
        }
        SameOriginOperationResult historicalFailure(boolean optionalCurrentPin) {
            var environment = ClosureEvidenceFactory.environment(owner, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "group-lineage", "group-binding", "group-provider", "group-order", "group-limits", GasSchedule.contracts10().portableLimits());
            var b1 = snapshot(B, new Node().name("B1").properties("x", new Node().value(1)).contracts(new Node()));
            var b2 = snapshot(B, new Node().name("B2").properties("x", new Node().value(2)).contracts(new Node()));
            var a = snapshot(A, new Node().name("A keeps B1").properties("b", new Node().blueId(b1.blueId()))
                    .contracts(new Node().properties("source", typed(CHANNEL_ID))
                            .properties("handler", typed(HANDLER_ID).properties("channel", new Node().value("source")))
                            .properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/b"))))));
            var binding = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), A, ScopeAddress.embedded("/b", 1), B, b1.blueId(), true, null);
            List<ManagedReadPin> pins = new ArrayList<>(); pins.add(ManagedReadPin.fromExactEvidence(B, b1.blueId(), b1.document(), null));
            if (optionalCurrentPin) pins.add(ManagedReadPin.fromExactEvidence(B, b2.blueId(), b2.document(), null));
            var closure = ClosureEvidenceFactory.affectedClosure(1, List.of(a, b2), List.of(binding),
                    List.of(ClosureEvidenceFactory.acyclicComponent(b2), ClosureEvidenceFactory.acyclicComponent(a)), List.of(A, B),
                    pins);
            Node event = new Node().name("only A input");
            var cause = ClosureEvidenceFactory.externalCause(event, DirectBlueIdCalculator.calculateBlueId(event), ExternalOrderKey.of(List.of(1L, "entry")), environment.externalOrderPolicyIdentity());
            var input = ClosureEvidenceFactory.processClosure(closure, cause, List.of(new DirectLogicalDelivery(ManagedScopeKey.root(A), "source", "logical", 0)),
                    ClosureEvidenceFactory.executionPolicy(100_000, Map.of(A, 0L), "group-policy"), environment);
            try (var contracts = new BlueClosureContracts(owner)) {
                var result = contracts.processSameOrigin(input); assertTrue(result.complete()); assertEquals(1, result.operations().size()); return result.operations().get(0);
            }
        }
        private static ManagedDocumentSnapshot snapshot(DocumentId id, Node body) {
            body.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                    .properties("document", new Node().blueId(DirectBlueIdCalculator.calculateBlueId(body))));
            return new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, true, 7, 1);
        }
        @Override public void close() { owner.close(); }
    }
    private static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    public static final class TestChannel extends ChannelContract { }
    public static final class TestHandler extends HandlerContract { }
    private static final class TestChannelProcessor implements ChannelProcessor<TestChannel> {
        @Override public Class<TestChannel> contractType() { return TestChannel.class; }
        @Override public ExternalChannelSubscriptionFunctions<TestChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<>() {
                @Override public List<String> channelKeys(TestChannel channel) { return List.of("test"); }
                @Override public boolean preselects(TestChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public boolean accepts(TestChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public String logicalDeliveryKey(TestChannel channel, Node event, Node payload, ExternalChannelFunctionContext context) { return "logical"; }
                @Override public String checkpointDomainDiscriminator(TestChannel channel) { return "group-codec-test"; }
            };
        }
    }
}
