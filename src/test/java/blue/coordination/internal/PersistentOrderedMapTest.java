package blue.coordination.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistentOrderedMapTest {
    private static final long RANDOM_SEED = 0x5EED_C0DEL;

    @Test
    void deterministicRandomizedMutationsMatchTreeMapAndPreserveSnapshots() {
        // given
        PersistentOrderedMap<Integer, String> actual =
                PersistentOrderedMap.empty(Comparator.naturalOrder());
        TreeMap<Integer, String> expected = new TreeMap<>();
        ArrayList<Snapshot> snapshots = new ArrayList<>();
        Random random = new Random(RANDOM_SEED);

        // when
        for (int step = 0; step < 4_096; step++) {
            int key = random.nextInt(512);
            int operation = random.nextInt(5);
            PersistentOrderedMap<Integer, String> before = actual;
            TreeMap<Integer, String> expectedBefore = new TreeMap<>(expected);

            if (operation <= 1) {
                String value = "value-" + step;
                PersistentOrderedMap.Mutation<Integer, String> mutation =
                        actual.put(key, value);

                assertTrue(mutation.changed());
                assertEquals(
                        mutation.metrics().comparisons(),
                        mutation.comparisons());
                assertEquals(
                        mutation.metrics().copiedNodes(),
                        mutation.copiedNodes());
                assertTrue(mutation.comparisons()
                        <= before.heightForTesting());
                assertTrue(mutation.copiedNodes() > 0);
                actual = mutation.map();
                expected.put(key, value);
            } else if (operation == 2) {
                boolean present = expected.containsKey(key);
                PersistentOrderedMap.Mutation<Integer, String> mutation =
                        actual.remove(key);

                assertEquals(present, mutation.changed());
                assertTrue(mutation.comparisons()
                        <= before.heightForTesting());
                if (!present) {
                    assertSame(before, mutation.map());
                    assertSame(
                            before.rootIdentityForTesting(),
                            mutation.map().rootIdentityForTesting());
                    assertEquals(0, mutation.copiedNodes());
                }
                actual = mutation.map();
                expected.remove(key);
            } else if (operation == 3) {
                PersistentOrderedMap.ReadResult<String> result =
                        actual.read(key);

                assertEquals(expected.get(key), result.value());
                assertEquals(expected.containsKey(key), result.found());
                assertTrue(result.comparisons()
                        <= actual.heightForTesting());
                assertEquals(result.comparisons(), actual.lookupSteps(key));
            } else if (snapshots.size() < 32) {
                snapshots.add(new Snapshot(
                        actual,
                        new TreeMap<>(expected)));
            }

            assertMapEquals(expectedBefore, before);
            assertMapEquals(expected, actual);
            actual.assertStructurallyValid();
            assertAvlHeightBound(actual);
        }

        // then
        for (Snapshot snapshot : snapshots) {
            assertMapEquals(snapshot.expected(), snapshot.actual());
            snapshot.actual().assertStructurallyValid();
        }
    }

    @Test
    void metricsCountActualSearchComparisonsAndAllocatedNodes() {
        // given
        PersistentOrderedMap<Integer, String> map =
                PersistentOrderedMap.empty(Comparator.naturalOrder());

        // when
        PersistentOrderedMap.Mutation<Integer, String> first = map.put(
                1, "one");
        PersistentOrderedMap.Mutation<Integer, String> second = first.map()
                .put(2, "two");
        PersistentOrderedMap.Mutation<Integer, String> rotated = second.map()
                .put(3, "three");

        // then
        assertEquals(0, first.comparisons());
        assertEquals(1, first.copiedNodes());
        assertEquals(1, second.comparisons());
        assertEquals(2, second.copiedNodes());
        assertEquals(2, rotated.comparisons());
        assertEquals(5, rotated.copiedNodes(),
                "the third insert allocates its path and two rotation nodes");
        assertEquals(List.of(1, 2, 3), rotated.map().keys());
        assertEquals(2, rotated.map().heightForTesting());
        rotated.map().assertStructurallyValid();

        PersistentOrderedMap.ReadResult<String> hit = rotated.map().read(3);
        PersistentOrderedMap.ReadResult<String> miss = rotated.map().read(4);
        assertTrue(hit.found());
        assertEquals("three", hit.value());
        assertEquals(2, hit.comparisons());
        assertFalse(miss.found());
        assertNull(miss.value());
        assertEquals(2, miss.comparisons());
    }

    @Test
    void singleUpdateCopiesOnlyItsPathAndSharesEveryUntouchedNode() {
        // given
        PersistentOrderedMap<Integer, String> map =
                PersistentOrderedMap.empty(Comparator.naturalOrder());
        for (int key = 0; key < 1_024; key++) {
            map = map.put(key, "value-" + key).map();
        }
        map.assertStructurallyValid();
        PersistentOrderedMap<Integer, String> before = map;

        // when
        PersistentOrderedMap.Mutation<Integer, String> mutation = map.put(
                512, "updated");
        PersistentOrderedMap<Integer, String> after = mutation.map();

        // then
        assertEquals(mutation.comparisons(), mutation.copiedNodes(),
                "a value replacement allocates exactly its search path");
        assertTrue(mutation.copiedNodes() < 16);
        assertEquals(
                before.size() - mutation.copiedNodes(),
                before.sharedNodeCountForTesting(after));
        assertEquals("value-512", before.get(512));
        assertEquals("updated", after.get(512));
        assertEquals(before.keys(), after.keys());
        before.assertStructurallyValid();
        after.assertStructurallyValid();
    }

    private static void assertMapEquals(
            Map<Integer, String> expected,
            PersistentOrderedMap<Integer, String> actual) {
        assertEquals(expected.size(), actual.size());
        assertEquals(List.copyOf(expected.keySet()), actual.keys());
        assertEquals(List.copyOf(expected.values()), actual.values());
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            assertEquals(entry.getValue(), actual.get(entry.getKey()));
        }
    }

    private static void assertAvlHeightBound(
            PersistentOrderedMap<Integer, String> map) {
        if (map.isEmpty()) {
            assertEquals(0, map.heightForTesting());
            return;
        }
        int logarithmCeiling = 32 - Integer.numberOfLeadingZeros(map.size());
        assertTrue(map.heightForTesting() <= 2 * logarithmCeiling,
                () -> "AVL height " + map.heightForTesting()
                        + " is not logarithmic for " + map.size()
                        + " entries");
    }

    private record Snapshot(
            PersistentOrderedMap<Integer, String> actual,
            TreeMap<Integer, String> expected) {
    }
}
