package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.CoordinationProcessHeaderBridge;
import blue.language.processor.CoordinationSubscriptionProjectionBridge;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.JsonPointer;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;

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

    /**
     * Creates a projector bound to one configured Coordination processor.
     *
     * @param processor configured processor
     */
    CoordinationSubscriptionProjector(
            DocumentProcessor processor) {
        this.processor =
                Objects.requireNonNull(
                        processor, "processor");
        this.bridge =
                new CoordinationSubscriptionProjectionBridge(
                        this.processor);
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
        List<CoordinationSubscriptionOccurrence> occurrences =
                occurrences(
                        projection,
                        Collections
                                .<String,
                                        CoordinationSubscriptionOccurrence>
                                emptyMap());
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
        if (newRootRevision
                <= prior.rootRevision()) {
            throw new IllegalArgumentException(
                    "newRootRevision must be greater than "
                            + "the previous revision");
        }
        if (order.compareTo(
                prior.activationFrontier()) <= 0) {
            throw new IllegalArgumentException(
                    "transitionOrderKey must advance beyond "
                            + "the previous frontier");
        }
        Set<String> exactChanges =
                canonicalChangedPaths(changedPaths);
        preflightDirectRootSubscriptions(
                newRoot, quotas);
        List<SubscriptionDelta.Entry> active =
                new ArrayList<SubscriptionDelta.Entry>();
        Map<String, CoordinationSubscriptionOccurrence>
                previousByInternalKey =
                new LinkedHashMap<
                        String,
                        CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : prior.occurrences()) {
            SubscriptionDelta.Entry entry =
                    occurrence.toSubscriptionDeltaEntry();
            active.add(entry);
            previousByInternalKey.put(
                    internalKey(entry),
                    occurrence);
        }

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
        if (!prior.languageRuntimeRegistryIdentity()
                .equals(
                        projection
                                .languageRuntimeRegistryIdentity())) {
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

        List<CoordinationSubscriptionOccurrence> resulting =
                occurrences(
                        projection,
                        previousByInternalKey);
        CoordinationSubscriptionSnapshot snapshot =
                new CoordinationSubscriptionSnapshot(
                        projection
                                .languageRuntimeRegistryIdentity(),
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
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        for (SubscriptionDelta.Entry entry
                : projection.delta().added()) {
            CoordinationSubscriptionOccurrence occurrence =
                    resultingByInternalKey.get(
                            internalKey(entry));
            if (occurrence == null) {
                throw new IllegalStateException(
                        "Added occurrence is absent from "
                                + "the resulting snapshot");
            }
            added.add(occurrence);
        }

        List<CoordinationSubscriptionOccurrence> retired =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        Set<String> changed =
                new LinkedHashSet<String>();
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
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : resulting) {
            if (!changed.contains(
                    internalKey(
                            occurrence
                                    .toSubscriptionDeltaEntry()))) {
                unchanged.add(occurrence);
            }
        }
        return new CoordinationSubscriptionUpdate(
                snapshot,
                added,
                retired,
                unchanged,
                order);
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
        return CoordinationProcessHeaderBridge
                .canonicalExactCopy(
                        CoordinationProcessHeaderBridge
                                .materializeVerifiedExactReference(
                                        processor,
                                        root));
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
        subscriptionTypes.add(TimelineChannel.blueId());
        subscriptionTypes.add(AllTimelinesChannel.blueId());
        subscriptionTypes.add(
                CompositeTimelineChannel.blueId());
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
                    previous) {
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
            CoordinationSubscriptionProjectionBridge
                    .HeaderProjection header =
                    projection.headers().get(key);
            if (header != null) {
                result.add(
                        new CoordinationSubscriptionOccurrence(
                                entry.scopePath(),
                                scopeBlueId,
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
}
