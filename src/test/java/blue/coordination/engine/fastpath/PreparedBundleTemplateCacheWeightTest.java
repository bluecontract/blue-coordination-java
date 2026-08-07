package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PreparedBundleTemplateCacheWeightTest {

    @Test
    void shouldEvictByMetadataBytesInDeterministicAccessOrder() {
        Fixture fixture = fixture();
        long oneTemplate = new PreparedBundleTemplate(
                "inventory-probe", fixture.handles, fixture.sizes)
                .approximateRetainedWeightBytes();
        PreparedBundleTemplateCache cache =
                new PreparedBundleTemplateCache(
                        8, Math.multiplyExact(oneTemplate, 2L));
        PreparedBundleTemplate first = cache.require(
                "inventory-first", fixture.handles, fixture.sizes);
        cache.require("inventory-second", fixture.handles, fixture.sizes);
        assertSame(first, cache.require(
                "inventory-first", fixture.handles, fixture.sizes));

        cache.require("inventory-third", fixture.handles, fixture.sizes);

        assertEquals(2, cache.size());
        assertEquals(oneTemplate * 2L, cache.retainedWeightBytes());
        assertEquals(3L, cache.builds());
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.evictions());
        cache.require("inventory-second", fixture.handles, fixture.sizes);
        assertEquals(4L, cache.builds(),
                "the byte-eldest template must be rebuilt");
    }

    @Test
    void shouldReturnButNeverRetainAnOversizedTemplate() {
        Fixture fixture = fixture();
        long weight = new PreparedBundleTemplate(
                "inventory-probe", fixture.handles, fixture.sizes)
                .approximateRetainedWeightBytes();
        PreparedBundleTemplateCache cache =
                new PreparedBundleTemplateCache(4, weight - 1L);

        cache.require("inventory", fixture.handles, fixture.sizes);
        cache.require("inventory", fixture.handles, fixture.sizes);

        assertEquals(0, cache.size());
        assertEquals(0L, cache.retainedWeightBytes());
        assertEquals(2L, cache.builds());
        assertEquals(2L, cache.misses());
    }

    @Test
    void shouldRejectNonPositiveBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedBundleTemplateCache(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedBundleTemplateCache(1, 0L));
    }

    private static Fixture fixture() {
        Node node = new Node().properties(
                "value", new Node().value("template"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(node);
        Object owner = new Object();
        ExactNodeHandle handle = ExactNodeHandle.copyAndVerify(
                blueId, node, owner);
        return new Fixture(
                Collections.singletonMap(blueId, handle),
                Collections.singletonMap(blueId, Long.valueOf(128L)));
    }

    private static final class Fixture {
        private final Map<String, ExactNodeHandle> handles;
        private final Map<String, Long> sizes;

        private Fixture(
                Map<String, ExactNodeHandle> handles,
                Map<String, Long> sizes) {
            this.handles = handles;
            this.sizes = sizes;
        }
    }
}
