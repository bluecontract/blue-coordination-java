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

    @Test void independentMembersRetainOneSharedProofInEitherPublicationOrder() {
        // given
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("logical-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("logical-b").properties("peer", new Node().blueId("this#0")))));
        var packets = new java.util.ArrayList<blue.coordination.api.storage.CoordinationRecords.Publication>();
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new WholeObjectStorage(bytes, MAX, 128);
        var records = new LogicalRecordMapTest.Store();
        // when
        for (String name : List.of("logical-a", "logical-b")) {
            var attempt = records.attempt(); var context = new LogicalRecordContext(attempt);
            var index = new StoredWholeObjectIndex(bytes, LIMITS).openLogical(context);
            var writer = new WholeObjectStore(new EngineMetrics(), storage.open(index));
            String member = provider.getBlueIdByName(name); var body = provider.fetchByBlueId(member).get(0);
            var proof = provider.cyclicSetProofFor(member).proof().orElseThrow();
            writer.putVerifiedProviderEvidence(ExactValue.fromVerifiedProviderEvidence(member, body, proof), body, proof, "logical");
            index.stage(storage.retain(writer.changes())).selectLogical(); context.flush();
            packets.add(attempt.prepare(name, List.of(), new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1})));
        }
        // then
        for (boolean reverse : List.of(false, true)) {
            var target = new LogicalRecordMapTest.Store();
            assertTrue(target.publish(packets.get(reverse ? 1 : 0))); assertTrue(target.publish(packets.get(reverse ? 0 : 1)));
            try (var attempt = target.attempt()) {
                var index = new StoredWholeObjectIndex(bytes, LIMITS).openLogical(new LogicalRecordContext(attempt));
                var cold = new WholeObjectStore(new EngineMetrics(), storage.open(index));
                for (String name : List.of("logical-a", "logical-b")) {
                    String member = provider.getBlueIdByName(name);
                    assertEquals(member, cold.require(member).blueId());
                }
                assertEquals(2, ((List<?>) index.cyclicMembers(BlueIds.cyclicSetMasterBlueId(provider.getBlueIdByName("logical-a")))).size());
            }
        }
        assertTrue(packets.stream().allMatch(packet -> packet.immutableFacts().size() == 2));
        assertTrue(packets.stream().flatMap(packet -> packet.points().stream())
                .noneMatch(point -> blue.coordination.api.storage.CoordinationRecords.immutableFamily(point.key().family())));
    }

    @Test void logicalEntryUpgradeReopensTheColdExactValue() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new WholeObjectStorage(bytes, MAX, 128);
        var records = new LogicalRecordMapTest.Store(); var exact = ExactValue.verified(new Node().value("upgrade"));
        // when
        for (boolean full : List.of(false, true)) {
            var attempt = records.attempt(); var context = new LogicalRecordContext(attempt);
            var index = new StoredWholeObjectIndex(bytes, LIMITS).openLogical(context);
            var writer = new WholeObjectStore(new EngineMetrics(), storage.open(index));
            if (full) writer.put(exact, "full"); else writer.put(exact.referenceNode(), "reference");
            index.stage(storage.retain(writer.changes())).selectLogical(); context.flush();
            assertTrue(records.publish(attempt.prepare("upgrade-" + full, List.of(), new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1}))));
        }
        // then
        try (var attempt = records.attempt()) {
            var index = new StoredWholeObjectIndex(bytes, LIMITS).openLogical(new LogicalRecordContext(attempt));
            assertTrue(new WholeObjectStore(new EngineMetrics(), storage.open(index)).require(exact.blueId()).sameExactValue(exact));
        }
    }

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
