package blue.coordination.engine.api;

import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Complete ordered occurrence candidates for one affected Root session. */
public final class IndexedSessionCandidates
        implements Comparable<IndexedSessionCandidates> {

    private final DocumentSessionId sessionId;
    private final List<String> orderedOccurrenceKeys;
    private final int totalScopeDepth;
    private final long plannedEpoch;
    private final String plannedRootBlueId;
    private final String subscriptionSnapshotIdentity;

    public IndexedSessionCandidates(
            DocumentSessionId sessionId,
            List<String> orderedOccurrenceKeys,
            int totalScopeDepth,
            long plannedEpoch,
            String plannedRootBlueId,
            String subscriptionSnapshotIdentity) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        List<String> copied = new ArrayList<String>(
                Objects.requireNonNull(
                        orderedOccurrenceKeys, "orderedOccurrenceKeys"));
        if (copied.isEmpty()) {
            throw new IllegalArgumentException(
                    "orderedOccurrenceKeys must not be empty");
        }
        for (String key : copied) {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException(
                        "Occurrence keys must be non-empty");
            }
        }
        this.orderedOccurrenceKeys = Collections.unmodifiableList(copied);
        if (totalScopeDepth < 0) {
            throw new IllegalArgumentException(
                    "totalScopeDepth must be non-negative");
        }
        this.totalScopeDepth = totalScopeDepth;
        if (plannedEpoch < 0L) {
            throw new IllegalArgumentException(
                    "plannedEpoch must be non-negative");
        }
        this.plannedEpoch = plannedEpoch;
        this.plannedRootBlueId = requireText(
                plannedRootBlueId, "plannedRootBlueId");
        this.subscriptionSnapshotIdentity = requireText(
                subscriptionSnapshotIdentity,
                "subscriptionSnapshotIdentity");
    }

    public DocumentSessionId sessionId() {
        return sessionId;
    }

    public List<String> orderedOccurrenceKeys() {
        return orderedOccurrenceKeys;
    }

    /** Sum of matching occurrence path depths, retained by the route index. */
    public int totalScopeDepth() {
        return totalScopeDepth;
    }

    public long plannedEpoch() { return plannedEpoch; }
    public String plannedRootBlueId() { return plannedRootBlueId; }
    public String subscriptionSnapshotIdentity() {
        return subscriptionSnapshotIdentity;
    }

    @Override
    public int compareTo(IndexedSessionCandidates other) {
        return ExternalOrderKey.compareTextCodePoints(
                sessionId.value(),
                Objects.requireNonNull(other, "other").sessionId.value());
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }
}
