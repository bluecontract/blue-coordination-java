package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.ChannelEvaluationContextFactory;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSPrincipalActor;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeTimelineChannelProcessorTest {
    private static final String TIMELINE = "shared-timeline";
    private static final String ACTOR = "shared-actor";

    @Test
    void compositeWithSeveralMatchingChildrenDeliversOnce() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = matchingChildren();
        contracts.put("inbox", composite("childB", "childA", "childA"));
        contracts.put("handler", sourceReportingHandler("inbox", "compositeSourceChannelKey"));
        Node initialized = initializedDocument(fixture, contracts);

        DocumentProcessingResult result = process(fixture, initialized, 1, 10, "hello");

        assertChatCount(result.triggeredEvents(), "childA", 1);
        assertNotNull(checkpoint(result.document(), "inbox"));
        assertNull(checkpoint(result.document(), "inbox::childA"));
        assertNull(checkpoint(result.document(), "inbox::childB"));
    }

    @Test
    void unionCheckpointIsIndependentFromChildCheckpoint() {
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Map<String, ChannelContract> channels = singletonChannel("child", child);
        ChannelEventCheckpoint checkpoint = new ChannelEventCheckpoint()
                .putEvent("child", eventNode(fixture, 10, 100, "child ahead"));
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                "inbox",
                eventNode(fixture, 9, 99, "union backfill"),
                channels,
                singletonMarker(checkpoint),
                new TimelineChannelProcessor());
        CompositeTimelineChannel union = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));

        ChannelEvaluation evaluation = new CompositeTimelineChannelProcessor().evaluate(union, context);

        assertTrue(evaluation.matches());
        assertEquals(BigInteger.valueOf(9), evaluation.event().get("/sequence"));
    }

    @Test
    void childCheckpointIsIndependentFromUnionCheckpoint() {
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Node current = eventNode(fixture, 1, 1, "child backfill");
        Node unionAhead = eventNode(fixture, 10, 100, "union ahead");
        ChannelEventCheckpoint checkpoint = new ChannelEventCheckpoint().putEvent("inbox", unionAhead);
        Map<String, MarkerContract> markers = singletonMarker(checkpoint);
        ChannelEvaluationContext evaluationContext = ChannelEvaluationContextFactory.create(
                "child",
                current,
                singletonChannel("child", child),
                markers,
                new TimelineChannelProcessor());
        TimelineChannelProcessor processor = new TimelineChannelProcessor();

        assertTrue(processor.evaluate(child, evaluationContext).matches());
        assertTrue(processor.isNewerEvent(child,
                ChannelCheckpointContext.of("/", "child", current, "current", null, null, markers)));
        assertFalse(new CompositeTimelineChannelProcessor().isNewerEvent(
                new CompositeTimelineChannel().channels(Collections.singletonList("child")),
                ChannelCheckpointContext.of("/", "inbox", current, "current", unionAhead, "ahead", markers)));
    }

    @Test
    void directChildAndUnionHandlersMayBothRun() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("child", TestTimelineProvider.channel(TIMELINE, ACTOR));
        contracts.put("inbox", composite("child"));
        contracts.put("childHandler", fixedHandler("child", "direct"));
        contracts.put("unionHandler", fixedHandler("inbox", "union"));
        Node initialized = initializedDocument(fixture, contracts);

        DocumentProcessingResult result = process(fixture, initialized, 1, 1, "hello");

        assertChatCount(result.triggeredEvents(), "direct", 1);
        assertChatCount(result.triggeredEvents(), "union", 1);
        assertNotNull(checkpoint(result.document(), "child"));
        assertNotNull(checkpoint(result.document(), "inbox"));
    }

    @Test
    void matchingChildSelectionIsDeterministic() {
        Fixture fixture = configuredFixture();
        Map<String, Node> ordered = matchingChildren();
        ordered.get("childB").properties("order", new Node().value(-1));
        ordered.put("inbox", composite("childA", "childB"));
        ordered.put("handler", sourceReportingHandler("inbox", "compositeSourceChannelKey"));

        DocumentProcessingResult orderWinner = process(fixture,
                initializedDocument(fixture, ordered),
                1,
                1,
                "order");

        assertChatCount(orderWinner.triggeredEvents(), "childB", 1);

        Fixture keyFixture = configuredFixture();
        Map<String, Node> tied = matchingChildren();
        tied.put("inbox", composite("childB", "childA"));
        tied.put("handler", sourceReportingHandler("inbox", "compositeSourceChannelKey"));

        DocumentProcessingResult keyWinner = process(keyFixture,
                initializedDocument(keyFixture, tied),
                1,
                1,
                "key");

        assertChatCount(keyWinner.triggeredEvents(), "childA", 1);
    }

    @Test
    void newUnionWithNoCheckpointCanBackfillIndependently() {
        Fixture fixture = configuredFixture();
        TimelineChannel child = timelineContract();
        Node current = eventNode(fixture, 5, 50, "backfill");
        Node ahead = eventNode(fixture, 10, 100, "ahead");
        ChannelEventCheckpoint checkpoint = new ChannelEventCheckpoint()
                .putEvent("child", ahead)
                .putEvent("existingUnion", ahead);
        Map<String, MarkerContract> markers = singletonMarker(checkpoint);
        CompositeTimelineChannel union = new CompositeTimelineChannel()
                .channels(Collections.singletonList("child"));
        CompositeTimelineChannelProcessor processor = new CompositeTimelineChannelProcessor();
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                "newUnion",
                current,
                singletonChannel("child", child),
                markers,
                new TimelineChannelProcessor());

        assertTrue(processor.evaluate(union, context).matches());
        assertTrue(processor.isNewerEvent(union,
                ChannelCheckpointContext.of("/", "newUnion", current, "current", null, null, markers)));
    }

    @Test
    void missingChildChannelFailsClearly() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("inbox", composite("missing"));

        DocumentProcessingResult result = process(fixture,
                initializedDocument(fixture, contracts),
                1,
                1,
                "hello");

        assertRuntimeFatal(result, "references missing child channel 'missing'");
    }

    @Test
    void nonTimelineChildFailsClearly() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("triggered", new Node().type("Triggered Event Channel"));
        contracts.put("inbox", composite("triggered"));

        DocumentProcessingResult result = process(fixture,
                initializedDocument(fixture, contracts),
                1,
                1,
                "hello");

        assertRuntimeFatal(result, "must be a Timeline Channel");
    }

    @Test
    void selfReferenceFailsClearly() {
        Fixture fixture = configuredFixture();
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("inbox", composite("inbox"));

        DocumentProcessingResult result = process(fixture,
                initializedDocument(fixture, contracts),
                1,
                1,
                "hello");

        assertRuntimeFatal(result, "cannot include itself");
    }

    @Test
    void childChannelEventFilterIsHonored() {
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
                        eventNode(fixture, 1, 1, "allowed"),
                        channels,
                        Collections.<String, MarkerContract>emptyMap(),
                        new TimelineChannelProcessor()));
        ChannelEvaluation denied = processor.evaluate(union,
                ChannelEvaluationContextFactory.create(
                        "inbox",
                        eventNode(fixture, 2, 2, "denied"),
                        channels,
                        Collections.<String, MarkerContract>emptyMap(),
                        new TimelineChannelProcessor()));

        assertTrue(allowed.matches());
        assertFalse(denied.matches());
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
                .actor(new MyOSPrincipalActor().accountId(ACTOR));
    }

    private static Map<String, ChannelContract> singletonChannel(String key, ChannelContract channel) {
        Map<String, ChannelContract> channels = new LinkedHashMap<String, ChannelContract>();
        channels.put(key, channel);
        return channels;
    }

    private static Map<String, MarkerContract> singletonMarker(ChannelEventCheckpoint checkpoint) {
        Map<String, MarkerContract> markers = new LinkedHashMap<String, MarkerContract>();
        markers.put("checkpoint", checkpoint);
        return markers;
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

    private static Node sourceReportingHandler(String channel, String metadataKey) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(
                        appendChatMessageStep(bexBinding("event", "/meta/" + metadataKey))));
    }

    private static Node fixedHandler(String channel, String message) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(
                        appendChatMessageStep(new Node().value(message))));
    }

    private static Node appendChatMessageStep(Node message) {
        return new Node()
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendEvent", new Node().properties("$merge", new Node().items(
                                new Node().properties("type", new Node().value("Coordination/Chat Message")),
                                new Node().properties("message", message)))),
                        new Node().properties("$return", new Node().value(true))));
    }

    private static Node bexBinding(String name, String path) {
        return new Node().properties("$binding", new Node().value(name + path));
    }

    private static Node initializedDocument(Fixture fixture, Map<String, Node> contracts) {
        Node document = new Node()
                .blue(fixture.repository.typeAliasBlue())
                .name("Composite Timeline V2 Test")
                .properties("contracts", new Node().properties(contracts));
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(fixture.blue.preprocess(document));
        assertEquals(ProcessorStatus.SUCCESS, initialized.status(), initialized.failureReason());
        return initialized.document();
    }

    private static DocumentProcessingResult process(Fixture fixture,
                                                    Node document,
                                                    long sequence,
                                                    long timestamp,
                                                    String message) {
        return fixture.blue.processDocument(document, eventNode(fixture, sequence, timestamp, message));
    }

    private static Node eventNode(Fixture fixture,
                                  long sequence,
                                  long timestamp,
                                  String message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                TIMELINE,
                ACTOR,
                BigInteger.valueOf(sequence),
                BigInteger.valueOf(timestamp),
                TestTimelineProvider.chatMessage(message));
    }

    private static Node checkpoint(Node document, String key) {
        try {
            return document.getAsNode("/contracts/checkpoint/lastEvents/" + key);
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

    private static void assertRuntimeFatal(DocumentProcessingResult result, String expectedMessage) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status(), result.failureReason());
        assertTrue(result.failureReason() != null && result.failureReason().contains(expectedMessage),
                result.failureReason());
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
