package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedEpochReceiptStoreTest {
    private static final Method IDENTIFY_TRANSITION = identifyTransition();

    @Test
    void contiguousHistoryIsImmutableAuditableAndIdempotent() {
        // given
        DocumentId source = DocumentId.of("source");
        ExactValue zero = exact("zero");
        ExactValue one = exact("one");
        ManagedEpochReceipt epochZero = receipt(
                source, 0L, null, zero, 10L);
        ManagedEpochReceipt epochOne = receipt(
                source, 1L, zero.blueId(), one, 11L);

        // when
        ManagedEpochReceiptStore zeroStore =
                ManagedEpochReceiptStore.empty().withReceipt(epochZero);
        ManagedEpochReceiptStore complete = zeroStore.withReceipt(epochOne);
        ManagedEpochReceiptStore.EvidenceRead evidence = complete
                .exactEvidence(source, 1L);

        // then
        assertSame(zeroStore, zeroStore.withReceipt(epochZero));
        assertEquals(-1L, ManagedEpochReceiptStore.empty()
                .latestEpoch(source));
        assertEquals(1L, complete.latestEpoch(source));
        assertEquals(2, complete.receiptCount());
        assertEquals(1, complete.documentCount());
        assertEquals(
                epochOne.receiptIdentity(),
                complete.exact(source, 1L).receipt().receiptIdentity());
        assertEquals(
                epochZero.receiptIdentity(),
                complete.byIdentity(epochZero.receiptIdentity())
                        .receipt().receiptIdentity());
        assertEquals(
                List.of(
                        epochZero.receiptIdentity(),
                        epochOne.receiptIdentity()),
                complete.audit(source).receipts().stream()
                        .map(ManagedEpochReceipt::receiptIdentity)
                        .toList());
        assertEquals(0, complete.audit(source).unrelatedDocumentReads());
        assertFalse(complete.exactTransition(source, 1L).found(),
                "projection-only append has no fabricated Contracts receipt");
        assertEquals(epochOne.receiptIdentity(),
                evidence.receipt().receiptIdentity());
        assertEquals(1, evidence.receiptRowsRead());
        assertEquals(0, evidence.unrelatedDocumentReads());
        complete.assertStructurallyValid();
    }

    @Test
    void gapsWrongBeforeAndEpochConflictsFailClosed() {
        // given
        DocumentId source = DocumentId.of("source");
        ExactValue zero = exact("zero");
        ManagedEpochReceiptStore store = ManagedEpochReceiptStore.empty()
                .withReceipt(receipt(source, 0L, null, zero, 20L));

        // when
        IllegalArgumentException gap = assertThrows(
                IllegalArgumentException.class,
                () -> store.withReceipt(receipt(
                        source,
                        2L,
                        zero.blueId(),
                        exact("two"),
                        21L)));
        IllegalArgumentException wrongBefore = assertThrows(
                IllegalArgumentException.class,
                () -> store.withReceipt(receipt(
                        source,
                        1L,
                        exact("wrong").blueId(),
                        exact("one"),
                        22L)));

        ManagedEpochReceipt epochOne = receipt(
                source, 1L, zero.blueId(), exact("one"), 23L);
        ManagedEpochReceiptStore advanced = store.withReceipt(epochOne);
        IllegalArgumentException conflict = assertThrows(
                IllegalArgumentException.class,
                () -> advanced.withReceipt(receipt(
                        source,
                        1L,
                        zero.blueId(),
                        exact("other-one"),
                        24L)));

        // then
        assertFalse(gap.getMessage().isBlank());
        assertFalse(wrongBefore.getMessage().isBlank());
        assertFalse(conflict.getMessage().isBlank());
    }

    @Test
    void componentRebindAdvancesOnlyTheContinuationCursor() {
        // given
        DocumentId source = DocumentId.of("representation-source");
        ExactValue zero = exact("zero");
        ExactValue representation = exact("component-representation");
        ManagedEpochReceipt epochZero = receipt(
                source, 0L, null, zero, 30L);
        ManagedEpochReceiptStore original = ManagedEpochReceiptStore.empty()
                .withReceipt(epochZero);
        ManagedDocumentTransitionReceipt transition = transition(
                source, zero.blueId(), representation.blueId());

        // when
        ManagedEpochReceiptStore rebound = original
                .withComponentRepresentationRebind(
                        source,
                        0L,
                        zero.blueId(),
                        representation.blueId(),
                        transition);

        // Rebind checkpoint before advancing from its continuation identity.
        assertEquals(1, rebound.receiptCount());
        assertEquals(0L, rebound.latestEpoch(source));
        assertEquals(epochZero.receiptIdentity(),
                rebound.exact(source, 0L).receipt().receiptIdentity());
        assertThrows(IllegalArgumentException.class, () -> rebound.withReceipt(
                receipt(source, 1L, zero.blueId(), exact("wrong"), 31L)));
        ManagedEpochReceipt epochOne = receipt(
                source,
                1L,
                representation.blueId(),
                exact("one"),
                32L);
        ManagedEpochReceiptStore advanced = rebound.withReceipt(epochOne);

        // then
        assertEquals(2, advanced.receiptCount());
        assertEquals(epochOne.receiptIdentity(),
                advanced.exact(source, 1L).receipt().receiptIdentity());
        assertEquals(1, original.receiptCount(),
                "the prior immutable receipt-store image must be unchanged");
    }

    @Test
    void oneThousandUnrelatedDocumentsDoNotExpandAnExactRead() {
        // given
        ManagedEpochReceiptStore store = ManagedEpochReceiptStore.empty();
        for (int index = 0; index < 1_000; index++) {
            DocumentId document = DocumentId.of("ambient-" + index);
            store = store.withReceipt(receipt(
                    document,
                    0L,
                    null,
                    exact("ambient-" + index),
                    1_000L + index));
        }
        DocumentId selected = DocumentId.of("selected");
        ManagedEpochReceipt selectedReceipt = receipt(
                selected, 0L, null, exact("selected"), 9_999L);
        store = store.withReceipt(selectedReceipt);

        // when
        ManagedEpochReceiptStore.ReceiptRead read = store.exact(selected, 0L);

        // then
        assertTrue(read.found());
        assertEquals(selectedReceipt.receiptIdentity(),
                read.receipt().receiptIdentity());
        assertTrue(read.indexComparisons() < 32, read::toString);
        assertEquals(1, read.receiptRowsRead());
        assertEquals(0, read.unrelatedDocumentReads());
        assertTrue(store.lastMutationNodeCopies() < 64,
                "append must path-copy logarithmic indexes");
        assertEquals(1, store.audit(selected).receiptRowsRead());
        assertEquals(0, store.audit(selected).unrelatedDocumentReads());
        store.assertStructurallyValid();
    }

    private static ManagedEpochReceipt receipt(
            DocumentId documentId,
            long epoch,
            String beforeBlueId,
            ExactValue after,
            long identitySeed) {
        return ManagedEpochReceipt.identified(
                documentId,
                epoch,
                epoch == 0L
                        ? DocumentRevision.Kind.INITIALIZATION
                        : DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                beforeBlueId,
                after,
                hash(identitySeed),
                null,
                ExternalOrderKey.of(List.of(epoch, documentId.value())),
                hash(identitySeed + 100_000L),
                hash(identitySeed + 200_000L),
                List.of(),
                identitySeed);
    }

    private static ExactValue exact(String value) {
        return ExactValue.verified(new Node().properties(
                "state", new Node().value(value)));
    }

    private static ManagedDocumentTransitionReceipt transition(
            DocumentId documentId,
            String beforeBlueId,
            String afterBlueId) {
        try {
            return (ManagedDocumentTransitionReceipt)
                    IDENTIFY_TRANSITION.invoke(
                            null,
                            hash(300_000L),
                            0L,
                            new blue.language.processor.closure.DocumentId(
                                    documentId.value()),
                            hash(300_001L),
                            beforeBlueId,
                            afterBlueId,
                            List.of(),
                            0L);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static Method identifyTransition() {
        try {
            Method method = ManagedDocumentTransitionReceipt.class
                    .getDeclaredMethod(
                            "identified",
                            String.class,
                            long.class,
                            blue.language.processor.closure.DocumentId.class,
                            String.class,
                            String.class,
                            String.class,
                            List.class,
                            long.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static String hash(long value) {
        return "sha256:" + String.format("%064x", value);
    }
}
