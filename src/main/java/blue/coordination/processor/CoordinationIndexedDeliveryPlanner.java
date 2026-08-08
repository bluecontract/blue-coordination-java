package blue.coordination.processor;

import blue.coordination.fastpath.AdmittedOccurrence;
import blue.coordination.fastpath.AdmittedProjection;
import blue.coordination.engine.CoordinationProcessingEngine
        .AdmittedPlanningAuthority;
import blue.coordination.processor.delivery.CoordinationDeliveryDiagnosticView;
import blue.coordination.processor.delivery.CoordinationIndexedDeliveryEngine;
import blue.coordination.processor.subscription.CoordinationSubscriptionProjectionBridge;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProviderResult;
import blue.language.model.NodePath;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Public, persistence-neutral indexed Coordination delivery planner.
 *
 * <p>A host supplies candidate occurrence keys from its physical index. This
 * planner fetches and verifies the exact Root/event, validates snapshot and
 * runtime bindings, re-runs the registered Language subscription functions,
 * rejects an incomplete or wrongly ordered candidate list, and returns
 * Language-verifiable evidence. No persistence or cross-document scheduling
 * policy is embedded here.</p>
 */
public final class CoordinationIndexedDeliveryPlanner {

    private final blue.language.processor.DocumentProcessor
            processor;
    private final CoordinationIndexedDeliveryEngine engine;
    private final CoordinationSubscriptionProjectionBridge
            subscriptionProjectionBridge;
    private final AdmittedPlanningAuthority admittedPlanningAuthority;

    /**
     * Creates a planner that delegates authoritative semantic evaluation to
     * the public Contracts service.
     */
    CoordinationIndexedDeliveryPlanner(
            blue.language.processor.DocumentProcessor processor,
            BlueContracts contracts) {
        this(processor, contracts, null);
    }

    CoordinationIndexedDeliveryPlanner(
            blue.language.processor.DocumentProcessor processor,
            BlueContracts contracts,
            AdmittedPlanningAuthority admittedPlanningAuthority) {
        this.processor = Objects.requireNonNull(processor, "processor");
        BlueContracts exactContracts = Objects.requireNonNull(
                contracts, "contracts");
        if (admittedPlanningAuthority != null) {
            admittedPlanningAuthority.requireDomain(
                    this.processor, exactContracts);
        }
        this.engine = admittedPlanningAuthority == null
                ? new CoordinationIndexedDeliveryEngine(exactContracts)
                : CoordinationIndexedDeliveryEngine.forAdmittedPlanning(
                        exactContracts, admittedPlanningAuthority);
        this.subscriptionProjectionBridge =
                new CoordinationSubscriptionProjectionBridge(
                        exactContracts);
        this.admittedPlanningAuthority = admittedPlanningAuthority;
    }

    /**
     * Prepares one exact event against a persisted active snapshot.
     *
     * <p>The supplied candidate collection is an exact, ordered index
     * contract. Duplicates, omissions, extra false positives, and canonical
     * ordering drift are rejected before a plan is returned.</p>
     *
     * @param rootBlueId exact observation Root identity
     * @param eventBlueId exact event identity
     * @param activeSnapshot exact persisted active subscription snapshot
     * @param indexedCandidateOccurrenceKeys exact ordered index result
     * @param exactProvider exact direct-node provider
     * @param rootRevision managed Root revision
     * @param eventOrderKey immutable external event order
     * @return immutable verified delivery preparation
     */
    public CoordinationPreparedDelivery prepare(
            String rootBlueId,
            String eventBlueId,
            CoordinationSubscriptionSnapshot activeSnapshot,
            Collection<String> indexedCandidateOccurrenceKeys,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey) {
        return prepare(
                rootBlueId,
                eventBlueId,
                activeSnapshot,
                indexedCandidateOccurrenceKeys,
                exactProvider,
                rootRevision,
                eventOrderKey,
                CoordinationHostQuotaSession.disabled());
    }

