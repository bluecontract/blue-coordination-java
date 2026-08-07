package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.BlueContracts;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public Contracts equivalence over nested stable-key collection scopes.
 */
final class CoordinationNestedIndexedCurrentRootDeliveryEquivalenceTest {

    private static final String AGREEMENT_A =
            "/agreements/agreement-a";
    private static final String AGREEMENT_B =
            "/agreements/agreement-b";
    private static final String LESSON_A =
            AGREEMENT_A + "/lessons/lesson-a";
    private static final String LESSON_B =
            AGREEMENT_A + "/lessons/lesson-b";
    private static final String LESSON_C =
            AGREEMENT_B + "/lessons/lesson-c";
    private static final String CANCELLATION_A =
            LESSON_A + "/cancellations/cancel-a";
    private static final String PAYMENT_A =
            AGREEMENT_A + "/payments/payment-a";

    private static final Node CHANNEL_TYPE =
            new Node().name("Nested delivery Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);

    @Test
    void shouldMatchIndexedAndCurrentRootPlansForNestedCollections() {
        // given
        try (Fixture fixture = Fixture.open()) {
            Prepared prepared = fixture.prepare();

            // when
            PlatformProcessInvocation indexedInvocation =
                    fixture.host.preparePlatformCommitInvocation(
                            prepared.indexed,
                            fixture.provider);
            PlatformProcessInvocation currentRootInvocation =
                    fixture.host.preparePlatformCommitInvocation(
                            prepared.currentRoot,
                            fixture.provider);

            // then
            assertEquals(
                    Arrays.asList(
                            AGREEMENT_A,
                            LESSON_A,
                            CANCELLATION_A,
                            LESSON_B,
                            PAYMENT_A,
                            AGREEMENT_B,
                            LESSON_C),
                    sortedScopePaths(prepared.activeIntervals));
            assertEquals(
                    Collections.singletonList(CANCELLATION_A),
                    deliveryScopePaths(
                            prepared.indexed.deliveryPlan()));
            assertPlansEqual(
                    prepared.indexed.deliveryPlan(),
                    prepared.currentRoot);
            assertSame(
                    prepared.indexed.deliveryPlan(),
                    indexedInvocation.deliveryPlan());
            assertSame(
                    prepared.currentRoot,
                    currentRootInvocation.deliveryPlan());
            assertTrue(prepared.indexed.deliveryPlan()
                    .availableExactNodeBlueIds()
                    .containsAll(prepared.indexed.deliveryPlan()
                            .requiredExactNodeBlueIds()));
        }
    }

    @Test
    void shouldProduceEquivalentNestedPlatformResultGasAndNamedTrace() {
        // given
        try (Fixture fixture = Fixture.open()) {
            Prepared prepared = fixture.prepare();
            PlatformProcessInvocation indexedInvocation =
                    fixture.host.preparePlatformCommitInvocation(
                            prepared.indexed,
                            fixture.provider);
            PlatformProcessInvocation currentRootInvocation =
                    fixture.host.preparePlatformCommitInvocation(
                            prepared.currentRoot,
                            fixture.provider);
            Set<String> forbiddenColdBranchBlueIds =
                    coldBranchBlueIds(prepared.root);
            ExternalDeliveryPlanDeriver indexedVerifier =
                    (root, event) -> prepared.indexed.deliveryPlan();
            ExternalDeliveryPlanDeriver independentVerifier =
                    fixture.host.currentRootDeliveryPlanDeriver(
                            Fixture.ROOT_REVISION,
                            Fixture.EVENT_ORDER,
                            prepared.activeIntervals);

            // when
            PlatformProcessingResult indexedCommit;
            PlatformProcessingResult currentRootCommit;
            List<String> indexedProviderDemands;
            List<String> currentRootProviderDemands;
            ProcessingDebugResult indexedDebug;
            ProcessingDebugResult currentRootDebug;
            try (BlueContracts indexedCommitContracts =
                         fixture.newContracts(indexedVerifier);
                 BlueContracts currentRootCommitContracts =
                         fixture.newContracts(independentVerifier);
                 DocumentProcessor indexedTraceProcessor =
                         fixture.newTraceProcessor(indexedVerifier);
                 DocumentProcessor currentRootTraceProcessor =
                         fixture.newTraceProcessor(independentVerifier)) {
                fixture.provider.clearDemands();
                indexedCommit = new CoordinationContractsHost(
                        indexedCommitContracts).processForPlatformCommit(
                            prepared.root,
                            prepared.event,
                            indexedInvocation);
                indexedProviderDemands = fixture.provider.demands();
                fixture.provider.clearDemands();
                currentRootCommit = new CoordinationContractsHost(
                        currentRootCommitContracts).processForPlatformCommit(
                            prepared.root,
                            prepared.event,
                            currentRootInvocation);
                currentRootProviderDemands = fixture.provider.demands();
                indexedDebug = indexedTraceProcessor
                        .processDocumentWithTrace(
                            prepared.root,
                            prepared.event);
                currentRootDebug = currentRootTraceProcessor
                        .processDocumentWithTrace(
                            prepared.root,
                            prepared.event);
            }

            // then
            assertSuccessfulEquivalentResults(
                    indexedCommit.processResult(),
                    currentRootCommit.processResult());
            assertEquals(
                    indexedCommit.commitCompanion()
                            .expectedRootRevision(),
                    currentRootCommit.commitCompanion()
                            .expectedRootRevision());
            assertEquals(
                    indexedCommit.commitCompanion().eventOrderKey(),
                    currentRootCommit.commitCompanion().eventOrderKey());
            assertSuccessfulEquivalentResults(
                    indexedDebug.processResult(),
                    currentRootDebug.processResult());
            assertEquals(
                    gasProjection(indexedDebug.trace()),
                    gasProjection(currentRootDebug.trace()));
            assertEquals(
                    traceProjection(indexedDebug.trace()),
                    traceProjection(currentRootDebug.trace()));
            assertEquals(
                    indexedDebug.trace().semanticDemands(),
                    currentRootDebug.trace().semanticDemands());
            assertEquals(
                    Collections.singletonList(CANCELLATION_A),
                    externalDeliveryScopes(indexedDebug.trace()));
            assertNoForbiddenDemands(
                    forbiddenColdBranchBlueIds,
                    indexedProviderDemands,
                    indexedDebug.trace().semanticDemands());
            assertNoForbiddenDemands(
                    forbiddenColdBranchBlueIds,
                    currentRootProviderDemands,
                    currentRootDebug.trace().semanticDemands());
        }
    }

    private static void assertPlansEqual(
            ExternalDeliveryPlan indexed,
            ExternalDeliveryPlan currentRoot) {
        assertEquals(indexed.managedRootRevision(),
                currentRoot.managedRootRevision());
        assertEquals(indexed.indexedRootRevision(),
                currentRoot.indexedRootRevision());
        assertEquals(indexed.eventOrderKey(),
                currentRoot.eventOrderKey());
        assertEquals(deliveryProjection(indexed.deliveries()),
                deliveryProjection(currentRoot.deliveries()));
        assertEquals(indexed.activeSubscriptionIntervals(),
                currentRoot.activeSubscriptionIntervals());
        assertEquals(indexed.availableExactNodeBlueIds(),
                currentRoot.availableExactNodeBlueIds());
        assertEquals(indexed.requiredExactNodeBlueIds(),
                currentRoot.requiredExactNodeBlueIds());
        assertEquals(indexed.exactRuntimeState(),
                currentRoot.exactRuntimeState());
    }

    private static void assertSuccessfulEquivalentResults(
            DocumentProcessingResult indexed,
            DocumentProcessingResult currentRoot) {
        assertEquals(ProcessorStatus.SUCCESS, indexed.status(),
                diagnostic(indexed));
        assertEquals(ProcessorStatus.SUCCESS, currentRoot.status(),
                diagnostic(currentRoot));
        assertEquals(
                NodeWireForm.get(indexed.document()),
                NodeWireForm.get(currentRoot.document()));
        assertEquals(
                nodeWireForms(indexed.events()),
                nodeWireForms(currentRoot.events()));
        assertEquals(indexed.totalGas(), currentRoot.totalGas());
    }

    private static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() != null
                ? result.diagnostic().message()
                : null;
    }

