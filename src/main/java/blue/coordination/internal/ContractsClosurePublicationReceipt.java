package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Durable terminal receipt for one already-frozen PROCESS_CLOSURE cohort.
 *
 * <p>The exact completed Contracts attempt is retained so a crash after the
 * store swap can be reconciled without re-executing Contracts.  Both commits
 * and deterministic rollback results are terminal; a resource suspension is
 * deliberately not representable.</p>
 */
record ContractsClosurePublicationReceipt(
        String publicationIdentity,
        List<DocumentId> documentIds,
        ClosureAttemptResult attempt,
        long automaticRetryCount) {

    ContractsClosurePublicationReceipt(
            String publicationIdentity,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt) {
        this(publicationIdentity, documentIds, attempt, 0L);
    }

    ContractsClosurePublicationReceipt {
        publicationIdentity = requireText(
                publicationIdentity, "publicationIdentity");
        attempt = Objects.requireNonNull(attempt, "attempt");
        if (!attempt.isComplete()) {
            throw new IllegalArgumentException(
                    "A durable process receipt requires a completed attempt");
        }
        MultiDocumentPublicationTransaction.requireSafeInteger(
                automaticRetryCount, "automaticRetryCount");
        if (attempt.processResult().status()
                == ProcessorStatus.CAPABILITY_FAILURE) {
            throw new IllegalArgumentException(
                    "Capability failure is retryable host state, not a durable "
                            + "process disposition");
        }
        TreeSet<DocumentId> canonical = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : Objects.requireNonNull(
                documentIds, "documentIds")) {
            if (!canonical.add(Objects.requireNonNull(
                    documentId, "documentId"))) {
                throw new IllegalArgumentException(
                        "Process receipt repeats document " + documentId);
            }
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "A process receipt must identify a non-empty cohort");
        }
        documentIds = List.copyOf(new ArrayList<>(canonical));

        ClosureProcessResult result = attempt.processResult();
        Set<DocumentId> resultDocuments = new LinkedHashSet<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            if (!resultDocuments.add(DocumentId.of(
                    document.documentId().value()))) {
                throw new IllegalArgumentException(
                        "Process receipt result repeats document "
                                + document.documentId().value());
            }
        }
        if (!resultDocuments.equals(new LinkedHashSet<>(documentIds))) {
            throw new IllegalArgumentException(
                    "Process receipt cohort differs from its exact result");
        }
    }

    /** Whether the retained terminal result committed durable effects. */
    boolean commits() {
        return attempt.processResult().commits();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
