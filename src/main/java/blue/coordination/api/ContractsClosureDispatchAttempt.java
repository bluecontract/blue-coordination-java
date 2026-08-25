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
        List<ComponentSnapshot> inputComponents) {

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
                publicationIdentity, replayed, 0L, List.of(), List.of());
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
                List.of(), List.of());
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
                || !inputComponents.isEmpty())) {
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

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