    /**
     * Processes an exact prepared Root/event pair for one atomic host commit
     * through the same Contracts generation that verified indexed delivery.
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            CoordinationPreparedDelivery prepared) {
        return engine.processForPlatformCommit(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(event, "event"),
                Objects.requireNonNull(prepared, "prepared").evidence());
    }

    /**
     * Processes an exact preparation with the same strict request-local
     * provider domain used by platform hosts. The evaluator-produced plan
     * carries its non-forgeable Contracts generation binding into PROCESS.
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            CoordinationPreparedDelivery prepared,
            NodeProvider exactProvider) {
        CoordinationPreparedDelivery exactPrepared =
                Objects.requireNonNull(prepared, "prepared");
        return engine.processForPlatformCommit(
                Objects.requireNonNull(root, "root"),
                Objects.requireNonNull(event, "event"),
                exactPrepared.deliveryPlan(),
                Objects.requireNonNull(
                        exactProvider, "exactProvider"));
    }

    /**
     * Prepares one exact event while enforcing explicit nonportable host-work
     * quotas for candidate validation and prefetch construction.
     *
     * @param rootBlueId exact observation Root identity
     * @param eventBlueId exact event identity
     * @param activeSnapshot exact persisted active subscription snapshot
     * @param indexedCandidateOccurrenceKeys exact ordered index result
     * @param exactProvider exact direct-node provider
     * @param rootRevision managed Root revision
     * @param eventOrderKey immutable external event order
     * @param hostQuotas invocation-local nonportable host quota session
     * @return immutable verified delivery preparation
     */
    public CoordinationPreparedDelivery prepare(
            String rootBlueId,
            String eventBlueId,
            CoordinationSubscriptionSnapshot activeSnapshot,
            Collection<String> indexedCandidateOccurrenceKeys,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            CoordinationHostQuotaSession hostQuotas) {
        CoordinationHostQuotaSession quotas =
                Objects.requireNonNull(
                        hostQuotas, "hostQuotas");
        String exactRootBlueId = requireText(
                rootBlueId, "rootBlueId");
        String exactEventBlueId = requireText(
                eventBlueId, "eventBlueId");
        CoordinationSubscriptionSnapshot.PlanningVerification verified =
                requireSnapshot(
                        activeSnapshot,
                        exactRootBlueId,
                        rootRevision,
                        eventOrderKey);
        ExactLookup lookup = new ExactLookup(
                Objects.requireNonNull(
                        exactProvider, "exactProvider"));
        Node root = lookup.require(exactRootBlueId);
        Node event = lookup.require(exactEventBlueId);

        return prepareVerified(
                exactRootBlueId,
                exactEventBlueId,
                verified,
                indexedCandidateOccurrenceKeys,
                exactProvider,
                rootRevision,
                eventOrderKey,
                quotas,
                lookup,
                root,
                event,
                false,
                null);
    }

    /**
     * Engine-only fast path for exact Root/event values already verified by
     * canonical inventory admission. The opaque authority is compared by
     * reference, so an external caller cannot turn an untrusted Node into an
     * admitted value. Contracts still performs its own public-boundary
     * defensive copies and remains the semantic evaluator.
     */
    public CoordinationPreparedDelivery prepareAdmitted(
            AdmittedPlanningAuthority admittedAuthority,
            String rootBlueId,
            Node exactRoot,
            String eventBlueId,
            Node exactEvent,
            CoordinationSubscriptionSnapshot activeSnapshot,
            Collection<String> indexedCandidateOccurrenceKeys,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey) {
        if (admittedPlanningAuthority == null
                || admittedPlanningAuthority != Objects.requireNonNull(
                        admittedAuthority, "admittedAuthority")) {
            throw invalid("Admitted planning capability is invalid");
        }
        String exactRootBlueId = requireText(rootBlueId, "rootBlueId");
        String exactEventBlueId = requireText(eventBlueId, "eventBlueId");
        Node root = requireAdmittedNode(
                exactRoot, exactRootBlueId, "exactRoot");
        Node event = requireAdmittedNode(
                exactEvent, exactEventBlueId, "exactEvent");
        CoordinationSubscriptionSnapshot.PlanningVerification verified =
                requireSnapshot(
                        activeSnapshot,
                        exactRootBlueId,
                        rootRevision,
                        eventOrderKey);
        NodeProvider provider = Objects.requireNonNull(
                exactProvider, "exactProvider");
        ExactLookup lookup = ExactLookup.admitted(
                provider,
                exactRootBlueId,
                root,
                exactEventBlueId,
                event);
        return prepareVerified(
                exactRootBlueId,
                exactEventBlueId,
                verified,
                indexedCandidateOccurrenceKeys,
                provider,
                rootRevision,
                eventOrderKey,
                CoordinationHostQuotaSession.disabled(),
                lookup,
                root,
                event,
                true,
                null);
    }

