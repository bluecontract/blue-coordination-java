package blue.coordination.api;

/** The selected external history is not yet completely readable; discard this owner and retry from fresh evidence. */
public final class TimelineHistoryUnavailableException extends blue.language.processor.NoncommittingExecutionException {
    private static final long serialVersionUID = 1L;
    public TimelineHistoryUnavailableException(String message) { super(message); }
}
