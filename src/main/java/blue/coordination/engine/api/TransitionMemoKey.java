package blue.coordination.engine.api;

import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Exact safe key for optional whole-transition memoization. */
public final class TransitionMemoKey {

    private final DocumentSessionId sessionId;
    private final String rootBlueId;
    private final String eventBlueId;
    private final String executionEvidenceIdentity;
    private final String environmentIdentity;
    private final String gasScheduleIdentity;
    private final ExternalOrderKey expectedCommittedFrontier;

    public TransitionMemoKey(
            DocumentSessionId sessionId,
            String rootBlueId,
            String eventBlueId,
            String executionEvidenceIdentity,
            String environmentIdentity,
            String gasScheduleIdentity) {
        this(
                sessionId,
                rootBlueId,
                eventBlueId,
                executionEvidenceIdentity,
                environmentIdentity,
                gasScheduleIdentity,
                null);
    }

    /**
     * Creates a memo key bound to the complete authoritative session CAS
     * generation, including progress-only commits which retain the Root.
     */
    public TransitionMemoKey(
            DocumentSessionId sessionId,
            String rootBlueId,
            String eventBlueId,
            String executionEvidenceIdentity,
            String environmentIdentity,
            String gasScheduleIdentity,
            ExternalOrderKey expectedCommittedFrontier) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.executionEvidenceIdentity = requireText(
                executionEvidenceIdentity, "executionEvidenceIdentity");
        this.environmentIdentity = requireText(
                environmentIdentity, "environmentIdentity");
        this.gasScheduleIdentity = requireText(
                gasScheduleIdentity, "gasScheduleIdentity");
        this.expectedCommittedFrontier = expectedCommittedFrontier;
    }

    public DocumentSessionId sessionId() { return sessionId; }
    public String rootBlueId() { return rootBlueId; }
    public String eventBlueId() { return eventBlueId; }
    public String executionEvidenceIdentity() {
        return executionEvidenceIdentity;
    }
    public String environmentIdentity() { return environmentIdentity; }
    public String gasScheduleIdentity() { return gasScheduleIdentity; }
    public ExternalOrderKey expectedCommittedFrontier() {
        return expectedCommittedFrontier;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TransitionMemoKey)) return false;
        TransitionMemoKey that = (TransitionMemoKey) other;
        return sessionId.equals(that.sessionId)
                && rootBlueId.equals(that.rootBlueId)
                && eventBlueId.equals(that.eventBlueId)
                && executionEvidenceIdentity.equals(
                        that.executionEvidenceIdentity)
                && environmentIdentity.equals(that.environmentIdentity)
                && gasScheduleIdentity.equals(that.gasScheduleIdentity)
                && Objects.equals(
                        expectedCommittedFrontier,
                        that.expectedCommittedFrontier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sessionId,
                rootBlueId,
                eventBlueId,
                executionEvidenceIdentity,
                environmentIdentity,
                gasScheduleIdentity,
                expectedCommittedFrontier);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
