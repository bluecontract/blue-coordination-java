package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationCause;
import blue.language.provider.CyclicSetProof;
import java.util.function.Function;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministically derives occurrence plans and their first due work. */
final class ManagedCatchUpPlanner {
    private static final Comparator<DocumentId> DOCUMENT_ORDER =
            EmbeddingBinding.DOCUMENT_ORDER;
    private static final Comparator<PreparedWork> PREPARED_WORK_ORDER =
            Comparator.comparing(PreparedWork::barrierCauseOrder)
                    .thenComparing(PreparedWork::sourceOrder)
                    .thenComparing(
                            work -> work.work().sourceDocumentId(),
                            DOCUMENT_ORDER)
                    .thenComparingLong(
                            work -> work.work().sourceEpoch())
                    .thenComparing(
                            work -> work.work().consumerDocumentId(),
                            DOCUMENT_ORDER)
                    .thenComparing(
                            work -> work.work().targetPath(),
                            EmbeddingBinding.TEXT_ORDER)
                    .thenComparingLong(
                            work -> work.work().activationGeneration());

    private ManagedCatchUpPlanner() {
    }

    static PlanningResult afterPublication(
            CatchUpPlanStore beforePlans,
            ManagedOccurrenceInventory beforeInventory,
            ManagedOccurrenceInventory afterInventory,
            Collection<DocumentId> affectedSources,
            Collection<ManagedEpochReceipt> committedSourceReceipts,
            String causedByIdentity,
            ExternalOrderKey causeOrder,
            HeadLookup heads,
            ReceiptLookup receipts,
            GraphGenerationLookup graphGenerations) {
        return afterPublication(beforePlans, beforeInventory, afterInventory, affectedSources, committedSourceReceipts, causedByIdentity, causeOrder, heads, receipts, graphGenerations, null, null);
    }

