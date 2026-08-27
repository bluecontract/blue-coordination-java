package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Persistent exact indexes for occurrence-specific catch-up plans. */
final class ManagedCatchUpPlanIndex {
    private static final ManagedCatchUpPlanIndex EMPTY =
            new ManagedCatchUpPlanIndex(
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.DOCUMENT_ORDER),
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.DOCUMENT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    PersistentOrderedMap.empty(
                            EmbeddingBinding.DOCUMENT_ORDER),
                    0,
                    0);

    private final PersistentOrderedMap<String,
            ManagedOccurrenceCatchUpPlan> byIdentity;
    private final PersistentOrderedMap<DocumentId, IdBucket> byConsumer;
    private final PersistentOrderedMap<DocumentId, IdBucket> bySource;
    private final PersistentOrderedMap<String, IdBucket> byOccurrence;
    private final PersistentOrderedMap<String, IdBucket> byBarrier;
    private final PersistentOrderedMap<DocumentId, Integer>
            activePlanCountBySource;
    private final int lastMutationComparisons;
    private final int lastMutationNodeCopies;

    private ManagedCatchUpPlanIndex(
            PersistentOrderedMap<String,
                    ManagedOccurrenceCatchUpPlan> byIdentity,
            PersistentOrderedMap<DocumentId, IdBucket> byConsumer,
            PersistentOrderedMap<DocumentId, IdBucket> bySource,
            PersistentOrderedMap<String, IdBucket> byOccurrence,
            PersistentOrderedMap<String, IdBucket> byBarrier,
            PersistentOrderedMap<DocumentId, Integer>
                    activePlanCountBySource,
            int lastMutationComparisons,
            int lastMutationNodeCopies) {
        this.byIdentity = Objects.requireNonNull(byIdentity, "byIdentity");
        this.byConsumer = Objects.requireNonNull(byConsumer, "byConsumer");
        this.bySource = Objects.requireNonNull(bySource, "bySource");
        this.byOccurrence = Objects.requireNonNull(
                byOccurrence, "byOccurrence");
        this.byBarrier = Objects.requireNonNull(byBarrier, "byBarrier");
        this.activePlanCountBySource = Objects.requireNonNull(
                activePlanCountBySource, "activePlanCountBySource");
        this.lastMutationComparisons = lastMutationComparisons;
        this.lastMutationNodeCopies = lastMutationNodeCopies;
    }

    static ManagedCatchUpPlanIndex empty() {
        return EMPTY;
    }

    ManagedCatchUpPlanIndex withPlan(
            ManagedOccurrenceCatchUpPlan plan,
            boolean cursorMayAdvance) {
        ManagedOccurrenceCatchUpPlan selected = Objects.requireNonNull(
                plan, "plan");
        PersistentOrderedMap.ReadResult<ManagedOccurrenceCatchUpPlan> read =
                byIdentity.read(selected.planIdentity());
        if (read.found()) {
            ManagedOccurrenceCatchUpPlan prior = read.value();
            if (prior.snapshotIdentity().equals(selected.snapshotIdentity())) {
                return this;
            }
            requireSameDefinition(prior, selected);
            requireMonotonicTransition(prior, selected, cursorMayAdvance);
            PersistentOrderedMap.Mutation<String,
                    ManagedOccurrenceCatchUpPlan> mutation = byIdentity.put(
                            selected.planIdentity(), selected);
            ActiveSourceMutation activeSources = activeSourceMutation(
                    activePlanCountBySource, prior, selected);
            return new ManagedCatchUpPlanIndex(
                    mutation.map(),
                    byConsumer,
                    bySource,
                    byOccurrence,
                    byBarrier,
                    activeSources.map(),
                    sum(
                            read.comparisons(),
                            mutation.comparisons(),
                            activeSources.comparisons()),
                    Math.addExact(
                            mutation.copiedNodes(),
                            activeSources.copiedNodes()));
        }

        if (selected.nextSourceEpoch()
                != Math.addExact(selected.admittedSourceEpoch(), 1L)) {
            throw new IllegalArgumentException(
                    "A new catch-up plan must start immediately after its "
                            + "admitted source epoch");
        }
        requireUnusedOccurrenceGeneration(selected);

        PersistentOrderedMap.Mutation<String,
                ManagedOccurrenceCatchUpPlan> identityMutation =
                byIdentity.put(selected.planIdentity(), selected);
        DocumentIndexMutation consumer = add(
                byConsumer,
                selected.consumerDocumentId(),
                selected.planIdentity());
        DocumentIndexMutation source = add(
                bySource,
                selected.sourceDocumentId(),
                selected.planIdentity());
        TextIndexMutation occurrence = add(
                byOccurrence,
                selected.targetOccurrenceIdentity(),
                selected.planIdentity());
        TextIndexMutation barrier = add(
                byBarrier,
                selected.barrierIdentity(),
                selected.planIdentity());
        ActiveSourceMutation activeSources = activeSourceMutation(
                activePlanCountBySource, null, selected);
        return new ManagedCatchUpPlanIndex(
                identityMutation.map(),
                consumer.map(),
                source.map(),
                occurrence.map(),
                barrier.map(),
                activeSources.map(),
                sum(
                        read.comparisons(),
                        identityMutation.comparisons(),
                        consumer.comparisons(),
                        source.comparisons(),
                        occurrence.comparisons(),
                        barrier.comparisons(),
                        activeSources.comparisons()),
                sum(
                        identityMutation.copiedNodes(),
                        consumer.copiedNodes(),
                        source.copiedNodes(),
                        occurrence.copiedNodes(),
                        barrier.copiedNodes(),
                        activeSources.copiedNodes()));
    }

    PlanRead exact(String planIdentity) {
        PersistentOrderedMap.ReadResult<ManagedOccurrenceCatchUpPlan> read =
                byIdentity.read(Objects.requireNonNull(
                        planIdentity, "planIdentity"));
        return new PlanRead(
                read.value(), read.comparisons(), read.found() ? 1 : 0, 0);
    }

    PlanAudit forConsumer(DocumentId consumerDocumentId) {
        return audit(byConsumer, Objects.requireNonNull(
                consumerDocumentId, "consumerDocumentId"));
    }

    PlanAudit forSource(DocumentId sourceDocumentId) {
        return audit(bySource, Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId"));
    }

    PlanAudit forOccurrence(String targetOccurrenceIdentity) {
        return audit(byOccurrence, Objects.requireNonNull(
                targetOccurrenceIdentity, "targetOccurrenceIdentity"));
    }

    PlanAudit forBarrier(String barrierIdentity) {
        return audit(byBarrier, Objects.requireNonNull(
                barrierIdentity, "barrierIdentity"));
    }

    boolean hasActivePlanForSource(DocumentId sourceDocumentId) {
        return activePlanCountBySource.read(Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId")).found();
    }

    int size() {
        return byIdentity.size();
    }

    int lastMutationComparisons() {
        return lastMutationComparisons;
    }

    int lastMutationNodeCopies() {
        return lastMutationNodeCopies;
    }

    void assertStructurallyValid() {
        byIdentity.assertStructurallyValid();
        byConsumer.assertStructurallyValid();
        bySource.assertStructurallyValid();
        byOccurrence.assertStructurallyValid();
        byBarrier.assertStructurallyValid();
        activePlanCountBySource.assertStructurallyValid();
        byConsumer.values().forEach(IdBucket::assertStructurallyValid);
        bySource.values().forEach(IdBucket::assertStructurallyValid);
        byOccurrence.values().forEach(IdBucket::assertStructurallyValid);
        byBarrier.values().forEach(IdBucket::assertStructurallyValid);
        TreeMap<DocumentId, Integer> expectedActive = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (ManagedOccurrenceCatchUpPlan plan : byIdentity.values()) {
            if (belongsToActiveBarrier(plan)) {
                expectedActive.merge(
                        plan.sourceDocumentId(), 1, Math::addExact);
            }
        }
        if (!activePlanCountBySource.entries().equals(
                List.copyOf(expectedActive.entrySet()))) {
            throw new IllegalStateException(
                    "Active catch-up source index is inconsistent");
        }
    }

    private static ActiveSourceMutation activeSourceMutation(
            PersistentOrderedMap<DocumentId, Integer> index,
            ManagedOccurrenceCatchUpPlan before,
            ManagedOccurrenceCatchUpPlan after) {
        boolean wasActive = before != null && belongsToActiveBarrier(before);
        boolean isActive = after != null && belongsToActiveBarrier(after);
        if (wasActive == isActive) {
            return new ActiveSourceMutation(index, 0, 0);
        }
        DocumentId source = (after == null ? before : after)
                .sourceDocumentId();
        PersistentOrderedMap.ReadResult<Integer> read = index.read(source);
        int current = read.found() ? read.value() : 0;
        int next = Math.addExact(current, isActive ? 1 : -1);
        if (next < 0) {
            throw new IllegalStateException(
                    "Active catch-up source count became negative");
        }
        PersistentOrderedMap.Mutation<DocumentId, Integer> mutation = next == 0
                ? index.remove(source)
                : index.put(source, next);
        return new ActiveSourceMutation(
                mutation.map(),
                Math.addExact(read.comparisons(), mutation.comparisons()),
                mutation.copiedNodes());
    }

    private static boolean belongsToActiveBarrier(
            ManagedOccurrenceCatchUpPlan plan) {
        return plan.status() != ManagedCatchUpStatus.COMPLETE
                && plan.status()
                        != ManagedCatchUpStatus
                                .CANCELLED_OCCURRENCE_RETIRED;
    }

    private record ActiveSourceMutation(
            PersistentOrderedMap<DocumentId, Integer> map,
            int comparisons,
            int copiedNodes) {
        private ActiveSourceMutation {
            map = Objects.requireNonNull(map, "map");
        }
    }

    record PlanRead(
            ManagedOccurrenceCatchUpPlan plan,
            int indexComparisons,
            int planRowsRead,
            int unrelatedPlanReads) {
        boolean found() {
            return plan != null;
        }
    }

    record PlanAudit(
            List<ManagedOccurrenceCatchUpPlan> plans,
            int indexComparisons,
            int planRowsRead,
            int unrelatedPlanReads) {
        PlanAudit {
            plans = List.copyOf(Objects.requireNonNull(plans, "plans"));
        }
    }

    private PlanAudit audit(
            PersistentOrderedMap<DocumentId, IdBucket> index,
            DocumentId key) {
        PersistentOrderedMap.ReadResult<IdBucket> bucketRead = index.read(key);
        return audit(bucketRead);
    }

    private PlanAudit audit(
            PersistentOrderedMap<String, IdBucket> index, String key) {
        PersistentOrderedMap.ReadResult<IdBucket> bucketRead = index.read(key);
        return audit(bucketRead);
    }

    private PlanAudit audit(
            PersistentOrderedMap.ReadResult<IdBucket> bucketRead) {
        if (!bucketRead.found()) {
            return new PlanAudit(
                    List.of(), bucketRead.comparisons(), 0, 0);
        }
        ArrayList<ManagedOccurrenceCatchUpPlan> plans = new ArrayList<>();
        int comparisons = bucketRead.comparisons();
        for (String identity : bucketRead.value().identities()) {
            PersistentOrderedMap.ReadResult<ManagedOccurrenceCatchUpPlan> read =
                    byIdentity.read(identity);
            comparisons = Math.addExact(comparisons, read.comparisons());
            if (!read.found()) {
                throw new IllegalStateException(
                        "Catch-up plan index points to a missing plan");
            }
            plans.add(read.value());
        }
        return new PlanAudit(plans, comparisons, plans.size(), 0);
    }

    private void requireUnusedOccurrenceGeneration(
            ManagedOccurrenceCatchUpPlan selected) {
        PlanAudit audit = forOccurrence(
                selected.targetOccurrenceIdentity());
        for (ManagedOccurrenceCatchUpPlan existing : audit.plans()) {
            if (existing.activationGeneration()
                            == selected.activationGeneration()
                    && !existing.planIdentity().equals(
                            selected.planIdentity())) {
                throw new IllegalArgumentException(
                        "An occurrence activation generation already owns a "
                                + "different catch-up plan");
            }
        }
    }

    private static void requireSameDefinition(
            ManagedOccurrenceCatchUpPlan prior,
            ManagedOccurrenceCatchUpPlan changed) {
        if (!prior.barrierIdentity().equals(changed.barrierIdentity())
                || !prior.consumerDocumentId().equals(
                        changed.consumerDocumentId())
                || !prior.targetOccurrenceIdentity().equals(
                        changed.targetOccurrenceIdentity())
                || !prior.targetPath().equals(changed.targetPath())
                || prior.activationGeneration()
                        != changed.activationGeneration()
                || !prior.sourceDocumentId().equals(
                        changed.sourceDocumentId())
                || prior.admittedSourceEpoch()
                        != changed.admittedSourceEpoch()
                || !prior.admittedSourceBlueId().equals(
                        changed.admittedSourceBlueId())
                || !prior.causedByIdentity().equals(
                        changed.causedByIdentity())) {
            throw new IllegalArgumentException(
                    "Catch-up plan definition changed under one identity");
        }
    }

    private static void requireMonotonicTransition(
            ManagedOccurrenceCatchUpPlan prior,
            ManagedOccurrenceCatchUpPlan changed,
            boolean cursorMayAdvance) {
        if (changed.requiredThroughSourceEpoch()
                < prior.requiredThroughSourceEpoch()) {
            throw new IllegalArgumentException(
                    "Catch-up required frontier cannot move backward");
        }
        long expectedCursor = cursorMayAdvance
                ? Math.addExact(prior.nextSourceEpoch(), 1L)
                : prior.nextSourceEpoch();
        if (changed.nextSourceEpoch() != expectedCursor) {
            throw new IllegalArgumentException(
                    "Catch-up cursor must advance exactly once through a "
                            + "committed application");
        }
        ManagedCatchUpStatus before = prior.status();
        ManagedCatchUpStatus after = changed.status();
        if (before == ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED) {
            throw new IllegalArgumentException(
                    "A retired occurrence plan cannot change");
        }
        if (before == ManagedCatchUpStatus.BLOCKED
                && after != ManagedCatchUpStatus
                        .CANCELLED_OCCURRENCE_RETIRED) {
            throw new IllegalArgumentException(
                    "A blocked catch-up plan can only record occurrence "
                            + "retirement");
        }
        if (before == ManagedCatchUpStatus.RUNNING
                && after == ManagedCatchUpStatus.PENDING) {
            throw new IllegalArgumentException(
                    "A running catch-up plan cannot return to pending");
        }
        if (before == ManagedCatchUpStatus.WAITING_FOR_HISTORY
                && after == ManagedCatchUpStatus.PENDING) {
            throw new IllegalArgumentException(
                    "A waiting catch-up plan cannot return to pending");
        }
        if (before == ManagedCatchUpStatus.COMPLETE
                && after != before) {
            throw new IllegalArgumentException(
                    "A complete historical catch-up plan cannot change");
        }
    }

    private static DocumentIndexMutation add(
            PersistentOrderedMap<DocumentId, IdBucket> index,
            DocumentId key,
            String identity) {
        PersistentOrderedMap.ReadResult<IdBucket> read = index.read(key);
        IdBucket prior = read.found() ? read.value() : IdBucket.empty();
        IdBucket changed = prior.adding(identity);
        PersistentOrderedMap.Mutation<DocumentId, IdBucket> mutation =
                index.put(key, changed);
        return new DocumentIndexMutation(
                mutation.map(),
                Math.addExact(
                        read.comparisons(),
                        Math.addExact(
                                changed.lastMutationComparisons(),
                                mutation.comparisons())),
                Math.addExact(
                        changed.lastMutationNodeCopies(),
                        mutation.copiedNodes()));
    }

    private static TextIndexMutation add(
            PersistentOrderedMap<String, IdBucket> index,
            String key,
            String identity) {
        PersistentOrderedMap.ReadResult<IdBucket> read = index.read(key);
        IdBucket prior = read.found() ? read.value() : IdBucket.empty();
        IdBucket changed = prior.adding(identity);
        PersistentOrderedMap.Mutation<String, IdBucket> mutation =
                index.put(key, changed);
        return new TextIndexMutation(
                mutation.map(),
                Math.addExact(
                        read.comparisons(),
                        Math.addExact(
                                changed.lastMutationComparisons(),
                                mutation.comparisons())),
                Math.addExact(
                        changed.lastMutationNodeCopies(),
                        mutation.copiedNodes()));
    }

    private static int sum(int... values) {
        int total = 0;
        for (int value : values) {
            total = Math.addExact(total, value);
        }
        return total;
    }

    private record DocumentIndexMutation(
            PersistentOrderedMap<DocumentId, IdBucket> map,
            int comparisons,
            int copiedNodes) { }

    private record TextIndexMutation(
            PersistentOrderedMap<String, IdBucket> map,
            int comparisons,
            int copiedNodes) { }

    private static final class IdBucket {
        private final PersistentOrderedMap<String, Boolean> identities;
        private final int lastMutationComparisons;
        private final int lastMutationNodeCopies;

        private IdBucket(
                PersistentOrderedMap<String, Boolean> identities,
                int lastMutationComparisons,
                int lastMutationNodeCopies) {
            this.identities = identities;
            this.lastMutationComparisons = lastMutationComparisons;
            this.lastMutationNodeCopies = lastMutationNodeCopies;
        }

        static IdBucket empty() {
            return new IdBucket(
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
                    0,
                    0);
        }

        IdBucket adding(String identity) {
            if (identities.containsKey(identity)) {
                return this;
            }
            PersistentOrderedMap.Mutation<String, Boolean> mutation =
                    identities.put(identity, Boolean.TRUE);
            return new IdBucket(
                    mutation.map(),
                    mutation.comparisons(),
                    mutation.copiedNodes());
        }

        List<String> identities() {
            return identities.keys();
        }

        int lastMutationComparisons() {
            return lastMutationComparisons;
        }

        int lastMutationNodeCopies() {
            return lastMutationNodeCopies;
        }

        void assertStructurallyValid() {
            identities.assertStructurallyValid();
        }
    }
}
