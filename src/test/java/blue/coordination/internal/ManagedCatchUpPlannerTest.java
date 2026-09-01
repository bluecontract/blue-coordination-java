package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical work materialization across occurrence-specific cursors. */
final class ManagedCatchUpPlannerTest {
    private static final DocumentId CONSUMER = DocumentId.of("consumer");
    private static final DocumentId OTHER_CONSUMER =
            DocumentId.of("other-consumer");
    private static final DocumentId SOURCE = DocumentId.of("source");
    private static final DocumentId SECOND_SOURCE =
            DocumentId.of("second-source");
    private static final String POLICY = hash(1L);

    @Test
    void oneConsumerOwnsOneFenceAndTheNextOccurrenceIsRebasedAfterCommit() {
        // given
        ExactValue sourceZero = exact("source-0");
        ExactValue sourceOne = exact("source-1");
        ExactValue sourceTwo = exact("source-2");
        ManagedEpochReceipt epochOne = receipt(
                1L, sourceZero, sourceOne, 10L);
        ManagedEpochReceipt epochTwo = receipt(
                2L, sourceOne, sourceTwo, 20L);
        Map<Long, ManagedEpochReceipt> receipts = Map.of(
                1L, epochOne,
                2L, epochTwo);
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        pending("/second", sourceZero.blueId()),
                        pending("/first", sourceZero.blueId())));
        ExactValue consumerOne = exact("consumer-1");

        // when
        ManagedCatchUpPlanner.PlanningResult initial =
                ManagedCatchUpPlanner.afterPublication(
                        CatchUpPlanStore.empty(),
                        ManagedOccurrenceInventory.empty(),
                        inventory,
                        List.of(CONSUMER),
                        List.of(),
                        hash(30L),
                        order(30L),
                        document -> document.equals(SOURCE)
                                ? new ManagedCatchUpPlanner.Head(
                                        2L, sourceTwo.blueId())
                                : new ManagedCatchUpPlanner.Head(
                                        1L, consumerOne.blueId()),
                        (document, epoch) -> document.equals(SOURCE)
                                ? receipts.get(epoch) : null,
                        ignored -> 1L);

        CatchUpPlanStore plans = initial.plans();
        ManagedEpochApplicationWork first = plans.nextDueWork().work();
        ExactValue consumerTwo = exact("consumer-2");
        CatchUpPlanStore afterFirst = plans.withCommittedApplication(
                first,
                ManagedEpochApplicationReceipt.identified(
                        first.workIdentity(),
                        first.planIdentity(),
                        first.sourceReceiptIdentity(),
                        hash(40L),
                        hash(41L),
                        hash(42L),
                        CONSUMER,
                        2L,
                        hash(43L),
                        consumerTwo.blueId(),
                        2L));
        ManagedCatchUpPlanner.PlanningResult rebound =
                ManagedCatchUpPlanner.afterPublication(
                        afterFirst,
                        inventory,
                        inventory,
                        List.of(CONSUMER),
                        List.of(),
                        hash(44L),
                        order(44L),
                        document -> document.equals(SOURCE)
                                ? new ManagedCatchUpPlanner.Head(
                                        2L, sourceTwo.blueId())
                                : new ManagedCatchUpPlanner.Head(
                                        2L, consumerTwo.blueId()),
                        (document, epoch) -> document.equals(SOURCE)
                                ? receipts.get(epoch) : null,
                        ignored -> 2L);

        ManagedEpochApplicationWork second = rebound.plans()
                .nextDueWork().work();

        // then
        assertEquals(2, plans.planCount());
        assertEquals(1, plans.dueWorkCount(),
                "parallel occurrence plans must not freeze the same head");
        assertEquals("/first", first.targetPath());
        assertEquals(1L, first.expectedConsumerCommittedEpoch());
        assertEquals(1L, first.sourceEpoch());
        assertEquals(1, plans.plansForConsumer(CONSUMER).plans().stream()
                .filter(plan -> plans.pendingWorkForPlan(
                        plan.planIdentity()).found())
                .count());
        assertEquals(0, afterFirst.dueWorkCount());
        assertEquals(1, rebound.plans().dueWorkCount());
        assertEquals("/second", second.targetPath(),
                "the untouched occurrence still needs the earlier receipt");
        assertEquals(1L, second.sourceEpoch());
        assertEquals(2L, second.expectedConsumerCommittedEpoch());
        assertEquals(consumerTwo.blueId(),
                second.expectedConsumerCommittedBlueId());
        assertEquals(2L, second.expectedGraphGeneration());
        assertFalse(rebound.plans().pendingWorkForPlan(
                first.planIdentity()).found());
        assertTrue(rebound.plans().pendingWorkForPlan(
                second.planIdentity()).found());
    }

    @Test
    void waitingOrBlockedSiblingGatesOnlyItsImpactedConsumer() {
        // given
        ExactValue firstZero = exact("first-source-0");
        ExactValue firstOne = exact("first-source-1");
        ExactValue secondZero = exact("second-source-0");
        ExactValue secondOne = exact("second-source-1");
        ManagedEpochReceipt firstReceipt = receipt(
                SOURCE, 1L, firstZero, firstOne, 10L);
        ManagedEpochReceipt secondReceipt = receipt(
                SECOND_SOURCE, 1L, secondZero, secondOne, 20L);
        ManagedOccurrenceInventory initialInventory =
                ManagedOccurrenceInventory.of(List.of(
                        pending(
                                CONSUMER,
                                SOURCE,
                                "/first",
                                firstZero.blueId()),
                        pending(
                                CONSUMER,
                                SECOND_SOURCE,
                                "/second",
                                secondZero.blueId())));
        ExactValue consumerZero = exact("consumer-0");
        ManagedCatchUpPlanner.PlanningResult initial =
                ManagedCatchUpPlanner.afterPublication(
                        CatchUpPlanStore.empty(),
                        ManagedOccurrenceInventory.empty(),
                        initialInventory,
                        List.of(CONSUMER),
                        List.of(),
                        hash(500L),
                        order(500L),
                        document -> {
                            if (document.equals(SOURCE)) {
                                return new ManagedCatchUpPlanner.Head(
                                        1L, firstOne.blueId());
                            }
                            if (document.equals(SECOND_SOURCE)) {
                                return new ManagedCatchUpPlanner.Head(
                                        1L, secondOne.blueId());
                            }
                            return new ManagedCatchUpPlanner.Head(
                                    0L, consumerZero.blueId());
                        },
                        (document, epoch) -> {
                            if (document.equals(SOURCE) && epoch == 1L) {
                                return firstReceipt;
                            }
                            if (document.equals(SECOND_SOURCE)
                                    && epoch == 1L) {
                                return secondReceipt;
                            }
                            return null;
                        },
                        ignored -> 1L);
        ManagedEpochApplicationWork failedWork =
                initial.plans().nextDueWork().work();
        CatchUpPlanStore waiting = initial.plans().withEvidenceFailure(
                ManagedEpochEvidenceException.waiting(
                        failedWork,
                        "TEST_HISTORY_MISSING",
                        "The first source receipt is unavailable"));
        CatchUpPlanStore blocked = initial.plans().withEvidenceFailure(
                ManagedEpochEvidenceException.blocked(
                        failedWork,
                        "TEST_HISTORY_INVALID",
                        "The first source receipt is invalid"));
        ManagedOccurrenceInventory withOtherConsumer =
                ManagedOccurrenceInventory.of(List.of(
                        pending(
                                CONSUMER,
                                SOURCE,
                                "/first",
                                firstZero.blueId()),
                        pending(
                                CONSUMER,
                                SECOND_SOURCE,
                                "/second",
                                secondZero.blueId()),
                        pending(
                                OTHER_CONSUMER,
                                SECOND_SOURCE,
                                "/other",
                                secondZero.blueId())));
        ExactValue otherConsumerZero = exact("other-consumer-0");
        ManagedCatchUpPlanner.HeadLookup heads = document -> {
            if (document.equals(SOURCE)) {
                return new ManagedCatchUpPlanner.Head(
                        1L, firstOne.blueId());
            }
            if (document.equals(SECOND_SOURCE)) {
                return new ManagedCatchUpPlanner.Head(
                        1L, secondOne.blueId());
            }
            return new ManagedCatchUpPlanner.Head(
                    0L,
                    document.equals(OTHER_CONSUMER)
                            ? otherConsumerZero.blueId()
                            : consumerZero.blueId());
        };
        ManagedCatchUpPlanner.ReceiptLookup repairedReceipts =
                (document, epoch) -> document.equals(SECOND_SOURCE)
                                && epoch == 1L
                        ? secondReceipt
                        : null;

        // when
        ManagedCatchUpPlanner.PlanningResult afterWaiting =
                ManagedCatchUpPlanner.afterPublication(
                        waiting,
                        initialInventory,
                        withOtherConsumer,
                        List.of(OTHER_CONSUMER),
                        List.of(secondReceipt),
                        hash(501L),
                        order(501L),
                        heads,
                        repairedReceipts,
                        ignored -> 2L);
        ManagedCatchUpPlanner.PlanningResult afterBlocked =
                ManagedCatchUpPlanner.afterPublication(
                        blocked,
                        initialInventory,
                        withOtherConsumer,
                        List.of(OTHER_CONSUMER),
                        List.of(secondReceipt),
                        hash(501L),
                        order(501L),
                        heads,
                        repairedReceipts,
                        ignored -> 2L);

        // then
        assertEquals(SOURCE, failedWork.sourceDocumentId(),
                "the lower source order owns the failed canonical slot");
        assertConsumerGate(
                afterWaiting.plans(), ManagedCatchUpBarrierStatus
                        .WAITING_FOR_HISTORY);
        assertConsumerGate(
                afterBlocked.plans(), ManagedCatchUpBarrierStatus.BLOCKED);
    }

    private static ManagedOccurrenceBinding pending(
            String path,
            String expectedBlueId) {
        return pending(CONSUMER, SOURCE, path, expectedBlueId);
    }

    private static ManagedOccurrenceBinding pending(
            DocumentId consumer,
            DocumentId source,
            String path,
            String expectedBlueId) {
        return ManagedOccurrenceBinding.derived(
                POLICY,
                new blue.language.processor.closure.DocumentId(
                        consumer.value()),
                ScopeAddress.embedded(path, 1L),
                new blue.language.processor.closure.DocumentId(
                        source.value()),
                expectedBlueId,
                false,
                0L);
    }

    private static ManagedEpochReceipt receipt(
            long epoch,
            ExactValue before,
            ExactValue after,
            long order) {
        return receipt(SOURCE, epoch, before, after, order);
    }

    private static ManagedEpochReceipt receipt(
            DocumentId source,
            long epoch,
            ExactValue before,
            ExactValue after,
            long order) {
        return ManagedEpochReceipt.identified(
                source,
                epoch,
                DocumentRevision.Kind.TIMELINE_ENTRY,
                before.blueId(),
                after,
                hash(100L + epoch),
                null,
                order(order),
                hash(200L + epoch),
                hash(300L + epoch),
                List.of(),
                epoch);
    }

    private static void assertConsumerGate(
            CatchUpPlanStore plans,
            ManagedCatchUpBarrierStatus expectedBarrierStatus) {
        assertEquals(1, plans.dueWorkCount(),
                "only the unrelated consumer may own new due work");
        assertEquals(OTHER_CONSUMER,
                plans.nextDueWork().work().consumerDocumentId());
        assertTrue(plans.plansForConsumer(CONSUMER).plans().stream()
                .noneMatch(plan -> plans.pendingWorkForPlan(
                        plan.planIdentity()).found()));
        assertTrue(plans.plansForConsumer(CONSUMER).plans().stream()
                .filter(plan -> plan.sourceDocumentId().equals(SECOND_SOURCE))
                .allMatch(plan -> plan.status()
                        == ManagedCatchUpStatus.PENDING));
        assertTrue(plans.activeBarriersForConsumer(CONSUMER).barriers().stream()
                .anyMatch(barrier -> barrier.status()
                        == expectedBarrierStatus));
        assertTrue(plans.plansForConsumer(OTHER_CONSUMER).plans().stream()
                .anyMatch(plan -> plans.pendingWorkForPlan(
                        plan.planIdentity()).found()));
        plans.assertStructurallyValid();
    }

    private static ExactValue exact(String state) {
        return ExactValue.verified(new Node().properties(
                "state", new Node().value(state)));
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(List.of(value));
    }

    private static String hash(long value) {
        return "sha256:" + String.format("%064x", value);
    }
}
