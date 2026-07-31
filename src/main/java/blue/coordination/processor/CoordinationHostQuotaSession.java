package blue.coordination.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Invocation-local enforcement and diagnostics for nonportable host work.
 *
 * <p>A session is passed explicitly to one splitter, projection, indexed
 * planner, or Mandate helper call. It owns no global state and never opens a
 * portable runtime ledger. Disabled sessions preserve quota enforcement
 * without retaining diagnostic entries, which keeps existing API overloads
 * behavior-compatible.</p>
 */
public final class CoordinationHostQuotaSession {
    static final String SPLIT_DOCUMENT = "split-document";
    static final String SPLIT_EVENT = "split-event";
    static final String PROJECT_CURRENT_SUBSCRIPTIONS =
            "project-current-subscriptions";
    static final String PROJECT_UPDATED_SUBSCRIPTIONS =
            "project-updated-subscriptions";
    static final String PREPARE_INDEXED_DELIVERY =
            "prepare-indexed-delivery";
    private static final String OPERATION_MANDATE =
            "operation-mandate-eligibility";
    private static final String DOCUMENT_RESPONDER_MANDATE =
            "document-responder-mandate-eligibility";

    private final CoordinationHostQuotaSchedule schedule;
    private final boolean observing;
    private final List<CoordinationHostQuotaTraceEntry> trace =
            new ArrayList<CoordinationHostQuotaTraceEntry>();
    private long nextSequence;
    private long splitterCatalogEntriesAdmitted;
    private long splitterFragmentsAdmitted;
    private long splitterCutsAdmitted;
    private long fragmentEdgeOccurrencesAdmitted;
    private long subscriptionOccurrencesAdmitted;
    private long indexedCandidatesAdmitted;
    private long prefetchIdentitiesAdmitted;

    private CoordinationHostQuotaSession(
            CoordinationHostQuotaSchedule schedule,
            boolean observing) {
        this.schedule = Objects.requireNonNull(
                schedule, "schedule");
        this.observing = observing;
    }

    /**
     * Creates a session that enforces limits and retains an exact trace.
     *
     * @return observing session backed by the bundled schedule
     */
    public static CoordinationHostQuotaSession observing() {
        return observing(
                CoordinationHostQuotaSchedule.defaults());
    }

    /**
     * Creates an observing session for an explicit immutable schedule.
     *
     * @param schedule immutable host quota schedule to enforce
     * @return observing session backed by the supplied schedule
     */
    public static CoordinationHostQuotaSession observing(
            CoordinationHostQuotaSchedule schedule) {
        return new CoordinationHostQuotaSession(
                schedule, true);
    }

    /**
     * Creates a no-trace session that still enforces manifest limits.
     *
     * @return non-observing session backed by the bundled schedule
     */
    public static CoordinationHostQuotaSession disabled() {
        return disabled(
                CoordinationHostQuotaSchedule.defaults());
    }

    static CoordinationHostQuotaSession disabled(
            CoordinationHostQuotaSchedule schedule) {
        return new CoordinationHostQuotaSession(
                schedule, false);
    }

    /**
     * Returns the immutable schedule used by this invocation.
     *
     * @return this session's immutable host quota schedule
     */
    public CoordinationHostQuotaSchedule schedule() {
        return schedule;
    }

    /**
     * Returns a defensive immutable snapshot of admitted observations.
     *
     * @return immutable copy of the trace in admission order
     */
    public synchronized List<CoordinationHostQuotaTraceEntry>
    trace() {
        return Collections.unmodifiableList(
                new ArrayList<CoordinationHostQuotaTraceEntry>(
                        trace));
    }