    private static List<String> sortedScopePaths(
            List<SubscriptionDelta.Entry> intervals) {
        List<String> result = new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            result.add(interval.scopePath());
        }
        Collections.sort(result);
        return result;
    }

    private static List<String> deliveryScopePaths(
            ExternalDeliveryPlan plan) {
        List<String> result = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery : plan.deliveries()) {
            result.add(delivery.scopePath());
        }
        return result;
    }

    private static List<String> deliveryProjection(
            List<ExternalDeliverySnapshot> deliveries) {
        List<String> result = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            result.add(
                    delivery.scopePath()
                            + "|" + delivery.channelKey()
                            + "|" + delivery.order()
                            + "|" + delivery.sourceContributionNodeBlueIds()
                            + "|" + delivery.effectiveTypeBlueId()
                            + "|" + delivery.subscriptionKeys()
                            + "|" + delivery.checkpointDomainBlueId()
                            + "|" + delivery.checkpointSubjectBlueId()
                            + "|" + delivery.activationStartExclusive()
                            + "|" + delivery.activationEndInclusive());
        }
        return result;
    }

    private static List<String> externalDeliveryScopes(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record
                : trace.records(
                ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY)) {
            result.add(record.scopePath());
        }
        return result;
    }

    private static List<String> nodeWireForms(List<Node> nodes) {
        List<String> result = new ArrayList<>();
        for (Node node : nodes) {
            result.add(String.valueOf(NodeWireForm.get(node)));
        }
        return result;
    }

    private static List<String> gasProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            result.add(
                    entry.sequence()
                            + "|" + entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.scopePath()
                            + "|" + entry.contractKey()
                            + "|" + entry.logicalPath()
                            + "|" + entry.reason());
        }
        return result;
    }

    private static List<String> traceProjection(
            ProcessingConformanceTrace trace) {
        List<String> result = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            Node node = record.node();
            result.add(
                    record.sequence()
                            + "|" + record.kind()
                            + "|" + record.scopePath()
                            + "|" + record.contractKey()
                            + "|" + record.logicalPath()
                            + "|" + record.details()
                            + "|" + (node != null
                            ? DirectBlueIdCalculator.calculateBlueId(node)
                            : null));
        }
        return result;
    }

    private static Set<String> coldBranchBlueIds(Node root) {
        Set<String> result = new LinkedHashSet<>();
        result.add(DirectBlueIdCalculator.calculateBlueId(
                root.getAsNode(LESSON_B)));
        result.add(DirectBlueIdCalculator.calculateBlueId(
                root.getAsNode(PAYMENT_A)));
        result.add(DirectBlueIdCalculator.calculateBlueId(
                root.getAsNode(AGREEMENT_B)));
        result.add(DirectBlueIdCalculator.calculateBlueId(
                root.getAsNode(LESSON_C)));
        return result;
    }

    private static void assertNoForbiddenDemands(
            Set<String> forbidden,
            List<String> providerDemands,
            List<String> semanticDemands) {
        for (String blueId : forbidden) {
            assertFalse(providerDemands.contains(blueId),
                    "forbidden provider demand " + blueId);
            assertFalse(semanticDemands.contains(blueId),
                    "forbidden semantic demand " + blueId);
        }
    }

    private static List<ExternalSubscriptionOccurrenceKey> candidates(
            List<SubscriptionDelta.Entry> intervals,
            String subscriptionKey) {
        List<ExternalSubscriptionOccurrenceKey> result =
                new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            if (!interval.subscriptionKeys().contains(subscriptionKey)) {
                continue;
            }
            result.add(ExternalSubscriptionOccurrenceKey.of(
                    interval.scopePath(),
                    interval.channelKey()));
        }
        return result;
    }

    private static Node nestedRoot() {
        Node cancelA = scopedNode("Cancellation A", "cancel-a");
        Node lessonA = scopedNode("Lesson A", "lesson-a")
                .properties(
                        "cancellations",
                        objectMap("cancel-a", cancelA));
        lessonA.getContracts().properties(
                "embedded",
                processEmbeddedCollections("/cancellations"));
        Node lessonB = scopedNode("Lesson B", "lesson-b");
        Node lessonC = scopedNode("Lesson C", "lesson-c");
        Node paymentA = scopedNode("Payment A", "payment-a");

        Map<String, Node> agreementALessons = new LinkedHashMap<>();
        agreementALessons.put("lesson-b", lessonB);
        agreementALessons.put("lesson-a", lessonA);
        Node agreementA = scopedNode("Agreement A", "agreement-a")
                .properties(
                        "lessons", new Node().properties(agreementALessons),
                        "payments", objectMap("payment-a", paymentA));
        agreementA.getContracts().properties(
                "embedded",
                processEmbeddedCollections("/lessons", "/payments"));

        Node agreementB = scopedNode("Agreement B", "agreement-b")
                .properties(
                        "lessons", objectMap("lesson-c", lessonC));
        agreementB.getContracts().properties(
                "embedded",
                processEmbeddedCollections("/lessons"));

        Map<String, Node> agreements = new LinkedHashMap<>();
        agreements.put("agreement-b", agreementB);
        agreements.put("agreement-a", agreementA);
        return new Node()
                .name("Nested delivery Root")
                .properties(
                        "agreements",
                        new Node().properties(agreements))
                .contracts(new Node().properties(
                        "embedded",
                        processEmbeddedCollections("/agreements")));
    }

    private static Node scopedNode(String name, String binding) {
        return new Node()
                .name(name)
                .contracts(new Node().properties(
                        "timeline", channel(binding)));
    }

    private static Node channel(String binding) {
        return new Node()
                .type(new Node().blueId(CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "binding", new Node().value(binding));
    }

    private static Node processEmbeddedCollections(
            String... collectionPaths) {
        List<Node> paths = new ArrayList<>();
        for (String path : collectionPaths) {
            paths.add(new Node().value(path));
        }
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths", new Node().items(paths));
    }

    private static Node objectMap(String key, Node value) {
        Map<String, Node> entries = new LinkedHashMap<>();
        entries.put(key, value);
        return new Node().properties(entries);
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.<Object>singletonList(
                        BigInteger.valueOf(value)));
    }

    public static final class NestedChannel extends ChannelContract {
        private String binding;

        public NestedChannel() {
        }

        public String getBinding() {
            return binding;
        }

        public void setBinding(String binding) {
            this.binding = binding;
        }
    }

    private static final class NestedChannelProcessor
            implements ChannelProcessor<NestedChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                NestedChannel> FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<NestedChannel>() {
                    @Override
                    public List<String> channelKeys(
                            NestedChannel contract) {
                        return Collections.singletonList(
                                contract.getBinding());
                    }

                    @Override
                    public List<String> channelKeys(
                            NestedChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                context.scopePath()
                                        + "@"
                                        + contract.getBinding());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            NestedChannel contract) {
                        return contract.getBinding();
                    }
                };

        @Override
        public Class<NestedChannel> contractType() {
            return NestedChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<NestedChannel>
        externalSubscriptionFunctions() {
            return FUNCTIONS;
        }

        @Override
        public boolean matches(
                NestedChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static final class Prepared {
        private final Node root;
        private final Node event;
        private final List<SubscriptionDelta.Entry> activeIntervals;
        private final IndexedDeliveryPreparation indexed;
        private final ExternalDeliveryPlan currentRoot;

        private Prepared(
                Node root,
                Node event,
                List<SubscriptionDelta.Entry> activeIntervals,
                IndexedDeliveryPreparation indexed,
                ExternalDeliveryPlan currentRoot) {
            this.root = root;
            this.event = event;
            this.activeIntervals = activeIntervals;
            this.indexed = indexed;
            this.currentRoot = currentRoot;
        }
    }

    private static final class RecordingNodeProvider
            implements NodeProvider {
        private final NodeProvider delegate;
        private final List<String> demands = new ArrayList<>();

        private RecordingNodeProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized List<Node> fetchByBlueId(String blueId) {
            demands.add(blueId);
            return delegate.fetchByBlueId(blueId);
        }

        private synchronized void clearDemands() {
            demands.clear();
        }

        private synchronized List<String> demands() {
            return Collections.unmodifiableList(
                    new ArrayList<>(demands));
        }
    }

    private static final class Fixture implements AutoCloseable {
        private static final long ROOT_REVISION = 12L;
        private static final ExternalOrderKey ACTIVATION_ORDER = order(10L);
        private static final ExternalOrderKey EVENT_ORDER = order(20L);

        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final CoordinationContractsHost host;
        private final DocumentProcessor traceProcessor;
        private final RecordingNodeProvider provider;

        private Fixture(
                BlueLanguage language,
                BlueContracts contracts,
                CoordinationContractsHost host,
                DocumentProcessor traceProcessor,
                RecordingNodeProvider provider) {
            this.language = language;
            this.contracts = contracts;
            this.host = host;
            this.traceProcessor = traceProcessor;
            this.provider = provider;
        }

        private static Fixture open() {
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .registerDefaults()
                            .register(
                                    CHANNEL_TYPE_BLUE_ID,
                                    CHANNEL_TYPE.clone(),
                                    new NestedChannelProcessor())
                            .build();
            NodeProvider baseProvider = new SequentialNodeProvider(
                    BlueRuntimeTypeRegistry.getDefault()
                            .asProcessorSnapshotProvider(),
                    registry.exactTypeProvider());
            RecordingNodeProvider provider =
                    new RecordingNodeProvider(baseProvider);
            BlueLanguage language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build();
            BlueContracts contracts = BlueContracts.builder(
                            language.processing())
                    .runtimeRegistry(registry)
                    .build();
            DocumentProcessor traceProcessor = DocumentProcessor.builder()
                    .nodeProvider(provider)
                    .runtimeRegistry(registry)
                    .runtimeRegistryIdentity(
                            RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                    .build();
            return new Fixture(
                    language,
                    contracts,
                    new CoordinationContractsHost(contracts),
                    traceProcessor,
                    provider);
        }

        private Prepared prepare() {
            DocumentProcessingResult initialized =
                    traceProcessor.initializeDocument(nestedRoot());
            assertEquals(ProcessorStatus.SUCCESS,
                    initialized.status(), diagnostic(initialized));
            Node root = initialized.document();
            SubscriptionDelta initial = host.projectInitialSubscriptions(
                    root, ROOT_REVISION, ACTIVATION_ORDER);
            SubscriptionDelta.Entry selected = null;
            for (SubscriptionDelta.Entry interval : initial.added()) {
                if (CANCELLATION_A.equals(interval.scopePath())) {
                    selected = interval;
                    break;
                }
            }
            assertTrue(selected != null,
                    "missing selected cancellation interval");
            String selectedSubscriptionKey =
                    selected.subscriptionKeys().get(0);
            Node event = new Node().properties(
                    "subscriptionKey",
                    new Node().value(selectedSubscriptionKey));
            IndexedDeliveryPreparation indexed =
                    host.prepareIndexedDelivery(
                            root,
                            event,
                            ROOT_REVISION,
                            EVENT_ORDER,
                            initial.added(),
                            candidates(
                                    initial.added(),
                                    selectedSubscriptionKey));
            ExternalDeliveryPlan currentRoot =
                    host.currentRootDeliveryPlanDeriver(
                                    ROOT_REVISION,
                                    EVENT_ORDER,
                                    initial.added())
                            .derive(root, event);
            return new Prepared(
                    root,
                    event,
                    initial.added(),
                    indexed,
                    currentRoot);
        }

        private BlueContracts newContracts(
                ExternalDeliveryPlanDeriver deriver) {
            return BlueContracts.builder(language.processing())
                    .runtimeRegistry(traceProcessor
                            .administration().contractRegistry())
                    .deliveryPlanDeriver(deriver)
                    .build();
        }

        private DocumentProcessor newTraceProcessor(
                ExternalDeliveryPlanDeriver deriver) {
            return DocumentProcessor.builder()
                    .nodeProvider(provider)
                    .runtimeRegistry(traceProcessor
                            .administration().contractRegistry())
                    .runtimeRegistryIdentity(
                            RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY)
                    .deliveryPlanDeriver(deriver)
                    .build();
        }

        @Override
        public void close() {
            traceProcessor.close();
            contracts.close();
            language.close();
        }
    }
}