    /**
     * Engine-owned admitted path using a generation-bound static projection.
     * The frozen semantic evaluator still runs on a cache miss; only
     * Coordination's repeated candidate and scope-chain discovery is reused.
     */
    CoordinationPreparedDelivery prepareProjectedAdmitted(
            AdmittedPlanningAuthority admittedAuthority,
            String rootBlueId,
            Node exactRoot,
            String eventBlueId,
            Node exactEvent,
            CoordinationSubscriptionSnapshot activeSnapshot,
            Collection<String> indexedCandidateOccurrenceKeys,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            AdmittedProjection.SelectedSurface selectedSurface) {
        if (admittedPlanningAuthority == null
                || admittedPlanningAuthority != Objects.requireNonNull(
                        admittedAuthority, "admittedAuthority")) {
            throw invalid("Admitted planning capability is invalid");
        }
        String exactRootBlueId = requireText(rootBlueId, "rootBlueId");
        String exactEventBlueId = requireText(eventBlueId, "eventBlueId");
        Node root = requireAdmittedNode(
                exactRoot, exactRootBlueId, "exactRoot");
        Node event = requireAdmittedNode(
                exactEvent, exactEventBlueId, "exactEvent");
        CoordinationSubscriptionSnapshot.PlanningVerification verified =
                requireSnapshot(
                        activeSnapshot,
                        exactRootBlueId,
                        rootRevision,
                        eventOrderKey);
        AdmittedProjection.SelectedSurface projected =
                Objects.requireNonNull(
                        selectedSurface, "selectedSurface");
        requireProjectedGeneration(
                projected,
                exactRootBlueId,
                rootRevision,
                verified.snapshot().digest());
        NodeProvider provider = Objects.requireNonNull(
                exactProvider, "exactProvider");
        ExactLookup lookup = ExactLookup.admitted(
                provider,
                exactRootBlueId,
                root,
                exactEventBlueId,
                event);
        return prepareVerified(
                exactRootBlueId,
                exactEventBlueId,
                verified,
                indexedCandidateOccurrenceKeys,
                provider,
                rootRevision,
                eventOrderKey,
                CoordinationHostQuotaSession.disabled(),
                lookup,
                root,
                event,
                true,
                projected);
    }

