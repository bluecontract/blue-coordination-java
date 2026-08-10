package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;

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
