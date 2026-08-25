package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Persistent-index parity and locality proofs for occurrence inventory. */
final class ManagedOccurrenceInventoryPersistentIndexTest {
    private static final String POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String INPUT_MASTER =
            "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr";
    private static final String INPUT_B = INPUT_MASTER + "#1";
    private static final String AFTER_REMOVE_B =
            "8JUt1dEDU1yTVwiNzmW84yecWT7sZsrovRknkrw1CDR5";
    private static final String AFTER_READD_B =
            "9Sov32cJbfoBc2NvjLBtkkgPVrMV7sJ8sBksik5e8ps8#1";
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");

    @Test
    void randomizedSourceDeltasMatchCanonicalReferenceAndAvlInvariants() {
        // given
        TreeMap<String, ManagedOccurrenceBinding> reference =
                initialReference(40);
        ManagedOccurrenceInventory initial =
                ManagedOccurrenceInventory.of(reference.values());

        // when
        ManagedOccurrenceInventory result = exerciseRandomSourceDeltas(
                initial, reference, 800, new Random(0x51A7E5L));

        // then
        assertEquals(canonicalIdentities(reference.values()),
                rowIdentities(result.rows()));
        result.assertStructurallyValid();
    }

    @Test
    void rebindRetireAndActivateReportOnlyTheirExactCommittedRow() {
        // given
        ManagedOccurrenceBinding selected = row(
                A, "/b", 1L, B, INPUT_B, true, null);
        ManagedOccurrenceBinding unrelated = row(
                DocumentId.of("unrelated-source"),
                "/peer",
                3L,
                DocumentId.of("unrelated-target"),
                cyclicBlueId(91L),
                true,
                null);
        ManagedOccurrenceInventory initial =
                ManagedOccurrenceInventory.of(List.of(
                        selected, unrelated));

        // when
        ManagedOccurrenceInventory.DeltaResult rebound = initial.applyDelta(
                List.of(ManagedOccurrenceInventory.Change.rebind(
                        A, "/b", B, AFTER_REMOVE_B)));
        ManagedOccurrenceInventory.DeltaResult retired =
                rebound.inventory().applyDelta(List.of(
                        ManagedOccurrenceInventory.Change.retire(
                                A, "/b", B, AFTER_REMOVE_B)));
        ManagedOccurrenceInventory.DeltaResult activated =
                retired.inventory().applyDelta(List.of(
                        ManagedOccurrenceInventory.Change.activate(
                                A, "/b", B, AFTER_READD_B)));

        // then
        assertExactChangedRowWork(rebound);
        assertExactChangedRowWork(retired);
        assertExactChangedRowWork(activated);
        assertEquals(1L,
                rebound.inventory().row(A, "/b").activationGeneration());
        assertFalse(retired.inventory().row(A, "/b").active());
        assertEquals(2L,
                retired.inventory().row(A, "/b").activationGeneration());
        assertTrue(activated.inventory().row(A, "/b").active());
        assertEquals(2L,
                activated.inventory().row(A, "/b")
                        .activationGeneration());
        assertSame(initial.row(DocumentId.of("unrelated-source"), "/peer"),
                activated.inventory().row(
                        DocumentId.of("unrelated-source"), "/peer"));
        activated.inventory().assertStructurallyValid();
    }

    @Test
    void oneChangedSourceSharesOneThousandUnrelatedRowsAndIsLogarithmic() {
        // given
        ArrayList<ManagedOccurrenceBinding> rows = new ArrayList<>();
        rows.add(row(A, "/b", 1L, B, INPUT_B, true, null));
        for (int index = 0; index < 1_000; index++) {
            rows.add(row(
                    ambientSource(index),
                    "/peer",
                    1L,
                    DocumentId.of("ambient-target-" + index),
                    cyclicBlueId(index + 10L),
                    index % 3 != 0,
                    null));
        }
        ManagedOccurrenceInventory initial =
                ManagedOccurrenceInventory.of(rows);
        ManagedOccurrenceBinding unrelated = initial.row(
                ambientSource(777), "/peer");

        // when
        ManagedOccurrenceInventory.DeltaResult delta =
                initial.replaceSources(
                        Set.of(A),
                        List.of(row(
                                A,
                                "/b",
                                1L,
                                B,
                                AFTER_READD_B,
                                true,
                                null)));

        // then
        assertTrue(delta.changed());
        assertEquals(1L, delta.metrics().rowsRead());
        assertTrue(delta.metrics().indexComparisons() < 500L,
                () -> "comparisons="
                        + delta.metrics().indexComparisons());
        assertTrue(delta.metrics().nodeAllocations() < 500L,
                () -> "allocations="
                        + delta.metrics().nodeAllocations());
        assertTrue(initial.sharedSourcePathNodeCountForTesting(
                delta.inventory()) > 950);
        assertTrue(initial.sharedCanonicalNodeCountForTesting(
                delta.inventory()) > 950);
        assertSame(unrelated, delta.inventory().row(
                ambientSource(777), "/peer"));
        assertNotSame(initial, delta.inventory());
        assertEquals(1_001, delta.inventory().rows().size());
        delta.inventory().assertStructurallyValid();
    }

