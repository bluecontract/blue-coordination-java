package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transition and projection proofs for the complete occurrence inventory. */
final class ManagedOccurrenceInventoryTest {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");
    private static final DocumentId C = DocumentId.of("c");
    private static final DocumentId D = DocumentId.of("d");
    private static final String POLICY =
            "sha256:c1e8d880499cbafc595e1fb213ee73acc6ddb8d1d9850c7ddff2224c88a03d35";
    private static final String INPUT_MASTER =
            "4ZMfXZbSNVnEaqHVwYyYFHSfJ4JYs6VbR2oLZNqNkScr";
    private static final String INPUT_A = INPUT_MASTER + "#0";
    private static final String INPUT_B = INPUT_MASTER + "#1";
    private static final String AFTER_REMOVE_A =
            "D2pJsHeSPWSRkCPwNDJxNeYWmGETw6jtd35e3yyfJpjs";
    private static final String AFTER_REMOVE_B =
            "8JUt1dEDU1yTVwiNzmW84yecWT7sZsrovRknkrw1CDR5";
    private static final String AFTER_READD_MASTER =
            "9Sov32cJbfoBc2NvjLBtkkgPVrMV7sJ8sBksik5e8ps8";
    private static final String AFTER_READD_A = AFTER_READD_MASTER + "#0";
    private static final String AFTER_READD_B = AFTER_READD_MASTER + "#1";

    @Test
    void c35RemovalCommitsSuccessorThenLaterInvocationReaddsIt() {
        // given

        ManagedOccurrenceBinding aToB = asserted(
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca",
                "sha256:8e0adfdc7abea06d373ff4aa63d4b828da81abc01479d4a94cc7afdfe7b0e6e8",
                A, "/b", 1L, B, INPUT_B, true, null);
        ManagedOccurrenceBinding bToA = asserted(
                "sha256:f1e82f9a16ec41c5b9d4c5d37d0e412f4c0cdf05a639cc507aedfee2e8d3f232",
                "sha256:54757271a216624fb69f85769c3da699585f8efea9b4600fd494c3f3f1a4e515",
                B, "/a", 1L, A, INPUT_A, true, null);
        ManagedOccurrenceInventory input =
                ManagedOccurrenceInventory.of(List.of(bToA, aToB));

        ManagedOccurrenceInventory afterRemoval = input.apply(List.of(
                ManagedOccurrenceInventory.Change.rebind(
                        B, "/a", A, AFTER_REMOVE_A),
                ManagedOccurrenceInventory.Change.retire(
                        A, "/b", B, AFTER_REMOVE_B)));

        // when
        ManagedOccurrenceBinding successor = afterRemoval.row(A, "/b");

        // then
        assertFalse(successor.active());
        assertEquals(2L, successor.activationGeneration());
        assertEquals(B.value(), successor.targetDocumentId().value());
        assertEquals(AFTER_REMOVE_B, successor.expectedTargetBlueId());
        assertEquals(
                "sha256:e6af3ab7752ec65f1c992ec9d6626d5f29b5902f9b0b618b0e05ef3ff3eb8dd7",
                successor.occurrenceIdentity());
        assertEquals(
                "sha256:77e1b695e1628933af18ce0456f20a19bd2fc723b7a7b0d833cc49a0f2cb4fe3",
                successor.bindingIdentity());
        assertNotEquals(aToB.occurrenceIdentity(),
                successor.occurrenceIdentity());
        assertNotEquals(aToB.bindingIdentity(), successor.bindingIdentity());
        assertEquals(List.of(B.value()), afterRemoval.activeRows().stream()
                .map(row -> row.sourceDocumentId().value())
                .toList());

        ProcessEmbeddedComponentIndex afterRemovalIndex =
                ProcessEmbeddedComponentIndex.fromOccurrenceInventory(
                        afterRemoval);
        assertFalse(afterRemovalIndex.component(A).cyclic());
        assertFalse(afterRemovalIndex.component(B).cyclic());
        assertEquals(List.of(List.of(A), List.of(B)),
                afterRemovalIndex.components().stream()
                        .map(ProcessEmbeddedComponentIndex.Component::members)
                        .toList());

        ManagedOccurrenceInventory afterReadd = afterRemoval.apply(List.of(
                ManagedOccurrenceInventory.Change.activate(
                        A, "/b", B, AFTER_READD_B),
                ManagedOccurrenceInventory.Change.rebind(
                        B, "/a", A, AFTER_READD_A)));

        ManagedOccurrenceBinding readded = afterReadd.row(A, "/b");
        assertTrue(readded.active());
        assertEquals(2L, readded.activationGeneration());
        assertEquals(successor.occurrenceIdentity(),
                readded.occurrenceIdentity());
        assertEquals(
                "sha256:c2ab3c3f234687987ce2f7deb2712b202d280c08f17454e771b12015cc8c6c8d",
                readded.bindingIdentity());
        assertNotEquals(successor.bindingIdentity(),
                readded.bindingIdentity());
        ProcessEmbeddedComponentIndex.Component cycle =
                ProcessEmbeddedComponentIndex
                        .fromOccurrenceInventory(afterReadd)
                        .component(A);
        assertTrue(cycle.cyclic());
        assertEquals(List.of(A, B), cycle.members());
    }

