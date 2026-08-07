package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelEvaluationContextFactory;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.HandlerMatchContextFactory;
import blue.language.processor.model.ChannelContract;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Request;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.PrincipalActor;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationRequestRoutingEvaluationTest {
    private static final String SOURCE = "aliceChannel";
    private static final String TARGET = "bobChannel";
    private static final String TIMELINE = "alice-timeline";
    private static final String ACTOR = "alice-account";

    @Test
    void shouldEnsureThatGeneratedOperationRequestRemainsTheExactSingleTimelinePayload() {
        // given
        Fixture fixture = fixture();
        Node event = entry(fixture, request("increment", TARGET, new Node().value(7)));

        // when
        ChannelEvaluation evaluation = evaluate(fixture, event, channels());

        // then
        assertOrdinary(evaluation, event);
        assertEquals(BigInteger.TEN, evaluation.event().get("/timestamp"));
        assertEquals(BigInteger.valueOf(7),
                evaluation.event().get("/message/request"));
    }

    @Test
    void shouldEnsureThatSameChannelTimelineRequestAlsoRemainsAnExactPayload() {
        // given
        Fixture fixture = fixture();
        Map<String, ChannelContract> channels = channels();
        // when
        Node event = entry(fixture, request("increment", SOURCE, new Node().value(7)));

        // then
        assertOrdinary(evaluate(fixture, event, channels), event);
    }

    @Test
    void shouldEnsureThatCompatibleOperationRequestSubtypeRetainsExactFields() {
        // given
        Fixture fixture = fixture();
        Node message = requestWithType(compatibleSubtype(), "increment", TARGET, new Node().value(7))
                .properties("specializedField", new Node().value("preserved"));
        Node event = entry(fixture, TestTimelineProvider.chatMessage("placeholder"))
                .properties("message", message);

        // when
        ChannelEvaluation evaluation = evaluate(fixture, event, channels());

        // then
        assertOrdinary(evaluation, event);
        assertEquals("preserved",
                evaluation.event().get("/message/specializedField"));
    }

    @Test
    void shouldRejectMaterializedOperationRequestTypeWithoutExactIdentity() {
        // given
        Fixture fixture = fixture();
        Node materializedType = fixture.repository
                .nodeByBlueId(OperationRequest.blueId())
                .orElseThrow(() -> new AssertionError(
                        "Operation Request type is absent"))
                .clone()
                .blueId(null);
        Node request = requestWithType(
                materializedType,
                "increment",
                TARGET,
                new Node().value(7));

        // when
        CoordinationEventNodes.OperationRequestView view =
                CoordinationEventNodes.operationRequest(request);

        // then
        assertNull(view,
                "a materialized type definition without its exact declared "
                        + "BlueId must not become an Operation Request");
    }

    @Test
    void shouldEnsureThatUnrelatedRequestSubtypeKeepsOrdinaryDelivery() {
        // given
        Fixture fixture = fixture();
        Node unrelated = requestWithType(new Node().blueId(Request.blueId()),
                "increment", TARGET, new Node().value(7));
        // when
        Node event = entry(fixture, TestTimelineProvider.chatMessage("placeholder"))
                .properties("message", unrelated);

        // then
        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void shouldEnsureThatQualifiedNameAndStructuralLookalikesAreNotRecognized() {
        // given
        Node qualifiedName = request("increment", TARGET, new Node().value(7));
        // when
        Node structural = new Node()
                .properties("operation", new Node().value("increment"))
                .properties("channel", new Node().value(TARGET));

        // then
        assertNull(CoordinationEventNodes.operationRequest(qualifiedName));
        assertNull(CoordinationEventNodes.operationRequest(structural));
    }

    @Test
    void shouldEnsureThatUnavailableTypeClaimIsNotRecognized() {
        // given
        // when
        Node unavailable = requestWithType(
                new Node().blueId("11111111111111111111111111111111"),
                "increment",
                TARGET,
                new Node().value(7));

        // then
        assertNull(CoordinationEventNodes.operationRequest(unavailable));
    }

    @Test
    void shouldEnsureThatAbsentEventAndNonTextRoutingFieldsAreNotRoutable() {
        // given
        // when
        // then
        assertNull(CoordinationEventNodes.operationRequest(null));

        Node nonTextOperation = new Node()
                .type(new Node().blueId(OperationRequest.blueId()))
                .properties("operation", new Node().value(7))
                .properties("channel", new Node().value(TARGET));
        Node nonTextChannel = new Node()
                .type(new Node().blueId(OperationRequest.blueId()))
                .properties("operation", new Node().value("increment"))
                .properties("channel", new Node().value(7));

        assertFalse(CoordinationEventNodes.operationRequest(nonTextOperation).routable());
        assertFalse(CoordinationEventNodes.operationRequest(nonTextChannel).routable());
    }

    @Test
    void shouldEnsureThatMalformedInlineTypeMetadataFailsClosed() {
        // given
        Map<String, Node> malformedProperties = new LinkedHashMap<String, Node>();
        malformedProperties.put("broken", null);
        Node malformedType = new Node()
                .blueId(Request.blueId())
                .properties(malformedProperties);
        // when
        Node request = requestWithType(malformedType,
                "increment", TARGET, new Node().value(7));

        // then
        assertNull(CoordinationEventNodes.operationRequest(request));
    }

    @Test
    void shouldEnsureThatMissingAndBlankOperationKeepOrdinaryDelivery() {
        // given
        Fixture fixture = fixture();
        Node missing = resolvedRequest(fixture, null, TARGET);
        // when
        Node blank = resolvedRequest(fixture, " \t", TARGET);

        // then
        assertOrdinary(evaluate(fixture, entry(fixture, missing), channels()), entry(fixture, missing));
        assertOrdinary(evaluate(fixture, entry(fixture, blank), channels()), entry(fixture, blank));
    }

    @Test
    void shouldEnsureThatMissingAndBlankChannelKeepOrdinaryDelivery() {
        // given
        Fixture fixture = fixture();
        Node missing = resolvedRequest(fixture, "increment", null);
        // when
        Node blank = resolvedRequest(fixture, "increment", " \n");

        // then
        assertOrdinary(evaluate(fixture, entry(fixture, missing), channels()), entry(fixture, missing));
        assertOrdinary(evaluate(fixture, entry(fixture, blank), channels()), entry(fixture, blank));
    }

    @Test
    void shouldEnsureThatUnknownTargetKeepsOrdinaryDelivery() {
        // given
        Fixture fixture = fixture();
        // when
        Node event = entry(fixture, request("increment", "missing", new Node().value(7)));

        // then
        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void shouldEnsureThatOrdinaryTimelineMessageKeepsOrdinaryDelivery() {
        // given
        Fixture fixture = fixture();
        // when
        Node event = entry(fixture, TestTimelineProvider.chatMessage("hello"));

        // then
        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void shouldEnsureThatTargetExternalAcceptanceEvaluatorIsNotInvoked() {
        // given
        Fixture fixture = fixture();
        CountingTimelineProcessor targetProcessor = new CountingTimelineProcessor();
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                SOURCE,
                entry(fixture, request("increment", TARGET, new Node().value(7))),
                channels(),
                Collections.emptyMap(),
                targetProcessor);

        // when
        ChannelEvaluation evaluation = new TimelineChannelProcessor().evaluate(sourceContract(), context);

        // then
        assertTrue(evaluation.matches());
        assertEquals(0, targetProcessor.evaluations);
    }

    @Test
    void shouldEnsureThatUnionPreservesTheExactChildPayloadWithoutSyntheticMetadata() {
        // given
        Node event = new Node()
                .properties("payload", new Node().value("selected"))
                .properties("meta", new Node()
                        .properties("existing", new Node().value("retained")));
        // when
        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionPayload(
                ChannelEvaluation.match(event, "child-event-id"),
                new Node().properties("fallback", new Node().value(true)));

        // then
        assertTrue(evaluation.matches());
        assertEquals("selected", evaluation.event().get("/payload"));
        assertEquals("retained", evaluation.event().get("/meta/existing"));
        assertNull(TimelineProviderSupport.property(
                evaluation.event().getAsNode("/meta"),
                "compositeSourceChannelKey"));
        assertEquals("child-event-id", evaluation.eventId());
    }

    @Test
    void shouldEnsureThatUnionOrdinaryDeliveryUsesFallbackAndPreservesEventId() {
        // given
        Node fallback = new Node().properties("payload", new Node().value("fallback"));

        // when
        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionPayload(
                ChannelEvaluation.match(null, "ordinary-id"),
                fallback);

        // then
        assertTrue(evaluation.matches());
        assertEquals("fallback", evaluation.event().get("/payload"));
        assertNull(TimelineProviderSupport.property(evaluation.event(), "meta"));
        assertEquals("ordinary-id", evaluation.eventId());
    }

    @Test
    void shouldEnsureThatUnionWithoutChildOrFallbackEventDoesNotMatch() {
        // given
        // when
        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionPayload(
                ChannelEvaluation.match(null),
                null);

        // then
        assertFalse(evaluation.matches());
    }

    @Test
    void shouldEnsureThatOperationMatcherRequiresExactEffectiveChannelAndOperationKey() {
        // given
        Fixture fixture = fixture();
        Node event = entry(fixture, request("increment", TARGET, new Node().value(7)));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.request(resolvedPattern(fixture, "Integer"));
        operation.setKey("increment");

        // when
        OperationRequestMatcher matcher = new OperationRequestMatcher();

        // then
        assertTrue(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", TARGET, event)));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", "BobChannel", event)));
        operation.setKey("Increment");
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", TARGET, event)));
    }

    @Test
    void shouldEnsureThatOperationMatcherTreatsPureReferenceMessageLikeInlineRequest() {
        // given
        Fixture fixture = fixture();
        Node requestContent = new Node()
                .name("Referenced Operation Request")
                .type(new Node().blueId(OperationRequest.blueId()))
                .properties("operation", new Node().value("increment"))
                .properties("channel", new Node().value(TARGET))
                .properties("request", new Node().value(7));
        BasicNodeProvider requestProvider =
                new BasicNodeProvider(requestContent);
        String requestBlueId = requestProvider.getBlueIdByName(
                "Referenced Operation Request");
        fixture.blue.addNodeProvider(requestProvider);
        Node event = entry(
                fixture,
                new Node().blueId(requestBlueId));
        SequentialWorkflowOperation operation =
                new SequentialWorkflowOperation();
        operation.request(resolvedPattern(fixture, "Integer"));
        // when
        operation.setKey("increment");

        // then
        assertTrue(new OperationRequestMatcher().matches(
                operation,
                HandlerMatchContextFactory.create(
                        fixture.blue,
                        "increment",
                        TARGET,
                        event)));
    }

    @Test
    void shouldDistinguishMetadataOnlyFromPayloadConstrainedRequestPatterns() {
        // given
        Fixture fixture = fixture();
        Node event = entry(fixture, resolvedRequest(fixture, "run", TARGET));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        // when
        OperationRequestMatcher matcher = new OperationRequestMatcher();

        // then
        assertTrue(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));

        operation.request(resolvedPattern(fixture, "Integer"));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));

        operation.request(new Node().name("Required Request"));
        assertTrue(matcher.matches(operation,
                HandlerMatchContextFactory.create(
                        fixture.blue,
                        "run",
                        TARGET,
                        event)));
    }

    @Test
    void shouldEnsureThatOperationMatcherFailsClosedForMissingInputsAndMalformedRoute() {
        // given
        Fixture fixture = fixture();
        OperationRequestMatcher matcher = new OperationRequestMatcher();
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        // when
        Node validEvent = entry(fixture, resolvedRequest(fixture, "run", TARGET));

        // then
        assertFalse(matcher.matches(null,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, validEvent)));
        assertFalse(matcher.matches(operation, null));

        Node ordinaryEvent = entry(fixture, TestTimelineProvider.chatMessage("ordinary"));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, ordinaryEvent)));

        operation.setKey(" \t");
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, validEvent)));

        operation.setKey(null);
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, validEvent)));

        operation.setKey("run");
        Node malformedEvent = entry(fixture, resolvedRequest(fixture, null, TARGET));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, malformedEvent)));
    }

    @Test
    void shouldEnsureThatExplicitlyEmptyRequestPatternAllowsAbsentPayload() {
        // given
        Fixture fixture = fixture();
        Node event = entry(fixture, resolvedRequest(fixture, "run", TARGET));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        // when
        operation.request(new Node());

        // then
        assertTrue(new OperationRequestMatcher().matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));
    }

    @Test
    void shouldTreatRepositoryDescriptionOnlyRequestAsUnconstrained() {
        // given
        Fixture fixture = fixture();
        Node event = entry(
                fixture,
                resolvedRequest(fixture, "run", TARGET));
        SequentialWorkflowOperation operation =
                new SequentialWorkflowOperation();
        operation.setKey("run");
        operation.request(new Node().description(
                "Repository-authored request documentation"));

        // when
        boolean matched =
                new OperationRequestMatcher().matches(
                        operation,
                        HandlerMatchContextFactory.create(
                                fixture.blue,
                                "run",
                                TARGET,
                                event));

        // then
        assertTrue(matched);
    }

    private static ChannelEvaluation evaluate(Fixture fixture,
                                              Node event,
                                              Map<String, ChannelContract> channels) {
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                SOURCE,
                event,
                channels,
                Collections.emptyMap(),
                new TimelineChannelProcessor());
        return new TimelineChannelProcessor().evaluate(sourceContract(), context);
    }

    private static void assertOrdinary(ChannelEvaluation evaluation, Node expectedEvent) {
        assertTrue(evaluation.matches());
        assertNotNull(evaluation.event());
        assertEquals(TimelineProviderSupport.eventId(expectedEvent),
                TimelineProviderSupport.eventId(evaluation.event()));
    }

    private static Map<String, ChannelContract> channels() {
        Map<String, ChannelContract> channels = new LinkedHashMap<String, ChannelContract>();
        channels.put(SOURCE, sourceContract());
        channels.put(TARGET, targetContract());
        return channels;
    }

    private static TimelineChannel sourceContract() {
        return new TimelineChannel()
                .timeline(new Timeline().timelineId(TIMELINE))
                .actor(new PrincipalActor().accountId(ACTOR));
    }

    private static TimelineChannel targetContract() {
        return new TimelineChannel()
                .timeline(new Timeline().timelineId("bob-timeline"))
                .actor(new PrincipalActor().accountId("bob-account"));
    }

    private static Node entry(Fixture fixture, Node message) {
        return TestTimelineProvider.timelineEntry(fixture.blue,
                fixture.repository,
                TIMELINE,
                ACTOR,
                BigInteger.TEN,
                message);
    }

    private static Node request(String operation, String channel, Node payload) {
        return new Node()
                .type(OperationRequest.qualifiedName())
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel))
                .properties("request", payload);
    }

    private static Node resolvedRequest(Fixture fixture, String operation, String channel) {
        Node request = new Node().type(OperationRequest.qualifiedName());
        if (operation != null) {
            request.properties("operation", new Node().value(operation));
        }
        if (channel != null) {
            request.properties("channel", new Node().value(channel));
        }
        return fixture.blue.preprocess(request.blue(fixture.repository.importsDirective())).blue(null);
    }

    private static Node requestWithType(Node type, String operation, String channel, Node payload) {
        return new Node()
                .type(type)
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel))
                .properties("request", payload);
    }

    private static Node compatibleSubtype() {
        return new Node()
                .name("Specialized Operation Request")
                .type(new Node().blueId(OperationRequest.blueId()));
    }

    private static Node resolvedPattern(Fixture fixture, String type) {
        return fixture.blue.preprocess(new Node()
                .type(type)
                .blue(fixture.repository.importsDirective())).blue(null);
    }

    private static Fixture fixture() {
        BlueRepository repository = BlueRepository.current();
        return new Fixture(repository, CoordinationTestResources.configuredBlue(repository));
    }

    private static final class CountingTimelineProcessor implements ChannelProcessor<TimelineChannel> {
        private int evaluations;

        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TimelineChannel contract, ChannelEvaluationContext context) {
            evaluations++;
            return ChannelEvaluation.noMatch();
        }
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