    private CoordinationPreparedDelivery prepareVerified(
            String exactRootBlueId,
            String exactEventBlueId,
            CoordinationSubscriptionSnapshot.PlanningVerification verified,
            Collection<String> indexedCandidateOccurrenceKeys,
            NodeProvider exactProvider,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            CoordinationHostQuotaSession quotas,
            ExactLookup lookup,
            Node root,
            Node event,
            boolean admitted,
            AdmittedProjection.SelectedSurface projectedSurface) {
        CoordinationSubscriptionSnapshot snapshot = verified.snapshot();

        CandidateMapping candidates =
                candidates(
                        verified,
                        indexedCandidateOccurrenceKeys,
                        quotas);
        if (projectedSurface != null
                && !projectedSurface.publicKeys().equals(
                        candidates.publicKeys)) {
            throw invalid(
                    "Admitted projection changed indexed candidate order");
        }

        CoordinationIndexedDeliveryEngine.Prepared prepared =
                admitted
                        ? engine.prepareAdmitted(
                        admittedPlanningAuthority,
                        exactRootBlueId,
                        root,
                        exactEventBlueId,
                        event,
                        exactProvider,
                        rootRevision,
                        eventOrderKey,
                        verified.indexedActiveSurface(),
                        candidates.publicKeys)
                        : engine.prepare(
                        root,
                        event,
                        exactProvider,
                        rootRevision,
                        eventOrderKey,
                        verified.indexedActiveSurface(),
                        candidates.publicKeys);
        List<String> publicOrder = new ArrayList<>();
        Map<String, CoordinationSubscriptionOccurrence>
                selectedOccurrences =
                new LinkedHashMap<>();
        for (String languageKey
                : prepared.occurrenceOrder()) {
            CoordinationSubscriptionOccurrence occurrence =
                    verified.occurrenceByLanguageKey(languageKey);
            if (occurrence == null) {
                throw invalid(
                        "Language selected an occurrence outside the "
                                + "active subscription snapshot");
            }
            publicOrder.add(occurrence.occurrenceKey());
            selectedOccurrences.put(
                    occurrence.occurrenceKey(),
                    occurrence);
        }
        if (!publicOrder.equals(candidates.publicKeys)) {
            throw invalid(
                    "Indexed candidate public occurrence order changed "
                            + "during semantic planning");
        }

        verifyDiagnostics(
                prepared.diagnostics(),
                selectedOccurrences);
        List<CoordinationDeliveryDiagnostic> publicDiagnostics =
                publicDiagnostics(
                        prepared.diagnostics(),
                        publicOrder);
        Map<String, List<String>> scopeChains = projectedSurface == null
                ? selectedScopeChains(
                        exactRootBlueId,
                        root,
                        selectedOccurrences.values(),
                        lookup)
                : projectedScopeChains(
                        exactRootBlueId,
                        projectedSurface,
                        selectedOccurrences,
                        publicOrder);
        ResourceClosure resources =
                resourceClosure(
                        exactRootBlueId,
                        exactEventBlueId,
                        selectedOccurrences.values(),
                        publicDiagnostics,
                        scopeChains,
                        quotas);
        CoordinationSemanticDemandBoundary demandBoundary =
                new CoordinationSemanticDemandBoundary(
                        exactRootBlueId,
                        exactEventBlueId,
                        scopeChains.keySet(),
                        resources.requiredSeeds,
                        resources.sourceHeaders,
                        resources.targetHeaders,
                        resources.targetSelectors,
                        resources.prefetch);
        return new CoordinationPreparedDelivery(
                exactRootBlueId,
                exactEventBlueId,
                prepared.evidence(),
                prepared.plan(),
                prepared.planIdentity(),
                snapshot.digest(),
                publicOrder,
                publicDiagnostics,
                scopeChains,
                resources.requiredSeeds,
                resources.prefetch,
                demandBoundary);
    }

    private static void requireProjectedGeneration(
            AdmittedProjection.SelectedSurface selected,
            String rootBlueId,
            long rootRevision,
            String subscriptionDigest) {
        if (!selected.generation().rootBlueId().equals(rootBlueId)
                || selected.generation().rootRevision() != rootRevision
                || !selected.generation().subscriptionDigest().equals(
                        subscriptionDigest)) {
            throw invalid(
                    "Admitted projection belongs to another Root generation");
        }
    }

    private static Map<String, List<String>> projectedScopeChains(
            String rootBlueId,
            AdmittedProjection.SelectedSurface selected,
            Map<String, CoordinationSubscriptionOccurrence> occurrences,
            List<String> publicOrder) {
        if (!selected.publicKeys().equals(publicOrder)
                || selected.occurrences().size() != publicOrder.size()) {
            throw invalid(
                    "Admitted projection selection differs from Language");
        }
        Set<String> expectedScopePaths = new LinkedHashSet<String>();
        for (int index = 0; index < publicOrder.size(); index++) {
            CoordinationSubscriptionOccurrence occurrence =
                    occurrences.get(publicOrder.get(index));
            AdmittedOccurrence projected =
                    selected.occurrences().get(index);
            if (occurrence == null
                    || !projected.publicKey().equals(
                            occurrence.occurrenceKey())
                    || !projected.scopePath().equals(
                            occurrence.scopePath())
                    || !projected.scopeBlueId().equals(
                            occurrence.scopeBlueId())
                    || !projected.channelKey().equals(
                            occurrence.channelKey())
                    || !projected.effectiveTypeBlueId().equals(
                            occurrence.effectiveTypeBlueId())
                    || projected.order() != occurrence.order()
                    || !projected.headerIdentityBlueId().equals(
                            occurrence.headerIdentityBlueId())
                    || !projected.checkpointDomainBlueId().equals(
                            occurrence.checkpointDomainBlueId())
                    || !projected.sourceContributionBlueIds().equals(
                            occurrence.sourceContributionNodeBlueIds())
                    || !projected.dependencyBlueIds().equals(
                            occurrence.dependencyNodeBlueIds())
                    || !projected.subscriptionKeys().equals(
                            occurrence.subscriptionKeys())) {
                throw invalid(
                        "Admitted projection occurrence is stale at "
                                + publicOrder.get(index));
            }
            expectedScopePaths.add(occurrence.scopePath());
        }
        if (!selected.scopeChains().keySet().equals(expectedScopePaths)) {
            throw invalid(
                    "Admitted projection scope-chain set is incomplete");
        }
        for (Map.Entry<String, List<String>> entry
                : selected.scopeChains().entrySet()) {
            List<String> chain = entry.getValue();
            if (chain.isEmpty() || !rootBlueId.equals(chain.get(0))) {
                throw invalid(
                        "Admitted projection scope chain has another Root");
            }
        }
        return selected.scopeChains();
    }

