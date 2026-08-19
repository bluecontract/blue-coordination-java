package blue.coordination.internal;

import java.util.Objects;

/**
 * Raw diagnostics for known environment-sized closure publication work.
 * Each charge uses the source collection cardinality at an actual global
 * traversal or copy boundary; repeated passes charge the entries repeatedly.
 */
final class ContractsStructuralWorkMetrics {
    static final String GLOBAL_STATE_PASSES =
            "contracts.publication.globalStatePasses";
    static final String GLOBAL_STATE_ENTRIES_TRAVERSED =
            "contracts.publication.globalStateEntriesTraversed";
    static final String GLOBAL_SESSION_ENTRIES_TRAVERSED =
            "contracts.publication.globalSessionEntriesTraversed";
    static final String GLOBAL_OCCURRENCE_ENTRIES_TRAVERSED =
            "contracts.publication.globalOccurrenceEntriesTraversed";
    static final String GLOBAL_COMPONENT_ENTRIES_TRAVERSED =
            "contracts.publication.globalComponentEntriesTraversed";
    static final String GLOBAL_GRAPH_ENTRIES_TRAVERSED =
            "contracts.publication.globalGraphEntriesTraversed";
    static final String GLOBAL_SUBSCRIPTION_ENTRIES_TRAVERSED =
            "contracts.publication.globalSubscriptionEntriesTraversed";
    static final String GLOBAL_RECEIPT_ENTRIES_TRAVERSED =
            "contracts.publication.globalReceiptEntriesTraversed";
    static final String GLOBAL_ROUTE_ENTRIES_TRAVERSED =
            "contracts.publication.globalRouteEntriesTraversed";
    static final String GLOBAL_EVIDENCE_ENTRIES_TRAVERSED =
            "contracts.publication.globalEvidenceEntriesTraversed";

    private ContractsStructuralWorkMetrics() {
    }

    static void recordGlobalPass(
            EngineMetrics metrics,
            String category,
            long entriesTraversed) {
        recordGlobalPasses(metrics, category, 1L, entriesTraversed);
    }

    static void recordGlobalPasses(
            EngineMetrics metrics,
            String category,
            long passes,
            long entriesTraversed) {
        if (passes < 0L || entriesTraversed < 0L) {
            throw new IllegalArgumentException(
                    "Global traversal measurements must be non-negative");
        }
        EngineMetrics selected = Objects.requireNonNull(metrics, "metrics");
        selected.add(GLOBAL_STATE_PASSES, passes);
        selected.add(GLOBAL_STATE_ENTRIES_TRAVERSED, entriesTraversed);
        selected.add(Objects.requireNonNull(category, "category"),
                entriesTraversed);
    }
}
