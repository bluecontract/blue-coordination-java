package blue.coordination.basic.engine;

import java.util.Objects;

/** One active processable child occurrence discovered from the frozen catalog. */
public record EmbeddedOccurrence(
        String scopePath,
        DocumentId childDocumentId,
        ExactNodeValue suppliedState,
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
