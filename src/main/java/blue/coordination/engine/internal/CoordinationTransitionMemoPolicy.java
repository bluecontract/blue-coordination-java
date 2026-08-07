package blue.coordination.engine.internal;

import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;

import java.util.Objects;

/** Conservative whole-transition memo admission policy. */
public final class CoordinationTransitionMemoPolicy {

    private CoordinationTransitionMemoPolicy() {
    }

    /**
     * Returns whether one completed PROCESS result is safe to memoize.
     *
     * <p>A capability failure may represent provider or runtime capability
     * unavailability. That condition can clear without changing the semantic
     * invocation key, so it must not poison a whole-transition memo. Other
     * current Contracts statuses are completed deterministic outcomes bound
     * by the exact transition key.</p>
     */
    public static boolean permits(DocumentProcessingResult result) {
        ProcessorStatus status = Objects.requireNonNull(
                Objects.requireNonNull(result, "result").status(),
                "result.status");
        return status != ProcessorStatus.CAPABILITY_FAILURE;
    }
}
