package blue.coordination.engine.api;

import java.util.Objects;

/**
 * Immutable proof that one request-local processing bundle was loaded for one
 * exact engine plan generation.
 */
public final class ProcessingBundlePlanBinding {

    private final DocumentSessionId sessionId;
    private final long epoch;
    private final String rootBlueId;
    private final String eventBlueId;
    private final String planIdentity;
    private final String subscriptionDigest;
    private final String environmentIdentity;

    public ProcessingBundlePlanBinding(
            DocumentSessionId sessionId,
            long epoch,
            String rootBlueId,
            String eventBlueId,
            String planIdentity,
            String subscriptionDigest,
            String environmentIdentity) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        this.epoch = epoch;
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.planIdentity = requireText(planIdentity, "planIdentity");
        this.subscriptionDigest = requireText(
                subscriptionDigest, "subscriptionDigest");
        this.environmentIdentity = requireText(
                environmentIdentity, "environmentIdentity");
    }

    public DocumentSessionId sessionId() { return sessionId; }
    public long epoch() { return epoch; }
    public String rootBlueId() { return rootBlueId; }
    public String eventBlueId() { return eventBlueId; }
    public String planIdentity() { return planIdentity; }
    public String subscriptionDigest() { return subscriptionDigest; }
    public String environmentIdentity() { return environmentIdentity; }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
