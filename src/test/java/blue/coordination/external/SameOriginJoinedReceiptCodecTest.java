package blue.coordination.external;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguageRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public facade + actual conditional three-party joins; no fabricated global closure result. */
class SameOriginJoinedReceiptCodecTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();
    private static final DocumentId A = new DocumentId("a"), B = new DocumentId("b"), C = new DocumentId("c");
    private static final Node CHANNEL = new Node().name("Joined receipt channel"), HANDLER = new Node().name("Joined receipt handler");
    private static final String CHANNEL_ID = DirectBlueIdCalculator.calculateBlueId(CHANNEL), HANDLER_ID = DirectBlueIdCalculator.calculateBlueId(HANDLER);

    @Test void acceptedThreePartyGroupColdRoundTripKeepsOriginalSeedsAcceptedJoinsAndOneFinalOwner() {
        try (Fixture fixture = new Fixture(false)) {
            var operations = fixture.evaluate(100_000); assertEquals(1, operations.size()); var group = operations.get(0);
            assertEquals(ProcessorStatus.SUCCESS, group.status()); assertEquals(Set.of(A, B, C), group.ownedDocumentIds());
            assertEquals(2, group.admissions().size()); assertEquals(3, new HashSet<>(group.originalSeedByMember().values()).size());
            var executionCalls = List.copyOf(fixture.calls);
            Map<String, byte[]> store = new HashMap<>(); var encoded = OperationReceiptCodec.encode(group, store::put, LIMITS);
            var metadata = OperationReceiptCodec.restoreSameOrigin(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(group.originalSeedByMember(), metadata.group().originalSeedByMember());
            assertEquals(group.operationIdentity(), metadata.group().identity());
            assertEquals(group.admissions().stream().map(SameOriginOperationResult.Admission::canonicalSite).toList(),
                    metadata.group().admissions().stream().map(SameOriginGroupEvidence.Admission::canonicalSite).toList());
            var program = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(Set.of(A, B, C), program.ownedDocumentIds()); assertEquals(2, program.acceptedViews().size());
            assertEquals(group.operationIdentity(), program.invocationIdentity());
            var originalSteps = group.sourceProgram().orElseThrow().steps();
            assertEquals(originalSteps.stream().map(SourceObservationProgram.Step::workIdentity).toList(),
                    program.steps().stream().map(SourceObservationProgram.Step::workIdentity).toList());
            assertEquals(transitions(group.sourceProgram().orElseThrow()), transitions(program));
            assertFalse(transitions(program).isEmpty());
            for (var id : List.of(A, B, C)) assertEquals(1, OperationReceiptCodec.restoreState(encoded.receiptIdentity(), id, false, 91, store::get, LIMITS).epoch());
            assertEquals(group.totalGas(), OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS).trace().stream().mapToLong(GasTraceEntry::subtotal).sum());
            assertEquals(group.checkpointWrites().size(), OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, LIMITS).checkpoints().size());
            assertEquals(executionCalls, fixture.calls, "Cold receipt readers never rerun source handlers");
            assertInvalidGroupMutation(encoded.receiptIdentity(), store, evidence -> {
                var admissions = (com.fasterxml.jackson.databind.node.ArrayNode) evidence.get("admissions");
                var first = admissions.get(0).deepCopy(); admissions.set(0, admissions.get(1)); admissions.set(1, first);
            }, receipt -> { });
        }
    }

    @Test void failureAfterAcceptedJoinColdRestoresEveryOwnedPredecessorUnderOneFailureCapability() {
        try (Fixture fixture = new Fixture(true)) {
            var operations = fixture.evaluate(100_000); assertEquals(1, operations.size()); var failed = operations.get(0);
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.status()); assertEquals(2, failed.admissions().size());
            Map<String, byte[]> store = new HashMap<>(); var encoded = OperationReceiptCodec.encode(failed, store::put, LIMITS);
            var failure = OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(Set.of(A, B, C), failure.ownedDocumentIds()); assertEquals(failed.operationIdentity(), failure.invocationIdentity());
            assertEquals(3, failure.sourcePredecessors().size()); assertNull(failure.rejectedChargeIdentity());
            assertTrue(OperationReceiptCodec.decode(encoded.receiptIdentity(), store::get, LIMITS).sourceProgramIdentity().isEmpty());
            for (var id : List.of(A, B, C)) {
                var before = failed.predecessors().stream().filter(state -> state.documentId().equals(id)).findFirst().orElseThrow();
                var cold = OperationReceiptCodec.restoreState(encoded.receiptIdentity(), id, false, 72, store::get, LIMITS);
                assertEquals(before.blueId(), cold.blueId()); assertEquals(before.epoch(), cold.epoch());
            }
            var settlement = OperationReceiptCodec.restoreSameOrigin(encoded.receiptIdentity(), store::get, LIMITS);
            assertEquals(2, settlement.group().admissions().size()); assertTrue(settlement.rejectedAdmission().isEmpty()); assertTrue(settlement.rejectedCharge().isEmpty());
            assertTrue(OperationReceiptCodec.restoreEffects(encoded.receiptIdentity(), store::get, LIMITS).checkpoints().isEmpty());
        }
    }

    @Test void actualRejectedUnionRetainsInitiatingOwnerAndBudgetsButNoOrdinaryRejectedCharge() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            var operations = fixture.evaluate(15_000); assertEquals(3, operations.size());
            var failed = operations.stream().filter(group -> group.status() != ProcessorStatus.SUCCESS).findFirst().orElseThrow();
            assertEquals(Set.of(B), failed.ownedDocumentIds()); assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.status());
            assertEquals(ProcessorErrorCategory.AtomicScopeGasAdmissionFailure, failed.failure().orElseThrow().diagnostic().category());
            Map<String, byte[]> store = new HashMap<>(); var encoded = OperationReceiptCodec.encode(failed, store::put, LIMITS);
            var settlement = OperationReceiptCodec.restoreSameOrigin(encoded.receiptIdentity(), store::get, LIMITS);
            var rejection = settlement.rejectedAdmission().orElseThrow(); assertTrue(settlement.rejectedCharge().isEmpty());
            assertEquals(15_000, rejection.limit()); assertEquals(Set.of(B), rejection.contributions().get(0).members());
            assertEquals(Set.of(A, C), rejection.contributions().get(1).members());
            assertEquals(failed.failure().orElseThrow().rejectedAdmission().orElseThrow().contributions().stream().map(value -> value.admitted() + value.reserved()).toList(),
                    rejection.contributions().stream().map(value -> value.admitted() + value.reserved()).toList());
            var gas = OperationReceiptCodec.restoreGas(encoded.receiptIdentity(), store::get, LIMITS);
            assertTrue(gas.rejectedCharge().isEmpty()); assertTrue(gas.sameOriginRejectedCharge().isEmpty()); assertTrue(gas.rejectedAdmission().isPresent());
            assertNull(OperationReceiptCodec.restoreSourceFailure(encoded.receiptIdentity(), store::get, LIMITS).rejectedChargeIdentity());
            for (var recovered : operations) {
                var receipt = OperationReceiptCodec.encode(recovered, store::put, LIMITS);
                assertEquals(recovered.operationIdentity(), OperationReceiptCodec.restoreSameOrigin(receipt.receiptIdentity(), store::get, LIMITS).group().identity());
                if (recovered.status() == ProcessorStatus.SUCCESS) assertEquals(recovered.ownedDocumentIds(), OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), store::get, LIMITS).ownedDocumentIds());
            }
            ObjectMapper json = new ObjectMapper(); ObjectNode receipt = (ObjectNode) json.readTree(store.get(encoded.receiptIdentity()));
            ObjectNode group = (ObjectNode) json.readTree(store.get(receipt.get("sameOrigin").textValue()));
            var contributions = group.get("rejectedAdmission").get("contributions");
            var first = contributions.get(0).deepCopy(); ((com.fasterxml.jackson.databind.node.ArrayNode) contributions).set(0, contributions.get(1));
            ((com.fasterxml.jackson.databind.node.ArrayNode) contributions).set(1, first);
            receipt.put("sameOrigin", retain(json.writeValueAsBytes(group), store)); String wrongOwner = retain(json.writeValueAsBytes(receipt), store);
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSameOrigin(wrongOwner, store::get, LIMITS));
            assertInvalidGroupMutation(encoded.receiptIdentity(), store, evidence -> {
                ObjectNode contribution = (ObjectNode) evidence.get("rejectedAdmission").get("contributions").get(0);
                ((ObjectNode) contribution.get("admittedLocal")).put(B.value(), contribution.get("admitted").longValue() + 1);
            }, value -> { });
            assertInvalidGroupMutation(encoded.receiptIdentity(), store, evidence -> {
                ObjectNode contribution = (ObjectNode) evidence.get("rejectedAdmission").get("contributions").get(0);
                ((ObjectNode) contribution.get("reservedLocal")).put(B.value(), contribution.get("reserved").longValue() + 1);
            }, value -> { });
            assertInvalidGroupMutation(encoded.receiptIdentity(), store, evidence -> {
                ((ObjectNode) evidence.get("rejectedAdmission").get("contributions").get(0)).put("admitted", 15_001);
            }, value -> { });
            assertInvalidGroupMutation(encoded.receiptIdentity(), store, evidence -> { }, value -> {
                ((ObjectNode) value.get("gas").get("diagnostic")).put("category", "RuntimeExecutionFailure");
            });
        }
    }

    private static void assertInvalidGroupMutation(String key, Map<String, byte[]> store,
            java.util.function.Consumer<ObjectNode> changeEvidence, java.util.function.Consumer<ObjectNode> changeReceipt) {
        ObjectNode receipt = (ObjectNode) OperationReceiptCodec.json(store.get(key));
        ObjectNode evidence = (ObjectNode) OperationReceiptCodec.json(store.get(receipt.get("sameOrigin").textValue()));
        changeEvidence.accept(evidence); changeReceipt.accept(receipt);
        receipt.put("sameOrigin", retain(OperationReceiptCodec.bytes(evidence), store));
        String altered = retain(OperationReceiptCodec.bytes(receipt), store);
        assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSameOrigin(altered, store::get, LIMITS));
    }
    private static List<String> transitions(SourceObservationProgram program) {
        return program.steps().stream().flatMap(step -> step.actions().stream()).filter(SourceObservationProgram.Patch.class::isInstance)
                .map(SourceObservationProgram.Patch.class::cast).map(SourceObservationProgram.Patch::transitionIdentity).toList();
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> calls = new ArrayList<>(); final String[] targets = new String[2];
        final BlueLanguageRuntime language; final DocumentProcessor owner; final boolean fatal;
        Fixture(boolean fatal) {
            this.fatal = fatal;
            var registry = ContractProcessorRegistryBuilder.create().register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                            calls.add(context.contractKey());
                            switch (context.contractKey()) {
                                case "producer" -> { charge(context); context.applyPatch(JsonPatch.replace("/x", new Node().value(BigInteger.ONE)));
                                    context.applyPatch(JsonPatch.add("/a", new Node().blueId(targets[0]))); }
                                case "joinThirdParty" -> { context.applyPatch(JsonPatch.add("/c", new Node().blueId(targets[1]))); charge(context); }
                                case "aOwn", "cOwn" -> context.applyPatch(JsonPatch.replace("/seen", new Node().value(BigInteger.valueOf(5))));
                                case "zzFailure" -> throw new ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "joined receipt fixture failure");
                                default -> throw new AssertionError(context.contractKey());
                            }
                        }
                    }).build();
            NodeProvider provider = id -> CHANNEL_ID.equals(id) ? List.of(CHANNEL.clone()) : HANDLER_ID.equals(id)
                    ? List.of(HANDLER.clone()) : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id);
            language = BlueLanguageRuntime.create(provider, blue.language.api.BlueCachePolicy.disabled(), Map.of());
            owner = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(provider).snapshotStore(new Snapshots(language)).build();
        }
        List<SameOriginOperationResult> evaluate(long cap) {
            var environment = ClosureEvidenceFactory.environment(owner, hash('a'), hash('b'), "group-lineage", "group-binding", "group-provider", "group-order", "group-limits", GasSchedule.contracts10().portableLimits());
            Node bBody = new Node().name("B").properties("x", new Node().value(BigInteger.ZERO)).contracts(new Node()
                    .properties("source", typed(CHANNEL_ID)).properties("producer", handler("source")).properties("embedded", embedded("/a")));
            if (fatal) bBody.getContracts().properties("zzFailure", handler("source"));
            var b = state(B, bBody);
            var a = state(A, new Node().name("A").properties("b", new Node().blueId(b.blueId())).properties("seen", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("source", typed(CHANNEL_ID)).properties("aOwn", handler("source"))
                            .properties("updates", typed(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL).properties("path", new Node().value("/b/x")))
                            .properties("joinThirdParty", handler("updates")).properties("embedded", embedded("/b", "/c"))));
            var c = state(C, new Node().name("C").properties("a", new Node().blueId(a.blueId())).properties("seen", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("source", typed(CHANNEL_ID)).properties("cOwn", handler("source")).properties("embedded", embedded("/a"))));
            targets[0] = a.blueId(); targets[1] = c.blueId();
            String policy = environment.managedBindingPolicyIdentity();
            var bindings = List.of(binding(policy, A, "/b", b, true), binding(policy, C, "/a", a, true),
                    binding(policy, A, "/c", c, false), binding(policy, B, "/a", a, false));
            var snapshot = ClosureEvidenceFactory.affectedClosure(1, List.of(a, b, c), bindings,
                    List.of(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(c)), List.of(A, B, C));
            Node event = new Node().name("joined source input");
            var cause = ClosureEvidenceFactory.externalCause(event, DirectBlueIdCalculator.calculateBlueId(event), ExternalOrderKey.of(List.of(1L, "entry")), environment.externalOrderPolicyIdentity());
            var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(delivery(B, 0), delivery(A, 1), delivery(C, 2)), ClosureEvidenceFactory.executionPolicy(cap, Map.of(), "joined-codec"), environment);
            var attachments = new SameOriginAttachmentPolicy(List.of(new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, A, bindings.get(2).occurrenceIdentity(), C, c.blueId()),
                    new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, B, bindings.get(3).occurrenceIdentity(), A, a.blueId())));
            try (var contracts = new BlueClosureContracts(owner)) { var result = contracts.processSameOrigin(input, attachments); assertTrue(result.complete()); return result.operations(); }
        }
        @Override public void close() { owner.close(); language.close(); }
    }
    private static void charge(ProcessorExecutionContext context) { var ledger = context.newRuntimeGasLedger("joined-receipt-fixture", Map.of("unit", 1L)); ledger.charge("unit", 10_000); context.submitRuntimeGasLedger(ledger); }
    private static ManagedDocumentSnapshot state(DocumentId id, Node body) {
        body.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(DirectBlueIdCalculator.calculateBlueId(body))));
        return new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, true, 0, 1);
    }
    private static ManagedOccurrenceBinding binding(String policy, DocumentId source, String path, ManagedDocumentSnapshot target, boolean active) {
        return ManagedOccurrenceBinding.derived(policy, source, ScopeAddress.embedded(path, 1), target.documentId(), target.blueId(), active, null);
    }
    private static DirectLogicalDelivery delivery(DocumentId id, long ordinal) { return new DirectLogicalDelivery(ManagedScopeKey.root(id), "source", "logical", ordinal); }
    private static Node typed(String id) { return new Node().type(new Node().blueId(id)); }
    private static Node handler(String channel) { return typed(HANDLER_ID).properties("channel", new Node().value(channel)); }
    private static Node embedded(String... paths) { return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(Arrays.stream(paths).map(path -> new Node().value(path)).toList())); }
    private static String hash(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static String retain(byte[] value, Map<String, byte[]> store) { String key = FrozenNodeEvidenceCodec.digest(value); store.put(key, value); return key; }
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
                @Override public String checkpointDomainDiscriminator(TestChannel channel) { return "joined-codec"; }
            };
        }
    }
    private record Snapshots(BlueLanguageRuntime language) implements ProcessingSnapshotManager {
        @Override public ResolvedSnapshot fromDocument(Node document) { return language.snapshots().resolve(document.clone()); }
        @Override public ResolvedSnapshot fromDocumentPreservingPaths(Node document, Collection<String> paths) { return language.snapshots().resolvePreservingPaths(document.clone(), paths); }
        @Override public ResolvedSnapshot fromDocumentTransientPreservingPaths(Node document, Collection<String> paths) { return fromDocumentPreservingPaths(document, paths); }
        @Override public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) { return language.patching().apply(snapshot, patch); }
        @Override public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) { return language.snapshots().cache(snapshot); }
    }
}
