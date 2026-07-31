package blue.coordination.processor.mandate;

import blue.language.model.Node;

/**
 * Caller-supplied result of deterministic Mandate validation.
 *
 * <p>Coordination does not execute the validation function here because the
 * feeder/provider boundary owns that work and the hosted semantic-gas boundary
 * is not exposed by the current generic processor API. Passed and rejected
 * evidence is bound to the exact function and candidate request identities so
 * it cannot be reused for another decision.</p>
 */
public final class MandateValidationEvidence {
    enum Outcome {
        PASSED,
        REJECTED,
        UNAVAILABLE
    }

    private final Outcome outcome;
    private final String functionBlueId;
    private final String requestBlueId;
    private final String reason;

    private MandateValidationEvidence(
            Outcome outcome,
            String functionBlueId,
            String requestBlueId,
            String reason) {
        this.outcome = outcome;
        this.functionBlueId = functionBlueId;
        this.requestBlueId = requestBlueId;
        this.reason = reason;
    }

    public static MandateValidationEvidence passed(
            Node exactFunction,
            Node exactRequest) {
        return bound(
                Outcome.PASSED,
                exactFunction,
                exactRequest,
                "mandate-validation-function-passed");
    }

    public static MandateValidationEvidence rejected(
            Node exactFunction,
            Node exactRequest,
            String reason) {
        return bound(
                Outcome.REJECTED,
                exactFunction,
                exactRequest,
                MandateEligibilityNodes.nonBlank(
                        reason,
                        "mandate-validation-function-rejected"));
    }

    public static MandateValidationEvidence unavailable(String reason) {
        return new MandateValidationEvidence(
                Outcome.UNAVAILABLE,
                null,
                null,
                MandateEligibilityNodes.nonBlank(
                        reason,
                        "mandate-validation-evidence-unavailable"));
    }

    private static MandateValidationEvidence bound(
            Outcome outcome,
            Node exactFunction,
            Node exactRequest,
            String reason) {
        return new MandateValidationEvidence(
                outcome,
                MandateEligibilityNodes.exactBlueId(
                        exactFunction, "validation function"),
                MandateEligibilityNodes.exactBlueId(
                        exactRequest, "validation request"),
                reason);
    }

    Outcome outcome() {
        return outcome;
    }

    String reason() {
        return reason;
    }

    boolean isBoundTo(Node exactFunction, Node exactRequest) {
        return functionBlueId.equals(
                MandateEligibilityNodes.exactBlueId(
                        exactFunction, "validation function"))
                && requestBlueId.equals(
                MandateEligibilityNodes.exactBlueId(
                        exactRequest, "validation request"));
    }
}
