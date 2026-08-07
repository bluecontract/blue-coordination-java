package blue.coordination.engine.spi;

import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.TransitionMemoKey;

import java.util.Optional;

/** Optional exact whole-transition memo store; child-only memoization is unsafe. */
public interface CoordinationTransitionMemoStore {
    Optional<CoordinationTransition> find(TransitionMemoKey key);
    void put(TransitionMemoKey key, CoordinationTransition transition);
}
