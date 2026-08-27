package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CatchUpPlanStoreTest {

    @Test
    void applicationIsResponseLossSafeAndCompletePlanStaysClosed() {
        // given
        Fixture fixture = fixture(
                1L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                1L,
                0L,
                10L,
                20L);
        CatchUpPlanStore due = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier())
                .withWork(fixture.work(), fixture.sourceReceipt());
        ManagedEpochApplicationReceipt application = application(
                fixture.work(), 100L, 1L);

        // when
        CatchUpPlanStore committed = due.withCommittedApplication(
                fixture.work(), application);
        CatchUpPlanStore extended = committed.withExtendedSourceFrontier(
                fixture.plan().sourceDocumentId(), 1L);
        ManagedOccurrenceCatchUpPlan stillComplete = extended.plan(
                fixture.plan().planIdentity()).plan();

        // then
        assertTrue(due.hasActiveBarriers());
        assertTrue(due.hasActivePlanForSource(
                fixture.plan().sourceDocumentId()));
        assertEquals(ManagedCatchUpStatus.COMPLETE,
                committed.plan(fixture.plan().planIdentity())
                        .plan().status());
        assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                committed.barrier(fixture.barrier().barrierIdentity())
                        .barrier().status());
        assertEquals(0, committed.dueWorkCount());
        assertFalse(committed.hasActiveBarriers());
        assertFalse(committed.hasActivePlanForSource(
                fixture.plan().sourceDocumentId()));
        assertEquals(
                application.applicationReceiptIdentity(),
                committed.applicationByWork(fixture.work().workIdentity())
                        .receipt().applicationReceiptIdentity());
        assertSame(committed, committed.withCommittedApplication(
                fixture.work(), application),
                "response-loss reconciliation must not advance twice");

        assertSame(committed, extended,
                "future live epochs use ordinary active-occurrence flow");
        assertEquals(1L, stillComplete.nextSourceEpoch());
        assertEquals(0L, stillComplete.requiredThroughSourceEpoch());
        assertEquals(ManagedCatchUpStatus.COMPLETE, stillComplete.status());
        assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                extended.barrier(fixture.barrier().barrierIdentity())
                        .barrier().status());
        due.assertStructurallyValid();
        committed.assertStructurallyValid();
    }

    @Test
    void duplicateOccurrencesOwnIndependentCursorsAndCanonicalDueOrder() {
        // given
        DocumentId consumer = DocumentId.of("consumer");
        DocumentId source = DocumentId.of("source");
        String cause = hash(500L);
        ExternalOrderKey causeOrder = order(10L);
        ManagedCatchUpBarrier definition = ManagedCatchUpBarrier.identified(
                consumer,
                cause,
                causeOrder,
                List.of(),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);
        ExactValue admitted = exact("admitted");
        ManagedOccurrenceCatchUpPlan second = plan(
                definition.barrierIdentity(),
                consumer,
                source,
                hash(501L),
                "/second",
                1L,
                admitted,
                0L,
                cause);
        ManagedOccurrenceCatchUpPlan first = plan(
                definition.barrierIdentity(),
                consumer,
                source,
                hash(502L),
                "/first",
                1L,
                admitted,
                0L,
                cause);
        ManagedCatchUpBarrier barrier = ManagedCatchUpBarrier.identified(
                consumer,
                cause,
                causeOrder,
                List.of(second.planIdentity(), first.planIdentity()),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);
        ManagedEpochReceipt sourceReceipt = sourceReceipt(
                source, 0L, admitted.blueId(), exact("source-zero"), 510L,
                order(20L));
        ManagedEpochApplicationWork secondWork = work(
                second, sourceReceipt, 520L);
        ManagedEpochApplicationWork firstWork = work(
                first, sourceReceipt, 521L);

        // when
        CatchUpPlanStore store = CatchUpPlanStore.empty()
                .withPlan(second)
                .withPlan(first)
                .withBarrier(barrier)
                .withWork(secondWork, sourceReceipt)
                .withWork(firstWork, sourceReceipt);
        CatchUpPlanStore beforeFirst = store;
        IllegalArgumentException overtaking = assertThrows(
                IllegalArgumentException.class,
                () -> beforeFirst.withCommittedApplication(
                        secondWork, application(secondWork, 529L, 1L)));
        CatchUpPlanStore oneApplied = store.withCommittedApplication(
                firstWork, application(firstWork, 530L, 1L));

        // then
        assertEquals(firstWork.workIdentity(),
                store.nextDueWork().work().workIdentity(),
                "target path is the canonical final occurrence tie-breaker");
        assertFalse(overtaking.getMessage().isBlank());
        assertEquals(1L,
                oneApplied.plan(first.planIdentity()).plan().nextSourceEpoch());
        assertEquals(0L,
                oneApplied.plan(second.planIdentity()).plan().nextSourceEpoch());
        assertEquals(secondWork.workIdentity(),
                oneApplied.nextDueWork().work().workIdentity());
        assertEquals(2,
                oneApplied.plansForSource(source).planRowsRead());
        assertTrue(oneApplied.hasActivePlanForSource(source),
                "the second occurrence still retains the source lane");
        assertEquals(0,
                oneApplied.plansForSource(source).unrelatedPlanReads());
    }

    @Test
    void occurrenceRetirementIsGenerationSafeAndCancelsPendingWork() {
        // given
        Fixture fixture = fixture(
                30L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                2L,
                0L,
                10L,
                20L);
        CatchUpPlanStore store = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier())
                .withWork(fixture.work(), fixture.sourceReceipt());

        // when
        IllegalArgumentException staleGeneration = assertThrows(
                IllegalArgumentException.class,
                () -> store.withRetiredOccurrence(
                        fixture.plan().targetOccurrenceIdentity(), 1L));
        CatchUpPlanStore retired = store.withRetiredOccurrence(
                fixture.plan().targetOccurrenceIdentity(), 2L);

        // then
        assertFalse(staleGeneration.getMessage().isBlank());
        assertEquals(ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED,
                retired.plan(fixture.plan().planIdentity()).plan().status());
        assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                retired.barrier(fixture.barrier().barrierIdentity())
                        .barrier().status());
        assertEquals(0, retired.dueWorkCount());
        assertFalse(retired.hasActivePlanForSource(
                fixture.plan().sourceDocumentId()));
        assertSame(retired, retired.withRetiredOccurrence(
                fixture.plan().targetOccurrenceIdentity(), 2L));
    }

    @Test
    void missingReceiptPublishesWaitingEvidenceAndWorkResumesIt() {
        // given
        Fixture fixture = fixture(
                45L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                1L,
                0L,
                10L,
                20L);
        CatchUpPlanStore pending = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier());

        // when
        CatchUpPlanStore waiting = pending.withWaitingForHistory(
                fixture.plan().planIdentity(),
                "MISSING_MANAGED_EPOCH_RECEIPT",
                "Required source epoch zero is not durable");

        ManagedOccurrenceCatchUpPlan waitingPlan = waiting.plan(
                fixture.plan().planIdentity()).plan();
        CatchUpPlanStore resumed = waiting.withWork(
                fixture.work(), fixture.sourceReceipt());

        // then
        assertEquals(ManagedCatchUpStatus.WAITING_FOR_HISTORY,
                waitingPlan.status());
        assertEquals("MISSING_MANAGED_EPOCH_RECEIPT",
                waitingPlan.waitingCode().orElseThrow());
        assertEquals(ManagedCatchUpBarrierStatus.WAITING_FOR_HISTORY,
                waiting.barrier(fixture.barrier().barrierIdentity())
                        .barrier().status());
        assertSame(waiting, waiting.withWaitingForHistory(
                fixture.plan().planIdentity(),
                "MISSING_MANAGED_EPOCH_RECEIPT",
                "Required source epoch zero is not durable"));

        assertEquals(ManagedCatchUpStatus.RUNNING,
                resumed.plan(fixture.plan().planIdentity()).plan().status());
        assertEquals(ManagedCatchUpBarrierStatus.OPEN,
                resumed.barrier(fixture.barrier().barrierIdentity())
                        .barrier().status());
        assertEquals(fixture.work().workIdentity(),
                resumed.nextDueWork().work().workIdentity());
    }

    @Test
    void evidenceRepairRequeuesTheRegisteredWorkWithoutReplacingItsAudit() {
        // given
        Fixture fixture = fixture(
                50L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                1L,
                0L,
                10L,
                20L);
        CatchUpPlanStore due = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier())
                .withWork(fixture.work(), fixture.sourceReceipt());
        CatchUpPlanStore waiting = due.withEvidenceFailure(
                ManagedEpochEvidenceException.waiting(
                        fixture.work(),
                        "TEST_EVIDENCE_MISSING",
                        "The immutable evidence is temporarily unavailable"));
        ManagedEpochApplicationWork registered = waiting.work(
                fixture.work().workIdentity()).work();

        // when
        CatchUpPlanStore resumed = waiting.withWork(
                fixture.work(), fixture.sourceReceipt());

        // then
        assertEquals(0, waiting.dueWorkCount());
        assertEquals(1, waiting.workCount());
        assertTrue(fixture.sourceReceipt().beforeBlueId().isEmpty(),
                "epoch zero exposes no durable before-receipt state");
        assertSame(fixture.work(), registered,
                "evidence failure retains the immutable work audit row");
        assertEquals(1, resumed.workCount());
        assertEquals(1, resumed.dueWorkCount());
        assertSame(registered,
                resumed.work(fixture.work().workIdentity()).work());
        assertEquals(fixture.work().workIdentity(),
                resumed.pendingWorkForPlan(
                        fixture.plan().planIdentity()).work().workIdentity());
        assertEquals(fixture.work().workIdentity(),
                resumed.nextDueWork().work().workIdentity());
        assertEquals(ManagedCatchUpStatus.RUNNING,
                resumed.plan(fixture.plan().planIdentity()).plan().status());
        resumed.assertStructurallyValid();
    }

    @Test
    void staleRegisteredWorkCannotReplaceAnotherPendingAttempt() {
        // given
        Fixture fixture = fixture(
                55L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                1L,
                0L,
                10L,
                20L);
        CatchUpPlanStore waiting = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier())
                .withWork(fixture.work(), fixture.sourceReceipt())
                .withEvidenceFailure(ManagedEpochEvidenceException.waiting(
                        fixture.work(),
                        "TEST_EVIDENCE_MISSING",
                        "The immutable evidence is temporarily unavailable"));
        ManagedEpochApplicationWork replacement =
                ManagedEpochApplicationWork.identified(
                        fixture.work().planIdentity(),
                        fixture.work().barrierIdentity(),
                        fixture.work().sourceReceiptIdentity(),
                        fixture.work().sourceDocumentId(),
                        fixture.work().sourceEpoch(),
                        fixture.work().consumerDocumentId(),
                        fixture.work().targetOccurrenceIdentity(),
                        fixture.work().targetPath(),
                        fixture.work().activationGeneration(),
                        1L,
                        exact("replacement-consumer").blueId(),
                        1L);
        CatchUpPlanStore replacementDue = waiting.withWork(
                replacement, fixture.sourceReceipt());

        // when
        IllegalStateException inconsistent = assertThrows(
                IllegalStateException.class,
                () -> replacementDue.withWork(
                        fixture.work(), fixture.sourceReceipt()));

        // then
        assertFalse(inconsistent.getMessage().isBlank());
        assertEquals(2, replacementDue.workCount(),
                "both immutable attempt rows remain available for audit");
        assertEquals(replacement.workIdentity(),
                replacementDue.nextDueWork().work().workIdentity());
        replacementDue.assertStructurallyValid();
    }

    @Test
    void retirementKeepsCompleteAuditAndCancelsBlockedPlan() {
        // given
        Fixture completedFixture = fixture(
                56L,
                DocumentId.of("complete-consumer"),
                DocumentId.of("complete-source"),
                "/complete",
                1L,
                0L,
                10L,
                20L);
        CatchUpPlanStore completed = CatchUpPlanStore.empty()
                .withPlan(completedFixture.plan())
                .withBarrier(completedFixture.barrier())
                .withWork(
                        completedFixture.work(),
                        completedFixture.sourceReceipt())
                .withCommittedApplication(
                        completedFixture.work(),
                        application(completedFixture.work(), 560L, 1L));
        String completeSnapshot = completed.plan(
                completedFixture.plan().planIdentity())
                .plan().snapshotIdentity();

        Fixture blockedFixture = fixture(
                57L,
                DocumentId.of("blocked-consumer"),
                DocumentId.of("blocked-source"),
                "/blocked",
                1L,
                0L,
                11L,
                21L);
        CatchUpPlanStore blocked = CatchUpPlanStore.empty()
                .withPlan(blockedFixture.plan())
                .withBarrier(blockedFixture.barrier())
                .withWork(
                        blockedFixture.work(), blockedFixture.sourceReceipt())
                .withEvidenceFailure(ManagedEpochEvidenceException.blocked(
                        blockedFixture.work(),
                        "TEST_EVIDENCE_INVALID",
                        "The immutable evidence is invalid"));

        // when
        CatchUpPlanStore completeAfterDetach = completed.withRetiredOccurrence(
                completedFixture.plan().targetOccurrenceIdentity(), 1L);
        CatchUpPlanStore blockedAfterDetach = blocked.withRetiredOccurrence(
                blockedFixture.plan().targetOccurrenceIdentity(), 1L);

        // then
        assertSame(completed, completeAfterDetach,
                "completed plan snapshots are immutable historical audit");
        assertEquals(completeSnapshot, completeAfterDetach.plan(
                completedFixture.plan().planIdentity())
                .plan().snapshotIdentity());
        assertEquals(ManagedCatchUpStatus.COMPLETE, completeAfterDetach.plan(
                completedFixture.plan().planIdentity()).plan().status());
        assertEquals(ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED,
                blockedAfterDetach.plan(
                        blockedFixture.plan().planIdentity()).plan().status());
        assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                blockedAfterDetach.barrier(
                        blockedFixture.barrier().barrierIdentity())
                        .barrier().status());
        assertEquals(0, blockedAfterDetach.dueWorkCount());
        blockedAfterDetach.assertStructurallyValid();
    }

    @Test
    void cursorAndBarrierMismatchesFailClosed() {
        // given
        Fixture fixture = fixture(
                60L,
                DocumentId.of("consumer"),
                DocumentId.of("source"),
                "/child",
                1L,
                1L,
                10L,
                20L);
        CatchUpPlanStore store = CatchUpPlanStore.empty()
                .withPlan(fixture.plan())
                .withBarrier(fixture.barrier())
                .withWork(fixture.work(), fixture.sourceReceipt());
        ManagedEpochApplicationReceipt wrongCursor = application(
                fixture.work(), 61L, 2L);

        // when
        IllegalArgumentException cursorFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.withCommittedApplication(
                        fixture.work(), wrongCursor));
        ManagedOccurrenceCatchUpPlan skipped = ManagedOccurrenceCatchUpPlan
                .identified(
                        fixture.plan().barrierIdentity(),
                        fixture.plan().consumerDocumentId(),
                        fixture.plan().targetOccurrenceIdentity(),
                        fixture.plan().targetPath(),
                        fixture.plan().activationGeneration(),
                        fixture.plan().sourceDocumentId(),
                        fixture.plan().admittedSourceEpoch(),
                        fixture.plan().admittedSourceBlueId(),
                        1L,
                        fixture.plan().requiredThroughSourceEpoch(),
                        fixture.plan().causedByIdentity(),
                        ManagedCatchUpStatus.RUNNING,
                        null,
                        null);
        IllegalArgumentException skipFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.withPlan(skipped));

        // then
        assertFalse(cursorFailure.getMessage().isBlank());
        assertFalse(skipFailure.getMessage().isBlank());
    }

    @Test
    void oneThousandUnrelatedPlansDoNotExpandDueSelectionOrSourceAudit() {
        // given
        CatchUpPlanStore store = CatchUpPlanStore.empty();
        for (int index = 0; index < 1_000; index++) {
            Fixture ambient = fixture(
                    1_000L + index,
                    DocumentId.of("ambient-consumer-" + index),
                    DocumentId.of("ambient-source-" + index),
                    "/child",
                    1L,
                    0L,
                    1_000L + index,
                    1_000L + index);
            store = store
                    .withPlan(ambient.plan())
                    .withBarrier(ambient.barrier())
                    .withWork(ambient.work(), ambient.sourceReceipt());
        }
        Fixture selected = fixture(
                9_000L,
                DocumentId.of("selected-consumer"),
                DocumentId.of("selected-source"),
                "/selected",
                1L,
                0L,
                0L,
                0L);
        store = store
                .withPlan(selected.plan())
                .withBarrier(selected.barrier())
                .withWork(selected.work(), selected.sourceReceipt());

        // when
        ManagedCatchUpWorkIndex.DueWorkRead due = store.nextDueWork();
        ManagedCatchUpPlanIndex.PlanAudit sourceAudit = store.plansForSource(
                selected.plan().sourceDocumentId());

        // then
        assertEquals(1_001, store.planCount());
        assertEquals(selected.work().workIdentity(),
                due.work().workIdentity());
        assertTrue(due.indexRowsRead() < 32, due::toString);
        assertEquals(1, due.workRowsRead());
        assertEquals(0, due.unrelatedPlanReads());
        assertEquals(1, sourceAudit.planRowsRead());
        assertEquals(0, sourceAudit.unrelatedPlanReads());
        assertTrue(sourceAudit.indexComparisons() < 32,
                sourceAudit::toString);
        assertTrue(store.lastMutationNodeCopies() < 128,
                "one mutation must path-copy bounded exact indexes");
        store.assertStructurallyValid();
    }

    private static Fixture fixture(
            long seed,
            DocumentId consumer,
            DocumentId source,
            String targetPath,
            long activationGeneration,
            long requiredThroughEpoch,
            long causeOrder,
            long sourceOrder) {
        String cause = hash(seed + 10_000L);
        ManagedCatchUpBarrier definition = ManagedCatchUpBarrier.identified(
                consumer,
                cause,
                order(causeOrder),
                List.of(),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);
        ExactValue admitted = exact("admitted-" + seed);
        ManagedOccurrenceCatchUpPlan plan = plan(
                definition.barrierIdentity(),
                consumer,
                source,
                hash(seed + 20_000L),
                targetPath,
                activationGeneration,
                admitted,
                requiredThroughEpoch,
                cause);
        ManagedCatchUpBarrier barrier = ManagedCatchUpBarrier.identified(
                consumer,
                cause,
                order(causeOrder),
                List.of(plan.planIdentity()),
                ManagedCatchUpBarrierStatus.OPEN,
                null,
                null);
        ManagedEpochReceipt sourceReceipt = sourceReceipt(
                source,
                0L,
                admitted.blueId(),
                exact("source-zero-" + seed),
                seed + 30_000L,
                order(sourceOrder));
        return new Fixture(
                plan,
                barrier,
                sourceReceipt,
                work(plan, sourceReceipt, seed + 40_000L));
    }

    private static ManagedOccurrenceCatchUpPlan plan(
            String barrierIdentity,
            DocumentId consumer,
            DocumentId source,
            String occurrenceIdentity,
            String targetPath,
            long activationGeneration,
            ExactValue admitted,
            long requiredThroughEpoch,
            String causeIdentity) {
        return ManagedOccurrenceCatchUpPlan.identified(
                barrierIdentity,
                consumer,
                occurrenceIdentity,
                targetPath,
                activationGeneration,
                source,
                -1L,
                admitted.blueId(),
                0L,
                requiredThroughEpoch,
                causeIdentity,
                ManagedCatchUpStatus.PENDING,
                null,
                null);
    }

    private static ManagedEpochReceipt sourceReceipt(
            DocumentId source,
            long epoch,
            String beforeBlueId,
            ExactValue after,
            long seed,
            ExternalOrderKey sourceOrder) {
        return ManagedEpochReceipt.identified(
                source,
                epoch,
                epoch == 0L
                        ? DocumentRevision.Kind.INITIALIZATION
                        : DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                epoch == 0L ? null : beforeBlueId,
                after,
                hash(seed),
                null,
                sourceOrder,
                hash(seed + 1L),
                hash(seed + 2L),
                List.of(),
                seed);
    }

    private static ManagedEpochApplicationWork work(
            ManagedOccurrenceCatchUpPlan plan,
            ManagedEpochReceipt sourceReceipt,
            long seed) {
        return ManagedEpochApplicationWork.identified(
                plan.planIdentity(),
                plan.barrierIdentity(),
                sourceReceipt.receiptIdentity(),
                plan.sourceDocumentId(),
                sourceReceipt.epoch(),
                plan.consumerDocumentId(),
                plan.targetOccurrenceIdentity(),
                plan.targetPath(),
                plan.activationGeneration(),
                0L,
                exact("consumer-" + seed).blueId(),
                0L);
    }

    private static ManagedEpochApplicationReceipt application(
            ManagedEpochApplicationWork work,
            long seed,
            long resultingCursor) {
        return ManagedEpochApplicationReceipt.identified(
                work.workIdentity(),
                work.planIdentity(),
                work.sourceReceiptIdentity(),
                hash(seed),
                hash(seed + 1L),
                hash(seed + 2L),
                work.consumerDocumentId(),
                seed,
                hash(seed + 3L),
                exact("committed-" + seed).blueId(),
                resultingCursor);
    }

    private static ExactValue exact(String value) {
        return ExactValue.verified(new Node().properties(
                "state", new Node().value(value)));
    }

    private static ExternalOrderKey order(long value) {
        return ExternalOrderKey.of(List.of(value));
    }

    private static String hash(long value) {
        return "sha256:" + String.format("%064x", value);
    }

    private record Fixture(
            ManagedOccurrenceCatchUpPlan plan,
            ManagedCatchUpBarrier barrier,
            ManagedEpochReceipt sourceReceipt,
            ManagedEpochApplicationWork work) { }
}
