package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.language.processor.ExternalOrderKey;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/** Persistent work, due-order, and response-loss receipt indexes. */
final class ManagedCatchUpWorkIndex {
    private static final Comparator<DueKey> DUE_ORDER = Comparator
            .comparing(DueKey::barrierCauseOrder)
            .thenComparing(DueKey::sourceOrder)
            .thenComparing(
                    DueKey::sourceDocumentId,
                    EmbeddingBinding.DOCUMENT_ORDER)
            .thenComparingLong(DueKey::sourceEpoch)
            .thenComparing(
                    DueKey::consumerDocumentId,
                    EmbeddingBinding.DOCUMENT_ORDER)
            .thenComparing(DueKey::targetPath, EmbeddingBinding.TEXT_ORDER)
            .thenComparingLong(DueKey::activationGeneration);
    private static final ManagedCatchUpWorkIndex EMPTY =
            new ManagedCatchUpWorkIndex(
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentMinimumMap.empty(DUE_ORDER),
                    0,
                    0);

    private final PersistentOrderedMap<String, RegisteredWork> byWorkIdentity;
    private final PersistentOrderedMap<String, String> pendingWorkByPlan;
    private final PersistentOrderedMap<String,
            ManagedEpochApplicationReceipt> applicationByIdentity;
    private final PersistentOrderedMap<String, String> applicationByWork;
    private final PersistentMinimumMap<DueKey, String> due;
    private final int lastMutationComparisons;
    private final int lastMutationNodeCopies;

    private ManagedCatchUpWorkIndex(
            PersistentOrderedMap<String, RegisteredWork> byWorkIdentity,
            PersistentOrderedMap<String, String> pendingWorkByPlan,
            PersistentOrderedMap<String,
                    ManagedEpochApplicationReceipt> applicationByIdentity,
            PersistentOrderedMap<String, String> applicationByWork,
            PersistentMinimumMap<DueKey, String> due,
            int lastMutationComparisons,
            int lastMutationNodeCopies) {
        this.byWorkIdentity = Objects.requireNonNull(
                byWorkIdentity, "byWorkIdentity");
        this.pendingWorkByPlan = Objects.requireNonNull(
                pendingWorkByPlan, "pendingWorkByPlan");
        this.applicationByIdentity = Objects.requireNonNull(
                applicationByIdentity, "applicationByIdentity");
        this.applicationByWork = Objects.requireNonNull(
                applicationByWork, "applicationByWork");
        this.due = Objects.requireNonNull(due, "due");
        this.lastMutationComparisons = lastMutationComparisons;
        this.lastMutationNodeCopies = lastMutationNodeCopies;
    }

    static ManagedCatchUpWorkIndex empty() {
        return EMPTY;
    }

