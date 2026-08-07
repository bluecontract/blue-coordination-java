package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact feeder-candidate verification through the public Contracts API. */
final class CoordinationPublicIndexedDeliveryCandidatesTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Indexed candidate Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);

    @Test
    void shouldAcceptExactCandidatesAndMatchCurrentRootDerivation() {
        // given
        try (Fixture fixture = Fixture.open()) {
            List<ExternalSubscriptionOccurrenceKey> exact =
                    fixture.exactCandidates();

            // when
            IndexedDeliveryPreparation indexed = fixture.prepare(
                    fixture.rootRevision, exact);
            ExternalDeliveryPlan currentRoot = fixture.contracts
                    .currentRootDeliveryPlanDeriver(
                            fixture.rootRevision,
                            fixture.eventOrder,
                            fixture.activeIntervals)
                    .derive(fixture.root, fixture.event);

            // then
            assertEquals(
                    Arrays.asList("alpha", "beta"),
                    deliveryChannelKeys(indexed.deliveryPlan()));
            assertEquals(
                    deliveryChannelKeys(indexed.deliveryPlan()),
                    deliveryChannelKeys(currentRoot));
            assertEquals(
                    indexed.deliveryPlan().activeSubscriptionIntervals(),
                    currentRoot.activeSubscriptionIntervals());
        }
    }

    @Test
    void shouldRejectOmittedIndexedCandidate() {
        // given
        try (Fixture fixture = Fixture.open()) {
            List<ExternalSubscriptionOccurrenceKey> omitted =
                    Collections.singletonList(
                            fixture.exactCandidates().get(0));

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> fixture.prepare(
                            fixture.rootRevision, omitted));

            // then
            assertEquals(
                    "Indexed physical candidate occurrence list does not "
                            + "match the complete evaluated subscription surface",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectExtraIndexedCandidate() {
        // given
        try (Fixture fixture = Fixture.open()) {
            List<ExternalSubscriptionOccurrenceKey> extra =
                    new ArrayList<>(fixture.exactCandidates());
            extra.add(ExternalSubscriptionOccurrenceKey.of(
                    "/", "not-retained"));

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> fixture.prepare(
                            fixture.rootRevision, extra));

            // then
            assertEquals(
                    "Indexed physical candidate occurrence list does not "
                            + "match the complete evaluated subscription surface",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectDuplicateIndexedCandidate() {
        // given
        try (Fixture fixture = Fixture.open()) {
            List<ExternalSubscriptionOccurrenceKey> duplicate =
                    new ArrayList<>(fixture.exactCandidates());
            duplicate.add(fixture.exactCandidates().get(0));

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> fixture.prepare(
                            fixture.rootRevision, duplicate));

            // then
            assertEquals(
                    "Duplicate indexed physical candidate occurrence: /alpha",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectIndexedCandidatesInWrongOrder() {
        // given
        try (Fixture fixture = Fixture.open()) {
            List<ExternalSubscriptionOccurrenceKey> reversed =
                    new ArrayList<>(fixture.exactCandidates());
            Collections.reverse(reversed);

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> fixture.prepare(
                            fixture.rootRevision, reversed));

            // then
            assertEquals(
                    "Indexed physical candidate occurrence list does not "
                            + "match the complete evaluated subscription surface",
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectCandidateSurfaceFromStaleRootRevision() {
        // given
        try (Fixture fixture = Fixture.open()) {
            long staleRevision = fixture.rootRevision - 1L;

            // when
            InvalidExecutionEvidenceException failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> fixture.prepare(
                            staleRevision,
                            fixture.exactCandidates()));

            // then
            assertEquals(
                    "Retained subscription interval is not active at indexed Root revision 6 at //alpha",
                    failure.getMessage());
        }
    }

    private static List<String> deliveryChannelKeys(
            ExternalDeliveryPlan plan) {
        List<String> keys = new ArrayList<>();
        plan.deliveries().forEach(delivery ->
                keys.add(delivery.channelKey()));
        return keys;
    }

    public static final class IndexedChannel extends ChannelContract {
        private String binding;

        public IndexedChannel() {
        }

        public String getBinding() {
            return binding;
        }

        public void setBinding(String binding) {
            this.binding = binding;
        }
    }

    private static final class IndexedChannelProcessor
            implements ChannelProcessor<IndexedChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                IndexedChannel> FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<IndexedChannel>() {
                    @Override
                    public List<String> channelKeys(
                            IndexedChannel contract) {
                        return Collections.singletonList(
                                contract.getBinding());
                    }

                    @Override
                    public List<String> channelKeys(
                            IndexedChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                context.scopePath()
                                        + "@"
                                        + contract.getBinding());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            IndexedChannel contract) {
                        return contract.getBinding();
                    }
                };

        @Override
        public Class<IndexedChannel> contractType() {
            return IndexedChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<IndexedChannel>
        externalSubscriptionFunctions() {
            return FUNCTIONS;
        }

        @Override
        public boolean matches(
                IndexedChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final Node root;
        private final Node event;
        private final long rootRevision;
        private final ExternalOrderKey eventOrder;
        private final List<SubscriptionDelta.Entry> activeIntervals;

        private Fixture(
                BlueLanguage language,
                BlueContracts contracts,
                Node root,
                Node event,
                long rootRevision,
                ExternalOrderKey eventOrder,
                List<SubscriptionDelta.Entry> activeIntervals) {
            this.language = language;
            this.contracts = contracts;
            this.root = root;
            this.event = event;
            this.rootRevision = rootRevision;
            this.eventOrder = eventOrder;
            this.activeIntervals = activeIntervals;
        }

        private static Fixture open() {
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .registerDefaults()
                            .register(
                                    CHANNEL_TYPE_BLUE_ID,
                                    CHANNEL_TYPE.clone(),
                                    new IndexedChannelProcessor())
                            .build();
            NodeProvider provider = new SequentialNodeProvider(
                    BlueRuntimeTypeRegistry.getDefault()
                            .asProcessorSnapshotProvider(),
                    registry.exactTypeProvider());
            BlueLanguage language = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .build();
            BlueContracts contracts = BlueContracts.builder(
                            language.processing())
                    .runtimeRegistry(registry)
                    .build();
            Node root = new Node()
                    .name("Indexed candidate Root")
                    .contracts(new Node().properties(
                            "alpha", channel("shared"),
                            "beta", channel("shared")));
            Node event = new Node().properties(
                    "subscriptionKey",
                    new Node().value("/@shared"));
            long revision = 7L;
            ExternalOrderKey activationOrder = order(10L);
            ExternalOrderKey eventOrder = order(20L);
            SubscriptionDelta initial = contracts
                    .subscriptionSurfaceProjection()
                    .projectInitial(
                            root, revision, activationOrder);
            return new Fixture(
                    language,
                    contracts,
                    root,
                    event,
                    revision,
                    eventOrder,
                    initial.added());
        }

        private IndexedDeliveryPreparation prepare(
                long revision,
                List<ExternalSubscriptionOccurrenceKey> candidates) {
            return contracts.indexedDeliveryEvaluator().prepare(
                    root,
                    event,
                    revision,
                    eventOrder,
                    activeIntervals,
                    candidates);
        }

        private List<ExternalSubscriptionOccurrenceKey> exactCandidates() {
            List<ExternalSubscriptionOccurrenceKey> result =
                    new ArrayList<>();
            for (SubscriptionDelta.Entry interval : activeIntervals) {
                result.add(ExternalSubscriptionOccurrenceKey.of(
                        interval.scopePath(), interval.channelKey()));
            }
            return result;
        }

        @Override
        public void close() {
            contracts.close();
            language.close();
        }
    }

    private static Node channel(String binding) {
        return new Node()
                .type(new Node().blueId(CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "binding", new Node().value(binding));
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.<Object>singletonList(value));
    }
}
