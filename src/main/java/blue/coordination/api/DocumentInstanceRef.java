package blue.coordination.api;

import java.util.Objects;

/**
 * One non-reusable execution of a semantic document inside a Coordination address.
 * This identity is authority metadata, never authored content or a Blue identity.
 */
public record DocumentInstanceRef(DocumentId documentId, String instanceId) {
    /** Validates the semantic identity and nonempty, well-formed instance identity. */
    public DocumentInstanceRef {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(instanceId, "instanceId");
        if (instanceId.isBlank()) throw new IllegalArgumentException("Blank execution instance identity");
        for (int i = 0; i < instanceId.length(); i++) {
            char value = instanceId.charAt(i);
            if (Character.isHighSurrogate(value)) {
                if (++i == instanceId.length() || !Character.isLowSurrogate(instanceId.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode execution instance identity");
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("Invalid Unicode execution instance identity");
            }
        }
    }
}