    ManagedCatchUpWorkIndex withWork(
            ManagedEpochApplicationWork work,
            ManagedCatchUpBarrier barrier,
            ManagedEpochReceipt sourceReceipt) {
        ManagedEpochApplicationWork selected = Objects.requireNonNull(
                work, "work");
        ManagedCatchUpBarrier selectedBarrier = Objects.requireNonNull(
                barrier, "barrier");
        ManagedEpochReceipt source = Objects.requireNonNull(
                sourceReceipt, "sourceReceipt");
        ExternalOrderKey sourceOrder = source.sourceOrder().orElseThrow(
                () -> new IllegalArgumentException(
                        "Due managed epoch work requires exact source order"));
        DueKey dueKey = new DueKey(
                selectedBarrier.causeOrder(),
                sourceOrder,
                selected.sourceDocumentId(),
                selected.sourceEpoch(),
                selected.consumerDocumentId(),
                selected.targetPath(),
                selected.activationGeneration());

        PersistentOrderedMap.ReadResult<RegisteredWork> workRead =
                byWorkIdentity.read(selected.workIdentity());
        PersistentOrderedMap.ReadResult<String> appliedRead =
                applicationByWork.read(selected.workIdentity());
        if (workRead.found()) {
            RegisteredWork existing = workRead.value();
            if (!existing.dueKey().equals(dueKey)) {
                throw new IllegalArgumentException(
                        "Work identity has conflicting canonical due order");
            }
            PersistentOrderedMap.ReadResult<String> pendingRead =
                    pendingWorkByPlan.read(selected.planIdentity());
            PersistentMinimumMap.ReadResult<String> dueRead = due.read(dueKey);
            if (appliedRead.found()) {
                if ((pendingRead.found()
                                && pendingRead.value().equals(
                                        selected.workIdentity()))
                        || dueRead.found()) {
                    throw new IllegalStateException(
                            "Applied catch-up work remains in a pending "
                                    + "durable index");
                }
                return this;
            }

            boolean pendingMatches = pendingRead.found()
                    && pendingRead.value().equals(selected.workIdentity());
            boolean dueMatches = dueRead.found()
                    && dueRead.value().equals(selected.workIdentity());
            if (pendingRead.found() != dueRead.found()
                    || (pendingRead.found()
                            && (!pendingMatches || !dueMatches))) {
                throw new IllegalStateException(
                        "Registered unapplied catch-up work has inconsistent "
                                + "pending and due indexes");
            }
            if (pendingMatches) {
                return this;
            }

            PersistentOrderedMap.Mutation<String, String> pendingMutation =
                    pendingWorkByPlan.put(
                            selected.planIdentity(), selected.workIdentity());
            PersistentMinimumMap.Mutation<DueKey, String> dueMutation =
                    due.put(dueKey, selected.workIdentity());
            return new ManagedCatchUpWorkIndex(
                    byWorkIdentity,
                    pendingMutation.map(),
                    applicationByIdentity,
                    applicationByWork,
                    dueMutation.map(),
                    sum(
                            workRead.comparisons(),
                            appliedRead.comparisons(),
                            pendingRead.comparisons(),
                            dueRead.comparisons(),
                            pendingMutation.comparisons(),
                            dueMutation.comparisons()),
                    sum(
                            pendingMutation.copiedNodes(),
                            dueMutation.copiedNodes()));
        }
        if (appliedRead.found()) {
            throw new IllegalStateException(
                    "Application index points to unregistered catch-up work");
        }
        PersistentOrderedMap.ReadResult<String> pendingRead =
                pendingWorkByPlan.read(selected.planIdentity());
        if (pendingRead.found()) {
            throw new IllegalArgumentException(
                    "A catch-up plan already owns one pending work item");
        }
        PersistentMinimumMap.ReadResult<String> dueRead = due.read(dueKey);
        if (dueRead.found()) {
            throw new IllegalArgumentException(
                    "Canonical catch-up due order is not unique");
        }

        PersistentOrderedMap.Mutation<String, RegisteredWork> workMutation =
                byWorkIdentity.put(
                        selected.workIdentity(),
                        new RegisteredWork(selected, dueKey));
        PersistentOrderedMap.Mutation<String, String> pendingMutation =
                pendingWorkByPlan.put(
                        selected.planIdentity(), selected.workIdentity());
        PersistentMinimumMap.Mutation<DueKey, String> dueMutation = due.put(
                dueKey, selected.workIdentity());
        return new ManagedCatchUpWorkIndex(
                workMutation.map(),
                pendingMutation.map(),
                applicationByIdentity,
                applicationByWork,
                dueMutation.map(),
                sum(
                        workRead.comparisons(),
                        appliedRead.comparisons(),
                        pendingRead.comparisons(),
                        dueRead.comparisons(),
                        workMutation.comparisons(),
                        pendingMutation.comparisons(),
                        dueMutation.comparisons()),
                sum(
                        workMutation.copiedNodes(),
                        pendingMutation.copiedNodes(),
                        dueMutation.copiedNodes()));
    }

