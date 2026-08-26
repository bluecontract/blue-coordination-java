package blue.coordination.api;

import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Typed Coordination failure that preserves its semantic cause. */
public final class CoordinationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final CoordinationErrorCode code;
    private final Map<String, String> details;
    private final ProcessorStatus processorStatus;
    private final ProcessorErrorCategory processorCategory;
    private final String processorMessage;
    private final Map<String, String> processorDetails;

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
        this(code, message, cause, details, null, null);
    }

    /**
     * Creates a typed failure while retaining an exact frozen-processor
     * diagnostic.
     *
     * <p>The processor fields are snapshots. Diagnostic details remain
     * separate from Coordination details so equal keys cannot overwrite one
     * another.</p>
     */
    public CoordinationException(
            CoordinationErrorCode code,
            String message,
            Throwable cause,
            Map<String, String> details,
            ProcessorStatus processorStatus,
            ProcessorDiagnostic processorDiagnostic) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(details, "details")));
        this.processorStatus = processorStatus;
        this.processorCategory = processorDiagnostic == null
                ? null
                : processorDiagnostic.category();
        this.processorMessage = processorDiagnostic == null
                ? null
                : processorDiagnostic.message();
        this.processorDetails = processorDiagnostic == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(
                        processorDiagnostic.details()));
    }

    /** Returns the stable machine-readable failure category. */
    public CoordinationErrorCode code() {
        return code;
    }

    /** Returns immutable diagnostic fields suitable for structured logging. */
    public Map<String, String> details() {
        return details;
    }

    /** Returns the frozen processor status when this failure came from it. */
    public Optional<ProcessorStatus> processorStatus() {
        return Optional.ofNullable(processorStatus);
    }

    /** Returns the frozen processor category when one was reported. */
    public Optional<ProcessorErrorCategory> processorCategory() {
        return Optional.ofNullable(processorCategory);
    }

    /** Returns the exact frozen processor message when one was reported. */
    public Optional<String> processorMessage() {
        return Optional.ofNullable(processorMessage);
    }

    /** Returns immutable frozen processor diagnostic details. */
    public Map<String, String> processorDetails() {
        return processorDetails == null ? Map.of() : processorDetails;
    }
}
