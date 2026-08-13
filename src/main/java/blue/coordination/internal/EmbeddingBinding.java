package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Objects;

/** Immutable topology and activation identity for one Process Embedded occurrence. */
record EmbeddingBinding(
        String bindingId,
        DocumentId parentDocumentId,
        String absolutePath,
        DocumentId childDocumentId,
        long activationGeneration,
        ActivationMode activationMode,
        ExternalOrderKey establishedFrontier,
        String admittedChildBlueId,
        Long admittedChildEpoch,
        String admissionProofIdentity,
        String attachmentEntryBlueId,
        ExternalOrderKey attachmentOrder) {
    static final Comparator<String> TEXT_ORDER =
            ExternalOrderKey::compareTextCodePoints;
    static final Comparator<DocumentId> DOCUMENT_ORDER =
            Comparator.comparing(DocumentId::value, TEXT_ORDER);
    static final Comparator<EmbeddingBinding> WITHIN_PARENT_ORDER = Comparator
            .comparing(EmbeddingBinding::absolutePath, TEXT_ORDER)
            .thenComparing(b -> b.childDocumentId().value(), TEXT_ORDER)
            .thenComparingLong(EmbeddingBinding::activationGeneration);
    static final Comparator<EmbeddingBinding> GLOBAL_ORDER = Comparator
            .comparing((EmbeddingBinding b) -> b.parentDocumentId().value(),
                    TEXT_ORDER)
            .thenComparing(WITHIN_PARENT_ORDER);
    EmbeddingBinding {
        bindingId = requireText(bindingId, "bindingId");
        parentDocumentId = Objects.requireNonNull(
                parentDocumentId, "parentDocumentId");
        absolutePath = requirePath(absolutePath);
        childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        if (activationGeneration < 1L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
        activationMode = Objects.requireNonNull(
                activationMode, "activationMode");
        if ((activationMode == ActivationMode.IMPORT_FROM_FRONTIER
                || activationMode == ActivationMode.ATTACH_CURRENT_STATE)
                && establishedFrontier == null) {
            throw new IllegalArgumentException(
                    activationMode + " requires establishedFrontier");
        }
        if (activationMode != ActivationMode.IMPORT_FROM_FRONTIER
                && activationMode != ActivationMode.ATTACH_CURRENT_STATE
                && establishedFrontier != null) {
            throw new IllegalArgumentException(
                    "This activation mode cannot carry establishedFrontier");
        }
        admittedChildBlueId = requireText(
                admittedChildBlueId, "admittedChildBlueId");
        if (admittedChildEpoch != null && admittedChildEpoch < 0L) {
            throw new IllegalArgumentException(
                    "admittedChildEpoch must be non-negative");
        }
        admissionProofIdentity = requireText(
                admissionProofIdentity, "admissionProofIdentity");
        attachmentEntryBlueId = requireText(
                attachmentEntryBlueId, "attachmentEntryBlueId");
        attachmentOrder = Objects.requireNonNull(
                attachmentOrder, "attachmentOrder");
    }

    long attachmentTimestampMicros() {
        if (!attachmentOrder.components().isEmpty()
                && attachmentOrder.components().get(0)
                instanceof BigInteger timestamp) {
            try {
                return Math.max(1L, timestamp.longValueExact());
            } catch (ArithmeticException ignored) {
                return 1L;
            }
        }
        return 1L;
    }

    private static String requirePath(String value) {
        String checked = requireText(value, "absolutePath");
        if (!checked.startsWith("/")) {
            throw new IllegalArgumentException(
                    "absolutePath must be a JSON Pointer");
        }
        return checked;
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
