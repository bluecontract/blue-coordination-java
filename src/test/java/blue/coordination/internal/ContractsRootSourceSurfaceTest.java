package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ContractsRootSourceSurfaceTest {
    private static final String POLICY =
            "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String BLUE_ID =
            "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr";
    private static final DocumentId ROOT = DocumentId.of("root");
    private static final DocumentId CHILD = DocumentId.of("child");
    private static final DocumentId LEAF = DocumentId.of("leaf");
    private static final DocumentId INACTIVE = DocumentId.of("inactive");
    private static final DocumentId OTHER_ROOT = DocumentId.of("other-root");

    @Test
    void rootLaneOwnsUnionOfRootAndActiveEmbeddedTimelinesOnly() {
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        active(ROOT, "/child", CHILD),
                        active(CHILD, "/leaf", LEAF),
                        active(LEAF, "/back", ROOT),
                        inactive(ROOT, "/inactive", INACTIVE)));
        Map<DocumentId, Set<String>> timelines = Map.of(
                ROOT, Set.of("timeline/root", "timeline/shared"),
                CHILD, Set.of("timeline/child", "timeline/shared"),
                LEAF, Set.of("timeline/leaf"),
                INACTIVE, Set.of("timeline/inactive"),
                OTHER_ROOT, Set.of("timeline/other"));

        ContractsRootSourceSurface.Surface surface =
                ContractsRootSourceSurface.resolve(
                        ContractsRootFeederWindow.LaneId.publicRoots(
                                List.of(ROOT)),
                        inventory,
                        document -> timelines.getOrDefault(document, Set.of()));

        assertEquals(List.of(CHILD, LEAF, ROOT),
                surface.managedDocuments());
        assertEquals(Set.of(
                        "timeline/root",
                        "timeline/shared",
                        "timeline/child",
                        "timeline/leaf"),
                surface.timelineIds());
    }

    @Test
    void disconnectedPublicRootKeepsAnIndependentSourceSurface() {
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(active(ROOT, "/child", CHILD)));
        Map<DocumentId, Set<String>> timelines = Map.of(
                ROOT, Set.of("timeline/root"),
                CHILD, Set.of("timeline/child"),
                OTHER_ROOT, Set.of("timeline/other"));

        ContractsRootSourceSurface.Surface first =
                ContractsRootSourceSurface.resolve(
                        ContractsRootFeederWindow.LaneId.publicRoots(
                                List.of(ROOT)),
                        inventory,
                        document -> timelines.getOrDefault(document, Set.of()));
        ContractsRootSourceSurface.Surface second =
                ContractsRootSourceSurface.resolve(
                        ContractsRootFeederWindow.LaneId.publicRoots(
                                List.of(OTHER_ROOT)),
                        inventory,
                        document -> timelines.getOrDefault(document, Set.of()));

        assertEquals(List.of(CHILD, ROOT), first.managedDocuments());
        assertEquals(Set.of("timeline/root", "timeline/child"),
                first.timelineIds());
        assertEquals(List.of(OTHER_ROOT), second.managedDocuments());
        assertEquals(Set.of("timeline/other"), second.timelineIds());
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
