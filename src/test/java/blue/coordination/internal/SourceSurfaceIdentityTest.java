package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceSurfaceIdentityTest {
    @Test
    void unrelatedBusinessStateDoesNotInvalidateCompletenessIdentity() {
        // given
        EmbeddingBinding stateA = binding(
                "parent", "/child", "child", "state-A", 1L);
        EmbeddingBinding stateB = binding(
                "parent", "/child", "child", "state-B", 1L);

        // when
        String identityA = identity(stateA);
        String identityB = identity(stateB);

        // then
        assertEquals(identityA, identityB,
                "admitted/current business-state identity is not a source surface");
    }

    @Test
    void bindingLineageUsesCanonicalFieldsInsteadOfDelimitedDiagnosticId() {
        // given
        EmbeddingBinding left = binding(
                "parent|/slot", "/child", "child", "state", 1L);
        EmbeddingBinding right = binding(
                "parent", "/slot|/child", "child", "state", 1L);

        // when
        String leftIdentity = identity(left);
        String rightIdentity = identity(right);

        // then
        assertEquals(left.bindingId(), right.bindingId(),
                "the legacy diagnostic encoding must demonstrate the collision");
        assertNotEquals(leftIdentity, rightIdentity,
                "parent DocumentId and path must be encoded independently");
    }

    @Test
    void stableBindingDimensionsInvalidateIdentity() {
        // given
        EmbeddingBinding binding = binding(
                "parent", "/child", "child", "state", 1L);

        // when
        String baseline = identity(binding);

        // then
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
        // given
        SubscriptionDelta.Entry first = subscription(
                "/", "channelA", "channel-type", "source", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic"),
                0L, order(100L), null);
        SubscriptionDelta.Entry second = subscription(
                "/", "channelB", "channel-type", "source", 1,
                "timeline-key", "checkpoint", dependencies("intrinsic"),
                0L, order(100L), null);
        EmbeddingBinding binding = defaultBinding();

        // when
        String forward = identity(
                binding, List.of(first, second), routing());
        String reverse = identity(
                binding, List.of(second, first), routing());

        // then
        assertEquals(forward, reverse);
    }

    @Test
    void subscriptionTextDimensionsUseUnicodeCodePointOrder() {
        // given
        String privateUse = "\uE000";
        String supplementary = "\uD800\uDC00";
        List<SubscriptionDelta.Entry> channelKeys = new ArrayList<>(List.of(
                subscription("/", supplementary, "type", "source", 0,
                        "key", "checkpoint", dependencies("intrinsic"),
                        0L, order(100L), null),
                subscription("/", privateUse, "type", "source", 0,
                        "key", "checkpoint", dependencies("intrinsic"),
                        0L, order(100L), null)));

        // when
        channelKeys.sort(SourceSurfaceIdentity.ENTRY_ORDER);
        List<SubscriptionDelta.Entry> sourceLists = new ArrayList<>(List.of(
                subscription("/", "channel", "type", supplementary, 0,
                        "key", "checkpoint", dependencies("intrinsic"),
                        0L, order(100L), null),
                subscription("/", "channel", "type", privateUse, 0,
                        "key", "checkpoint", dependencies("intrinsic"),
                        0L, order(100L), null)));
        sourceLists.sort(SourceSurfaceIdentity.ENTRY_ORDER);

        // then
        assertTrue(privateUse.compareTo(supplementary) > 0,
                "the fixture must oppose Java UTF-16 ordering");
        assertEquals(List.of(privateUse, supplementary), channelKeys.stream()
                .map(SubscriptionDelta.Entry::channelKey).toList());
        assertEquals(List.of(privateUse, supplementary), sourceLists.stream()
                .map(entry -> entry.sourceContributionNodeBlueIds().get(0))
                .toList());
    }

    @Test
    void tiedCanonicalSubscriptionPrefixesStillHaveTotalOrder() {
        // given
        SubscriptionDelta.Entry sourceA = subscription(
                "/", "ownerChannel", "channel-type", "source-A", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic-A"),
                0L, order(100L), null);
        SubscriptionDelta.Entry sourceB = subscription(
                "/", "ownerChannel", "channel-type", "source-B", 0,
                "timeline-key", "checkpoint", dependencies("intrinsic-B"),
                0L, order(100L), null);

        // when
        String forward = identity(defaultBinding(),
                List.of(sourceA, sourceB), routing());
        String reverse = identity(defaultBinding(),
                List.of(sourceB, sourceA), routing());

        // then
        assertEquals(forward, reverse);
    }

    @Test
    void everySubscriptionIdentityDimensionInvalidatesIdentity() {
        // given
        EmbeddingBinding binding = defaultBinding();
        SubscriptionDelta.Entry baselineEntry = subscription(
                "/", "ownerChannel", "channel-type", "source-A", 0,
                "timeline-key", "checkpoint-A", dependencies("intrinsic-A"),
                1L, order(100L), null);

        // when
        String baseline = identity(
                binding, List.of(baselineEntry), routing());

        // then
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
        // given
        EmbeddingBinding binding = defaultBinding();
        ExternalChannelDependencySnapshot none =
                ExternalChannelDependencySnapshot.none();
        ExternalChannelDependencySnapshot wholeSurface = dependencies(
                List.of(), true, false, List.of());
        ExternalChannelDependencySnapshot wholeCatalog = dependencies(
                List.of(), false, true, List.of("contract-A"));

        // when
        String baseline = identity(
                binding, List.of(subscription(none)), routing());

        // then
        assertAll(
                () -> changed(baseline, subscription(wholeSurface)),
                () -> changed(baseline, subscription(wholeCatalog)));
    }

    @Test
    void exactDependencyAndCatalogEvidenceInvalidateIdentity() {
        // given
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

        // when
        String identityDependencyA = identityWith(dependencyA);
        String identityDependencyB = identityWith(dependencyB);
        String identityCatalogA = identityWith(catalogA);
        String identityCatalogB = identityWith(catalogB);

        // then
        assertAll(
                () -> assertNotEquals(
                        identityDependencyA, identityDependencyB),
                () -> assertNotEquals(
                        identityCatalogA, identityCatalogB,
                        "raw catalog membership is completeness evidence"));
    }

    @Test
    void compiledRoutesAndEmbeddedReceiverCapabilityInvalidateIdentity() {
        // given
        EmbeddingBinding binding = defaultBinding();
        List<SubscriptionDelta.Entry> subscriptions =
                List.of(subscription(ExternalChannelDependencySnapshot.none()));

        // when
        String baseline = identity(binding, subscriptions, routing(
                "/", "increment", "ownerChannel", "timeline", "alice", false));

        // then
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
        // given
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

        // when
        String leftIdentity = identity(defaultBinding(), subscriptions(), left);
        String rightIdentity = identity(
                defaultBinding(), subscriptions(), right);

        // then
        assertEquals(leftIdentity, rightIdentity);
    }

    @Test
    void tiedRoutePrefixesStillHaveCanonicalDefinitionOrder() {
        // given
        RoutingSurface.Definition alice = new RoutingSurface.Definition(
                "/", "increment", "ownerChannel", List.of(
                new RoutingSurface.SourceAddress("timeline-b", "bob"),
                new RoutingSurface.SourceAddress("timeline-a", "alice")));
        RoutingSurface.Definition carol = new RoutingSurface.Definition(
                "/", "increment", "ownerChannel",
                "timeline-c", "carol");

        // when
        String aliceFirst = identity(defaultBinding(), subscriptions(),
                new RoutingSurface(List.of(alice, carol), false));
        String carolFirst = identity(defaultBinding(), subscriptions(),
                new RoutingSurface(List.of(carol, alice), false));

        // then
        assertEquals(aliceFirst, carolFirst);
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
