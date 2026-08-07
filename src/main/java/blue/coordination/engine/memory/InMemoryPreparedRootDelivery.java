package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.IndexedSessionCandidates;
import blue.coordination.engine.api.StoredCoordinationEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact-epoch output of mutation-free planning and PROCESS for one Root.
 * Successful ordered publication attaches its single lifecycle evidence once.
 */
public final class InMemoryPreparedRootDelivery {

    private final StoredCoordinationEvent event;
    private final IndexedSessionCandidates target;
    private final CoordinationTransition transition;
    private DemoTransition committedTransition;

    public InMemoryPreparedRootDelivery(
            StoredCoordinationEvent event,
            IndexedSessionCandidates target,
            CoordinationTransition transition) {
        this.event = Objects.requireNonNull(event, "event");
        this.target = Objects.requireNonNull(target, "target");
        this.transition = Objects.requireNonNull(transition, "transition");
        if (!target.sessionId().equals(
                transition.plan().session().sessionId())
                || target.plannedEpoch() != transition.beforeEpoch()
                || !target.plannedRootBlueId().equals(
                        transition.beforeRootBlueId())) {
            throw new IllegalArgumentException(
                    "Prepared transition does not bind to frozen target");
        }
    }

    public StoredCoordinationEvent event() {
        return event;
    }

    public IndexedSessionCandidates target() {
        return target;
    }

    public CoordinationTransition transition() {
        return transition;
    }

    /** Successful ordered publication evidence, absent before commit. */
    public synchronized Optional<DemoTransition> committedTransition() {
        return Optional.ofNullable(committedTransition);
    }

    synchronized void recordCommittedTransition(DemoTransition value) {
        DemoTransition checked = Objects.requireNonNull(
                value, "committedTransition");
        if (checked.transition() != transition) {
            throw new IllegalArgumentException(
                    "Committed evidence belongs to another transition");
        }
        if (committedTransition != null) {
            throw new IllegalStateException(
                    "Prepared delivery already has committed evidence");
        }
        committedTransition = checked;
    }
}
