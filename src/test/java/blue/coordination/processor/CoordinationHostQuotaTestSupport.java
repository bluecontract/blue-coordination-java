package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact manifest and embedded-root fixtures for host-quota tests.
 */
final class CoordinationHostQuotaTestSupport {
    private static final int DEFAULT_SPLITTER_CUTS = 16384;
    private static final int DEFAULT_MANDATE_CANDIDATES = 4096;
    private static final int DEFAULT_SPLITTER_CATALOG_ENTRIES = 65536;
    private static final int DEFAULT_SPLITTER_FRAGMENTS = 65536;
    private static final int DEFAULT_FRAGMENT_EDGE_OCCURRENCES = 262144;
    private static final int DEFAULT_SUBSCRIPTION_OCCURRENCES = 65536;
    private static final int DEFAULT_INDEXED_CANDIDATES = 65536;
    private static final int DEFAULT_PREFETCH_IDENTITIES = 65536;

    private CoordinationHostQuotaTestSupport() {
    }

    static CoordinationHostQuotaSchedule schedule(
            int maxSplitterCuts,
            int maxMandateCandidates) {
        return schedule(
                maxSplitterCuts,
                maxMandateCandidates,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedCatalogEntries(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                limit,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedSplitterFragments(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                limit,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedFragmentEdges(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                limit,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedSubscriptionOccurrences(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                limit,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedIndexedCandidates(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                limit,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static CoordinationHostQuotaSchedule limitedPrefetchIdentities(
            int limit) {
        return schedule(
                DEFAULT_SPLITTER_CUTS,
                DEFAULT_MANDATE_CANDIDATES,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                limit);
    }

    static CoordinationHostQuotaSchedule schedule(
            int maxSplitterCuts,
            int maxMandateCandidates,
            int maxSplitterCatalogEntries,
            int maxSplitterFragments,
            int maxFragmentEdgeOccurrences,
            int maxSubscriptionOccurrences,
            int maxIndexedCandidates,
            int maxPrefetchIdentities) {
        return CoordinationHostQuotaSchedule.load(
                new ByteArrayInputStream(
                        manifest(
                                maxSplitterCuts,
                                maxMandateCandidates,
                                maxSplitterCatalogEntries,
                                maxSplitterFragments,
                                maxFragmentEdgeOccurrences,
                                maxSubscriptionOccurrences,
                                maxIndexedCandidates,
                                maxPrefetchIdentities)
                                .getBytes(
                                        StandardCharsets.UTF_8)));
    }

    static String manifest(
            int maxSplitterCuts,
            int maxMandateCandidates) {
        return manifest(
                maxSplitterCuts,
                maxMandateCandidates,
                DEFAULT_SPLITTER_CATALOG_ENTRIES,
                DEFAULT_SPLITTER_FRAGMENTS,
                DEFAULT_FRAGMENT_EDGE_OCCURRENCES,
                DEFAULT_SUBSCRIPTION_OCCURRENCES,
                DEFAULT_INDEXED_CANDIDATES,
                DEFAULT_PREFETCH_IDENTITIES);
    }

    static String manifest(
            int maxSplitterCuts,
            int maxMandateCandidates,
            int maxSplitterCatalogEntries,
            int maxSplitterFragments,
            int maxFragmentEdgeOccurrences,
            int maxSubscriptionOccurrences,
            int maxIndexedCandidates,
            int maxPrefetchIdentities) {
        return "schedule: blue-coordination/host-quotas/1.0\n"
                + "status: nonportable-diagnostic\n"
                + "portableProcessGas: false\n"
                + "description: Exact test host quota schedule.\n"
                + "counters:\n"
                + "- name: splitterCatalogEntryVisited\n"
                + "  unit: one catalog entry\n"
                + "- name: splitterFragmentAdmitted\n"
                + "  unit: one retained fragment\n"
                + "- name: splitterCutValidated\n"
                + "  unit: one validated cut\n"
                + "- name: mandatePredicateEvaluated\n"
                + "  unit: one mandate predicate\n"
                + "- name: responderMandateCandidateTested\n"
                + "  unit: one responder candidate\n"
                + "- name: subscriptionOccurrenceProjected\n"
                + "  unit: one subscription occurrence\n"
                + "- name: indexedCandidateValidated\n"
                + "  unit: one indexed candidate\n"
                + "- name: prefetchIdentityConstructed\n"
                + "  unit: one prefetch identity\n"
                + "- name: fragmentEdgeMetadataProduced\n"
                + "  unit: one fragment edge occurrence\n"
                + "limits:\n"
                + "  maxSplitterCuts: "
                + maxSplitterCuts
                + "\n"
                + "  maxMandateCandidatesPerDecision: "
                + maxMandateCandidates
                + "\n"
                + "  maxSplitterCatalogEntriesPerSplit: "
                + maxSplitterCatalogEntries
                + "\n"
                + "  maxSplitterFragmentsPerSplit: "
                + maxSplitterFragments
                + "\n"
                + "  maxFragmentEdgeOccurrencesPerSplit: "
                + maxFragmentEdgeOccurrences
                + "\n"
                + "  maxSubscriptionOccurrencesPerProjection: "
                + maxSubscriptionOccurrences
                + "\n"
                + "  maxIndexedCandidatesPerPlan: "
                + maxIndexedCandidates
                + "\n"
                + "  maxPrefetchIdentitiesPerPlan: "
                + maxPrefetchIdentities
                + "\n";
    }

    static Node embeddedRoot(int childCount) {
        Node root = new Node();
        List<Node> paths = new ArrayList<Node>();
        for (int index = 1; index <= childCount; index++) {
            String child = "child" + index;
            root.properties(
                    child,
                    new Node().properties(
                            "ordinal",
                            new Node().value(index)));
            paths.add(
                    new Node().value("/" + child));
        }
        Node processEmbedded =
                new Node()
                        .type(
                                new Node().blueId(
                                        RuntimeBlueIds
                                                .PROCESS_EMBEDDED))
                        .properties(
                                "paths",
                                new Node().items(paths));
        return root.contracts(
                new Node().properties(
                        "embedded",
                        processEmbedded));
    }
}
