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
import blue.repo.myos.MyOSPrincipalActor;
import blue.repo.myos.MyOSTimeline;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineChannelBindingMatchingTest {
    private static final String TIMELINE = "owner-timeline";
    private static final String ACTOR = "owner-account";
    private static final TimelineChannelProcessor TIMELINE_PROCESSOR = new TimelineChannelProcessor();

    @Test
    void matchingTimelineAndActorAccepts() {
        Fixture fixture = configuredFixture();

        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, TIMELINE, ACTOR));

        assertTrue(evaluation.matches());
    }

    @Test
    void differentTimelineRejects() {
        Fixture fixture = configuredFixture();

        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, "different-timeline", ACTOR));

        assertFalse(evaluation.matches());
    }

    @Test
    void differentActorRejects() {
        Fixture fixture = configuredFixture();

        ChannelEvaluation evaluation = evaluateTimeline(
                channel(TIMELINE, ACTOR),
                resolvedEvent(fixture, TIMELINE, "different-account"));

        assertFalse(evaluation.matches());
    }

    @Test
    void missingFixedTimelineFieldRejects() {
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        event.getAsNode("/timeline").getProperties().remove("timelineId");

        assertFalse(evaluateTimeline(channel(TIMELINE, ACTOR), event).matches());
    }

    @Test
    void missingFixedActorFieldRejects() {
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        event.getAsNode("/actor").getProperties().remove("accountId");

        assertFalse(evaluateTimeline(channel(TIMELINE, ACTOR), event).matches());
    }

    @Test
    void additionalTimelineFieldsDoNotReject() {
        Fixture fixture = configuredFixture();
        MyOSTimeline configuredTimeline = new MyOSTimeline();
        configuredTimeline.timelineId(TIMELINE);
        MyOSTimeline entryTimeline = new MyOSTimeline().accountId("provider-account");
        entryTimeline.timelineId(TIMELINE);

        ChannelEvaluation evaluation = evaluateTimeline(
                channel(configuredTimeline, principal(ACTOR)),
                resolvedEvent(fixture, entryTimeline, principal(ACTOR)));

        assertTrue(evaluation.matches());
    }

    @Test
    void additionalActorFieldsDoNotReject() {
        Fixture fixture = configuredFixture();
        MyOSAgentActor configuredActor = new MyOSAgentActor().accountId(ACTOR);
        MyOSAgentActor entryActor = new MyOSAgentActor().accountId(ACTOR);
        entryActor.onBehalfOf(principal("represented-account"));

        ChannelEvaluation evaluation = evaluateTimeline(
                channel(new Timeline().timelineId(TIMELINE), configuredActor),
                resolvedEvent(fixture, new Timeline().timelineId(TIMELINE), entryActor));

        assertTrue(evaluation.matches());
    }

    @Test
    void missingRequiredEntryBindingRejects() {
        Fixture fixture = configuredFixture();
        Node missingTimeline = resolvedEvent(fixture, TIMELINE, ACTOR);
        missingTimeline.getProperties().remove("timeline");
        Node missingActor = resolvedEvent(fixture, TIMELINE, ACTOR);
        missingActor.getProperties().remove("actor");

        TimelineChannel channel = channel(TIMELINE, ACTOR);
        assertFalse(evaluateTimeline(channel, missingTimeline).matches());
        assertFalse(evaluateTimeline(channel, missingActor).matches());
    }

    @Test
    void missingConfiguredBindingRejects() {
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);

        assertFalse(evaluateTimeline(
                new TimelineChannel().actor(principal(ACTOR)), event).matches());
        assertFalse(evaluateTimeline(
                new TimelineChannel().timeline(new Timeline().timelineId(TIMELINE)), event).matches());
    }

    @Test
    void missingMatchingInputsReject() {
        Fixture fixture = configuredFixture();
        CoordinationEventNodes.TimelineEntryView entry = CoordinationEventNodes.timelineEntry(
                resolvedEvent(fixture, TIMELINE, ACTOR));

        assertFalse(TimelineProviderSupport.matchesTimelineAndActor(null, entry));
        assertFalse(TimelineProviderSupport.matchesTimelineAndActor(
                channel(TIMELINE, ACTOR), null));
    }

    @Test
    void compositeDelegatesCorrectedActorMatch() {
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        Map<String, ChannelContract> wrongOnly = channels(
                "wrong", channel(TIMELINE, "different-account"));
        CompositeTimelineChannel wrongOnlyComposite = new CompositeTimelineChannel()
                .channels(Collections.singletonList("wrong"));

        assertFalse(evaluateComposite(wrongOnlyComposite, event, wrongOnly).matches());

        Map<String, ChannelContract> withMatch = channels(
                "wrong", channel(TIMELINE, "different-account"),
                "matching", channel(TIMELINE, ACTOR));
        CompositeTimelineChannel composite = new CompositeTimelineChannel()
                .channels(Arrays.asList("wrong", "matching"));

        ChannelEvaluation evaluation = evaluateComposite(composite, event, withMatch);

        assertTrue(evaluation.matches());
        assertEquals("matching", evaluation.event().getAsText("/meta/compositeSourceChannelKey"));
    }

    @Test
    void allTimelinesDelegatesCorrectedActorMatch() {
        Fixture fixture = configuredFixture();
        Node event = resolvedEvent(fixture, TIMELINE, ACTOR);
        Map<String, ChannelContract> wrongOnly = channels(
                "wrong", channel(TIMELINE, "different-account"));

        assertFalse(evaluateAll(event, wrongOnly).matches());

        Map<String, ChannelContract> withMatch = channels(
                "wrong", channel(TIMELINE, "different-account"),
                "matching", channel(TIMELINE, ACTOR));

        ChannelEvaluation evaluation = evaluateAll(event, withMatch);

        assertTrue(evaluation.matches());
        assertEquals("matching", evaluation.event().getAsText("/meta/allTimelinesSourceChannelKey"));
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
        return channel(new Timeline().timelineId(timelineId), principal(actorId));
    }

    private static TimelineChannel channel(Timeline timeline, Actor actor) {
        return new TimelineChannel().timeline(timeline).actor(actor);
    }

    private static MyOSPrincipalActor principal(String accountId) {
        return new MyOSPrincipalActor().accountId(accountId);
    }

    private static Node resolvedEvent(Fixture fixture, String timelineId, String actorId) {
        return resolvedEvent(fixture,
                new Timeline().timelineId(timelineId),
                principal(actorId));
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
