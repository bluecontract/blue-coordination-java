package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Committed source-surface refresh coverage for public Root feeder lanes. */
final class ContractsActiveSourceTimelineIndexTest {
    private static final String POLICY =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String BLUE_ID =
            "8M3d43KXskYr7rrdiaXPiPHmypFEjtU4uUyECtU7Tiyx";
    private static final DocumentId ROOT = DocumentId.of("root");
    private static final DocumentId COLLECTION_OWNER =
            DocumentId.of("collection-owner");
    private static final DocumentId MEMBER = DocumentId.of("member");
    private static final DocumentId PROSPECTIVE =
            DocumentId.of("prospective");

    @Test
    void descendantChangeRefreshesEveryContainingPublicRoot() {
        // given
        ContractsActiveSourceTimelineIndex index =
                new ContractsActiveSourceTimelineIndex(List.of(ROOT));
        ManagedOccurrenceInventory initial = ManagedOccurrenceInventory.of(
                List.of(
                        active(ROOT, "/owner", COLLECTION_OWNER),
                        inactive(
                                COLLECTION_OWNER,
                                "/orders/prospective",
                                PROSPECTIVE)));
        Map<DocumentId, Set<String>> timelines = new LinkedHashMap<>();
        timelines.put(ROOT, Set.of("timeline/root"));
        timelines.put(COLLECTION_OWNER, Set.of("timeline/owner-old"));
        timelines.put(PROSPECTIVE, Set.of("timeline/prospective"));
        index.refresh(List.of(ROOT), initial, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/owner-old"),
                index.timelineIds());

        // when
        // Only the descendant joins the committed processing cohort.
        timelines.put(COLLECTION_OWNER, Set.of("timeline/owner-new"));
        index.refresh(List.of(COLLECTION_OWNER), initial, timelines::get);

        // then
        assertEquals(Set.of("timeline/root", "timeline/owner-new"),
                index.timelineIds());
    }

    @Test
    void collectionActivationRecomputesFromCommittedActiveRows() {
        // given
        ContractsActiveSourceTimelineIndex index =
                new ContractsActiveSourceTimelineIndex(List.of(ROOT));
        ManagedOccurrenceInventory prospective =
                ManagedOccurrenceInventory.of(List.of(
                        active(ROOT, "/owner", COLLECTION_OWNER),
                        inactive(
                                COLLECTION_OWNER,
                                "/orders/member",
                                MEMBER)));
        Map<DocumentId, Set<String>> timelines = Map.of(
                ROOT, Set.of("timeline/root"),
                COLLECTION_OWNER, Set.of("timeline/owner"),
                MEMBER, Set.of("timeline/member"));
        index.refresh(List.of(ROOT), prospective, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/owner"),
                index.timelineIds());

        // when
        ManagedOccurrenceInventory committed = prospective.replaceSources(
                        List.of(COLLECTION_OWNER),
                        List.of(active(
                                COLLECTION_OWNER,
                                "/orders/member",
                                MEMBER)))
                .inventory();
        index.refresh(
                List.of(COLLECTION_OWNER, MEMBER),
                committed,
                timelines::get);

        // then
        assertEquals(Set.of(
                        "timeline/root",
                        "timeline/owner",
                        "timeline/member"),
                index.timelineIds());
    }

    @Test
    void independentAdmissionPreservesSharedTimelineReferencesAndImmutableSnapshots() {
        DocumentId second = DocumentId.of("second-root");
        ContractsActiveSourceTimelineIndex index = new ContractsActiveSourceTimelineIndex(List.of(ROOT));
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(List.of(active(ROOT, "/child", MEMBER)));
        Map<DocumentId, Set<String>> timelines = new LinkedHashMap<>();
        timelines.put(ROOT, Set.of("timeline/root"));
        timelines.put(second, Set.of("timeline/second"));
        timelines.put(MEMBER, Set.of("timeline/shared"));
        Map<DocumentId, Integer> reads = new LinkedHashMap<>();
        java.util.function.Function<DocumentId, Set<String>> resolver = document -> {
            reads.merge(document, 1, Integer::sum);
            return timelines.get(document);
        };
        index.refresh(List.of(ROOT), inventory, resolver);
        Set<String> originalSnapshot = index.timelineIds();
        index.addPublicRoots(List.of(second));
        inventory = inventory.replaceSources(List.of(second), List.of(active(second, "/child", MEMBER))).inventory();
        reads.clear();
        index.refresh(List.of(second), inventory, resolver);
        assertEquals(Map.of(second, 1, MEMBER, 1), reads);
        assertEquals(Set.of("timeline/root", "timeline/shared"), originalSnapshot);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared"), index.timelineIds());

        timelines.put(MEMBER, Set.of("timeline/shared-new"));
        reads.clear();
        index.refresh(List.of(MEMBER), inventory, resolver);
        assertEquals(Map.of(ROOT, 1, second, 1, MEMBER, 2), reads);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared-new"), index.timelineIds());
        inventory = inventory.replaceSources(List.of(ROOT), List.of()).inventory();
        index.refresh(List.of(ROOT), inventory, resolver);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared-new"), index.timelineIds());
        inventory = inventory.replaceSources(List.of(second), List.of()).inventory();
        index.refresh(List.of(second), inventory, resolver);
        assertEquals(Set.of("timeline/root", "timeline/second"), index.timelineIds());
        ContractsActiveSourceTimelineIndex reconstructed = new ContractsActiveSourceTimelineIndex(List.of(ROOT, second));
        reconstructed.refresh(List.of(ROOT, second), inventory, resolver);
        assertEquals(index.timelineIds(), reconstructed.timelineIds());
    }