    ManagedCatchUpWorkIndex withApplication(
            ManagedEpochApplicationWork work,
            ManagedEpochApplicationReceipt receipt) {
        ManagedEpochApplicationWork selectedWork = Objects.requireNonNull(
                work, "work");
        ManagedEpochApplicationReceipt selectedReceipt =
                Objects.requireNonNull(receipt, "receipt");
        PersistentOrderedMap.ReadResult<RegisteredWork> workRead =
                byWorkIdentity.read(selectedWork.workIdentity());
        if (!workRead.found()
                || !workRead.value().work().workIdentity().equals(
                        selectedWork.workIdentity())) {
            throw new IllegalArgumentException(
                    "Application refers to unregistered catch-up work");
        }
        requireApplicationMatch(selectedWork, selectedReceipt);
        PersistentOrderedMap.ReadResult<String> workApplicationRead =
                applicationByWork.read(selectedWork.workIdentity());
        PersistentOrderedMap.ReadResult<ManagedEpochApplicationReceipt>
                identityRead = applicationByIdentity.read(
                        selectedReceipt.applicationReceiptIdentity());
        if (workApplicationRead.found() || identityRead.found()) {
            if (workApplicationRead.found()
                    && identityRead.found()
                    && workApplicationRead.value().equals(
                            selectedReceipt.applicationReceiptIdentity())
                    && identityRead.value().workIdentity().equals(
                            selectedWork.workIdentity())) {
                return this;
            }
            throw new IllegalArgumentException(
                    "Application receipt conflicts with durable work identity");
        }

        RegisteredWork registered = workRead.value();
        PersistentOrderedMap.ReadResult<String> pendingRead =
                pendingWorkByPlan.read(selectedWork.planIdentity());
        if (!pendingRead.found()
                || !pendingRead.value().equals(selectedWork.workIdentity())) {
            throw new IllegalArgumentException(
                    "Application does not own the plan's pending work slot");
        }
        PersistentOrderedMap.Mutation<String,
                ManagedEpochApplicationReceipt> applicationMutation =
                applicationByIdentity.put(
                        selectedReceipt.applicationReceiptIdentity(),
                        selectedReceipt);
        PersistentOrderedMap.Mutation<String, String> byWorkMutation =
                applicationByWork.put(
                        selectedWork.workIdentity(),
                        selectedReceipt.applicationReceiptIdentity());
        PersistentOrderedMap.Mutation<String, String> pendingMutation =
                pendingWorkByPlan.remove(selectedWork.planIdentity());
        PersistentMinimumMap.Mutation<DueKey, String> dueMutation = due.remove(
                registered.dueKey());
        if (!pendingMutation.changed() || !dueMutation.changed()) {
            throw new IllegalStateException(
                    "Committed catch-up work was absent from a durable index");
        }
        return new ManagedCatchUpWorkIndex(
                byWorkIdentity,
                pendingMutation.map(),
                applicationMutation.map(),
                byWorkMutation.map(),
                dueMutation.map(),
                sum(
                        workRead.comparisons(),
                        workApplicationRead.comparisons(),
                        identityRead.comparisons(),
                        pendingRead.comparisons(),
                        applicationMutation.comparisons(),
                        byWorkMutation.comparisons(),
                        pendingMutation.comparisons(),
                        dueMutation.comparisons()),
                sum(
                        applicationMutation.copiedNodes(),
                        byWorkMutation.copiedNodes(),
                        pendingMutation.copiedNodes(),
                        dueMutation.copiedNodes()));
    }