    private static Node requireAdmittedNode(
            Node supplied,
            String expectedBlueId,
            String label) {
        Node value = Objects.requireNonNull(supplied, label);
        if (value.isReferenceOnly()) {
            throw invalid(label + " must be expanded exact content");
        }
        String declared = value.getBlueId();
        if (declared != null && !expectedBlueId.equals(declared)) {
            throw invalid(label + " carries another declared BlueId");
        }
        return value;
    }

    private static List<CoordinationDeliveryDiagnostic>
    publicDiagnostics(
            List<CoordinationDeliveryDiagnosticView> diagnostics,
            List<String> publicOrder) {
        List<CoordinationDeliveryDiagnostic> result =
                new ArrayList<>(diagnostics.size());
        for (int index = 0;
                index < diagnostics.size();
                index++) {
            CoordinationDeliveryDiagnosticView diagnostic =
                    diagnostics.get(index);
            result.add(new CoordinationDeliveryDiagnostic(
                    publicOrder.get(index),
                    diagnostic.scopePath(),
                    diagnostic.sourceChannelKey(),
                    diagnostic.sourceEffectiveTypeBlueId(),
                    diagnostic.sourceHeaderBlueId(),
                    diagnostic.sourceContributionBlueIds(),
                    diagnostic.checkpointDomainBlueId(),
                    diagnostic.checkpointSubjectBlueId(),
                    diagnostic.payloadBlueId(),
                    diagnostic.targetChannelKey(),
                    diagnostic.targetEffectiveTypeBlueId(),
                    diagnostic.targetHeaderBlueId(),
                    diagnostic.targetContributionBlueIds(),
                    diagnostic.logicalDeliveryKey(),
                    diagnostic.dependencyBlueIds()));
        }
        return Collections.unmodifiableList(result);
    }

    private CoordinationSubscriptionSnapshot.PlanningVerification
    requireSnapshot(
            CoordinationSubscriptionSnapshot supplied,
            String rootBlueId,
            long rootRevision,
            ExternalOrderKey eventOrderKey) {
        CoordinationSubscriptionSnapshot snapshot =
                Objects.requireNonNull(
                        supplied, "activeSnapshot");
        if (rootRevision < 0L) {
            throw invalid(
                    "Root revision must be non-negative");
        }
        ExternalOrderKey order = Objects.requireNonNull(
                eventOrderKey, "eventOrderKey");
        /* Construction/rehydration validates every active occurrence once.
         * The proof below binds the immutable exact indexes to this runtime
         * and Root generation in constant time. */
        CoordinationSubscriptionSnapshot.PlanningVerification verified =
                snapshot.verifiedForInProcessPlanning(
                        subscriptionProjectionBridge
                                .languageRuntimeRegistryIdentity(),
                        CoordinationRuntimeRegistrations
                                .identity(processor),
                        rootBlueId,
                        rootRevision);
        if (order.compareTo(
                verified.snapshot().activationFrontier()) <= 0) {
            throw invalid(
                    "Event order is not after the active subscription "
                            + "snapshot frontier");
        }
        return verified;
    }