    @Test
    void failedMultiRootResolutionLeavesPreviousCountsAndSnapshotsIntact() {
        DocumentId second = DocumentId.of("second-root");
        ContractsActiveSourceTimelineIndex index = new ContractsActiveSourceTimelineIndex(List.of(ROOT, second));
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(List.of(
                active(ROOT, "/child", MEMBER), active(second, "/child", MEMBER)));
        Map<DocumentId, Set<String>> timelines = new LinkedHashMap<>();
        timelines.put(ROOT, Set.of("timeline/root"));
        timelines.put(second, Set.of("timeline/second"));
        timelines.put(MEMBER, Set.of("timeline/shared"));
        index.refresh(List.of(ROOT, second), inventory, timelines::get);
        Set<String> retained = index.timelineIds();
        timelines.put(MEMBER, Set.of("timeline/replacement"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                index.refresh(List.of(MEMBER), inventory, document -> {
                    if (document.equals(second)) throw new IllegalStateException("source lookup failed");
                    return timelines.get(document);
                }));
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared"), index.timelineIds());
        assertEquals(retained, index.timelineIds());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> retained.remove("timeline/shared"));
        // Retrying one changed root must not see an unpublished replacement from
        // the failed batch. The other root still owns the old Timeline reference.
        index.refresh(List.of(ROOT), inventory, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared", "timeline/replacement"), index.timelineIds());
        index.refresh(List.of(second), inventory, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/replacement"), index.timelineIds());
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/shared"), retained);
    }

    @Test
    void parallelEdgesActivationAndRetargetKeepExactReverseMembership() {
        DocumentId second = DocumentId.of("second-root");
        ContractsActiveSourceTimelineIndex index = new ContractsActiveSourceTimelineIndex(List.of(ROOT, second));
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(List.of(
                active(ROOT, "/one", MEMBER), active(ROOT, "/two", MEMBER), active(second, "/child", MEMBER)));
        Map<DocumentId, Set<String>> timelines = Map.of(ROOT, Set.of("timeline/root"),
                second, Set.of("timeline/second"), MEMBER, Set.of("timeline/member"),
                PROSPECTIVE, Set.of("timeline/prospective"));
        index.refresh(List.of(ROOT, second), inventory, timelines::get);
        inventory = inventory.replaceSources(List.of(ROOT), List.of(
                inactive(ROOT, "/one", MEMBER), active(ROOT, "/two", MEMBER))).inventory();
        index.refresh(List.of(ROOT), inventory, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/member"), index.timelineIds());
        inventory = inventory.replaceSources(List.of(ROOT), List.of(
                inactive(ROOT, "/one", MEMBER), inactive(ROOT, "/two", MEMBER))).inventory();
        index.refresh(List.of(ROOT), inventory, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/member"), index.timelineIds());
        inventory = inventory.replaceSources(List.of(second), List.of(active(second, "/child", PROSPECTIVE))).inventory();
        index.refresh(List.of(second), inventory, timelines::get);
        assertEquals(Set.of("timeline/root", "timeline/second", "timeline/prospective"), index.timelineIds());
        Map<DocumentId, Integer> reads = new LinkedHashMap<>();
        index.refresh(List.of(MEMBER), inventory, document -> {
            reads.merge(document, 1, Integer::sum); return timelines.get(document);
        });
        assertEquals(Map.of(), reads);
        index.refresh(List.of(PROSPECTIVE), inventory, document -> {
            reads.merge(document, 1, Integer::sum); return timelines.get(document);
        });
        assertEquals(Map.of(second, 1, PROSPECTIVE, 1), reads);
        ContractsActiveSourceTimelineIndex reconstructed = new ContractsActiveSourceTimelineIndex(List.of(ROOT, second));
        reconstructed.refresh(List.of(ROOT, second), inventory, timelines::get);
        assertEquals(reconstructed.timelineIds(), index.timelineIds());
    }

    private static ManagedOccurrenceBinding active(
            DocumentId source,
            String path,
            DocumentId target) {
        return occurrence(source, path, target, true);
    }

    private static ManagedOccurrenceBinding inactive(
            DocumentId source,
            String path,
            DocumentId target) {
        return occurrence(source, path, target, false);
    }

    private static ManagedOccurrenceBinding occurrence(
            DocumentId source,
            String path,
            DocumentId target,
            boolean active) {
        return ManagedOccurrenceBinding.derived(
                POLICY,
                new blue.language.processor.closure.DocumentId(
                        source.value()),
                ScopeAddress.embedded(path, 1L),
                new blue.language.processor.closure.DocumentId(
                        target.value()),
                BLUE_ID,
                active,
                null);
    }
}
