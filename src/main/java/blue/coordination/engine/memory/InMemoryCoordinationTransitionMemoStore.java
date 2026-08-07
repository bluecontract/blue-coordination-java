package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.TransitionMemoKey;
import blue.coordination.engine.spi.CoordinationTransitionMemoStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe exact whole-transition memo store for demos and tests. */
public final class InMemoryCoordinationTransitionMemoStore
        implements CoordinationTransitionMemoStore {

    private final Map<TransitionMemoKey, CoordinationTransition> values =
            new LinkedHashMap<TransitionMemoKey, CoordinationTransition>();

    @Override
    public synchronized Optional<CoordinationTransition> find(
            TransitionMemoKey key) {
        return Optional.ofNullable(values.get(
                Objects.requireNonNull(key, "key")));
    }

    @Override
    public synchronized void put(
            TransitionMemoKey key,
            CoordinationTransition transition) {
        TransitionMemoKey checkedKey = Objects.requireNonNull(key, "key");
        CoordinationTransition checkedValue = Objects.requireNonNull(
                transition, "transition");
        CoordinationTransition existing = values.get(checkedKey);
        if (existing != null
                && !existing.commitPlan().transitionIdentity().equals(
                        checkedValue.commitPlan().transitionIdentity())) {
            throw new IllegalStateException(
                    "Memo key is already bound to another transition");
        }
        values.put(checkedKey, checkedValue);
    }
}
