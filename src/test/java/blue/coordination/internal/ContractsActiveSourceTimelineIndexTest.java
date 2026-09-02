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
            "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr";
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

        // when: only the descendant joins the committed processing cohort
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