    ManagedCatchUpWorkIndex withoutPendingWorkForPlan(String planIdentity) {
        String selected = Objects.requireNonNull(planIdentity, "planIdentity");
        PersistentOrderedMap.ReadResult<String> pendingRead =
                pendingWorkByPlan.read(selected);
        if (!pendingRead.found()) {
            return this;
        }
        PersistentOrderedMap.ReadResult<RegisteredWork> workRead =
                byWorkIdentity.read(pendingRead.value());
        if (!workRead.found()) {
            throw new IllegalStateException(
                    "Pending plan index points to missing catch-up work");
        }
        PersistentOrderedMap.Mutation<String, String> pendingMutation =
                pendingWorkByPlan.remove(selected);
        PersistentMinimumMap.Mutation<DueKey, String> dueMutation = due.remove(
                workRead.value().dueKey());
        if (!pendingMutation.changed() || !dueMutation.changed()) {
            throw new IllegalStateException(
                    "Retired plan work was absent from a durable due index");
        }
        return new ManagedCatchUpWorkIndex(
                byWorkIdentity,
                pendingMutation.map(),
                applicationByIdentity,
                applicationByWork,
                dueMutation.map(),
                sum(
                        pendingRead.comparisons(),
                        workRead.comparisons(),
                        pendingMutation.comparisons(),
                        dueMutation.comparisons()),
                sum(
                        pendingMutation.copiedNodes(),
                        dueMutation.copiedNodes()));
    }

    DueWorkRead nextDueWork() {
        return nextDueWorkExcluding(Set.of());
    }

    /**
     * Selects the canonical due item outside consumers already failed by the
     * current drain. Traversal advances by AVL successor, so work is bounded
     * by the excluded lanes rather than all unrelated plans.
     */
    DueWorkRead nextDueWorkExcluding(Set<DocumentId> excludedConsumers) {
        Set<DocumentId> excluded = Set.copyOf(Objects.requireNonNull(
                excludedConsumers, "excludedConsumers"));
        PersistentMinimumMap.MinimumResult<DueKey, String> minimum =
                due.minimum();
        int indexRowsRead = minimum.rowsRead();
        int workRowsRead = 0;
        PersistentMinimumMap.MinimumResult<DueKey, String> selected = minimum;
        while (selected.found()) {
            PersistentOrderedMap.ReadResult<RegisteredWork> workRead =
                    byWorkIdentity.read(selected.entry().getValue());
            indexRowsRead = Math.addExact(
                    indexRowsRead, workRead.comparisons());
            if (!workRead.found()) {
                throw new IllegalStateException(
                        "Due index points to missing catch-up work");
            }
            workRowsRead = Math.addExact(workRowsRead, 1);
            ManagedEpochApplicationWork work = workRead.value().work();
            if (!excluded.contains(work.consumerDocumentId())) {
                return new DueWorkRead(
                        work, indexRowsRead, workRowsRead, 0);
            }
            selected = due.higherThan(selected.entry().getKey());
            indexRowsRead = Math.addExact(
                    indexRowsRead, selected.rowsRead());
        }
        return new DueWorkRead(null, indexRowsRead, workRowsRead, 0);
    }

    WorkRead work(String workIdentity) {
        PersistentOrderedMap.ReadResult<RegisteredWork> read =
                byWorkIdentity.read(Objects.requireNonNull(
                        workIdentity, "workIdentity"));
        return new WorkRead(
                read.found() ? read.value().work() : null,
                read.comparisons(),
                read.found() ? 1 : 0,
                0);
    }

    WorkRead pendingWorkForPlan(String planIdentity) {
        PersistentOrderedMap.ReadResult<String> pending =
                pendingWorkByPlan.read(Objects.requireNonNull(
                        planIdentity, "planIdentity"));
        if (!pending.found()) {
            return new WorkRead(null, pending.comparisons(), 0, 0);
        }
        PersistentOrderedMap.ReadResult<RegisteredWork> selected =
                byWorkIdentity.read(pending.value());
        if (!selected.found()) {
            throw new IllegalStateException(
                    "Pending plan index points to missing work");
        }
        return new WorkRead(
                selected.value().work(),
                Math.addExact(
                        pending.comparisons(), selected.comparisons()),
                1,
                0);
    }

