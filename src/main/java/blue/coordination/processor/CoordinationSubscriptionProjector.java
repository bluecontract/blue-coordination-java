package blue.coordination.processor;

import blue.language.model.Node;
import blue.coordination.processor.subscription.CoordinationSubscriptionProjectionBridge;
import blue.coordination.processor.fragmentation.EffectiveCutCatalogReader;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.EmbeddedScopePlanView;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Public persistence-neutral façade over Language's authoritative
 * subscription-surface validator.
 *
 * <p>Initial projection performs one complete admission pass. The
 * changed-path update overload revalidates only affected branches and exact
 * dependency closures; unchanged occurrence headers are retained without
 * executable-body expansion.</p>
 */
public final class CoordinationSubscriptionProjector {
    private final DocumentProcessor processor;
    private final CoordinationSubscriptionProjectionBridge bridge;

    CoordinationSubscriptionProjector(
            DocumentProcessor processor,
            BlueContracts contracts) {
        this.processor =
                Objects.requireNonNull(
                        processor, "processor");
        this.bridge = new CoordinationSubscriptionProjectionBridge(
                Objects.requireNonNull(contracts, "contracts"));
    }

    /**
     * Projects the complete initial active subscription surface.
     *
     * @param exactRoot exact admitted Root
     * @param rootRevision non-negative host revision
     * @param activationFrontier exclusive activation order frontier
     * @return immutable identity-bearing snapshot
     */
    public CoordinationSubscriptionSnapshot projectCurrent(
            Node exactRoot,
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        return projectCurrent(
                exactRoot,
                rootRevision,
                activationFrontier,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Projects the complete initial active subscription surface while
     * enforcing the explicit nonportable host-work quota.
     *
     * @param exactRoot exact admitted Root
     * @param rootRevision non-negative host revision
     * @param activationFrontier exclusive activation order frontier
     * @param hostQuotas invocation-local nonportable host quota session
     * @return immutable identity-bearing snapshot
     */
    public CoordinationSubscriptionSnapshot projectCurrent(
            Node exactRoot,
            long rootRevision,
            ExternalOrderKey activationFrontier,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        Node root =
                materializeRoot(
                        exactRoot, "exactRoot");
        requireCurrentArguments(
                rootRevision,
                activationFrontier);
        preflightDirectRootSubscriptions(
                root, quotas);
        EffectiveFragmentationCatalog catalog =
                bridge.effectiveFragmentationCatalog(root);
        Map<String, ScopeProvenance> provenanceByScope =
                provenanceByScope(catalog);
        CoordinationSubscriptionProjectionBridge.Projection
                projection =
                bridge.projectCurrent(
                        root,
                        rootRevision,
                        activationFrontier);
        recordProjectionEntries(
                projection,
                quotas,
                CoordinationHostQuotaSession
                        .PROJECT_CURRENT_SUBSCRIPTIONS,
                        false);
        requireCatalogBinding(catalog, projection);
        List<CoordinationSubscriptionOccurrence> occurrences =
                occurrences(
                        projection,
                        Collections
                                .<String,
                                        CoordinationSubscriptionOccurrence>
                                emptyMap(),
                        provenanceByScope);
        return new CoordinationSubscriptionSnapshot(
                projection.languageRuntimeRegistryIdentity(),
                coordinationRuntimeRegistryIdentity(),
                projection.rootBlueId(),
                rootRevision,
                activationFrontier,
                occurrences,
                projection.processEmbeddedRoutes(),
                projection.prunedScopePaths());
    }

    /**
     * Compatibility update that deliberately treats the whole Root as
     * changed.
     *
     * <p>Indexed hosts should call the changed-path overload to retain
     * branch-local validation.</p>
     *
     * @param previous exact prior projection
     * @param exactNewRoot exact resulting Root
     * @param newRootRevision resulting host revision
     * @param transitionOrderKey exact transition order
     * @return immutable delta and resulting snapshot
     */
    public CoordinationSubscriptionUpdate projectUpdate(
            CoordinationSubscriptionSnapshot previous,
            Node exactNewRoot,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey) {
        return projectUpdate(
                previous,
                exactNewRoot,
                newRootRevision,
                transitionOrderKey,
                Collections.singleton(JsonPointer.ROOT),
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Compatibility update that treats the whole Root as changed while
     * enforcing the explicit nonportable host-work quota.
     *
     * @param previous exact prior projection
     * @param exactNewRoot exact resulting Root
     * @param newRootRevision resulting host revision
     * @param transitionOrderKey exact transition order
     * @param hostQuotas invocation-local nonportable host quota session
     * @return immutable delta and resulting snapshot
     */
    public CoordinationSubscriptionUpdate projectUpdate(
            CoordinationSubscriptionSnapshot previous,
            Node exactNewRoot,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey,
            CoordinationHostQuotaSession hostQuotas) {
        return projectUpdate(
                previous,
                exactNewRoot,
                newRootRevision,
                transitionOrderKey,
                Collections.singleton(JsonPointer.ROOT),
                hostQuotas);
    }

    /**
     * Applies the exact Language-owned subscription transition from a
     * successful platform commit.
     *
     * <p>The companion was produced and validated in the same Contracts
     * invocation as the semantic Root result. This overload therefore does
     * not re-run subscription semantics over the published output Root. It
     * verifies the companion against the retained snapshot, applies its exact
     * interval transition, and derives only Coordination's persistence
     * metadata for the resulting active surface.</p>
     *
     * @param previous exact prior projection
     * @param platformResult exact semantic result and companion pair returned
     *                       by the committing Contracts invocation
     * @return immutable exact delta and resulting snapshot
     */
    public CoordinationSubscriptionUpdate applyPlatformCommit(
            CoordinationSubscriptionSnapshot previous,
            PlatformProcessingResult platformResult,
            Node exactResultingRoot) {
        CoordinationSubscriptionSnapshot prior =
                Objects.requireNonNull(previous, "previous");
        PlatformProcessingResult platform =
                Objects.requireNonNull(platformResult, "platformResult");
        PlatformCommitCompanion committed = platform.commitCompanion();
        if (!platform.processResult().commits()
                || !committed.commitsRootAndOutbox()) {
            throw new IllegalArgumentException(
                    "Platform result must commit Root and outbox");
        }
        requireBinding(prior);
        if (!prior.rootBlueId().equals(
                        committed.expectedRootBlueId())
                || prior.rootRevision()
                        != committed.expectedRootRevision()) {
            throw new IllegalArgumentException(
                    "Platform commit companion does not bind the previous "
                            + "subscription snapshot");
        }

        Node newRoot = materializeRoot(
                exactResultingRoot,
                "exactResultingRoot");
        long newRootRevision = committed.resultingRootRevision();
        ExternalOrderKey order = committed.eventOrderKey();
        requireUpdateArguments(
                prior,
                newRootRevision,
                order);
        CoordinationHostQuotaSession quotas =
                CoordinationHostQuotaSession.disabled();
        preflightDirectRootSubscriptions(newRoot, quotas);
        EffectiveFragmentationCatalog catalog =
                bridge.effectiveFragmentationCatalog(newRoot);
        Map<String, ScopeProvenance> provenanceByScope =
                provenanceByScope(catalog);
        List<SubscriptionDelta.Entry> active =
                activeEntries(prior);
        Map<String, CoordinationSubscriptionOccurrence>
                previousByInternalKey =
                indexByInternalKey(prior.occurrences());
        CoordinationSubscriptionProjectionBridge.Projection projection =
                bridge.projectUpdate(
                        active,
                        platform,
                        newRoot,
                        catalog);
        return finalizeUpdate(
                prior,
                newRootRevision,
                order,
                quotas,
                catalog,
                provenanceByScope,
                previousByInternalKey,
                projection);
    }

    /**
     * Projects an incremental transition over exact changed branches.
     *
     * @param previous exact prior projection, including rehydrated values
     * @param exactNewRoot exact resulting Root
     * @param newRootRevision strictly increasing host revision
     * @param transitionOrderKey strictly increasing transition order
     * @param changedPaths non-empty exact absolute changed pointers
     * @return immutable delta and resulting snapshot
     */
    public CoordinationSubscriptionUpdate projectUpdate(
            CoordinationSubscriptionSnapshot previous,
            Node exactNewRoot,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey,
            Set<String> changedPaths) {
        return projectUpdate(
                previous,
                exactNewRoot,
                newRootRevision,
                transitionOrderKey,
                changedPaths,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Projects an incremental transition over exact changed branches while
     * enforcing the explicit nonportable host-work quota.
     *
     * @param previous exact prior projection, including rehydrated values
     * @param exactNewRoot exact resulting Root
     * @param newRootRevision strictly increasing host revision
     * @param transitionOrderKey strictly increasing transition order
     * @param changedPaths non-empty exact absolute changed pointers
     * @param hostQuotas invocation-local nonportable host quota session
     * @return immutable delta and resulting snapshot
     */
    public CoordinationSubscriptionUpdate projectUpdate(
            CoordinationSubscriptionSnapshot previous,
            Node exactNewRoot,
            long newRootRevision,
            ExternalOrderKey transitionOrderKey,
            Set<String> changedPaths,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        CoordinationSubscriptionSnapshot prior =
                Objects.requireNonNull(previous, "previous");
        Node newRoot =
                materializeRoot(
                        exactNewRoot, "exactNewRoot");
        ExternalOrderKey order =
                Objects.requireNonNull(
                        transitionOrderKey,
                        "transitionOrderKey");
        requireBinding(prior);
        requireUpdateArguments(
                prior,
                newRootRevision,
                order);
        Set<String> exactChanges =
                canonicalChangedPaths(changedPaths);
        preflightDirectRootSubscriptions(
                newRoot, quotas);
        EffectiveFragmentationCatalog catalog =
                bridge.effectiveFragmentationCatalog(newRoot);
        Map<String, ScopeProvenance> provenanceByScope =
                provenanceByScope(catalog);
        List<SubscriptionDelta.Entry> active =
                activeEntries(prior);
        Map<String, CoordinationSubscriptionOccurrence>
                previousByInternalKey =
                indexByInternalKey(prior.occurrences());

        CoordinationSubscriptionProjectionBridge.Projection
                projection =
                bridge.projectUpdate(
                        newRoot,
                        active,
                        exactChanges,
                        newRootRevision,
                        order,
                        prior.processEmbeddedRoutes(),
                        prior.prunedScopePaths());
        return finalizeUpdate(
                prior,
                newRootRevision,
                order,
                quotas,
                catalog,
                provenanceByScope,
                previousByInternalKey,
                projection);
    }

    private CoordinationSubscriptionUpdate finalizeUpdate(
            CoordinationSubscriptionSnapshot prior,
            long newRootRevision,
            ExternalOrderKey order,
            CoordinationHostQuotaSession quotas,
            EffectiveFragmentationCatalog catalog,
            Map<String, ScopeProvenance> provenanceByScope,
            Map<String, CoordinationSubscriptionOccurrence>
                    previousByInternalKey,
            CoordinationSubscriptionProjectionBridge.Projection
                    projection) {
        if (!prior.languageRuntimeRegistryIdentity()
                .equals(projection.languageRuntimeRegistryIdentity())) {
            throw new IllegalArgumentException(
                    "Language runtime registry identity changed "
                            + "during subscription projection");
        }
        recordProjectionEntries(
                projection,
                quotas,
                CoordinationHostQuotaSession
                        .PROJECT_UPDATED_SUBSCRIPTIONS,
                true);
        requireCatalogBinding(catalog, projection);

        List<CoordinationSubscriptionOccurrence> resulting =
                occurrences(
                        projection,
                        previousByInternalKey,
                        provenanceByScope);
        CoordinationSubscriptionSnapshot snapshot =
                new CoordinationSubscriptionSnapshot(
                        projection.languageRuntimeRegistryIdentity(),
                        coordinationRuntimeRegistryIdentity(),
                        projection.rootBlueId(),
                        newRootRevision,
                        order,
                        resulting,
                        projection.processEmbeddedRoutes(),
                        projection.prunedScopePaths());

        Map<String, CoordinationSubscriptionOccurrence>
                resultingByInternalKey =
                indexByInternalKey(resulting);
        List<CoordinationSubscriptionOccurrence> added =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        for (SubscriptionDelta.Entry entry
                : projection.delta().added()) {
            CoordinationSubscriptionOccurrence occurrence =
                    resultingByInternalKey.get(internalKey(entry));
            if (occurrence == null) {
                throw new IllegalStateException(
                        "Added occurrence is absent from "
                                + "the resulting snapshot");
            }
            added.add(
                    occurrence.withScopeAndInterval(
                            occurrence.scopeBlueId(),
                            entry));
        }

        List<CoordinationSubscriptionOccurrence> retired =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        Set<String> changed = new LinkedHashSet<String>();
        for (SubscriptionDelta.Entry entry
                : projection.delta().removed()) {
            String key = internalKey(entry);
            CoordinationSubscriptionOccurrence occurrence =
                    previousByInternalKey.get(key);
            if (occurrence == null) {
                throw new IllegalStateException(
                        "Retired occurrence is absent from "
                                + "the previous snapshot");
            }
            retired.add(
                    occurrence.withScopeAndInterval(
                            occurrence.scopeBlueId(),
                            entry));
            changed.add(key);
        }
        for (SubscriptionDelta.Entry entry
                : projection.delta().added()) {
            changed.add(internalKey(entry));
        }

        List<CoordinationSubscriptionOccurrence> unchanged =
                new ArrayList<CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : resulting) {
            if (!changed.contains(
                    internalKey(
                            occurrence.toSubscriptionDeltaEntry()))) {
                unchanged.add(occurrence);
            }
        }
        return new CoordinationSubscriptionUpdate(
                snapshot,
                added,
                retired,
                unchanged,
                order,
                catalog);
    }

    private static void requireUpdateArguments(
            CoordinationSubscriptionSnapshot prior,
            long newRootRevision,
            ExternalOrderKey order) {
        if (newRootRevision <= prior.rootRevision()) {
            throw new IllegalArgumentException(
                    "newRootRevision must be greater than "
                            + "the previous revision");
        }
        if (order.compareTo(prior.activationFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "transitionOrderKey must advance beyond "
                            + "the previous frontier");
        }
    }

    private static List<SubscriptionDelta.Entry> activeEntries(
            CoordinationSubscriptionSnapshot snapshot) {
        List<SubscriptionDelta.Entry> active =
                new ArrayList<SubscriptionDelta.Entry>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            active.add(occurrence.toSubscriptionDeltaEntry());
        }
        return active;
    }

    private Node materializeRoot(
            Node supplied,
            String label) {
        Node root =
                Objects.requireNonNull(
                        supplied, label);
        if (!root.isReferenceOnly()) {
            return root;
        }
        return bridge.materializeExactRoot(root);
    }

    /*
     * This preflight deliberately counts only exact, direct Root contract
     * declarations whose first declared type BlueId is a registered
     * Coordination external-channel type. It is therefore a cheap lower
     * bound, not a second subscription-surface implementation: inherited-only
     * and Process Embedded occurrences remain Language's responsibility.
     */
    private void preflightDirectRootSubscriptions(
            Node exactRoot,
            CoordinationHostQuotaSession quotas) {
        quotas.requireSubscriptionProjectionCapacity(
                minimumDirectRootSubscriptionOccurrences(
                        exactRoot));
    }

    private long minimumDirectRootSubscriptionOccurrences(
            Node exactRoot) {
        Node contracts = exactRoot.getContracts();
        Map<String, Node> declarations =
                contracts != null
                        ? contracts.getProperties()
                        : null;
        if (declarations == null
                || declarations.isEmpty()
                || containsDirectTermination(declarations)) {
            return 0L;
        }
        Set<String> subscriptionTypes =
                new LinkedHashSet<String>();
        CoordinationCurrentRepositoryIdentities current =
                CoordinationCurrentRepositoryIdentities.current();
        subscriptionTypes.add(current.timelineChannelBlueId());
        subscriptionTypes.add(current.allTimelinesChannelBlueId());
        subscriptionTypes.add(current.compositeTimelineChannelBlueId());
        subscriptionTypes.addAll(
                CoordinationRuntimeRegistrations
                        .timelineSubtypeBlueIds(processor));
        long count = 0L;
        for (Node declaration : declarations.values()) {
            if (subscriptionTypes.contains(
                    firstDeclaredTypeBlueId(declaration))) {
                count = Math.addExact(count, 1L);
            }
        }
        return count;
    }

    private static boolean containsDirectTermination(
            Map<String, Node> declarations) {
        Node terminated =
                declarations.get(
                        ProcessorContractConstants
                                .KEY_TERMINATED);
        return RuntimeBlueIds
                .PROCESSING_TERMINATED_MARKER.equals(
                        firstDeclaredTypeBlueId(
                                terminated));
    }

    private static String firstDeclaredTypeBlueId(
            Node declaration) {
        Node type =
                declaration != null
                        ? declaration.getType()
                        : null;
        Set<Node> visited =
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>());
        while (type != null && visited.add(type)) {
            if (type.getBlueId() != null) {
                return type.getBlueId();
            }
            type = type.getType();
        }
        return null;
    }

    private static void requireCurrentArguments(
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
        Objects.requireNonNull(
                activationFrontier,
                "activationFrontier");
    }

    private static void recordProjectionEntries(
            CoordinationSubscriptionProjectionBridge.Projection projection,
            CoordinationHostQuotaSession quotas,
            String operation,
            boolean includeRetired) {
        int index = 0;
        for (SubscriptionDelta.Entry ignored
                : projection.activeEntries()) {
            quotas.recordSubscriptionOccurrence(
                    operation,
                    index++,
                    "active-occurrence");
        }
        if (!includeRetired) {
            return;
        }
        for (SubscriptionDelta.Entry ignored
                : projection.delta().removed()) {
            quotas.recordSubscriptionOccurrence(
                    operation,
                    index++,
                    "retired-occurrence");
        }
    }

    private List<CoordinationSubscriptionOccurrence> occurrences(
            CoordinationSubscriptionProjectionBridge.Projection
                    projection,
            Map<String, CoordinationSubscriptionOccurrence>
                    previous,
            Map<String, ScopeProvenance> provenanceByScope) {
        List<CoordinationSubscriptionOccurrence> result =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        for (SubscriptionDelta.Entry entry
                : projection.activeEntries()) {
            String key = internalKey(entry);
            String scopeBlueId =
                    Objects.requireNonNull(
                            projection.scopeBlueIds().get(key),
                            "scopeBlueId");
            ScopeProvenance provenance =
                    provenanceByScope.get(entry.scopePath());
            if (provenance == null) {
                throw new IllegalStateException(
                        "Subscription projection selected a scope absent "
                                + "from the structured fragmentation "
                                + "catalog: " + entry.scopePath());
            }
            CoordinationSubscriptionProjectionBridge
                    .HeaderProjection header =
                    projection.headers().get(key);
            if (header != null) {
                result.add(
                        new CoordinationSubscriptionOccurrence(
                                entry.scopePath(),
                                scopeBlueId,
                                provenance.declaringScopePath,
                                provenance.origin,
                                provenance.explicitDeclarationPath,
                                provenance.collectionDeclarationPath,
                                provenance.collectionMemberKey,
                                entry.channelKey(),
                                entry
                                        .sourceContributionNodeBlueIds(),
                                entry.effectiveTypeBlueId(),
                                entry.order(),
                                entry.checkpointDomainBlueId(),
                                header.identityBlueId(),
                                header.fieldBlueIds(),
                                entry.subscriptionKeys(),
                                entry.activationRootRevision(),
                                entry.startAfterExternalOrderKey(),
                                entry.endAtRootRevision(),
                                entry.dependencies()));
                continue;
            }
            CoordinationSubscriptionOccurrence retained =
                    previous.get(key);
            if (retained == null) {
                throw new IllegalStateException(
                        "Language retained an occurrence without "
                                + "prior public header evidence");
            }
            if (!provenance.matches(retained)) {
                throw new IllegalStateException(
                        "Language retained an occurrence after its "
                                + "structured declaration provenance "
                                + "changed at " + entry.scopePath());
            }
            result.add(
                    retained.withScopeAndInterval(
                            scopeBlueId, entry));
        }
        Collections.sort(
                result,
                CoordinationSubscriptionOccurrence
                        .CANONICAL_ORDER);
        return Collections.unmodifiableList(result);
    }

    private static Map<String, ScopeProvenance> provenanceByScope(
            EffectiveFragmentationCatalog catalog) {
        Map<String, ScopeProvenance> result =
                new LinkedHashMap<String, ScopeProvenance>();
        boolean rootPlanPresent = false;
        for (EffectiveCutCatalogReader.ScopePlan scopePlan
                : EffectiveCutCatalogReader.read(catalog)) {
            if ("/".equals(scopePlan.scopePath())) {
                rootPlanPresent = true;
                result.put(
                        "/",
                        ScopeProvenance.root());
            }
            for (EffectiveCutCatalogReader.EmbeddedOccurrence occurrence
                    : scopePlan.occurrences()) {
                CoordinationSubscriptionOccurrence.Origin origin =
                        occurrence.origin()
                                        == EmbeddedScopePlanView
                                                .Origin.EXPLICIT
                                ? CoordinationSubscriptionOccurrence
                                        .Origin.EXPLICIT
                                : CoordinationSubscriptionOccurrence
                                        .Origin.COLLECTION_MEMBER;
                ScopeProvenance provenance =
                        new ScopeProvenance(
                                occurrence.declaringScopePath(),
                                origin,
                                occurrence.explicitDeclarationPath(),
                                occurrence.collectionDeclarationPath(),
                                occurrence.collectionMemberKey());
                if (result.put(
                        occurrence.concretePath(),
                        provenance) != null) {
                    throw new IllegalArgumentException(
                            "Structured fragmentation catalog declares "
                                    + "scope more than once: "
                                    + occurrence.concretePath());
                }
            }
        }
        if (!rootPlanPresent) {
            throw new IllegalArgumentException(
                    "Structured fragmentation catalog has no Root scope "
                            + "plan");
        }
        return Collections.unmodifiableMap(result);
    }

    private static void requireCatalogBinding(
            EffectiveFragmentationCatalog catalog,
            CoordinationSubscriptionProjectionBridge.Projection
                    projection) {
        if (!catalog.rootBlueId().equals(
                projection.rootBlueId())) {
            throw new IllegalStateException(
                    "Subscription projection Root identity disagrees with "
                            + "the structured fragmentation catalog");
        }
    }

    private void requireBinding(
            CoordinationSubscriptionSnapshot snapshot) {
        if (!CoordinationSubscriptionSnapshot.VERSION
                .equals(snapshot.projectionVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported Coordination projection version");
        }
        if (!CoordinationSubscriptionSnapshot
                .ALGORITHM_IDENTITY.equals(
                        snapshot.algorithmIdentity())) {
            throw new IllegalArgumentException(
                    "Subscription projection algorithm "
                            + "identity mismatch");
        }
        if (!coordinationRuntimeRegistryIdentity().equals(
                snapshot
                        .coordinationRuntimeRegistryIdentity())) {
            throw new IllegalArgumentException(
                    "Coordination runtime registry identity "
                            + "mismatch");
        }
        if (!bridge.languageRuntimeRegistryIdentity()
                .equals(
                        snapshot
                                .languageRuntimeRegistryIdentity())) {
            throw new IllegalArgumentException(
                    "Language runtime registry identity mismatch");
        }
    }

    private String coordinationRuntimeRegistryIdentity() {
        return CoordinationRuntimeRegistrations
                .identity(processor);
    }

    private static Set<String> canonicalChangedPaths(
            Set<String> supplied) {
        Objects.requireNonNull(supplied, "changedPaths");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "changedPaths must not be empty");
        }
        Set<String> result =
                new LinkedHashSet<String>();
        for (String path : supplied) {
            result.add(
                    PointerUtils.normalizePointer(
                            Objects.requireNonNull(
                                    path,
                                    "changed path")));
        }
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, CoordinationSubscriptionOccurrence>
    indexByInternalKey(
            List<CoordinationSubscriptionOccurrence> occurrences) {
        Map<String, CoordinationSubscriptionOccurrence> result =
                new LinkedHashMap<
                        String,
                        CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : occurrences) {
            result.put(
                    internalKey(
                            occurrence
                                    .toSubscriptionDeltaEntry()),
                    occurrence);
        }
        return result;
    }

    private static String internalKey(
            SubscriptionDelta.Entry entry) {
        return entry.scopePath()
                + "\u001f" + entry.channelKey();
    }

    private static final class ScopeProvenance {
        private final String declaringScopePath;
        private final CoordinationSubscriptionOccurrence.Origin origin;
        private final String explicitDeclarationPath;
        private final String collectionDeclarationPath;
        private final String collectionMemberKey;

        private ScopeProvenance(
                String declaringScopePath,
                CoordinationSubscriptionOccurrence.Origin origin,
                String explicitDeclarationPath,
                String collectionDeclarationPath,
                String collectionMemberKey) {
            this.declaringScopePath = declaringScopePath;
            this.origin = origin;
            this.explicitDeclarationPath = explicitDeclarationPath;
            this.collectionDeclarationPath = collectionDeclarationPath;
            this.collectionMemberKey = collectionMemberKey;
        }

        private static ScopeProvenance root() {
            return new ScopeProvenance(
                    "/",
                    CoordinationSubscriptionOccurrence.Origin.ROOT,
                    null,
                    null,
                    null);
        }

        private boolean matches(
                CoordinationSubscriptionOccurrence occurrence) {
            return declaringScopePath.equals(
                            occurrence.declaringScopePath())
                    && origin == occurrence.origin()
                    && Objects.equals(
                            explicitDeclarationPath,
                            occurrence.explicitDeclarationPath())
                    && Objects.equals(
                            collectionDeclarationPath,
                            occurrence.collectionDeclarationPath())
                    && Objects.equals(
                            collectionMemberKey,
                            occurrence.collectionMemberKey());
        }
    }
}
