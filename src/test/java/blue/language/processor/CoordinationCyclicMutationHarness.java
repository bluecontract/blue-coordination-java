package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Collections;
import java.util.Objects;

/**
 * Test bridge for the immutable mutation boundary used by Coordination
 * workflow updates.
 */
public final class CoordinationCyclicMutationHarness {

    private CoordinationCyclicMutationHarness() {
    }

    /**
     * Plans one exact replacement while retaining the supplied opaque cyclic
     * edge as an unresolved canonical reference.
     *
     * @param processor configured processor
     * @param root exact canonical Root
     * @param opaquePath absolute path of the opaque cyclic edge
     * @param replacementPath absolute replacement path
     * @param replacement exact replacement value
     * @return exact resulting canonical Root
     */
    public static Node replace(
            DocumentProcessor processor,
            Node root,
            String opaquePath,
            String replacementPath,
            Node replacement) {
        ProcessingSnapshotManager snapshots =
                Objects.requireNonNull(
                        processor, "processor")
                        .snapshotManager();
        ResolvedSnapshot snapshot =
                snapshots
                        .fromDocumentTransientPreservingPaths(
                                Objects.requireNonNull(
                                        root, "root")
                                        .clone(),
                                Collections.singleton(
                                        opaquePath));
        return ImmutablePatchPlanner
                .forSnapshot(snapshot)
                .planWithExactReplacement(
                        "/",
                        JsonPatch.replace(
                                replacementPath,
                                Objects.requireNonNull(
                                        replacement,
                                        "replacement")))
                .rootNode();
    }
}
