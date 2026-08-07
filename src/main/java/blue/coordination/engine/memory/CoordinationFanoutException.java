package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationDispatchSnapshot;
import blue.coordination.engine.api.DocumentSessionId;

import java.util.Objects;

/** Partial fan-out failure carrying the exact resumable ledger snapshot. */
@SuppressWarnings("serial")
public final class CoordinationFanoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final DocumentSessionId failedSessionId;
    private final CoordinationDispatchSnapshot dispatch;

    public CoordinationFanoutException(
            DocumentSessionId failedSessionId,
            CoordinationDispatchSnapshot dispatch,
            Throwable cause) {
        super("Fan-out failed at "
                + Objects.requireNonNull(failedSessionId, "failedSessionId")
                + "; retry the same event to resume", cause);
        this.failedSessionId = failedSessionId;
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    }

    public DocumentSessionId failedSessionId() { return failedSessionId; }
    public CoordinationDispatchSnapshot dispatch() { return dispatch; }
}