    private static CandidateMapping candidates(
            CoordinationSubscriptionSnapshot.PlanningVerification verified,
            Collection<String> supplied,
            CoordinationHostQuotaSession hostQuotas) {
        Objects.requireNonNull(
                supplied,
                "indexedCandidateOccurrenceKeys");
        List<String> publicKeys = new ArrayList<>(
                supplied.size());
        Set<String> unique = new LinkedHashSet<>();
        int candidateIndex = 0;
        for (String key : supplied) {
            hostQuotas.recordIndexedCandidate(
                    candidateIndex++);
            String exact = requireText(
                    key, "indexed candidate occurrence key");
            if (!unique.add(exact)) {
                throw invalid(
                        "Duplicate indexed candidate occurrence: "
                                + exact);
            }
            CoordinationSubscriptionOccurrence occurrence =
                    verified.occurrence(exact);
            if (occurrence == null) {
                throw invalid(
                        "Indexed candidate is absent or stale in the active "
                                + "snapshot: " + exact);
            }
            publicKeys.add(exact);
        }
        return new CandidateMapping(publicKeys);
    }

    private static void verifyDiagnostics(
            List<CoordinationDeliveryDiagnosticView> diagnostics,
            Map<String, CoordinationSubscriptionOccurrence>
                    selectedOccurrences) {
        if (diagnostics.size()
                != selectedOccurrences.size()) {
            throw invalid(
                    "Prepared diagnostics do not cover the selected "
                            + "source occurrence set");
        }
        int index = 0;
        for (CoordinationSubscriptionOccurrence occurrence
                : selectedOccurrences.values()) {
            CoordinationDeliveryDiagnosticView diagnostic =
                    diagnostics.get(index++);
            if (!occurrence.scopePath().equals(
                    diagnostic.scopePath())
                    || !occurrence.channelKey().equals(
                    diagnostic.sourceChannelKey())
                    || !occurrence.effectiveTypeBlueId()
                    .equals(
                            diagnostic
                                    .sourceEffectiveTypeBlueId())
                    || !occurrence.headerIdentityBlueId()
                    .equals(
                            diagnostic.sourceHeaderBlueId())
                    || !occurrence
                    .sourceContributionNodeBlueIds()
                    .equals(
                            diagnostic
                                    .sourceContributionBlueIds())
                    || !occurrence.checkpointDomainBlueId()
                    .equals(
                            diagnostic
                                    .checkpointDomainBlueId())) {
                throw invalid(
                        "Prepared source diagnostic disagrees with "
                                + "the retained subscription snapshot at "
                                + occurrence.occurrenceKey());
            }
        }
    }

