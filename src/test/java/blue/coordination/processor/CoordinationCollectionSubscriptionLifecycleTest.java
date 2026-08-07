package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationCollectionSubscriptionLifecycleTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Collection lifecycle Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);
    private static final String TEST_REGISTRY_IDENTITY =
            "blue.coordination/test/collection-subscriptions/1";

    private final List<Fixture> openedFixtures = new ArrayList<>();

    @AfterEach
    void closeOpenedFixtures() {
        for (Fixture fixture : openedFixtures) {
            fixture.close();
        }
        openedFixtures.clear();
    }

    @Test
    void shouldProjectInitialStableKeyCollectionMembersThroughPublicContractsApi() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put("lesson-b", lesson("lesson-b"));
        lessons.put("lesson-a", lesson("lesson-a"));
        Node root = initialized(
                fixture,
                rootWithLessons(lessons));
        CoordinationSubscriptionProjector projector =
                projector(fixture);
        ExternalOrderKey activation = order(10);

        // when
        CoordinationSubscriptionSnapshot snapshot =
                projector.projectCurrent(root, 1L, activation);

        // then
        assertEquals(
                Arrays.asList(
                        "/lessons/lesson-a",
                        "/lessons/lesson-b"),
                scopePaths(snapshot));
        assertEquals(
                CoordinationSubscriptionOccurrence.Origin
                        .COLLECTION_MEMBER,
                snapshot.occurrences().get(0).origin());
        assertEquals(
                "/lessons",
                snapshot.occurrences().get(0)
                        .collectionDeclarationPath());
        assertEquals(
                "lesson-a",
                snapshot.occurrences().get(0)
                        .collectionMemberKey());
        assertEquals(
                Long.valueOf(1L),
                snapshot.occurrences().get(0)
                        .activationRootRevision());
        assertEquals(
                activation,
                snapshot.occurrences().get(0)
                        .activationFrontier());
        assertTrue(
                !snapshot.occurrences().get(0)
                        .headerFieldBlueIds().isEmpty());
    }

    @Test
    void shouldActivateAddedCollectionMemberStrictlyAfterTransitionFrontier() {
        // given
        Fixture fixture = fixture();
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put("lesson-a", lesson("lesson-a"));
        Node initialRoot = initialized(
                fixture,
                rootWithLessons(lessons));
        CoordinationSubscriptionProjector projector =
                projector(fixture);
        CoordinationSubscriptionSnapshot initial =
                projector.projectCurrent(
                        initialRoot,
                        1L,
                        order(10));
        Node resultingRoot = initialRoot.clone();
        resultingRoot.getAsNode("/lessons").properties(
                "lesson-b",
                lesson("lesson-b"));
        ExternalOrderKey transition = order(20);

        // when
        CoordinationSubscriptionUpdate update =
                projector.projectUpdate(
                        initial,
                        resultingRoot,
                        2L,
                        transition,
                        Collections.singleton(
                                "/lessons/lesson-b"));

        // then
        assertEquals(1, update.added().size());
        assertEquals(
                "/lessons/lesson-b",
                update.added().get(0).scopePath());
        assertEquals(
                Long.valueOf(2L),
                update.added().get(0)
                        .activationRootRevision());
        assertEquals(
                transition,
                update.added().get(0)
                        .activationFrontier());
        assertEquals(1, update.unchanged().size());
        assertEquals(
                "/lessons/lesson-a",
                update.unchanged().get(0).scopePath());
        assertTrue(update.retired().isEmpty());
    }

    @Test
    void shouldRetireAndReaddStableKeyAsFreshActivationInterval() {
        // given
        Fixture fixture = fixture();
        Node lessonB = lesson("lesson-b");
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put("lesson-a", lesson("lesson-a"));
        lessons.put("lesson-b", lessonB.clone());
        Node present = initialized(
                fixture,
                rootWithLessons(lessons));
        CoordinationSubscriptionProjector projector =
                projector(fixture);
        CoordinationSubscriptionSnapshot initial =
                projector.projectCurrent(
                        present,
                        1L,
                        order(10));
        Node removedRoot = present.clone();
        removedRoot.getAsNode("/lessons")
                .getProperties()
                .remove("lesson-b");
        CoordinationSubscriptionUpdate removal =
                projector.projectUpdate(
                        initial,
                        removedRoot,
                        2L,
                        order(20),
                        Collections.singleton(
                                "/lessons/lesson-b"));
        Node readdedRoot = removedRoot.clone();
        readdedRoot.getAsNode("/lessons").properties(
                "lesson-b",
                lessonB.clone());
        ExternalOrderKey readditionFrontier = order(30);

        // when
        CoordinationSubscriptionUpdate readdition =
                projector.projectUpdate(
                        removal.snapshot(),
                        readdedRoot,
                        3L,
                        readditionFrontier,
                        Collections.singleton(
                                "/lessons/lesson-b"));

        // then
        assertEquals(1, removal.retired().size());
        assertEquals(
                Long.valueOf(2L),
                removal.retired().get(0)
                        .endAtRootRevision());
        assertEquals(1, readdition.added().size());
        assertEquals(
                Long.valueOf(3L),
                readdition.added().get(0)
                        .activationRootRevision());
        assertEquals(
                readditionFrontier,
                readdition.added().get(0)
                        .activationFrontier());
        assertEquals(
                initial.occurrences().get(1).scopeBlueId(),
                readdition.added().get(0).scopeBlueId());
        assertNotEquals(
                initial.digest(),
                readdition.snapshot().digest());
    }

    @Test
    void shouldProjectNestedCollectionMemberProvenanceAtEveryScope() {
        // given
        Fixture fixture = fixture();
        Node cancellation = scopeWithChannel("cancel-a");
        Node lesson = scopeWithChannel("lesson-a");
        lesson.properties(
                "cancellations",
                objectMap(Collections.singletonMap(
                        "cancel-a", cancellation)));
        lesson.getContracts().properties(
                "embedded",
                processEmbeddedCollections("/cancellations"));
        Node agreement = scopeWithChannel("agreement-a");
        agreement.properties(
                "lessons",
                objectMap(Collections.singletonMap(
                        "lesson-a", lesson)));
        agreement.getContracts().properties(
                "embedded",
                processEmbeddedCollections("/lessons"));
        Node root = baseDocument();
        root.properties(
                "agreements",
                objectMap(Collections.singletonMap(
                        "agreement-a", agreement)));
        root.contracts(new Node().properties(
                "embedded",
                processEmbeddedCollections("/agreements")));
        Node initialized = initialized(fixture, root);

        // when
        CoordinationSubscriptionSnapshot snapshot =
                projector(fixture).projectCurrent(
                        initialized,
                        1L,
                        order(10));

        // then
        assertEquals(
                Arrays.asList(
                        "/agreements/agreement-a",
                        "/agreements/agreement-a/lessons/lesson-a",
                        "/agreements/agreement-a/lessons/lesson-a/"
                                + "cancellations/cancel-a"),
                scopePaths(snapshot));
        assertEquals(
                Arrays.asList(
                        "agreement-a",
                        "lesson-a",
                        "cancel-a"),
                memberKeys(snapshot));
        assertEquals(
                "/agreements/agreement-a/lessons/lesson-a",
                snapshot.occurrences().get(2)
                        .declaringScopePath());
    }

    @Test
    void shouldMaterializePureReferenceChannelHeaderThroughPublicContractsApi() {
        // given
        Node exactChannel = channel("pure-reference");
        String channelBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        exactChannel);
        NodeProvider exactChannelProvider = blueId ->
                channelBlueId.equals(blueId)
                        ? Collections.singletonList(
                                exactChannel.clone())
                        : null;
        Fixture fixture = fixture(exactChannelProvider);
        Node root = baseDocument().contracts(
                new Node().properties(
                        "timeline",
                        new Node().blueId(channelBlueId)));

        // when
        CoordinationSubscriptionSnapshot snapshot =
                projector(fixture).projectCurrent(
                        root,
                        1L,
                        order(10));

        // then
        assertEquals(1, snapshot.occurrences().size());
        CoordinationSubscriptionOccurrence occurrence =
                snapshot.occurrences().get(0);
        assertEquals("/", occurrence.scopePath());
        assertEquals("timeline", occurrence.channelKey());
        assertEquals(
                Collections.singletonList(
                        "/@pure-reference"),
                occurrence.subscriptionKeys());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("pure-reference")),
                occurrence.headerFieldBlueIds().get("binding"));
        assertTrue(occurrence.sourceContributionNodeBlueIds()
                .contains(channelBlueId));
    }

    private static CoordinationSubscriptionProjector projector(
            Fixture fixture) {
        return new CoordinationSubscriptionProjector(
                fixture.processor,
                fixture.contracts);
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture fixture(NodeProvider exactNodeProvider) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .register(
                                CHANNEL_TYPE_BLUE_ID,
                                CHANNEL_TYPE.clone(),
                                new LifecycleChannelProcessor())
                        .build();
        List<NodeProvider> providers = new ArrayList<>();
        if (exactNodeProvider != null) {
            providers.add(exactNodeProvider);
        }
        providers.add(BlueRuntimeTypeRegistry.getDefault()
                .asProcessorSnapshotProvider());
        providers.add(registry.exactTypeProvider());
        NodeProvider provider = new SequentialNodeProvider(
                providers.toArray(new NodeProvider[providers.size()]));
        BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
        BlueContracts contracts = BlueContracts.builder(
                        language.processing())
                .runtimeRegistry(registry)
                .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeAccess(contracts.runtimeAccess())
                .runtimeRegistry(registry)
                .runtimeRegistryIdentity(
                        TEST_REGISTRY_IDENTITY)
                .build();
        Fixture fixture = new Fixture(
                language,
                contracts,
                processor);
        openedFixtures.add(fixture);
        return fixture;
    }

    private static Node initialized(
            Fixture fixture,
            Node authored) {
        if (!fixture.contracts.runtimeAccess().isCurrent()) {
            throw new IllegalStateException(
                    "Contracts runtime access must be current");
        }
        return authored.clone();
    }

    private static Node rootWithLessons(
            Map<String, Node> lessons) {
        Node root = baseDocument();
        root.properties("lessons", objectMap(lessons));
        root.contracts(new Node().properties(
                "embedded",
                processEmbeddedCollections("/lessons")));
        return root;
    }

    private static Node baseDocument() {
        return new Node()
                .name("Collection subscription lifecycle");
    }

    private static Node lesson(String key) {
        return scopeWithChannel(key);
    }

    private static Node scopeWithChannel(String key) {
        return new Node().contracts(
                new Node().properties(
                        "timeline",
                        channel(key)));
    }

    private static Node channel(String key) {
        return new Node()
                .type(new Node().blueId(
                        CHANNEL_TYPE_BLUE_ID))
                .properties(
                        "binding",
                        new Node().value(key));
    }

    private static Node processEmbeddedCollections(
            String... collectionPaths) {
        List<Node> paths = new ArrayList<>();
        for (String path : collectionPaths) {
            paths.add(new Node().value(path));
        }
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(paths));
    }

    private static Node objectMap(Map<String, Node> entries) {
        return new Node().properties(
                new LinkedHashMap<>(entries));
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(
                Collections.singletonList(
                        BigInteger.valueOf(value)));
    }

    private static List<String> scopePaths(
            CoordinationSubscriptionSnapshot snapshot) {
        List<String> paths = new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            paths.add(occurrence.scopePath());
        }
        return paths;
    }

    private static List<String> memberKeys(
            CoordinationSubscriptionSnapshot snapshot) {
        List<String> keys = new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            keys.add(occurrence.collectionMemberKey());
        }
        return keys;
    }

    public static final class LifecycleChannel
            extends ChannelContract {
        private String binding;

        public LifecycleChannel() {
        }

        public String getBinding() {
            return binding;
        }

        public void setBinding(String binding) {
            this.binding = binding;
        }
    }

    private static final class LifecycleChannelProcessor
            implements ChannelProcessor<LifecycleChannel> {
        private static final ExternalChannelSubscriptionFunctions<
                LifecycleChannel> FUNCTIONS =
                new ExternalChannelSubscriptionFunctions<
                        LifecycleChannel>() {
                    @Override
                    public List<String> channelKeys(
                            LifecycleChannel contract) {
                        return Collections.singletonList(
                                contract.getBinding());
                    }

                    @Override
                    public List<String> channelKeys(
                            LifecycleChannel contract,
                            ExternalChannelFunctionContext context) {
                        return Collections.singletonList(
                                context.scopePath() + "@"
                                        + contract.getBinding());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            LifecycleChannel contract) {
                        return contract.getBinding();
                    }
                };

        @Override
        public Class<LifecycleChannel> contractType() {
            return LifecycleChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<LifecycleChannel>
        externalSubscriptionFunctions() {
            return FUNCTIONS;
        }

        @Override
        public boolean matches(
                LifecycleChannel contract,
                ChannelEvaluationContext context) {
            return true;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final DocumentProcessor processor;

        private Fixture(
                BlueLanguage language,
                BlueContracts contracts,
                DocumentProcessor processor) {
            this.language = language;
            this.contracts = contracts;
            this.processor = processor;
        }

        @Override
        public void close() {
            processor.close();
            contracts.close();
            language.close();
        }
    }
}
