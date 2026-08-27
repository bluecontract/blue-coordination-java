package blue.coordination.internal;

import org.junit.jupiter.api.Test;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistentMinimumMapTest {

    @Test
    void higherThanFindsStrictSuccessorsWithLogarithmicReads() {
        // given
        PersistentMinimumMap<Integer, String> map =
                PersistentMinimumMap.empty(Comparator.naturalOrder());
        for (int value = 0; value < 1_000; value++) {
            map = map.put(value, "value-" + value).map();
        }

        // when
        PersistentMinimumMap.MinimumResult<Integer, String> first =
                map.minimum();
        PersistentMinimumMap.MinimumResult<Integer, String> middle =
                map.higherThan(498);
        PersistentMinimumMap.MinimumResult<Integer, String> absent =
                map.higherThan(999);

        // then
        assertTrue(first.found());
        assertEquals(0, first.entry().getKey());
        assertTrue(middle.found());
        assertEquals(499, middle.entry().getKey());
        assertTrue(middle.rowsRead() < 32, middle::toString);
        assertFalse(absent.found());
        assertTrue(absent.rowsRead() < 32, absent::toString);
        map.assertStructurallyValid();
    }
}
