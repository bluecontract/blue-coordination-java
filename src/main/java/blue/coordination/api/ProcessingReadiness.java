package blue.coordination.api;

/** A fresh scheduling observation, separate from the evidence of any completed stage. */
public record ProcessingReadiness(boolean quiescent, boolean paused) {
    /** Quiescence and remaining scheduling heads are mutually exclusive. */
    public ProcessingReadiness {
        if (quiescent && paused) throw new IllegalArgumentException("Quiescent scheduling cannot have pending heads");
    }
    /** No selectable head, but at least one root has an unresolved prerequisite. */
    public boolean blocked() { return !quiescent && !paused; }
}
