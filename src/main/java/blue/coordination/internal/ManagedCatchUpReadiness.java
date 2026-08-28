package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads readiness dependencies without changing occurrence or plan ownership.
 * A Root's ready frontier includes the barriers of its declared managed sources;
 * their plans remain in those sources' own consumer buckets. Cycles are visited
 * once and unrelated document buckets are never scanned.
 */
final class ManagedCatchUpReadiness {
    private final CatchUpPlanStore plans;
    private final ManagedOccurrenceInventory inventory;
    private final Map<DocumentId, CatchUpPlanStore.ActiveBarriersRead> cached = new HashMap<>();

    ManagedCatchUpReadiness(CatchUpPlanStore plans, ManagedOccurrenceInventory inventory) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    boolean blocked(DocumentId documentId) {
        return !read(documentId).barrierIdentities().isEmpty();
    }

    /** Readiness changes flow to declared parents even when they perform no work. */
    static Set<DocumentId> dependents(Collection<DocumentId> changed,
            ManagedOccurrenceInventory before, ManagedOccurrenceInventory after) {
        Set<DocumentId> result = new TreeSet<>(EmbeddingBinding.DOCUMENT_ORDER);
        ArrayDeque<DocumentId> pending = new ArrayDeque<>(changed);
        while (!pending.isEmpty()) {
            DocumentId child = pending.removeFirst();
            if (!result.add(child)) {
                continue;
            }
            addParents(child, before, pending);
            if (after != before) {
                addParents(child, after, pending);
            }
        }
        return result;
    }

    private static void addParents(DocumentId child, ManagedOccurrenceInventory inventory,
            ArrayDeque<DocumentId> pending) {
        for (ManagedOccurrenceBinding row : inventory.rowsTouching(child)) {
            if (row.targetDocumentId().value().equals(child.value())
                    && (row.active() || row.pendingHistoricalEpoch() != null)) {
                pending.addLast(DocumentId.of(row.sourceDocumentId().value()));
            }
        }
    }

    CatchUpPlanStore.ActiveBarriersRead read(DocumentId documentId) {
        return cached.computeIfAbsent(Objects.requireNonNull(documentId, "documentId"), this::collect);
    }

    private CatchUpPlanStore.ActiveBarriersRead collect(DocumentId documentId) {
        if (!plans.hasActiveBarriers()) {
            return plans.activeBarriersForConsumer(documentId);
        }
        Map<String, ManagedCatchUpBarrier> barriers = new TreeMap<>(EmbeddingBinding.TEXT_ORDER);
        Set<DocumentId> visited = new HashSet<>();
        ArrayDeque<DocumentId> pending = new ArrayDeque<>();
        pending.add(documentId);
        int comparisons = 0;
        int planRows = 0;
        int barrierRows = 0;
        int unrelated = 0;
        while (!pending.isEmpty()) {
            DocumentId owner = pending.removeFirst();
            if (!visited.add(owner)) {
                continue;
            }
            CatchUpPlanStore.ActiveBarriersRead own = plans.activeBarriersForConsumer(owner);
            comparisons = Math.addExact(comparisons, own.indexComparisons());
            planRows = Math.addExact(planRows, own.planRowsRead());
            barrierRows = Math.addExact(barrierRows, own.barrierRowsRead());
            unrelated = Math.addExact(unrelated, own.unrelatedPlanReads());
            own.barriers().forEach(barrier -> barriers.put(barrier.barrierIdentity(), barrier));
            for (ManagedOccurrenceBinding occurrence : inventory.rowsFrom(owner)) {
                if (occurrence.active() || occurrence.pendingHistoricalEpoch() != null) {
                    pending.addLast(DocumentId.of(occurrence.targetDocumentId().value()));
                }
            }
        }
        return new CatchUpPlanStore.ActiveBarriersRead(List.copyOf(barriers.keySet()),
                List.copyOf(barriers.values()), comparisons, planRows, barrierRows, unrelated);
    }
}
