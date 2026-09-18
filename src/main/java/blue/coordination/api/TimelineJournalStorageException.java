package blue.coordination.api;

/** Physical journal evidence is unavailable, corrupt, or no longer current. */
public final class TimelineJournalStorageException
        extends blue.language.processor.NoncommittingExecutionException {
    private static final long serialVersionUID = 1L;

    public TimelineJournalStorageException(String message) { super(message); }
    public TimelineJournalStorageException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
