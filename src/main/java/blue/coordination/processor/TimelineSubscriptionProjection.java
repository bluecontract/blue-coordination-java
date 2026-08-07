package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.GasChargeContext;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.TypeClassResolver;
import blue.repo.coordination.Actor;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finite, representation-blind subscription projection for Timeline entries.
 *
 * <p>The selective forms are canonical projections of the fixed repository's
 * immutable equality bindings. Each discriminator includes the declared
 * binding type and the Blue identity of its exact scalar identifier. This
 * prevents, for example, Principal and Agent actors that share an account ID
 * from colliding. Bindings with additional pattern structure deliberately use
 * a bounded broader key; the processor-owned matcher remains authoritative.</p>
 */
final class TimelineSubscriptionProjection {
    static final String VERSION =
            "blue.coordination/1.0/timeline-entry-projection-v3";
    static final String BROAD_KEY = VERSION + ":broad";
    private static final String TIMELINE_FIELD = "timelineId";
    private static final String ACTOR_FIELD = "accountId";
    private static final Map<String, Class<?>> REGISTERED_TYPES =
            registeredTypes();
    private static final List<String> TIMELINE_PROJECTION_TYPES =
            registeredProjectionTypes(
                    Timeline.class, "getTimelineId");
    private static final List<String> ACTOR_PROJECTION_TYPES =
            registeredProjectionTypes(
                    Actor.class, "getAccountId");

    private TimelineSubscriptionProjection() {
    }

