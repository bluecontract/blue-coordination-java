package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.ManagedCatchUpStatus;
import java.util.*;

/** Source counts are derived queries, never a shared mutable counter written by independent consumers. */
final class LogicalPlanCounts implements LogicalRecordMap.Source<DocumentId, Integer> {
    private final PersistentOrderedMap<String, ManagedOccurrenceCatchUpPlan> plans;
    private final PersistentOrderedMap<DocumentId, ManagedCatchUpPlanIndex.IdBucket> sources;

    private LogicalPlanCounts(PersistentOrderedMap<String, ManagedOccurrenceCatchUpPlan> plans,
            PersistentOrderedMap<DocumentId, ManagedCatchUpPlanIndex.IdBucket> sources) { this.plans = plans; this.sources = sources; }

    static PersistentOrderedMap<DocumentId, Integer> open(LogicalRecordContext context,
            PersistentOrderedMap<String, ManagedOccurrenceCatchUpPlan> plans,
            PersistentOrderedMap<DocumentId, ManagedCatchUpPlanIndex.IdBucket> sources) {
        return PersistentOrderedMap.logical(EmbeddingBinding.DOCUMENT_ORDER,
                LogicalRecordMap.virtual(EmbeddingBinding.DOCUMENT_ORDER, context, new LogicalPlanCounts(plans, sources),
                        (key, value) -> { throw new IllegalStateException("Derived active-source counts cannot be written"); }, value -> value > 0));
    }

    @Override public Integer get(DocumentId source) {
        var members = sources.get(source); if (members == null) return null;
        int count = 0;
        for (String identity : members.identities()) {
            var plan = plans.get(identity);
            if (plan == null || !source.equals(plan.sourceDocumentId())) throw new IllegalStateException("Invalid source-plan membership");
            if (plan.status() != ManagedCatchUpStatus.COMPLETE && plan.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED)
                count = Math.addExact(count, 1);
        }
        return count == 0 ? null : count;
    }
    @Override public boolean contains(DocumentId source) { return get(source) != null; }
    @Override public List<Map.Entry<DocumentId, Integer>> entries() {
        var result = new ArrayList<Map.Entry<DocumentId, Integer>>();
        for (var source : sources.keys()) { var count = get(source); if (count != null) result.add(Map.entry(source, count)); }
        return List.copyOf(result);
    }
    @Override public Map.Entry<DocumentId, Integer> first(DocumentId lower, boolean exclusive, DocumentId upper) {
        var selected = lower == null ? sources.minimum() : exclusive ? sources.higherThan(lower) : null;
        if (selected == null) {
            var iterator = sources.range(lower, upper);
            if (!iterator.hasNext()) return null;
            var row = iterator.next(); selected = new PersistentOrderedMap.MinimumResult<>(row, 1);
        }
        while (selected.found() && (upper == null || EmbeddingBinding.DOCUMENT_ORDER.compare(selected.entry().getKey(), upper) < 0)) {
            var owner = selected.entry().getKey(); var count = get(owner);
            if (count != null) return Map.entry(owner, count);
            selected = sources.higherThan(owner);
        }
        return null;
    }
}
