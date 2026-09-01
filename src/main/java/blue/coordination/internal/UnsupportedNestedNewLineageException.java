package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;

import java.util.Map;
import java.util.Objects;

/**
 * Owning-layer signal for the deliberately unsupported publication of a new
 * document lineage from retained managed-epoch processing.
 */
final class UnsupportedNestedNewLineageException
        extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final ManagedEpochApplicationWork work;
    private final DocumentId newDocumentId;

    UnsupportedNestedNewLineageException(
            ManagedEpochApplicationWork work,
            DocumentId newDocumentId) {
        super("Retained managed-epoch application cannot create a new nested "
                + "document lineage "
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
