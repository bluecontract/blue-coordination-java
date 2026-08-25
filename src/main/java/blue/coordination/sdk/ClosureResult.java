package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
import java.util.Objects;

/** Independent terminal result for one affected disconnected closure. */
public record ClosureResult(
        String closureId,
        EntryDisposition disposition,
        List<DocumentChange> changes,
        List<PublicEvent> publicEvents,
        ProcessingStats stats,
        Diagnostic diagnostic,
        List<ResourceDemand> resourceDemands,
        long processorAttemptCount) {

    /**
     * Preserves the original result constructor for callers that do not need
     * typed resource or automatic retry evidence.
     */
    public ClosureResult(
            String closureId,
            EntryDisposition disposition,
            List<DocumentChange> changes,
            List<PublicEvent> publicEvents,
            ProcessingStats stats,
            Diagnostic diagnostic) {
        this(closureId, disposition, changes, publicEvents, stats, diagnostic,
                List.of(), 1L);
    }

    /** Defensively copies result collections. */
    public ClosureResult {
        closureId = SdkPreconditions.requireText(closureId, "closureId");
        disposition = Objects.requireNonNull(disposition, "disposition");
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
        publicEvents = List.copyOf(Objects.requireNonNull(
                publicEvents, "publicEvents"));
        stats = Objects.requireNonNull(stats, "stats");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        resourceDemands = List.copyOf(Objects.requireNonNull(
                resourceDemands, "resourceDemands"));
        if (processorAttemptCount < 1L) {
            throw new IllegalArgumentException(
                    "processorAttemptCount must be positive");
        }
    }

    /** Whether this closure published all of its exact state changes. */
    public boolean applied() {
        return disposition == EntryDisposition.APPLIED;
    }

    /** Number of automatic resource-resolution retries before this result. */
    public long automaticRetryCount() {
        return processorAttemptCount - 1L;
    }

    /** Stable typed description of one unresolved exact resource. */
    public record ResourceDemand(
            String kind,
            String demandIdentity,
            String blueId,
            DocumentId sourceDocumentId,
            String sourcePath) {

        /** Validates the immutable evidence tuple. */
        public ResourceDemand {
            kind = SdkPreconditions.requireText(kind, "kind");
            demandIdentity = SdkPreconditions.requireText(
                    demandIdentity, "demandIdentity");
            blueId = SdkPreconditions.requireText(blueId, "blueId");
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            sourcePath = SdkPreconditions.requireText(
                    sourcePath, "sourcePath");
        }
    }
}
