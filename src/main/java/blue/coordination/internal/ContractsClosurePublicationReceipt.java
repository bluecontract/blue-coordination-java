package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.ProcessorStatus;

import java.util.List;
import java.util.Objects;
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
        long automaticRetryCount,
        ManagedSurfacePublicationEvidence managedSurfaceEvidence,
        ContractsManagedDraftPlan rejectedDraftPlan) {

    ContractsClosurePublicationReceipt(String identity, List<DocumentId> members,
            ClosureAttemptResult attempt, long retries, ManagedSurfacePublicationEvidence evidence) {
        this(identity, members, attempt, retries, evidence, null);
    }


    ContractsClosurePublicationReceipt(
            String publicationIdentity,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt) {
        this(publicationIdentity, documentIds, attempt, 0L,
                ManagedSurfacePublicationEvidence.empty());
    }

    ContractsClosurePublicationReceipt(
            String publicationIdentity,
            List<DocumentId> documentIds,
            ClosureAttemptResult attempt,
            long automaticRetryCount) {
        this(publicationIdentity, documentIds, attempt, automaticRetryCount,
                ManagedSurfacePublicationEvidence.empty());
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
        managedSurfaceEvidence = Objects.requireNonNull(
                managedSurfaceEvidence, "managedSurfaceEvidence");
        if (attempt.processResult().status()
                == ProcessorStatus.CAPABILITY_FAILURE) {
            throw new IllegalArgumentException(
                    "Capability failure is retryable host state, not a durable "
                            + "process disposition");
        }
        documentIds = List.copyOf(documentIds);
        TreeSet<DocumentId> canonical = new TreeSet<>(EmbeddingBinding.DOCUMENT_ORDER);
        canonical.addAll(documentIds);
        if (canonical.isEmpty() || canonical.size() != documentIds.size()) {
            throw new IllegalArgumentException("Process receipt requires a non-empty unique cohort");
        }
        documentIds = List.copyOf(canonical);
        ClosureProcessResult result = attempt.processResult();
        ContractsClosureAdapter.resultingDocuments(result, canonical);
        if (rejectedDraftPlan != null && (!result.commits()
                || managedSurfaceEvidence.present()
                || !rejectedDraftPlan.missingExpectedOccurrence(result))) {
            throw new IllegalArgumentException("Host rejection requires an exact unmet managed occurrence expectation");
        }
        if (!result.commits()
                && managedSurfaceEvidence.present()) {
            throw new IllegalArgumentException(
                    "A non-committing receipt cannot retain managed "
                            + "publication evidence");
        }
    }

    /** Returns the same receipt with its prepared route-index delta retained. */
    ContractsClosurePublicationReceipt withOperationRouteChanges(
            List<OperationRouteIndex.OperationRouteChange> changes) {
        if (!commits()) {
            throw new IllegalStateException(
                    "A non-committing receipt has no route publication");
        }
        return new ContractsClosurePublicationReceipt(
                publicationIdentity,
                documentIds,
                attempt,
                automaticRetryCount,
                managedSurfaceEvidence.withOperationRouteChanges(changes));
    }

    /** Whether the retained terminal result committed durable effects. */
    boolean commits() {
        return rejectedDraftPlan == null && attempt.processResult().commits();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
