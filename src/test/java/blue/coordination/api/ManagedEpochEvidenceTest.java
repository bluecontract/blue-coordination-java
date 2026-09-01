package blue.coordination.api;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedEpochEvidenceTest {
    private static final DocumentId SOURCE = DocumentId.of("source");
    private static final DocumentId CONSUMER = DocumentId.of("consumer");

    @Test
    void initializationReceiptHasNoBeforeStateAndIntegratesWithEpochZero() {
        // given
        ExactValue initial = exact("initial");
        ExternalOrderKey causeOrder = ExternalOrderKey.of(
                List.of(0L, "initialization"));

        // when
        ManagedEpochReceipt receipt = ManagedEpochReceipt.identified(
                SOURCE,
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                null,
                initial,
                identity('0'),
                null,
                causeOrder,
                identity('a'),
                identity('b'),
                List.of(),
                3L);

        DocumentRevision revision = new DocumentRevision(
                SOURCE,
                0L,
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                null,
                initial,
                null,
                causeOrder,
                initial.blueId(),
                null,
                List.of(),
                3L,
                receipt);

        // then
        assertTrue(receipt.beforeBlueId().isEmpty());
        assertEquals(receipt, revision.managedEpochReceipt().orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> ManagedEpochReceipt.identified(
                        SOURCE,
                        0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        initial.blueId(),
                        initial,
                        identity('0'),
                        null,
                        causeOrder,
                        identity('a'),
                        identity('b'),
                        List.of(),
                        3L));
    }

    @Test
    void authoredSelectorSentinelCannotBecomeADurableReceiptEpoch() {
        // given
        ExactValue authoredInitial = exact("authored-initial");

        // when
        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> ManagedEpochReceipt.identified(
                        SOURCE,
                        -1L,
                        DocumentRevision.Kind.INITIALIZATION,
                        null,
                        authoredInitial,
                        identity('0'),
                        null,
                        ExternalOrderKey.of(List.of(0L, "initialization")),
                        identity('a'),
                        identity('b'),
                        List.of(),
                        0L));

        // then
        assertTrue(invalid.getMessage().contains("non-negative"));
    }

    @Test
    void completeReceiptPreservesDuplicateEventsAndRejectsTampering() {
        // given
        ExactValue state = exact("state");
        ExactValue equalEvent = exact("event");
        ManagedEventOccurrence first = ManagedEventOccurrence.identified(
                0L, 7L, SOURCE, identity('a'), equalEvent, false);
        ManagedEventOccurrence second = ManagedEventOccurrence.identified(
                1L, 8L, SOURCE, identity('b'), equalEvent, false);

        // when
        ManagedEpochReceipt receipt = ManagedEpochReceipt.identified(
                SOURCE,
                1L,
                DocumentRevision.Kind.EVENT_ONLY,
                state.blueId(),
                state,
                identity('c'),
                null,
                ExternalOrderKey.of(List.of(1L, "source")),
                identity('d'),
                identity('e'),
                List.of(first, second),
                11L);
        DocumentRevision revision = new DocumentRevision(
                SOURCE,
                1L,
                1L,
                DocumentRevision.Kind.EVENT_ONLY,
                state,
                state,
                null,
                ExternalOrderKey.of(List.of(1L, "source")),
                state.blueId(),
                null,
                List.of(),
                11L,
                receipt);

        // then
        assertEquals(first.eventBlueId(), second.eventBlueId());
        assertNotEquals(
                first.managedEventIdentity(), second.managedEventIdentity());
        assertEquals(List.of(first, second), receipt.emittedEvents());
        assertEquals(state.blueId(), receipt.beforeBlueId().orElseThrow());
        assertEquals(state.blueId(), receipt.afterBlueId());
        assertThrows(IllegalArgumentException.class, () ->
                new ManagedEpochReceipt(
                        receipt.receiptIdentity(),
                        SOURCE,
                        1L,
                        DocumentRevision.Kind.EVENT_ONLY,
                        state.blueId(),
                        state,
                        identity('c'),
                        null,
                        ExternalOrderKey.of(List.of(1L, "source")),
                        identity('d'),
                        identity('e'),
                        List.of(second, first),
                        11L));
        assertEquals(receipt, revision.managedEpochReceipt().orElseThrow());
    }

    @Test
    void identitiesRequirePortableTextAndSafeOrdinals() {
        // given
        ExactValue event = exact("event");

        // when
        IllegalArgumentException unsafeOrdinal = assertThrows(
                IllegalArgumentException.class, () ->
                ManagedEventOccurrence.identified(
                        Long.MAX_VALUE,
                        1L,
                        SOURCE,
                        identity('a'),
                        event,
                        true));
        IllegalArgumentException nonPortableText = assertThrows(
                IllegalArgumentException.class, () ->
                ManagedEventOccurrence.identified(
                        0L,
                        1L,
                        DocumentId.of("e\u0301"),
                        identity('a'),
                        event,
                        true));

        // then
        assertFalse(unsafeOrdinal.getMessage().isBlank());
        assertFalse(nonPortableText.getMessage().isBlank());
    }

    @Test
    void planBarrierWorkApplicationAndReadinessAreSelfVerifying() {
        // given
        ExactValue sourceState = exact("source-state");
        ExactValue consumerState = exact("consumer-state");
        ExternalOrderKey causeOrder = ExternalOrderKey.of(
                List.of(2L, "timeline", 1L));

        // when
        ManagedCatchUpBarrier emptyBarrier = ManagedCatchUpBarrier.identified(
                CONSUMER,
                identity('f'),
                causeOrder,
                List.of(),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);
        ManagedOccurrenceCatchUpPlan plan =
                ManagedOccurrenceCatchUpPlan.identified(
                        emptyBarrier.barrierIdentity(),
                        CONSUMER,
                        identity('1'),
                        "/child",
                        1L,
                        SOURCE,
                        -1L,
                        sourceState.blueId(),
                        0L,
                        2L,
                        identity('f'),
                        ManagedCatchUpStatus.PENDING,
                        null,
                        null);
        ManagedCatchUpBarrier barrier = ManagedCatchUpBarrier.identified(
                CONSUMER,
                identity('f'),
                causeOrder,
                List.of(plan.planIdentity()),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);

        ManagedEpochApplicationWork work =
                ManagedEpochApplicationWork.identified(
                        plan.planIdentity(),
                        barrier.barrierIdentity(),
                        identity('2'),
                        SOURCE,
                        0L,
                        CONSUMER,
                        identity('1'),
                        "/child",
                        1L,
                        4L,
                        consumerState.blueId(),
                        3L);
        ManagedEpochApplicationReceipt application =
                ManagedEpochApplicationReceipt.identified(
                        work.workIdentity(),
                        plan.planIdentity(),
                        identity('2'),
                        identity('3'),
                        identity('4'),
                        identity('5'),
                        CONSUMER,
                        5L,
                        identity('6'),
                        consumerState.blueId(),
                        1L);
        ManagedDocumentReadiness ready = ManagedDocumentReadiness.identified(
                CONSUMER,
                5L,
                consumerState.blueId(),
                Long.valueOf(5L),
                consumerState.blueId(),
                SessionStatus.READY,
                null,
                null,
                List.of());

        // then
        assertEquals(emptyBarrier.barrierIdentity(), barrier.barrierIdentity());
        assertNotEquals(
                emptyBarrier.snapshotIdentity(), barrier.snapshotIdentity());
        assertEquals(work.workIdentity(), application.workIdentity());
        assertTrue(ready.ready());
        assertFalse(plan.status().terminal());
        assertThrows(IllegalArgumentException.class, () ->
                new ManagedEpochApplicationReceipt(
                        application.applicationReceiptIdentity(),
                        work.workIdentity(),
                        plan.planIdentity(),
                        identity('2'),
                        identity('3'),
                        identity('4'),
                        identity('5'),
                        CONSUMER,
                        5L,
                        identity('6'),
                        consumerState.blueId(),
                        2L));
    }

    private static ExactValue exact(String value) {
        return ExactValue.verified(new Node().value(value));
    }

    private static String identity(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
