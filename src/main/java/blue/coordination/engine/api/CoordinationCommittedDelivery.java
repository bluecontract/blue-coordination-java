package blue.coordination.engine.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative evidence committed with one Root-session transition.
 *
 * <p>This value deliberately contains only facts owned by the session-store
 * transaction. The dispatch ledger adds the frozen occurrence selection and
 * attempt state that it owns.</p>
 */
public final class CoordinationCommittedDelivery {

    private final String eventBlueId;
    private final DocumentSessionId sessionId;
    private final long plannedEpoch;
    private final String plannedRootBlueId;
    private final long resultingEpoch;
    private final String resultingRootBlueId;
    private final String transitionIdentity;
    private final List<String> rootOutboxEventBlueIds;

    public CoordinationCommittedDelivery(
            String eventBlueId,
            DocumentSessionId sessionId,
            long plannedEpoch,
            String plannedRootBlueId,
            long resultingEpoch,
            String resultingRootBlueId,
            String transitionIdentity,
            List<String> rootOutboxEventBlueIds) {
        this.eventBlueId = requireText(eventBlueId, "eventBlueId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (plannedEpoch < 0L || resultingEpoch < plannedEpoch) {
            throw new IllegalArgumentException("Invalid delivery epochs");
        }
        this.plannedEpoch = plannedEpoch;
        this.plannedRootBlueId = requireText(
                plannedRootBlueId, "plannedRootBlueId");
        this.resultingEpoch = resultingEpoch;
        this.resultingRootBlueId = requireText(
                resultingRootBlueId, "resultingRootBlueId");
        this.transitionIdentity = requireText(
                transitionIdentity, "transitionIdentity");
        this.rootOutboxEventBlueIds = immutableText(
                rootOutboxEventBlueIds, "rootOutboxEventBlueIds");
    }

    public String eventBlueId() { return eventBlueId; }
    public DocumentSessionId sessionId() { return sessionId; }
    public long plannedEpoch() { return plannedEpoch; }
    public String plannedRootBlueId() { return plannedRootBlueId; }
    public long resultingEpoch() { return resultingEpoch; }
    public String resultingRootBlueId() { return resultingRootBlueId; }
    public String transitionIdentity() { return transitionIdentity; }
    public List<String> rootOutboxEventBlueIds() {
        return rootOutboxEventBlueIds;
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
}