    @Test
    void rejectedOrEmptyInvocationCannotAdvanceCommittedInventory() {
        // given

        ManagedOccurrenceBinding active = row(
                A, "/b", 1L, B, INPUT_B, true, null);

        // when
        ManagedOccurrenceInventory inventory =
                ManagedOccurrenceInventory.of(List.of(active));

        // then
        assertSame(inventory, inventory.apply(List.of()));
        assertThrows(IllegalStateException.class,
                () -> inventory.apply(List.of(
                        ManagedOccurrenceInventory.Change.retire(
                                A, "/b", C, INPUT_B))));
        assertEquals(active.occurrenceIdentity(),
                inventory.row(A, "/b").occurrenceIdentity());

        assertThrows(IllegalArgumentException.class,
                () -> inventory.apply(List.of(
                        ManagedOccurrenceInventory.Change.retire(
                                A, "/b", B, INPUT_B),
                        ManagedOccurrenceInventory.Change.activate(
                                A, "/b", B, INPUT_B))));
        assertTrue(inventory.row(A, "/b").active());
        assertEquals(1L,
                inventory.row(A, "/b").activationGeneration());

        ManagedOccurrenceInventory twoRows =
                ManagedOccurrenceInventory.of(List.of(
                        active,
                        row(B, "/a", 1L, A, INPUT_A, true, null)));
        assertThrows(IllegalStateException.class,
                () -> twoRows.apply(List.of(
                        ManagedOccurrenceInventory.Change.rebind(
                                A, "/b", B, AFTER_REMOVE_B),
                        ManagedOccurrenceInventory.Change.retire(
                                B, "/a", C, AFTER_REMOVE_A))));
        assertEquals(INPUT_B,
                twoRows.row(A, "/b").expectedTargetBlueId());
        assertEquals(INPUT_A,
                twoRows.row(B, "/a").expectedTargetBlueId());
    }

    @Test
    void activeDifferentLineageRebindAllocatesExactFreshNextGenerationRow() {
        // given

        ManagedOccurrenceBinding before = row(
                A, "/b", 4L, B, INPUT_B, true, null);
        ManagedOccurrenceInventory inventory =
                ManagedOccurrenceInventory.of(List.of(before));

        // when
        ManagedOccurrenceInventory rebound = inventory.apply(List.of(
                ManagedOccurrenceInventory.Change.rebind(
                        A, "/b", C, AFTER_READD_B)));

        // then
        ManagedOccurrenceBinding after = rebound.row(A, "/b");
        ManagedOccurrenceBinding exact = row(
                A, "/b", 5L, C, AFTER_READD_B, true, null);
        assertTrue(after.active());
        assertEquals(5L, after.activationGeneration());
        assertEquals(C.value(), after.targetDocumentId().value());
        assertEquals(AFTER_READD_B, after.expectedTargetBlueId());
        assertEquals(exact.occurrenceIdentity(),
                after.occurrenceIdentity());
        assertEquals(exact.bindingIdentity(), after.bindingIdentity());
        assertNotEquals(before.occurrenceIdentity(),
                after.occurrenceIdentity());
        assertNotEquals(before.bindingIdentity(), after.bindingIdentity());
        assertEquals(INPUT_B,
                inventory.row(A, "/b").expectedTargetBlueId());
        assertEquals(B.value(),
                inventory.row(A, "/b").targetDocumentId().value());
    }

