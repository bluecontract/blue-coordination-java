package blue.coordination.processor;

/**
 * Nonportable Coordination preparation and provider-side safety quotas.
 *
 * <p>These limits are deliberately separate from portable {@code PROCESS}
 * gas. They bound host work performed before or outside the processor's
 * semantic invocation and are mirrored by
 * {@code coordination-host-quotas-1.0.yaml}.</p>
 */
public final class CoordinationHostQuotas {
    private static final CoordinationHostQuotaSchedule SCHEDULE =
            CoordinationHostQuotaSchedule.defaults();

    public static final int MAX_SPLITTER_CUTS =
            SCHEDULE.maxSplitterCuts();
    public static final int MAX_MANDATE_CANDIDATES_PER_DECISION =
            SCHEDULE.maxMandateCandidatesPerDecision();
    public static final int MAX_SPLITTER_CATALOG_ENTRIES_PER_SPLIT =
            SCHEDULE.maxSplitterCatalogEntriesPerSplit();
    public static final int MAX_SPLITTER_FRAGMENTS_PER_SPLIT =
            SCHEDULE.maxSplitterFragmentsPerSplit();
    public static final int MAX_FRAGMENT_EDGE_OCCURRENCES_PER_SPLIT =
            SCHEDULE.maxFragmentEdgeOccurrencesPerSplit();
    public static final int MAX_SUBSCRIPTION_OCCURRENCES_PER_PROJECTION =
            SCHEDULE.maxSubscriptionOccurrencesPerProjection();
    public static final int MAX_INDEXED_CANDIDATES_PER_PLAN =
            SCHEDULE.maxIndexedCandidatesPerPlan();
    public static final int MAX_PREFETCH_IDENTITIES_PER_PLAN =
            SCHEDULE.maxPrefetchIdentitiesPerPlan();

    private CoordinationHostQuotas() {
    }

    /**
     * Returns the immutable manifest-backed host quota schedule.
     *
     * @return bundled host quota schedule
     */
    public static CoordinationHostQuotaSchedule schedule() {
        return SCHEDULE;
    }
}
