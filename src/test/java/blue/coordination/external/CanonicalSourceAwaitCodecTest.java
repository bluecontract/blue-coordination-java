package blue.coordination.external;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSourceAwaitCodecTest {
    @Test void aggregateAwaitBudgetIncludesEveryDemandAndRoot() {
        String id = DirectBlueIdCalculator.calculateBlueId(new Node().value("required"));
        var a = ExactNodeDemand.derived(id, new DocumentId("source"), "/a");
        var b = ExactNodeDemand.derived(id, new DocumentId("source"), "/b");
        var await = new CanonicalSourceHistory.Await(List.of("timeline:opaque-required-prefix", a.demandIdentity(), b.demandIdentity()), List.of(a, b));
        Map<String, byte[]> bytes = new HashMap<>();
        String root = CanonicalSourceAwaitCodec.encode(await, bytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
        long total = bytes.values().stream().mapToLong(value -> value.length).sum();
        int largest = bytes.values().stream().mapToInt(value -> value.length).max().orElseThrow();
        var tooSmall = new FrozenNodeEvidenceCodec.Limits(largest, total - 1, 32, 100);
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class, () -> CanonicalSourceAwaitCodec.encode(await, (key, value) -> { }, tooSmall));
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class, () -> CanonicalSourceAwaitCodec.decode(root, bytes::get, tooSmall));
        var exact = new FrozenNodeEvidenceCodec.Limits(largest, total, 32, 100);
        var cold = CanonicalSourceAwaitCodec.decode(root, bytes::get, exact);
        assertEquals(await.keys(), cold.keys()); assertEquals(await.resourceDemands(), cold.resourceDemands());
        var tooFewFragments = new FrozenNodeEvidenceCodec.Limits(largest, total, 32, bytes.size() - 1);
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> CanonicalSourceAwaitCodec.encode(await, (key, value) -> { }, tooFewFragments));
        assertThrows(FrozenNodeEvidenceCodec.CapacityExceeded.class,
                () -> CanonicalSourceAwaitCodec.decode(root, bytes::get, tooFewFragments));
    }

    @Test void sharedInlineFragmentIsReadOnceAcrossTheWholeAwait() {
        Node value = new Node().value("one shared inline value");
        String id = DirectBlueIdCalculator.calculateBlueId(value);
        String hash = "sha256:" + "a".repeat(64);
        var a = ManagedOccurrenceEvidenceDemand.derived(hash, hash, 0, new DocumentId("source"), "/a", id, id, 0, value);
        var b = ManagedOccurrenceEvidenceDemand.derived(hash, hash, 0, new DocumentId("source"), "/b", id, id, 1, value);
        var await = new CanonicalSourceHistory.Await(List.of(a.demandIdentity(), b.demandIdentity()), List.of(a, b));
        Map<String, byte[]> bytes = new HashMap<>(); Map<String, Integer> reads = new HashMap<>();
        String root = CanonicalSourceAwaitCodec.encode(await, bytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
        var cold = CanonicalSourceAwaitCodec.decode(root, key -> { reads.merge(key, 1, Integer::sum); return bytes.get(key); }, FrozenNodeEvidenceCodec.Limits.defaults());
        assertEquals(await.resourceDemands(), cold.resourceDemands());
        assertEquals(bytes.keySet(), reads.keySet()); assertTrue(reads.values().stream().allMatch(count -> count == 1));
    }
}