    @Test
    void differentLineageRebindRejectsInactiveHistoricalAndOverflowRows() {
        // given

        ManagedOccurrenceInventory inactive =
                ManagedOccurrenceInventory.of(List.of(row(
                        A, "/b", 2L, B, INPUT_B, false, null)));
        ManagedOccurrenceInventory historical =
                ManagedOccurrenceInventory.of(List.of(row(
                        A, "/b", 2L, B, INPUT_B, false, 7L)));
        ManagedOccurrenceInventory atLimit =
                ManagedOccurrenceInventory.of(List.of(row(
                        A, "/b", MAX_SAFE_INTEGER,
                        B, INPUT_B, true, null)));

        // when
        assertThrows(IllegalStateException.class,
                () -> inactive.apply(List.of(
                        ManagedOccurrenceInventory.Change.rebind(
                                A, "/b", C, AFTER_READD_B))));
        assertThrows(IllegalStateException.class,
                () -> historical.apply(List.of(
                        ManagedOccurrenceInventory.Change.rebind(
                                A, "/b", C, AFTER_READD_B))));
        assertThrows(IllegalStateException.class,
                () -> atLimit.apply(List.of(
                        ManagedOccurrenceInventory.Change.rebind(
                                A, "/b", C, AFTER_READD_B))));

        // then
        assertFalse(inactive.row(A, "/b").active());
        assertEquals(7L,
                historical.row(A, "/b").pendingHistoricalEpoch());
        assertEquals(MAX_SAFE_INTEGER,
                atLimit.row(A, "/b").activationGeneration());
    }

    @Test
    void inactiveRowsStayOutOfEdgesButRemainInCompleteMembership() {
        // given

        ManagedOccurrenceInventory inventory =
                ManagedOccurrenceInventory.of(List.of(
                        row(A, "/b", 1L, B, INPUT_B, true, null),
                        row(B, "/a", 1L, A, INPUT_A, true, null),
                        row(C, "/a", 4L, A, INPUT_A, false, null),
                        row(A, "/d", 3L, D, INPUT_A, false, 7L)));

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex
                        .fromDocumentsAndOccurrenceInventory(
                                List.of(DocumentId.of("isolated")), inventory);

        // then
        assertEquals(List.of(A, B, C, D, DocumentId.of("isolated")),
                index.documents());
        assertTrue(index.component(A).cyclic());
        assertEquals(index.component(A), index.component(B));
        assertFalse(index.component(C).cyclic());
        assertEquals(List.of(C), index.component(C).members());
        assertEquals(List.of(D), index.component(D).members());
        assertEquals(List.of(), index.targets(index.component(C)));
        assertEquals(List.of(), index.sources(index.component(D)));
        assertEquals(inventory.rows().stream()
                        .filter(row -> row.sourceDocumentId().value()
                                .equals(A.value()))
                        .toList(),
                inventory.rowsFrom(A));
    }

    @Test
    void insertionOrderCannotChangeInventoryOrComponentProjection() {
        // given

        List<ManagedOccurrenceBinding> forward = List.of(
                row(A, "/b", 1L, B, INPUT_B, true, null),
                row(B, "/a", 1L, A, INPUT_A, true, null),
                row(C, "/d", 1L, D, AFTER_REMOVE_B, false, null));
        List<ManagedOccurrenceBinding> reverse =
                new ArrayList<>(forward);
        Collections.reverse(reverse);

        ManagedOccurrenceInventory first =
                ManagedOccurrenceInventory.of(forward);

        // when
        ManagedOccurrenceInventory second =
                ManagedOccurrenceInventory.of(reverse);

        // then
        assertEquals(rowIdentities(first.rows()),
                rowIdentities(second.rows()));
        assertEquals(rowIdentities(first.activeRows()),
                rowIdentities(second.activeRows()));
        assertEquals(first.documentIds(), second.documentIds());

        ProcessEmbeddedComponentIndex firstIndex =
                ProcessEmbeddedComponentIndex.fromOccurrenceInventory(first);
        ProcessEmbeddedComponentIndex secondIndex =
                ProcessEmbeddedComponentIndex.fromOccurrenceInventory(second);
        assertEquals(firstIndex.documents(), secondIndex.documents());
        assertEquals(firstIndex.components(), secondIndex.components());
        assertEquals(firstIndex.cohorts(), secondIndex.cohorts());
    }

