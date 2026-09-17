package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ScopeAddress;
import blue.coordination.sdk.ActivationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

final class StoredOccurrenceIndexesTest {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);
    private static final String POLICY = "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String BLUE = "8M3d43KXskYr7rrdiaXPiPHmypFEjtU4uUyECtU7Tiyx";

    @Test void actualRootedAdmissionRowsReopenWithoutProviderOrOtherSessions() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick")); f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            // when
            var actual = f.engine.documents().require(parent.id()).rootedView().snapshot().occurrences();
            // then
            assertFalse(actual.isEmpty());
            assertTrue(actual.stream().anyMatch(row -> row.sourceDocumentId().value().equals(parent.id().value())
                    && row.targetDocumentId().value().equals(source.id().value())));
            var resident = ManagedOccurrenceInventory.of(actual);
            var storage = new StoredOccurrenceIndexes(f.bytes, LIMITS); var stored = storage.retainPartition(resident);
            var cold = new StoredOccurrenceIndexes(f.bytes.copy(), LIMITS);
            int reads = f.providerReads.get();
            var reopened = cold.open(root -> storage.root(stored, root));
            for (var row : actual) {
                var sourceId = DocumentId.of(row.sourceDocumentId().value());
                assertRows(List.of(row), List.of(cold.find(reopened, sourceId, row.sourceAddress().path()).orElseThrow()));
                assertRows(resident.rowsFrom(sourceId), cold.sourceRows(reopened, sourceId, false));
                assertRows(resident.activeRowsFrom(sourceId), cold.sourceRows(reopened, sourceId, true));
                assertRows(resident.rowsTouching(sourceId), cold.touching(reopened, sourceId));
            }
            assertEquals(reads, f.providerReads.get());
        }
    }

    @Test void rebranchPreservesAllCursorsOldRootsAndExactMutationCounters() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredOccurrenceIndexes(bytes, LIMITS);
        var rows = new ArrayList<ManagedOccurrenceBinding>();
        for (int i = 0; i < 48; i++) rows.add(row("source-" + i, "target-" + i, true, null));
        var representation = row("representation", "target", false, 0L).withRepresentationCursor(new ManagedRepresentationCursor(
                identity('a'), identity('b'), identity('c'), identity('d')));
        rows.add(representation);
        var resident = ManagedOccurrenceInventory.of(rows); var stored = storage.retainPartition(resident);
        var cold = new StoredOccurrenceIndexes(bytes.copy(), LIMITS);
        // when
        var reopened = cold.open(root -> storage.root(stored, root));
        // then
        assertRows(List.of(representation), cold.sourceRows(reopened, DocumentId.of("representation"), false));
        var replacement = row("source-7", "target-7", false, -1L);
        var expected = resident.replaceSources(List.of(DocumentId.of("source-7")), List.of(replacement));
        var changed = reopened.replaceSources(List.of(DocumentId.of("source-7")), List.of(replacement));
        assertEquals(expected.metrics(), changed.metrics());
        assertRows(expected.inventory().rows(), changed.inventory().rows());
        var finalIndex = cold.retainPartition(changed.inventory());
        assertRows(List.of(replacement), cold.sourceRows(cold.open(root -> cold.root(finalIndex, root)), DocumentId.of("source-7"), false));
        assertTrue(cold.find(reopened, DocumentId.of("source-7"), "/child").orElseThrow().active());
        assertRows(List.of(representation), cold.sourceRows(finalIndex, DocumentId.of("representation"), false));
        assertEquals(resident.activeRowsFromRead(DocumentId.of("source-9")).indexComparisons(),
                reopened.activeRowsFromRead(DocumentId.of("source-9")).indexComparisons());
    }

    @Test void selectedMixedIndexAndMalformedIdentityFailNoncommitting() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredOccurrenceIndexes(bytes, LIMITS);
        var original = row("a", "b", true, null); var input = storage.retainPartition(ManagedOccurrenceInventory.of(List.of(original)));
        var inactive = row("a", "b", false, 0L); var updated = storage.retainPartition(ManagedOccurrenceInventory.of(List.of(inactive)));
        // when
        var mixed = storage.open(root -> storage.root(root == StoredOccurrenceIndexes.Root.SOURCE ? updated : input, root));
        // then
        assertThrows(CoordinationObjectStorageException.class, () -> storage.sourceRows(mixed, DocumentId.of("a"), false));
        var missing = storage.retainPartition(ManagedOccurrenceInventory.empty());
        var hole = storage.open(root -> storage.root(root == StoredOccurrenceIndexes.Root.OCCURRENCE_ID ? missing : input, root));
        assertThrows(CoordinationObjectStorageException.class, () -> storage.find(hole, DocumentId.of("a"), "/child"));
        var codecs = new StoreIndexCodecs(bytes, LIMITS);
        var wrong = new ManagedOccurrenceBinding(identity('f'), original.bindingIdentity(), original.bindingPolicyIdentity(),
                original.sourceDocumentId(), original.sourceAddress(), original.targetDocumentId(), original.expectedTargetBlueId(), true, null);
        assertThrows(CoordinationObjectStorageException.class, () -> codecs.occurrences.decode(codecs.occurrences.encode(wrong)));
        assertThrows(CoordinationObjectStorageException.class, () -> storage.open(root -> storage.root(input, StoredOccurrenceIndexes.Root.PATH)));
    }

    @Test void absentAndPhysicalFailureStayDistinctWithoutWriteOnRead() {
        // given
        var bytes = new DocumentSessionStorageTest.Bytes(); var storage = new StoredOccurrenceIndexes(bytes, LIMITS);
        var selected = storage.retainPartition(ManagedOccurrenceInventory.of(List.of(row("a", "b", true, null))));
        var coldBytes = bytes.copy(); var cold = new StoredOccurrenceIndexes(coldBytes, LIMITS);
        var reopened = cold.open(root -> storage.root(selected, root));
        // when
        int retained = coldBytes.records.size();
        // then
        assertTrue(cold.find(reopened, DocumentId.of("absent"), "/child").isEmpty());
        coldBytes.failRead = true;
        assertThrows(CoordinationObjectStorageException.class, () -> cold.find(reopened, DocumentId.of("a"), "/child"));
        coldBytes.failRead = false;
        assertTrue(cold.find(reopened, DocumentId.of("a"), "/child").isPresent());
        assertEquals(retained, coldBytes.records.size());
    }

    private static ManagedOccurrenceBinding row(String source, String target, boolean active, Long epoch) {
        return ManagedOccurrenceBinding.derived(POLICY, new blue.language.processor.closure.DocumentId(source), ScopeAddress.embedded("/child", 1),
                new blue.language.processor.closure.DocumentId(target), BLUE, active, epoch);
    }
    private static String identity(char value) { return "sha256:" + String.valueOf(value).repeat(64); }
    private static void assertRows(List<ManagedOccurrenceBinding> expected, List<ManagedOccurrenceBinding> actual) {
        assertEquals(expected.size(), actual.size()); var codec = new StoreIndexCodecs(new DocumentSessionStorageTest.Bytes(), LIMITS).occurrences;
        for (int i = 0; i < expected.size(); i++) assertArrayEquals(codec.encode(expected.get(i)), codec.encode(actual.get(i)));
    }
}
