package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContextFactory;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.MarkerContract;
import blue.repo.BlueRepository;
import blue.repo.coordination.Actor;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSAgentActor;
import blue.repo.myos.PrincipalActor;
import blue.repo.myos.MyOSTimeline;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineChannelBindingMatchingTest {
    private static final String TIMELINE = "owner-timeline";
    private static final String ACTOR = "owner-account";
    private static final TimelineChannelProcessor TIMELINE_PROCESSOR = new TimelineChannelProcessor();

    @Test
    void shouldEnsureThatMatchingTimelineAndActorAccepts() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, TIMELINE, ACTOR));

        // Then
        assertTrue(evaluation.matches());
    }

    @Test
    void shouldEnsureThatDifferentTimelineRejects() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, "different-timeline", ACTOR));

        // Then
        assertFalse(evaluation.matches());
    }

    @Test
    void shouldEnsureThatDifferentActorRejects() {
        // Given
        Fixture fixture = configuredFixture();

        // When
        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, TIMELINE, "different-account"));

        // Then
        assertFalse(evaluation.matches());
    }

    @Test
    void shouldEnsureThatMissingFixedTimelineFieldRejects() {
        // Given
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        // When
        event.getAsNode("/timeline").getProperties().remove("timelineId");

        // Then
        assertFalse(evaluateTimeline(channel(TIMELINE, ACTOR), event).matches());
    }

    @Test
    void shouldEnsureThatMissingFixedActorFieldRejects() {
        // Given
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        // When
        event.getAsNode("/actor").getProperties().remove("accountId");

        // Then
        assertFalse(evaluateTimeline(channel(TIMELINE, ACTOR), event).matches());
    }

    @Test
    void shouldEnsureThatAdditionalTimelineFieldsDoNotReject() {
        // Given
        Fixture fixture = configuredFixture();
        MyOSTimeline configuredTimeline = new MyOSTimeline();
        configuredTimeline.timelineId(TIMELINE);
        MyOSTimeline entryTimeline = new MyOSTimeline();
        entryTimeline.timelineId(TIMELINE);
        Node event = resolvedEvent(fixture, entryTimeline, principal(ACTOR));
        event.getAsNode("/timeline").properties("providerExtension", new Node().value("present"));

        // When
        ChannelEvaluation evaluation = evaluateTimeline(
                channel(configuredTimeline, principal(ACTOR)),
                event);

        // Then
        assertTrue(evaluation.matches());
    }

    @Test
    void shouldEnsureThatAdditionalActorFieldsDoNotReject() {
        // Given
        Fixture fixture = configuredFixture();
        MyOSAgentActor configuredActor = new MyOSAgentActor().accountId(ACTOR);
        MyOSAgentActor entryActor = new MyOSAgentActor().accountId(ACTOR);
        entryActor.onBehalfOf(principal("represented-account"));

        // When
        ChannelEvaluation evaluation = evaluateTimeline(
                channel(timeline(TIMELINE), configuredActor),
                resolvedEvent(fixture, timeline(TIMELINE), entryActor));

        // Then
        assertTrue(evaluation.matches());
    }

    @Test
    void shouldEnsureThatMissingRequiredEntryBindingRejects() {
        // Given
        Fixture fixture = configuredFixture();
        Node missingTimeline = resolvedEvent(fixture, TIMELINE, ACTOR);
        missingTimeline.getProperties().remove("timeline");
        Node missingActor = resolvedEvent(fixture, TIMELINE, ACTOR);
        missingActor.getProperties().remove("actor");

        // When
        TimelineChannel channel = channel(TIMELINE, ACTOR);
        // Then
        assertFalse(evaluateTimeline(channel, missingTimeline).matches());
        assertFalse(evaluateTimeline(channel, missingActor).matches());
    }

    @Test
    void shouldEnsureThatMissingConfiguredBindingRejects() {
        // Given
        Fixture fixture = configuredFixture();
        // When
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);

        // Then
        assertFalse(evaluateTimeline(
                new TimelineChannel().actor(principal(ACTOR)), event).matches());
        assertFalse(evaluateTimeline(
                new TimelineChannel().timeline(timeline(TIMELINE)), event).matches());
    }

    @Test
    void shouldEnsureThatMissingMatchingInputsReject() {
        // Given
        Fixture fixture = configuredFixture();
        // When
        CoordinationEventNodes.TimelineEntryView entry = CoordinationEventNodes.timelineEntry(
                resolvedEvent(fixture, TIMELINE, ACTOR));

        // Then
        assertFalse(TimelineProviderSupport.matchesTimelineAndActor(null, entry));
        assertFalse(TimelineProviderSupport.matchesTimelineAndActor(
                channel(TIMELINE, ACTOR), null));
    }

    @Test
    void shouldEnsureThatCompositeDelegatesCorrectedActorMatch() {
        // Given
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        Map<String, ChannelContract> wrongOnly = channels(
                "wrong", channel(TIMELINE, "different-account"));
        // When
        CompositeTimelineChannel wrongOnlyComposite = new CompositeTimelineChannel()
                .channels(Collections.singletonList("wrong"));

        // Then
        assertFalse(evaluateComposite(wrongOnlyComposite, event, wrongOnly).matches());

        Map<String, ChannelContract> withMatch = channels(
                "wrong", channel(TIMELINE, "different-account"),
                "matching", channel(TIMELINE, ACTOR));
        CompositeTimelineChannel composite = new CompositeTimelineChannel()
                .channels(Arrays.asList("wrong", "matching"));

        ChannelEvaluation evaluation = evaluateComposite(composite, event, withMatch);

        assertTrue(evaluation.matches());
        assertEquals(
                TimelineProviderSupport.eventId(event),
                TimelineProviderSupport.eventId(evaluation.event()));
        assertNull(
                TimelineProviderSupport.property(
                        evaluation.event(),
                        "meta"),
                "Composite delivery must not synthesize metadata");
    }

    @Test
    void shouldEnsureThatAllTimelinesDelegatesCorrectedActorMatch() {
        // Given
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        // When
        Map<String, ChannelContract> wrongOnly = channels(
                "wrong", channel(TIMELINE, "different-account"));

        // Then
        assertFalse(evaluateAll(event, wrongOnly).matches());

        Map<String, ChannelContract> withMatch = channels(
                "wrong", channel(TIMELINE, "different-account"),
                "matching", channel(TIMELINE, ACTOR));

        ChannelEvaluation evaluation = evaluateAll(event, withMatch);

        assertTrue(evaluation.matches());
        assertEquals(
                TimelineProviderSupport.eventId(event),
                TimelineProviderSupport.eventId(evaluation.event()));
        assertNull(
                TimelineProviderSupport.property(
                        evaluation.event(),
                        "meta"),
                "All Timelines delivery must not synthesize metadata");
    }

    private static ChannelEvaluation evaluateTimeline(TimelineChannel channel, Node event) {
        return TIMELINE_PROCESSOR.evaluate(channel, ChannelEvaluationContextFactory.create(
                "timeline",
                event,
                Collections.<String, ChannelContract>emptyMap(),
                noMarkers(),
                TIMELINE_PROCESSOR));
    }

    private static ChannelEvaluation evaluateComposite(CompositeTimelineChannel composite,
                                                       Node event,
                                                       Map<String, ChannelContract> channels) {
        return new CompositeTimelineChannelProcessor().evaluate(composite,
                ChannelEvaluationContextFactory.create(
                        "composite", event, channels, noMarkers(), TIMELINE_PROCESSOR));
    }

    private static ChannelEvaluation evaluateAll(Node event,
                                                 Map<String, ChannelContract> channels) {
        return new AllTimelinesChannelProcessor().evaluate(new AllTimelinesChannel(),
                ChannelEvaluationContextFactory.create(
                        "all", event, channels, noMarkers(), TIMELINE_PROCESSOR));
    }

    private static TimelineChannel channel(String timelineId, String actorId) {
        return channel(timeline(timelineId), principal(actorId));
    }

    private static TimelineChannel channel(Timeline timeline, Actor actor) {
        return new TimelineChannel().timeline(timeline).actor(actor);
    }

    private static PrincipalActor principal(String accountId) {
        return new PrincipalActor().accountId(accountId);
    }

    private static Node resolvedEvent(Fixture fixture, String timelineId, String actorId) {
        return resolvedEvent(fixture,
                timeline(timelineId),
                principal(actorId));
    }

    private static Timeline timeline(String timelineId) {
        return new Timeline().timelineId(timelineId);
    }

    private static Node resolvedEvent(Fixture fixture, Timeline timeline, Actor actor) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(timeline)
                .actor(actor)
                .timestamp(BigInteger.ONE);
        Node event = fixture.blue.objectToNode(entry)
                .properties("timestamp", new Node().value(BigInteger.ONE))
                .properties("message", TestTimelineProvider.chatMessage("hello"))
                .blue(fixture.repository.typeAliasBlue());
        return fixture.blue.resolve(fixture.blue.preprocess(event).blue(null));
    }

    private static Map<String, ChannelContract> channels(String key,
                                                         ChannelContract channel) {
        Map<String, ChannelContract> channels = new LinkedHashMap<String, ChannelContract>();
        channels.put(key, channel);
        return channels;
    }

    private static Map<String, ChannelContract> channels(String firstKey,
                                                         ChannelContract firstChannel,
                                                         String secondKey,
                                                         ChannelContract secondChannel) {
        Map<String, ChannelContract> channels = channels(firstKey, firstChannel);
        channels.put(secondKey, secondChannel);
        return channels;
    }

    private static Map<String, MarkerContract> noMarkers() {
        return Collections.emptyMap();
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
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
