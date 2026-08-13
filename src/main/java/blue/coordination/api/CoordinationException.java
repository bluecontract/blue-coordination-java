package blue.coordination.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Typed Coordination failure that preserves its semantic cause. */
public final class CoordinationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final CoordinationErrorCode code;
    private final Map<String, String> details;

    /** Creates a typed failure without a nested cause or detail fields. */
    public CoordinationException(
            CoordinationErrorCode code,
            String message) {
        this(code, message, null, Map.of());
    }

    /** Creates a typed failure while preserving cause and immutable details. */
    public CoordinationException(
            CoordinationErrorCode code,
            String message,
            Throwable cause,
            Map<String, String> details) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(details, "details")));
    }

    /** Returns the stable machine-readable failure category. */
    public CoordinationErrorCode code() {
        return code;
    }

    /** Returns immutable diagnostic fields suitable for structured logging. */
    public Map<String, String> details() {
        return details;
    }
}
