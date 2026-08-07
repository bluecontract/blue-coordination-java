package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable request to plan and optionally commit one already ordered event. */
public final class ProcessRequest {

    private final DocumentSessionId sessionId;
    private final Long expectedEpoch;
    private final Node event;
    private final ExternalOrderKey eventOrderKey;
    private final DeliveryPlanningMode planningMode;
    private final List<String> orderedIndexedOccurrenceKeys;
    private final PrefetchPolicy prefetchPolicy;
    private final boolean commit;

    public ProcessRequest(
            DocumentSessionId sessionId,
            Long expectedEpoch,
            Node event,
            ExternalOrderKey eventOrderKey,
            DeliveryPlanningMode planningMode,
            List<String> orderedIndexedOccurrenceKeys,
            PrefetchPolicy prefetchPolicy,
            boolean commit) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        if (expectedEpoch != null && expectedEpoch.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "expectedEpoch must be non-negative");
        }
        this.expectedEpoch = expectedEpoch;
        this.event = Objects.requireNonNull(event, "event").clone();
        this.eventOrderKey = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        this.planningMode = Objects.requireNonNull(
                planningMode, "planningMode");
        List<String> occurrences = new ArrayList<String>(
                Objects.requireNonNull(
                        orderedIndexedOccurrenceKeys,
                        "orderedIndexedOccurrenceKeys"));
        for (String occurrence : occurrences) {
            if (occurrence == null || occurrence.isEmpty()) {
                throw new IllegalArgumentException(
                        "Indexed occurrence keys must be non-empty");
            }
        }
        this.orderedIndexedOccurrenceKeys =
                Collections.unmodifiableList(occurrences);
        this.prefetchPolicy = Objects.requireNonNull(
                prefetchPolicy, "prefetchPolicy");
        this.commit = commit;
        if (planningMode == DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY
                && !occurrences.isEmpty()) {
            throw new IllegalArgumentException(
                    "Compatibility planning cannot accept indexed candidates");
        }
    }

    public DocumentSessionId sessionId() { return sessionId; }
    public Long expectedEpoch() { return expectedEpoch; }
    public Node event() { return event.clone(); }
    public ExternalOrderKey eventOrderKey() { return eventOrderKey; }
    public DeliveryPlanningMode planningMode() { return planningMode; }
    public List<String> orderedIndexedOccurrenceKeys() {
        return orderedIndexedOccurrenceKeys;
    }
    public PrefetchPolicy prefetchPolicy() { return prefetchPolicy; }
    public boolean commit() { return commit; }
}
