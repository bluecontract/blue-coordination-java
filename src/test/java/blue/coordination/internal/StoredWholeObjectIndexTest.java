package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredWholeObjectIndexTest {
    private static final int MAX = 1024 * 1024;
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(65536, 8192, 32768, 8192, 64);

    @Test void selectedExactBodyAndCyclicProofReopenWithoutProviderOrCatalogMaterialization() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new WholeObjectStorage(bytes, MAX, 128);
        var indexes = new StoredWholeObjectIndex(bytes, LIMITS); var writer = new WholeObjectStore(new EngineMetrics());
        for (int i = 0; i < 100; i++) writer.put(new Node().value("unrelated-" + i), "unrelated");
        var value = writer.put(new Node().value("selected"), "test");
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("index-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("index-b").properties("peer", new Node().blueId("this#0")))));
        String member = provider.getBlueIdByName("index-a"); var body = provider.fetchByBlueId(member).get(0);
        var proof = provider.cyclicSetProofFor(member).proof().orElseThrow();
        var exact = ExactValue.fromVerifiedProviderEvidence(member, body, proof);
        writer.putVerifiedProviderEvidence(exact, body, proof, "cyclic");
        var selection = indexes.empty().stage(storage.retain(writer.changes())).selection();
        var copied = bytes.copy(); var coldIndex = new StoredWholeObjectIndex(copied, LIMITS).open(selection);
        var cold = new WholeObjectStore(new EngineMetrics(), new WholeObjectStorage(copied, MAX, 128).open(coldIndex));
        // when
        int reads = copied.reads;
        // then
        assertEquals(102, cold.size()); assertEquals(reads, copied.reads);
        assertTrue(cold.require(value.blueId()).sameExactValue(value));
        assertTrue(cold.require(member).sameExactValue(exact));
        assertEquals(List.of(member), coldIndex.cyclicMembers(BlueIds.cyclicSetMasterBlueId(member)));
        assertTrue(cold.changes().entries().isEmpty()); assertTrue(cold.changes().proofs().isEmpty()); assertEquals(0, copied.writes);
    }

    @Test void referenceUpgradeIsSeparateFromPinnedOldViewAndFailedStageCannotChangeIt() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new WholeObjectStorage(bytes, MAX, 128);
        var indexes = new StoredWholeObjectIndex(bytes, LIMITS); var writer = new WholeObjectStore(new EngineMetrics());
        var value = ExactValue.verified(new Node().value("complete")); writer.put(value.referenceNode(), "reference");
        var old = indexes.empty().stage(storage.retain(writer.changes()));
        var upgraded = new WholeObjectStore(new EngineMetrics(), storage.open(old)); upgraded.put(value, "full");
        var delta = storage.retain(upgraded.changes()); var previous = old.selection();
        // when
        bytes.failAtWrite = bytes.writes + 1;
        // then
        assertThrows(CoordinationObjectStorageException.class, () -> old.stage(delta));
        assertArrayEquals(previous.entries(), old.selection().entries()); bytes.failAtWrite = -1;
        var next = old.stage(delta);
        assertTrue(new WholeObjectStore(new EngineMetrics(), storage.open(old)).require(value.blueId()).frozen().isReferenceOnly());
        assertTrue(new WholeObjectStore(new EngineMetrics(), storage.open(next)).require(value.blueId()).sameExactValue(value));
        var missing = bytes.copy(); missing.records.remove(next.entryAddress(value.blueId()).orElseThrow());
        var cold = new WholeObjectStore(new EngineMetrics(), new WholeObjectStorage(missing, MAX, 128).open(new StoredWholeObjectIndex(missing, LIMITS).open(next.selection())));
        assertThrows(CoordinationObjectStorageException.class, () -> cold.require(value.blueId()));
        assertThrows(CoordinationObjectStorageException.class, () -> old.stage(new WholeObjectStorage.References(Map.of(), Map.of(), Map.of("foreign", List.of(value.blueId())))));
    }
}