    @Test
    void exactIdentitiesAndPortableIntegersAreEnforcedAtTheBoundary() {
        // given
        String expectedOccurrenceIdentity =
                "sha256:f5d1cd1ca17ac4fa6547d53f85dadb18f4b37e1bca42588f5cb4fb9090023eca";
        String expectedBindingIdentity =
                "sha256:8e0adfdc7abea06d373ff4aa63d4b828da81abc01479d4a94cc7afdfe7b0e6e8";

        // when
        ManagedOccurrenceBinding exact = row(
                A, "/b", 1L, B, INPUT_B, true, null);

        // then
        assertEquals(expectedOccurrenceIdentity, exact.occurrenceIdentity());
        assertEquals(expectedBindingIdentity, exact.bindingIdentity());

        assertThrows(IllegalArgumentException.class,
                () -> asserted(
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                        exact.bindingIdentity(),
                        A, "/b", 1L, B, INPUT_B, true, null));
        ManagedOccurrenceBinding unchecked =
                new ManagedOccurrenceBinding(
                        "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                        exact.bindingIdentity(),
                        POLICY,
                        contractsDocumentId(A),
                        ScopeAddress.embedded("/b", 1L),
                        contractsDocumentId(B),
                        INPUT_B,
                        true,
                        null);
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceInventory.of(List.of(unchecked)));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedOccurrenceInventory.of(List.of(
                        exact,
                        row(A, "/b", 2L, B, INPUT_B, false, null))));
        assertThrows(IllegalArgumentException.class,
                () -> row(A, "/b", 0L, B, INPUT_B, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> row(A, "/b", 1L, B, INPUT_B, false,
                        MAX_SAFE_INTEGER + 1L));
        assertThrows(IllegalArgumentException.class,
                () -> row(A, "/b", 1L, B, INPUT_B, true, 0L));

        ManagedOccurrenceInventory atLimit =
                ManagedOccurrenceInventory.of(List.of(row(
                        A, "/b", MAX_SAFE_INTEGER,
                        B, INPUT_B, true, null)));
        assertThrows(IllegalStateException.class,
                () -> atLimit.apply(List.of(
                        ManagedOccurrenceInventory.Change.retire(
                                A, "/b", B, INPUT_B))));
        assertEquals(MAX_SAFE_INTEGER,
                atLimit.row(A, "/b").activationGeneration());
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

    private static ManagedOccurrenceBinding asserted(
            String occurrenceIdentity,
            String bindingIdentity,
            DocumentId source,
            String path,
            long generation,
            DocumentId target,
            String expectedTargetBlueId,
            boolean active,
            Long pendingHistoricalEpoch) {
        return ManagedOccurrenceBinding.verified(
                occurrenceIdentity,
                bindingIdentity,
                POLICY,
                contractsDocumentId(source),
                ScopeAddress.embedded(path, generation),
                contractsDocumentId(target),
                expectedTargetBlueId,
                active,
                pendingHistoricalEpoch);
    }

    private static blue.language.processor.closure.DocumentId
            contractsDocumentId(DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static List<String> rowIdentities(
            List<ManagedOccurrenceBinding> rows) {
        return rows.stream()
                .map(row -> row.occurrenceIdentity()
                        + ":" + row.bindingIdentity()
                        + ":" + row.active()
                        + ":" + row.pendingHistoricalEpoch())
                .toList();
    }
}