    private static Map<String, List<String>>
    selectedScopeChains(
            String rootBlueId,
            Node root,
            Collection<CoordinationSubscriptionOccurrence>
                    selected,
            ExactLookup lookup) {
        Map<String, List<String>> result =
                new LinkedHashMap<>();
        Map<String, String> identitiesByPointer =
                new LinkedHashMap<>();
        CoordinationExactNodeIndex exactNodeIndex =
                new CoordinationExactNodeIndex();
        identitiesByPointer.put(JsonPointer.ROOT, rootBlueId);
        for (CoordinationSubscriptionOccurrence occurrence
                : selected) {
            String scopePath = occurrence.scopePath();
            if (result.containsKey(scopePath)) {
                continue;
            }
            List<String> identities = new ArrayList<>();
            identities.add(rootBlueId);
            List<String> segments =
                    JsonPointer.split(scopePath);
            List<String> prefix = new ArrayList<>();
            for (String segment : segments) {
                prefix.add(segment);
                String pointer =
                        JsonPointer.toPointer(prefix);
                String identity = identitiesByPointer.get(pointer);
                if (identity == null) {
                    Node selectedNode = resolveScopeNode(
                            root, pointer, lookup);
                    identity = exactIdentity(
                            selectedNode, exactNodeIndex);
                    identitiesByPointer.put(pointer, identity);
                }
                identities.add(identity);
            }
            if (!identities.get(
                    identities.size() - 1)
                    .equals(occurrence.scopeBlueId())) {
                throw invalid(
                        "Subscription occurrence scope identity is stale at "
                                + scopePath + ": current="
                                + identities.get(identities.size() - 1)
                                + ", projected="
                                + occurrence.scopeBlueId());
            }
            result.put(
                    scopePath,
                    Collections.unmodifiableList(
                            identities));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Node resolveScopeNode(
            Node root,
            String pointer,
            ExactLookup lookup) {
        final Object selectedNode;
        try {
            selectedNode = NodePath.get(
                    root,
                    pointer,
                    new Function<Node, Node>() {
                        @Override
                        public Node apply(Node reference) {
                            return reference != null
                                    && reference.isReferenceOnly()
                                    ? lookup.require(
                                    reference.getBlueId())
                                    : reference;
                        }
                    });
        } catch (RuntimeException unavailable) {
            if (unavailable
                    instanceof ExecutionEvidenceUnavailableException) {
                throw unavailable;
            }
            throw invalid(
                    "Unable to resolve selected scope chain "
                            + pointer + ": "
                            + deterministicMessage(unavailable));
        }
        if (!(selectedNode instanceof Node)) {
            throw invalid(
                    "Selected scope chain is not structural at "
                            + pointer);
        }
        return (Node) selectedNode;
    }

    private static ResourceClosure resourceClosure(
            String rootBlueId,
            String eventBlueId,
            Collection<CoordinationSubscriptionOccurrence>
                    selected,
            List<CoordinationDeliveryDiagnostic> diagnostics,
            Map<String, List<String>> scopeChains,
            CoordinationHostQuotaSession hostQuotas) {
        LinkedHashSet<String> required =
                new LinkedHashSet<>();
        LinkedHashSet<String> sourceHeaders =
                new LinkedHashSet<>();
        LinkedHashSet<String> targetHeaders =
                new LinkedHashSet<>();
        LinkedHashSet<String> targetSelectors =
                new LinkedHashSet<>();
        TreeSet<String> prefetch = new TreeSet<>(
                ExternalOrderKey::compareTextCodePoints);
        required.add(rootBlueId);
        required.add(eventBlueId);
        for (List<String> chain : scopeChains.values()) {
            required.addAll(chain);
        }
        for (CoordinationSubscriptionOccurrence occurrence
                : selected) {
            required.add(occurrence.scopeBlueId());
            required.addAll(
                    occurrence
                            .sourceContributionNodeBlueIds());
            sourceHeaders.add(
                    occurrence.headerIdentityBlueId());
            admitPrefetch(
                    prefetch,
                    occurrence
                            .sourceContributionNodeBlueIds(),
                    rootBlueId,
                    eventBlueId,
                    hostQuotas);
        }
        for (CoordinationDeliveryDiagnostic diagnostic
                : diagnostics) {
            if (diagnostic.targetHeaderBlueId() != null) {
                required.addAll(
                        diagnostic
                                .targetContributionBlueIds());
                targetHeaders.add(
                        diagnostic.targetHeaderBlueId());
                targetSelectors.add(
                        CoordinationSemanticDemandBoundary
                                .selector(
                                        diagnostic.scopePath(),
                                        diagnostic
                                                .targetChannelKey()));
                admitPrefetch(
                        prefetch,
                        diagnostic
                                .targetContributionBlueIds(),
                        rootBlueId,
                        eventBlueId,
                        hostQuotas);
            }
        }
        return new ResourceClosure(
                required,
                sourceHeaders,
                targetHeaders,
                targetSelectors,
                prefetch);
    }

    private static void admitPrefetch(
            TreeSet<String> prefetch,
            Collection<String> identities,
            String rootBlueId,
            String eventBlueId,
            CoordinationHostQuotaSession hostQuotas) {
        for (String identity : identities) {
            if (rootBlueId.equals(identity)
                    || eventBlueId.equals(identity)
                    || prefetch.contains(identity)) {
                continue;
            }
            hostQuotas.recordPrefetchIdentity(
                    prefetch.size());
            prefetch.add(identity);
        }
    }

    private static String exactIdentity(
            Node supplied,
            CoordinationExactNodeIndex exactNodeIndex) {
        if (supplied.isReferenceOnly()) {
            return supplied.getBlueId();
        }
        String declared = supplied.getBlueId();
        if (declared == null) {
            return exactNodeIndex.blueId(supplied);
        }
        Node canonical = supplied.clone().blueId(null);
        String calculated =
                DirectBlueIdCalculator.calculateBlueId(canonical);
        if (!declared.equals(calculated)) {
            throw invalid(
                    "Exact content carries mismatched root BlueId "
                            + declared);
        }
        return calculated;
    }

    private static String deterministicMessage(
            RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private static String requireText(
            String value,
            String label) {
        if (value == null || value.isEmpty()) {
            throw invalid(
                    label + " must be non-empty");
        }
        return value;
    }

    private static InvalidExecutionEvidenceException invalid(
            String message) {
        return new InvalidExecutionEvidenceException(message);
    }

    private static final class ExactLookup {
        private final NodeProvider provider;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();

        private ExactLookup(NodeProvider provider) {
            this.provider = provider;
        }

        private static ExactLookup admitted(
                NodeProvider provider,
                String rootBlueId,
                Node root,
                String eventBlueId,
                Node event) {
            ExactLookup result = new ExactLookup(provider);
            result.cache.put(rootBlueId, root);
            result.cache.put(eventBlueId, event);
            return result;
        }

        private synchronized Node require(String blueId) {
            Node cached = cache.get(blueId);
            if (cached != null) {
                /* ExactLookup is invocation-local. All consumers traverse
                 * retained nodes read-only, while the Contracts boundary
                 * takes its own defensive semantic-input snapshots. */
                return cached;
            }
            NodeProviderResult result =
                    Objects.requireNonNull(
                            provider.fetchResultByBlueId(
                                    blueId),
                            "provider result");
            if (result.outcome()
                    == NodeProviderOutcome.NOT_FOUND
                    || result.outcome()
                    == NodeProviderOutcome.UNAVAILABLE) {
                throw new ExecutionEvidenceUnavailableException(
                        "Exact provider content is unavailable for "
                                + blueId,
                        Collections.singleton(blueId));
            }
            if (result.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                throw invalid(
                        "Exact provider reported invalid evidence for "
                                + blueId
                                + diagnostic(result));
            }
            List<Node> candidates = result.nodes();
            if (candidates.size() != 1) {
                throw invalid(
                        "Exact Root/event lookup must return exactly one "
                                + "node for " + blueId);
            }
            Node supplied = candidates.get(0);
            if (supplied.isReferenceOnly()) {
                throw invalid(
                        "Exact provider returned a pure reference for "
                                + blueId);
            }
            Node canonical = supplied.clone();
            String declared = canonical.getBlueId();
            if (declared != null) {
                if (!blueId.equals(declared)) {
                    throw invalid(
                            "Provider content root BlueId metadata "
                                    + declared
                                    + " disagrees with requested "
                                    + blueId);
                }
                canonical.blueId(null);
            }
            final String calculated;
            try {
                calculated =
                        DirectBlueIdCalculator.calculateBlueId(
                                canonical);
            } catch (RuntimeException invalidContent) {
                throw invalid(
                        "Provider content is not exact canonical BlueId "
                                + "input for " + blueId + ": "
                                + deterministicMessage(
                                invalidContent));
            }
            if (!blueId.equals(calculated)) {
                throw invalid(
                        "Provider returned content with BlueId "
                                + calculated
                                + " for requested " + blueId);
            }
            cache.put(blueId, canonical);
            return canonical;
        }

        private static String diagnostic(
                NodeProviderResult result) {
            return result.diagnostic().isPresent()
                    ? ": " + result.diagnostic().get()
                    : "";
        }
    }

    private static final class CandidateMapping {
        private final List<String> publicKeys;

        private CandidateMapping(List<String> publicKeys) {
            this.publicKeys =
                    Collections.unmodifiableList(
                            new ArrayList<>(publicKeys));
        }
    }

    private static final class ResourceClosure {
        private final Set<String> requiredSeeds;
        private final Set<String> sourceHeaders;
        private final Set<String> targetHeaders;
        private final Set<String> targetSelectors;
        private final List<String> prefetch;

        private ResourceClosure(
                Collection<String> requiredSeeds,
                Collection<String> sourceHeaders,
                Collection<String> targetHeaders,
                Collection<String> targetSelectors,
                Collection<String> prefetch) {
            this.requiredSeeds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    requiredSeeds));
            this.sourceHeaders =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    sourceHeaders));
            this.targetHeaders =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    targetHeaders));
            this.targetSelectors =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    targetSelectors));
            this.prefetch =
                    Collections.unmodifiableList(
                            new ArrayList<>(prefetch));
        }
    }
}
