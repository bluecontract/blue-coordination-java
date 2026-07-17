package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelDelivery;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelEvaluationContextFactory;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.HandlerMatchContextFactory;
import blue.language.processor.model.ChannelContract;
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
import java.util.List;
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
    void generatedOperationRequestRoutesToDeclaredChannel() {
        Fixture fixture = fixture();
        Node event = entry(fixture, request("increment", TARGET, new Node().value(7)));

        ChannelEvaluation evaluation = evaluate(fixture, event, channels());

        ChannelDelivery delivery = onlyDelivery(evaluation);
        assertEquals(TARGET, delivery.handlerChannelKey());
        assertEquals(OperationRequest.blueId() + ":increment", delivery.logicalDeliveryKey());
        assertNull(delivery.checkpointKey());
        assertNull(delivery.shouldProcess());
        assertNull(delivery.eventId());
        assertEquals(BigInteger.TEN, delivery.event().get("/timestamp"));
        assertEquals(BigInteger.valueOf(7), delivery.event().get("/message/request"));
    }

    @Test
    void sameChannelTimelineRequestUsesTheSameRoutedPath() {
        Fixture fixture = fixture();
        Map<String, ChannelContract> channels = channels();
        Node event = entry(fixture, request("increment", SOURCE, new Node().value(7)));

        ChannelDelivery delivery = onlyDelivery(evaluate(fixture, event, channels));

        assertEquals(SOURCE, delivery.handlerChannelKey());
        assertEquals(OperationRequest.blueId() + ":increment", delivery.logicalDeliveryKey());
    }

    @Test
    void compatibleOperationRequestSubtypeInheritsRoutingAndRetainsFields() {
        Fixture fixture = fixture();
        Node message = requestWithType(compatibleSubtype(fixture), "increment", TARGET, new Node().value(7))
                .properties("specializedField", new Node().value("preserved"));
        Node event = entry(fixture, TestTimelineProvider.chatMessage("placeholder"))
                .properties("message", message);

        ChannelDelivery delivery = onlyDelivery(evaluate(fixture, event, channels()));

        assertEquals(TARGET, delivery.handlerChannelKey());
        assertEquals("preserved", delivery.event().get("/message/specializedField"));
    }

    @Test
    void unrelatedRequestSubtypeKeepsOrdinaryDelivery() {
        Fixture fixture = fixture();
        Node unrelated = requestWithType(new Node().blueId(Request.blueId()),
                "increment", TARGET, new Node().value(7));
        Node event = entry(fixture, TestTimelineProvider.chatMessage("placeholder"))
                .properties("message", unrelated);

        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void qualifiedNameAndStructuralLookalikesAreNotRecognized() {
        Node qualifiedName = request("increment", TARGET, new Node().value(7));
        Node structural = new Node()
                .properties("operation", new Node().value("increment"))
                .properties("channel", new Node().value(TARGET));

        assertNull(CoordinationEventNodes.operationRequest(qualifiedName));
        assertNull(CoordinationEventNodes.operationRequest(structural));
    }

    @Test
    void unavailableTypeClaimIsNotRecognized() {
        Node unavailable = requestWithType(
                new Node().blueId("11111111111111111111111111111111"),
                "increment",
                TARGET,
                new Node().value(7));

        assertNull(CoordinationEventNodes.operationRequest(unavailable));
    }

    @Test
    void absentEventAndNonTextRoutingFieldsAreNotRoutable() {
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
    void malformedInlineTypeMetadataFailsClosed() {
        Map<String, Node> malformedProperties = new LinkedHashMap<String, Node>();
        malformedProperties.put("broken", null);
        Node malformedType = new Node()
                .blueId(Request.blueId())
                .properties(malformedProperties);
        Node request = requestWithType(malformedType,
                "increment", TARGET, new Node().value(7));

        assertNull(CoordinationEventNodes.operationRequest(request));
    }

    @Test
    void missingAndBlankOperationKeepOrdinaryDelivery() {
        Fixture fixture = fixture();
        Node missing = resolvedRequest(fixture, null, TARGET);
        Node blank = resolvedRequest(fixture, " \t", TARGET);

        assertOrdinary(evaluate(fixture, entry(fixture, missing), channels()), entry(fixture, missing));
        assertOrdinary(evaluate(fixture, entry(fixture, blank), channels()), entry(fixture, blank));
    }

    @Test
    void missingAndBlankChannelKeepOrdinaryDelivery() {
        Fixture fixture = fixture();
        Node missing = resolvedRequest(fixture, "increment", null);
        Node blank = resolvedRequest(fixture, "increment", " \n");

        assertOrdinary(evaluate(fixture, entry(fixture, missing), channels()), entry(fixture, missing));
        assertOrdinary(evaluate(fixture, entry(fixture, blank), channels()), entry(fixture, blank));
    }

    @Test
    void unknownTargetKeepsOrdinaryDelivery() {
        Fixture fixture = fixture();
        Node event = entry(fixture, request("increment", "missing", new Node().value(7)));

        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void ordinaryTimelineMessageKeepsOrdinaryDelivery() {
        Fixture fixture = fixture();
        Node event = entry(fixture, TestTimelineProvider.chatMessage("hello"));

        assertOrdinary(evaluate(fixture, event, channels()), event);
    }

    @Test
    void targetExternalAcceptanceEvaluatorIsNotInvoked() {
        Fixture fixture = fixture();
        CountingTimelineProcessor targetProcessor = new CountingTimelineProcessor();
        ChannelEvaluationContext context = ChannelEvaluationContextFactory.create(
                SOURCE,
                entry(fixture, request("increment", TARGET, new Node().value(7))),
                channels(),
                Collections.emptyMap(),
                targetProcessor);

        ChannelEvaluation evaluation = new TimelineChannelProcessor().evaluate(sourceContract(), context);

        assertTrue(evaluation.matches());
        assertEquals(0, targetProcessor.evaluations);
    }

    @Test
    void unionDeliveryPreservesRouteMetadataAndOwnsItsCheckpoint() {
        Node event = new Node()
                .properties("payload", new Node().value("selected"))
                .properties("meta", new Node()
                        .properties("existing", new Node().value("retained")));
        ChannelDelivery child = ChannelDelivery.of(event,
                "child-event-id",
                "child-checkpoint",
                Boolean.FALSE,
                TARGET,
                "logical-route");

        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionDelivery(
                ChannelEvaluation.matchDeliveries(Collections.singletonList(child)),
                new Node().properties("fallback", new Node().value(true)),
                "compositeSourceChannelKey",
                SOURCE);

        ChannelDelivery union = onlyDelivery(evaluation);
        assertEquals("selected", union.event().get("/payload"));
        assertEquals("retained", union.event().get("/meta/existing"));
        assertEquals(SOURCE, union.event().get("/meta/compositeSourceChannelKey"));
        assertEquals("child-event-id", union.eventId());
        assertNull(union.checkpointKey());
        assertEquals(Boolean.FALSE, union.shouldProcess());
        assertEquals(TARGET, union.handlerChannelKey());
        assertEquals("logical-route", union.logicalDeliveryKey());
    }

    @Test
    void unionOrdinaryDeliveryUsesFallbackAndPreservesEventId() {
        Node fallback = new Node().properties("payload", new Node().value("fallback"));

        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionDelivery(
                ChannelEvaluation.match(null, "ordinary-id"),
                fallback,
                "compositeSourceChannelKey",
                SOURCE);

        assertTrue(evaluation.matches());
        assertEquals("fallback", evaluation.event().get("/payload"));
        assertEquals(SOURCE, evaluation.event().get("/meta/compositeSourceChannelKey"));
        assertEquals("ordinary-id", evaluation.eventId());
    }

    @Test
    void unionWithoutChildOrFallbackEventDoesNotMatch() {
        ChannelEvaluation evaluation = TimelineProviderSupport.preserveUnionDelivery(
                ChannelEvaluation.match(null),
                null,
                "compositeSourceChannelKey",
                SOURCE);

        assertFalse(evaluation.matches());
    }

    @Test
    void operationMatcherRequiresExactEffectiveChannelAndOperationKey() {
        Fixture fixture = fixture();
        Node event = entry(fixture, request("increment", TARGET, new Node().value(7)));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.request(resolvedPattern(fixture, "Integer"));
        operation.setKey("increment");

        OperationRequestMatcher matcher = new OperationRequestMatcher();

        assertTrue(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", TARGET, event)));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", "BobChannel", event)));
        operation.setKey("Increment");
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "increment", TARGET, event)));
    }

    @Test
    void requestMayBeAbsentOnlyForAnEmptyOperationPattern() {
        Fixture fixture = fixture();
        Node event = entry(fixture, resolvedRequest(fixture, "run", TARGET));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        OperationRequestMatcher matcher = new OperationRequestMatcher();

        assertTrue(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));

        operation.request(resolvedPattern(fixture, "Integer"));
        assertFalse(matcher.matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));
    }

    @Test
    void operationMatcherFailsClosedForMissingInputsAndMalformedRoute() {
        Fixture fixture = fixture();
        OperationRequestMatcher matcher = new OperationRequestMatcher();
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        Node validEvent = entry(fixture, resolvedRequest(fixture, "run", TARGET));

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
    void explicitlyEmptyRequestPatternAllowsAbsentPayload() {
        Fixture fixture = fixture();
        Node event = entry(fixture, resolvedRequest(fixture, "run", TARGET));
        SequentialWorkflowOperation operation = new SequentialWorkflowOperation();
        operation.setKey("run");
        operation.request(new Node());

        assertTrue(new OperationRequestMatcher().matches(operation,
                HandlerMatchContextFactory.create(fixture.blue, "run", TARGET, event)));
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
        assertTrue(evaluation.deliveries().isEmpty());
        assertNotNull(evaluation.event());
        assertEquals(TimelineProviderSupport.eventId(expectedEvent),
                TimelineProviderSupport.eventId(evaluation.event()));
    }

    private static ChannelDelivery onlyDelivery(ChannelEvaluation evaluation) {
        assertTrue(evaluation.matches());
        List<ChannelDelivery> deliveries = evaluation.deliveries();
        assertEquals(1, deliveries.size());
        return deliveries.get(0);
    }

    private static Map<String, ChannelContract> channels() {
        Map<String, ChannelContract> channels = new LinkedHashMap<String, ChannelContract>();
        channels.put(SOURCE, sourceContract());
        channels.put(TARGET, targetContract());
        return channels;
    }

    private static TimelineChannel sourceContract() {
        return new TimelineChannel()
                .timeline(new Timeline().providerId("test-provider").timelineId(TIMELINE))
                .actor(new PrincipalActor().accountId(ACTOR));
    }

    private static TimelineChannel targetContract() {
        return new TimelineChannel()
                .timeline(new Timeline().providerId("test-provider").timelineId("bob-timeline"))
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
        return fixture.blue.preprocess(request.blue(fixture.repository.typeAliasBlue())).blue(null);
    }

    private static Node requestWithType(Node type, String operation, String channel, Node payload) {
        return new Node()
                .type(type)
                .properties("operation", new Node().value(operation))
                .properties("channel", new Node().value(channel))
                .properties("request", payload);
    }

    private static Node compatibleSubtype(Fixture fixture) {
        Node subtype = new Node()
                .name("Specialized Operation Request")
                .type(new Node().blueId(OperationRequest.blueId()));
        return subtype.blueId(blue.language.utils.BlueIdCalculator.calculateBlueId(subtype));
    }

    private static Node resolvedPattern(Fixture fixture, String type) {
        return fixture.blue.preprocess(new Node()
                .type(type)
                .blue(fixture.repository.typeAliasBlue())).blue(null);
    }

    private static Fixture fixture() {
        BlueRepository repository = BlueRepository.latest();
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
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
