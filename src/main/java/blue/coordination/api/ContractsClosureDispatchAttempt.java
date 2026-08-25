package blue.coordination.api;

import blue.language.processor.closure.ClosureAttemptResult;

import java.util.List;
import java.util.Objects;

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
        long automaticRetryCount) {

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
                publicationIdentity, replayed, 0L);
    }

    /** Validates immutable cohort evidence. */
    public ContractsClosureDispatchAttempt {
        entryBlueId = requireText(entryBlueId, "entryBlueId");
        documentIds = List.copyOf(Objects.requireNonNull(
                documentIds, "documentIds"));
        attempt = Objects.requireNonNull(attempt, "attempt");
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
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
