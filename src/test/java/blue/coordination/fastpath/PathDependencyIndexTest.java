package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PathDependencyIndexTest {
    @Test
    void matchesOnlyExactPointerAncestorsAndDescendants() {
        PathDependencyIndex index = PathDependencyIndex.empty()
                .updated("ancestor", Collections.<String>emptySet(),
                        Collections.singleton("/a"))
                .updated("descendant", Collections.<String>emptySet(),
                        Collections.singleton("/a/b/c"))
                .updated("boundary", Collections.<String>emptySet(),
                        Collections.singleton("/ab"))
                .updated("escaped", Collections.<String>emptySet(),
                        Collections.singleton("/a~1b/leaf"));

        assertEquals(Arrays.asList("ancestor", "descendant"),
                new ArrayList<String>(index.affected(
                        Collections.singleton("/a/b"))));
        assertEquals(Collections.singleton("boundary"),
                index.affected(Collections.singleton("/ab/child")));
        assertEquals(Collections.singleton("escaped"),
                index.affected(Collections.singleton("/a~1b")));
        assertEquals(Collections.singleton("ancestor"),
                index.affected(Collections.singleton("/a/b/leaf")),
                "escaped slash is one pointer segment");
    }

    @Test
    void rootBindingMatchesEveryCanonicalChangeAndResultsAreOrderedImmutable() {
        PathDependencyIndex index = PathDependencyIndex.empty()
                .updated("z-key", Collections.<String>emptySet(),
                        Collections.singleton("/"))
                .updated("a-key", Collections.<String>emptySet(),
                        Collections.singleton("/orders/one"));

        Set<String> affected = index.affected(
                Collections.singleton("/orders/one/value"));

        assertEquals(Arrays.asList("a-key", "z-key"),
                new ArrayList<String>(affected));
        assertThrows(UnsupportedOperationException.class,
                () -> affected.add("mutation"));
    }

    @Test
    void persistentMoveAndRemovalKeepExactBindingCount() {
        PathDependencyIndex initial = PathDependencyIndex.empty();
        PathDependencyIndex added = initial.updated(
                "public",
                Collections.<String>emptySet(),
                Arrays.asList("/old/a", "/old/b"));

        assertEquals(2, added.pathCount());
        assertSame(added, added.updated(
                "public",
                Arrays.asList("/old/b", "/old/a"),
                Arrays.asList("/old/a", "/old/b")));

        PathDependencyIndex moved = added.updated(
                "public",
                Arrays.asList("/old/a", "/old/b"),
                Collections.singleton("/new"));
        assertEquals(1, moved.pathCount());
        assertTrue(moved.affected(Collections.singleton("/old")).isEmpty());
        assertEquals(Collections.singleton("public"),
                moved.affected(Collections.singleton("/new/value")));

        PathDependencyIndex removed = moved.updated(
                "public",
                Collections.singleton("/new"),
                Collections.<String>emptySet());
        assertSame(initial, removed);
        assertEquals(0, removed.pathCount());
        assertTrue(removed.affected(Collections.singleton("/new")).isEmpty());
    }

    @Test
    void rejectsUnprovenPreviousBindingsAndDuplicatePaths() {
        PathDependencyIndex empty = PathDependencyIndex.empty();
        assertThrows(IllegalArgumentException.class,
                () -> empty.updated(
                        "public",
                        Collections.singleton("/absent"),
                        Collections.<String>emptySet()));
        assertThrows(IllegalArgumentException.class,
                () -> empty.updated(
                        "public",
                        Collections.<String>emptySet(),
                        Arrays.asList("/same", "/same")));

        PathDependencyIndex bound = empty.updated(
                "public",
                Collections.<String>emptySet(),
                Collections.singleton("/bound"));
        assertThrows(IllegalArgumentException.class,
                () -> bound.updated(
                        "public",
                        Collections.<String>emptySet(),
                        Collections.singleton("/bound")));
    }
}
