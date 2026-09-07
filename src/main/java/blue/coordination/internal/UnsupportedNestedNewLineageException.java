package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;

import java.util.Map;
import java.util.Objects;

/**
 * Owning-layer signal when a retained application cannot authenticate a new
 * lineage's complete Contracts birth evidence.
 */
final class UnsupportedNestedNewLineageException
        extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final ManagedEpochApplicationWork work;
    private final DocumentId newDocumentId;

    UnsupportedNestedNewLineageException(
            ManagedEpochApplicationWork work,
            DocumentId newDocumentId) {
        super("Retained managed-epoch application requires complete Contracts "
                + "birth evidence for new nested lineage "
                + Objects.requireNonNull(newDocumentId, "newDocumentId"));
        this.work = Objects.requireNonNull(work, "work");
        this.newDocumentId = newDocumentId;
    }

    Map<String, String> details() {
        return Map.of(
                "workIdentity", work.workIdentity(),
                "planIdentity", work.planIdentity(),
                "barrierIdentity", work.barrierIdentity(),
                "sourceReceiptIdentity", work.sourceReceiptIdentity(),
                "consumerDocumentId", work.consumerDocumentId().value(),
                "sourceDocumentId", work.sourceDocumentId().value(),
                "sourceEpoch", Long.toString(work.sourceEpoch()),
                "targetOccurrenceIdentity",
                work.targetOccurrenceIdentity(),
                "targetPath", work.targetPath(),
                "newDocumentId", newDocumentId.value());
    }
}
