package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class SourceSurfaceIdentityTest {
    @Test
    void unrelatedBusinessStateDoesNotInvalidateCompletenessIdentity() {
        EmbeddingBinding stateA = binding(
                "parent", "/child", "child", "state-A", 1L);
        EmbeddingBinding stateB = binding(
                "parent", "/child", "child", "state-B", 1L);

        assertEquals(identity(stateA), identity(stateB),
                "admitted/current business-state identity is not a source surface");
    }

    @Test
    void bindingLineageUsesCanonicalFieldsInsteadOfDelimitedDiagnosticId() {
        EmbeddingBinding left = binding(
                "parent|/slot", "/child", "child", "state", 1L);
        EmbeddingBinding right = binding(
                "parent", "/slot|/child", "child", "state", 1L);

        assertEquals(left.bindingId(), right.bindingId(),
                "the legacy diagnostic encoding must demonstrate the collision");
        assertNotEquals(identity(left), identity(right),
                "parent DocumentId and path must be encoded independently");
    }

    @Test
    void stableBindingDimensionsInvalidateIdentity() {
        String baseline = identity(binding(
                "parent", "/child", "child", "state", 1L));

        assertAll(
                () -> assertNotEquals(baseline, identity(binding(
                        "other-parent", "/child", "child", "state", 1L))),
                () -> assertNotEquals(baseline, identity(binding(
                        "parent", "/other", "child", "state", 1L))),
                () -> assertNotEquals(baseline, identity(binding(
                        "parent", "/child", "other-child", "state", 1L))),
                () -> assertNotEquals(baseline, identity(binding(
                        "parent", "/child", "child", "state", 2L))));
    }

    @Test
    void canonicalSubscriptionOrderDoesNotChangeIdentity() {
        SubscriptionDelta.Entry first = subscription(
                "/", "channelA", "channel-type", "source", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic"),
                0L, order(100L), null);
        SubscriptionDelta.Entry second = subscription(
                "/", "channelB", "channel-type", "source", 1,
                "timeline-key", "checkpoint", dependencies("intrinsic"),
                0L, order(100L), null);
        EmbeddingBinding binding = defaultBinding();

        assertEquals(
                identity(binding, List.of(first, second), routing()),
                identity(binding, List.of(second, first), routing()));
    }

    @Test
    void tiedCanonicalSubscriptionPrefixesStillHaveTotalOrder() {
        SubscriptionDelta.Entry sourceA = subscription(
                "/", "ownerChannel", "channel-type", "source-A", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic-A"),
                0L, order(100L), null);
        SubscriptionDelta.Entry sourceB = subscription(
                "/", "ownerChannel", "channel-type", "source-B", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic-B"),
                0L, order(100L), null);

        assertEquals(
                identity(defaultBinding(),
                        List.of(sourceA, sourceB), routing()),
                identity(defaultBinding(),
                        List.of(sourceB, sourceA), routing()));
    }

    @Test
    void everySubscriptionIdentityDimensionInvalidatesIdentity() {
        EmbeddingBinding binding = defaultBinding();
        SubscriptionDelta.Entry baselineEntry = subscription(
                "/", "ownerChannel", "channel-type", "source-A", 0,
                "timeline-key", "checkpoint-A", dependencies("intrinsic-A"),
                1L, order(100L), null);
        String baseline = identity(
                binding, List.of(baselineEntry), routing());

        assertAll(
                () -> changed(baseline, subscription(
                        "/nested", "ownerChannel", "channel-type", "source-A",
                        0, "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "otherChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "other-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-B", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 1,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "other-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-B",
                        dependencies("intrinsic-A"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-B"), 1L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 2L, order(100L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(200L), null)),
                () -> changed(baseline, subscription(
                        "/", "ownerChannel", "channel-type", "source-A", 0,
                        "timeline-key", "checkpoint-A",
                        dependencies("intrinsic-A"), 1L, order(100L), 3L)));
    }

    @Test
    void wholeSurfaceAndChannelCatalogEvidenceInvalidateIdentity() {
        EmbeddingBinding binding = defaultBinding();
        ExternalChannelDependencySnapshot none =
                ExternalChannelDependencySnapshot.none();
        ExternalChannelDependencySnapshot wholeSurface = dependencies(
                List.of(), true, false, List.of());
        ExternalChannelDependencySnapshot wholeCatalog = dependencies(
                List.of(), false, true, List.of("contract-A"));
        String baseline = identity(
                binding, List.of(subscription(none)), routing());

        assertAll(
                () -> changed(baseline, subscription(wholeSurface)),
                () -> changed(baseline, subscription(wholeCatalog)));
    }

    @Test
    void exactDependencyAndCatalogEvidenceInvalidateIdentity() {
        ExternalChannelDependencySnapshot dependencyA = dependencies(
                List.of("intrinsic"), false, false, List.of());
        ExternalChannelDependencySnapshot dependencyB = dependencies(
                List.of("intrinsic", "consulted-entry"),
                false, false, List.of());
        ExternalChannelDependencySnapshot catalogA = dependencies(
                List.of("intrinsic"), false, true,
                List.of("contract-A"));
        ExternalChannelDependencySnapshot catalogB = dependencies(
                List.of("intrinsic"), false, true,
                List.of("contract-B"));

        assertAll(
                () -> assertNotEquals(
                        identityWith(dependencyA), identityWith(dependencyB)),
                () -> assertNotEquals(
                        identityWith(catalogA), identityWith(catalogB),
                        "raw catalog membership is completeness evidence"));
    }

    @Test
    void compiledRoutesAndEmbeddedReceiverCapabilityInvalidateIdentity() {
        EmbeddingBinding binding = defaultBinding();
        List<SubscriptionDelta.Entry> subscriptions =
                List.of(subscription(ExternalChannelDependencySnapshot.none()));
        String baseline = identity(binding, subscriptions, routing(
                "/", "increment", "ownerChannel", "timeline", "alice", false));

        assertAll(
                () -> changed(baseline, routing(
                        "/nested", "increment", "ownerChannel",
                        "timeline", "alice", false)),
                () -> changed(baseline, routing(
                        "/", "decrement", "ownerChannel",
                        "timeline", "alice", false)),
                () -> changed(baseline, routing(
                        "/", "increment", "otherChannel",
                        "timeline", "alice", false)),
                () -> changed(baseline, routing(
                        "/", "increment", "ownerChannel",
                        "other-timeline", "alice", false)),
                () -> changed(baseline, routing(
                        "/", "increment", "ownerChannel",
                        "timeline", "bob", false)),
                () -> changed(baseline, routing(
                        "/", "increment", "ownerChannel",
                        "timeline", "alice", true)));
    }

    @Test
    void canonicalRouteAndSourceOrderingDoesNotChangeIdentity() {
        RoutingSurface.Definition increment = new RoutingSurface.Definition(
                "/", "increment", "ownerChannel", List.of(
                new RoutingSurface.SourceAddress("timeline-b", "bob"),
                new RoutingSurface.SourceAddress("timeline-a", "alice")));
        RoutingSurface.Definition decrement = new RoutingSurface.Definition(
                "/", "decrement", "ownerChannel", "timeline-a", "alice");
        RoutingSurface left = new RoutingSurface(
                List.of(increment, decrement), false);
        RoutingSurface right = new RoutingSurface(List.of(
                decrement,
                new RoutingSurface.Definition(
                        "/", "increment", "ownerChannel", List.of(
                        new RoutingSurface.SourceAddress(
                                "timeline-a", "alice"),
                        new RoutingSurface.SourceAddress(
                                "timeline-b", "bob")))), false);

        assertEquals(
                identity(defaultBinding(), subscriptions(), left),
                identity(defaultBinding(), subscriptions(), right));
    }

    @Test
    void tiedRoutePrefixesStillHaveCanonicalDefinitionOrder() {
        RoutingSurface.Definition alice = new RoutingSurface.Definition(
                "/", "increment", "ownerChannel", List.of(
                new RoutingSurface.SourceAddress("timeline-b", "bob"),
                new RoutingSurface.SourceAddress("timeline-a", "alice")));
        RoutingSurface.Definition carol = new RoutingSurface.Definition(
                "/", "increment", "ownerChannel",
                "timeline-c", "carol");

        assertEquals(
                identity(defaultBinding(), subscriptions(),
                        new RoutingSurface(List.of(alice, carol), false)),
                identity(defaultBinding(), subscriptions(),
                        new RoutingSurface(List.of(carol, alice), false)));
    }

    private static void changed(
            String baseline,
            SubscriptionDelta.Entry changed) {
        assertNotEquals(baseline,
                identity(defaultBinding(), List.of(changed), routing()));
    }

    private static void changed(String baseline, RoutingSurface changed) {
        assertNotEquals(baseline,
                identity(defaultBinding(), subscriptions(), changed));
    }

    private static String identity(EmbeddingBinding binding) {
        return identity(binding, subscriptions(), routing());
    }

    private static String identityWith(
            ExternalChannelDependencySnapshot dependencies) {
        return identity(defaultBinding(),
                List.of(subscription(dependencies)), routing());
    }

    private static String identity(
            EmbeddingBinding binding,
            List<SubscriptionDelta.Entry> subscriptions,
            RoutingSurface routing) {
        return SourceSurfaceIdentity.calculate(
                binding, subscriptions, routing);
    }

    private static EmbeddingBinding defaultBinding() {
        return binding("parent", "/child", "child", "state", 1L);
    }

    private static EmbeddingBinding binding(
            String parent,
            String path,
            String child,
            String admittedStateBlueId,
            long generation) {
        return new EmbeddingBinding(
                parent + "|" + path + "|" + generation,
                DocumentId.of(parent),
                path,
                DocumentId.of(child),
                generation,
                ActivationMode.IMPORT_FULL_HISTORY,
                null,
                admittedStateBlueId,
                null,
                "proof",
                "attachment-entry",
                order(300L));
    }

    private static List<SubscriptionDelta.Entry> subscriptions() {
        return List.of(subscription(ExternalChannelDependencySnapshot.none()));
    }

    private static SubscriptionDelta.Entry subscription(
            ExternalChannelDependencySnapshot dependencies) {
        return subscription(
                "/", "ownerChannel", "channel-type", "source", 0,
                "timeline-key", "checkpoint-domain", dependencies,
                0L, order(100L), null);
    }

    private static SubscriptionDelta.Entry subscription(
            String scope,
            String channel,
            String type,
            String source,
            int channelOrder,
            String subscriptionKey,
            String checkpointDomain,
            ExternalChannelDependencySnapshot dependencies,
            Long activationRevision,
            ExternalOrderKey start,
            Long endRevision) {
        return new SubscriptionDelta.Entry(
                scope,
                channel,
                type,
                List.of(source),
                channelOrder,
                List.of(subscriptionKey),
                checkpointDomain,
                dependencies,
                activationRevision,
                start,
                endRevision);
    }

    private static ExternalChannelDependencySnapshot dependencies(
            String intrinsic) {
        return dependencies(List.of(intrinsic), false, false, List.of());
    }

    private static ExternalChannelDependencySnapshot dependencies(
            List<String> intrinsic,
            boolean wholeSurface,
            boolean wholeCatalog,
            List<String> catalogKeys) {
        return new ExternalChannelDependencySnapshot(
                intrinsic,
                List.of(),
                List.of(),
                wholeSurface,
                List.of(),
                wholeCatalog,
                catalogKeys);
    }

    private static RoutingSurface routing() {
        return routing(
                "/", "increment", "ownerChannel",
                "timeline", "alice", false);
    }

    private static RoutingSurface routing(
            String scope,
            String operation,
            String channel,
            String timeline,
            String actor,
            boolean embeddedReceiver) {
        return new RoutingSurface(List.of(new RoutingSurface.Definition(
                scope, operation, channel, timeline, actor)), embeddedReceiver);
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(List.of(
                BigInteger.valueOf(value), "timeline", "entry"));
    }
}
