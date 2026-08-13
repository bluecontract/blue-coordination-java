package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.RuntimeWorkSession;
import blue.language.snapshot.FrozenNode;
import blue.repo.BlueRepository;
import blue.repo.coordination.Actor;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSAdminActor;
import blue.repo.myos.MyOSTimeline;
import blue.repo.myos.PrincipalActor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineSubscriptionProjectionTest {
    private static final String UNLISTED_TIMELINE_BLUE_ID =
            "FZrvbrXVyURJ7BDokWvbN753NgWV8RVepeKngSMUjPxg";
    private static final String UNLISTED_ACTOR_BLUE_ID =
            "FCNMAeNe8X5LiG8TPfwk6wS9k7uYnxxYyUe6iCAEVSNC";

    @Test
    void shouldBoundMyosSubtypeProjectionToNineUniqueKeys() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            MyOSTimeline timeline = new MyOSTimeline();
            timeline.timelineId("timeline-a");
            MyOSAdminActor actor = new MyOSAdminActor();
            actor.accountId("actor-a");
            TimelineChannel channel = new TimelineChannel()
                    .timeline(timeline)
                    .actor(actor);
            Node event = entry(
                    fixture.blue.objectToNode(timeline),
                    fixture.blue.objectToNode(actor));

            // when
            List<String> eventKeys = eventKeys(
                    fixture, event, Collections.<String, Node>emptyMap());
            List<String> channelKeys =
                    TimelineSubscriptionProjection.channelKeys(channel);

            // then
            assertFalse(eventKeys.isEmpty());
            assertTrue(eventKeys.size() <= 9, eventKeys.toString());
            assertEquals(
                    eventKeys.size(),
                    new LinkedHashSet<String>(eventKeys).size());
            assertTrue(eventKeys.contains(
                    TimelineSubscriptionProjection.BROAD_KEY));
            assertFalse(Collections.disjoint(channelKeys, eventKeys));
        }
    }

    @Test
    void shouldProduceIdenticalKeysForInlineAndReferenceHeaders() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            Node timeline = timeline(fixture, "timeline-a");
            Node actor = actor(fixture, "actor-a");
            Map<String, Node> references =
                    new LinkedHashMap<String, Node>();
            String timelineBlueId =
                    fixture.blue.calculateBlueId(timeline);
            String actorBlueId =
                    fixture.blue.calculateBlueId(actor);
            references.put(timelineBlueId, timeline);
            references.put(actorBlueId, actor);
            Node inline = entry(timeline, actor);
            Node referenced = entry(
                    new Node().blueId(timelineBlueId),
                    new Node().blueId(actorBlueId));
            ExternalChannelFunctionContext context =
                    context(fixture, references);

            // when
            List<String> inlineKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            inline, context);
            List<String> referenceKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            referenced, context);

            // then
            assertFalse(inlineKeys.isEmpty());
            assertEquals(inlineKeys, referenceKeys);
        }
    }

    @Test
    void shouldProjectVerifiedPartialTimelineEntryHeaderLikeInlineEvent() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            Node timeline = timeline(fixture, "timeline-a");
            Node actor = actor(fixture, "actor-a");
            String timelineBlueId =
                    fixture.blue.calculateBlueId(timeline);
            String actorBlueId =
                    fixture.blue.calculateBlueId(actor);
            Node partialHeader = new Node()
                    .type(new Node().blueId(
                            TimelineEntry.blueId()))
                    .properties(
                            "timeline",
                            new Node().blueId(
                                    timelineBlueId))
                    .properties(
                            "actor",
                            new Node().blueId(
                                    actorBlueId));
            String headerBlueId =
                    fixture.blue.calculateBlueId(
                            partialHeader);
            Map<String, Node> references =
                    new LinkedHashMap<String, Node>();
            references.put(timelineBlueId, timeline);
            references.put(actorBlueId, actor);
            references.put(
                    headerBlueId,
                    partialHeader);
            Node inline = entry(timeline, actor);
            Node referencedPartialHeader =
                    new Node().blueId(headerBlueId);
            ExternalChannelFunctionContext context =
                    context(fixture, references);

            // when
            List<String> inlineKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            inline, context);
            List<String> partialKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            referencedPartialHeader,
                            context);

            // then
            assertFalse(partialKeys.isEmpty());
            assertEquals(inlineKeys, partialKeys);
        }
    }

    @Test
    void shouldFailClosedWhenVerifiedTimelineHeaderEvidenceIsUnavailable() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            Node exactHeader = entry(
                    timeline(fixture, "timeline-a"),
                    actor(fixture, "actor-a"));
            Node unavailable =
                    new Node().blueId(
                            fixture.blue.calculateBlueId(
                                    exactHeader));
            ExternalChannelFunctionContext context =
                    context(
                            fixture,
                            Collections.<String, Node>emptyMap());

            // when
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> TimelineSubscriptionProjection
                                    .eventKeys(
                                            unavailable,
                                            context));

            // then
            assertTrue(
                    failure.getMessage().contains(
                            "Missing exact reference"),
                    failure.getMessage());
        }
    }

    @Test
    void shouldFailClosedWhenVerifiedTimelineHeaderEvidenceIsInvalid() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            Node expectedHeader = entry(
                    timeline(fixture, "timeline-a"),
                    actor(fixture, "actor-a"));
            String expectedBlueId =
                    fixture.blue.calculateBlueId(
                            expectedHeader);
            Node invalidContent = entry(
                    timeline(fixture, "timeline-a"),
                    actor(fixture, "different-actor"));
            Map<String, Node> references =
                    new LinkedHashMap<String, Node>();
            references.put(
                    expectedBlueId,
                    invalidContent);
            ExternalChannelFunctionContext context =
                    context(fixture, references);

            // when
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> TimelineSubscriptionProjection
                                    .eventKeys(
                                            new Node().blueId(
                                                    expectedBlueId),
                                            context));

            // then
            assertTrue(
                    failure.getMessage().contains(
                            "does not match exact reference"),
                    failure.getMessage());
        }
    }

    @Test
    void shouldPreserveProjectionAcrossColdAndWarmReferenceMaterialization() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            Node timeline = timeline(
                    fixture, "timeline-a");
            Node actor = actor(
                    fixture, "actor-a");
            String timelineBlueId =
                    fixture.blue.calculateBlueId(
                            timeline);
            String actorBlueId =
                    fixture.blue.calculateBlueId(
                            actor);
            Map<String, Node> references =
                    new LinkedHashMap<String, Node>();
            references.put(timelineBlueId, timeline);
            references.put(actorBlueId, actor);
            Node referenced = entry(
                    new Node().blueId(timelineBlueId),
                    new Node().blueId(actorBlueId));
            ExternalChannelFunctionContext coldContext =
                    context(fixture, references);
            ExternalChannelFunctionContext warmContext =
                    context(fixture, references);
            List<String> inlineKeys =
                    eventKeys(
                            fixture,
                            entry(timeline, actor),
                            Collections.<String, Node>emptyMap());

            // when
            List<String> coldKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            referenced,
                            coldContext);
            TimelineSubscriptionProjection.eventKeys(
                    referenced,
                    warmContext);
            List<String> warmKeys =
                    TimelineSubscriptionProjection.eventKeys(
                            referenced,
                            warmContext);

            // then
            assertEquals(inlineKeys, coldKeys);
            assertEquals(coldKeys, warmKeys);
        }
    }

    @Test
    void shouldUseBroaderKeysForPartialPatterns() {
        // given
        Timeline exactTimeline =
                new Timeline().timelineId("timeline-a");
        TimelineChannel timelineOnly = new TimelineChannel()
                .timeline(exactTimeline)
                .actor(new Actor());
        TimelineChannel fullyBroad = new TimelineChannel()
                .timeline(new Timeline())
                .actor(new Actor());

        // when
        List<String> timelineOnlyKeys =
                TimelineSubscriptionProjection.channelKeys(
                        timelineOnly);
        List<String> broadKeys =
                TimelineSubscriptionProjection.channelKeys(
                        fullyBroad);

        // then
        assertEquals(1, timelineOnlyKeys.size());
        assertTrue(timelineOnlyKeys.get(0).startsWith(
                TimelineSubscriptionProjection.VERSION
                        + ":timeline="));
        assertEquals(
                Collections.singletonList(
                        TimelineSubscriptionProjection.BROAD_KEY),
                broadKeys);
    }

    @Test
    void shouldReturnNoKeysForMalformedTimelineEntryHeaders() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            List<Node> malformed = Arrays.asList(
                    new Node().value("not an event"),
                    new Node()
                            .type(new Node().blueId(
                                    TimelineEntry.blueId()))
                            .properties("timeline",
                                    timeline(fixture, "timeline-a")),
                    new Node()
                            .type(new Node().blueId(
                                    TimelineEntry.blueId()))
                            .properties("actor",
                                    actor(fixture, "actor-a")),
                    entry(
                            new Node().type(
                                    new Node().blueId(
                                            PrincipalActor.blueId())),
                            actor(fixture, "actor-a")),
                    entry(
                            timeline(fixture, "timeline-a"),
                            new Node().type(
                                    new Node().blueId(
                                            Timeline.blueId()))));
            ExternalChannelFunctionContext context =
                    context(fixture, Collections.<String, Node>emptyMap());

            // when
            List<List<String>> projected =
                    new ArrayList<List<String>>();
            for (Node event : malformed) {
                projected.add(
                        TimelineSubscriptionProjection.eventKeys(
                                event, context));
            }

            // then
            for (List<String> keys : projected) {
                assertTrue(keys.isEmpty(), keys.toString());
            }
        }
    }

    @Test
    void shouldNotChargeHeaderReadsForNonTimelineEntryAtZeroGasLimit() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            GasMeter parent = new GasMeter(
                    GasSchedule.contracts10(),
                    0L);
            ExternalChannelFunctionContext context =
                    context(
                            fixture,
                            Collections.<String, Node>emptyMap(),
                            parent);
            Node nonTimelineEntry =
                    new Node().value("not-a-timeline-entry");

            // when
            List<String> keys =
                    TimelineSubscriptionProjection.eventKeys(
                            nonTimelineEntry,
                            context);

            // then
            assertTrue(keys.isEmpty());
            assertTrue(
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .isEmpty());
        }
    }

    @Test
    void shouldChargeExactlyTwoHeaderReadsForTimelineEntry() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            GasMeter parent = new GasMeter(
                    GasSchedule.contracts10(),
                    2L);
            ExternalChannelFunctionContext context =
                    context(
                            fixture,
                            Collections.<String, Node>emptyMap(),
                            parent);
            Node timelineEntry = entry(
                    timeline(fixture, "timeline-a"),
                    actor(fixture, "actor-a"));

            // when
            List<String> keys =
                    TimelineSubscriptionProjection.eventKeys(
                            timelineEntry,
                            context);

            // then
            assertFalse(keys.isEmpty());
            assertEquals(
                    1,
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .size());
            assertEquals(
                    "timelineHeaderRead",
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .counter());
            assertEquals(
                    2L,
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .quantity());
        }
    }

    @Test
    void shouldChargeOnlyTimelineComparisonWhenMismatchShortCircuits() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            GasMeter parent = new GasMeter(
                    GasSchedule.contracts10(),
                    2L);
            ExternalChannelFunctionContext context =
                    context(
                            fixture,
                            Collections.<String, Node>emptyMap(),
                            parent);
            TimelineChannel channel =
                    channel("timeline-a", "actor-a");
            Node differentTimeline = entry(
                    timeline(fixture, "timeline-b"),
                    actor(fixture, "actor-a"));

            // when
            boolean accepted =
                    TimelineExternalSubscriptionFunctions
                            .INSTANCE
                            .accepts(
                                    channel,
                                    differentTimeline,
                                    context);

            // then
            assertFalse(accepted);
            assertEquals(
                    1,
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .size());
            assertEquals(
                    "timelineBindingCompared",
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .counter());
            assertEquals(
                    1L,
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .quantity());
            assertEquals(
                    "compare Timeline binding",
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .reason());
        }
    }

    @Test
    void shouldChargeTimelineAndActorComparisonsForAcceptedEntry() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            GasMeter parent = new GasMeter(
                    GasSchedule.contracts10(),
                    4L);
            ExternalChannelFunctionContext context =
                    context(
                            fixture,
                            Collections.<String, Node>emptyMap(),
                            parent);
            TimelineChannel channel =
                    channel("timeline-a", "actor-a");
            Node matching = entry(
                    timeline(fixture, "timeline-a"),
                    actor(fixture, "actor-a"));

            // when
            boolean accepted =
                    TimelineExternalSubscriptionFunctions
                            .INSTANCE
                            .accepts(
                                    channel,
                                    matching,
                                    context);

            // then
            assertTrue(accepted);
            assertEquals(
                    2,
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .size());
            assertEquals(
                    "compare Timeline binding",
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(0)
                            .reason());
            assertEquals(
                    "compare Actor binding",
                    context.runtimeWorkSession()
                            .stagedTrace()
                            .get(1)
                            .reason());
            for (int index = 0; index < 2; index++) {
                assertEquals(
                        "timelineBindingCompared",
                        context.runtimeWorkSession()
                                .stagedTrace()
                                .get(index)
                                .counter());
                assertEquals(
                        1L,
                        context.runtimeWorkSession()
                                .stagedTrace()
                                .get(index)
                                .quantity());
            }
        }
    }

    @Test
    void shouldIntersectKeysWheneverFinalAcceptanceSucceeds() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            List<TimelineChannel> channels = Arrays.asList(
                    channel("timeline-a", "actor-a"),
                    channel("timeline-b", "actor-b"));
            List<Node> events = Arrays.asList(
                    entry(
                            timeline(fixture, "timeline-a"),
                            actor(fixture, "actor-a")),
                    entry(
                            timeline(fixture, "timeline-a"),
                            actor(fixture, "actor-b")),
                    entry(
                            timeline(fixture, "timeline-b"),
                            actor(fixture, "actor-b")));
            ExternalChannelFunctionContext context =
                    context(fixture, Collections.<String, Node>emptyMap());
            int accepted = 0;

            // when
            for (TimelineChannel channel : channels) {
                for (Node event : events) {
                    if (!TimelineExternalSubscriptionFunctions.INSTANCE
                            .accepts(channel, event, context)) {
                        continue;
                    }
                    accepted++;
                    List<String> channelKeys =
                            TimelineSubscriptionProjection.channelKeys(
                                    channel);
                    List<String> eventKeys =
                            TimelineSubscriptionProjection.eventKeys(
                                    event, context);

                    // then
                    assertFalse(
                            Collections.disjoint(
                                    channelKeys, eventKeys),
                            channelKeys + " vs " + eventKeys);
                }
            }
            assertTrue(accepted > 0);
        }
    }

    @Test
    void shouldRecognizeRegisteredMyosSubtypeMembership() {
        // given
        try (ProjectionFixture fixture = configuredFixture()) {
            MyOSTimeline timeline = new MyOSTimeline();
            timeline.timelineId("timeline-a");
            MyOSAdminActor actor = new MyOSAdminActor();
            actor.accountId("actor-a");
            Node timelineNode = fixture.blue.objectToNode(timeline);
            Node actorNode = fixture.blue.objectToNode(actor);

            // when
            List<String> keys = eventKeys(
                    fixture,
                    entry(timelineNode, actorNode),
                    Collections.<String, Node>emptyMap());

            // then
            assertEquals(
                    MyOSTimeline.class,
                    fixture.blue.getTypeClassResolver()
                            .resolveClass(MyOSTimeline.blueId()));
            assertEquals(
                    MyOSAdminActor.class,
                    fixture.blue.getTypeClassResolver()
                            .resolveClass(MyOSAdminActor.blueId()));
            assertTrue(Timeline.class.isAssignableFrom(
                    fixture.blue.getTypeClassResolver()
                            .resolveClass(MyOSTimeline.blueId())));
            assertTrue(Actor.class.isAssignableFrom(
                    fixture.blue.getTypeClassResolver()
                            .resolveClass(MyOSAdminActor.blueId())));
            assertTrue(contains(keys, MyOSTimeline.blueId()));
            assertTrue(contains(keys, MyOSAdminActor.blueId()));
        }
    }

    private static TimelineChannel channel(
            String timelineId,
            String actorId) {
        return new TimelineChannel()
                .timeline(
                        new Timeline().timelineId(
                                timelineId))
                .actor(
                        new PrincipalActor().accountId(
                                actorId));
    }

    private static Node timeline(
            ProjectionFixture fixture,
            String timelineId) {
        return fixture.blue.objectToNode(
                new Timeline().timelineId(timelineId));
    }

    private static Node actor(
            ProjectionFixture fixture,
            String actorId) {
        return fixture.blue.objectToNode(
                new PrincipalActor().accountId(actorId));
    }

    private static Node entry(
            Node timeline,
            Node actor) {
        return new Node()
                .type(new Node().blueId(
                        TimelineEntry.blueId()))
                .properties("timeline", timeline)
                .properties("actor", actor)
                .properties("timestamp",
                        new Node().value(BigInteger.ONE))
                .properties("message",
                        new Node().value("message"));
    }

    private static List<String> eventKeys(
            ProjectionFixture fixture,
            Node event,
            Map<String, Node> references) {
        return TimelineSubscriptionProjection.eventKeys(
                event, context(fixture, references));
    }

    private static boolean contains(
            List<String> keys,
            String fragment) {
        for (String key : keys) {
            if (key.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static ProjectionFixture configuredFixture() {
        BlueRepository repository =
                BlueRepository.current();
        CoordinationTestRuntime blue =
                CoordinationTestRuntime.create(repository);
        return new ProjectionFixture(blue);
    }

    private static ExternalChannelFunctionContext context(
            ProjectionFixture fixture,
            Map<String, Node> references) {
        return context(
                fixture,
                references,
                new GasMeter());
    }

    private static ExternalChannelFunctionContext context(
            ProjectionFixture fixture,
            Map<String, Node> references,
            GasMeter parent) {
        try {
            Class<?> accessType = Class.forName(
                    "blue.language.processor."
                            + "ExternalChannelFunctionContext$Access");
            InvocationHandler handler =
                    new ProjectionAccess(
                            fixture, references);
            Object access = Proxy.newProxyInstance(
                    accessType.getClassLoader(),
                    new Class<?>[] {accessType},
                    handler);
            RuntimeWorkSession session =
                    runtimeWorkSession(parent);
            Constructor<ExternalChannelFunctionContext> constructor =
                    ExternalChannelFunctionContext.class
                            .getDeclaredConstructor(
                                    String.class,
                                    String.class,
                                    accessType,
                                    RuntimeWorkSession.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    "/", "timeline", access, session);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to construct projection context",
                    exception);
        }
    }

    private static RuntimeWorkSession runtimeWorkSession(
            GasMeter parent)
            throws ReflectiveOperationException {
        Constructor<RuntimeWorkSession> constructor =
                RuntimeWorkSession.class.getDeclaredConstructor(
                        GasMeter.class,
                        RuntimeWorkSession.Mode.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                parent,
                RuntimeWorkSession.Mode.ADMISSION);
    }

    private static final class ProjectionAccess
            implements InvocationHandler {
        private final ProjectionFixture fixture;
        private final Map<String, Node> references;
        private final Map<String, FrozenNode>
                materializedReferences =
                new LinkedHashMap<String, FrozenNode>();

        private ProjectionAccess(
                ProjectionFixture fixture,
                Map<String, Node> references) {
            this.fixture = fixture;
            this.references =
                    new LinkedHashMap<String, Node>(
                            references);
        }

        @Override
        public Object invoke(
                Object proxy,
                Method method,
                Object[] arguments) {
            if ("matchesPattern".equals(
                    method.getName())) {
                return matchesPattern(
                        fixture,
                        (FrozenNode) arguments[0],
                        (FrozenNode) arguments[1]);
            }
            if ("materializeExactReference".equals(
                    method.getName())) {
                FrozenNode reference =
                        (FrozenNode) arguments[0];
                FrozenNode warm =
                        materializedReferences.get(
                                reference
                                        .getReferenceBlueId());
                if (warm != null) {
                    return warm;
                }
                Node materialized = references.get(
                        reference.getReferenceBlueId());
                if (materialized == null) {
                    throw new IllegalArgumentException(
                            "Missing exact reference "
                                    + reference
                                    .getReferenceBlueId());
                }
                if (materialized.isReferenceOnly()) {
                    throw new IllegalStateException(
                            "Verified provider returned a reference "
                                    + "instead of exact content for "
                                    + reference
                                    .getReferenceBlueId());
                }
                String actualBlueId =
                        fixture.blue.calculateBlueId(
                                materialized);
                if (!reference.getReferenceBlueId()
                        .equals(actualBlueId)) {
                    throw new IllegalStateException(
                            "Verified provider content "
                                    + actualBlueId
                                    + " does not match exact reference "
                                    + reference
                                    .getReferenceBlueId());
                }
                FrozenNode verified =
                        FrozenNode.fromResolvedNode(
                                materialized);
                materializedReferences.put(
                        reference.getReferenceBlueId(),
                        verified);
                return verified;
            }
            if ("toString".equals(method.getName())) {
                return "ProjectionAccess";
            }
            if ("hashCode".equals(method.getName())) {
                return Integer.valueOf(
                        System.identityHashCode(proxy));
            }
            if ("equals".equals(method.getName())) {
                return Boolean.valueOf(
                        proxy == arguments[0]);
            }
            throw new UnsupportedOperationException(
                    method.getName());
        }

        private static boolean matchesPattern(
                ProjectionFixture fixture,
                FrozenNode candidate,
                FrozenNode pattern) {
            if (pattern == null) {
                return true;
            }
            if (candidate == null) {
                return false;
            }
            return matchesNode(
                    fixture,
                    candidate.toNode(),
                    pattern.toNode());
        }

        private static boolean matchesNode(
                ProjectionFixture fixture,
                Node candidate,
                Node pattern) {
            if (pattern.getType() != null
                    && !matchesDeclaredType(
                    fixture,
                    candidate.getType(),
                    pattern.getType())) {
                return false;
            }
            if (pattern.getValue() != null
                    && !Objects.equals(
                    pattern.getValue(),
                    candidate.getValue())) {
                return false;
            }
            if (pattern.getProperties() == null) {
                return true;
            }
            if (candidate.getProperties() == null) {
                return false;
            }
            for (Map.Entry<String, Node> entry
                    : pattern.getProperties()
                    .entrySet()) {
                Node candidateProperty =
                        candidate.getProperties().get(
                                entry.getKey());
                if (candidateProperty == null
                        || !matchesNode(
                        fixture,
                        candidateProperty,
                        entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean matchesDeclaredType(
                ProjectionFixture fixture,
                Node candidateType,
                Node patternType) {
            String candidateBlueId =
                    candidateType != null
                            ? candidateType.getBlueId()
                            : null;
            String patternBlueId =
                    patternType != null
                            ? patternType.getBlueId()
                            : null;
            if (candidateBlueId == null
                    || patternBlueId == null) {
                return Objects.equals(
                        candidateBlueId,
                        patternBlueId);
            }
            if (candidateBlueId.equals(
                    patternBlueId)) {
                return true;
            }
            Class<?> candidateClass =
                    fixture.blue.typeClassResolver()
                            .resolveClass(candidateBlueId);
            Class<?> patternClass =
                    fixture.blue.typeClassResolver()
                            .resolveClass(patternBlueId);
            return candidateClass != null
                    && patternClass != null
                    && patternClass.isAssignableFrom(
                    candidateClass);
        }
    }

    private static final class ProjectionFixture
            implements AutoCloseable {
        private final CoordinationTestRuntime blue;

        private ProjectionFixture(CoordinationTestRuntime blue) {
            this.blue = blue;
        }

        @Override
        public void close() {
            blue.close();
        }
    }

    @TypeBlueId(UNLISTED_TIMELINE_BLUE_ID)
    private static final class UnlistedTimeline
            extends Timeline {
        @Override
        public UnlistedTimeline timelineId(
                String timelineId) {
            super.timelineId(timelineId);
            return this;
        }
    }

    @TypeBlueId(UNLISTED_ACTOR_BLUE_ID)
    private static final class UnlistedActor
            extends blue.repo.myos.PrincipalActor {
        @Override
        public UnlistedActor accountId(
                String accountId) {
            super.accountId(accountId);
            return this;
        }
    }
}