    ApplicationRead applicationByWork(String workIdentity) {
        PersistentOrderedMap.ReadResult<String> workRead =
                applicationByWork.read(Objects.requireNonNull(
                        workIdentity, "workIdentity"));
        if (!workRead.found()) {
            return new ApplicationRead(
                    null, workRead.comparisons(), 0, 0);
        }
        PersistentOrderedMap.ReadResult<ManagedEpochApplicationReceipt> read =
                applicationByIdentity.read(workRead.value());
        if (!read.found()) {
            throw new IllegalStateException(
                    "Application-by-work index points to a missing receipt");
        }
        return new ApplicationRead(
                read.value(),
                Math.addExact(workRead.comparisons(), read.comparisons()),
                1,
                0);
    }

    ApplicationRead application(String applicationReceiptIdentity) {
        PersistentOrderedMap.ReadResult<ManagedEpochApplicationReceipt> read =
                applicationByIdentity.read(Objects.requireNonNull(
                        applicationReceiptIdentity,
                        "applicationReceiptIdentity"));
        return new ApplicationRead(
                read.value(), read.comparisons(), read.found() ? 1 : 0, 0);
    }

    int workCount() {
        return byWorkIdentity.size();
    }

    int applicationCount() {
        return applicationByIdentity.size();
    }

    int dueCount() {
        return due.size();
    }

    int lastMutationComparisons() {
        return lastMutationComparisons;
    }

    int lastMutationNodeCopies() {
        return lastMutationNodeCopies;
    }

    void assertStructurallyValid() {
        byWorkIdentity.assertStructurallyValid();
        pendingWorkByPlan.assertStructurallyValid();
        applicationByIdentity.assertStructurallyValid();
        applicationByWork.assertStructurallyValid();
        due.assertStructurallyValid();
    }

    record DueWorkRead(
            ManagedEpochApplicationWork work,
            int indexRowsRead,
            int workRowsRead,
            int unrelatedPlanReads) {
        boolean found() {
            return work != null;
        }
    }

    record WorkRead(
            ManagedEpochApplicationWork work,
            int indexComparisons,
            int workRowsRead,
            int unrelatedPlanReads) {
        boolean found() {
            return work != null;
        }
    }

    record ApplicationRead(
            ManagedEpochApplicationReceipt receipt,
            int indexComparisons,
            int applicationRowsRead,
            int unrelatedPlanReads) {
        boolean found() {
            return receipt != null;
        }
    }

    private static void requireApplicationMatch(
            ManagedEpochApplicationWork work,
            ManagedEpochApplicationReceipt receipt) {
        if (!receipt.representationCauseIdentity().equals(work.representationCause().map(cause -> cause.causeIdentity()))
                || !receipt.workIdentity().equals(work.workIdentity())
                || !receipt.planIdentity().equals(work.planIdentity())
                || !receipt.sourceReceiptIdentity().equals(
                        work.sourceReceiptIdentity())
                || !receipt.consumerDocumentId().equals(
                        work.consumerDocumentId())
                || receipt.resultingSourceCursor()
                        != Math.addExact(work.sourceEpoch(), 1L)) {
            throw new IllegalArgumentException(
                    "Application receipt does not match its exact work item");
        }
    }

    private static int sum(int... values) {
        int total = 0;
        for (int value : values) {
            total = Math.addExact(total, value);
        }
        return total;
    }

    private record RegisteredWork(
            ManagedEpochApplicationWork work, DueKey dueKey) {
        private RegisteredWork {
            work = Objects.requireNonNull(work, "work");
            dueKey = Objects.requireNonNull(dueKey, "dueKey");
        }
    }

    private record DueKey(
            ExternalOrderKey barrierCauseOrder,
            ExternalOrderKey sourceOrder,
            DocumentId sourceDocumentId,
            long sourceEpoch,
            DocumentId consumerDocumentId,
            String targetPath,
            long activationGeneration) {
        private DueKey {
            barrierCauseOrder = Objects.requireNonNull(
                    barrierCauseOrder, "barrierCauseOrder");
            sourceOrder = Objects.requireNonNull(sourceOrder, "sourceOrder");
            sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            consumerDocumentId = Objects.requireNonNull(
                    consumerDocumentId, "consumerDocumentId");
            targetPath = Objects.requireNonNull(targetPath, "targetPath");
        }
    }
}
