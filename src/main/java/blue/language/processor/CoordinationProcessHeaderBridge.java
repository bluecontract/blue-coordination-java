package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Narrow package bridge for exact, verified PROCESS-header materialization.
 *
 * <p>The Language snapshot manager remains encapsulated. Coordination receives
 * only the exact immutable content for a reference it has already classified
 * as a registered non-executable header value.</p>
 */
public final class CoordinationProcessHeaderBridge {

    private CoordinationProcessHeaderBridge() {
    }

    /**
     * Opens one exact header reference through the processor's verified
     * snapshot boundary.
     *
     * @param processor configured document processor
     * @param reference exact pure reference selected by Coordination
     * @return exact immutable provider content
     */
    public static Node materializeVerifiedExactReference(
            DocumentProcessor processor,
            Node reference) {
        Node checked =
                Objects.requireNonNull(
                        reference, "reference");
        if (!checked.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "PROCESS header materialization requires a pure reference");
        }
        ProcessingSnapshotManager snapshots =
                Objects.requireNonNull(
                        Objects.requireNonNull(
                                processor, "processor")
                                .snapshotManager(),
                        "processor snapshotManager");
        FrozenNode materialized =
                snapshots.materializeVerifiedExactReference(
                        FrozenNode.fromNode(checked));
        if (materialized == null
                || materialized.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Verified PROCESS header content is unavailable for "
                            + checked.getBlueId());
        }
        return materialized.toNode();
    }

    /**
     * Returns an owned exact copy with resolved provider provenance removed.
     *
     * <p>PROCESS snapshots may expose nominal type definitions as a BlueId
     * together with their resolved fields. That resolved view is not legal
     * canonical fragment input. The Language-owned provenance normalizer
     * restores nominal references while retaining authored anonymous types
     * and ordinary exact content.</p>
     *
     * @param resolvedContent exact or resolved content owned by the caller
     * @return canonical-shape defensive copy suitable for fragmentation
     */
    public static Node canonicalExactCopy(
            Node resolvedContent) {
        Node exact =
                Objects.requireNonNull(
                        resolvedContent,
                        "resolvedContent")
                        .clone();
        MaterializationProvenance.clear(exact);
        return exact;
    }

    /**
     * Reports whether a deterministic external-function pass is attached to
     * the invocation-owned semantic output boundary.
     *
     * <p>Out-of-band subscription and feeder planning deliberately run
     * without that boundary. Coordination uses this distinction only to
     * return an identity-preserving reference when Language has already
     * carried the exact PROCESS event under the same identity.</p>
     *
     * @param workSession current external-function work session
     * @return whether exact input carry/reuse is available
     */
    public static boolean hasSemanticOutputBoundary(
            RuntimeWorkSession workSession) {
        return Objects.requireNonNull(
                workSession, "workSession")
                .hasSemanticOutputBoundary();
    }
}
