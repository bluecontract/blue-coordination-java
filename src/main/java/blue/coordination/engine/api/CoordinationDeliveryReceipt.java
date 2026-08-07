package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable idempotency receipt for one event delivered to one Root session. */
public final class CoordinationDeliveryReceipt {

    private final String eventBlueId;
    private final DocumentSessionId sessionId;
    private final CoordinationDeliveryStatus status;
    private final int attemptCount;
    private final long plannedEpoch;
    private final String plannedRootBlueId;
    private final String plannedSubscriptionSnapshotIdentity;
    private final List<String> orderedOccurrenceKeys;
    private final Long resultingEpoch;
    private final String resultingRootBlueId;
    private final String transitionIdentity;
    private final List<String> committedOutboxEventBlueIds;
    private final String failureClass;

    public CoordinationDeliveryReceipt(
            String eventBlueId,
            DocumentSessionId sessionId,
            CoordinationDeliveryStatus status,
            int attemptCount,
            long plannedEpoch,
            String plannedRootBlueId,
            String plannedSubscriptionSnapshotIdentity,
            List<String> orderedOccurrenceKeys,
            Long resultingEpoch,
            String resultingRootBlueId,
            String transitionIdentity,
            List<String> committedOutboxEventBlueIds,
            String failureClass) {
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.status = Objects.requireNonNull(status, "status");
        if (attemptCount < 0 || plannedEpoch < 0L) {
            throw new IllegalArgumentException(
                    "Attempt count and planned epoch must be non-negative");
        }
        this.attemptCount = attemptCount;
        this.plannedEpoch = plannedEpoch;
        this.plannedRootBlueId = requireText(
                plannedRootBlueId, "plannedRootBlueId");
        this.plannedSubscriptionSnapshotIdentity = requireText(
                plannedSubscriptionSnapshotIdentity,
                "plannedSubscriptionSnapshotIdentity");
        this.orderedOccurrenceKeys = immutableNonEmptyText(
                orderedOccurrenceKeys, "orderedOccurrenceKeys");
        this.resultingEpoch = resultingEpoch;
        this.resultingRootBlueId = emptyToNull(resultingRootBlueId);
        this.transitionIdentity = emptyToNull(transitionIdentity);
        this.committedOutboxEventBlueIds = immutableText(
                committedOutboxEventBlueIds,
                "committedOutboxEventBlueIds");
        this.failureClass = emptyToNull(failureClass);
        validateState();
    }

    public String eventBlueId() { return eventBlueId; }
    public DocumentSessionId sessionId() { return sessionId; }
    public CoordinationDeliveryStatus status() { return status; }
    public int attemptCount() { return attemptCount; }
    public long plannedEpoch() { return plannedEpoch; }
    public String plannedRootBlueId() { return plannedRootBlueId; }
    public String plannedSubscriptionSnapshotIdentity() {
        return plannedSubscriptionSnapshotIdentity;
    }
    public List<String> orderedOccurrenceKeys() {
        return orderedOccurrenceKeys;
    }
    public Optional<Long> resultingEpoch() {
        return Optional.ofNullable(resultingEpoch);
    }
    public Optional<String> resultingRootBlueId() {
        return Optional.ofNullable(resultingRootBlueId);
    }
    public Optional<String> transitionIdentity() {
        return Optional.ofNullable(transitionIdentity);
    }
    public List<String> committedOutboxEventBlueIds() {
        return committedOutboxEventBlueIds;
    }
    public Optional<String> failureClass() {
        return Optional.ofNullable(failureClass);
    }
    /** Compatibility diagnostic accessor; failures persist class only. */
    public Optional<String> failure() { return failureClass(); }
    public boolean committed() {
        return status == CoordinationDeliveryStatus.COMMITTED;
    }
    public boolean succeeded() { return committed(); }

    private void validateState() {
        boolean hasResult = resultingEpoch != null
                || resultingRootBlueId != null
                || transitionIdentity != null
                || !committedOutboxEventBlueIds.isEmpty();
        switch (status) {
            case PENDING:
                if (attemptCount != 0 || hasResult || failureClass != null) {
                    throw invalidState();
                }
                break;
            case IN_FLIGHT:
                if (attemptCount == 0 || hasResult || failureClass != null) {
                    throw invalidState();
                }
                break;
            case FAILED:
                if (attemptCount == 0 || hasResult || failureClass == null) {
                    throw invalidState();
                }
                break;
            case COMMITTED:
                if (attemptCount == 0
                        || resultingEpoch == null
                        || resultingEpoch.longValue() < plannedEpoch
                        || resultingRootBlueId == null
                        || transitionIdentity == null
                        || failureClass != null) {
                    throw invalidState();
                }
                break;
            default:
                throw new IllegalStateException("Unknown delivery status");
        }
    }

    private IllegalArgumentException invalidState() {
        return new IllegalArgumentException(
                "Receipt fields are inconsistent with status " + status);
    }

    private static List<String> immutableNonEmptyText(
            List<String> source,
            String label) {
        List<String> result = immutableText(source, label);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return result;
    }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        List<String> copy = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : copy) {
            requireText(value, label + " entry");
        }
        return Collections.unmodifiableList(copy);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