    @Test
    void sourceReplacementRejectsUnselectedAndDuplicateRowsAtomically() {
        // given
        ManagedOccurrenceInventory inventory =
                ManagedOccurrenceInventory.of(List.of(
                        row(A, "/b", 1L, B, INPUT_B, true, null),
                        row(B, "/a", 1L, A, cyclicBlueId(0L),
                                true, null)));
        List<String> before = rowIdentities(inventory.rows());

        // when
        IllegalArgumentException unselected = assertThrows(
                IllegalArgumentException.class,
                () -> inventory.replaceSources(
                        Set.of(A), inventory.rowsFrom(B)));
        ManagedOccurrenceBinding replacement = row(
                A, "/b", 2L, B, AFTER_READD_B, false, null);
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> inventory.replaceSources(
                        Set.of(A), List.of(replacement, replacement)));

        // then
        assertTrue(unselected.getMessage().contains("unaffected source"));
        assertTrue(duplicate.getMessage().contains("source/path"));
        assertEquals(before, rowIdentities(inventory.rows()));
        inventory.assertStructurallyValid();
    }

    private static ManagedOccurrenceInventory exerciseRandomSourceDeltas(
            ManagedOccurrenceInventory initial,
            TreeMap<String, ManagedOccurrenceBinding> reference,
            int iterations,
            Random random) {
        ManagedOccurrenceInventory inventory = initial;
        long[] revisions = new long[40];
        for (int index = 0; index < revisions.length; index++) {
            revisions[index] = 1L;
        }
        for (int iteration = 0; iteration < iterations; iteration++) {
            int sourceIndex = random.nextInt(revisions.length);
            DocumentId source = source(sourceIndex);
            List<ManagedOccurrenceBinding> before = referenceRowsFrom(
                    reference, source);
            List<ManagedOccurrenceBinding> replacements = switch (
                    random.nextInt(5)) {
                case 0 -> List.of();
                case 1 -> before;
                default -> randomReplacementRows(
                        sourceIndex,
                        ++revisions[sourceIndex],
                        random);
            };
            boolean expectedChanged = !canonicalIdentities(before).equals(
                    canonicalIdentities(replacements));

            ManagedOccurrenceInventory.DeltaResult delta =
                    inventory.replaceSources(Set.of(source), replacements);
            replaceReferenceSource(reference, source, replacements);

            assertEquals(expectedChanged, delta.changed(),
                    "changed at iteration " + iteration);
            assertEquals(before.size(), delta.metrics().rowsRead(),
                    "rows read at iteration " + iteration);
            if (expectedChanged) {
                assertTrue(delta.metrics().nodeAllocations() > 0L,
                        "allocations at iteration " + iteration);
                assertNotSame(inventory, delta.inventory());
            } else {
                assertEquals(0L, delta.metrics().nodeAllocations(),
                        "allocations at iteration " + iteration);
                assertSame(inventory, delta.inventory());
            }
            inventory = delta.inventory();
            assertEquals(canonicalIdentities(reference.values()),
                    rowIdentities(inventory.rows()),
                    "all rows at iteration " + iteration);
            assertEquals(canonicalActiveIdentities(reference.values()),
                    rowIdentities(inventory.activeRows()),
                    "active rows at iteration " + iteration);
            assertEquals(canonicalIdentities(referenceRowsFrom(
                            reference, source)),
                    rowIdentities(inventory.rowsFrom(source)),
                    "source rows at iteration " + iteration);
            assertEquals(referenceDocumentIds(reference.values()),
                    inventory.documentIds(),
                    "documents at iteration " + iteration);
            inventory.assertStructurallyValid();
        }
        return inventory;
    }

    private static TreeMap<String, ManagedOccurrenceBinding>
            initialReference(int sourceCount) {
        TreeMap<String, ManagedOccurrenceBinding> reference =
                new TreeMap<>();
        for (int sourceIndex = 0;
                sourceIndex < sourceCount;
                sourceIndex++) {
            for (int pathIndex = 0; pathIndex < 2; pathIndex++) {
                ManagedOccurrenceBinding row = row(
                        source(sourceIndex),
                        "/child-" + pathIndex,
                        1L,
                        target(sourceIndex, pathIndex),
                        cyclicBlueId(sourceIndex * 4L + pathIndex),
                        (sourceIndex + pathIndex) % 3 != 0,
                        null);
                reference.put(referenceKey(row), row);
            }
        }
        return reference;
    }

    private static List<ManagedOccurrenceBinding> randomReplacementRows(
            int sourceIndex,
            long revision,
            Random random) {
        int rowCount = Math.addExact(random.nextInt(3), 1);
        ArrayList<ManagedOccurrenceBinding> rows = new ArrayList<>(rowCount);
        for (int pathIndex = 0; pathIndex < rowCount; pathIndex++) {
            boolean active = random.nextBoolean();
            rows.add(row(
                    source(sourceIndex),
                    "/child-" + pathIndex,
                    revision,
                    target(sourceIndex, random.nextInt(4)),
                    cyclicBlueId(
                            revision * 1_000L
                                    + sourceIndex * 4L
                                    + pathIndex),
                    active,
                    !active && random.nextInt(8) == 0
                            ? revision
                            : null));
        }
        return List.copyOf(rows);
    }

    private static void replaceReferenceSource(
            TreeMap<String, ManagedOccurrenceBinding> reference,
            DocumentId source,
            Collection<ManagedOccurrenceBinding> replacements) {
        reference.entrySet().removeIf(entry ->
                entry.getValue().sourceDocumentId().value().equals(
                        source.value()));
        for (ManagedOccurrenceBinding replacement : replacements) {
            reference.put(referenceKey(replacement), replacement);
        }
    }

    private static List<ManagedOccurrenceBinding> referenceRowsFrom(
            TreeMap<String, ManagedOccurrenceBinding> reference,
            DocumentId source) {
        return reference.values().stream()
                .filter(row -> row.sourceDocumentId().value().equals(
                        source.value()))
                .sorted()
                .toList();
    }

    private static List<DocumentId> referenceDocumentIds(
            Collection<ManagedOccurrenceBinding> rows) {
        TreeSet<DocumentId> documentIds = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (ManagedOccurrenceBinding row : rows) {
            documentIds.add(DocumentId.of(row.sourceDocumentId().value()));
            documentIds.add(DocumentId.of(row.targetDocumentId().value()));
        }
        return List.copyOf(documentIds);
    }

    private static void assertExactChangedRowWork(
            ManagedOccurrenceInventory.DeltaResult result) {
        assertTrue(result.changed());
        assertEquals(1L, result.metrics().rowsRead());
        assertTrue(result.metrics().indexComparisons() > 0L);
        assertTrue(result.metrics().nodeAllocations() > 0L);
    }

    private static ManagedOccurrenceBinding row(
            DocumentId source,
            String path,
            long generation,
            DocumentId target,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        return ManagedOccurrenceBinding.derived(
                POLICY,
                contractsDocumentId(source),
                ScopeAddress.embedded(path, generation),
                contractsDocumentId(target),
                expectedTargetBlueId,
                active,
                pendingHistoricalEpoch);
    }

    private static DocumentId source(int index) {
        return DocumentId.of("source-" + index);
    }

    private static DocumentId target(int sourceIndex, int pathIndex) {
        return DocumentId.of(
                "target-" + sourceIndex + "-" + pathIndex);
    }

    private static DocumentId ambientSource(int index) {
        return DocumentId.of("ambient-source-" + index);
    }

    private static String cyclicBlueId(long index) {
        return INPUT_MASTER + "#" + index;
    }

    private static String referenceKey(ManagedOccurrenceBinding row) {
        return row.sourceDocumentId().value() + "\u0000" + row.sourcePath();
    }

    private static List<String> canonicalIdentities(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().sorted().map(
                ManagedOccurrenceInventoryPersistentIndexTest::rowIdentity)
                .toList();
    }

    private static List<String> canonicalActiveIdentities(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().filter(ManagedOccurrenceBinding::active)
                .sorted()
                .map(ManagedOccurrenceInventoryPersistentIndexTest::
                        rowIdentity)
                .toList();
    }

    private static List<String> rowIdentities(
            Collection<ManagedOccurrenceBinding> rows) {
        return rows.stream().map(
                ManagedOccurrenceInventoryPersistentIndexTest::rowIdentity)
                .toList();
    }

    private static String rowIdentity(ManagedOccurrenceBinding row) {
        return row.occurrenceIdentity()
                + ":" + row.bindingIdentity()
                + ":" + row.active()
                + ":" + row.pendingHistoricalEpoch();
    }

    private static blue.language.processor.closure.DocumentId
            contractsDocumentId(DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }
}
