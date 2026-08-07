package blue.coordination.processor.subscription;

import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.api.BlueOperationResult;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePath;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.BlueContracts;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessorRuntimeAccess;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.SubscriptionSurfaceProjection;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Public-only boundary for authoritative subscription projection.
 *
 * <p>The bridge delegates subscription semantics to Contracts' public
 * {@link SubscriptionSurfaceProjection}. Coordination adds only immutable
 * persistence metadata derived from the public runtime snapshot and the
 * public {@link EffectiveFragmentationCatalog}; it never recreates matching,
 * inheritance, activation, or collection-expansion rules.</p>
 */
public final class CoordinationSubscriptionProjectionBridge {
    private final ProcessorRuntimeAccess runtimeAccess;
    private final SubscriptionSurfaceProjection surfaceProjection;
    private final Function<Node, EffectiveFragmentationCatalog>
            fragmentationCatalog;

    /**
     * Binds directly to the focused Contracts facade.
     *
     * @param contracts live Contracts generation
     */
    public CoordinationSubscriptionProjectionBridge(
            BlueContracts contracts) {
        BlueContracts checked = Objects.requireNonNull(
                contracts, "contracts");
        this.runtimeAccess = checked.runtimeAccess();
        this.surfaceProjection =
                checked.subscriptionSurfaceProjection();
        this.fragmentationCatalog =
                checked::effectiveFragmentationCatalog;
    }

    /** Returns one exact owned Root, materializing only a pure top-level reference. */
    public Node materializeExactRoot(Node suppliedRoot) {
        Node root = Objects.requireNonNull(
                suppliedRoot, "suppliedRoot");
        if (!root.isReferenceOnly()) {
            return root.clone();
        }
        BlueOperationResult<FrozenNode> materialized = runtimeAccess
                .materializeVerifiedExactReference(
                        FrozenNode.fromNode(root.clone()));
        if (materialized.isEstablished()) {
            return CoordinationProcessHeaderSupport.canonicalExactCopy(
                    materialized.requireEstablished().toNode());
        }
        String detail = materialized.reason().orElse(
                "Exact Root reference could not be materialized");
        if (!materialized.outstandingBlueIds().isEmpty()) {
            throw new ExecutionEvidenceUnavailableException(
                    detail,
                    materialized.outstandingBlueIds());
        }
        throw new InvalidExecutionEvidenceException(detail);
    }

