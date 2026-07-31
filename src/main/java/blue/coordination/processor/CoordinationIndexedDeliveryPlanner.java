package blue.coordination.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.CoordinationIndexedDeliveryEngine;
import blue.language.processor.CoordinationSubscriptionProjectionBridge;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodePathAccessor;

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

    /**
     * Creates a planner bound to a configured Coordination processor.
     *
     * @param processor configured Language/Contracts processor
     */
    CoordinationIndexedDeliveryPlanner(
            blue.language.processor.DocumentProcessor processor) {
        this.processor =
                Objects.requireNonNull(
                        processor, "processor");
        this.engine = new CoordinationIndexedDeliveryEngine(
                this.processor);
        this.subscriptionProjectionBridge =
                new CoordinationSubscriptionProjectionBridge(
                        this.processor);
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
        CoordinationSubscriptionSnapshot snapshot =
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

        CandidateMapping candidates =
                candidates(
                        snapshot,
                        indexedCandidateOccurrenceKeys,
                        quotas);
        List<SubscriptionDelta.Entry> activeIntervals =
                new ArrayList<>(
                        snapshot.occurrences().size());
        Map<String, CoordinationSubscriptionOccurrence>
                occurrenceByLanguageKey =
                new LinkedHashMap<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            SubscriptionDelta.Entry interval =
                    occurrence
                            .toSubscriptionDeltaEntry();
            activeIntervals.add(interval);
            String languageKey =
                    CoordinationIndexedDeliveryEngine
                            .languageOccurrenceKey(
                                    occurrence.scopePath(),
                                    occurrence.channelKey());
            if (occurrenceByLanguageKey.put(
                    languageKey, occurrence) != null) {
                throw invalid(
                        "Subscription snapshot maps two public occurrences "
                                + "to one Language occurrence");
            }
        }

        CoordinationIndexedDeliveryEngine.Prepared prepared =
                engine.prepare(
                        root,
                        event,
                        exactProvider,
                        rootRevision,
                        eventOrderKey,
                        activeIntervals,
                        candidates.languageKeys);
        List<String> publicOrder = new ArrayList<>();
        Map<String, CoordinationSubscriptionOccurrence>
                selectedOccurrences =
                new LinkedHashMap<>();
        for (String languageKey
                : prepared.occurrenceOrder()) {
            CoordinationSubscriptionOccurrence occurrence =
                    occurrenceByLanguageKey.get(languageKey);
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
        Map<String, List<String>> scopeChains =
                selectedScopeChains(
                        root,
                        selectedOccurrences.values(),
                        lookup);
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

    private static List<CoordinationDeliveryDiagnostic>
    publicDiagnostics(
            List<CoordinationDeliveryDiagnostic> diagnostics,
            List<String> publicOrder) {
        List<CoordinationDeliveryDiagnostic> result =
                new ArrayList<>(diagnostics.size());
        for (int index = 0;
                index < diagnostics.size();
                index++) {
            result.add(
                    diagnostics.get(index)
                            .withOccurrenceKey(
                                    publicOrder.get(index)));
        }
        return Collections.unmodifiableList(result);
    }

    private CoordinationSubscriptionSnapshot requireSnapshot(
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
        /*
         * Round-tripping re-runs the canonical digest and exact dependency
         * codec. A caller cannot hand us a subclass or a mutable map view.
         */
        CoordinationSubscriptionSnapshot verified;
        try {
            verified =
                    CoordinationSubscriptionSnapshot
                            .rehydrate(snapshot.toMap());
        } catch (RuntimeException invalidSnapshot) {
            throw invalid(
                    "Subscription snapshot identity is invalid: "
                            + deterministicMessage(
                            invalidSnapshot));
        }
        if (!CoordinationSubscriptionSnapshot.VERSION.equals(
                verified.projectionVersion())
                || !CoordinationSubscriptionSnapshot
                .ALGORITHM_IDENTITY.equals(
                        verified.algorithmIdentity())
                || !CoordinationRuntimeRegistrations
                .identity(processor).equals(
                        verified
                                .coordinationRuntimeRegistryIdentity())) {
            throw invalid(
                    "Subscription snapshot runtime or projection "
                            + "identity mismatch");
        }
        if (!subscriptionProjectionBridge
                .languageRuntimeRegistryIdentity()
                .equals(
                verified.languageRuntimeRegistryIdentity())) {
            throw invalid(
                    "Subscription snapshot Language runtime registry "
                            + "identity mismatch");
        }
        if (!rootBlueId.equals(verified.rootBlueId())) {
            throw invalid(
                    "Subscription snapshot Root identity mismatch");
        }
        if (rootRevision != verified.rootRevision()) {
            throw invalid(
                    "Subscription snapshot Root revision mismatch");
        }
        if (order.compareTo(
                verified.activationFrontier()) <= 0) {
            throw invalid(
                    "Event order is not after the active subscription "
                            + "snapshot frontier");
        }
        for (CoordinationSubscriptionOccurrence occurrence
                : verified.occurrences()) {
            if (occurrence.activationRootRevision() == null
                    || occurrence.activationRootRevision()
                    > rootRevision
                    || occurrence.endAtRootRevision() != null) {
                throw invalid(
                        "Subscription snapshot contains a stale occurrence: "
                                + occurrence.occurrenceKey());
            }
        }
        return verified;
    }

    private static CandidateMapping candidates(
            CoordinationSubscriptionSnapshot snapshot,
            Collection<String> supplied,
            CoordinationHostQuotaSession hostQuotas) {
        Objects.requireNonNull(
                supplied,
                "indexedCandidateOccurrenceKeys");
        List<String> publicKeys = new ArrayList<>(
                supplied.size());
        List<String> languageKeys = new ArrayList<>(
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
                    snapshot.occurrence(exact);
            if (occurrence == null) {
                throw invalid(
                        "Indexed candidate is absent or stale in the active "
                                + "snapshot: " + exact);
            }
            publicKeys.add(exact);
            languageKeys.add(
                    CoordinationIndexedDeliveryEngine
                            .languageOccurrenceKey(
                                    occurrence.scopePath(),
                                    occurrence.channelKey()));
        }
        return new CandidateMapping(
                publicKeys, languageKeys);
    }

    private static void verifyDiagnostics(
            List<CoordinationDeliveryDiagnostic> diagnostics,
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
            CoordinationDeliveryDiagnostic diagnostic =
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
            Node root,
            Collection<CoordinationSubscriptionOccurrence>
                    selected,
            ExactLookup lookup) {
        Map<String, List<String>> result =
                new LinkedHashMap<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : selected) {
            String scopePath = occurrence.scopePath();
            if (result.containsKey(scopePath)) {
                continue;
            }
            List<String> identities = new ArrayList<>();
            identities.add(
                    BlueIdCalculator.calculateBlueId(root));
            List<String> segments =
                    JsonPointer.split(scopePath);
            List<String> prefix = new ArrayList<>();
            for (String segment : segments) {
                prefix.add(segment);
                String pointer =
                        JsonPointer.toPointer(prefix);
                Object selectedNode;
                try {
                    selectedNode =
                            NodePathAccessor.get(
                                    root,
                                    pointer,
                                    new Function<Node, Node>() {
                                        @Override
                                        public Node apply(Node reference) {
                                            return reference != null
                                                    && reference
                                                    .isReferenceOnly()
                                                    ? lookup.require(
                                                    reference
                                                            .getBlueId())
                                                    : reference;
                                        }
                                    });
                } catch (RuntimeException unavailable) {
                    if (unavailable
                            instanceof
                            ExecutionEvidenceUnavailableException) {
                        throw unavailable;
                    }
                    throw invalid(
                            "Unable to resolve selected scope chain "
                                    + pointer + ": "
                                    + deterministicMessage(
                                    unavailable));
                }
                if (!(selectedNode instanceof Node)) {
                    throw invalid(
                            "Selected scope chain is not structural at "
                                    + pointer);
                }
                identities.add(exactIdentity(
                        (Node) selectedNode));
            }
            if (!identities.get(
                    identities.size() - 1)
                    .equals(occurrence.scopeBlueId())) {
                throw invalid(
                        "Subscription occurrence scope identity is stale at "
                                + scopePath);
            }
            result.put(
                    scopePath,
                    Collections.unmodifiableList(
                            identities));
        }
        return Collections.unmodifiableMap(result);
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

    private static String exactIdentity(Node supplied) {
        if (supplied.isReferenceOnly()) {
            return supplied.getBlueId();
        }
        Node canonical = supplied.clone();
        String declared = canonical.getBlueId();
        if (declared != null) {
            canonical.blueId(null);
        }
        String calculated =
                BlueIdCalculator.calculateBlueId(canonical);
        if (declared != null
                && !declared.equals(calculated)) {
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

        private synchronized Node require(String blueId) {
            Node cached = cache.get(blueId);
            if (cached != null) {
                return cached.clone();
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
                        BlueIdCalculator.calculateBlueId(
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
            cache.put(blueId, canonical.clone());
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
        private final List<String> languageKeys;

        private CandidateMapping(
                List<String> publicKeys,
                List<String> languageKeys) {
            this.publicKeys =
                    Collections.unmodifiableList(
                            new ArrayList<>(publicKeys));
            this.languageKeys =
                    Collections.unmodifiableList(
                            new ArrayList<>(languageKeys));
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
