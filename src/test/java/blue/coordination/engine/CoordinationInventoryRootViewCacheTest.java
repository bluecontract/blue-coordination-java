package blue.coordination.engine;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationRootViewCacheSnapshot;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.engine.fastpath.RetainedNodeWeight;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CoordinationInventoryRootViewCacheTest {

    @Test
    void shouldEvictLeastRecentlyUsedRootAtTheExplicitBound() {
        // given
        RootFixture first = root("first");
        RootFixture second = root("second");
        RootFixture third = root("third");
        CoordinationInventoryRootViewCache cache =
                new CoordinationInventoryRootViewCache(2);
        cache.install(first.inventory, first.root);
        cache.install(second.inventory, second.root);

        // when
        assertEquals(
                NodeWireForm.get(first.root),
                NodeWireForm.get(cache.find(first.inventory)));
        cache.install(third.inventory, third.root);

        // then
        assertNull(cache.find(second.inventory));
        assertEquals(
                NodeWireForm.get(first.root),
                NodeWireForm.get(cache.find(first.inventory)));
        assertEquals(
                NodeWireForm.get(third.root),
                NodeWireForm.get(cache.find(third.inventory)));
        CoordinationRootViewCacheSnapshot metrics = cache.snapshot();
        assertEquals(2, metrics.maximumSize());
        assertEquals(2, metrics.currentSize());
        assertEquals(3L, metrics.hitCount());
        assertEquals(1L, metrics.missCount());
        assertEquals(3L, metrics.installationCount());
        assertEquals(1L, metrics.evictionCount());
    }

    @Test
    void shouldReturnDefensiveRootsWhileInventoriesRemainBodyFree() {
        // given
        RootFixture fixture = root("immutable");
        CoordinationInventoryRootViewCache cache =
                new CoordinationInventoryRootViewCache(1);
        cache.install(fixture.inventory, fixture.root);

        // when
        Node firstRead = cache.find(fixture.inventory);
        firstRead.properties("tampered", new Node().value(true));
        Node secondRead = cache.find(fixture.inventory);

        // then
        assertEquals(
                NodeWireForm.get(fixture.root),
                NodeWireForm.get(secondRead));
        assertNull(fixture.inventory.directRootOrNull());
        assertThrows(
                IllegalArgumentException.class,
                () -> cache.install(
                        fixture.inventory,
                        root("different").root));
    }

    @Test
    void shouldAdoptARequestOwnedVerifiedRootWithoutAnotherFullCopy() {
        // given
        RootFixture fixture = root("process-result");
        CoordinationInventoryRootViewCache cache =
                new CoordinationInventoryRootViewCache(1);

        // when
        cache.installOwnedVerified(
                fixture.inventory,
                fixture.root,
                fixture.inventory.rootBlueId());

        // then
        assertSame(
                fixture.root,
                cache.findRetained(fixture.inventory),
                "the request-owned value crosses the private ownership "
                        + "boundary without a complete Root clone");
        Node publicRead = cache.find(fixture.inventory);
        publicRead.properties("tampered", new Node().value(true));
        assertEquals(
                NodeWireForm.get(fixture.root),
                NodeWireForm.get(cache.find(fixture.inventory)),
                "ordinary reads remain defensive");
    }

    @Test
    void shouldRejectANonPositiveBound() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationInventoryRootViewCache(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationInventoryRootViewCache(1, 0L));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationProcessingEngine.builder()
                        .rootViewCacheMaximumSize(0));
    }

    @Test
    void shouldEvictLeastRecentlyUsedRootsAtTheRetainedByteBound() {
        RootFixture first = root("aaaaa");
        RootFixture second = root("bbbbb");
        RootFixture third = root("ccccc");
        long oneRoot = RetainedNodeWeight
                .approximateRetainedWeightBytes(first.root);
        CoordinationInventoryRootViewCache cache =
                new CoordinationInventoryRootViewCache(
                        8, Math.multiplyExact(oneRoot, 2L));
        cache.install(first.inventory, first.root);
        cache.install(second.inventory, second.root);
        cache.find(first.inventory);

        cache.install(third.inventory, third.root);

        assertNull(cache.find(second.inventory));
        assertEquals(
                NodeWireForm.get(first.root),
                NodeWireForm.get(cache.find(first.inventory)));
        assertEquals(
                NodeWireForm.get(third.root),
                NodeWireForm.get(cache.find(third.inventory)));
        CoordinationRootViewCacheSnapshot metrics = cache.snapshot();
        assertEquals(2, metrics.currentSize());
        assertEquals(oneRoot * 2L, metrics.currentWeightBytes());
        assertEquals(oneRoot * 2L, metrics.maximumWeightBytes());
        assertEquals(1L, metrics.evictionCount());
    }

    @Test
    void shouldNotRetainAnOversizedRootOrDisturbWarmEntries() {
        RootFixture warm = root("small");
        RootFixture oversized = root("a much larger retained scalar");
        long warmWeight = RetainedNodeWeight
                .approximateRetainedWeightBytes(warm.root);
        long oversizedWeight = RetainedNodeWeight
                .approximateRetainedWeightBytes(oversized.root);
        CoordinationInventoryRootViewCache cache =
                new CoordinationInventoryRootViewCache(
                        8, oversizedWeight - 1L);
        cache.install(warm.inventory, warm.root);

        cache.install(oversized.inventory, oversized.root);

        assertEquals(
                NodeWireForm.get(warm.root),
                NodeWireForm.get(cache.find(warm.inventory)));
        assertNull(cache.find(oversized.inventory));
        assertEquals(1, cache.snapshot().currentSize());
        assertEquals(warmWeight, cache.snapshot().currentWeightBytes());
        assertEquals(1L, cache.snapshot().evictionCount());
    }

    private static RootFixture root(String value) {
        Node root = new Node()
                .properties("value", new Node().value(value));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(root.clone());
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter
                                .EDGE_METADATA_SCHEMA_ID,
                        rootBlueId,
                        Collections.singletonList(rootBlueId),
                        Collections.singletonList(
                                new FragmentRootRecord(
                                        rootBlueId,
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT,
                                        "")),
                        Collections.emptyList(),
                        Collections.emptyList());
        return new RootFixture(root, inventory);
    }

    private static final class RootFixture {
        private final Node root;
        private final CoordinationFragmentInventory inventory;

        private RootFixture(
                Node root,
                CoordinationFragmentInventory inventory) {
            this.root = root;
            this.inventory = inventory;
        }
    }
}