    static PlanningResult afterPublication(
            CatchUpPlanStore beforePlans,
            ManagedOccurrenceInventory beforeInventory,
            ManagedOccurrenceInventory afterInventory,
            Collection<DocumentId> affectedSources,
            Collection<ManagedEpochReceipt> committedSourceReceipts,
            String causedByIdentity,
            ExternalOrderKey causeOrder,
            HeadLookup heads,
            ReceiptLookup receipts,
            GraphGenerationLookup graphGenerations,
            ManagedRepresentationHistory representationHistory, Function<String, CyclicSetProof> cyclicProofs) {
        CatchUpPlanStore plans = Objects.requireNonNull(
                beforePlans, "beforePlans");
        ManagedOccurrenceInventory prior = Objects.requireNonNull(
                beforeInventory, "beforeInventory");
        ManagedOccurrenceInventory resulting = Objects.requireNonNull(
                afterInventory, "afterInventory");
        String cause = Objects.requireNonNull(
                causedByIdentity, "causedByIdentity");
        ExternalOrderKey order = Objects.requireNonNull(
                causeOrder, "causeOrder");
        HeadLookup headLookup = Objects.requireNonNull(heads, "heads");
        ReceiptLookup receiptLookup = Objects.requireNonNull(
                receipts, "receipts");
        GraphGenerationLookup graphLookup = Objects.requireNonNull(
                graphGenerations, "graphGenerations");

        ArrayList<ManagedEpochReceipt> committed = new ArrayList<>(
                Objects.requireNonNull(
                        committedSourceReceipts,
                        "committedSourceReceipts"));
        committed.sort(Comparator
                .comparing(ManagedEpochReceipt::documentId, DOCUMENT_ORDER)
                .thenComparingLong(ManagedEpochReceipt::epoch));
        for (ManagedEpochReceipt receipt : committed) {
            plans = plans.withExtendedSourceFrontier(
                    receipt.documentId(), receipt.epoch());
        }

        ArrayList<DocumentId> sources = new ArrayList<>(
                new LinkedHashSet<>(Objects.requireNonNull(
                        affectedSources, "affectedSources")));
        sources.sort(DOCUMENT_ORDER);
        for (DocumentId source : sources) {
            for (ManagedOccurrenceBinding oldRow : prior.rowsFrom(source)) {
                ManagedOccurrenceBinding replacement = resulting.find(
                        source, oldRow.sourcePath()).orElse(null);
                if (replacement == null
                        || !replacement.occurrenceIdentity().equals(
                                oldRow.occurrenceIdentity())
                        || replacement.activationGeneration()
                                != oldRow.activationGeneration()) {
                    plans = plans.withRetiredOccurrence(
                            oldRow.occurrenceIdentity(),
                            oldRow.activationGeneration());
                }
            }
        }

        LinkedHashMap<String, BarrierSeed> barriers = new LinkedHashMap<>();
        LinkedHashSet<String> touchedPlanIdentities = new LinkedHashSet<>();
        for (DocumentId consumer : sources) {
            for (ManagedOccurrenceBinding row
                    : resulting.rowsFrom(consumer)) {
                if (row.active() || row.pendingHistoricalEpoch() == null) {
                    continue;
                }
                long admitted = row.pendingHistoricalEpoch().longValue();
                DocumentId source = DocumentId.of(
                        row.targetDocumentId().value());
                Head sourceHead = headLookup.require(source);
                boolean pendingTail = admitted == sourceHead.epoch() && representationHistory != null
                        && representationHistory.atCaptured(source, admitted, row.pendingRepresentationCursor())
                            .next(row.pendingRepresentationCursor(), row.expectedTargetBlueId()).isPresent();
                if (admitted >= sourceHead.epoch() && !pendingTail) {
                    if (admitted == sourceHead.epoch()
                            && row.expectedTargetBlueId().equals(
                                    sourceHead.blueId())) {
                        throw new IllegalStateException(
                                "A current managed occurrence remained "
                                        + "historically pending at "
                                        + consumer + row.sourcePath()
                                        + " (cursor=" + admitted
                                        + ", sourceHead="
                                        + sourceHead.epoch()
                                        + ", target="
                                        + row.expectedTargetBlueId()
                                        + ")");
                    }
                    throw new IllegalStateException(
                            "Historical occurrence cursor is ahead of source "
                                    + source + " at " + consumer
                                    + row.sourcePath());
                }
                List<ManagedOccurrenceCatchUpPlan> existing = plans
                        .plansForOccurrence(row.occurrenceIdentity()).plans()
                        .stream()
                        .filter(plan -> plan.activationGeneration()
                                == row.activationGeneration())
                        .toList();
                if (existing.size() > 1) {
                    throw new IllegalStateException(
                            "Occurrence generation has several catch-up plans "
                                    + row.occurrenceIdentity());
                }
                if (existing.size() == 1) {
                    touchedPlanIdentities.add(
                            existing.get(0).planIdentity());
                    continue;
                }

                ManagedCatchUpBarrier provisional =
                        ManagedCatchUpBarrier.identified(
                                consumer,
                                cause,
                                order,
                                List.of(),
                                ManagedCatchUpBarrierStatus.COMPLETE,
                                null,
                                null);
                ManagedOccurrenceCatchUpPlan plan =
                        ManagedOccurrenceCatchUpPlan.identified(
                                provisional.barrierIdentity(),
                                consumer,
                                row.occurrenceIdentity(),
                                row.sourcePath(),
                                row.activationGeneration(),
                                source,
                                admitted,
                                row.expectedTargetBlueId(),
                                Math.addExact(admitted, 1L),
                                sourceHead.epoch(),
                                cause,
                                ManagedCatchUpStatus.PENDING,
                                null,
                                null);
                plans = plans.withPlan(plan);
                touchedPlanIdentities.add(plan.planIdentity());
                barriers.putIfAbsent(
                        plan.barrierIdentity(),
                        new BarrierSeed(consumer, cause, order));
            }
        }

        for (Map.Entry<String, BarrierSeed> entry : barriers.entrySet()) {
            List<String> members = plans.plansForBarrier(entry.getKey())
                    .plans().stream()
                    .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                    .sorted()
                    .toList();
            BarrierSeed seed = entry.getValue();
            plans = plans.withBarrier(ManagedCatchUpBarrier.identified(
                    seed.consumerDocumentId(),
                    seed.causedByIdentity(),
                    seed.causeOrder(),
                    members,
                    ManagedCatchUpBarrierStatus.OPEN,
                    null,
                    null));
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>(
                touchedPlanIdentities);
        for (ManagedEpochReceipt receipt : committed) {
            plans.plansForSource(receipt.documentId()).plans().stream()
                    .filter(ManagedCatchUpPlanner::belongsToActiveBarrier)
                    .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                    .forEach(candidates::add);
        }
        LinkedHashSet<DocumentId> impactedConsumers = new LinkedHashSet<>();
        for (String identity : candidates) {
            ManagedCatchUpPlanIndex.PlanRead read = plans.plan(identity);
            if (read.found()) {
                impactedConsumers.add(read.plan().consumerDocumentId());
            }
        }
        ArrayList<DocumentId> canonicalImpactedConsumers = new ArrayList<>(
                impactedConsumers);
        canonicalImpactedConsumers.sort(DOCUMENT_ORDER);
        for (DocumentId consumer : canonicalImpactedConsumers) {
            plans.plansForConsumer(consumer).plans().stream()
                    .filter(ManagedCatchUpPlanner::belongsToActiveBarrier)
                    .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                    .forEach(candidates::add);
        }
        ArrayList<String> canonicalCandidates = new ArrayList<>(candidates);
        canonicalCandidates.sort(String::compareTo);
        ArrayList<PreparedWork> preparedWork = new ArrayList<>();
        LinkedHashSet<DocumentId> waitingConsumers = new LinkedHashSet<>();
        LinkedHashSet<DocumentId> blockedConsumers = new LinkedHashSet<>();
        for (DocumentId consumer : canonicalImpactedConsumers) {
            boolean blocked = plans.activeBarriersForConsumer(consumer)
                    .barriers().stream()
                    .anyMatch(barrier -> barrier.status()
                            == ManagedCatchUpBarrierStatus.BLOCKED);
            if (blocked) {
                blockedConsumers.add(consumer);
            }
        }
        LinkedHashSet<DocumentId> consumersWithPendingWork =
                new LinkedHashSet<>();
        for (String identity : canonicalCandidates) {
            ManagedOccurrenceCatchUpPlan plan = plans.plan(identity).plan();
            if (plan == null
                    || plan.status() == ManagedCatchUpStatus.COMPLETE
                    || plan.status()
                            == ManagedCatchUpStatus
                                    .CANCELLED_OCCURRENCE_RETIRED
) {
                continue;
            }
            if (plan.status() == ManagedCatchUpStatus.BLOCKED) {
                blockedConsumers.add(plan.consumerDocumentId());
                continue;
            }
            if (hasPendingWorkForConsumer(
                    plans, plan.consumerDocumentId())) {
                consumersWithPendingWork.add(plan.consumerDocumentId());
                continue;
            }
            ManagedOccurrenceBinding occurrence = resulting.find(plan.consumerDocumentId(), plan.targetPath()).orElse(null);
            ManagedRepresentationCause representationCause = null;
            if (representationHistory != null && occurrence != null && !occurrence.active()
                    && occurrence.pendingHistoricalEpoch() != null && occurrence.pendingHistoricalEpoch() >= 0L) {
                var chain = representationHistory.atCaptured(plan.sourceDocumentId(), occurrence.pendingHistoricalEpoch(),
                        occurrence.pendingRepresentationCursor());
                var nextPosition = chain.next(occurrence.pendingRepresentationCursor(), occurrence.expectedTargetBlueId());
                if (nextPosition.isPresent()) {
                    var transition = nextPosition.orElseThrow();
                    representationCause = new ManagedRepresentationCause(occurrence.occurrenceIdentity(), transition,
                            chain.targetPositionIdentity(), chain.nextRevisionReceiptIdentity(),
                            cyclicProofs.apply(transition.transitionReceipt().afterBlueId()));
                }
            }
            if (representationCause == null && plan.nextSourceEpoch() > plan.requiredThroughSourceEpoch()) {
                throw new IllegalStateException("Pending historical tail has no independently authenticated next step");
            }
            ManagedEpochReceipt sourceReceipt = receiptLookup.find(
                    plan.sourceDocumentId(), representationCause == null ? plan.nextSourceEpoch() : representationCause.fromEpoch());
            if (sourceReceipt == null) {
                plans = plans.withWaitingForHistory(
                        plan.planIdentity(),
                        "MANAGED_EPOCH_RECEIPT_MISSING",
                        "No complete managed epoch receipt for "
                                + plan.sourceDocumentId() + " epoch "
                                + plan.nextSourceEpoch());
                waitingConsumers.add(plan.consumerDocumentId());
                continue;
            }
            Head consumerHead = headLookup.require(
                    plan.consumerDocumentId());
            ManagedEpochApplicationWork work =
                    ManagedEpochApplicationWork.identified(
                            plan.planIdentity(),
                            plan.barrierIdentity(),
                            sourceReceipt.receiptIdentity(),
                            plan.sourceDocumentId(),
                            sourceReceipt.epoch(),
                            plan.consumerDocumentId(),
                            plan.targetOccurrenceIdentity(),
                            plan.targetPath(),
                            plan.activationGeneration(),
                            consumerHead.epoch(),
                            consumerHead.blueId(),
                            graphLookup.require(
                                    plan.consumerDocumentId()));
            if (representationCause != null) work = ManagedEpochApplicationWork.identifiedRepresentation(work, representationCause);
            ManagedCatchUpBarrier barrier = plans
                    .barrier(plan.barrierIdentity()).barrier();
            if (barrier == null) {
                throw new IllegalStateException(
                        "Catch-up plan points to a missing barrier "
                                + plan.barrierIdentity());
            }
            preparedWork.add(new PreparedWork(
                    work,
                    sourceReceipt,
                    barrier.causeOrder(),
                    sourceReceipt.sourceOrder().orElseThrow(
                            () -> new IllegalStateException(
                                    "Catch-up source receipt has no exact "
                                            + "cause order"))));
        }
        preparedWork.sort(PREPARED_WORK_ORDER);
        LinkedHashSet<DocumentId> selectedConsumers = new LinkedHashSet<>();
        for (PreparedWork prepared : preparedWork) {
            DocumentId consumer = prepared.work().consumerDocumentId();
            if (blockedConsumers.contains(consumer)
                    || waitingConsumers.contains(consumer)
                    || consumersWithPendingWork.contains(consumer)
                    || !selectedConsumers.add(consumer)) {
                continue;
            }
            plans = plans.withWork(
                    prepared.work(), prepared.sourceReceipt());
        }
        LinkedHashSet<DocumentId> catchingUp = new LinkedHashSet<>();
        for (DocumentId documentId : sources) {
            if (plans.hasActiveBarrierForConsumer(documentId)) {
                catchingUp.add(documentId);
            }
        }
        for (ManagedEpochReceipt receipt : committed) {
            for (ManagedOccurrenceCatchUpPlan plan
                    : plans.plansForSource(
                            receipt.documentId()).plans()) {
                DocumentId consumer = plan.consumerDocumentId();
                if (plans.hasActiveBarrierForConsumer(consumer)) {
                    catchingUp.add(consumer);
                }
            }
        }
        return new PlanningResult(plans, catchingUp);
    }

    @FunctionalInterface
    interface HeadLookup {
        Head require(DocumentId documentId);
    }

    @FunctionalInterface
    interface ReceiptLookup {
        ManagedEpochReceipt find(DocumentId documentId, long epoch);
    }

    @FunctionalInterface
    interface GraphGenerationLookup {
        long require(DocumentId documentId);
    }

    record Head(long epoch, String blueId) {
        Head {
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "head epoch must be non-negative");
            }
            blueId = Objects.requireNonNull(blueId, "blueId");
        }
    }

    record PlanningResult(
            CatchUpPlanStore plans,
            Set<DocumentId> catchingUpConsumers) {
        PlanningResult {
            plans = Objects.requireNonNull(plans, "plans");
            ArrayList<DocumentId> canonical = new ArrayList<>(
                    Objects.requireNonNull(
                            catchingUpConsumers,
                            "catchingUpConsumers"));
            canonical.sort(DOCUMENT_ORDER);
            catchingUpConsumers = Collections.unmodifiableSet(
                    new LinkedHashSet<>(canonical));
        }
    }

    private record BarrierSeed(
            DocumentId consumerDocumentId,
            String causedByIdentity,
            ExternalOrderKey causeOrder) {
    }

    private static boolean hasPendingWorkForConsumer(
            CatchUpPlanStore plans,
            DocumentId consumerDocumentId) {
        for (ManagedOccurrenceCatchUpPlan plan
                : plans.plansForConsumer(consumerDocumentId).plans()) {
            if (plans.pendingWorkForPlan(plan.planIdentity()).found()) {
                return true;
            }
        }
        return false;
    }

    private static boolean belongsToActiveBarrier(
            ManagedOccurrenceCatchUpPlan plan) {
        return plan.status() != ManagedCatchUpStatus.COMPLETE
                && plan.status()
                        != ManagedCatchUpStatus
                                .CANCELLED_OCCURRENCE_RETIRED;
    }

    private record PreparedWork(
            ManagedEpochApplicationWork work,
            ManagedEpochReceipt sourceReceipt,
            ExternalOrderKey barrierCauseOrder,
            ExternalOrderKey sourceOrder) {
    }
}
