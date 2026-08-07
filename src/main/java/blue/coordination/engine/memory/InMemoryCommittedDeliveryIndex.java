package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationCommittedDelivery;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Authoritative event/session commit evidence owned by the session store. */
public final class InMemoryCommittedDeliveryIndex
        implements CoordinationCommittedDeliveryProbe {

    private final Map<Key, CoordinationCommittedDelivery> deliveries =
            new LinkedHashMap<Key, CoordinationCommittedDelivery>();

    synchronized void requireRecordable(
            CoordinationAtomicCommitPlan plan) {
        CoordinationCommittedDelivery candidate = from(plan);
        CoordinationCommittedDelivery prior = deliveries.get(new Key(
                candidate.eventBlueId(), candidate.sessionId()));
        if (prior != null && !same(prior, candidate)) {
            throw new IllegalStateException(
                    "Committed delivery evidence conflicts for "
                            + candidate.eventBlueId() + " -> "
                            + candidate.sessionId());
        }
    }

    synchronized CoordinationCommittedDelivery record(
            CoordinationAtomicCommitPlan plan) {
        CoordinationCommittedDelivery candidate = from(plan);
        Key key = new Key(candidate.eventBlueId(), candidate.sessionId());
        CoordinationCommittedDelivery prior = deliveries.get(key);
        if (prior != null) {
            if (!same(prior, candidate)) {
                throw new IllegalStateException(
                        "Committed delivery evidence conflicts for "
                                + candidate.eventBlueId() + " -> "
                                + candidate.sessionId());
            }
            return prior;
        }
        deliveries.put(key, candidate);
        return candidate;
    }

    @Override
    public synchronized Optional<CoordinationCommittedDelivery>
            committedDelivery(
                    StoredCoordinationEvent event,
                    DocumentSessionId sessionId) {
        Objects.requireNonNull(event, "event");
        return find(event.eventBlueId(), sessionId);
    }

    public synchronized Optional<CoordinationCommittedDelivery> find(
            String eventBlueId,
            DocumentSessionId sessionId) {
        return Optional.ofNullable(deliveries.get(new Key(
                requireText(eventBlueId, "eventBlueId"),
                Objects.requireNonNull(sessionId, "sessionId"))));
    }

    public synchronized CoordinationCommittedDelivery require(
            String eventBlueId,
            DocumentSessionId sessionId) {
        return find(eventBlueId, sessionId).orElseThrow(
                () -> new IllegalArgumentException(
                        "No committed delivery for "
                                + eventBlueId + " -> " + sessionId));
    }

    public synchronized int size() { return deliveries.size(); }

    /**
     * Returns an isolated mutable copy for one in-memory checkpoint fork.
     * Delivery values are immutable and may be shared safely.
     */
    synchronized InMemoryCommittedDeliveryIndex copy() {
        InMemoryCommittedDeliveryIndex result =
                new InMemoryCommittedDeliveryIndex();
        result.deliveries.putAll(deliveries);
        return result;
    }

    synchronized String stateFingerprint() {
        List<CoordinationCommittedDelivery> ordered =
                new ArrayList<CoordinationCommittedDelivery>(
                        deliveries.values());
        ordered.sort(Comparator
                .comparing(CoordinationCommittedDelivery::eventBlueId)
                .thenComparing(value -> value.sessionId().value()));
        StringBuilder canonical = new StringBuilder();
        for (CoordinationCommittedDelivery value : ordered) {
            canonical.append(value.eventBlueId()).append('\u0000')
                    .append(value.sessionId().value()).append('\u0000')
                    .append(value.plannedEpoch()).append('\u0000')
                    .append(value.plannedRootBlueId()).append('\u0000')
                    .append(value.resultingEpoch()).append('\u0000')
                    .append(value.resultingRootBlueId()).append('\u0000')
                    .append(value.transitionIdentity()).append('\u0000')
                    .append(value.rootOutboxEventBlueIds()).append('\n');
        }
        return InMemoryCheckpointFingerprint.sha256(canonical.toString());
    }

    private static CoordinationCommittedDelivery from(
            CoordinationAtomicCommitPlan plan) {
        CoordinationAtomicCommitPlan checked = Objects.requireNonNull(
                plan, "plan");
        return new CoordinationCommittedDelivery(
                checked.eventBlueId(),
                checked.sessionId(),
                checked.expectedEpoch(),
                checked.expectedRootBlueId(),
                checked.resultingEpoch(),
                checked.resultingRootBlueId(),
                checked.transitionIdentity(),
                checked.rootOutboxEventBlueIds());
    }

    private static boolean same(
            CoordinationCommittedDelivery left,
            CoordinationCommittedDelivery right) {
        return left.eventBlueId().equals(right.eventBlueId())
                && left.sessionId().equals(right.sessionId())
                && left.plannedEpoch() == right.plannedEpoch()
                && left.plannedRootBlueId().equals(
                        right.plannedRootBlueId())
                && left.resultingEpoch() == right.resultingEpoch()
                && left.resultingRootBlueId().equals(
                        right.resultingRootBlueId())
                && left.transitionIdentity().equals(
                        right.transitionIdentity())
                && left.rootOutboxEventBlueIds().equals(
                        right.rootOutboxEventBlueIds());
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return checked;
    }

    private static final class Key {
        private final String eventBlueId;
        private final DocumentSessionId sessionId;

        private Key(String eventBlueId, DocumentSessionId sessionId) {
            this.eventBlueId = eventBlueId;
            this.sessionId = sessionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return eventBlueId.equals(that.eventBlueId)
                    && sessionId.equals(that.sessionId);
        }

        @Override
        public int hashCode() {
            return 31 * eventBlueId.hashCode() + sessionId.hashCode();
        }
    }
}
