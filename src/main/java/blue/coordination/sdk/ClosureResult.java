package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Independent terminal result for one affected disconnected closure. */
public record ClosureResult(
        String closureId,
        EntryDisposition disposition,
        List<DocumentChange> changes,
        List<PublicEvent> publicEvents,
        ProcessingStats stats,
        Diagnostic diagnostic,
        List<ResourceDemand> resourceDemands,
        long processorAttemptCount,
        ManagedSurfaceEvidence managedSurfaceEvidence) {

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
                List.of(), 1L, ManagedSurfaceEvidence.empty());
    }

    /**
     * Preserves the resource-evidence constructor for callers that do not
     * need committed managed-surface presentation evidence.
     */
    public ClosureResult(
            String closureId,
            EntryDisposition disposition,
            List<DocumentChange> changes,
            List<PublicEvent> publicEvents,
            ProcessingStats stats,
            Diagnostic diagnostic,
            List<ResourceDemand> resourceDemands,
            long processorAttemptCount) {
        this(closureId, disposition, changes, publicEvents, stats, diagnostic,
                resourceDemands, processorAttemptCount,
                ManagedSurfaceEvidence.empty());
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
        managedSurfaceEvidence = Objects.requireNonNull(
                managedSurfaceEvidence, "managedSurfaceEvidence");
        if (processorAttemptCount < 1L) {
            throw new IllegalArgumentException(
                    "processorAttemptCount must be positive");
        }
        if (disposition != EntryDisposition.APPLIED
                && managedSurfaceEvidence.present()) {
            throw new IllegalArgumentException(
                    "Only an applied closure may expose managed-surface "
                            + "publication evidence");
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
            String sourcePath,
            Optional<ManagedResolutionStatus> managedResolutionStatus,
            Optional<String> managedResolutionDiagnostic) {

        /**
         * Preserves the original demand constructor when automatic managed
         * matching did not classify the suspended resource.
         */
        public ResourceDemand(
                String kind,
                String demandIdentity,
                String blueId,
                DocumentId sourceDocumentId,
                String sourcePath) {
            this(kind, demandIdentity, blueId, sourceDocumentId, sourcePath,
                    Optional.empty(), Optional.empty());
        }

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
            managedResolutionStatus = Objects.requireNonNull(
                    managedResolutionStatus, "managedResolutionStatus");
            managedResolutionDiagnostic = Objects.requireNonNull(
                    managedResolutionDiagnostic,
                    "managedResolutionDiagnostic");
            managedResolutionDiagnostic = managedResolutionDiagnostic.map(
                    diagnostic -> SdkPreconditions.requireText(
                            diagnostic, "managedResolutionDiagnostic value"));
            if (managedResolutionStatus.isPresent()
                    != managedResolutionDiagnostic.isPresent()) {
                throw new IllegalArgumentException(
                        "Managed resolution status and diagnostic must be "
                                + "present together");
            }
        }
    }

    /** Closed host-persistable automatic managed matching classification. */
    public enum ManagedResolutionStatus {
        /** Exact referenced or partially materialized content is unavailable. */
        MISSING_EXACT_CONTENT,
        /** Several managed lineages match the supplied exact value. */
        AMBIGUOUS_MANAGED_LINEAGE,
        /** Several historical epochs match in the selected lineage. */
        AMBIGUOUS_MANAGED_EPOCH,
        /** No retained lineage proves a progressed supplied value. */
        UNPROVEN_MANAGED_HISTORY,
        /** Explicit selection disagrees with retained exact state. */
        EXACT_STATE_MISMATCH,
        /** Authored content cannot initialize a valid managed document. */
        INVALID_AUTHORED_DOCUMENT
    }
}
