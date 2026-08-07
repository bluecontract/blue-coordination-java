package blue.coordination.engine.internal;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.engine.api.ChangeKind;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationPreparedDelivery;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.CoordinationSubscriptionUpdate;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives the immutable fragment, edge, and changed-scope projection of a
 * PROCESS result.
 *
 * <p>Identity comparison decides reuse; Java object identity is never used.
 * The normal planner consumes the already materialized semantic PROCESS
 * result and never reconstructs a full prior Root. It performs one bounded
 * canonical-winner lookup only for each identity absent from the prior
 * inventory, so an immutable physical body already admitted by another graph
 * remains authoritative. The splitter's direct-cut blueprint remains the
 * physical authority, while the full canonical split is retained only as an
 * explicit differential oracle. Unchanged physical bodies are retained by
 * exact identity and edge occurrences are deterministically re-derived for
 * the new Root binding.</p>
 */
public final class CoordinationFragmentTransitionPlanner {

    private final CoordinationDocumentSplitter splitter;
    private final NodeProvider canonicalPhysicalProvider;
    private final CoordinationFragmentStore canonicalPhysicalStore;

    /**
     * Creates an isolated planner that assumes no cross-inventory physical
     * winners exist.
     *
     * <p>This compatibility form is suitable for offline differential tests.
     * Managed engines should supply their canonical physical provider through
     * {@link #CoordinationFragmentTransitionPlanner(
     * CoordinationDocumentSplitter, NodeProvider)}.</p>
     *
     * @param splitter exact Coordination document splitter
     */
    public CoordinationFragmentTransitionPlanner(
            CoordinationDocumentSplitter splitter) {
        this(
                splitter,
                new NodeProvider() {
                    @Override
                    public List<Node> fetchByBlueId(String blueId) {
                        return Collections.emptyList();
                    }

                    @Override
                    public NodeProviderResult fetchResultByBlueId(
                            String blueId) {
                        Objects.requireNonNull(blueId, "blueId");
                        return NodeProviderResult.notFound();
                    }
                });
    }

    /**
     * Creates a planner bound to the immutable physical-fragment namespace.
     *
     * @param splitter exact Coordination document splitter
     * @param canonicalPhysicalProvider canonical physical winner provider
     */
    public CoordinationFragmentTransitionPlanner(
            CoordinationDocumentSplitter splitter,
            NodeProvider canonicalPhysicalProvider) {
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        NodeProvider checked = Objects.requireNonNull(
                canonicalPhysicalProvider, "canonicalPhysicalProvider");
        this.canonicalPhysicalStore = checked
                instanceof CoordinationFragmentStore
                ? (CoordinationFragmentStore) checked
                : null;
        this.canonicalPhysicalProvider = canonicalPhysicalStore != null
                ? canonicalPhysicalStore.canonicalFragmentProvider()
                : checked;
    }

    public CoordinationFragmentTransition plan(
            CoordinationFragmentInventory priorInventory,
            Node resultingExactRoot,
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationSubscriptionUpdate subscriptionUpdate) {
        Node result = Objects.requireNonNull(
                resultingExactRoot, "resultingExactRoot");
        return planVerifiedInternal(
                priorInventory,
                result,
                DirectBlueIdCalculator.calculateBlueId(result),
                preparedDelivery,
                subscriptionUpdate);
    }

    /**
     * Plans from the Root identity already proved by the single PROCESS
     * result digest pass. The resulting inventory remains an independent
     * binding check for that identity. Only the engine can construct the
     * required access authority; ordinary callers must use {@link #plan},
     * which calculates the supplied mutable Root's identity itself.
     */
    public CoordinationFragmentTransition planVerified(
            VerifiedNodeAccessAuthority accessAuthority,
            CoordinationFragmentInventory priorInventory,
            Node resultingExactRoot,
            String verifiedResultingRootBlueId,
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationSubscriptionUpdate subscriptionUpdate) {
        Objects.requireNonNull(
                accessAuthority, "verifiedNodeAccessAuthority");
        return planVerifiedInternal(
                priorInventory,
                resultingExactRoot,
                verifiedResultingRootBlueId,
                preparedDelivery,
                subscriptionUpdate);
    }

