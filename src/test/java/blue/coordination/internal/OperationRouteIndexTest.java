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
        // given
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface aliceSurface = surface("timeline-a", "alice");
        RoutingSurface bobSurface = surface("timeline-b", "bob");

        // when
        index.replace(DOCUMENT, aliceSurface, List.of(active(
                "timeline-a", "alice")));
        List<DocumentId> aliceBeforeReplacement = index.route(entry(
                "timeline-a", "alice"));
        List<DocumentId> bobBeforeReplacement = index.route(entry(
                "timeline-b", "bob"));

        index.replace(DOCUMENT, bobSurface, List.of(active(
                "timeline-b", "bob")));
        List<DocumentId> aliceAfterReplacement = index.route(entry(
                "timeline-a", "alice"));
        List<DocumentId> bobAfterReplacement = index.route(entry(
                "timeline-b", "bob"));

        index.replace(DOCUMENT, bobSurface, List.of());
        List<DocumentId> bobAfterRemoval = index.route(entry(
                "timeline-b", "bob"));

        // then
        assertEquals(List.of(DOCUMENT), aliceBeforeReplacement);
        assertEquals(List.of(), bobBeforeReplacement);
        assertEquals(List.of(), aliceAfterReplacement);
        assertEquals(List.of(DOCUMENT), bobAfterReplacement);
        assertEquals(List.of(), bobAfterRemoval);
        assertEquals(3L, metrics.counter("routing.surfaceCompilations"));
    }

    @Test
    void preparedReplacementRetainsExactLogicalRouteDelta() {
        // given
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface aliceSurface = surface("timeline-a", "alice");
        RoutingSurface bobSurface = surface("timeline-b", "bob");

        // when / then: add
        OperationRouteIndex.PreparedReplacement added =
                index.prepareReplacement(List.of(
                        new OperationRouteIndex.Replacement(
                                DOCUMENT,
                                aliceSurface,
                                List.of(active("timeline-a", "alice")))));
        assertEquals(List.of(new OperationRouteIndex.OperationRouteChange(
                        OperationRouteIndex.OperationRouteChangeKind.ADD,
                        DOCUMENT,
                        java.util.Optional.empty(),
                        java.util.Optional.of(routeState(
                                "timeline-a", "alice")))),
                added.operationRouteChanges());
        added.publish();

        // when / then: replace
        OperationRouteIndex.PreparedReplacement replaced =
                index.prepareReplacement(List.of(
                        new OperationRouteIndex.Replacement(
                                DOCUMENT,
                                bobSurface,
                                List.of(active("timeline-b", "bob")))));
        assertEquals(List.of(new OperationRouteIndex.OperationRouteChange(
                        OperationRouteIndex.OperationRouteChangeKind.REPLACE,
                        DOCUMENT,
                        java.util.Optional.of(routeState(
                                "timeline-a", "alice")),
                        java.util.Optional.of(routeState(
                                "timeline-b", "bob")))),
                replaced.operationRouteChanges());
        replaced.publish();

        OperationRouteIndex.PreparedReplacement unchanged =
                index.prepareReplacement(List.of(
                        new OperationRouteIndex.Replacement(
                                DOCUMENT,
                                bobSurface,
                                List.of(active("timeline-b", "bob")))));
        assertEquals(List.of(), unchanged.operationRouteChanges());
        unchanged.publish();

        // when / then: remove
        OperationRouteIndex.PreparedReplacement removed =
                index.prepareReplacement(List.of(
                        new OperationRouteIndex.Replacement(
                                DOCUMENT,
                                new RoutingSurface(List.of(), false),
                                List.of())));
        assertEquals(List.of(new OperationRouteIndex.OperationRouteChange(
                        OperationRouteIndex.OperationRouteChangeKind.REMOVE,
                        DOCUMENT,
                        java.util.Optional.of(routeState(
                                "timeline-b", "bob")),
                        java.util.Optional.empty())),
                removed.operationRouteChanges());
    }

    @Test
    void invalidReplacementLeavesPriorRouteGenerationPublished() {
        // given
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface surface = surface("timeline-a", "alice");
        SubscriptionDelta.Entry active = active("timeline-a", "alice");
        index.replace(DOCUMENT, surface, List.of(active));

        // when
        assertThrows(IllegalStateException.class,
                () -> index.replace(
                        DOCUMENT, surface, List.of(active, active)));

        // then
        assertEquals(List.of(DOCUMENT), index.route(entry(
                "timeline-a", "alice")));
    }

    @Test
    void changedSubscriptionPublishesOnlyItsRouteKey() {
        // given
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

        // when
        index.replace(DOCUMENT, surface, List.of(
                active("ownerChannel", "timeline-a", "alice",
                        ExternalOrderKey.of(List.of(5L))),
                active("backupChannel", "timeline-b", "bob", initial)));

        // then
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
    void replacesOneOfOneThousandRoutesByPersistentExactKeyWork() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        ExternalOrderKey initial = ExternalOrderKey.of(List.of(0L));
        for (int ordinal = 0; ordinal < 1_000; ordinal++) {
            String suffix = String.format("%04d", ordinal);
            String timeline = "timeline-" + suffix;
            String actor = "actor-" + suffix;
            index.replace(
                    DocumentId.of("document-" + suffix),
                    surface(timeline, actor),
                    List.of(active(
                            "ownerChannel", timeline, actor, initial)));
        }
        OperationRouteIndex.RouteStructureSnapshot before =
                index.routeStructureSnapshotForTesting();
        long comparisonsBefore = metrics.counter(
                "routing.routeIndexComparisons");
        long copiesBefore = metrics.counter(
                "routing.routeIndexNodesCopied");
        long globalBefore = metrics.counter(
                ContractsStructuralWorkMetrics
                        .GLOBAL_ROUTE_ENTRIES_TRAVERSED);

        // when
        String selected = "0500";
        ExternalOrderKey advanced = ExternalOrderKey.of(List.of(5L));
        index.replace(
                DocumentId.of("document-" + selected),
                surface("timeline-" + selected, "actor-" + selected),
                List.of(active(
                        "ownerChannel",
                        "timeline-" + selected,
                        "actor-" + selected,
                        advanced)));
        OperationRouteIndex.RouteStructureSnapshot after =
                index.routeStructureSnapshotForTesting();

        // then
        assertEquals(1_000, index.rowCount());
        assertEquals(globalBefore, metrics.counter(
                ContractsStructuralWorkMetrics
                        .GLOBAL_ROUTE_ENTRIES_TRAVERSED));
        assertTrue(after.sharedRouteNodes(before) > 950);
        assertTrue(after.sharedDocumentNodes(before) > 950);
        assertTrue(metrics.counter("routing.routeIndexComparisons")
                - comparisonsBefore < 200L);
        assertTrue(metrics.counter("routing.routeIndexNodesCopied")
                - copiesBefore < 200L);
        assertEquals(List.of(), index.route(entry(
                "timeline-" + selected,
                "actor-" + selected,
                "ownerChannel",
                ExactValue.verified(new Node().value("at-boundary")),
                advanced)));
        assertEquals(List.of(DocumentId.of("document-0999")), index.route(
                entry("timeline-0999", "actor-0999")));
    }

    @Test
    void activeLowerExclusiveBoundRejectsEarlierAndBoundaryEntries() {
        // given
        OperationRouteIndex index = new OperationRouteIndex(
                new EngineMetrics());
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(5L));
        index.replace(
                DOCUMENT,
                surface("timeline-a", "alice"),
                List.of(active("timeline-a", "alice", frontier)));

        // when
        List<DocumentId> before = index.route(entry(
                "timeline-a", "alice", ExternalOrderKey.of(List.of(4L))));
        List<DocumentId> boundary = index.route(entry(
                "timeline-a", "alice", frontier));
        List<DocumentId> after = index.route(entry(
                "timeline-a", "alice", ExternalOrderKey.of(List.of(6L))));

        // then
        assertEquals(List.of(), before);
        assertEquals(List.of(), boundary);
        assertEquals(List.of(DOCUMENT), after);
    }

    @Test
    void allTimelinesKeyStillHonorsTheFrozenMemberSourceSurface() {
        // given
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

        // when
        List<DocumentId> alice = index.route(entry(
                "timeline-a", "alice", "allChannel"));
        List<DocumentId> bob = index.route(entry(
                "timeline-b", "bob", "allChannel"));
        List<DocumentId> charlie = index.route(entry(
                "timeline-c", "charlie", "allChannel"));

        // then
        assertEquals(List.of(DOCUMENT), alice);
        assertEquals(List.of(DOCUMENT), bob);
        assertEquals(List.of(), charlie);
    }

    @Test
    void publicationGenerationIsMonotonicAndFailedWritesDoNotPublish() {
        // given
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface surface = surface("timeline-a", "alice");
        SubscriptionDelta.Entry active = active("timeline-a", "alice");

        // when
        long initialGeneration = index.generation();

        index.replace(DOCUMENT, surface, List.of(active));
        long firstGeneration = index.generation();
        assertThrows(IllegalStateException.class,
                () -> index.replace(
                        DOCUMENT, surface, List.of(active, active)));
        long generationAfterFailure = index.generation();

        index.replace(DOCUMENT, surface, List.of(active));
        long generationAfterNoOp = index.generation();
        long retainedRouteKeys = metrics.counter("routing.routeKeysRetained");
        index.remove(DocumentId.of("missing"));
        long generationAfterMissingRemoval = index.generation();
        index.remove(DOCUMENT);
        long generationAfterRemoval = index.generation();
        index.clear();
        long generationAfterEmptyClear = index.generation();

        index.replace(DOCUMENT, surface, List.of(active));
        long generationAfterReinsert = index.generation();
        index.clear();
        long generationAfterClear = index.generation();

        // then
        assertEquals(0L, initialGeneration);
        assertEquals(1L, firstGeneration);
        assertEquals(1L, generationAfterFailure);
        assertEquals(1L, generationAfterNoOp);
        assertEquals(1L, retainedRouteKeys);
        assertEquals(1L, generationAfterMissingRemoval);
        assertEquals(2L, generationAfterRemoval);
        assertEquals(2L, generationAfterEmptyClear);
        assertEquals(3L, generationAfterReinsert);
        assertEquals(4L, generationAfterClear);
    }

    @Test
    void freezesCanonicalRootDeliveriesWithoutContainerContext() {
        // given
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

        // when
        OperationRouteIndex.FrozenDirectDeliverySelection selected =
                index.selectDirectDeliveries(entry(
                        "timeline-a", "alice"));

        // then
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
        // given
        EngineMetrics metrics = new EngineMetrics();
        OperationRouteIndex index = new OperationRouteIndex(metrics);
        RoutingSurface relevantSurface = surface("timeline-a", "alice");
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        index.replace(DOCUMENT, relevantSurface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 0)));
        TimelineEntry relevantEntry = entry("timeline-a", "alice");
        OperationRouteIndex.FrozenDirectDeliverySelection frozen =
                index.selectDirectDeliveries(relevantEntry);

        // when
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
        long unrelatedGeneration = index.generation();
        boolean unrelatedRevalidation = index.revalidatesDirectDeliveries(
                relevantEntry, frozen.contractsEvidence());
        long snapshotsAfterUnrelated = metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS);
        long revalidationsAfterUnrelated = metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_REVALIDATION_SNAPSHOTS);

        index.remove(DOCUMENT);
        boolean relevantRevalidation = index.revalidatesDirectDeliveries(
                relevantEntry, frozen.contractsEvidence());

        // then
        assertTrue(unrelatedGeneration > frozen.routeGeneration());
        assertTrue(unrelatedRevalidation);
        assertEquals(1L, snapshotsAfterUnrelated);
        assertEquals(1L, revalidationsAfterUnrelated);
        assertFalse(relevantRevalidation);
        assertEquals(1L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_SNAPSHOTS));
        assertEquals(2L, metrics.counter(
                OperationRouteIndex.DIRECT_ROUTE_REVALIDATION_SNAPSHOTS));
    }

    @Test
    void preservesLegacyNestedRoutingButExcludesItFromClosureDeliveries() {
        // given
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

        // when
        TimelineEntry entry = entry("timeline-a", "alice");
        List<DocumentId> legacyRoute = index.route(entry);
        List<OperationRouteIndex.FrozenDirectDelivery> closureDeliveries =
                index.selectDirectDeliveries(entry).deliveries();

        // then
        assertEquals(List.of(DOCUMENT), legacyRoute);
        assertEquals(List.of(), closureDeliveries);
    }

    private static RoutingSurface surface(String timeline, String actor) {
        return new RoutingSurface(List.of(new RoutingSurface.Definition(
                "/",
                "increment",
                "ownerChannel",
                timeline,
                actor)), false);
    }

    private static OperationRouteIndex.OperationRouteState routeState(
            String timeline,
            String actor) {
        return new OperationRouteIndex.OperationRouteState(
                "/",
                "increment",
                "ownerChannel",
                List.of(new RoutingSurface.SourceAddress(timeline, actor)));
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
