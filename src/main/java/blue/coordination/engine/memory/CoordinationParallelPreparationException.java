package blue.coordination.engine.memory;

import blue.coordination.engine.api.DocumentSessionId;

import java.util.Objects;

/** Failure attributed to one canonical Root target during parallel prepare. */
public final class CoordinationParallelPreparationException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DocumentSessionId sessionId;

    public CoordinationParallelPreparationException(
            DocumentSessionId sessionId,
            Throwable cause) {
        super("Root preparation failed for "
                + Objects.requireNonNull(sessionId, "sessionId"),
                Objects.requireNonNull(cause, "cause"));
        this.sessionId = sessionId;
    }

    public DocumentSessionId sessionId() {
        return sessionId;
    }
}