    private CoordinationFragmentTransition planVerifiedInternal(
            CoordinationFragmentInventory priorInventory,
            Node resultingExactRoot,
            String verifiedResultingRootBlueId,
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationSubscriptionUpdate subscriptionUpdate) {
        CoordinationFragmentInventory prior = Objects.requireNonNull(
                priorInventory, "priorInventory");
        /* The engine owns this exact result for the duration of transition
         * planning. The splitter takes its own canonical defensive copy, so
         * an eager full-Root clone here only duplicates linear work. */
        Node result = Objects.requireNonNull(
                resultingExactRoot, "resultingExactRoot");
        CoordinationPreparedDelivery prepared = Objects.requireNonNull(
                preparedDelivery, "preparedDelivery");
        CoordinationSubscriptionUpdate subscriptions =
                Objects.requireNonNull(
                        subscriptionUpdate, "subscriptionUpdate");
        String resultingRootBlueId = Objects.requireNonNull(
                verifiedResultingRootBlueId,
                "verifiedResultingRootBlueId");
        EffectiveFragmentationCatalog catalog = subscriptions
                .fragmentationCatalog()
                .orElse(null);
        if (catalog != null
                && !resultingRootBlueId.equals(catalog.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Subscription update fragmentation catalog does not "
                            + "match the exact resulting Root");
        }
        if (prior.rootBlueId().equals(resultingRootBlueId)) {
            return new CoordinationFragmentTransition(
                    prior,
                    Collections.<String, Node>emptyMap(),
                    Collections.<String, Node>emptyMap(),
                    prior.fragmentBlueIds(),
                    Collections.<FragmentEdgeRecord>emptyList(),
                    Collections.<FragmentEdgeRecord>emptyList(),
                    Collections.<CoordinationScopeTransition>emptyList());
        }

        CoordinationDocumentSplitter.DocumentFragmentationBlueprint
                blueprint = catalog != null
                ? splitter.documentFragmentationBlueprint(result, catalog)
                : splitter.documentFragmentationBlueprint(result);
        CoordinationIncrementalFragmentAssembler.AssembledDocument assembled =
                new CoordinationIncrementalFragmentAssembler(
                        splitter,
                        canonicalPhysicalProvider,
                        canonicalPhysicalStore)
                        .assemble(
                                prior,
                                blueprint,
                                causalScopePaths(
                                        prepared,
                                        subscriptions));
        CoordinationFragmentInventory resulting = assembled.inventory();
        if (!resultingRootBlueId.equals(resulting.rootBlueId())) {
            throw new IllegalStateException(
                    "Incremental inventory changed the resulting Root identity");
        }

        return transition(
                prior,
                resulting,
                assembled.newFragments(),
                assembled.processingViews());
    }

