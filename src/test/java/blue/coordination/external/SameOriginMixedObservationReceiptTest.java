package blue.coordination.external;

import blue.language.api.BlueCachePolicy;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguageRuntime;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual producer runs, actual failed observation, and an owning cyclic finalization; no fabricated group result. */
class SameOriginMixedObservationReceiptTest {
    private static final DocumentId A = new DocumentId("a"), C = new DocumentId("c"), S = new DocumentId("s");
    private static final Node CHANNEL = new Node().name("Mixed observation external channel"), HANDLER = new Node().name("Mixed observation handler");
    private static final String CHANNEL_ID = id(CHANNEL), HANDLER_ID = id(HANDLER);
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void jointlyFailedConsumersRestoreDifferentSuccessfulPinsForTheSameOfferedSourceOperation() {
        try (Fixture fixture = new Fixture()) {
            ManagedDocumentSnapshot s0 = state(S, initialized(new Node().name("Source").properties("counter", new Node().value(BigInteger.ZERO))
                    .contracts(new Node().properties("source", typed(CHANNEL_ID)).properties("increment", handler("source")))), 0, 0);
            SameOriginOperationResult firstSource = fixture.source(s0, 1);
            ResultingDocument firstState = firstSource.resultingDocuments().get(0);
            ManagedDocumentSnapshot s1 = new ManagedDocumentSnapshot(firstState.documentId(), firstState.afterBlueId(),
                    firstState.document(), firstState.initialized(), firstState.terminated(), firstState.publicRoot(),
                    firstState.epoch(), firstState.componentGeneration());
            SameOriginOperationResult secondSource = fixture.source(s1, 2);
            assertEquals(2, fixture.sourceCalls);
            assertEquals(BigInteger.ONE, s1.document().getProperties().get("counter").getValue());
            Map<String, byte[]> store = new HashMap<>();
            String firstSourceReceipt = OperationReceiptCodec.encode(firstSource, store::put, LIMITS).receiptIdentity();
            String secondSourceReceipt = OperationReceiptCodec.encode(secondSource, store::put, LIMITS).receiptIdentity();
            SourceObservationProgram firstProgram = OperationReceiptCodec.restoreSourceProgram(firstSourceReceipt, store::get, LIMITS);
            SourceObservationProgram secondProgram = OperationReceiptCodec.restoreSourceProgram(secondSourceReceipt, store::get, LIMITS);

            ManagedDocumentSnapshot a0 = state(A, consumer("A", s0.blueId(), true), 0, 0);
            ManagedOccurrenceBinding aSource = fixture.binding(A, "/source", S, s0.blueId());
            AffectedClosureSnapshot firstCut = ClosureEvidenceFactory.affectedClosure(0, List.of(a0, s0), List.of(aSource),
                    List.of(ClosureEvidenceFactory.acyclicComponent(s0), ClosureEvidenceFactory.acyclicComponent(a0)), List.of(A, S));
            SameOriginOperationResult failedA = fixture.observe(firstCut, firstProgram, Map.of());
            assertEquals(Set.of(A), failedA.ownedDocumentIds()); assertEquals(ProcessorStatus.RUNTIME_FATAL, failedA.status());
            String firstFailureReceipt = OperationReceiptCodec.encode(failedA, store::put, LIMITS).receiptIdentity();
            SourceObservationGap gapA = OperationReceiptCodec.restoreSourceGap(firstFailureReceipt, A, S, firstSourceReceipt, store::get, LIMITS);
            assertEquals(s0.blueId(), gapA.observedBlueId()); assertEquals(0, gapA.observedEpoch());

            // The current exact component contains two consumers with independent prior views:
            // A retained S0 after its failed observation; C already successfully observed S1.
            AffectedClosureSnapshot mixed = fixture.cyclicConsumers(a0, s0, s1);
            assertEquals(ComponentKind.CYCLIC, mixed.component(A).kind());
            assertEquals(s0.blueId(), mixed.managedDocument(A).document().getProperties().get("source").getBlueId());
            assertEquals(s1.blueId(), mixed.managedDocument(C).document().getProperties().get("source").getBlueId());
            int beforeJointFailure = fixture.sourceCalls;
            SameOriginOperationResult failed = fixture.observe(mixed, secondProgram, Map.of(S, List.of(gapA)));
            assertEquals(Set.of(A, C), failed.ownedDocumentIds()); assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.status());
            assertEquals(beforeJointFailure, fixture.sourceCalls, "The already committed source program is interpreted without executing its handler again");
            assertEquals(Map.of(S, secondSource.operationIdentity()), failed.consumedSourceOperations());
            assertEquals(2, failed.observedSources().size());
            assertEquals(s0.blueId(), failed.observedSource(A, S).orElseThrow().blueId());
            assertEquals(0, failed.observedSource(A, S).orElseThrow().epoch());
            assertEquals(s1.blueId(), failed.observedSource(C, S).orElseThrow().blueId());
            assertEquals(1, failed.observedSource(C, S).orElseThrow().epoch());
            for (ResultingDocument result : failed.resultingDocuments()) {
                assertEquals(mixed.managedDocument(result.documentId()).blueId(), result.afterBlueId());
                assertEquals(mixed.managedDocument(result.documentId()).epoch(), result.epoch());
            }
            assertTrue(failed.events().isEmpty()); assertTrue(failed.sourceProgram().isEmpty());
            String failureReceipt = OperationReceiptCodec.encode(failed, store::put, LIMITS).receiptIdentity();
            var receipt = OperationReceiptCodec.decode(failureReceipt, store::get, LIMITS);
            assertEquals(2, receipt.observedSources().size());
            SourceObservationGap coldA = OperationReceiptCodec.restoreSourceGap(failureReceipt, A, S, secondSourceReceipt, store::get, LIMITS);
            SourceObservationGap coldC = OperationReceiptCodec.restoreSourceGap(failureReceipt, C, S, secondSourceReceipt, store::get, LIMITS);
            assertEquals(s0.blueId(), coldA.observedBlueId()); assertEquals(0, coldA.observedEpoch());
            assertEquals(s1.blueId(), coldC.observedBlueId()); assertEquals(1, coldC.observedEpoch());
            assertEquals(coldA.offeredSourceOperation(), coldC.offeredSourceOperation());
            assertEquals(failed.operationIdentity(), coldA.failedInvocationIdentity());
            assertEquals(failed.operationIdentity(), coldC.failedInvocationIdentity());
            assertTrue(OperationReceiptCodec.restoreReadPins(failureReceipt, store::get, LIMITS).isEmpty());
            assertThrows(InvalidExecutionEvidenceException.class, () -> OperationReceiptCodec.restoreSourceGap(
                    failureReceipt, S, S, secondSourceReceipt, store::get, LIMITS));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BlueLanguageRuntime language; final DocumentProcessor owner; final BlueClosureContracts contracts;
        final ClosureEnvironment environment; final ExecutionPolicy policy; int sourceCalls;
        Fixture() {
            var registry = ContractProcessorRegistryBuilder.create().register(CHANNEL_ID, CHANNEL, new TestChannelProcessor())
                    .register(HANDLER_ID, HANDLER, new HandlerProcessor<TestHandler>() {
                        @Override public Class<TestHandler> contractType() { return TestHandler.class; }
                        @Override public void execute(TestHandler handler, ProcessorExecutionContext context) {
                            if ("increment".equals(context.contractKey())) {
                                sourceCalls++;
                                BigInteger before = (BigInteger) context.resolvedFrozenAt("/counter").getValue();
                                context.applyPatch(JsonPatch.replace("/counter", new Node().value(before.add(BigInteger.ONE))));
                                context.emitEvent(new Node().value("source changed"));
                            } else if ("reject".equals(context.contractKey())) {
                                throw new ProcessorFailureException(ProcessorErrorCategory.RuntimeExecutionFailure, "Consumer rejects the source event");
                            }
                        }
                    }).build();
            NodeProvider provider = ref -> CHANNEL_ID.equals(ref) ? List.of(CHANNEL.clone()) : HANDLER_ID.equals(ref) ? List.of(HANDLER.clone())
                    : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(ref);
            language = BlueLanguageRuntime.create(provider, BlueCachePolicy.disabled(), Map.of());
            owner = DocumentProcessor.builder().nodeProvider(provider).runtimeRegistry(registry).snapshotStore(new Snapshots(language)).build();
            contracts = new BlueClosureContracts(owner);
            environment = ClosureEvidenceFactory.environment(owner, "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                    "mixed-lineage", "mixed-binding", "mixed-provider", "mixed-order", "mixed-limits", GasSchedule.contracts10().portableLimits());
            policy = ClosureEvidenceFactory.executionPolicy(100_000, Map.of(), "mixed-policy");
        }
        SameOriginOperationResult source(ManagedDocumentSnapshot source, long position) {
            Node event = new Node().value("entry-" + position);
            var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(source), List.of(), List.of(ClosureEvidenceFactory.acyclicComponent(source)), List.of(S));
            var cause = ClosureEvidenceFactory.externalCause(event, id(event), ExternalOrderKey.of(List.of(position, id(event))), environment.externalOrderPolicyIdentity());
            var input = ClosureEvidenceFactory.processClosure(snapshot, cause, List.of(new DirectLogicalDelivery(ManagedScopeKey.root(S), "source", "logical", 0)), policy, environment);
            var result = contracts.processSameOrigin(input); assertTrue(result.complete()); assertEquals(1, result.operations().size());
            assertEquals(ProcessorStatus.SUCCESS, result.operations().get(0).status()); return result.operations().get(0);
        }
        SameOriginOperationResult observe(AffectedClosureSnapshot snapshot, SourceObservationProgram program, Map<DocumentId, List<SourceObservationGap>> gaps) {
            var input = ClosureEvidenceFactory.processClosure(snapshot, program.externalCause(),
                    List.of(new DirectLogicalDelivery(ManagedScopeKey.root(S), "source", "logical", 0)), policy, environment);
            var result = contracts.processSameOrigin(input, SameOriginAttachmentPolicy.empty(), List.of(program), gaps, List.of());
            assertTrue(result.complete(), () -> result.requiredExactBlueIds().toString());
            assertEquals(1, result.operations().size()); return result.operations().get(0);
        }
        AffectedClosureSnapshot cyclicConsumers(ManagedDocumentSnapshot a0, ManagedDocumentSnapshot s0, ManagedDocumentSnapshot s1) {
            String aPlaceholder = id(new Node().name("A placeholder")), cPlaceholder = id(new Node().name("C placeholder"));
            Node a = a0.document().properties("c", new Node().blueId(cPlaceholder));
            a.getContracts().properties("embedded", embedded("/source", "/c"));
            Node c = consumer("C", s1.blueId(), false).properties("a", new Node().blueId(aPlaceholder));
            c.getContracts().properties("embedded", embedded("/source", "/a"));
            ManagedOccurrenceBinding cSource = binding(C, "/source", S, s1.blueId());
            List<ManagedOccurrenceBinding> bindings = List.of(binding(A, "/source", S, s0.blueId()), cSource,
                    binding(A, "/c", C, cPlaceholder), binding(C, "/a", A, aPlaceholder));
            ManagedReadPin pin = ManagedReadPin.fromExactEvidence(S, s1.blueId(), s1.document(), null);
            var finalized = new ComponentFinalizationKernel().finalizeComponents(new ComponentFinalizationInput(
                    ManagedDocumentGraph.fromBindings(List.of(A, C, S), bindings), Map.of(A, 0L, C, 0L, S, 0L),
                    Map.of(A, a, C, c, S, s0.document()), bindings, Map.of(cSource.occurrenceIdentity(), pin)));
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            for (DocumentId member : List.of(A, C, S)) {
                var exact = finalized.document(member);
                documents.add(new ManagedDocumentSnapshot(member, exact.blueId(), exact.document(), true, false, true,
                        member.equals(S) ? s0.epoch() : 1L, exact.componentGeneration()));
            }
            return ClosureEvidenceFactory.affectedClosure(1, documents, finalized.finalizedGraph().bindings(),
                    finalized.components().stream().map(FinalizedComponentEvidence::component).toList(), List.of(A, C, S), List.of(pin));
        }
        ManagedOccurrenceBinding binding(DocumentId consumer, String path, DocumentId source, String ref) {
            return ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), consumer, ScopeAddress.embedded(path, 1), source, ref, true, null);
        }
        @Override public void close() { contracts.close(); owner.close(); language.close(); }
    }
    private static Node consumer(String name, String sourceRef, boolean rejects) {
        Node result = new Node().name(name).properties("source", new Node().blueId(sourceRef)).contracts(new Node()
                .properties("embedded", embedded("/source")).properties("events", typed(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL)
                        .properties("sourcePath", new Node().value("/source"))));
        if (rejects) result.getContracts().properties("reject", handler("events"));
        return initialized(result);
    }
    private static Node initialized(Node body) {
        body.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER).properties("document", new Node().blueId(id(body)))); return body;
    }
    private static ManagedDocumentSnapshot state(DocumentId owner, Node body, long epoch, long generation) {
        return new ManagedDocumentSnapshot(owner, id(body), body, true, false, true, epoch, generation);
    }
    private static Node typed(String type) { return new Node().type(new Node().blueId(type)); }
    private static Node handler(String channel) { return typed(HANDLER_ID).properties("channel", new Node().value(channel)); }
    private static Node embedded(String... paths) { return typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(Arrays.stream(paths).map(path -> new Node().value(path)).toList())); }
    private static String id(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
    public static final class TestChannel extends ChannelContract { }
    public static final class TestHandler extends HandlerContract { }
    private static final class TestChannelProcessor implements ChannelProcessor<TestChannel> {
        @Override public Class<TestChannel> contractType() { return TestChannel.class; }
        @Override public ExternalChannelSubscriptionFunctions<TestChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<>() {
                @Override public List<String> channelKeys(TestChannel value) { return List.of("test"); }
                @Override public boolean preselects(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public boolean accepts(TestChannel value, Node event, ExternalChannelFunctionContext context) { return true; }
                @Override public String logicalDeliveryKey(TestChannel value, Node event, Node payload, ExternalChannelFunctionContext context) { return "logical"; }
                @Override public String checkpointDomainDiscriminator(TestChannel value) { return "mixed-observation"; }
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
