package blue.coordination.engine.memory;

import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Canonical one-record-per-event store for the in-memory reference host. */
public final class InMemoryStoredCoordinationEventStore {

    private final Map<String, StoredCoordinationEvent> byBlueId =
            new LinkedHashMap<String, StoredCoordinationEvent>();

    public synchronized StoredCoordinationEvent putCanonical(
            StoredCoordinationEvent event) {
        PreparedCanonicalPut prepared = prepareCanonical(event);
        publishPreparedCanonicalUnchecked(prepared);
        return prepared.result;
    }

    synchronized PreparedCanonicalPut prepareCanonical(
            StoredCoordinationEvent event) {
        StoredCoordinationEvent checked = Objects.requireNonNull(
                event, "event");
        StoredCoordinationEvent existing = byBlueId.get(
                checked.eventBlueId());
        if (existing == null) {
            return new PreparedCanonicalPut(
                    this, checked, checked);
        }
        if (!existing.fragmentInventoryIdentity().equals(
                checked.fragmentInventoryIdentity())
                || !existing.orderKey().equals(checked.orderKey())) {
            throw new IllegalStateException(
                    "Conflicting stored event " + checked.eventBlueId());
        }
        return new PreparedCanonicalPut(
                this, checked, existing);
    }

    synchronized void validatePreparedCanonical(
            PreparedCanonicalPut prepared) {
        PreparedCanonicalPut checked = Objects.requireNonNull(
                prepared, "prepared");
        if (checked.owner != this) {
            throw new IllegalArgumentException(
                    "Prepared event publication belongs to another store");
        }
        StoredCoordinationEvent current = byBlueId.get(
                checked.proposed.eventBlueId());
        if (current != null
                && (!current.fragmentInventoryIdentity().equals(
                        checked.proposed.fragmentInventoryIdentity())
                || !current.orderKey().equals(
                        checked.proposed.orderKey()))) {
            throw new IllegalStateException(
                    "Prepared stored event conflicts at publication: "
                            + checked.proposed.eventBlueId());
        }
    }

    synchronized void publishPreparedCanonicalUnchecked(
            PreparedCanonicalPut prepared) {
        if (!byBlueId.containsKey(prepared.proposed.eventBlueId())) {
            byBlueId.put(prepared.proposed.eventBlueId(), prepared.proposed);
        }
    }

    public synchronized Optional<StoredCoordinationEvent> find(
            String eventBlueId) {
        return Optional.ofNullable(byBlueId.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId")));
    }

    public synchronized StoredCoordinationEvent require(String eventBlueId) {
        StoredCoordinationEvent result = byBlueId.get(
                Objects.requireNonNull(eventBlueId, "eventBlueId"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "Unknown stored event " + eventBlueId);
        }
        return result;
    }

    public synchronized int size() { return byBlueId.size(); }

    /** Returns an isolated map retaining only immutable event handles. */
    synchronized InMemoryStoredCoordinationEventStore copy() {
        InMemoryStoredCoordinationEventStore result =
                new InMemoryStoredCoordinationEventStore();
        result.byBlueId.putAll(byBlueId);
        return result;
    }

    synchronized String stateFingerprint() {
        List<StoredCoordinationEvent> ordered =
                new ArrayList<StoredCoordinationEvent>(byBlueId.values());
        ordered.sort(Comparator.comparing(
                StoredCoordinationEvent::eventBlueId));
        StringBuilder canonical = new StringBuilder();
        for (StoredCoordinationEvent value : ordered) {
            canonical.append(value.eventBlueId()).append('\u0000')
                    .append(value.fragmentInventoryIdentity())
                    .append('\u0000')
                    .append(value.orderKey()).append('\n');
        }
        return InMemoryCheckpointFingerprint.sha256(canonical.toString());
    }

    static final class PreparedCanonicalPut {
        private final InMemoryStoredCoordinationEventStore owner;
        private final StoredCoordinationEvent proposed;
        private final StoredCoordinationEvent result;

        private PreparedCanonicalPut(
                InMemoryStoredCoordinationEventStore owner,
                StoredCoordinationEvent proposed,
                StoredCoordinationEvent result) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.proposed = Objects.requireNonNull(proposed, "proposed");
            this.result = Objects.requireNonNull(result, "result");
        }

        StoredCoordinationEvent result() { return result; }
    }
}
