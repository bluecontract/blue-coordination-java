package blue.coordination.engine.api;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable historical receipt for one committed document epoch. */
public final class DocumentEpochSnapshot {

    private final DocumentSessionId sessionId;
    private final long epoch;
    private final String rootBlueId;
    private final String priorRootBlueId;
    private final String causedByEventBlueId;
    private final ExternalOrderKey eventOrderKey;
    private final String fragmentInventoryIdentity;
    private final String subscriptionSnapshotIdentity;
    private final List<String> rootEventBlueIds;
    private final long totalGas;
    private final String transitionIdentity;

    public DocumentEpochSnapshot(
            DocumentSessionId sessionId,
            long epoch,
            String rootBlueId,
            String priorRootBlueId,
            String causedByEventBlueId,
            ExternalOrderKey eventOrderKey,
            String fragmentInventoryIdentity,
            String subscriptionSnapshotIdentity,
            List<String> rootEventBlueIds,
            long totalGas,
            String transitionIdentity) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (epoch < 0L || totalGas < 0L) {
            throw new IllegalArgumentException(
                    "epoch and totalGas must be non-negative");
        }
        this.epoch = epoch;
        this.rootBlueId = requireText(rootBlueId, "rootBlueId");
        this.priorRootBlueId = priorRootBlueId;
        this.causedByEventBlueId = causedByEventBlueId;
        this.eventOrderKey = eventOrderKey;
        this.fragmentInventoryIdentity = requireText(
                fragmentInventoryIdentity,
                "fragmentInventoryIdentity");
        this.subscriptionSnapshotIdentity = requireText(
                subscriptionSnapshotIdentity,
                "subscriptionSnapshotIdentity");
        this.rootEventBlueIds = immutableText(
                rootEventBlueIds, "rootEventBlueIds");
        this.totalGas = totalGas;
        this.transitionIdentity = requireText(
                transitionIdentity, "transitionIdentity");
        if (epoch == 0L
                && (priorRootBlueId != null
                || causedByEventBlueId != null
                || eventOrderKey != null)) {
            throw new IllegalArgumentException(
                    "Epoch zero cannot have a prior Root or causing event");
        }
        if (epoch > 0L
                && (priorRootBlueId == null
                || causedByEventBlueId == null
                || eventOrderKey == null)) {
            throw new IllegalArgumentException(
                    "A transition epoch requires prior Root and event evidence");
        }
    }

    public DocumentSessionId sessionId() { return sessionId; }
    public long epoch() { return epoch; }
    public String rootBlueId() { return rootBlueId; }
    public String priorRootBlueId() { return priorRootBlueId; }
    public String causedByEventBlueId() { return causedByEventBlueId; }
    public ExternalOrderKey eventOrderKey() { return eventOrderKey; }
    public String fragmentInventoryIdentity() {
        return fragmentInventoryIdentity;
    }
    public String subscriptionSnapshotIdentity() {
        return subscriptionSnapshotIdentity;
    }
    public List<String> rootEventBlueIds() { return rootEventBlueIds; }
    public long totalGas() { return totalGas; }
    public String transitionIdentity() { return transitionIdentity; }

    private static List<String> immutableText(
            List<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) {
            requireText(value, label + " entry");
        }
        return Collections.unmodifiableList(result);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }
}
