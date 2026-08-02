package blue.language.processor;

import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.TestTimelineProvider;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationCurrentRootDeliveryPlanDeriverTest {

    @Test
    void shouldMarkAnEmptyActiveSubscriptionSurfaceAsComplete() {
        try (Fixture fixture = fixture()) {
            // Given
            Node root = initialized(
                    fixture,
                    document(
                            fixture.repository,
                            new LinkedHashMap<>()));
            Node event = event(
                    fixture, "unmatched", 1);

            // When
            ExternalDeliveryPlan plan =
                    deriver(fixture).derive(root, event);

            // Then
            assertTrue(
                    plan.hasActiveSubscriptionIntervals());
            assertTrue(
                    plan.activeSubscriptionIntervals().isEmpty());
            assertTrue(plan.deliveries().isEmpty());
        }
    }

    @Test
    void shouldRetainCompleteSurfaceButDeliverOnlyMatchingChannel() {
        try (Fixture fixture = fixture()) {
            // Given
            Map<String, Node> contracts = new LinkedHashMap<>();
            contracts.put(
                    "matching",
                    TestTimelineProvider.channel("matching"));
            contracts.put(
                    "other",
                    TestTimelineProvider.channel("other"));
            Node root = initialized(
                    fixture,
                    document(fixture.repository, contracts));
            Node event = event(
                    fixture, "matching", 1);

            // When
            ExternalDeliveryPlan plan =
                    deriver(fixture).derive(root, event);

            // Then
            assertEquals(
                    Arrays.asList("matching", "other"),
                    intervalKeys(plan));
            assertEquals(
                    Arrays.asList("matching"),
                    deliveryKeys(plan));
            assertTrue(plan.exactRuntimeState());
        }
    }

    @Test
    void shouldIncludeExternalChannelsAtEmbeddedScopes() {
        try (Fixture fixture = fixture()) {
            // Given
            Map<String, Node> rootContracts =
                    new LinkedHashMap<>();
            rootContracts.put(
                    "rootChannel",
                    TestTimelineProvider.channel("root"));
            rootContracts.put(
                    "embedded",
                    new Node()
                            .type("Process Embedded")
                            .properties(
                                    "paths",
                                    new Node().items(
                                            new Node().value(
                                                    "/child"))));
            Map<String, Node> childContracts =
                    new LinkedHashMap<>();
            childContracts.put(
                    "childChannel",
                    TestTimelineProvider.channel("child"));
            Node authored =
                    document(fixture.repository, rootContracts)
                            .properties(
                                    "child",
                                    new Node()
                                            .name("Child")
                                            .properties(
                                                    "contracts",
                                                    new Node().properties(
                                                            childContracts)));
            Node root = initialized(fixture, authored);

            // When
            ExternalDeliveryPlan plan =
                    deriver(fixture).derive(
                            root,
                            event(fixture, "child", 2));

            // Then
            assertEquals(
                    Arrays.asList(
                            "/:rootChannel",
                            "/child:childChannel"),
                    intervalLocations(plan));
            assertEquals(
                    Arrays.asList(
                            "/child:childChannel"),
                    deliveryLocations(plan));
        }
    }

    @Test
    void shouldPruneDirectlyTerminatedEmbeddedScopesFromCurrentSurface() {
        try (Fixture fixture = fixture()) {
            // Given
            Map<String, Node> rootContracts =
                    new LinkedHashMap<>();
            rootContracts.put(
                    "rootChannel",
                    TestTimelineProvider.channel("root"));
            rootContracts.put(
                    "embedded",
                    new Node()
                            .type("Process Embedded")
                            .properties(
                                    "paths",
                                    new Node().items(
                                            new Node().value(
                                                    "/child"))));
            Map<String, Node> childContracts =
                    new LinkedHashMap<>();
            childContracts.put(
                    "childChannel",
                    TestTimelineProvider.channel("child"));
            Node root = initialized(
                    fixture,
                    document(fixture.repository, rootContracts)
                            .properties(
                                    "child",
                                    new Node()
                                            .name("Child")
                                            .properties(
                                                    "contracts",
                                                    new Node().properties(
                                                            childContracts))));
            root.getAsNode("/child/contracts")
                    .properties(
                            "terminated",
                            new Node()
                                    .type(
                                            new Node().blueId(
                                                    RuntimeBlueIds
                                                            .PROCESSING_TERMINATED_MARKER))
                                    .properties(
                                            "cause",
                                            new Node().value(
                                                    "test-complete")));

            // When
            ExternalDeliveryPlan plan =
                    deriver(fixture).derive(
                            root,
                            event(fixture, "root", 3));

            // Then
            assertEquals(
                    Arrays.asList("/:rootChannel"),
                    intervalLocations(plan));
            assertEquals(
                    Arrays.asList("/:rootChannel"),
                    deliveryLocations(plan));
        }
    }

    @Test
    void shouldNotExposeChannelCreatedAfterCurrentEventSnapshot() {
        try (Fixture fixture = fixture()) {
            // Given
            Map<String, Node> beforeContracts =
                    new LinkedHashMap<>();
            beforeContracts.put(
                    "creator",
                    TestTimelineProvider.channel("timeline"));
            Node before = fixture.blue.preprocess(
                    document(
                            fixture.repository,
                            beforeContracts));
            Map<String, Node> afterContracts =
                    new LinkedHashMap<>(beforeContracts);
            afterContracts.put(
                    "created",
                    TestTimelineProvider.channel("timeline"));
            Node after = fixture.blue.preprocess(
                    document(
                            fixture.repository,
                            afterContracts));
            Node event = event(
                    fixture, "timeline", 3);

            // When
            ExternalDeliveryPlan preEventPlan =
                    deriver(fixture).derive(before, event);
            ExternalDeliveryPlan laterPlan =
                    deriver(fixture).derive(after, event);

            // Then
            assertEquals(
                    Arrays.asList("creator"),
                    intervalKeys(preEventPlan));
            assertEquals(
                    Arrays.asList("creator"),
                    deliveryKeys(preEventPlan));
            assertFalse(intervalKeys(preEventPlan)
                    .contains("created"));
            assertEquals(
                    Arrays.asList("created", "creator"),
                    intervalKeys(laterPlan));
            assertEquals(
                    Arrays.asList("created", "creator"),
                    deliveryKeys(laterPlan));
        }
    }

    private static CoordinationCurrentRootDeliveryPlanDeriver
    deriver(Fixture fixture) {
        return new CoordinationCurrentRootDeliveryPlanDeriver(
                fixture.blue.getDocumentProcessor());
    }

    private static Node initialized(
            Fixture fixture,
            Node authored) {
        DocumentProcessingResult result =
                fixture.blue.initializeDocument(
                        fixture.blue.preprocess(authored));
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status());
        return result.document();
    }

    private static Node event(
            Fixture fixture,
            String timelineId,
            int timestamp) {
        return TestTimelineProvider.timelineEntry(
                fixture.blue,
                fixture.repository,
                timelineId,
                timestamp,
                TestTimelineProvider.chatMessage(
                        "event-" + timestamp));
    }

    private static Node document(
            BlueRepository repository,
            Map<String, Node> contracts) {
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Current Root delivery plan")
                .properties(
                        "contracts",
                        new Node().properties(contracts));
    }

    private static List<String> intervalKeys(
            ExternalDeliveryPlan plan) {
        List<String> keys = new ArrayList<>();
        for (SubscriptionDelta.Entry interval
                : plan.activeSubscriptionIntervals()) {
            keys.add(interval.channelKey());
        }
        return keys;
    }

    private static List<String> intervalLocations(
            ExternalDeliveryPlan plan) {
        List<String> locations = new ArrayList<>();
        for (SubscriptionDelta.Entry interval
                : plan.activeSubscriptionIntervals()) {
            locations.add(
                    interval.scopePath()
                            + ":" + interval.channelKey());
        }
        return locations;
    }

    private static List<String> deliveryKeys(
            ExternalDeliveryPlan plan) {
        List<String> keys = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : plan.deliveries()) {
            keys.add(delivery.channelKey());
        }
        return keys;
    }

    private static List<String> deliveryLocations(
            ExternalDeliveryPlan plan) {
        List<String> locations = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : plan.deliveries()) {
            locations.add(
                    delivery.scopePath()
                            + ":" + delivery.channelKey());
        }
        return locations;
    }

    private static Fixture fixture() {
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                CoordinationTestResources
                        .configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static final class Fixture
            implements AutoCloseable {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(
                BlueRepository repository,
                Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }

        @Override
        public void close() {
            blue.close();
        }
    }
}
