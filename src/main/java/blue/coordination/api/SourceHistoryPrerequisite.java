package blue.coordination.api;

import blue.language.processor.ExternalOrderKey;
import java.util.Objects;

/**
 * One fenced source-owned prerequisite of an actual suspended rooted input.
 * This operational descriptor grants no authority to replace a source receipt.
 *
 * @param selectionIdentity identity of all exact selection operands
 * @param requestingRoot root whose unchanged input needs the source
 * @param requestingInvocationIdentity original requesting processor input
 * @param demandIdentity actual processor-issued occurrence demand
 * @param sourceDocumentId independently owned source lineage
 * @param authoredBlueId exact originally authored source body
 * @param cutoffExclusive frozen parent boundary, never a worker clock
 * @param kind one source action or an explicit resource wait
 * @param sourceEpoch fenced current source epoch, or minus one before admission
 * @param sourceBlueId fenced current source identity, or the original authored identity
 * @param workIdentity admission input, LIVE input, or exact retained work identity
 * @param entryBlueId exact LIVE entry, or null for other phases
 * @param journalRevision exact local completeness revision
 * @param routeGeneration exact source-provider routing generation
 * @param sourceSurfaceIdentity authenticated effective source surface fingerprint
 * @param diagnostic reason for a wait, or null for executable work
 */
public record SourceHistoryPrerequisite(String selectionIdentity, DocumentId requestingRoot,
        String requestingInvocationIdentity, String demandIdentity, DocumentId sourceDocumentId,
        String authoredBlueId, ExternalOrderKey cutoffExclusive, Kind kind, long sourceEpoch,
        String sourceBlueId, String workIdentity, String entryBlueId, long journalRevision,
        long routeGeneration, String sourceSurfaceIdentity, String diagnostic) {
    /** Validates the closed one-step phase and its exact coordinates. */
    public SourceHistoryPrerequisite {
        Objects.requireNonNull(selectionIdentity); Objects.requireNonNull(requestingRoot);
        Objects.requireNonNull(requestingInvocationIdentity); Objects.requireNonNull(demandIdentity);
        Objects.requireNonNull(sourceDocumentId); Objects.requireNonNull(authoredBlueId);
        Objects.requireNonNull(cutoffExclusive); Objects.requireNonNull(kind); Objects.requireNonNull(sourceBlueId);
        Objects.requireNonNull(workIdentity); Objects.requireNonNull(sourceSurfaceIdentity);
        if (sourceEpoch < -1L || journalRevision < 0L || routeGeneration < 0L
                || (kind == Kind.LIVE) != (entryBlueId != null)
                || (kind == Kind.WAIT) != (diagnostic != null)
                || kind == Kind.ADMISSION && sourceEpoch != -1L) {
            throw new IllegalArgumentException("Invalid source-prerequisite phase coordinates");
        }
    }

    /** Closed set of separately reported source-owned actions. */
    public enum Kind {
        /** Complete provider evidence is not available; no processing is permitted. */
        WAIT,
        /** One genuine FULL_HISTORY source admission. */
        ADMISSION,
        /** One exact source-owned LIVE input below the parent boundary. */
        LIVE,
        /** One ordinary source-owned retained-history application. */
        MANAGED_HISTORY,
        /** One exact historical calculation in the source's retained rooted view. */
        ROOTED_RETAINED
    }
}
