package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable occurrence-specific catch-up plans, barriers, work, and receipts.
 *
 * <p>The store deliberately does not own document state. A caller prepares a
 * complete replacement containing this value and the consumer publication,
 * then swaps both together. This value enforces every cursor and receipt edge
 * before that outer transaction can become durable.</p>
 */
final class CatchUpPlanStore {
    private static final CatchUpPlanStore EMPTY = new CatchUpPlanStore(
            ManagedCatchUpPlanIndex.empty(),
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
            ManagedCatchUpWorkIndex.empty(),
            0,
            0,
            0);

    private final ManagedCatchUpPlanIndex plans;
    private final PersistentOrderedMap<String, ManagedCatchUpBarrier> barriers;
    private final ManagedCatchUpWorkIndex work;
    private final int activeBarrierCount;
    private final int lastMutationComparisons;
    private final int lastMutationNodeCopies;

    private CatchUpPlanStore(
            ManagedCatchUpPlanIndex plans,
            PersistentOrderedMap<String,
                    ManagedCatchUpBarrier> barriers,
            ManagedCatchUpWorkIndex work,
            int activeBarrierCount,
            int lastMutationComparisons,
            int lastMutationNodeCopies) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.barriers = Objects.requireNonNull(barriers, "barriers");
        this.work = Objects.requireNonNull(work, "work");
        this.activeBarrierCount = requireNonNegative(
                activeBarrierCount, "activeBarrierCount");
        if (activeBarrierCount > barriers.size()) {
            throw new IllegalArgumentException(
                    "activeBarrierCount exceeds retained barriers");
        }
        this.lastMutationComparisons = requireNonNegative(
                lastMutationComparisons, "lastMutationComparisons");
        this.lastMutationNodeCopies = requireNonNegative(
                lastMutationNodeCopies, "lastMutationNodeCopies");
    }

    static CatchUpPlanStore empty() {
        return EMPTY;
    }

    CatchUpPlanStore withPlan(ManagedOccurrenceCatchUpPlan plan) {
        ManagedCatchUpPlanIndex changed = plans.withPlan(
                Objects.requireNonNull(plan, "plan"), false);
        if (changed == plans) {
            return this;
        }
        CatchUpPlanStore updated = new CatchUpPlanStore(
                changed,
                barriers,
                work,
                activeBarrierCount,
                changed.lastMutationComparisons(),
                changed.lastMutationNodeCopies());
        return updated.reconcileExistingBarrier(plan.barrierIdentity());
    }

    CatchUpPlanStore withBarrier(ManagedCatchUpBarrier barrier) {
        ManagedCatchUpBarrier selected = Objects.requireNonNull(
                barrier, "barrier");
        ManagedCatchUpBarrier canonical = canonicalBarrier(selected);
        if (!canonical.snapshotIdentity().equals(
                selected.snapshotIdentity())) {
            throw new IllegalArgumentException(
                    "Catch-up barrier status is not complete for its plans");
        }
        PersistentOrderedMap.ReadResult<ManagedCatchUpBarrier> read =
                barriers.read(selected.barrierIdentity());
        if (read.found()) {
            requireSameBarrierDefinition(read.value(), selected);
            requireMemberExtension(read.value(), selected);
            if (read.value().snapshotIdentity().equals(
                    selected.snapshotIdentity())) {
                return this;
            }
        }
        PersistentOrderedMap.Mutation<String, ManagedCatchUpBarrier> mutation =
                barriers.put(selected.barrierIdentity(), selected);
        int nextActiveBarrierCount = Math.addExact(
                activeBarrierCount,
                activeDelta(read.value(), selected));
        return new CatchUpPlanStore(
                plans,
                mutation.map(),
                work,
                nextActiveBarrierCount,
                Math.addExact(read.comparisons(), mutation.comparisons()),
                mutation.copiedNodes());
    }

    /** Publishes exact same-cursor evidence for a missing required receipt. */
    CatchUpPlanStore withWaitingForHistory(
            String planIdentity, String waitingCode, String waitingMessage) {
        ManagedOccurrenceCatchUpPlan plan = requirePlan(Objects.requireNonNull(
                planIdentity, "planIdentity"));
        if (plan.status() == ManagedCatchUpStatus.BLOCKED
                || plan.status() == ManagedCatchUpStatus.COMPLETE
                || plan.status()
                        == ManagedCatchUpStatus
                                .CANCELLED_OCCURRENCE_RETIRED) {
            throw new IllegalArgumentException(
                    "A terminal catch-up plan cannot wait for history");
        }
        ManagedOccurrenceCatchUpPlan waiting = copyPlan(
                plan,
                plan.nextSourceEpoch(),
                plan.requiredThroughSourceEpoch(),
                ManagedCatchUpStatus.WAITING_FOR_HISTORY,
                Objects.requireNonNull(waitingCode, "waitingCode"),
                waitingMessage);
        ManagedCatchUpPlanIndex changedPlans = plans.withPlan(waiting, false);
        if (changedPlans == plans) {
            return this;
        }
        CatchUpPlanStore changed = new CatchUpPlanStore(
                changedPlans,
                barriers,
                work,
                activeBarrierCount,
                changedPlans.lastMutationComparisons(),
                changedPlans.lastMutationNodeCopies());
        return changed.reconcileExistingBarrier(plan.barrierIdentity());
    }

    /**
     * Atomically removes one selected due row and publishes its same-cursor
     * immutable-evidence failure through the owning plan and barrier.
     */
    CatchUpPlanStore withEvidenceFailure(
            ManagedEpochEvidenceException failure) {
        ManagedEpochEvidenceException selected = Objects.requireNonNull(
                failure, "failure");
        return withTerminalFailure(
                selected.work(),
                selected.planStatus(),
                selected.code(),
                selected.message(),
                "Immutable-evidence");
    }

    /** Blocks one post-PROCESS publication failure at the same cursor. */
    CatchUpPlanStore withApplicationFailure(
            ManagedEpochApplicationWork work,
            String code,
            String message) {
        return withTerminalFailure(
                Objects.requireNonNull(work, "work"),
                ManagedCatchUpStatus.BLOCKED,
                Objects.requireNonNull(code, "code"),
                Objects.requireNonNull(message, "message"),
                "Managed-application publication");
    }

    private CatchUpPlanStore withTerminalFailure(
            ManagedEpochApplicationWork selectedWork,
            ManagedCatchUpStatus status,
            String code,
            String message,
            String failureKind) {
        ManagedOccurrenceCatchUpPlan plan = requirePlan(
                selectedWork.planIdentity());
        ManagedCatchUpBarrier barrier = requireBarrier(
                selectedWork.barrierIdentity());
        requireFailureWorkMatch(selectedWork, plan, barrier);

        ManagedCatchUpWorkIndex.WorkRead registered = work.work(
                selectedWork.workIdentity());
        ManagedCatchUpWorkIndex.WorkRead pending = work.pendingWorkForPlan(
                selectedWork.planIdentity());
        if (!registered.found()
                || !registered.work().workIdentity().equals(
                        selectedWork.workIdentity())
                || !pending.found()
                || !pending.work().workIdentity().equals(
                        selectedWork.workIdentity())) {
            throw new IllegalArgumentException(
                    failureKind + " failure does not own the pending due "
                            + "work slot");
        }
        if (plan.status().terminal()) {
            throw new IllegalArgumentException(
                    "A terminal catch-up plan cannot accept a "
                            + failureKind.toLowerCase(java.util.Locale.ROOT)
                            + " failure");
        }

        ManagedOccurrenceCatchUpPlan failed = copyPlan(
                plan,
                plan.nextSourceEpoch(),
                plan.requiredThroughSourceEpoch(),
                Objects.requireNonNull(status, "status"),
                code,
                message);
        ManagedCatchUpPlanIndex changedPlans = plans.withPlan(failed, false);
        ManagedCatchUpWorkIndex changedWork =
                work.withoutPendingWorkForPlan(plan.planIdentity());
        if (changedWork == work) {
            throw new IllegalStateException(
                    failureKind + " failure did not remove its due row");
        }
        CatchUpPlanStore changed = new CatchUpPlanStore(
                changedPlans,
                barriers,
                changedWork,
                activeBarrierCount,
                sum(
                        registered.indexComparisons(),
                        pending.indexComparisons(),
                        changedPlans.lastMutationComparisons(),
                        changedWork.lastMutationComparisons()),
                sum(
                        changedPlans.lastMutationNodeCopies(),
                        changedWork.lastMutationNodeCopies()));
        return changed.reconcileExistingBarrier(plan.barrierIdentity());
    }

    /** Extends only active indexed plans for one advancing source lineage. */
    CatchUpPlanStore withExtendedSourceFrontier(
            DocumentId sourceDocumentId, long requiredThroughEpoch) {
        if (requiredThroughEpoch < 0L) {
            throw new IllegalArgumentException(
                    "requiredThroughEpoch must be non-negative");
        }
        ManagedCatchUpPlanIndex.PlanAudit audit = plans.forSource(
                Objects.requireNonNull(
                        sourceDocumentId, "sourceDocumentId"));
        CatchUpPlanStore changed = this;
        int comparisons = audit.indexComparisons();
        int copies = 0;
        boolean extendedAny = false;
        for (ManagedOccurrenceCatchUpPlan plan : audit.plans()) {
            if (requiredThroughEpoch <= plan.requiredThroughSourceEpoch()
                    || plan.status() == ManagedCatchUpStatus.COMPLETE
                    || plan.status() == ManagedCatchUpStatus.BLOCKED
                    || plan.status()
                            == ManagedCatchUpStatus
                                    .CANCELLED_OCCURRENCE_RETIRED) {
                continue;
            }
            ManagedOccurrenceCatchUpPlan extended = copyPlan(
                    plan,
                    plan.nextSourceEpoch(),
                    requiredThroughEpoch,
                    plan.status(),
                    plan.waitingCode().orElse(null),
                    plan.waitingMessage().orElse(null));
            changed = changed.withPlan(extended);
            extendedAny = true;
            comparisons = Math.addExact(
                    comparisons, changed.lastMutationComparisons());
            copies = Math.addExact(copies, changed.lastMutationNodeCopies());
        }
        return extendedAny ? changed.withMetrics(comparisons, copies) : this;
    }

    /** Retires only the exact occurrence activation generation. */
    CatchUpPlanStore withRetiredOccurrence(
            String targetOccurrenceIdentity, long activationGeneration) {
        if (activationGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "activationGeneration must be positive");
        }
        ManagedCatchUpPlanIndex.PlanAudit audit = plans.forOccurrence(
                Objects.requireNonNull(
                        targetOccurrenceIdentity,
                        "targetOccurrenceIdentity"));
        for (ManagedOccurrenceCatchUpPlan plan : audit.plans()) {
            if (plan.activationGeneration() > activationGeneration
                    && plan.status()
                            != ManagedCatchUpStatus
                                    .CANCELLED_OCCURRENCE_RETIRED) {
                throw new IllegalArgumentException(
                        "A stale occurrence generation cannot cancel a later "
                                + "active plan");
            }
        }
        CatchUpPlanStore changed = this;
        int comparisons = audit.indexComparisons();
        int copies = 0;
        boolean retiredAny = false;
        for (ManagedOccurrenceCatchUpPlan plan : audit.plans()) {
            if (plan.activationGeneration() != activationGeneration
                    || plan.status() == ManagedCatchUpStatus.COMPLETE
                    || plan.status()
                            == ManagedCatchUpStatus
                                    .CANCELLED_OCCURRENCE_RETIRED) {
                continue;
            }
            ManagedOccurrenceCatchUpPlan cancelled = copyPlan(
                    plan,
                    plan.nextSourceEpoch(),
                    plan.requiredThroughSourceEpoch(),
                    ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED,
                    null,
                    null);
            changed = changed.withPlan(cancelled);
            retiredAny = true;
            ManagedCatchUpWorkIndex withoutPending =
                    changed.work.withoutPendingWorkForPlan(
                            plan.planIdentity());
            if (withoutPending != changed.work) {
                changed = new CatchUpPlanStore(
                        changed.plans,
                        changed.barriers,
                        withoutPending,
                        changed.activeBarrierCount,
                        Math.addExact(
                                changed.lastMutationComparisons(),
                                withoutPending.lastMutationComparisons()),
                        Math.addExact(
                                changed.lastMutationNodeCopies(),
                                withoutPending.lastMutationNodeCopies()));
            }
            comparisons = Math.addExact(
                    comparisons, changed.lastMutationComparisons());
            copies = Math.addExact(copies, changed.lastMutationNodeCopies());
        }
        return retiredAny ? changed.withMetrics(comparisons, copies) : this;
    }

    /** Registers one exact next-epoch work item under canonical due order. */
    CatchUpPlanStore withWork(
            ManagedEpochApplicationWork applicationWork,
            ManagedEpochReceipt sourceReceipt) {
        ManagedEpochApplicationWork selected = Objects.requireNonNull(
                applicationWork, "applicationWork");
        ManagedEpochReceipt source = Objects.requireNonNull(
                sourceReceipt, "sourceReceipt");
        ManagedCatchUpWorkIndex.ApplicationRead alreadyApplied =
                work.applicationByWork(selected.workIdentity());
        if (alreadyApplied.found()) {
            return this;
        }
        ManagedOccurrenceCatchUpPlan plan = requirePlan(
                selected.planIdentity());
        ManagedCatchUpBarrier barrier = requireBarrier(
                selected.barrierIdentity());
        requireWorkMatch(selected, source, plan, barrier);
        CatchUpPlanStore changed = this;
        int comparisons = alreadyApplied.indexComparisons();
        int copies = 0;
        if (plan.status() == ManagedCatchUpStatus.PENDING
                || plan.status() == ManagedCatchUpStatus.WAITING_FOR_HISTORY) {
            ManagedOccurrenceCatchUpPlan running = copyPlan(
                    plan,
                    plan.nextSourceEpoch(),
                    plan.requiredThroughSourceEpoch(),
                    ManagedCatchUpStatus.RUNNING,
                    null,
                    null);
            changed = changed.withPlan(running);
            comparisons = Math.addExact(
                    comparisons, changed.lastMutationComparisons());
            copies = Math.addExact(copies, changed.lastMutationNodeCopies());
            barrier = changed.requireBarrier(selected.barrierIdentity());
        }
        ManagedCatchUpWorkIndex changedWork = changed.work.withWork(
                selected, barrier, source);
        if (changedWork == changed.work) {
            return changed.withMetrics(comparisons, copies);
        }
        comparisons = Math.addExact(
                comparisons, changedWork.lastMutationComparisons());
        copies = Math.addExact(copies, changedWork.lastMutationNodeCopies());
        return new CatchUpPlanStore(
                changed.plans,
                changed.barriers,
                changedWork,
                changed.activeBarrierCount,
                comparisons,
                copies);
    }

    /** Atomically records an application and advances exactly one cursor. */
    CatchUpPlanStore withCommittedApplication(
            ManagedEpochApplicationWork applicationWork,
            ManagedEpochApplicationReceipt applicationReceipt) {
        return withCommittedApplication(
                applicationWork, applicationReceipt, Set.of());
    }

    /**
     * Commits the canonical due work outside consumers whose earlier attempt
     * failed in this drain, preserving sibling-lane independence.
     */
    CatchUpPlanStore withCommittedApplication(
            ManagedEpochApplicationWork applicationWork,
            ManagedEpochApplicationReceipt applicationReceipt,
            Set<DocumentId> excludedConsumers) {
        ManagedEpochApplicationWork selectedWork = Objects.requireNonNull(
                applicationWork, "applicationWork");
        ManagedEpochApplicationReceipt receipt = Objects.requireNonNull(
                applicationReceipt, "applicationReceipt");
        ManagedCatchUpWorkIndex.ApplicationRead existing =
                work.applicationByWork(selectedWork.workIdentity());
        if (existing.found()) {
            if (existing.receipt().applicationReceiptIdentity().equals(
                    receipt.applicationReceiptIdentity())) {
                return this;
            }
            throw new IllegalArgumentException(
                    "Work identity has a different committed application");
        }
        ManagedCatchUpWorkIndex.WorkRead workRead = work.work(
                selectedWork.workIdentity());
        if (!workRead.found()) {
            throw new IllegalArgumentException(
                    "Cannot commit unregistered catch-up work");
        }
        ManagedCatchUpWorkIndex.DueWorkRead canonicalDue =
                work.nextDueWorkExcluding(Objects.requireNonNull(
                        excludedConsumers, "excludedConsumers"));
        if (!canonicalDue.found()
                || !canonicalDue.work().workIdentity().equals(
                        selectedWork.workIdentity())) {
            throw new IllegalArgumentException(
                    "Catch-up application would overtake canonical due work");
        }
        ManagedOccurrenceCatchUpPlan plan = requirePlan(
                selectedWork.planIdentity());
        if (plan.nextSourceEpoch() != selectedWork.sourceEpoch()
                || plan.status() != ManagedCatchUpStatus.RUNNING) {
            throw new IllegalArgumentException(
                    "Committed work does not own the plan's exact next cursor");
        }

        ManagedCatchUpWorkIndex changedWork = work.withApplication(
                selectedWork, receipt);
        long cursor = receipt.resultingSourceCursor();
        ManagedCatchUpStatus status = cursor
                == Math.addExact(plan.requiredThroughSourceEpoch(), 1L)
                ? ManagedCatchUpStatus.COMPLETE
                : ManagedCatchUpStatus.RUNNING;
        ManagedOccurrenceCatchUpPlan advanced = copyPlan(
                plan,
                cursor,
                plan.requiredThroughSourceEpoch(),
                status,
                null,
                null);
        ManagedCatchUpPlanIndex changedPlans = plans.withPlan(advanced, true);
        CatchUpPlanStore changed = new CatchUpPlanStore(
                changedPlans,
                barriers,
                changedWork,
                activeBarrierCount,
                sum(
                        existing.indexComparisons(),
                        workRead.indexComparisons(),
                        canonicalDue.indexRowsRead(),
                        changedWork.lastMutationComparisons(),
                        changedPlans.lastMutationComparisons()),
                sum(
                        changedWork.lastMutationNodeCopies(),
                        changedPlans.lastMutationNodeCopies()));
        return changed.reconcileExistingBarrier(plan.barrierIdentity());
    }

    ManagedCatchUpPlanIndex.PlanRead plan(String planIdentity) {
        return plans.exact(planIdentity);
    }

    ManagedCatchUpPlanIndex.PlanAudit plansForConsumer(
            DocumentId consumerDocumentId) {
        return plans.forConsumer(consumerDocumentId);
    }

    boolean hasActiveBarrierForConsumer(DocumentId consumerDocumentId) {
        return !activeBarrierIdentities(consumerDocumentId).isEmpty();
    }

    boolean hasActiveBarriers() {
        return activeBarrierCount != 0;
    }

    boolean hasActivePlanForSource(DocumentId sourceDocumentId) {
        return plans.hasActivePlanForSource(Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId"));
    }

    List<String> activeBarrierIdentities(DocumentId consumerDocumentId) {
        return activeBarriersForConsumer(consumerDocumentId)
                .barrierIdentities();
    }

    /** Opens only one consumer's plan bucket and its referenced barriers. */
    ActiveBarriersRead activeBarriersForConsumer(
            DocumentId consumerDocumentId) {
        ArrayList<String> active = new ArrayList<>();
        ArrayList<ManagedCatchUpBarrier> activeBarriers = new ArrayList<>();
        ManagedCatchUpPlanIndex.PlanAudit planRead = plansForConsumer(
                Objects.requireNonNull(
                        consumerDocumentId, "consumerDocumentId"));
        int comparisons = planRead.indexComparisons();
        int barrierRowsRead = 0;
        int unrelatedReads = planRead.unrelatedPlanReads();
        for (String identity : planRead.plans().stream()
                .map(ManagedOccurrenceCatchUpPlan::barrierIdentity)
                .distinct()
                .sorted(EmbeddingBinding.TEXT_ORDER)
                .toList()) {
            BarrierRead read = barrier(identity);
            comparisons = Math.addExact(
                    comparisons, read.indexComparisons());
            barrierRowsRead = Math.addExact(
                    barrierRowsRead, read.barrierRowsRead());
            unrelatedReads = Math.addExact(
                    unrelatedReads, read.unrelatedPlanReads());
            if (!read.found()) {
                throw new IllegalStateException(
                        "Catch-up plan points to a missing barrier");
            }
            if (read.barrier().status()
                    != ManagedCatchUpBarrierStatus.COMPLETE) {
                active.add(identity);
                activeBarriers.add(read.barrier());
            }
        }
        return new ActiveBarriersRead(
                active,
                activeBarriers,
                comparisons,
                planRead.planRowsRead(),
                barrierRowsRead,
                unrelatedReads);
    }

    ManagedCatchUpPlanIndex.PlanAudit plansForSource(
            DocumentId sourceDocumentId) {
        return plans.forSource(sourceDocumentId);
    }

    ManagedCatchUpPlanIndex.PlanAudit plansForOccurrence(
            String targetOccurrenceIdentity) {
        return plans.forOccurrence(targetOccurrenceIdentity);
    }

    ManagedCatchUpPlanIndex.PlanAudit plansForBarrier(
            String barrierIdentity) {
        return plans.forBarrier(barrierIdentity);
    }

    BarrierRead barrier(String barrierIdentity) {
        PersistentOrderedMap.ReadResult<ManagedCatchUpBarrier> read =
                barriers.read(Objects.requireNonNull(
                        barrierIdentity, "barrierIdentity"));
        return new BarrierRead(
                read.value(), read.comparisons(), read.found() ? 1 : 0, 0);
    }

    ManagedCatchUpWorkIndex.DueWorkRead nextDueWork() {
        return work.nextDueWork();
    }

    ManagedCatchUpWorkIndex.DueWorkRead nextDueWorkExcluding(
            Set<DocumentId> excludedConsumers) {
        return work.nextDueWorkExcluding(excludedConsumers);
    }

    ManagedCatchUpWorkIndex.WorkRead work(String workIdentity) {
        return work.work(workIdentity);
    }

    ManagedCatchUpWorkIndex.WorkRead pendingWorkForPlan(
            String planIdentity) {
        return work.pendingWorkForPlan(planIdentity);
    }

    ManagedCatchUpWorkIndex.ApplicationRead applicationByWork(
            String workIdentity) {
        return work.applicationByWork(workIdentity);
    }

    ManagedCatchUpWorkIndex.ApplicationRead application(
            String applicationReceiptIdentity) {
        return work.application(applicationReceiptIdentity);
    }

    int planCount() {
        return plans.size();
    }

    int barrierCount() {
        return barriers.size();
    }

    int workCount() {
        return work.workCount();
    }

    int applicationCount() {
        return work.applicationCount();
    }

    int dueWorkCount() {
        return work.dueCount();
    }

    int lastMutationComparisons() {
        return lastMutationComparisons;
    }

    int lastMutationNodeCopies() {
        return lastMutationNodeCopies;
    }

    void assertStructurallyValid() {
        plans.assertStructurallyValid();
        barriers.assertStructurallyValid();
        work.assertStructurallyValid();
        long actualActive = barriers.values().stream()
                .filter(CatchUpPlanStore::isActive)
                .count();
        if (actualActive != activeBarrierCount) {
            throw new IllegalStateException(
                    "Active catch-up barrier count disagrees with index: "
                            + activeBarrierCount + " != " + actualActive);
        }
    }

    record BarrierRead(
            ManagedCatchUpBarrier barrier,
            int indexComparisons,
            int barrierRowsRead,
            int unrelatedPlanReads) {
        boolean found() {
            return barrier != null;
        }
    }

    record ActiveBarriersRead(
            List<String> barrierIdentities,
            List<ManagedCatchUpBarrier> barriers,
            int indexComparisons,
            int planRowsRead,
            int barrierRowsRead,
            int unrelatedPlanReads) {
        ActiveBarriersRead {
            barrierIdentities = List.copyOf(Objects.requireNonNull(
                    barrierIdentities, "barrierIdentities"));
            barriers = List.copyOf(Objects.requireNonNull(
                    barriers, "barriers"));
            if (barrierIdentities.size() != barriers.size()) {
                throw new IllegalArgumentException(
                        "Active barrier identities and rows must align");
            }
            requireNonNegative(indexComparisons, "indexComparisons");
            requireNonNegative(planRowsRead, "planRowsRead");
            requireNonNegative(barrierRowsRead, "barrierRowsRead");
            requireNonNegative(unrelatedPlanReads, "unrelatedPlanReads");
        }
    }

    private CatchUpPlanStore reconcileExistingBarrier(
            String barrierIdentity) {
        PersistentOrderedMap.ReadResult<ManagedCatchUpBarrier> read =
                barriers.read(barrierIdentity);
        if (!read.found()) {
            return this;
        }
        if (!read.value().planIdentities().containsAll(
                plans.forBarrier(barrierIdentity).plans().stream()
                        .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                        .toList())) {
            return this;
        }
        ManagedCatchUpBarrier canonical = canonicalBarrier(read.value());
        if (canonical.snapshotIdentity().equals(
                read.value().snapshotIdentity())) {
            return this;
        }
        PersistentOrderedMap.Mutation<String, ManagedCatchUpBarrier> mutation =
                barriers.put(barrierIdentity, canonical);
        return new CatchUpPlanStore(
                plans,
                mutation.map(),
                work,
                Math.addExact(
                        activeBarrierCount,
                        activeDelta(read.value(), canonical)),
                sum(
                        lastMutationComparisons,
                        read.comparisons(),
                        mutation.comparisons()),
                Math.addExact(
                        lastMutationNodeCopies, mutation.copiedNodes()));
    }

    private ManagedCatchUpBarrier canonicalBarrier(
            ManagedCatchUpBarrier definition) {
        if (definition.planIdentities().isEmpty()) {
            throw new IllegalArgumentException(
                    "A catch-up barrier requires at least one plan");
        }
        ManagedCatchUpPlanIndex.PlanAudit indexed = plans.forBarrier(
                definition.barrierIdentity());
        ArrayList<String> indexedIdentities = new ArrayList<>();
        indexed.plans().forEach(
                plan -> indexedIdentities.add(plan.planIdentity()));
        if (!indexedIdentities.equals(definition.planIdentities())) {
            throw new IllegalArgumentException(
                    "Catch-up barrier must name every and only its indexed "
                            + "member plan");
        }

        ManagedCatchUpBarrierStatus status =
                ManagedCatchUpBarrierStatus.COMPLETE;
        String waitingCode = null;
        String waitingMessage = null;
        for (ManagedOccurrenceCatchUpPlan plan : indexed.plans()) {
            if (!plan.consumerDocumentId().equals(
                        definition.consumerDocumentId())
                    || !plan.causedByIdentity().equals(
                            definition.causedByIdentity())) {
                throw new IllegalArgumentException(
                        "Barrier member does not share consumer and cause");
            }
            if (plan.status() == ManagedCatchUpStatus.BLOCKED) {
                status = ManagedCatchUpBarrierStatus.BLOCKED;
                waitingCode = plan.waitingCode().orElseThrow();
                waitingMessage = plan.waitingMessage().orElse(null);
                break;
            }
            if (plan.status() == ManagedCatchUpStatus.WAITING_FOR_HISTORY
                    && status != ManagedCatchUpBarrierStatus
                            .WAITING_FOR_HISTORY) {
                status = ManagedCatchUpBarrierStatus.WAITING_FOR_HISTORY;
                waitingCode = plan.waitingCode().orElseThrow();
                waitingMessage = plan.waitingMessage().orElse(null);
            } else if (status == ManagedCatchUpBarrierStatus.COMPLETE
                    && plan.status() != ManagedCatchUpStatus.COMPLETE
                    && plan.status()
                            != ManagedCatchUpStatus
                                    .CANCELLED_OCCURRENCE_RETIRED) {
                status = ManagedCatchUpBarrierStatus.OPEN;
            }
        }
        return ManagedCatchUpBarrier.identified(
                definition.consumerDocumentId(),
                definition.causedByIdentity(),
                definition.causeOrder(),
                definition.planIdentities(),
                status,
                waitingCode,
                waitingMessage);
    }

    private ManagedOccurrenceCatchUpPlan requirePlan(String planIdentity) {
        ManagedCatchUpPlanIndex.PlanRead read = plans.exact(planIdentity);
        if (!read.found()) {
            throw new IllegalArgumentException(
                    "Unknown catch-up plan " + planIdentity);
        }
        return read.plan();
    }

    private ManagedCatchUpBarrier requireBarrier(String barrierIdentity) {
        BarrierRead read = barrier(barrierIdentity);
        if (!read.found()) {
            throw new IllegalArgumentException(
                    "Unknown catch-up barrier " + barrierIdentity);
        }
        return read.barrier();
    }

    private static void requireWorkMatch(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt source,
            ManagedOccurrenceCatchUpPlan plan,
            ManagedCatchUpBarrier barrier) {
        if (!work.barrierIdentity().equals(plan.barrierIdentity())
                || !work.barrierIdentity().equals(
                        barrier.barrierIdentity())
                || !work.sourceReceiptIdentity().equals(
                        source.receiptIdentity())
                || !work.sourceDocumentId().equals(plan.sourceDocumentId())
                || !work.sourceDocumentId().equals(source.documentId())
                || work.sourceEpoch() != plan.nextSourceEpoch()
                || work.sourceEpoch() != source.epoch()
                || work.sourceEpoch() > plan.requiredThroughSourceEpoch()
                || !work.consumerDocumentId().equals(
                        plan.consumerDocumentId())
                || !work.targetOccurrenceIdentity().equals(
                        plan.targetOccurrenceIdentity())
                || !work.targetPath().equals(plan.targetPath())
                || work.activationGeneration()
                        != plan.activationGeneration()
                || !matchesAdmittedPredecessor(work, source, plan)
                || plan.status() == ManagedCatchUpStatus.BLOCKED
                || plan.status() == ManagedCatchUpStatus.COMPLETE
                || plan.status()
                        == ManagedCatchUpStatus
                                .CANCELLED_OCCURRENCE_RETIRED) {
            throw new IllegalArgumentException(
                    "Managed epoch work does not match its plan, barrier, and "
                            + "source receipt");
        }
    }

    private static boolean matchesAdmittedPredecessor(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt source,
            ManagedOccurrenceCatchUpPlan plan) {
        if (work.sourceEpoch()
                != Math.addExact(plan.admittedSourceEpoch(), 1L)) {
            return true;
        }
        if (plan.admittedSourceEpoch() == -1L) {
            return work.sourceEpoch() == 0L
                    && source.kind() == DocumentRevision.Kind.INITIALIZATION
                    && source.beforeBlueId().isEmpty();
        }
        return source.beforeBlueId()
                .filter(plan.admittedSourceBlueId()::equals)
                .isPresent();
    }

    private static void requireFailureWorkMatch(
            ManagedEpochApplicationWork work,
            ManagedOccurrenceCatchUpPlan plan,
            ManagedCatchUpBarrier barrier) {
        if (!work.barrierIdentity().equals(plan.barrierIdentity())
                || !work.barrierIdentity().equals(
                        barrier.barrierIdentity())
                || !work.sourceDocumentId().equals(plan.sourceDocumentId())
                || work.sourceEpoch() != plan.nextSourceEpoch()
                || work.sourceEpoch() > plan.requiredThroughSourceEpoch()
                || !work.consumerDocumentId().equals(
                        plan.consumerDocumentId())
                || !work.targetOccurrenceIdentity().equals(
                        plan.targetOccurrenceIdentity())
                || !work.targetPath().equals(plan.targetPath())
                || work.activationGeneration()
                        != plan.activationGeneration()) {
            throw new IllegalArgumentException(
                    "Immutable-evidence failure work does not match its plan "
                            + "and barrier");
        }
    }

    private static ManagedOccurrenceCatchUpPlan copyPlan(
            ManagedOccurrenceCatchUpPlan plan,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            ManagedCatchUpStatus status,
            String waitingCode,
            String waitingMessage) {
        return ManagedOccurrenceCatchUpPlan.identified(
                plan.barrierIdentity(),
                plan.consumerDocumentId(),
                plan.targetOccurrenceIdentity(),
                plan.targetPath(),
                plan.activationGeneration(),
                plan.sourceDocumentId(),
                plan.admittedSourceEpoch(),
                plan.admittedSourceBlueId(),
                nextSourceEpoch,
                requiredThroughSourceEpoch,
                plan.causedByIdentity(),
                status,
                waitingCode,
                waitingMessage);
    }

    private static void requireSameBarrierDefinition(
            ManagedCatchUpBarrier prior,
            ManagedCatchUpBarrier changed) {
        if (!prior.consumerDocumentId().equals(
                    changed.consumerDocumentId())
                || !prior.causedByIdentity().equals(
                        changed.causedByIdentity())
                || !prior.causeOrder().equals(changed.causeOrder())) {
            throw new IllegalArgumentException(
                    "Catch-up barrier definition changed under one identity");
        }
    }

    private static void requireMemberExtension(
            ManagedCatchUpBarrier prior,
            ManagedCatchUpBarrier changed) {
        if (!changed.planIdentities().containsAll(prior.planIdentities())) {
            throw new IllegalArgumentException(
                    "Catch-up barrier members cannot be removed");
        }
    }

    private CatchUpPlanStore withMetrics(int comparisons, int copies) {
        if (this == EMPTY && comparisons == 0 && copies == 0) {
            return this;
        }
        return new CatchUpPlanStore(
                plans,
                barriers,
                work,
                activeBarrierCount,
                comparisons,
                copies);
    }

    private static int activeDelta(
            ManagedCatchUpBarrier before,
            ManagedCatchUpBarrier after) {
        return (isActive(after) ? 1 : 0) - (isActive(before) ? 1 : 0);
    }

    private static boolean isActive(ManagedCatchUpBarrier barrier) {
        return barrier != null
                && barrier.status() != ManagedCatchUpBarrierStatus.COMPLETE;
    }

    private static int requireNonNegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    private static int sum(int... values) {
        int total = 0;
        for (int value : values) {
            total = Math.addExact(total, value);
        }
        return total;
    }
}
