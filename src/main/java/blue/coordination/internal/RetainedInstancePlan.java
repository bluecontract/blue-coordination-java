package blue.coordination.internal;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import java.util.Set;

/** Proves that a foreign consumer needs only its exact already-complete original interval. */
final class RetainedInstancePlan {
    private RetainedInstancePlan() { }
    static boolean witnessed(DocumentInstanceRef source, ManagedOccurrenceCatchUpPlan plan,
            InMemoryDocumentStore documents, ContractsClosureAdapter adapter, TimelineJournal journal) {
        if (!plan.sourceDocumentId().equals(source.documentId()) || plan.consumerDocumentId().equals(source.documentId())) return false;
        var observer = Set.of(plan.consumerDocumentId());
        if (!documents.pendingSourceInstance(source.documentId(), observer).equals(source)) return false;
        var consumer = documents.require(plan.consumerDocumentId());
        var occurrence = consumer.rootedView().snapshot().occurrences().stream().filter(row ->
                row.occurrenceIdentity().equals(plan.targetOccurrenceIdentity())
                        && row.sourceDocumentId().value().equals(plan.consumerDocumentId().value())
                        && row.targetDocumentId().value().equals(source.documentId().value())
                        && row.sourcePath().equals(plan.targetPath())
                        && row.activationGeneration() == plan.activationGeneration()).findFirst().orElse(null);
        if (occurrence == null || occurrence.active() || occurrence.pendingHistoricalEpoch() == null
                || occurrence.pendingHistoricalEpoch() + 1 != plan.nextSourceEpoch()) return false;
        var barrier = documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow();
        if (!barrier.consumerDocumentId().equals(plan.consumerDocumentId()) || !barrier.planIdentities().contains(plan.planIdentity())) return false;
        var retained = documents.sourceBefore(source.documentId(), barrier.causeOrder(), observer).orElse(null);
        if (retained == null || retained.epoch() < plan.requiredThroughSourceEpoch()) return false;
        var selected = consumer.rootedView().snapshot().managedDocument(ContractsClosureAdapter.closureId(source.documentId()));
        if (selected == null || selected.epoch() != plan.requiredThroughSourceEpoch()
                || retained.epoch() != selected.epoch() || !retained.currentRepresentation().blueId().equals(selected.blueId())) return false;
        if (!RootedSourceHistoryAssessment.assess(source.documentId(), retained, barrier.causeOrder(), documents,
                adapter, journal, observer).satisfied()) return false;
        // Verify complete immutable numbered evidence; the exact occurrence and cutoff above own this interval.
        for (long epoch = Math.max(0, plan.admittedSourceEpoch()); epoch <= plan.requiredThroughSourceEpoch(); epoch++) {
            var evidence = documents.managedEpochEvidence(source.documentId(), epoch, observer);
            var receipt = evidence.receipt();
            if (receipt == null || evidence.transitionReceipt() == null
                    || receipt.sourceOrder().filter(order -> order.compareTo(barrier.causeOrder()) < 0).isEmpty()) return false;
            var revision = retained.revision(epoch);
            if (!revision.managedEpochReceipt().orElseThrow().receiptIdentity().equals(receipt.receiptIdentity()))
                throw new IllegalStateException("Retained interval differs from original numbered evidence");
        }
        return true;
    }
}
