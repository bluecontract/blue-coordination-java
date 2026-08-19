package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.processor.TimelineProviderSupport;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact active-subscription routing tests for one document generation. */
final class OperationRouteIndexTest {
    private static final DocumentId DOCUMENT = DocumentId.of("document-a");

    @Test
    void atomicallyReplacesRowsFromExactActiveSubscriptionKeys() {
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface aliceSurface = surface("timeline-a", "alice");
        RoutingSurface bobSurface = surface("timeline-b", "bob");

        index.replace(DOCUMENT, aliceSurface, List.of(active(
                "timeline-a", "alice")));
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-a", "alice")));
        assertEquals(List.of(), index.route(entry("timeline-b", "bob")));

        index.replace(DOCUMENT, bobSurface, List.of(active(
                "timeline-b", "bob")));
        assertEquals(List.of(), index.route(entry("timeline-a", "alice")));
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-b", "bob")));

        index.replace(DOCUMENT, bobSurface, List.of());
        assertEquals(List.of(), index.route(entry("timeline-b", "bob")));
        assertEquals(3L, metrics.counter("routing.surfaceCompilations"));
    }

    @Test
    void invalidReplacementLeavesPriorRouteGenerationPublished() {
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface surface = surface("timeline-a", "alice");
        SubscriptionDelta.Entry active = active("timeline-a", "alice");
        index.replace(DOCUMENT, surface, List.of(active));

        assertThrows(IllegalStateException.class,
                () -> index.replace(
                        DOCUMENT, surface, List.of(active, active)));

        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-a", "alice")));
    }

    @Test
    void changedSubscriptionPublishesOnlyItsRouteKey() {
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface surface = new RoutingSurface(List.of(
                new RoutingSurface.Definition(
                        "/", "increment", "ownerChannel",
                        "timeline-a", "alice"),
                new RoutingSurface.Definition(
                        "/", "increment", "backupChannel",
                        "timeline-b", "bob")), false);
        ExternalOrderKey initial = ExternalOrderKey.of(List.of(0L));
        index.replace(DOCUMENT, surface, List.of(
                active("ownerChannel", "timeline-a", "alice", initial),
                active("backupChannel", "timeline-b", "bob", initial)));
        long updated = metrics.counter("routing.routeKeysUpdated");
        long retained = metrics.counter("routing.routeKeysRetained");

        index.replace(DOCUMENT, surface, List.of(
                active("ownerChannel", "timeline-a", "alice",
                        ExternalOrderKey.of(List.of(5L))),
                active("backupChannel", "timeline-b", "bob", initial)));

        assertEquals(updated + 1L,
                metrics.counter("routing.routeKeysUpdated"));
        assertEquals(retained + 1L,
                metrics.counter("routing.routeKeysRetained"));
        assertEquals(List.of(), index.route(entry(
                "timeline-a", "alice", "ownerChannel",
                ExactValue.verified(new Node().value("owner-boundary")),
                ExternalOrderKey.of(List.of(5L)))));
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-b", "bob", "backupChannel")));
    }

    @Test
    void activeLowerExclusiveBoundRejectsEarlierAndBoundaryEntries() {
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(5L));
        index.replace(
                DOCUMENT,
                surface("timeline-a", "alice"),
                List.of(active("timeline-a", "alice", frontier)));