    /**
     * Explicit full-split oracle for deterministic differential verification.
     * Production transition planning never calls this method.
     */
    CoordinationFragmentTransition planCanonicalOracle(
            CoordinationFragmentInventory priorInventory,
            Node resultingExactRoot) {
        CoordinationFragmentInventory prior = Objects.requireNonNull(
                priorInventory, "priorInventory");
        Node result = Objects.requireNonNull(
                resultingExactRoot, "resultingExactRoot").clone();
        CoordinationDocumentSplitter.SplitGraph graph =
                splitter.splitDocument(result);
        CoordinationFragmentInventory resulting =
                CoordinationFragmentInventory.from(graph);
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(result);
        if (!rootBlueId.equals(resulting.rootBlueId())
                || !rootBlueId.equals(
                        DirectBlueIdCalculator.calculateBlueId(
                                graph.reconstruct()))) {
            throw new IllegalStateException(
                    "Canonical oracle does not reconstruct the exact result");
        }
        Set<String> priorIds = new LinkedHashSet<String>(
                prior.fragmentBlueIds());
        Map<String, Node> newFragments = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : graph.fragments().entrySet()) {
            if (!priorIds.contains(entry.getKey())) {
                newFragments.put(entry.getKey(), entry.getValue());
            }
        }
        Map<String, Node> processingViews =
                new LinkedHashMap<String, Node>(
                        CoordinationProcessingViews.collect(graph));
        return transition(
                prior,
                resulting,
                newFragments,
                processingViews);
    }

    private static CoordinationFragmentTransition transition(
            CoordinationFragmentInventory prior,
            CoordinationFragmentInventory resulting,
            Map<String, Node> suppliedNewFragments,
            Map<String, Node> suppliedProcessingViews) {

        Set<String> priorIds = new LinkedHashSet<String>(
                prior.fragmentBlueIds());
        Set<String> resultingIds = new LinkedHashSet<String>(
                resulting.fragmentBlueIds());
        Map<String, Node> newFragments = new LinkedHashMap<String, Node>(
                suppliedNewFragments);
        Set<String> expectedNew = new LinkedHashSet<String>(resultingIds);
        expectedNew.removeAll(priorIds);
        if (!expectedNew.equals(newFragments.keySet())) {
            throw new IllegalStateException(
                    "Transition bodies do not match new fragment identities");
        }
        Set<String> reused = new LinkedHashSet<String>();
        for (String blueId : resultingIds) {
            if (priorIds.contains(blueId)) {
                reused.add(blueId);
            }
        }
        Set<String> retiredFragmentBlueIds =
                new LinkedHashSet<String>(priorIds);
        retiredFragmentBlueIds.removeAll(resultingIds);

        Set<FragmentEdgeRecord> oldEdges = new LinkedHashSet<FragmentEdgeRecord>(
                prior.edges());
        Set<FragmentEdgeRecord> newEdges = new LinkedHashSet<FragmentEdgeRecord>(
                resulting.edges());
        List<FragmentEdgeRecord> added = new ArrayList<FragmentEdgeRecord>(
                newEdges);
        added.removeAll(oldEdges);
        List<FragmentEdgeRecord> retired = new ArrayList<FragmentEdgeRecord>(
                oldEdges);
        retired.removeAll(newEdges);

        Map<String, Node> changedProcessingViews =
                new LinkedHashMap<String, Node>(
                        suppliedProcessingViews);

        return new CoordinationFragmentTransition(
                resulting,
                newFragments,
                changedProcessingViews,
                reused,
                retiredFragmentBlueIds,
                added,
                retired,
                scopeTransitions(prior, resulting));
    }

    private static Set<String> causalScopePaths(
            CoordinationPreparedDelivery preparedDelivery,
            CoordinationSubscriptionUpdate subscriptionUpdate) {
        Set<String> result = new LinkedHashSet<String>();
        result.addAll(
                preparedDelivery
                        .selectedScopeChainIdentities()
                        .keySet());
        for (CoordinationSubscriptionOccurrence occurrence
                : subscriptionUpdate.added()) {
            result.add(occurrence.scopePath());
        }
        for (CoordinationSubscriptionOccurrence occurrence
                : subscriptionUpdate.retired()) {
            result.add(occurrence.scopePath());
        }
        return Collections.unmodifiableSet(result);
    }

    static List<CoordinationScopeTransition> scopeTransitions(
            CoordinationFragmentInventory before,
            CoordinationFragmentInventory after) {
        Map<String, FragmentEdgeRecord> oldScopes = embeddedScopes(before);
        Map<String, FragmentEdgeRecord> newScopes = embeddedScopes(after);
        Set<String> paths = new LinkedHashSet<String>(oldScopes.keySet());
        paths.addAll(newScopes.keySet());
        List<String> orderedPaths = new ArrayList<String>(paths);
        Collections.sort(orderedPaths);
        List<CoordinationScopeTransition> result =
                new ArrayList<CoordinationScopeTransition>();
        if (!before.rootBlueId().equals(after.rootBlueId())) {
            result.add(new CoordinationScopeTransition(
                    "/",
                    ChangeKind.CHANGED,
                    before.rootBlueId(),
                    after.rootBlueId(),
                    CoordinationDocumentSplitter.EmbeddedEdgeOrigin.NONE,
                    null));
        }
        for (String path : orderedPaths) {
            FragmentEdgeRecord oldEdge = oldScopes.get(path);
            FragmentEdgeRecord newEdge = newScopes.get(path);
            String oldBlueId = oldEdge == null ? null : oldEdge.childBlueId();
            String newBlueId = newEdge == null ? null : newEdge.childBlueId();
            if (Objects.equals(oldBlueId, newBlueId)) {
                continue;
            }
            ChangeKind kind = oldEdge == null
                    ? ChangeKind.ADDED
                    : newEdge == null
                    ? ChangeKind.REMOVED
                    : ChangeKind.CHANGED;
            FragmentEdgeRecord provenance = newEdge != null
                    ? newEdge
                    : oldEdge;
            result.add(new CoordinationScopeTransition(
                    path,
                    kind,
                    oldBlueId,
                    newBlueId,
                    provenance.embeddedOrigin(),
                    activationIntervalIdentity(provenance, newBlueId)));
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, FragmentEdgeRecord> embeddedScopes(
            CoordinationFragmentInventory inventory) {
        Map<String, FragmentEdgeRecord> result =
                new LinkedHashMap<String, FragmentEdgeRecord>();
        for (FragmentEdgeRecord edge : inventory.edges()) {
            if (edge.edgeKind()
                    != CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT
                    || edge.rootKind()
                    != CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT) {
                continue;
            }
            FragmentEdgeRecord previous = result.put(
                    edge.absolutePointer(), edge);
            if (previous != null
                    && !previous.childBlueId().equals(edge.childBlueId())) {
                throw new IllegalStateException(
                        "One scope path has conflicting edge identities: "
                                + edge.absolutePointer());
            }
        }
        return result;
    }

    private static String activationIntervalIdentity(
            FragmentEdgeRecord edge,
            String afterBlueId) {
        String member = edge.collectionMemberKey() == null
                ? ""
                : edge.collectionMemberKey();
        String target = afterBlueId == null ? edge.childBlueId() : afterBlueId;
        Node descriptor = new Node()
                .properties(
                        "kind",
                        new Node().value(
                                "blue.coordination/owned-occurrence-interval/1.0"))
                .properties("path", new Node().value(edge.absolutePointer()))
                .properties("member", new Node().value(member))
                .properties("initialBlueId", new Node().value(target));
        return DirectBlueIdCalculator.calculateBlueId(descriptor);
    }
}
