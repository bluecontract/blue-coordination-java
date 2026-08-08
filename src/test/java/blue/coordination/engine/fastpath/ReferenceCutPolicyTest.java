package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReferenceCutPolicyTest {

    @Test
    void rootProtectionMustNotAccidentallyDisableEveryDescendantCut() {
        ReferenceCutPolicy policy = ReferenceCutPolicy.strictDefaults();
        FragmentEdgeRecord cold = splitterCreatedEdge("/cold");

        assertTrue(policy.mayCut(cold, ActivePathSet.of(
                Collections.singletonList("/hot"))));
        assertFalse(policy.mayCut(
                cold,
                ActivePathSet.of(
                        Collections.singletonList("/cold/selected"))));
    }

    @Test
    void activeClosureRetainsContractRootWithoutInliningItsWholeSubtree() {
        ReferenceCutPolicy policy = ReferenceCutPolicy.strictDefaults();
        ActivePathSet active = ActivePathSet.of(
                Collections.singletonList("/contracts"));

        assertFalse(policy.mayCut(
                splitterCreatedEdge("/contracts"),
                active));
        assertTrue(policy.mayCut(
                splitterCreatedEdge("/contracts/workflow/steps"),
                active));
    }

    @Test
    void largeActiveSurfaceUsesPreindexedAncestorClosure() {
        int paths = 4_096;
        List<String> supplied = new ArrayList<String>(paths);
        for (int index = 0; index < paths; index++) {
            supplied.add("/tenant-" + index + "/contracts/workflow");
        }

        ActivePathSet active = ActivePathSet.of(supplied);

        assertTrue(active.enters("/tenant-0"));
        assertTrue(active.enters("/tenant-4095/contracts"));
        assertTrue(active.enters(
                "/tenant-2048/contracts/workflow"));
        assertFalse(active.enters("/unrelated-decoy"));
        assertFalse(active.enters("/tenant-0/contracts/workflow/body"));
        assertTrue(active.enteredAncestorCount() <= paths * 3 + 1,
                "the closure must contain prefixes, not path-pair products");
        assertTrue(active.identity().startsWith(
                "blue.coordination/reference-cut/active-paths/1:"));
    }

    private static FragmentEdgeRecord splitterCreatedEdge(String path) {
        String owner = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties("cold", new Node().value("value")));
        String child = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("value"));
        return new FragmentEdgeRecord(
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT,
                owner,
                owner,
                "/",
                path,
                path,
                child,
                CoordinationDocumentSplitter.EdgeKind.DOCUMENT_DIRECT_CHILD,
                false,
                true,
                null,
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin.NONE,
                null,
                null,
                null,
                null,
                null,
                Collections.emptyList());
    }
}
