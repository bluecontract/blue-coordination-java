package blue.coordination.internal;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistentMapViewTest {
    @Test
    void exposesDeterministicReadOnlyMapSemantics() {
        // given
        PersistentOrderedMap<Integer, String> index =
                PersistentOrderedMap.empty(Comparator.naturalOrder());
        index = index.put(2, "two").map();
        index = index.put(1, "one").map();

        // when
        Map<Integer, String> view = new PersistentMapView<>(index);

        // then
        assertEquals("one", view.get(1));
        assertNull(view.get("wrong-type"));
        assertTrue(view.containsKey(2));
        assertFalse(view.containsKey(3));
        assertEquals(List.of(1, 2), view.keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class,
                () -> view.put(3, "three"));
        assertThrows(UnsupportedOperationException.class,
                () -> view.remove(1));
    }
}
