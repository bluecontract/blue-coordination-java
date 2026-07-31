package blue.coordination.processor.mandate;

/**
 * Deterministic feeder/provider decision. Suspension means that exact evidence
 * is unavailable and must never be interpreted as semantic ineligibility.
 */
public final class MandateEligibilityDecision {
    public enum Outcome {
        ELIGIBLE,
        INELIGIBLE,
        SUSPENDED
    }

    private final Outcome outcome;
    private final String reason;
    private final String selectedMandateBlueId;

    private MandateEligibilityDecision(
            Outcome outcome,
            String reason,
            String selectedMandateBlueId) {
        this.outcome = outcome;
        this.reason = reason;
        this.selectedMandateBlueId = selectedMandateBlueId;
    }

    static MandateEligibilityDecision eligible(
            String reason,
            String selectedMandateBlueId) {
        return new MandateEligibilityDecision(
                Outcome.ELIGIBLE, reason, selectedMandateBlueId);
    }

    static MandateEligibilityDecision ineligible(String reason) {
        return new MandateEligibilityDecision(
                Outcome.INELIGIBLE, reason, null);
    }

    static MandateEligibilityDecision suspended(String reason) {
        return new MandateEligibilityDecision(
                Outcome.SUSPENDED, reason, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    public String reason() {
        return reason;
    }

    public String selectedMandateBlueId() {
        return selectedMandateBlueId;
    }

    public boolean isEligible() {
        return outcome == Outcome.ELIGIBLE;
    }

    public boolean isIneligible() {
        return outcome == Outcome.INELIGIBLE;
    }

    public boolean isSuspended() {
        return outcome == Outcome.SUSPENDED;
    }
}
