package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.language.processor.ExternalOrderKey;

import java.util.Objects;

/** Immutable authoritative current state of one managed document session. */
public final class ManagedDocumentSnapshot {

    private final DocumentSessionId sessionId;
    private final String initialDocumentBlueId;
    private final String currentRootBlueId;
    private final long currentEpoch;
    private final String environmentIdentity;
    private final ExternalOrderKey committedFrontier;
    private final String fragmentInventoryIdentity;
    private final CoordinationSubscriptionSnapshot subscriptions;
    private final ManagedDocumentStatus status;

    public ManagedDocumentSnapshot(
            DocumentSessionId sessionId,
            String initialDocumentBlueId,
            String currentRootBlueId,
            long currentEpoch,
            String environmentIdentity,
            ExternalOrderKey committedFrontier,
            String fragmentInventoryIdentity,
            CoordinationSubscriptionSnapshot subscriptions,
            ManagedDocumentStatus status) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.initialDocumentBlueId = requireText(
                initialDocumentBlueId, "initialDocumentBlueId");
        this.currentRootBlueId = requireText(
                currentRootBlueId, "currentRootBlueId");
        if (currentEpoch < 0L) {
            throw new IllegalArgumentException(
                    "currentEpoch must be non-negative");
        }
        this.currentEpoch = currentEpoch;
        this.environmentIdentity = requireText(
                environmentIdentity, "environmentIdentity");
        this.committedFrontier = Objects.requireNonNull(
                committedFrontier, "committedFrontier");
        this.fragmentInventoryIdentity = requireText(
                fragmentInventoryIdentity,
                "fragmentInventoryIdentity");
        this.subscriptions = Objects.requireNonNull(
                subscriptions, "subscriptions");
        this.status = Objects.requireNonNull(status, "status");
    }

    public DocumentSessionId sessionId() {
        return sessionId;
    }

    public String initialDocumentBlueId() {
        return initialDocumentBlueId;
    }

    public String currentRootBlueId() {
        return currentRootBlueId;
    }

    public long currentEpoch() {
        return currentEpoch;
    }

    public String environmentIdentity() {
        return environmentIdentity;
    }

    public ExternalOrderKey committedFrontier() {
        return committedFrontier;
    }

    public String fragmentInventoryIdentity() {
        return fragmentInventoryIdentity;
    }

    public CoordinationSubscriptionSnapshot subscriptions() {
        return subscriptions;
    }

    public ManagedDocumentStatus status() {
        return status;
    }

    /** Returns a copy with only the lifecycle state changed. */
    public ManagedDocumentSnapshot withStatus(ManagedDocumentStatus value) {
        return new ManagedDocumentSnapshot(
                sessionId,
                initialDocumentBlueId,
                currentRootBlueId,
                currentEpoch,
                environmentIdentity,
                committedFrontier,
                fragmentInventoryIdentity,
                subscriptions,
                value);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
