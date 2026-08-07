package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Immutable exact document admission request. */
public final class DocumentRegistration {

    private final DocumentSessionId sessionId;
    private final Node exactDocument;
    private final ExternalOrderKey activationFrontier;
    private final RegistrationMode mode;
    private final Long claimedEpoch;

    public DocumentRegistration(
            DocumentSessionId sessionId,
            Node exactDocument,
            ExternalOrderKey activationFrontier,
            RegistrationMode mode,
            Long claimedEpoch) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.exactDocument = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        this.activationFrontier = Objects.requireNonNull(
                activationFrontier, "activationFrontier");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (claimedEpoch != null && claimedEpoch.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "claimedEpoch must be non-negative");
        }
        this.claimedEpoch = claimedEpoch;
    }

    /** Creates the normal open-or-create registration. */
    public static DocumentRegistration openOrCreate(
            DocumentSessionId sessionId,
            Node exactDocument,
            ExternalOrderKey activationFrontier) {
        return new DocumentRegistration(
                sessionId,
                exactDocument,
                activationFrontier,
                RegistrationMode.OPEN_OR_CREATE,
                null);
    }

    public DocumentSessionId sessionId() {
        return sessionId;
    }

    public Node exactDocument() {
        return exactDocument.clone();
    }

    public ExternalOrderKey activationFrontier() {
        return activationFrontier;
    }

    public RegistrationMode mode() {
        return mode;
    }

    public Long claimedEpoch() {
        return claimedEpoch;
    }
}
