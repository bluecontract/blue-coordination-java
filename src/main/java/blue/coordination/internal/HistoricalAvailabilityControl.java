package blue.coordination.internal;

import java.util.Optional;

/** Deterministic fail-closed availability seam for in-memory tests. */
final class HistoricalAvailabilityControl {
    private HistoricalStep blockedStep;

    synchronized void makeAvailable() {
        blockedStep = null;
    }

    synchronized void makeUnavailable(String diagnostic) {
        blockedStep = new HistoricalStep.Unavailable(diagnostic);
    }

    synchronized void invalidateEvidence(String diagnostic) {
        blockedStep = new HistoricalStep.InvalidEvidence(diagnostic);
    }

    synchronized Optional<HistoricalStep> blockedStep() {
        return Optional.ofNullable(blockedStep);
    }
}
