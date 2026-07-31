package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.JsonPointer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Narrow package bridge from Coordination's public projection façade to the
 * configured Language subscription-surface validator.
 *
 * <p>The bridge exists because the validator's semantic collaborators are
 * intentionally package-private. It exposes immutable projection evidence,
 * never those collaborators, and therefore keeps Coordination from
 * duplicating matching, inheritance, dependency, or pruning rules.</p>
 */
public final class CoordinationSubscriptionProjectionBridge {
    private final DocumentProcessor processor;

    /**
     * Binds a bridge to one live configured processor.
     *
     * @param processor configured processor
     */
    public CoordinationSubscriptionProjectionBridge(
            DocumentProcessor processor) {
        this.processor = Objects.requireNonNull(
                processor, "processor");
    }

    /**
     * Derives one complete initial surface.
     *
     * @param exactRoot exact admitted Root
     * @param rootRevision host-supplied Root revision
     * @param activationFrontier exclusive activation order frontier
     * @return immutable bridge projection
     */
    public Projection projectCurrent(
            Node exactRoot,
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        requireRevision(rootRevision);
        Objects.requireNonNull(exactRoot, "exactRoot");
        Objects.requireNonNull(
                activationFrontier, "activationFrontier");
        ProcessingSnapshotManager snapshots = snapshotManager();
        ResolvedSnapshot rootSnapshot =
                snapshots.fromDocumentTransient(exactRoot);
        Node emptyRoot = new Node();
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext.builder(
                                emptyRoot,
                                exactRoot,
                                Collections.singleton(
                                        JsonPointer.ROOT),
                                processor.gasSchedule())
                        .snapshots(
                                snapshots.fromDocumentTransient(
                                        emptyRoot),
                                rootSnapshot)
                        .activeSubscriptionIntervals(
                                Collections.<SubscriptionDelta.Entry>
                                        emptyList())
                        .committingInterval(
                                activationFrontier,
                                rootRevision)
                        .build();
        SubscriptionDelta delta =
                processor.subscriptionSurfaceValidator()
                        .validate(context);
        if (!delta.removed().isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Initial Coordination subscription projection "
                            + "unexpectedly retired occurrences");
        }
        Topology topology = fullTopology(rootSnapshot);
        return projection(
                rootSnapshot,
                delta,
                delta.added(),
                occurrenceKeys(delta.added()),
                topology);
    }

    /**
     * Derives an incremental surface transition over exact changed branches.
     *
     * @param exactNewRoot exact tentative Root
     * @param activeIntervals complete prior active interval surface
     * @param changedPaths exact changed absolute pointers
     * @param newRootRevision host-supplied resulting Root revision
     * @param transitionOrderKey exact transition order
     * @param previousProcessEmbeddedRoutes prior non-executable topology
     * @param previousPrunedScopePaths prior directly pruned scopes
     * @return immutable bridge projection
     */
    public Projection projectUpdate(
            Node exactNewRoot,
            List<SubscriptionDelta.Entry> activeIntervals,
            Set<String> changedPaths,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey,
            Map<String, List<String>>
                    previousProcessEmbeddedRoutes,
            Set<String> previousPrunedScopePaths) {
        Objects.requireNonNull(exactNewRoot, "exactNewRoot");
        Objects.requireNonNull(activeIntervals, "activeIntervals");
        Objects.requireNonNull(changedPaths, "changedPaths");
        Objects.requireNonNull(
                transitionOrderKey, "transitionOrderKey");
        Objects.requireNonNull(
                previousProcessEmbeddedRoutes,
                "previousProcessEmbeddedRoutes");
        Objects.requireNonNull(
                previousPrunedScopePaths,
                "previousPrunedScopePaths");
        requireRevision(newRootRevision);
        if (changedPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "changedPaths must not be empty");
        }

        Set<String> expandedChanges =
                expandRemovedEmbeddedBranches(
                        changedPaths,
                        previousProcessEmbeddedRoutes);
        ProcessingSnapshotManager snapshots = snapshotManager();
        ResolvedSnapshot rootSnapshot =
                snapshots.fromDocumentTransient(exactNewRoot);
        /*
         * Complete retained intervals make the old Root unnecessary for
         * ordinary header/dependency comparison. For a removed prior Process
         * Embedded declaration, expandRemovedEmbeddedBranches explicitly
         * marks its old child scopes. The exact new Root still supplies every
         * new/retyped declaration to Language's own validator.
         */
        SubscriptionSurfaceValidationContext context =
                SubscriptionSurfaceValidationContext.builder(
                                exactNewRoot,
                                exactNewRoot,
                                expandedChanges,
                                processor.gasSchedule())
                        .snapshots(rootSnapshot, rootSnapshot)
                        .activeSubscriptionIntervals(
                                activeIntervals)
                        .committingInterval(
                                transitionOrderKey,
                                newRootRevision)
                        .build();
        SubscriptionDelta delta =
                processor.subscriptionSurfaceValidator()
                        .validate(context);
        List<SubscriptionDelta.Entry> active =
                apply(activeIntervals, delta);
        Set<String> additions = occurrenceKeys(
                delta.added());
        Topology topology = updateTopology(
                rootSnapshot,
                expandedChanges,
                previousProcessEmbeddedRoutes,
                previousPrunedScopePaths,
                active);
        return projection(
                rootSnapshot,
                delta,
                active,
                additions,
                topology);
    }

    /**
     * Returns the exact Language/Contracts runtime registry identity.
     *
     * @return configured runtime registry identity
     */
    public String languageRuntimeRegistryIdentity() {
        return processor.runtimeRegistryIdentity();
    }

    private Projection projection(
            ResolvedSnapshot snapshot,
            SubscriptionDelta delta,
            List<SubscriptionDelta.Entry> active,
            Set<String> additions,
            Topology topology) {
        Map<String, HeaderProjection> headers =
                new LinkedHashMap<String, HeaderProjection>();
        Map<String, String> scopeBlueIds =
                new LinkedHashMap<String, String>();
        for (SubscriptionDelta.Entry entry : active) {
            String key = occurrenceKey(entry);
            String scopeBlueId =
                    snapshot.canonicalBlueIdAt(
                            entry.scopePath());
            if (scopeBlueId == null) {
                throw new InvalidExecutionEvidenceException(
                        "Subscription scope is absent from exact Root: "
                                + entry.scopePath());
            }
            scopeBlueIds.put(key, scopeBlueId);
            if (additions.contains(key)) {
                headers.put(
                        key,
                        headerProjection(snapshot, entry));
            }
        }
        return new Projection(
                snapshot.blueId(),
                processor.runtimeRegistryIdentity(),
                delta,
                active,
                scopeBlueIds,
                headers,
                topology.routes,
                topology.prunedScopePaths);
    }

    private HeaderProjection headerProjection(
            ResolvedSnapshot snapshot,
            SubscriptionDelta.Entry entry) {
        ContractBundle bundle =
                processor.contractLoader().load(
                        snapshot, entry.scopePath());
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        entry.channelKey());
        if (contract == null
                || !entry.effectiveTypeBlueId().equals(
                        contract.effectiveTypeBlueId())) {
            throw new InvalidExecutionEvidenceException(
                    "Projected subscription header is unavailable at "
                            + entry.scopePath() + "/"
                            + entry.channelKey());
        }
        Map<String, String> fields =
                new LinkedHashMap<String, String>();
        List<String> names =
                new ArrayList<String>(
                        contract.headerFields().keySet());
        Collections.sort(
                names,
                ExternalOrderKey::compareTextCodePoints);
        for (String name : names) {
            FrozenNode value =
                    contract.headerFields().get(name);
            fields.put(name, value.blueId());
        }
        ChannelMemberSnapshot header =
                ChannelMemberSnapshot.from(contract);
        return new HeaderProjection(
                header.headerIdentityBlueId(),
                fields);
    }

    private Topology fullTopology(
            ResolvedSnapshot snapshot) {
        Map<String, List<String>> routes =
                new LinkedHashMap<String, List<String>>();
        Set<String> pruned =
                new LinkedHashSet<String>();
        collectTopology(
                snapshot,
                JsonPointer.ROOT,
                routes,
                pruned,
                new LinkedHashSet<String>());
        return new Topology(routes, pruned);
    }

    private Topology updateTopology(
            ResolvedSnapshot snapshot,
            Set<String> changedPaths,
            Map<String, List<String>> previousRoutes,
            Set<String> previousPruned,
            List<SubscriptionDelta.Entry> active) {
        Map<String, List<String>> routes =
                copyRoutes(previousRoutes);
        Set<String> pruned =
                new LinkedHashSet<String>(
                        previousPruned);
        Set<String> knownScopes =
                knownScopes(previousRoutes, active);
        Set<String> refreshScopes =
                refreshScopes(
                        changedPaths, knownScopes);
        for (String refresh : minimalScopes(
                refreshScopes)) {
            removeBranch(routes, pruned, refresh);
            if (snapshot.canonicalAt(refresh) != null) {
                collectTopology(
                        snapshot,
                        refresh,
                        routes,
                        pruned,
                        new LinkedHashSet<String>());
            }
        }
        return new Topology(routes, pruned);
    }

    private void collectTopology(
            ResolvedSnapshot snapshot,
            String startScope,
            Map<String, List<String>> routes,
            Set<String> pruned,
            Set<String> visited) {
        Deque<String> pending =
                new ArrayDeque<String>();
        pending.add(startScope);
        while (!pending.isEmpty()) {
            String scope = pending.removeFirst();
            if (!visited.add(scope)) {
                throw new InvalidExecutionEvidenceException(
                        "Repeated Process Embedded scope "
                                + scope);
            }
            Node selected =
                    snapshot.canonicalNodeAt(scope);
            if (directTerminated(selected)) {
                pruned.add(scope);
                continue;
            }
            ContractBundle bundle =
                    processor.contractLoader().load(
                            snapshot, scope);
            EffectiveContractSnapshot embedded =
                    processEmbedded(bundle);
            if (embedded == null) {
                continue;
            }
            List<String> children =
                    new ArrayList<String>();
            for (String relative : bundle.embeddedPaths()) {
                String child =
                        PointerUtils.resolvePointer(
                                scope, relative);
                children.add(child);
                pending.addLast(child);
            }
            routes.put(
                    PointerUtils.resolvePointer(
                            scope,
                            ProcessorPointerConstants
                                    .relativeContractsEntry(
                                            embedded.key())),
                    Collections.unmodifiableList(children));
        }
    }

    private static EffectiveContractSnapshot processEmbedded(
            ContractBundle bundle) {
        EffectiveContractSnapshot result = null;
        for (EffectiveContractSnapshot candidate
                : bundle.effectiveContractSnapshots()) {
            if (!EffectiveContractSnapshotConstants.Role
                    .PROCESS_EMBEDDED.equals(
                            candidate.role())) {
                continue;
            }
            if (result != null) {
                throw new InvalidExecutionEvidenceException(
                        "Multiple effective Process Embedded "
                                + "contracts");
            }
            result = candidate;
        }
        return result;
    }

    private static boolean directTerminated(Node scope) {
        Node contracts =
                scope != null ? scope.getContracts() : null;
        Node marker =
                contracts != null
                        && contracts.getProperties() != null
                        ? contracts.getProperties().get(
                        ProcessorContractConstants.KEY_TERMINATED)
                        : null;
        return RuntimeBlueIds.PROCESSING_TERMINATED_MARKER
                .equals(recognizedType(marker));
    }

    private static String recognizedType(Node node) {
        Node type = node != null ? node.getType() : null;
        Set<Node> visited =
                Collections.newSetFromMap(
                        new java.util.IdentityHashMap<Node, Boolean>());
        while (type != null && visited.add(type)) {
            if (type.getBlueId() != null) {
                return type.getBlueId();
            }
            type = type.getType();
        }
        return null;
    }

    private static Set<String> expandRemovedEmbeddedBranches(
            Set<String> changedPaths,
            Map<String, List<String>> previousRoutes) {
        Set<String> expanded =
                new LinkedHashSet<String>();
        for (String supplied : changedPaths) {
            String changed =
                    PointerUtils.normalizePointer(supplied);
            expanded.add(changed);
            for (Map.Entry<String, List<String>> route
                    : previousRoutes.entrySet()) {
                if (!overlaps(changed, route.getKey())) {
                    continue;
                }
                expanded.addAll(route.getValue());
            }
        }
        return Collections.unmodifiableSet(expanded);
    }

    private static Set<String> refreshScopes(
            Set<String> changedPaths,
            Set<String> knownScopes) {
        Set<String> refresh =
                new LinkedHashSet<String>();
        for (String changed : changedPaths) {
            String owner = deepestScope(
                    changed, knownScopes);
            if (owner == null) {
                continue;
            }
            String contracts =
                    PointerUtils.resolvePointer(
                            owner,
                            ProcessorPointerConstants
                                    .RELATIVE_CONTRACTS);
            String type =
                    PointerUtils.resolvePointer(
                            owner,
                            ProcessorPointerConstants.RELATIVE_TYPE);
            boolean replacesKnownScope = false;
            for (String known : knownScopes) {
                if (overlaps(changed, known)) {
                    replacesKnownScope = true;
                    break;
                }
            }
            if (overlaps(changed, contracts)
                    || overlaps(changed, type)
                    || replacesKnownScope) {
                refresh.add(owner);
            }
        }
        return refresh;
    }

    private static String deepestScope(
            String path,
            Set<String> knownScopes) {
        String result = null;
        int depth = -1;
        for (String scope : knownScopes) {
            if (!PointerUtils.descendantOrEqual(
                    path, scope)) {
                continue;
            }
            int candidateDepth =
                    JsonPointer.split(scope).size();
            if (candidateDepth > depth) {
                result = scope;
                depth = candidateDepth;
            }
        }
        return result;
    }

    private static Set<String> minimalScopes(
            Set<String> scopes) {
        Set<String> result =
                new LinkedHashSet<String>();
        for (String candidate : scopes) {
            boolean belowAnother = false;
            for (String other : scopes) {
                if (!candidate.equals(other)
                        && PointerUtils.descendantOrEqual(
                                candidate, other)) {
                    belowAnother = true;
                    break;
                }
            }
            if (!belowAnother) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static Set<String> knownScopes(
            Map<String, List<String>> routes,
            List<SubscriptionDelta.Entry> active) {
        Set<String> scopes =
                new LinkedHashSet<String>();
        scopes.add(JsonPointer.ROOT);
        for (Map.Entry<String, List<String>> route
                : routes.entrySet()) {
            scopes.add(ownerScope(route.getKey()));
            scopes.addAll(route.getValue());
        }
        for (SubscriptionDelta.Entry entry : active) {
            scopes.add(entry.scopePath());
        }
        return scopes;
    }

    private static String ownerScope(String contractPath) {
        List<String> segments =
                JsonPointer.split(contractPath);
        if (segments.size() < 2
                || !ProcessorContractConstants.KEY_CONTRACTS
                .equals(segments.get(
                        segments.size() - 2))) {
            throw new IllegalArgumentException(
                    "Invalid Process Embedded contract path: "
                            + contractPath);
        }
        return JsonPointer.toPointer(
                segments.subList(
                        0, segments.size() - 2));
    }

    private static void removeBranch(
            Map<String, List<String>> routes,
            Set<String> pruned,
            String scope) {
        List<String> remove =
                new ArrayList<String>();
        for (String contractPath : routes.keySet()) {
            if (PointerUtils.descendantOrEqual(
                    ownerScope(contractPath), scope)) {
                remove.add(contractPath);
            }
        }
        for (String key : remove) {
            routes.remove(key);
        }
        List<String> removePruned =
                new ArrayList<String>();
        for (String path : pruned) {
            if (PointerUtils.descendantOrEqual(
                    path, scope)) {
                removePruned.add(path);
            }
        }
        pruned.removeAll(removePruned);
    }

    private static List<SubscriptionDelta.Entry> apply(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta) {
        Map<String, SubscriptionDelta.Entry> active =
                new LinkedHashMap<String, SubscriptionDelta.Entry>();
        for (SubscriptionDelta.Entry entry : previous) {
            active.put(occurrenceKey(entry), entry);
        }
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            active.remove(occurrenceKey(entry));
        }
        for (SubscriptionDelta.Entry entry : delta.added()) {
            active.put(occurrenceKey(entry), entry);
        }
        return Collections.unmodifiableList(
                new ArrayList<SubscriptionDelta.Entry>(
                        active.values()));
    }

    private static Set<String> occurrenceKeys(
            List<SubscriptionDelta.Entry> entries) {
        Set<String> keys =
                new LinkedHashSet<String>();
        for (SubscriptionDelta.Entry entry : entries) {
            keys.add(occurrenceKey(entry));
        }
        return keys;
    }

    private static String occurrenceKey(
            SubscriptionDelta.Entry entry) {
        return entry.scopePath()
                + "\u001f" + entry.channelKey();
    }

    private static boolean overlaps(
            String left,
            String right) {
        return PointerUtils.descendantOrEqual(left, right)
                || PointerUtils.descendantOrEqual(right, left);
    }

    private static Map<String, List<String>> copyRoutes(
            Map<String, List<String>> source) {
        Map<String, List<String>> copy =
                new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry
                : source.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<String>(
                                    entry.getValue())));
        }
        return copy;
    }

    private ProcessingSnapshotManager snapshotManager() {
        return Objects.requireNonNull(
                processor.snapshotManager(),
                "processor snapshotManager");
    }

    private static void requireRevision(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
    }

    /**
     * Immutable bridge result for one initial or incremental projection.
     */
    public static final class Projection {
        private final String rootBlueId;
        private final String languageRuntimeRegistryIdentity;
        private final SubscriptionDelta delta;
        private final List<SubscriptionDelta.Entry> activeEntries;
        private final Map<String, String> scopeBlueIds;
        private final Map<String, HeaderProjection> headers;
        private final Map<String, List<String>>
                processEmbeddedRoutes;
        private final Set<String> prunedScopePaths;

        private Projection(
                String rootBlueId,
                String languageRuntimeRegistryIdentity,
                SubscriptionDelta delta,
                List<SubscriptionDelta.Entry> activeEntries,
                Map<String, String> scopeBlueIds,
                Map<String, HeaderProjection> headers,
                Map<String, List<String>> processEmbeddedRoutes,
                Set<String> prunedScopePaths) {
            this.rootBlueId = rootBlueId;
            this.languageRuntimeRegistryIdentity =
                    languageRuntimeRegistryIdentity;
            this.delta = delta;
            this.activeEntries =
                    Collections.unmodifiableList(
                            new ArrayList<SubscriptionDelta.Entry>(
                                    activeEntries));
            this.scopeBlueIds =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, String>(
                                    scopeBlueIds));
            this.headers =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, HeaderProjection>(
                                    headers));
            this.processEmbeddedRoutes =
                    Collections.unmodifiableMap(
                            copyRoutes(processEmbeddedRoutes));
            this.prunedScopePaths =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<String>(
                                    prunedScopePaths));
        }

        /** @return exact canonical Root BlueId */
        public String rootBlueId() {
            return rootBlueId;
        }

        /** @return exact configured Language runtime identity */
        public String languageRuntimeRegistryIdentity() {
            return languageRuntimeRegistryIdentity;
        }

        /** @return exact Language-validated transition delta */
        public SubscriptionDelta delta() {
            return delta;
        }

        /** @return complete resulting active interval surface */
        public List<SubscriptionDelta.Entry> activeEntries() {
            return activeEntries;
        }

        /**
         * Returns the exact selected scope identity for an internal
         * {@code scope-path + unit-separator + raw-key} occurrence key.
         *
         * @return immutable internal occurrence-to-scope map
         */
        public Map<String, String> scopeBlueIds() {
            return scopeBlueIds;
        }

        /**
         * Returns refreshed non-executable headers for newly added
         * occurrences.
         *
         * @return immutable internal occurrence-to-header map
         */
        public Map<String, HeaderProjection> headers() {
            return headers;
        }

        /** @return immutable non-executable Process Embedded topology */
        public Map<String, List<String>> processEmbeddedRoutes() {
            return processEmbeddedRoutes;
        }

        /** @return immutable directly pruned scope paths */
        public Set<String> prunedScopePaths() {
            return prunedScopePaths;
        }
    }

    /**
     * Immutable exact, non-executable effective Channel-header projection.
     */
    public static final class HeaderProjection {
        private final String identityBlueId;
        private final Map<String, String> fieldBlueIds;

        private HeaderProjection(
                String identityBlueId,
                Map<String, String> fieldBlueIds) {
            this.identityBlueId = identityBlueId;
            this.fieldBlueIds =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<String, String>(
                                    fieldBlueIds));
        }

        /** @return exact sanitized effective-header identity */
        public String identityBlueId() {
            return identityBlueId;
        }

        /** @return immutable exact header-field identities */
        public Map<String, String> fieldBlueIds() {
            return fieldBlueIds;
        }
    }

    private static final class Topology {
        private final Map<String, List<String>> routes;
        private final Set<String> prunedScopePaths;

        private Topology(
                Map<String, List<String>> routes,
                Set<String> prunedScopePaths) {
            this.routes = routes;
            this.prunedScopePaths = prunedScopePaths;
        }
    }
}
