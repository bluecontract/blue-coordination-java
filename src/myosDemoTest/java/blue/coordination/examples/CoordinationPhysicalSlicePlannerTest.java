package blue.coordination.examples;

import blue.coordination.engine.CoordinationFragmentSlicePlanner;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentSlicePlan;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves path selection is bounded by physical roots, not the owning Root. */
final class CoordinationPhysicalSlicePlannerTest {

    @Test
    void shouldSelectOnlyEmb1Emb2PhysicalRootsAndExcludeSibling() {
        // given
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                        "root-id",
                        List.of("root-id", "emb1-id", "emb2-id", "sibling-id"),
                        List.of(
                                root("root-id",
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT,
                                        ""),
                                root("emb1-id",
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/emb1"),
                                root("emb2-id",
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/emb1/emb2"),
                                root("sibling-id",
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/sibling")),
                        List.of(),
                        List.of());

        // when
        CoordinationFragmentSlicePlan slice =
                new CoordinationFragmentSlicePlanner().plan(
                        inventory, "/emb1");

        // then
        assertEquals("emb1-id", slice.selectedRootBlueId());
        assertEquals(List.of("emb1-id", "emb2-id"),
                slice.fragmentBlueIds());
        assertEquals(2, slice.roots().size());
        assertThrows(IllegalArgumentException.class, () ->
                new CoordinationFragmentSlicePlanner().plan(
                        inventory, "/missing"));
    }

    private static FragmentRootRecord root(
            String blueId,
            CoordinationDocumentSplitter.FragmentRootKind kind,
            String path) {
        return new FragmentRootRecord(blueId, kind, path);
    }
}
