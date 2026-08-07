package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreparedBundleGraphCacheWeightTest {

    @Test
    void shouldEvictByRetainedBytesInDeterministicAccessOrder() {
        CoordinationFragmentInventory first = inventory("aaaaa");
        CoordinationFragmentInventory second = inventory("bbbbb");
        CoordinationFragmentInventory third = inventory("ccccc");
        long oneIndex = new FragmentGraphIndex(first)
                .approximateRetainedWeightBytes();
        PreparedBundleGraphCache cache = new PreparedBundleGraphCache(
                8, Math.multiplyExact(oneIndex, 2L));
        cache.require(first);
        cache.require(second);
        cache.require(first);

        cache.require(third);

        assertEquals(2, cache.size());
        assertEquals(oneIndex * 2L, cache.retainedWeightBytes());
        assertEquals(3L, cache.builds());
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.evictions());
        cache.require(second);
        assertEquals(4L, cache.builds(),
                "the byte-eldest inventory must be rebuilt");
    }

    @Test
    void shouldReturnButNeverRetainAnOversizedIndex() {
        CoordinationFragmentInventory inventory = inventory("oversized");
        long weight = new FragmentGraphIndex(inventory)
                .approximateRetainedWeightBytes();
        PreparedBundleGraphCache cache = new PreparedBundleGraphCache(
                4, weight - 1L);

        cache.require(inventory);
        cache.require(inventory);

        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedWeightBytes());
        assertEquals(2L, cache.builds());
        assertEquals(2L, cache.misses());
    }

    @Test
    void shouldRejectNonPositiveBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedBundleGraphCache(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedBundleGraphCache(1, 0L));
    }

    private static CoordinationFragmentInventory inventory(String value) {
        Node root = new Node().properties(
                "value", new Node().value(value));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        return new CoordinationFragmentInventory(
                CoordinationFragmentInventory.SCHEMA_VERSION,
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                rootBlueId,
                Collections.singletonList(rootBlueId),
                Collections.singletonList(new FragmentRootRecord(
                        rootBlueId,
                        CoordinationDocumentSplitter.FragmentRootKind
                                .DOCUMENT,
                        "")),
                Collections.emptyList(),
                Collections.emptyList());
    }
}
