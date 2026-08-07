package blue.coordination.engine.memory;

import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InMemoryStoredCoordinationEventStoreTest {

    @Test
    void shouldRetainExactlyOneCanonicalHandlePerEventBlueId() {
        // given
        InMemoryStoredCoordinationEventStore store =
                new InMemoryStoredCoordinationEventStore();
        StoredCoordinationEvent first = event("event", "inventory", 1L);
        StoredCoordinationEvent equivalent = event(
                "event", "inventory", 1L);

        // when
        StoredCoordinationEvent inserted = store.putCanonical(first);
        StoredCoordinationEvent repeated = store.putCanonical(equivalent);

        // then
        assertSame(first, inserted);
        assertSame(first, repeated);
        assertEquals(1, store.size());
        assertSame(first, store.require("event"));
    }

    @Test
    void shouldRejectInventoryOrOrderingConflict() {
        // given
        InMemoryStoredCoordinationEventStore store =
                new InMemoryStoredCoordinationEventStore();
        store.putCanonical(event("event", "inventory", 1L));

        // when / then
        assertThrows(IllegalStateException.class, () -> store.putCanonical(
                event("event", "other-inventory", 1L)));
        assertThrows(IllegalStateException.class, () -> store.putCanonical(
                event("event", "inventory", 2L)));
    }

    @Test
    void shouldRebaseConcurrentSameAndDifferentKeyPublicationsExactly() {
        // given: all candidates are prepared before any publication
        InMemoryStoredCoordinationEventStore store =
                new InMemoryStoredCoordinationEventStore();
        StoredCoordinationEvent first = event("same", "inventory", 1L);
        StoredCoordinationEvent same = event("same", "inventory", 1L);
        StoredCoordinationEvent different = event(
                "different", "different-inventory", 2L);
        InMemoryStoredCoordinationEventStore.PreparedCanonicalPut
                preparedFirst = store.prepareCanonical(first);
        InMemoryStoredCoordinationEventStore.PreparedCanonicalPut
                preparedSame = store.prepareCanonical(same);
        InMemoryStoredCoordinationEventStore.PreparedCanonicalPut
                preparedDifferent = store.prepareCanonical(different);

        // when
        store.publishPreparedCanonicalUnchecked(preparedFirst);
        store.validatePreparedCanonical(preparedSame);
        store.publishPreparedCanonicalUnchecked(preparedSame);
        store.validatePreparedCanonical(preparedDifferent);
        store.publishPreparedCanonicalUnchecked(preparedDifferent);

        // then: the same key is idempotent and another key is not stale
        assertEquals(2, store.size());
        assertSame(first, store.require("same"));
        assertSame(different, store.require("different"));
    }

    private static StoredCoordinationEvent event(
            String blueId,
            String inventory,
            long sequence) {
        return new StoredCoordinationEvent(
                blueId,
                inventory,
                ExternalOrderKey.of(Arrays.<Object>asList(sequence, blueId)));
    }
}