        assertEquals(List.of(), index.route(entry(
                "timeline-a", "alice", ExternalOrderKey.of(List.of(4L)))));
        assertEquals(List.of(), index.route(entry(
                "timeline-a", "alice", frontier)));
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-a", "alice", ExternalOrderKey.of(List.of(6L)))));
    }

    @Test
    void allTimelinesKeyStillHonorsTheFrozenMemberSourceSurface() {
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface surface = new RoutingSurface(List.of(
                new RoutingSurface.Definition(
                        "/",
                        "increment",
                        "allChannel",
                        List.of(
                                new RoutingSurface.SourceAddress(
                                        "timeline-a", "alice"),
                                new RoutingSurface.SourceAddress(
                                        "timeline-b", "bob")))), false);
        List<String> eventKeys = TimelineProviderSupport
                .exactTimelineEntryEventKeys("timeline-a", "alice");
        String allTimelinesKey = eventKeys.get(eventKeys.size() - 1);
        SubscriptionDelta.Entry active = new SubscriptionDelta.Entry(
                "/",
                "allChannel",
                "all-timelines-type",
                List.of("source-allChannel"),
                0,
                List.of(allTimelinesKey),
                "checkpoint-domain",
                0L,
                ExternalOrderKey.of(List.of(0L)),
                null);
        index.replace(DOCUMENT, surface, List.of(active));

        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-a", "alice", "allChannel")));
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-b", "bob", "allChannel")));
        assertEquals(List.of(), index.route(entry(
                "timeline-c", "charlie", "allChannel")));
    }

    @Test
    void publicationGenerationIsMonotonicAndFailedWritesDoNotPublish() {
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface surface = surface("timeline-a", "alice");
        SubscriptionDelta.Entry active = active("timeline-a", "alice");
        assertEquals(0L, index.generation());

        index.replace(DOCUMENT, surface, List.of(active));
        assertEquals(1L, index.generation());
        assertThrows(IllegalStateException.class,
                () -> index.replace(
                        DOCUMENT, surface, List.of(active, active)));
        assertEquals(1L, index.generation());

        index.replace(DOCUMENT, surface, List.of(active));
        assertEquals(1L, index.generation());
        assertEquals(1L, metrics.counter("routing.routeKeysRetained"));
        index.remove(DocumentId.of("missing"));
        assertEquals(1L, index.generation());
        index.remove(DOCUMENT);
        assertEquals(2L, index.generation());
        index.clear();
        assertEquals(2L, index.generation());

        index.replace(DOCUMENT, surface, List.of(active));
        assertEquals(3L, index.generation());
        index.clear();
        assertEquals(4L, index.generation());
    }

    @Test
    void freezesCanonicalRootDeliveriesWithoutContainerContext() {
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        DocumentId later = DocumentId.of("document-z");
        DocumentId earlier = DocumentId.of("document-a");
        RoutingSurface surface = surface("timeline-a", "alice");
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        index.replace(later, surface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 4)));
        index.replace(earlier, surface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 2)));

        OperationRouteIndex.FrozenDirectDeliverySelection selected =
                index.selectDirectDeliveries(entry(
                        "timeline-a", "alice"));

        assertEquals(2L, selected.routeGeneration());
        assertEquals(List.of(earlier, later), selected.documentIds());
        assertEquals(List.of(earlier, later), selected.deliveries().stream()
                .map(OperationRouteIndex.FrozenDirectDelivery::documentId)
                .toList());
        assertEquals(List.of(0L, 1L), selected.deliveries().stream()
                .map(OperationRouteIndex.FrozenDirectDelivery
                        ::rawOccurrenceOrder)
                .toList());
        assertEquals(List.of("ownerChannel", "ownerChannel"),
                selected.contractsEvidence().stream()
                        .map(delivery -> delivery.channelKey())
                        .toList());
        String runtimeDeliveryKey = TimelineProviderSupport
                .operationRequestLogicalDeliveryKey(
                        "increment", "ownerChannel");
        assertEquals(List.of(runtimeDeliveryKey, runtimeDeliveryKey),
                selected.contractsEvidence().stream()
                        .map(delivery -> delivery.logicalDeliveryKey())
                        .toList());
        selected.contractsEvidence().forEach(delivery -> {
            assertEquals("/", delivery.targetScope().address().path());
            assertEquals(0L,
                    delivery.targetScope().address().activationGeneration());
        });
    }

    @Test
    void revalidationAcceptsUnrelatedBumpAndRejectsRelevantMutation() {
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface relevantSurface = surface("timeline-a", "alice");
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        index.replace(DOCUMENT, relevantSurface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 0)));
        TimelineEntry relevantEntry = entry("timeline-a", "alice");
        OperationRouteIndex.FrozenDirectDeliverySelection frozen =
                index.selectDirectDeliveries(relevantEntry);

        DocumentId unrelated = DocumentId.of("unrelated");
        index.replace(
                unrelated,
                surface("timeline-b", "bob"),
                List.of(active(
                        "ownerChannel",
                        "timeline-b",
                        "bob",
                        frontier,
                        0)));
        assertTrue(index.generation() > frozen.routeGeneration());

        assertTrue(index.revalidatesDirectDeliveries(
                relevantEntry, frozen.contractsEvidence()));
        assertEquals(1L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS));
        assertEquals(1L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_REVALIDATION_SNAPSHOTS));

        index.remove(DOCUMENT);
        assertFalse(index.revalidatesDirectDeliveries(
                relevantEntry, frozen.contractsEvidence()));
        assertEquals(1L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS));
        assertEquals(2L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_REVALIDATION_SNAPSHOTS));
    }

    @Test
    void preservesLegacyNestedRoutingButExcludesItFromClosureDeliveries() {
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface nested = new RoutingSurface(List.of(
                new RoutingSurface.Definition(
                        "/nested", "increment", "ownerChannel",
                        "timeline-a", "alice")), false);
        SubscriptionDelta.Entry active = new SubscriptionDelta.Entry(
                "/nested",
                "ownerChannel",
                "timeline-channel-type",
                List.of("source-ownerChannel"),
                0,
                List.of(TimelineProviderSupport.exactScalarEventKeys(
                        "timeline-a", "alice").get(0)),
                "checkpoint-domain",
                0L,
                ExternalOrderKey.of(List.of(0L)),
                null);
        index.replace(DOCUMENT, nested, List.of(active));

        TimelineEntry entry = entry("timeline-a", "alice");
        assertEquals(List.of(DOCUMENT), index.route(entry));
        assertEquals(List.of(),
                index.selectDirectDeliveries(entry).deliveries());
    }

    private static RoutingSurface surface(String timeline, String actor) {
        return new RoutingSurface(List.of(new RoutingSurface.Definition(
                "/",
                "increment",
                "ownerChannel",
                timeline,
                actor)), false);
    }

    private static SubscriptionDelta.Entry active(
            String timeline,
            String actor) {
        return active(
                timeline,
                actor,
                ExternalOrderKey.of(List.of(0L, "admission")));
    }

    private static SubscriptionDelta.Entry active(
            String timeline,
            String actor,
            ExternalOrderKey startAfter) {
        return active("ownerChannel", timeline, actor, startAfter);
    }

    private static SubscriptionDelta.Entry active(
            String channel,
            String timeline,
            String actor,
            ExternalOrderKey startAfter) {
        return active(channel, timeline, actor, startAfter, 0);
    }

    private static SubscriptionDelta.Entry active(
            String channel,
            String timeline,
            String actor,
            ExternalOrderKey startAfter,
            int order) {
        return new SubscriptionDelta.Entry(
                "/",
                channel,
                "timeline-channel-type",
                List.of("source-" + channel),
                order,
                List.of(TimelineProviderSupport.exactScalarEventKeys(
                        timeline, actor).get(0)),
                "checkpoint-domain",
                0L,
                startAfter,
                null);
    }

    private static TimelineEntry entry(String timeline, String actor) {
        return entry(timeline, actor, "ownerChannel");
    }

    private static TimelineEntry entry(
            String timeline,
            String actor,
            String channel) {
        ExactValue event = ExactValue.verified(new Node().value(
                timeline + "|" + actor + "|event"));
        ExternalOrderKey order = ExternalOrderKey.of(List.of(
                1L, timeline, event.blueId()));
        return entry(timeline, actor, channel, event, order);
    }

    private static TimelineEntry entry(
            String timeline,
            String actor,
            ExternalOrderKey order) {
        ExactValue event = ExactValue.verified(new Node().value(
                timeline + "|" + actor + "|" + order));
        return entry(timeline, actor, "ownerChannel", event, order);
    }

    private static TimelineEntry entry(
            String timeline,
            String actor,
            String channel,
            ExactValue event,
            ExternalOrderKey order) {
        ExactValue request = ExactValue.verified(new Node().value("request"));
        return new TimelineEntry(
                event,
                request,
                order,
                order,
                new Timeline(timeline, actor),
                "increment",
                channel,
                1L,
                1L,
                1L);
    }
}