    static List<String> channelKeys(TimelineChannel channel) {
        return channelKeys(
                channel,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static List<String> channelKeys(
            TimelineChannel channel,
            CoordinationSemanticTypeIdentities identities) {
        if (channel == null
                || channel.getTimeline() == null
                || channel.getActor() == null) {
            throw new IllegalArgumentException(
                    "Timeline Channel requires immutable timeline and actor headers");
        }
        Projection timeline = channelDiscriminator(
                channel.getTimeline(),
                Timeline.class,
                projectionTypes(
                        identities.timelineBlueId(),
                        TIMELINE_PROJECTION_TYPES,
                        identities),
                identities.timelineBlueId(),
                TIMELINE_FIELD);
        Projection actor = channelDiscriminator(
                channel.getActor(),
                Actor.class,
                projectionTypes(
                        identities.actorBlueId(),
                        ACTOR_PROJECTION_TYPES,
                        identities),
                identities.actorBlueId(),
                ACTOR_FIELD);
        String selective = mostSelectiveKey(
                timeline.discriminator,
                actor.discriminator);
        if (BROAD_KEY.equals(selective)) {
            return Collections.singletonList(BROAD_KEY);
        }
        LinkedHashSet<String> keys =
                new LinkedHashSet<String>();
        keys.add(selective);
        if (timeline.requiresBroadFallback
                || actor.requiresBroadFallback) {
            keys.add(BROAD_KEY);
        }
        return Collections.unmodifiableList(
                new ArrayList<String>(keys));
    }

    static List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context) {
        return eventKeys(
                exactEvent,
                context,
                CoordinationSemanticTypeIdentities.publishedDefaults());
    }

    static String exactScalarPairKey(
            String timelineId,
            String actorId,
            CoordinationSemanticTypeIdentities identities) {
        String timelineScalar = scalarIdentity(
                new Node().value(requireText(timelineId, "timelineId")));
        String actorScalar = scalarIdentity(
                new Node().value(requireText(actorId, "actorId")));
        return pairKey(
                canonicalDiscriminator(
                        identities.timelineBlueId(),
                        TIMELINE_FIELD,
                        timelineScalar),
                canonicalDiscriminator(
                        identities.actorBlueId(),
                        ACTOR_FIELD,
                        actorScalar));
    }

    static List<String> exactScalarEventKeys(
            String timelineId,
            String actorId,
            CoordinationSemanticTypeIdentities identities) {
        String timelineScalar = scalarIdentity(
                new Node().value(requireText(timelineId, "timelineId")));
        String actorScalar = scalarIdentity(
                new Node().value(requireText(actorId, "actorId")));
        String timeline = canonicalDiscriminator(
                identities.timelineBlueId(),
                TIMELINE_FIELD,
                timelineScalar);
        String actor = canonicalDiscriminator(
                identities.actorBlueId(),
                ACTOR_FIELD,
                actorScalar);
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        result.add(pairKey(timeline, actor));
        result.add(timelineKey(timeline));
        result.add(actorKey(actor));
        result.add(BROAD_KEY);
        return Collections.unmodifiableList(
                new ArrayList<String>(result));
    }

    static List<String> eventKeys(
            Node exactEvent,
            ExternalChannelFunctionContext context,
            CoordinationSemanticTypeIdentities identities) {
        Node header = CoordinationEventNodes.materializeHeaderValue(
                exactEvent, context);
        if (!CoordinationEventNodes.isTimelineEntry(
                header, context, identities)
                || header.getProperties() == null) {
            return Collections.emptyList();
        }
        CoordinationRuntimeGas.charge(
                context.runtimeWorkSession(),
                "timelineHeaderRead",
                2L,
                GasChargeContext.of(
                        context.scopePath(),
                        context.channelKey(),
                        null,
                        "read Timeline Entry timeline/actor projection"));
        Node suppliedTimeline =
                header.getProperties().get("timeline");
        Node suppliedActor =
                header.getProperties().get("actor");
        if (suppliedTimeline == null
                || suppliedActor == null) {
            return Collections.emptyList();
        }
        Node timeline = CoordinationEventNodes.materializeHeaderValue(
                suppliedTimeline, context);
        Node actor = CoordinationEventNodes.materializeHeaderValue(
                suppliedActor, context);
        if (!matchesType(
                timeline, identities.timelineBlueId(), context)
                || !matchesType(
                actor, identities.actorBlueId(), context)) {
            return Collections.emptyList();
        }

        Set<String> timelineKeys =
                eventDiscriminators(
                        timeline,
                        identities.timelineBlueId(),
                        projectionTypes(
                                identities.timelineBlueId(),
                                TIMELINE_PROJECTION_TYPES,
                                identities),
                        TIMELINE_FIELD,
                        context);
        if (timelineKeys.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> actorKeys =
                eventDiscriminators(
                        actor,
                        identities.actorBlueId(),
                        projectionTypes(
                                identities.actorBlueId(),
                                ACTOR_PROJECTION_TYPES,
                                identities),
                        ACTOR_FIELD,
                        context);

        LinkedHashSet<String> keys =
                new LinkedHashSet<String>();
        for (String timelineKey : timelineKeys) {
            for (String actorKey : actorKeys) {
                keys.add(pairKey(timelineKey, actorKey));
            }
        }
        for (String timelineKey : timelineKeys) {
            keys.add(timelineKey(timelineKey));
        }
        for (String actorKey : actorKeys) {
            keys.add(actorKey(actorKey));
        }
        keys.add(BROAD_KEY);
        return Collections.unmodifiableList(
                new ArrayList<String>(keys));
    }

    private static Projection channelDiscriminator(
            Object configuredBinding,
            Class<?> baseClass,
            List<String> registeredFamily,
            String configuredTypeBlueId,
            String scalarField) {
        if (!baseClass.isInstance(configuredBinding)) {
            return Projection.none();
        }
        Node binding =
                CoordinationEventNodes.generatedBindingNode(
                        configuredBinding);
        String type = configuredTypeBlueId;
        String discriminator = exactScalarProjection(
                binding,
                type,
                scalarField,
                Collections.singleton(scalarField));
        return discriminator != null
                ? Projection.selective(
                        discriminator,
                        !registeredFamily.contains(type))
                : Projection.none();
    }

    private static List<String> projectionTypes(
            String configuredBlueId,
            List<String> publishedTypes,
            CoordinationSemanticTypeIdentities identities) {
        return identities.custom()
                ? Collections.singletonList(configuredBlueId)
                : publishedTypes;
    }

    /**
     * Enumerates the scalar-bearing registered subtype family generically.
     *
     * <p>The generated repository registry supplies only candidate type
     * identities. The event-scoped Language matcher remains authoritative for
     * every same-or-subtype relationship, so the registry cannot turn
     * unavailable or inconsistent provider evidence into a match. An exact
     * valid subtype that is registered after this fixed catalog was built is
     * still projected under its own declared type; the corresponding channel
     * carries the broad fallback until that type becomes part of the fixed
     * registry.</p>
     */
    private static Set<String> eventDiscriminators(
            Node exact,
            String baseTypeBlueId,
            List<String> registeredFamily,
            String scalarField,
            ExternalChannelFunctionContext context) {
        Node suppliedScalar =
                property(exact, scalarField);
        Node exactScalar = suppliedScalar != null
                && suppliedScalar.isReferenceOnly()
                ? CoordinationEventNodes.materializeHeaderValue(
                suppliedScalar, context)
                : suppliedScalar;
        String scalar = scalarIdentity(exactScalar);
        if (scalar == null
                || !matchesType(
                exact, baseTypeBlueId, context)) {
            return Collections.emptySet();
        }

        LinkedHashSet<String> result =
                new LinkedHashSet<String>();
        for (String candidateType : registeredFamily) {
            if (matchesType(
                    exact, candidateType, context)) {
                result.add(canonicalDiscriminator(
                        candidateType,
                        scalarField,
                        scalar));
            }
        }
        String exactType = declaredTypeBlueId(exact);
        if (exactType != null) {
            result.add(canonicalDiscriminator(
                    exactType,
                    scalarField,
                    scalar));
        }
        return Collections.unmodifiableSet(result);
    }

    private static boolean matchesType(
            Node exact,
            String typeBlueId,
            ExternalChannelFunctionContext context) {
        return exact != null
                && context.matchesPattern(
                exact,
                new Node().type(
                        new Node().blueId(
                                typeBlueId)));
    }

    private static String exactScalarProjection(
            Node binding,
            String typeBlueId,
            String scalarField,
            Set<String> allowedProperties) {
        if (binding == null
                || typeBlueId == null
                || binding.isReferenceOnly()
                || hasOtherProperties(
                binding.getProperties(),
                allowedProperties)) {
            return null;
        }
        String scalar = scalarIdentity(
                property(binding, scalarField));
        return scalar != null
                ? canonicalDiscriminator(
                typeBlueId,
                scalarField,
                scalar)
                : null;
    }

    private static boolean hasOtherProperties(
            Map<String, Node> properties,
            Set<String> allowed) {
        if (properties == null) {
            return false;
        }
        for (Map.Entry<String, Node> entry
                : properties.entrySet()) {
            if (entry.getValue() != null
                    && !allowed.contains(
                    entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static Node property(
            Node node,
            String key) {
        return node != null
                && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static String declaredTypeBlueId(Node node) {
        Node type = node != null
                ? node.getType()
                : null;
        return type != null
                ? type.getBlueId()
                : null;
    }

    private static String scalarIdentity(Node value) {
        if (value == null
                || value.isReferenceOnly()
                || !(value.getValue()
                instanceof String)) {
            return null;
        }
        return DirectBlueIdCalculator.calculateBlueId(
                new Node().value(
                        value.getValue()));
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }

    private static String canonicalDiscriminator(
            String typeBlueId,
            String field,
            String scalarBlueId) {
        return "type-"
                + typeBlueId
                + ":"
                + field
                + "-"
                + scalarBlueId;
    }

    private static String mostSelectiveKey(
            String timeline,
            String actor) {
        if (timeline != null && actor != null) {
            return pairKey(timeline, actor);
        }
        if (timeline != null) {
            return timelineKey(timeline);
        }
        if (actor != null) {
            return actorKey(actor);
        }
        return BROAD_KEY;
    }

    private static String pairKey(
            String timeline,
            String actor) {
        return VERSION
                + ":timeline="
                + timeline
                + ":actor="
                + actor;
    }

    private static String timelineKey(String timeline) {
        return VERSION + ":timeline=" + timeline;
    }

    private static String actorKey(String actor) {
        return VERSION + ":actor=" + actor;
    }

    private static Map<String, Class<?>> registeredTypes() {
        TypeClassResolver resolver =
                new TypeClassResolver("blue.repo");
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, Class<?>>(
                        resolver.getBlueIdMap()));
    }

    private static List<String> registeredProjectionTypes(
            final Class<?> baseClass,
            String scalarAccessor) {
        List<Map.Entry<String, Class<?>>> candidates =
                new ArrayList<Map.Entry<String, Class<?>>>();
        for (Map.Entry<String, Class<?>> entry
                : REGISTERED_TYPES.entrySet()) {
            Class<?> candidateClass = entry.getValue();
            if (!baseClass.isAssignableFrom(candidateClass)
                    || !hasScalarAccessor(
                    candidateClass, scalarAccessor)) {
                continue;
            }
            candidates.add(entry);
        }
        Collections.sort(
                candidates,
                new Comparator<Map.Entry<String, Class<?>>>() {
                    @Override
                    public int compare(
                            Map.Entry<String, Class<?>> left,
                            Map.Entry<String, Class<?>> right) {
                        int leftDepth = inheritanceDepth(
                                baseClass, left.getValue());
                        int rightDepth = inheritanceDepth(
                                baseClass, right.getValue());
                        if (leftDepth != rightDepth) {
                            return leftDepth < rightDepth
                                    ? -1
                                    : 1;
                        }
                        return left.getKey().compareTo(
                                right.getKey());
                    }
                });
        List<String> result =
                new ArrayList<String>(
                        candidates.size());
        for (Map.Entry<String, Class<?>> candidate
                : candidates) {
            result.add(candidate.getKey());
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean hasScalarAccessor(
            Class<?> candidateClass,
            String scalarAccessor) {
        try {
            return String.class.equals(
                    candidateClass
                            .getMethod(scalarAccessor)
                            .getReturnType());
        } catch (NoSuchMethodException missingScalar) {
            return false;
        }
    }

    private static int inheritanceDepth(
            Class<?> baseClass,
            Class<?> candidateClass) {
        int depth = 0;
        Class<?> current = candidateClass;
        while (current != null
                && !baseClass.equals(current)) {
            current = current.getSuperclass();
            depth++;
        }
        return current != null
                ? depth
                : Integer.MAX_VALUE;
    }

    private static final class Projection {
        private static final Projection NONE =
                new Projection(null, false);

        private final String discriminator;
        private final boolean requiresBroadFallback;

        private Projection(
                String discriminator,
                boolean requiresBroadFallback) {
            this.discriminator = discriminator;
            this.requiresBroadFallback =
                    requiresBroadFallback;
        }

        private static Projection none() {
            return NONE;
        }

        private static Projection selective(
                String discriminator,
                boolean requiresBroadFallback) {
            return new Projection(
                    discriminator,
                    requiresBroadFallback);
        }
    }
}