    /**
     * Returns the admitted quantity for one supported counter.
     *
     * @param counter supported counter name
     * @return total quantity retained for the counter
     */
    public synchronized long quantity(String counter) {
        if (!schedule.supportsCounter(counter)) {
            throw new IllegalArgumentException(
                    "Unknown Coordination host counter "
                            + counter);
        }
        long total = 0L;
        for (CoordinationHostQuotaTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                total = Math.addExact(
                        total,
                        entry.quantity());
            }
        }
        return total;
    }

    synchronized void recordSplitterCatalogEntry(
            String logicalPath,
            String reason) {
        splitterCatalogEntriesAdmitted =
                admitOne(
                        "maxSplitterCatalogEntriesPerSplit",
                        schedule
                                .maxSplitterCatalogEntriesPerSplit(),
                        splitterCatalogEntriesAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .SPLITTER_CATALOG_ENTRY_VISITED,
                SPLIT_DOCUMENT,
                logicalPath,
                reason);
    }

    synchronized void recordSplitterFragment(
            String operation,
            String logicalPath,
            String reason) {
        splitterFragmentsAdmitted =
                admitOne(
                        "maxSplitterFragmentsPerSplit",
                        schedule
                                .maxSplitterFragmentsPerSplit(),
                        splitterFragmentsAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .SPLITTER_FRAGMENT_ADMITTED,
                operation,
                logicalPath,
                reason);
    }

    synchronized void recordSplitterCut(
            String logicalPath,
            String reason) {
        splitterCutsAdmitted =
                admitOne(
                        "maxSplitterCuts",
                        schedule.maxSplitterCuts(),
                        splitterCutsAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .SPLITTER_CUT_VALIDATED,
                SPLIT_DOCUMENT,
                logicalPath,
                reason);
    }

    synchronized void recordFragmentEdgeMetadata(
            String operation,
            String logicalPath,
            String reason) {
        fragmentEdgeOccurrencesAdmitted =
                admitOne(
                        "maxFragmentEdgeOccurrencesPerSplit",
                        schedule
                                .maxFragmentEdgeOccurrencesPerSplit(),
                        fragmentEdgeOccurrencesAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .FRAGMENT_EDGE_METADATA_PRODUCED,
                operation,
                logicalPath,
                reason);
    }

    synchronized void recordSubscriptionOccurrence(
            String operation,
            int occurrenceIndex,
            String reason) {
        if (occurrenceIndex < 0) {
            throw new IllegalArgumentException(
                    "occurrenceIndex must be non-negative");
        }
        subscriptionOccurrencesAdmitted =
                admitOne(
                        "maxSubscriptionOccurrencesPerProjection",
                        schedule
                                .maxSubscriptionOccurrencesPerProjection(),
                        subscriptionOccurrencesAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .SUBSCRIPTION_OCCURRENCE_PROJECTED,
                operation,
                "/occurrences/" + occurrenceIndex,
                reason);
    }

    /**
     * Rejects a projection when a cheaply established lower bound cannot fit
     * in the remaining occurrence quota.
     *
     * <p>This is a non-recording preflight. The exact Language projection
     * remains authoritative and {@link #recordSubscriptionOccurrence(String,
     * int, String)} records only occurrences actually returned by that
     * projection.</p>
     *
     * @param minimumOccurrences conservative lower bound for the pending
     * projection
     */
    synchronized void requireSubscriptionProjectionCapacity(
            long minimumOccurrences) {
        if (minimumOccurrences < 0L) {
            throw new IllegalArgumentException(
                    "minimumOccurrences must be non-negative");
        }
        long attempted =
                Math.addExact(
                        subscriptionOccurrencesAdmitted,
                        minimumOccurrences);
        long limit =
                schedule
                        .maxSubscriptionOccurrencesPerProjection();
        if (attempted > limit) {
            throw new CoordinationHostQuotaExceededException(
                    "maxSubscriptionOccurrencesPerProjection",
                    limit,
                    attempted,
                    subscriptionOccurrencesAdmitted);
        }
    }

    synchronized void recordIndexedCandidate(
            int candidateIndex) {
        if (candidateIndex < 0) {
            throw new IllegalArgumentException(
                    "candidateIndex must be non-negative");
        }
        indexedCandidatesAdmitted =
                admitOne(
                        "maxIndexedCandidatesPerPlan",
                        schedule
                                .maxIndexedCandidatesPerPlan(),
                        indexedCandidatesAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .INDEXED_CANDIDATE_VALIDATED,
                PREPARE_INDEXED_DELIVERY,
                "/indexed-candidates/" + candidateIndex,
                "candidate");
    }

    synchronized void recordPrefetchIdentity(
            int prefetchIndex) {
        if (prefetchIndex < 0) {
            throw new IllegalArgumentException(
                    "prefetchIndex must be non-negative");
        }
        prefetchIdentitiesAdmitted =
                admitOne(
                        "maxPrefetchIdentitiesPerPlan",
                        schedule
                                .maxPrefetchIdentitiesPerPlan(),
                        prefetchIdentitiesAdmitted);
        record(
                CoordinationHostQuotaSchedule
                        .PREFETCH_IDENTITY_CONSTRUCTED,
                PREPARE_INDEXED_DELIVERY,
                "/prefetch/" + prefetchIndex,
                "identity");
    }

    /**
     * Checks the manifest-backed responder candidate limit without recording
     * candidate work.
     *
     * @param candidateCount number of candidates proposed for the decision
     * @return whether the count is within the configured limit
     */
    public synchronized boolean admitsResponderCandidates(
            int candidateCount) {
        if (candidateCount < 0) {
            throw new IllegalArgumentException(
                    "candidateCount must be non-negative");
        }
        return candidateCount
                <= schedule
                .maxMandateCandidatesPerDecision();
    }

    /**
     * Records one named Operation Mandate eligibility guard before evaluation.
     *
     * @param logicalPath logical evidence path guarded by the predicate
     * @param reason stable reason naming the predicate
     */
    public synchronized void recordOperationMandatePredicate(
            String logicalPath,
            String reason) {
        record(
                CoordinationHostQuotaSchedule
                        .MANDATE_PREDICATE_EVALUATED,
                OPERATION_MANDATE,
                logicalPath,
                reason);
    }

    /**
     * Records one named Document Responder Mandate guard before evaluation.
     *
     * @param logicalPath logical evidence path guarded by the predicate
     * @param reason stable reason naming the predicate
     */
    public synchronized void recordDocumentResponderMandatePredicate(
            String logicalPath,
            String reason) {
        record(
                CoordinationHostQuotaSchedule
                        .MANDATE_PREDICATE_EVALUATED,
                DOCUMENT_RESPONDER_MANDATE,
                logicalPath,
                reason);
    }

    /**
     * Records one provider-side candidate immediately before it is tested.
     *
     * @param candidateIndex zero-based candidate index
     */
    public synchronized void recordResponderCandidate(
            int candidateIndex) {
        if (candidateIndex < 0) {
            throw new IllegalArgumentException(
                    "candidateIndex must be non-negative");
        }
        record(
                CoordinationHostQuotaSchedule
                        .RESPONDER_MANDATE_CANDIDATE_TESTED,
                DOCUMENT_RESPONDER_MANDATE,
                "/candidates/" + candidateIndex,
                "candidate");
    }

    private static long admitOne(
            String limitName,
            long limit,
            long admitted) {
        long attempted =
                Math.addExact(admitted, 1L);
        if (attempted > limit) {
            throw new CoordinationHostQuotaExceededException(
                    limitName,
                    limit,
                    attempted,
                    admitted);
        }
        return attempted;
    }

    private void record(
            String counter,
            String operation,
            String logicalPath,
            String reason) {
        if (!schedule.supportsCounter(counter)) {
            throw new IllegalArgumentException(
                    "Unknown Coordination host counter "
                            + counter);
        }
        if (!observing) {
            return;
        }
        trace.add(
                new CoordinationHostQuotaTraceEntry(
                        nextSequence,
                        counter,
                        1L,
                        operation,
                        logicalPath,
                        reason));
        nextSequence =
                Math.addExact(nextSequence, 1L);
    }
}
