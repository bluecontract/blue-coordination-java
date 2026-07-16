package blue.coordination.processor.workflow;

final class ComputeResultValidationException extends RuntimeException {
    ComputeResultValidationException(String message) {
        super(message);
    }

    ComputeResultValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
