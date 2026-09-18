package blue.coordination.api.storage;

/** A physical object is missing, corrupt, unavailable, or exceeds a host bound. */
public final class CoordinationObjectStorageException
        extends blue.language.processor.NoncommittingExecutionException {
    public CoordinationObjectStorageException(String message) { super(message); }
    public CoordinationObjectStorageException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