    /** Inspects the public structured scope catalog through the bound owner. */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node exactRoot) {
        return fragmentationCatalog.apply(
                Objects.requireNonNull(exactRoot, "exactRoot"));
    }

    public Projection projectCurrent(
            Node exactRoot,
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        Node root = Objects.requireNonNull(exactRoot, "exactRoot");
        ExternalOrderKey frontier = Objects.requireNonNull(
                activationFrontier, "activationFrontier");
        requireRevision(rootRevision);

        SubscriptionDelta delta = surfaceProjection.projectInitial(
                root,
                rootRevision,
                frontier);
        if (!delta.removed().isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Initial Coordination subscription projection "
                            + "unexpectedly retired occurrences");
        }
        EffectiveFragmentationCatalog catalog =
                effectiveFragmentationCatalog(root);
        return projection(
                root,
                catalog,
                delta,
                delta.added(),
                occurrenceKeys(delta.added()));
    }

    public Projection projectUpdate(
            Node exactNewRoot,
            List<SubscriptionDelta.Entry> activeIntervals,
            Set<String> changedPaths,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey,
            Map<String, List<String>> previousProcessEmbeddedRoutes,
            Set<String> previousPrunedScopePaths) {
        Node root = Objects.requireNonNull(
                exactNewRoot, "exactNewRoot");
        List<SubscriptionDelta.Entry> previous = Objects.requireNonNull(
                activeIntervals, "activeIntervals");
        Set<String> changes = Objects.requireNonNull(
                changedPaths, "changedPaths");
        ExternalOrderKey order = Objects.requireNonNull(
                transitionOrderKey, "transitionOrderKey");
        /*
         * Retained topology remains a persisted Coordination concern. The
         * public Contracts update operation now performs conservative route
         * invalidation from the complete retained interval surface, so those
         * historical maps are validated but never fed into semantic logic.
         */
        Objects.requireNonNull(
                previousProcessEmbeddedRoutes,
                "previousProcessEmbeddedRoutes");
        Objects.requireNonNull(
                previousPrunedScopePaths,
                "previousPrunedScopePaths");
        requireRevision(newRootRevision);

        SubscriptionDelta delta = surfaceProjection.projectUpdate(
                root,
                previous,
                changes,
                newRootRevision,
                order);
        List<SubscriptionDelta.Entry> active = refreshActiveEvidence(
                root,
                apply(
                        previous,
                        delta,
                        newRootRevision,
                        order),
                newRootRevision,
                order);
        EffectiveFragmentationCatalog catalog =
                effectiveFragmentationCatalog(root);
        return projection(
                root,
                catalog,
                delta,
                active,
                occurrenceKeys(delta.added()));
    }

    /**
     * Applies the exact subscription transition returned by the bound
     * Contracts platform-commit operation.
     *
     * <p>This overload deliberately does not re-run subscription semantics.
     * The non-publicly-constructible result keeps the semantic output and its
     * immutable, validated commit companion paired. Coordination verifies the
     * companion's interval transition against the persisted active surface,
     * then materializes only persistence metadata for newly active
     * occurrences from that same result's exact Root.</p>
     */
    public Projection projectUpdate(
            List<SubscriptionDelta.Entry> activeIntervals,
            PlatformProcessingResult platformResult,
            Node exactResultingRoot,
            EffectiveFragmentationCatalog resultingCatalog) {
        List<SubscriptionDelta.Entry> previous = Objects.requireNonNull(
                activeIntervals, "activeIntervals");
        PlatformProcessingResult platform = Objects.requireNonNull(
                platformResult, "platformResult");
        EffectiveFragmentationCatalog catalog = Objects.requireNonNull(
                resultingCatalog, "resultingCatalog");
        PlatformCommitCompanion companion = platform.commitCompanion();
        if (!platform.processResult().commits()
                || !companion.commitsRootAndOutbox()) {
            throw new InvalidExecutionEvidenceException(
                    "Committed subscription projection requires one "
                            + "Root-and-outbox platform result");
        }
        Node processRoot = Objects.requireNonNull(
                platform.processResult().document(),
                "platformResult.processResult.document");
        Node root = Objects.requireNonNull(
                exactResultingRoot, "exactResultingRoot");
        if (!DirectBlueIdCalculator.calculateBlueId(processRoot).equals(
                DirectBlueIdCalculator.calculateBlueId(root))) {
            throw new InvalidExecutionEvidenceException(
                    "Exact resulting Root disagrees with the platform "
                            + "PROCESS result identity");
        }
        SubscriptionDelta delta = companion.subscriptionDelta();
        ExternalOrderKey order = companion.eventOrderKey();
        long newRootRevision = companion.resultingRootRevision();
        requireRevision(newRootRevision);

        /* The companion is authoritative for interval membership. Retained
         * entries still need evidence from the exact resulting Root: a
         * PROCESS transition can change a Channel header dependency without
         * changing that Channel's occurrence identity. Preserve the original
         * interval bounds while refreshing only its semantic evidence. */
        List<SubscriptionDelta.Entry> active = refreshActiveEvidence(
                root,
                apply(
                        previous,
                        delta,
                        newRootRevision,
                        order),
                newRootRevision,
                order);
        return projection(
                root,
                catalog,
                delta,
                active,
                occurrenceKeys(delta.added()));
    }

    public String languageRuntimeRegistryIdentity() {
        return RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
    }

    private Projection projection(
            Node exactRoot,
            EffectiveFragmentationCatalog catalog,
            SubscriptionDelta delta,
            List<SubscriptionDelta.Entry> active,
            Set<String> additions) {
        /* Runtime snapshot access takes a detached copy and the remaining
         * consumers only traverse the Root. Preserve public defensive-copy
         * semantics in materializeExactRoot, but do not clone an already
         * inline engine-owned Root once more on this internal path. */
        Node materializedRoot = exactRoot.isReferenceOnly()
                ? materializeExactRoot(exactRoot)
                : exactRoot;
        blue.language.merge.ResolvedSnapshot snapshot =
                runtimeAccess.resolveTransientPreservingPaths(
                        materializedRoot,
                        executableBodyPaths(catalog));
        Map<String, HeaderProjection> headers = new LinkedHashMap<>();
        Map<String, String> scopeBlueIds = new LinkedHashMap<>();
        Map<String, String> scopeBlueIdsByPath = new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : active) {
            String key = occurrenceKey(entry);
            EffectiveContractSnapshot contract =
                    requireExternalContract(catalog, entry);
            String scopeBlueId = scopeBlueIdsByPath.get(
                    entry.scopePath());
            if (scopeBlueId == null) {
                scopeBlueId = exactScopeBlueId(
                        materializedRoot, entry.scopePath());
                scopeBlueIdsByPath.put(
                        entry.scopePath(), scopeBlueId);
            }
            scopeBlueIds.put(key, scopeBlueId);
            headers.put(
                    key,
                    headerProjection(contract, entry));
        }

        Topology topology = topology(snapshot, catalog);
        return new Projection(
                catalog.rootBlueId(),
                languageRuntimeRegistryIdentity(),
                delta,
                active,
                scopeBlueIds,
                headers,
                topology.routes,
                topology.prunedScopePaths);
    }

    private static EffectiveContractSnapshot requireExternalContract(
            EffectiveFragmentationCatalog catalog,
            SubscriptionDelta.Entry entry) {
        EffectiveContractSnapshot contract = null;
        List<EffectiveContractSnapshot> contracts = catalog
                .effectiveContractsByScope()
                .get(entry.scopePath());
        if (contracts != null) {
            for (EffectiveContractSnapshot candidate : contracts) {
                if (entry.channelKey().equals(candidate.key())) {
                    contract = candidate;
                    break;
                }
            }
        }
        if (contract == null
                || !EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL
                .equals(contract.role())
                || !entry.effectiveTypeBlueId().equals(
                contract.effectiveTypeBlueId())
                || entry.order() != contract.order()) {
            throw new InvalidExecutionEvidenceException(
                    "Projected subscription occurrence is absent from the "
                            + "exact effective catalog at "
                            + entry.scopePath() + "/" + entry.channelKey()
                            + "; available scopes="
                            + catalog.effectiveContractsByScope().keySet()
                            + "; available contracts="
                            + (contracts == null
                            ? Collections.emptyList()
                            : contracts.stream().map(candidate ->
                            candidate.key() + ":" + candidate.role() + ":"
                                    + candidate.effectiveTypeBlueId() + ":"
                                    + candidate.order()).collect(
                            java.util.stream.Collectors.toList()))
                            + "; projected type="
                            + entry.effectiveTypeBlueId()
                            + ", order=" + entry.order()
                            + ", sources="
                            + entry.sourceContributionNodeBlueIds()
                            + "; catalog sources="
                            + (contract == null
                            ? Collections.emptyList()
                            : contract.sourceContributionNodeBlueIds()));
        }
        return contract;
    }

    private String exactScopeBlueId(Node exactRoot, String scopePath) {
        Object selected;
        try {
            selected = NodePath.get(
                    exactRoot,
                    scopePath,
                    this::materializeExactReference);
        } catch (RuntimeException failure) {
            if (failure instanceof ExecutionEvidenceUnavailableException
                    || failure instanceof InvalidExecutionEvidenceException) {
                throw failure;
            }
            throw new InvalidExecutionEvidenceException(
                    "Subscription scope is absent from exact Root: "
                            + scopePath);
        }
        if (!(selected instanceof Node)) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription scope is not an exact object: "
                            + scopePath);
        }
        Node scope = (Node) selected;
        return scope.isReferenceOnly()
                ? scope.getBlueId()
                : DirectBlueIdCalculator.calculateBlueId(scope);
    }

    private Node materializeExactReference(Node reference) {
        if (reference == null || !reference.isReferenceOnly()) {
            return reference;
        }
        BlueOperationResult<FrozenNode> result = runtimeAccess
                .materializeVerifiedExactReference(
                        FrozenNode.fromNode(reference.clone()));
        if (result.isEstablished()) {
            return CoordinationProcessHeaderSupport.canonicalExactCopy(
                    result.requireEstablished().toNode());
        }
        String reason = result.reason().orElse(
                "Exact scope reference could not be materialized");
        if (!result.outstandingBlueIds().isEmpty()) {
            throw new ExecutionEvidenceUnavailableException(
                    reason, result.outstandingBlueIds());
        }
        throw new InvalidExecutionEvidenceException(reason);
    }

    private static HeaderProjection headerProjection(
            EffectiveContractSnapshot contract,
            SubscriptionDelta.Entry entry) {
        Map<String, String> fieldBlueIds = new LinkedHashMap<>();
        List<String> fieldNames = new ArrayList<>(
                contract.headerFields().keySet());
        fieldNames.sort(ExternalOrderKey::compareTextCodePoints);
        for (String fieldName : fieldNames) {
            FrozenNode value = contract.headerFields().get(fieldName);
            fieldBlueIds.put(fieldName, value.blueId());
        }
        return new HeaderProjection(
                dependencyHeaderIdentity(entry),
                fieldBlueIds);
    }

    private static String dependencyHeaderIdentity(
            SubscriptionDelta.Entry entry) {
        for (ExternalChannelDependencySnapshot.ChannelEntry channel
                : entry.dependencies().channelEntries()) {
            if (!entry.channelKey().equals(channel.channelKey())) {
                continue;
            }
            return channel.headerIdentityBlueId();
        }
        throw new InvalidExecutionEvidenceException(
                "Subscription dependency evidence omits Channel "
                        + entry.scopePath() + "/" + entry.channelKey());
    }

    private static List<String> executableBodyPaths(
            EffectiveFragmentationCatalog catalog) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<EffectiveContractSnapshot>> scope
                : catalog.effectiveContractsByScope().entrySet()) {
            for (EffectiveContractSnapshot contract : scope.getValue()) {
                for (String bodyField : contract.executableBodyFields()) {
                    result.add(PointerUtils.resolvePointer(
                            scope.getKey(),
                            ProcessorPointerConstants.relativeContractsEntry(
                                    contract.key()) + "/"
                                    + JsonPointer.escape(bodyField)));
                }
            }
        }
        result.sort(ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableList(result);
    }

    private static Topology topology(
            blue.language.merge.ResolvedSnapshot snapshot,
            EffectiveFragmentationCatalog catalog) {
        Set<String> pruned = new LinkedHashSet<>();
        List<String> scopePaths = new ArrayList<>(
                catalog.scopePlansByScope().keySet());
        scopePaths.sort(
                Comparator.comparingInt(
                                (String path) ->
                                        JsonPointer.split(path).size())
                        .thenComparing(
                                ExternalOrderKey::compareTextCodePoints));
        for (String scopePath : scopePaths) {
            if (belowAny(scopePath, pruned)) {
                continue;
            }
            Node selected = snapshot.canonicalNodeAt(scopePath);
            if (selected == null || selected.isReferenceOnly()) {
                selected = snapshot.resolvedNodeAt(scopePath);
            }
            if (directTerminated(selected)) {
                pruned.add(scopePath);
            }
        }

        Map<String, List<String>> routes = new LinkedHashMap<>();
        for (String scopePath : scopePaths) {
            if (belowAny(scopePath, pruned)) {
                continue;
            }
            EmbeddedScopePlanView plan = catalog
                    .scopePlansByScope().get(scopePath);
            EffectiveContractSnapshot embedded = null;
            for (EffectiveContractSnapshot candidate : catalog
                    .effectiveContractsByScope().get(scopePath)) {
                if (!EffectiveContractSnapshotConstants.Role
                        .PROCESS_EMBEDDED.equals(candidate.role())) {
                    continue;
                }
                if (embedded != null) {
                    throw new InvalidExecutionEvidenceException(
                            "Multiple effective Process Embedded contracts "
                                    + "at " + scopePath);
                }
                embedded = candidate;
            }
            if (embedded == null) {
                continue;
            }
            String contractPath = PointerUtils.resolvePointer(
                    scopePath,
                    ProcessorPointerConstants.relativeContractsEntry(
                            embedded.key()));
            routes.put(
                    contractPath,
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    plan.concreteChildPaths())));
        }
        return new Topology(routes, pruned);
    }

    private static boolean directTerminated(Node scope) {
        Node contracts = scope != null ? scope.getContracts() : null;
        Node marker = contracts != null
                && contracts.getProperties() != null
                ? contracts.getProperties().get(
                ProcessorContractConstants.KEY_TERMINATED)
                : null;
        return RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                recognizedType(marker));
    }

    private static String recognizedType(Node node) {
        Node type = node != null ? node.getType() : null;
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        while (type != null && visited.add(type)) {
            if (type.getBlueId() != null) {
                return type.getBlueId();
            }
            type = type.getType();
        }
        return null;
    }

    private static boolean belowAny(
            String scopePath,
            Set<String> ancestors) {
        for (String ancestor : ancestors) {
            if (PointerUtils.descendantOrEqual(
                    scopePath, ancestor)) {
                return true;
            }
        }
        return false;
    }

    private static List<SubscriptionDelta.Entry> apply(
            List<SubscriptionDelta.Entry> previous,
            SubscriptionDelta delta,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey) {
        Map<String, SubscriptionDelta.Entry> active =
                new LinkedHashMap<>();
        for (SubscriptionDelta.Entry entry : previous) {
            if (!entry.isActiveInterval()) {
                throw new InvalidExecutionEvidenceException(
                        "Persisted subscription surface contains a retired "
                                + "occurrence at " + entry.scopePath() + "/"
                                + entry.channelKey());
            }
            if (active.put(occurrenceKey(entry), entry) != null) {
                throw new InvalidExecutionEvidenceException(
                        "Persisted subscription surface contains duplicate "
                                + "occurrence at " + entry.scopePath() + "/"
                                + entry.channelKey());
            }
        }
        for (SubscriptionDelta.Entry entry : delta.removed()) {
            SubscriptionDelta.Entry retained = active.remove(
                    occurrenceKey(entry));
            if (retained == null) {
                throw new InvalidExecutionEvidenceException(
                        "Subscription delta retires an inactive occurrence "
                                + "at " + entry.scopePath() + "/"
                                + entry.channelKey());
            }
            requireRetirement(
                    retained,
                    entry,
                    newRootRevision);
        }
        for (SubscriptionDelta.Entry entry : delta.added()) {
            requireActivation(
                    entry,
                    newRootRevision,
                    transitionOrderKey);
            if (active.put(occurrenceKey(entry), entry) != null) {
                throw new InvalidExecutionEvidenceException(
                        "Subscription delta activates an already active "
                                + "occurrence at " + entry.scopePath() + "/"
                                + entry.channelKey());
            }
        }
        List<SubscriptionDelta.Entry> result =
                new ArrayList<>(active.values());
        result.sort((left, right) -> {
            int compared = ExternalOrderKey.compareTextCodePoints(
                    left.scopePath(), right.scopePath());
            if (compared != 0) {
                return compared;
            }
            compared = Integer.compare(left.order(), right.order());
            if (compared != 0) {
                return compared;
            }
            compared = ExternalOrderKey.compareTextCodePoints(
                    left.channelKey(), right.channelKey());
            return compared != 0
                    ? compared
                    : ExternalOrderKey.compareTextCodePoints(
                    left.effectiveTypeBlueId(),
                    right.effectiveTypeBlueId());
        });
        return Collections.unmodifiableList(result);
    }

    /**
     * Rebinds active interval evidence to the exact resulting Root while
     * retaining each interval's original activation boundary.
     *
     * <p>The platform delta controls membership, but a retained Channel can
     * acquire different header/dependency evidence as another contract in
     * the same scope changes. The public initial projection is the semantic
     * authority for that evidence. Matching by occurrence also turns any
     * disagreement between the committed delta and resulting surface into a
     * fail-closed error.</p>
     */
    private List<SubscriptionDelta.Entry> refreshActiveEvidence(
            Node resultingRoot,
            List<SubscriptionDelta.Entry> activeIntervals,
            long rootRevision,
            ExternalOrderKey frontier) {
        SubscriptionDelta current = surfaceProjection.projectInitial(
                resultingRoot,
                rootRevision,
                frontier);
        if (!current.removed().isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Current subscription projection unexpectedly retired "
                            + "occurrences");
        }
        Map<String, SubscriptionDelta.Entry> currentByKey =
                new LinkedHashMap<String, SubscriptionDelta.Entry>();
        for (SubscriptionDelta.Entry entry : current.added()) {
            String key = occurrenceKey(entry);
            if (currentByKey.put(key, entry) != null) {
                throw new InvalidExecutionEvidenceException(
                        "Current subscription surface contains duplicate "
                                + "occurrence at " + entry.scopePath() + "/"
                                + entry.channelKey());
            }
        }
        List<SubscriptionDelta.Entry> refreshed = new ArrayList<>();
        for (SubscriptionDelta.Entry interval : activeIntervals) {
            SubscriptionDelta.Entry evidence = currentByKey.remove(
                    occurrenceKey(interval));
            if (evidence == null) {
                throw new InvalidExecutionEvidenceException(
                        "Committed active interval is absent from the "
                                + "resulting subscription surface at "
                                + interval.scopePath() + "/"
                                + interval.channelKey());
            }
            requireCompatibleEvidenceRefresh(interval, evidence);
            boolean dependenciesChanged = !interval.dependencies().equals(
                    evidence.dependencies());
            if (!dependenciesChanged) {
                if (!interval.checkpointDomainBlueId().equals(
                        evidence.checkpointDomainBlueId())) {
                    throw new InvalidExecutionEvidenceException(
                            "Resulting subscription checkpoint domain "
                                    + "changed without its dependency "
                                    + "snapshot at "
                                    + interval.scopePath() + "/"
                                    + interval.channelKey());
                }
                refreshed.add(interval);
                continue;
            }
            refreshed.add(new SubscriptionDelta.Entry(
                    interval.scopePath(),
                    interval.channelKey(),
                    interval.effectiveTypeBlueId(),
                    interval.sourceContributionNodeBlueIds(),
                    interval.order(),
                    interval.subscriptionKeys(),
                    evidence.checkpointDomainBlueId(),
                    evidence.dependencies(),
                    interval.activationRootRevision(),
                    interval.startAfterExternalOrderKey(),
                    interval.endAtRootRevision()));
        }
        if (!currentByKey.isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Resulting subscription surface contains uncommitted "
                            + "active occurrences: " + currentByKey.keySet());
        }
        return Collections.unmodifiableList(refreshed);
    }

    /**
     * Limits compatibility re-evidencing to dependency-derived fields that
     * the frozen platform companion does not refresh for retained
     * occurrences.
     * Any other semantic change belongs in the authoritative remove/add
     * delta and therefore fails closed here.
     */
    private static void requireCompatibleEvidenceRefresh(
            SubscriptionDelta.Entry interval,
            SubscriptionDelta.Entry evidence) {
        List<String> changed = new ArrayList<>();
        addChanged(changed, "scopePath",
                interval.scopePath(), evidence.scopePath());
        addChanged(changed, "channelKey",
                interval.channelKey(), evidence.channelKey());
        addChanged(changed, "effectiveTypeBlueId",
                interval.effectiveTypeBlueId(),
                evidence.effectiveTypeBlueId());
        addChanged(changed, "sourceContributionNodeBlueIds",
                interval.sourceContributionNodeBlueIds(),
                evidence.sourceContributionNodeBlueIds());
        addChanged(changed, "order",
                interval.order(), evidence.order());
        addChanged(changed, "subscriptionKeys",
                interval.subscriptionKeys(), evidence.subscriptionKeys());
        if (!changed.isEmpty()) {
            throw new InvalidExecutionEvidenceException(
                    "Resulting subscription evidence changed outside the "
                            + "retained dependency snapshot at "
                            + interval.scopePath() + "/"
                            + interval.channelKey()
                            + "; changed=" + changed);
        }
    }

    private static void addChanged(
            List<String> changed,
            String field,
            Object retained,
            Object refreshed) {
        if (!Objects.equals(retained, refreshed)) {
            changed.add(field);
        }
    }

    private static void requireRetirement(
            SubscriptionDelta.Entry retained,
            SubscriptionDelta.Entry retired,
            long newRootRevision) {
        if (!sameSubscriptionSnapshot(retained, retired)
                || !Objects.equals(
                        retained.activationRootRevision(),
                        retired.activationRootRevision())
                || !Objects.equals(
                        retained.startAfterExternalOrderKey(),
                        retired.startAfterExternalOrderKey())
                || !Long.valueOf(newRootRevision).equals(
                        retired.endAtRootRevision())) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription retirement does not close the retained "
                            + "interval exactly at " + retired.scopePath()
                            + "/" + retired.channelKey());
        }
    }

    private static void requireActivation(
            SubscriptionDelta.Entry activated,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey) {
        if (!Long.valueOf(newRootRevision).equals(
                        activated.activationRootRevision())
                || !transitionOrderKey.equals(
                        activated.startAfterExternalOrderKey())
                || activated.endAtRootRevision() != null) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription activation does not start at the exact "
                            + "commit boundary at " + activated.scopePath()
                            + "/" + activated.channelKey());
        }
    }

    private static boolean sameSubscriptionSnapshot(
            SubscriptionDelta.Entry left,
            SubscriptionDelta.Entry right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.effectiveTypeBlueId().equals(
                        right.effectiveTypeBlueId())
                && left.sourceContributionNodeBlueIds().equals(
                        right.sourceContributionNodeBlueIds())
                && left.order() == right.order()
                && left.subscriptionKeys().equals(
                        right.subscriptionKeys())
                && left.checkpointDomainBlueId().equals(
                        right.checkpointDomainBlueId())
                && left.dependencies().equals(right.dependencies());
    }

    private static Set<String> occurrenceKeys(
            List<SubscriptionDelta.Entry> entries) {
        Set<String> keys = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry entry : entries) {
            keys.add(occurrenceKey(entry));
        }
        return keys;
    }

    private static String occurrenceKey(
            SubscriptionDelta.Entry entry) {
        return entry.scopePath() + "\u001f" + entry.channelKey();
    }

    private static void requireRevision(long revision) {
        if (revision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
    }

    /** Immutable result shape retained by Coordination's public facade. */
    public static final class Projection {
        private final String rootBlueId;
        private final String languageRuntimeRegistryIdentity;
        private final SubscriptionDelta delta;
        private final List<SubscriptionDelta.Entry> activeEntries;
        private final Map<String, String> scopeBlueIds;
        private final Map<String, HeaderProjection> headers;
        private final Map<String, List<String>> processEmbeddedRoutes;
        private final Set<String> prunedScopePaths;

        public Projection(
                String rootBlueId,
                String languageRuntimeRegistryIdentity,
                SubscriptionDelta delta,
                List<SubscriptionDelta.Entry> activeEntries,
                Map<String, String> scopeBlueIds,
                Map<String, HeaderProjection> headers,
                Map<String, List<String>> processEmbeddedRoutes,
                Set<String> prunedScopePaths) {
            this.rootBlueId = Objects.requireNonNull(rootBlueId, "rootBlueId");
            this.languageRuntimeRegistryIdentity = Objects.requireNonNull(
                    languageRuntimeRegistryIdentity,
                    "languageRuntimeRegistryIdentity");
            this.delta = Objects.requireNonNull(delta, "delta");
            this.activeEntries = Collections.unmodifiableList(
                    new ArrayList<SubscriptionDelta.Entry>(activeEntries));
            this.scopeBlueIds = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(scopeBlueIds));
            this.headers = Collections.unmodifiableMap(
                    new LinkedHashMap<String, HeaderProjection>(headers));
            Map<String, List<String>> routes = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry
                    : processEmbeddedRoutes.entrySet()) {
                routes.put(entry.getKey(), Collections.unmodifiableList(
                        new ArrayList<String>(entry.getValue())));
            }
            this.processEmbeddedRoutes = Collections.unmodifiableMap(routes);
            this.prunedScopePaths = Collections.unmodifiableSet(
                    new LinkedHashSet<String>(prunedScopePaths));
        }

        public String rootBlueId() { return rootBlueId; }
        public String languageRuntimeRegistryIdentity() {
            return languageRuntimeRegistryIdentity;
        }
        public SubscriptionDelta delta() { return delta; }
        public List<SubscriptionDelta.Entry> activeEntries() {
            return activeEntries;
        }
        public Map<String, String> scopeBlueIds() { return scopeBlueIds; }
        public Map<String, HeaderProjection> headers() { return headers; }
        public Map<String, List<String>> processEmbeddedRoutes() {
            return processEmbeddedRoutes;
        }
        public Set<String> prunedScopePaths() { return prunedScopePaths; }
    }

    /** Immutable non-executable effective Channel-header projection. */
    public static final class HeaderProjection {
        private final String identityBlueId;
        private final Map<String, String> fieldBlueIds;

        public HeaderProjection(
                String identityBlueId,
                Map<String, String> fieldBlueIds) {
            this.identityBlueId = Objects.requireNonNull(
                    identityBlueId, "identityBlueId");
            this.fieldBlueIds = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(fieldBlueIds));
        }

        public String identityBlueId() { return identityBlueId; }
        public Map<String, String> fieldBlueIds() { return fieldBlueIds; }
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
