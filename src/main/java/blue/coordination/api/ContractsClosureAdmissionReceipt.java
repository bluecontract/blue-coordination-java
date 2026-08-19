package blue.coordination.api;

import blue.language.processor.closure.ClosureAttemptResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Exact attempt and durable-publication evidence for one Contracts admission.
 *
 * <p>A resource suspension or semantic rejection has
 * {@link PublicationOutcome#NOT_PUBLISHED} and leaves Coordination state
 * unchanged. A retry of an already durable publication returns the original
 * immutable Contracts attempt with
 * {@link PublicationOutcome#ALREADY_PUBLISHED}; Contracts is not executed a
 * second time.</p>
 *
 * @param attempt exact completed result or exact resource suspension
 * @param publicationIdentity stable host publication identity
 * @param publicationOutcome whether this call published or replayed a receipt
 * @param documentIds canonical admitted document lineages
 */
public record ContractsClosureAdmissionReceipt(
        ClosureAttemptResult attempt,
        String publicationIdentity,
        PublicationOutcome publicationOutcome,
        List<DocumentId> documentIds) {

    /** Validates the relationship between the attempt and publication state. */
    public ContractsClosureAdmissionReceipt {
        attempt = Objects.requireNonNull(attempt, "attempt");
        publicationIdentity = requireText(
                publicationIdentity, "publicationIdentity");
        publicationOutcome = Objects.requireNonNull(
                publicationOutcome, "publicationOutcome");
        List<DocumentId> supplied = List.copyOf(Objects.requireNonNull(
                documentIds, "documentIds"));
        TreeSet<DocumentId> canonical = new TreeSet<>();
        for (DocumentId documentId : supplied) {
            if (!canonical.add(Objects.requireNonNull(
                    documentId, "documentId"))) {
                throw new IllegalArgumentException(
                        "Admission receipt repeats document " + documentId);
            }
        }
        documentIds = List.copyOf(new ArrayList<>(canonical));
        boolean commits = attempt.isComplete()
                && attempt.processResult().commits();
        if (publicationOutcome == PublicationOutcome.NOT_PUBLISHED) {
            if (commits || !documentIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "An unpublished admission cannot retain committed documents");
            }
        } else if (!commits || documentIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "A published admission requires a committing result and documents");
        }
    }

    /** Returns whether durable state exists for this admission. */
    public boolean published() {
        return publicationOutcome != PublicationOutcome.NOT_PUBLISHED;
    }

    /** Closed durable-publication outcome. */
    public enum PublicationOutcome {
        /** The attempt suspended or rejected and made no durable mutation. */
        NOT_PUBLISHED,
        /** This call atomically published the successful admission. */
        PUBLISHED,
        /** A prior call published it and this call reconciled that receipt. */
        ALREADY_PUBLISHED
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
