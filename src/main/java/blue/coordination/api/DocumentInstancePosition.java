package blue.coordination.api;

import java.util.Objects;

/**
 * Exact retained publication position within one execution instance. Construction
 * alone grants no authority: the library authenticates its persisted association.
 * Invocation and epoch retain their existing canonical processor meanings.
 */
public record DocumentInstancePosition(DocumentInstanceRef instance, long epoch, String invocationIdentity) {
    /** Rejects incomplete or negative history positions. */
    public DocumentInstancePosition {
        Objects.requireNonNull(instance, "instance"); Objects.requireNonNull(invocationIdentity, "invocationIdentity");
        if (epoch < 0 || invocationIdentity.isBlank()) throw new IllegalArgumentException("Invalid instance history position");
    }
}
