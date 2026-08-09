package blue.coordination.internal;

import blue.coordination.api.ExactValue;

import blue.coordination.api.DocumentId;

import blue.coordination.api.ActivationMode;

import java.util.Objects;

/** One active processable child occurrence discovered from the frozen catalog. */
record EmbeddedOccurrence(
        String scopePath,
        DocumentId childDocumentId,
        ExactValue suppliedState,
        ActivationMode activationMode) {
    public EmbeddedOccurrence {
        scopePath = requireText(scopePath, "scopePath");
        childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        suppliedState = Objects.requireNonNull(suppliedState, "suppliedState");
        activationMode = Objects.requireNonNull(activationMode, "activationMode");
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
