package blue.coordination.api;

import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Advanced exact Contracts evidence retained for one dispatched cohort.
 *
 * <p>The stable SDK translates this low-level attempt into application-facing
 * closure results. Existing Coordination callers may ignore it.</p>
 *
 * @param entryBlueId exact external Timeline Entry identity
 * @param documentIds canonical affected cohort members
 * @param attempt exact completed or suspended Contracts attempt
 * @param published whether durable state was published
 * @param publicationIdentity durable publication identity, when available
 * @param replayed whether an existing publication receipt was reconciled
 * @param automaticRetryCount number of resource-resolution retries before
 *                             this retained attempt
 * @param operationRouteChanges exact committed route-index reconciliation
 *                              changes
 */
public record ContractsClosureDispatchAttempt(
        String entryBlueId,
        List<DocumentId> documentIds,
        ClosureAttemptResult attempt,
        boolean published,
        String publicationIdentity,
        boolean replayed,
        long automaticRetryCount,
        List<ManagedOccurrenceResolution> managedOccurrenceResolutions,
        List<ComponentSnapshot> inputComponents,
        List<OperationRouteChange> operationRouteChanges) {

    /**
     * Preserves the original additive SDK seam for callers that do not need
     * automatic resource-resolution evidence.
     */
    public ContractsClosureDispatchAttempt(
            String entryBlueId,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt,
            boolean published,
            String publicationIdentity,
            boolean replayed) {
        this(entryBlueId, documentIds, attempt, published,
                publicationIdentity, replayed, 0L, List.of(), List.of(),
                List.of());
    }

    /**
     * Preserves the retry-evidence constructor for callers that do not need
     * managed publication presentation evidence.
     */
    public ContractsClosureDispatchAttempt(
            String entryBlueId,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt,
            boolean published,
            String publicationIdentity,
            boolean replayed,
            long automaticRetryCount) {
        this(entryBlueId, documentIds, attempt, published,
                publicationIdentity, replayed, automaticRetryCount,
                List.of(), List.of(), List.of());
    }

    /**
     * Preserves the managed-publication constructor for callers compiled
     * before typed operation-route changes were added.
     */
    public ContractsClosureDispatchAttempt(
            String entryBlueId,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt,
            boolean published,
            String publicationIdentity,
            boolean replayed,
            long automaticRetryCount,
            List<ManagedOccurrenceResolution> managedOccurrenceResolutions,
            List<ComponentSnapshot> inputComponents) {
        this(entryBlueId, documentIds, attempt, published,
                publicationIdentity, replayed, automaticRetryCount,
                managedOccurrenceResolutions, inputComponents, List.of());
    }

    /** Validates immutable cohort evidence. */
    public ContractsClosureDispatchAttempt {
        entryBlueId = requireText(entryBlueId, "entryBlueId");
        documentIds = List.copyOf(Objects.requireNonNull(
                documentIds, "documentIds"));
        attempt = Objects.requireNonNull(attempt, "attempt");
        managedOccurrenceResolutions = List.copyOf(Objects.requireNonNull(
                managedOccurrenceResolutions,
                "managedOccurrenceResolutions"));
        inputComponents = List.copyOf(Objects.requireNonNull(
                inputComponents, "inputComponents"));
        operationRouteChanges = List.copyOf(Objects.requireNonNull(
                operationRouteChanges, "operationRouteChanges"));
        if (automaticRetryCount < 0L) {
            throw new IllegalArgumentException(
                    "automaticRetryCount must be non-negative");
        }
        if (publicationIdentity != null && publicationIdentity.isBlank()) {
            throw new IllegalArgumentException(
                    "publicationIdentity must not be blank");
        }
        if (published && (!attempt.isComplete()
                || !attempt.processResult().commits())) {
            throw new IllegalArgumentException(
                    "Only a committing attempt can be published");
        }
        if (replayed && publicationIdentity == null) {
            throw new IllegalArgumentException(
                    "A replayed attempt requires a publication identity");
        }
        if (!published && (!managedOccurrenceResolutions.isEmpty()
                || !inputComponents.isEmpty()
                || !operationRouteChanges.isEmpty())) {
            throw new IllegalArgumentException(
                    "Only a published attempt can expose managed "
                            + "publication evidence");
        }
    }

    /** Automatic resolver classification for one committed occurrence. */
    public record ManagedOccurrenceResolution(
            String demandIdentity,
            ManagedOccurrenceBinding occurrence,
            TargetKind targetKind,
            Optional<ExactValue> authoredInitial) {

        /** Validates the exact immutable resolution tuple. */
        public ManagedOccurrenceResolution {
            demandIdentity = requireText(demandIdentity, "demandIdentity");
            occurrence = Objects.requireNonNull(occurrence, "occurrence");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            authoredInitial = Objects.requireNonNull(
                    authoredInitial, "authoredInitial");
            if ((targetKind == TargetKind.NEW_AUTHORED)
                    != authoredInitial.isPresent()) {
                throw new IllegalArgumentException(
                        "Only NEW_AUTHORED retains authored initial content");
            }
        }
    }

    /** Processor-owned automatic occurrence target classification. */
    public enum TargetKind {
        /** The exact current state of an existing managed lineage. */
        CURRENT_EXISTING,
        /** A new lineage initialized from the exact authored value. */
        NEW_AUTHORED
    }

    /** Closed operation-route transition kind. */
    public enum OperationRouteChangeKind {
        ADD,
        REMOVE,
        REPLACE
    }

    /** Exact externally routable operation state retained by Coordination. */
    public record OperationRouteState(
            String scopePath,
            String operation,
            String channel,
            List<Timeline> acceptedSources) {
        /** Validates one immutable route state. */
        public OperationRouteState {
            scopePath = requireText(scopePath, "scopePath");
            if (!scopePath.startsWith("/")) {
                throw new IllegalArgumentException(
                        "scopePath must be absolute");
            }
            operation = requireText(operation, "operation");
            channel = requireText(channel, "channel");
            acceptedSources = List.copyOf(Objects.requireNonNull(
                    acceptedSources, "acceptedSources"));
        }
    }

    /** One exact committed operation-route index transition. */
    public record OperationRouteChange(
            OperationRouteChangeKind kind,
            DocumentId documentId,
            Optional<OperationRouteState> before,
            Optional<OperationRouteState> after) {
        /** Validates the closed before/after transition shape. */
        public OperationRouteChange {
            kind = Objects.requireNonNull(kind, "kind");
            documentId = Objects.requireNonNull(documentId, "documentId");
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            if ((kind == OperationRouteChangeKind.ADD
                    && (before.isPresent() || after.isEmpty()))
                    || (kind == OperationRouteChangeKind.REMOVE
                    && (before.isEmpty() || after.isPresent()))
                    || (kind == OperationRouteChangeKind.REPLACE
                    && (before.isEmpty() || after.isEmpty()))) {
                throw new IllegalArgumentException(
                        "Operation route change kind disagrees with its sides");
            }
        }
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
