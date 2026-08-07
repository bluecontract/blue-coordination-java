package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.ChannelEvaluationContextFactory;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionSurfaceInvalidException;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.PrincipalActor;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTimelineChannelProcessorTest {
    private static final String TIMELINE = "shared-timeline";
    private static final String ACTOR = "shared-actor";

    @Test
    void shouldEnsureThatCompositeWithSeveralMatchingChildrenDeliversOnce() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = matchingChildren();
        contracts.put("inbox", composite("childB", "childA", "childA"));
        contracts.put("handler", fixedHandler("inbox", "union"));
        Node initialized = initializedDocument(fixture, contracts);

        // when
        DocumentProcessingResult result = process(fixture, initialized, 10, "hello");

        // then
        assertChatCount(result.events(), "union", 1);
        assertCompositeCheckpointSubject(
                checkpoint(result.document(), "inbox"),
                BigInteger.TEN,
                "childA");
        assertNull(checkpoint(result.document(), "inbox::childA"));
        assertNull(checkpoint(result.document(), "inbox::childB"));
    }

    @Test
    void shouldEnsureThatCompositeEvaluationUsesItsOwnExactPayload() {
        // given
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Map<String, ChannelContract> channels = singletonChannel("child", child);
        Node event = eventNode(fixture, 99, "composite");
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                "inbox",
                event,
                channels,
                Collections.emptyMap(),
                new TimelineChannelProcessor());
        CompositeTimelineChannel union = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));

        // when
        ChannelEvaluation evaluation = new CompositeTimelineChannelProcessor().evaluate(union, context);

        // then
        assertTrue(evaluation.matches());
        assertEquals(BigInteger.valueOf(99), evaluation.event().get("/timestamp"));
        assertEquals(TimelineProviderSupport.eventId(event), evaluation.eventId());
    }

    @Test
    void shouldEnsureThatDirectChildAndCompositeBothEvaluateTheExactOccurrence() {
        // given
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Node current = eventNode(fixture, 1, "shared");
        Map<String, ChannelContract> channels = singletonChannel("child", child);
        ChannelEvaluationContext evaluationContext = ChannelEvaluationContextFactory.create(
                "child",
                current,
                channels,
                Collections.emptyMap(),
                new TimelineChannelProcessor());
        TimelineChannelProcessor processor = new TimelineChannelProcessor();
        CompositeTimelineChannel composite = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));
        // when
        ChannelEvaluationContext compositeContext =
                ChannelEvaluationContextFactory.create(
                        "inbox",
                        current,
                        channels,
                        Collections.emptyMap(),
                        new TimelineChannelProcessor());

        // then
        assertTrue(processor.evaluate(child, evaluationContext).matches());
        assertTrue(new CompositeTimelineChannelProcessor()
                .evaluate(composite, compositeContext).matches());
    }

    @Test
    void shouldEnsureThatDirectChildAndUnionHandlersMayBothRun() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("child", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("inbox", composite("child"));
        contracts.put("childHandler", fixedHandler("child", "direct"));
        contracts.put("unionHandler", fixedHandler("inbox", "union"));
        Node initialized = initializedDocument(fixture, contracts);

        // when
        DocumentProcessingResult result = process(fixture, initialized, 1, "hello");

        // then
        assertChatCount(result.events(), "direct", 1);
        assertChatCount(result.events(), "union", 1);
        assertDirectCheckpointSubject(
                checkpoint(result.document(), "child"),
                BigInteger.ONE);
        assertCompositeCheckpointSubject(
                checkpoint(result.document(), "inbox"),
                BigInteger.ONE,
                "child");
    }

    @Test
    void shouldSelectTheLowestOrderMatchingCompositeChild() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> ordered = matchingChildren();
        ordered.get("childB").properties("order", new Node().value(-1));
        ordered.put("inbox", composite("childA", "childB"));
        ordered.put("handler", fixedHandler("inbox", "union"));

        // when
        DocumentProcessingResult orderWinner = process(fixture,
                initializedDocument(fixture, ordered),
                1,
                "order");

        // then
        assertChatCount(orderWinner.events(), "union", 1);
        assertCompositeCheckpointSubject(
                checkpoint(orderWinner.document(), "inbox"),
                BigInteger.ONE,
                "childB");
    }

    @Test
    void shouldSelectTheFirstMatchingCompositeChildKeyWhenOrdersTie() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> tied = matchingChildren();
        tied.put("inbox", composite("childB", "childA"));
        tied.put("handler", fixedHandler("inbox", "union"));

        // when
        DocumentProcessingResult keyWinner = process(fixture,
                initializedDocument(fixture, tied),
                1,
                "key");

        // then
        assertChatCount(keyWinner.events(), "union", 1);
        assertCompositeCheckpointSubject(
                checkpoint(keyWinner.document(), "inbox"),
                BigInteger.ONE,
                "childA");
    }

    @Test
    void shouldEnsureThatNewCompositeEvaluatesWithoutCheckpointState() {
        // given
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Node current = eventNode(fixture, 50, "backfill");
        CompositeTimelineChannel union = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));
        CompositeTimelineChannelProcessor processor = new CompositeTimelineChannelProcessor();
        // when
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                "newUnion",
                current,
                singletonChannel("child", child),
                Collections.emptyMap(),
                new TimelineChannelProcessor());

        // then
        assertTrue(processor.evaluate(union, context).matches());
    }

    @Test
    void shouldEnsureThatMissingChildChannelFailsClearly() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("inbox", composite("missing"));

        // when
        SubscriptionSurfaceInvalidException failure =
                projectInvalidSurface(fixture, contracts);

        // then
        assertTrue(failure.getMessage().contains("missing"));
    }

    @Test
    void shouldEnsureThatNonTimelineChildFailsClearly() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("triggered", new Node().type("Triggered Event Channel"));
        contracts.put("inbox", composite("triggered"));

        // when
        SubscriptionSurfaceInvalidException failure =
                projectInvalidSurface(fixture, contracts);

        // then
        assertTrue(failure.getMessage().contains("triggered"));
    }

    @Test
    void shouldEnsureThatSelfReferenceFailsClearly() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("inbox", composite("inbox"));

        // when
        SubscriptionSurfaceInvalidException failure =
                projectInvalidSurface(fixture, contracts);

        // then
        assertTrue(failure.getMessage().contains("inbox"));
    }

    @Test
    void shouldEnsureThatEmptyCompositeFailsSubscriptionSurfaceValidation() {
        // given
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("inbox", composite());

        // when
        SubscriptionSurfaceInvalidException failure =
                projectInvalidSurface(fixture, contracts);

        // then
        assertTrue(failure.getMessage().contains(
                "requires at least one member"));
    }

    @Test
    void shouldEnsureThatPreviewChannelDefinitionDoesNotParticipateInExternalAcceptance() {
        // given
        Fixture fixture = configuredFixture();
        TimelineChannel filtered = timelineContract();
        filtered.setDefinition(new Node()
                .type(TimelineEntry.repositoryType().reference())
                .properties("message", new Node()
                        .type(ChatMessage.repositoryType().reference())
                        .properties("message", new Node().value("allowed"))));
        Map<String, ChannelContract> channels = singletonChannel("child", filtered);
        CompositeTimelineChannel union = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));
        CompositeTimelineChannelProcessor processor = new CompositeTimelineChannelProcessor();

        ChannelEvaluation allowed = processor.evaluate(union,
                ChannelEvaluationContextFactory.create(
                        "inbox",
                        eventNode(fixture, 1, "allowed"),
                        channels,
                        Collections.emptyMap(),
                        new TimelineChannelProcessor()));
        // when
        ChannelEvaluation denied = processor.evaluate(union,
                ChannelEvaluationContextFactory.create(
                        "inbox",
                        eventNode(fixture, 2, "denied"),
                        channels,
                        Collections.emptyMap(),
                        new TimelineChannelProcessor()));

        // then
        assertTrue(allowed.matches());
        assertTrue(denied.matches());
    }

    private static Map<String, Node> matchingChildren() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("childA", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("childB", TestTimelineProvider.channel(TIMELINE, ACTOR));
        return contracts;
    }

    private static TimelineChannel timelineContract() {
        return new TimelineChannel()
                .timeline(new Timeline().timelineId(TIMELINE))
                .actor(new PrincipalActor().accountId(ACTOR));
    }

    private static Map<String, ChannelContract> singletonChannel(String key, ChannelContract channel) {
        Map<String, ChannelContract> channels = new LinkedHashMap<String, ChannelContract>();
        channels.put(key, channel);
        return channels;
    }

    private static Node composite(String... channels) {
        return new Node()
                .type("Coordination/Composite Timeline Channel")
                .properties("channels", stringList(channels));
    }

    private static Node stringList(String... values) {
        Node[] nodes = new Node[values.length];
        for (int i = 0; i < values.length; i++) {
            nodes[i] = new Node().value(values[i]);
        }
        return new Node().items(nodes);
    }

    private static Node fixedHandler(String channel, String message) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(
                        new Node()
                                .type("Coordination/Trigger Event")
                                .properties(
                                        "event",
                                        TestTimelineProvider.chatMessage(
                                                message))));
    }

    private static Node initializedDocument(Fixture fixture, Map<String, Node> contracts) {
        DocumentProcessingResult initialized =
                initializeDocument(fixture, contracts);
        assertEquals(ProcessorStatus.SUCCESS, initialized.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(initialized));
        return initialized.document();
    }

    private static DocumentProcessingResult initializeDocument(
            Fixture fixture,
            Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.importsDirective())
                .name("Composite Timeline V2 Test")
                .properties("contracts", new Node().properties(contracts));
        return fixture.blue.initializeDocument(
                fixture.blue.preprocess(document));
    }

    private static DocumentProcessingResult process(Fixture fixture,
                                                    Node document,
                                                    long timestamp,
                                                    String message) {
        return fixture.blue.processDocument(document, eventNode(fixture, timestamp, message));
    }

    private static Node eventNode(Fixture fixture,
                                  long timestamp,
                                  String message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                TIMELINE,
                ACTOR,
                BigInteger.valueOf(timestamp),
                TestTimelineProvider.chatMessage(message));
    }

    private static Node checkpoint(Node document, String key) {
        try {
            return document.getAsNode(
                    "/contracts/checkpoint/entries/"
                            + escapePointerSegment(key)
                            + "/subject");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String escapePointerSegment(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void assertCompositeCheckpointSubject(
            Node subject,
            BigInteger timestamp,
            String memberKey) {
        assertNotNull(
                subject,
                "Language checkpoint coalescing defect: "
                        + "aggregate checkpoint was erased by a later "
                        + "handler-group marker write");
        assertEquals(
                CompositeTimelineExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
        assertEquals(memberKey, subject.getAsText("/memberKey"));
        assertNotNull(subject.getAsText("/memberDomain"));
    }

    private static void assertDirectCheckpointSubject(
            Node subject,
            BigInteger timestamp) {
        assertNotNull(
                subject,
                "Language checkpoint coalescing defect: "
                        + "direct checkpoint was erased by a later "
                        + "handler-group marker write");
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
    }

    private static void assertChatCount(List<Node> events, String message, int expected) {
        int count = 0;
        for (Node event : events) {
            try {
                if (message.equals(event.get("/message"))) {
                    count++;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        assertEquals(expected, count);
    }

    private static SubscriptionSurfaceInvalidException
    projectInvalidSurface(
            Fixture fixture,
            Map<String, Node> contracts) {
        DocumentProcessingResult initialized =
                initializeDocument(fixture, contracts);
        assertEquals(
                ProcessorStatus.SUCCESS,
                initialized.status(),
                ProcessingResultTestSupport
                        .diagnosticMessage(initialized));
        CoordinationSubscriptionProjector projector =
                CoordinationDeliveryPlanning
                        .subscriptionProjector(
                                fixture.blue.processor(),
                                fixture.blue.contracts());
        return assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> projector.projectCurrent(
                        initialized.document(),
                        0L,
                        ExternalOrderKey.of(
                                Collections.singletonList(
                                        BigInteger.ZERO))));
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestResources.configuredBlue(repository);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final CoordinationTestRuntime blue;

        private Fixture(
                BlueRepository repository,
                CoordinationTestRuntime blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
