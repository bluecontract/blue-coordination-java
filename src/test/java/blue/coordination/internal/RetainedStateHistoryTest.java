package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical metadata controls; full processing equivalence belongs to the SDK history-access scenario. */
final class RetainedStateHistoryTest {
    private static final DocumentId OWNER = DocumentId.of("retained-history");
    private static final PersistentMapStorage.Limits LIMITS =
            new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);

    @Test void lineageDescriptorAndAppendDoNotCopyTheRetainedPrefix() {
        int smallerDescriptorBytes = 0;
        for (int count : new int[] {64, 1024}) {
            var bytes = new DocumentSessionStorageTest.Bytes();
            var codecs = new StoreIndexCodecs(RootedEngineStorage.controlledNamespace(bytes), LIMITS);
            var stored = codecs.lineages.prepareForStorage(lineage(count));
            int writes = bytes.writes;
            byte[] encoded = codecs.lineages.encode(stored);
            assertEquals(writes, bytes.writes, "Canonical encoding must not retain physical dependencies");
            assertTrue(encoded.length < 2048, "Lineage is a bounded descriptor, not one row per epoch");
            if (smallerDescriptorBytes != 0)
                assertTrue(encoded.length <= smallerDescriptorBytes + 32, "Only scalar identity lengths may grow here");
            smallerDescriptorBytes = encoded.length;

            int reads = bytes.reads;
            var cold = codecs.lineages.decode(encoded);
            assertTrue(bytes.reads - reads < 128, "Cold lineage decode selects only root/endpoints, not the prefix");
            assertEquals("state-0", cold.retainedBlueIdAt(0));
            assertEquals("state-" + (count - 1), cold.retainedBlueIdAt(count - 1));
            assertNull(cold.retainedBlueIdAt(-1)); assertNull(cold.retainedBlueIdAt(count));

            var metadata = (RetainedStateHistory) cold.retainedStates();
            writes = bytes.writes;
            var appended = metadata.index().put((long) count,
                    new ManagedLineageIndex.RetainedState(OWNER, count, "state-" + count)).map();
            var advanced = new ManagedLineageIndex.Lineage(OWNER, "authored", "state-0", count, "state-" + count,
                    new RetainedStateHistory(OWNER, appended), -1);
            var prepared = codecs.lineages.prepareForStorage(advanced);
            int appendWrites = bytes.writes - writes;
            assertTrue(appendWrites > 0 && appendWrites < 64,
                    "One append writes tree paths, not the 64/1024-row prefix");
            writes = bytes.writes;
            codecs.lineages.encode(prepared);
            assertEquals(writes, bytes.writes);
            assertEquals(count, cold.retainedStates().size(), "Old retained root remains immutable");
        }
    }

    @Test void identicalStoredBasisAndScalarMismatchNeedNoPrefixReads() {
        var bytes = new DocumentSessionStorageTest.Bytes();
        var codecs = new StoreIndexCodecs(RootedEngineStorage.controlledNamespace(bytes), LIMITS);
        byte[] encoded = codecs.lineages.encode(codecs.lineages.prepareForStorage(lineage(64)));
        var first = codecs.lineages.decode(encoded);
        var second = codecs.lineages.decode(encoded);
        var changedGap = new ManagedLineageIndex.Lineage(OWNER, "authored", "state-0", 63, "state-63",
                second.retainedStates(), 12);
        int reads = bytes.reads;
        bytes.failRead = true;
        assertTrue(first.sameIndexedHistory(second));
        assertEquals(first, second);
        assertFalse(first.sameIndexedHistory(changedGap), "Anchored representation gaps are part of the basis");
        assertEquals(reads, bytes.reads, "Comparing retained authority must not open any history row");
    }

    private static ManagedLineageIndex.Lineage lineage(int count) {
        PersistentOrderedMap<Long, ManagedLineageIndex.RetainedState> metadata = PersistentOrderedMap.empty(Long::compare);
        for (long epoch = 0; epoch < count; epoch++)
            metadata = metadata.put(epoch, new ManagedLineageIndex.RetainedState(OWNER, epoch, "state-" + epoch)).map();
        return new ManagedLineageIndex.Lineage(OWNER, "authored", "state-0", count - 1L, "state-" + (count - 1),
                new RetainedStateHistory(OWNER, metadata), -1);
    }
}
