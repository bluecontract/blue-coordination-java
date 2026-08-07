package blue.coordination.engine.memory;

import blue.coordination.engine.spi.CoordinationTransitionMemoStore;

class InMemoryCoordinationTransitionMemoStoreTest
        extends CoordinationTransitionMemoStoreContract {

    @Override
    CoordinationTransitionMemoStore createStore() {
        return new InMemoryCoordinationTransitionMemoStore();
    }
}
