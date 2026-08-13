package blue.coordination.processor.workflow;

/**
 * Signals that a deterministic Compute result violates the hosted
 * Coordination result contract.
 */
final class ComputeResultValidationException extends RuntimeException {
    ComputeResultValidationException(String message) {
        super(message);
    }

    ComputeResultValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
