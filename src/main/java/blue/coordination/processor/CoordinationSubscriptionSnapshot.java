package blue.coordination.processor;

import blue.coordination.fastpath.FastPathWorkMetrics;
import blue.coordination.fastpath.PathDependencyIndex;
import blue.coordination.processor.delivery.CoordinationIndexedDeliveryEngine;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable, identity-bearing Coordination external-subscription projection.
 *
 * <p>The snapshot is a scalar/list/map value that can be persisted without a
 * host-specific class. {@link #toMap()} and {@link #rehydrate(Map)} preserve
 * exact Language dependency evidence and reject identity drift. No executable
 * Channel or Handler body is retained.</p>
 */
public final class CoordinationSubscriptionSnapshot {
    /** Stable public schema/projection version. */
    public static final String VERSION =
            "blue.coordination/subscription-snapshot/3.0";

    /** Identity of the exact deterministic projection algorithm. */
    public static final String ALGORITHM_IDENTITY =
            identity(
                    "blue.coordination/"
                            + "subscription-projection-algorithm/3.0",
                    Collections.singletonList(
                            TimelineSubscriptionProjection.VERSION));

    private final String projectionVersion;
    private final String languageRuntimeRegistryIdentity;
    private final String coordinationRuntimeRegistryIdentity;
    private final String algorithmIdentity;
    private final String rootBlueId;
    private final long rootRevision;
    private final ExternalOrderKey activationFrontier;
    private final List<CoordinationSubscriptionOccurrence>
            occurrences;
    private final PathDependencyIndex dependencyIndex;
    private final CoordinationSubscriptionMerkleIndex merkleIndex;
    private final CoordinationIndexedDeliveryEngine.IndexedActiveSurface
            indexedActiveSurface;
    private final Map<String, List<String>>
            processEmbeddedRoutes;
    private final Set<String> prunedScopePaths;
    private final String digest;
    private final FastPathWorkMetrics serializationMetrics;
    private final PlanningVerification planningVerification;
    private final long constructionOccurrenceValidationCount;
    private final AtomicLong trustedPlanningVerificationCount =
            new AtomicLong();
    private final AtomicLong exactOccurrenceLookupCount =
            new AtomicLong();
    private final AtomicLong candidateScopeLookupCount =
            new AtomicLong();

    CoordinationSubscriptionSnapshot(
            String languageRuntimeRegistryIdentity,
            String coordinationRuntimeRegistryIdentity,
            String rootBlueId,
            long rootRevision,
            ExternalOrderKey activationFrontier,
            List<CoordinationSubscriptionOccurrence>
                    occurrences,
            Map<String, List<String>> processEmbeddedRoutes,
            Set<String> prunedScopePaths) {
        this(
                VERSION,
                languageRuntimeRegistryIdentity,
                coordinationRuntimeRegistryIdentity,
                ALGORITHM_IDENTITY,
                rootBlueId,
                rootRevision,
                activationFrontier,
                occurrences,
                processEmbeddedRoutes,
                prunedScopePaths,
                null,
                null,
                null,
                null);
    }

    CoordinationSubscriptionSnapshot(
            String languageRuntimeRegistryIdentity,
            String coordinationRuntimeRegistryIdentity,
            String rootBlueId,
            long rootRevision,
            ExternalOrderKey activationFrontier,
            List<CoordinationSubscriptionOccurrence> occurrences,
            Map<String, List<String>> processEmbeddedRoutes,
            Set<String> prunedScopePaths,
            PathDependencyIndex dependencyIndex,
            CoordinationSubscriptionMerkleIndex merkleIndex,
            FastPathWorkMetrics metrics) {
        this(
                VERSION,
                languageRuntimeRegistryIdentity,
                coordinationRuntimeRegistryIdentity,
                ALGORITHM_IDENTITY,
                rootBlueId,
                rootRevision,
                activationFrontier,
                occurrences,
                processEmbeddedRoutes,
                prunedScopePaths,
                null,
                dependencyIndex,
                merkleIndex,
                metrics);
    }

    private CoordinationSubscriptionSnapshot(
            String projectionVersion,
            String languageRuntimeRegistryIdentity,
            String coordinationRuntimeRegistryIdentity,
            String algorithmIdentity,
            String rootBlueId,
            long rootRevision,
            ExternalOrderKey activationFrontier,
            List<CoordinationSubscriptionOccurrence>
                    occurrences,
            Map<String, List<String>> processEmbeddedRoutes,
            Set<String> prunedScopePaths,
            String suppliedDigest,
            PathDependencyIndex suppliedDependencyIndex,
            CoordinationSubscriptionMerkleIndex suppliedMerkleIndex,
            FastPathWorkMetrics metrics) {
        this.projectionVersion =
                requireText(
                        projectionVersion,
                        "projectionVersion");
        this.languageRuntimeRegistryIdentity =
                requireText(
                        languageRuntimeRegistryIdentity,
                        "languageRuntimeRegistryIdentity");
        this.coordinationRuntimeRegistryIdentity =
                requireText(
                        coordinationRuntimeRegistryIdentity,
                        "coordinationRuntimeRegistryIdentity");
        this.algorithmIdentity =
                requireText(
                        algorithmIdentity,
                        "algorithmIdentity");
        this.rootBlueId =
                requireText(rootBlueId, "rootBlueId");
        if (rootRevision < 0L) {
            throw new IllegalArgumentException(
                    "rootRevision must be non-negative");
        }
        this.rootRevision = rootRevision;
        this.activationFrontier =
                Objects.requireNonNull(
                        activationFrontier,
                        "activationFrontier");
        List<CoordinationSubscriptionOccurrence> suppliedOccurrences =
                Objects.requireNonNull(occurrences, "occurrences");
        CoordinationSubscriptionMerkleIndex exactMerkleIndex =
                suppliedMerkleIndex;
        long validatedOccurrences = 0L;
        if (exactMerkleIndex == null) {
            exactMerkleIndex = CoordinationSubscriptionMerkleIndex.empty();
            for (CoordinationSubscriptionOccurrence occurrence
                    : suppliedOccurrences) {
                validatedOccurrences++;
                CoordinationSubscriptionOccurrence exact =
                        requireActiveOccurrence(
                                occurrence,
                                rootRevision,
                                this.activationFrontier);
                exactMerkleIndex = exactMerkleIndex.updated(null, exact);
            }
        } else if (!suppliedOccurrences.isEmpty()) {
            throw new IllegalArgumentException(
                    "A persistent successor must not also supply a full "
                            + "occurrence list");
        }
        this.merkleIndex = exactMerkleIndex;
        this.occurrences = exactMerkleIndex.occurrences();
        if (this.merkleIndex.size() != this.occurrences.size()) {
            throw new IllegalArgumentException(
                    "Subscription Merkle index size does not match snapshot");
        }
        this.dependencyIndex = suppliedDependencyIndex != null
                ? suppliedDependencyIndex
                : dependencyIndex(this.occurrences);
        this.indexedActiveSurface =
                CoordinationIndexedDeliveryEngine.IndexedActiveSurface
                        .from(this.occurrences);
        this.constructionOccurrenceValidationCount =
                validatedOccurrences;
        this.processEmbeddedRoutes =
                immutableRoutes(processEmbeddedRoutes);
        this.prunedScopePaths =
                immutablePaths(prunedScopePaths);
        this.digest = merkleDigest();
        this.serializationMetrics = metrics;
        if (suppliedDigest != null
                && !this.digest.equals(
                        suppliedDigest)) {
            throw new IllegalArgumentException(
                    "Persisted subscription snapshot digest "
                            + "does not match its content");
        }
        this.planningVerification = new PlanningVerification(
                this,
                identity(
                        "blue.coordination/"
                                + "trusted-subscription-planning/1.0",
                        Arrays.asList(
                                this.projectionVersion,
                                this.algorithmIdentity,
                                this.languageRuntimeRegistryIdentity,
                                this.coordinationRuntimeRegistryIdentity,
                                this.rootBlueId,
                                Long.toString(this.rootRevision),
                                this.digest)));
    }

    /** @return stable public projection schema version */
    public String projectionVersion() {
        return projectionVersion;
    }

    /** @return exact configured Language/Contracts runtime identity */
    public String languageRuntimeRegistryIdentity() {
        return languageRuntimeRegistryIdentity;
    }

    /** @return exact Coordination runtime registration identity */
    public String coordinationRuntimeRegistryIdentity() {
        return coordinationRuntimeRegistryIdentity;
    }

    /** @return exact subscription projection algorithm identity */
    public String algorithmIdentity() {
        return algorithmIdentity;
    }

    /** @return observation Root BlueId */
    public String rootBlueId() {
        return rootBlueId;
    }

    /** @return host-supplied observation Root revision */
    public long rootRevision() {
        return rootRevision;
    }

    /**
     * Returns the order frontier associated with this observed Root
     * revision.
     *
     * @return immutable host-supplied activation/transition frontier
     */
    public ExternalOrderKey activationFrontier() {
        return activationFrontier;
    }

    /** @return canonically ordered active occurrence values */
    public List<CoordinationSubscriptionOccurrence> occurrences() {
        return occurrences;
    }

    /**
     * Looks up an active occurrence by its stable public key.
     *
     * @param occurrenceKey stable public occurrence key
     * @return occurrence, or {@code null} when absent
     */
    public CoordinationSubscriptionOccurrence occurrence(
            String occurrenceKey) {
        return merkleIndex.occurrence(occurrenceKey);
    }

    /**
     * Returns exact affected public keys from the immutable admitted dependency
     * trie without iterating the active occurrence list.
     */
    public Set<String> affectedOccurrenceKeys(
            Set<String> changedPaths) {
        return dependencyIndex.affected(
                Objects.requireNonNull(changedPaths, "changedPaths"));
    }

    /** Number of exact occurrence-to-path bindings retained by the trie. */
    public int dependencyPathBindingCount() {
        return dependencyIndex.pathCount();
    }

    CoordinationSubscriptionOccurrence occurrenceByInternalKey(
            String scopePath,
            String channelKey) {
        return merkleIndex.occurrenceByInternalKey(
                scopePath, channelKey);
    }

    PathDependencyIndex dependencyIndexForSuccessor() {
        return dependencyIndex;
    }

    CoordinationSubscriptionMerkleIndex merkleIndexForSuccessor() {
        return merkleIndex;
    }

    /** Constant-time candidate validation used by sparse indexed planning. */
    public CoordinationSubscriptionOccurrence candidateOccurrence(
            String occurrenceKey) {
        String checked = Objects.requireNonNull(
                occurrenceKey, "occurrenceKey");
        candidateScopeLookupCount.incrementAndGet();
        return merkleIndex.occurrence(checked);
    }

    /**
     * Returns directly pruned participating scopes retained for incremental
     * reachability validation.
     *
     * @return immutable canonical scope paths
     */
    public Set<String> prunedScopePaths() {
        return prunedScopePaths;
    }

    /** @return stable Blue identity of this complete snapshot */
    public String digest() {
        return digest;
    }

    /**
     * Verifies and returns the immutable planning proof bound to the expected
     * runtime and exact Root generation.
     *
     * <p>Instances can only be created by the package projection constructor,
     * which calculates the canonical digest, or by {@link #rehydrate(Map)},
     * which additionally checks the persisted digest. All retained
     * collections are immutable and this class is final, so active occurrence
     * and revision invariants are checked once during construction. This
     * method performs only constant-time binding checks and returns a proof
     * that owns the prevalidated exact occurrence indexes.</p>
     */
    PlanningVerification verifiedForInProcessPlanning(
            String expectedLanguageRuntimeIdentity,
            String expectedCoordinationRuntimeIdentity,
            String expectedRootBlueId,
            long expectedRootRevision) {
        if (!VERSION.equals(projectionVersion)
                || !ALGORITHM_IDENTITY.equals(algorithmIdentity)
                || !coordinationRuntimeRegistryIdentity.equals(
                expectedCoordinationRuntimeIdentity)) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription snapshot runtime or projection "
                            + "identity mismatch");
        }
        if (!languageRuntimeRegistryIdentity.equals(
                expectedLanguageRuntimeIdentity)) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription snapshot Language runtime registry "
                            + "identity mismatch");
        }
        if (!rootBlueId.equals(expectedRootBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription snapshot Root identity mismatch");
        }
        if (rootRevision != expectedRootRevision) {
            throw new InvalidExecutionEvidenceException(
                    "Subscription snapshot Root revision mismatch");
        }
        trustedPlanningVerificationCount.incrementAndGet();
        return planningVerification;
    }

    /** Returns live work evidence for trusted verification and exact lookups. */
    public PlanningMetrics planningMetrics() {
        return new PlanningMetrics(
                constructionOccurrenceValidationCount,
                trustedPlanningVerificationCount.get(),
                exactOccurrenceLookupCount.get(),
                candidateScopeLookupCount.get());
    }

    /**
     * Returns the process-independent proof identity binding this immutable
     * snapshot to its projection, runtimes, and exact Root generation.
     *
     * @return stable direct Blue identity of the trusted planning binding
     */
    public String planningBindingIdentity() {
        return planningVerification.bindingIdentity();
    }

    /**
     * Serializes the snapshot to application-independent scalar/list/map
     * values.
     *
     * @return immutable canonical persistence map including the digest
     */
    public Map<String, Object> toMap() {
        if (serializationMetrics != null) {
            serializationMetrics.snapshotSerialized(occurrences.size());
        }
        Map<String, Object> result =
                new LinkedHashMap<String, Object>(
                        toCanonicalMap());
        result.put("digest", digest);
        return CoordinationSubscriptionSerialization
                .immutableMap(result);
    }

    /**
     * Rehydrates and verifies a canonical persisted snapshot.
     *
     * @param persisted scalar/list/map representation from {@link #toMap()}
     * @return exact immutable snapshot
     */
    public static CoordinationSubscriptionSnapshot rehydrate(
            Map<String, ?> persisted) {
        Objects.requireNonNull(persisted, "persisted");
        CoordinationSubscriptionSerialization.requireFields(
                persisted,
                "snapshot",
                new String[] {
                        "projectionVersion",
                        "languageRuntimeRegistryIdentity",
                        "coordinationRuntimeRegistryIdentity",
                        "algorithmIdentity",
                        "rootBlueId",
                        "rootRevision",
                        "activationFrontier",
                        "occurrences",
                        "processEmbeddedRoutes",
                        "prunedScopePaths",
                        "digest"
                });
        String projectionVersion =
                CoordinationSubscriptionSerialization
                        .text(
                                persisted,
                                "projectionVersion");
        if (!VERSION.equals(projectionVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported Coordination projection version: "
                            + projectionVersion);
        }
        String algorithmIdentity =
                CoordinationSubscriptionSerialization
                        .text(
                                persisted,
                                "algorithmIdentity");
        if (!ALGORITHM_IDENTITY.equals(
                algorithmIdentity)) {
            throw new IllegalArgumentException(
                    "Coordination subscription projection "
                            + "algorithm identity does not match "
                            + "this library");
        }
        List<CoordinationSubscriptionOccurrence> occurrences =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>();
        for (Map<String, ?> encoded
                : CoordinationSubscriptionSerialization
                .mapList(persisted, "occurrences")) {
            occurrences.add(
                    CoordinationSubscriptionOccurrence
                            .fromCanonicalMap(encoded));
        }
        Map<String, List<String>> routes =
                new LinkedHashMap<String, List<String>>();
        Map<String, ?> encodedRoutes =
                CoordinationSubscriptionSerialization.map(
                        persisted,
                        "processEmbeddedRoutes");
        for (Map.Entry<String, ?> route
                : encodedRoutes.entrySet()) {
            Map<String, Object> wrapper =
                    new LinkedHashMap<String, Object>();
            wrapper.put("values", route.getValue());
            routes.put(
                    route.getKey(),
                    CoordinationSubscriptionSerialization
                                    .textList(wrapper, "values"));
        }
        List<String> encodedPrunedScopePaths =
                CoordinationSubscriptionSerialization
                        .textList(
                                persisted,
                                "prunedScopePaths");
        CoordinationSubscriptionSnapshot snapshot =
                new CoordinationSubscriptionSnapshot(
                        projectionVersion,
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "languageRuntimeRegistryIdentity"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "coordinationRuntimeRegistryIdentity"),
                        algorithmIdentity,
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "rootBlueId"),
                        CoordinationSubscriptionSerialization
                                .requiredLong(
                                        persisted,
                                        "rootRevision"),
                        Objects.requireNonNull(
                                CoordinationSubscriptionSerialization
                                        .optionalOrderKey(
                                                persisted,
                                                "activationFrontier"),
                                "activationFrontier"),
                        occurrences,
                        routes,
                        new LinkedHashSet<String>(
                                encodedPrunedScopePaths),
                        CoordinationSubscriptionSerialization
                                .text(persisted, "digest"),
                        null,
                        null,
                        null);
        snapshot.requireCurrentFormat();
        if (!snapshot.occurrences().equals(
                occurrences)) {
            throw new IllegalArgumentException(
                    "Persisted subscription occurrences are "
                            + "not canonically ordered");
        }
        if (!new ArrayList<String>(
                snapshot.prunedScopePaths()).equals(
                        encodedPrunedScopePaths)) {
            throw new IllegalArgumentException(
                    "Persisted pruned scope paths are not "
                            + "unique and canonically ordered");
        }
        return snapshot;
    }

    Map<String, List<String>> processEmbeddedRoutes() {
        return processEmbeddedRoutes;
    }

    static Set<String> exactDependencyPaths(
            CoordinationSubscriptionOccurrence occurrence) {
        CoordinationSubscriptionOccurrence exact = Objects.requireNonNull(
                occurrence, "occurrence");
        LinkedHashSet<String> paths = new LinkedHashSet<String>();
        paths.add(exact.scopePath());
        ExternalChannelDependencySnapshot dependencies =
                exact.dependencyEvidence();
        LinkedHashSet<String> exactContractKeys =
                new LinkedHashSet<String>();
        exactContractKeys.add(exact.channelKey());
        for (ExternalChannelDependencySnapshot.Entry entry
                : dependencies.entries()) {
            exactContractKeys.add(entry.channelKey());
        }
        for (ExternalChannelDependencySnapshot.ChannelEntry entry
                : dependencies.channelEntries()) {
            exactContractKeys.add(entry.channelKey());
        }
        boolean wholeContractSurface =
                dependencies.wholeSameScopeExternalSurface()
                        || dependencies.wholeSameScopeChannelCatalog()
                        || !dependencies.typeFamilies().isEmpty();
        for (ExternalChannelDependencySnapshot.TypeFamily family
                : dependencies.typeFamilies()) {
            for (ExternalChannelDependencySnapshot.Member member
                    : family.members()) {
                exactContractKeys.add(member.channelKey());
            }
        }
        String scope = exact.scopePath();
        while (true) {
            String contracts = JsonPointer.append(scope, "$contracts");
            if (wholeContractSurface) paths.add(contracts);
            for (String contractKey : exactContractKeys) {
                paths.add(JsonPointer.append(contracts, contractKey));
            }
            if ("/".equals(scope)) break;
            int slash = scope.lastIndexOf('/');
            scope = slash <= 0 ? "/" : scope.substring(0, slash);
        }
        return Collections.unmodifiableSet(paths);
    }

    private static PathDependencyIndex dependencyIndex(
            List<CoordinationSubscriptionOccurrence> occurrences) {
        PathDependencyIndex result = PathDependencyIndex.empty();
        for (CoordinationSubscriptionOccurrence occurrence : occurrences) {
            result = result.updated(
                    occurrence.occurrenceKey(),
                    Collections.<String>emptySet(),
                    exactDependencyPaths(occurrence));
        }
        return result;
    }

    private static CoordinationSubscriptionOccurrence requireActiveOccurrence(
            CoordinationSubscriptionOccurrence occurrence,
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        CoordinationSubscriptionOccurrence exact = Objects.requireNonNull(
                occurrence, "subscription occurrence");
        if (exact.endAtRootRevision() != null) {
            throw new IllegalArgumentException(
                    "Snapshot contains a retired occurrence: "
                            + exact.occurrenceKey());
        }
        if (exact.activationRootRevision() == null
                || exact.activationRootRevision().longValue() > rootRevision
                || exact.activationFrontier() == null
                || exact.activationFrontier().compareTo(
                        activationFrontier) > 0) {
            throw new IllegalArgumentException(
                    "Snapshot contains an inactive or stale occurrence: "
                            + exact.occurrenceKey());
        }
        return exact;
    }

    /**
     * Calculates the version-3 identity from bounded scalar commitments.
     * Occurrence content is represented by the persistent treap root, so a
     * successor snapshot does not serialize or hash every active occurrence.
     */
    private String merkleDigest() {
        Map<String, Object> encodedRoutes =
                new LinkedHashMap<String, Object>();
        encodedRoutes.putAll(processEmbeddedRoutes);
        Map<String, Object> encodedPruned =
                new LinkedHashMap<String, Object>();
        encodedPruned.put(
                "paths",
                new ArrayList<String>(prunedScopePaths));

        Map<String, Object> commitment =
                new LinkedHashMap<String, Object>();
        commitment.put(
                "kind",
                "blue.coordination/subscription-snapshot-merkle/1.0");
        commitment.put("projectionVersion", projectionVersion);
        commitment.put(
                "languageRuntimeRegistryIdentity",
                languageRuntimeRegistryIdentity);
        commitment.put(
                "coordinationRuntimeRegistryIdentity",
                coordinationRuntimeRegistryIdentity);
        commitment.put("algorithmIdentity", algorithmIdentity);
        commitment.put("rootBlueId", rootBlueId);
        commitment.put("rootRevision", rootRevision);
        commitment.put(
                "activationFrontier",
                CoordinationSubscriptionSerialization.orderKeyToList(
                        activationFrontier));
        commitment.put("occurrenceCount", merkleIndex.size());
        commitment.put("occurrences", merkleIndex.digest());
        commitment.put(
                "processEmbeddedRoutes",
                CoordinationSubscriptionSerialization.digest(
                        encodedRoutes));
        commitment.put(
                "prunedScopePaths",
                CoordinationSubscriptionSerialization.digest(
                        encodedPruned));
        return CoordinationSubscriptionSerialization.digest(commitment);
    }

    private Map<String, Object> toCanonicalMap() {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        result.put("projectionVersion", projectionVersion);
        result.put(
                "languageRuntimeRegistryIdentity",
                languageRuntimeRegistryIdentity);
        result.put(
                "coordinationRuntimeRegistryIdentity",
                coordinationRuntimeRegistryIdentity);
        result.put(
                "algorithmIdentity",
                algorithmIdentity);
        result.put("rootBlueId", rootBlueId);
        result.put("rootRevision", rootRevision);
        result.put(
                "activationFrontier",
                CoordinationSubscriptionSerialization
                        .orderKeyToList(
                                activationFrontier));
        List<Map<String, Object>> encodedOccurrences =
                new ArrayList<Map<String, Object>>(
                        occurrences.size());
        for (CoordinationSubscriptionOccurrence occurrence
                : occurrences) {
            encodedOccurrences.add(
                    occurrence.toCanonicalMap());
        }
        result.put("occurrences", encodedOccurrences);
        result.put(
                "processEmbeddedRoutes",
                processEmbeddedRoutes);
        result.put(
                "prunedScopePaths",
                new ArrayList<String>(
                        prunedScopePaths));
        return CoordinationSubscriptionSerialization
                .immutableMap(result);
    }

    private void requireCurrentFormat() {
        if (!VERSION.equals(projectionVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported Coordination projection version: "
                            + projectionVersion);
        }
        if (!ALGORITHM_IDENTITY.equals(
                algorithmIdentity)) {
            throw new IllegalArgumentException(
                    "Coordination subscription projection "
                            + "algorithm identity does not match "
                            + "this library");
        }
    }

    /** Immutable live-work snapshot for trusted indexed planning. */
    public static final class PlanningMetrics {
        private final long constructionOccurrenceValidationCount;
        private final long trustedPlanningVerificationCount;
        private final long exactOccurrenceLookupCount;
        private final long candidateScopeLookupCount;

        private PlanningMetrics(
                long constructionOccurrenceValidationCount,
                long trustedPlanningVerificationCount,
                long exactOccurrenceLookupCount,
                long candidateScopeLookupCount) {
            this.constructionOccurrenceValidationCount =
                    constructionOccurrenceValidationCount;
            this.trustedPlanningVerificationCount =
                    trustedPlanningVerificationCount;
            this.exactOccurrenceLookupCount = exactOccurrenceLookupCount;
            this.candidateScopeLookupCount = candidateScopeLookupCount;
        }

        public long constructionOccurrenceValidationCount() {
            return constructionOccurrenceValidationCount;
        }

        public long trustedPlanningVerificationCount() {
            return trustedPlanningVerificationCount;
        }

        public long exactOccurrenceLookupCount() {
            return exactOccurrenceLookupCount;
        }

        public long candidateScopeLookupCount() {
            return candidateScopeLookupCount;
        }
    }

    /** Package proof that grants access to prevalidated exact indexes. */
    static final class PlanningVerification {
        private final CoordinationSubscriptionSnapshot snapshot;
        private final String bindingIdentity;

        private PlanningVerification(
                CoordinationSubscriptionSnapshot snapshot,
                String bindingIdentity) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.bindingIdentity = requireText(
                    bindingIdentity, "bindingIdentity");
        }

        CoordinationSubscriptionSnapshot snapshot() {
            return snapshot;
        }

        CoordinationIndexedDeliveryEngine.IndexedActiveSurface
        indexedActiveSurface() {
            return snapshot.indexedActiveSurface;
        }

        CoordinationSubscriptionOccurrence occurrence(String key) {
            snapshot.exactOccurrenceLookupCount.incrementAndGet();
            return snapshot.merkleIndex.occurrence(key);
        }

        CoordinationSubscriptionOccurrence occurrenceByLanguageKey(
                String key) {
            snapshot.exactOccurrenceLookupCount.incrementAndGet();
            return snapshot.merkleIndex.occurrenceByInternalKey(key);
        }

        String bindingIdentity() {
            return bindingIdentity;
        }
    }

    private static Map<String, List<String>> immutableRoutes(
            Map<String, List<String>> supplied) {
        Objects.requireNonNull(
                supplied, "processEmbeddedRoutes");
        List<String> keys =
                new ArrayList<String>(
                        supplied.keySet());
        Collections.sort(
                keys,
                ExternalOrderKey::compareTextCodePoints);
        Map<String, List<String>> result =
                new LinkedHashMap<String, List<String>>();
        for (String key : keys) {
            String suppliedKey =
                    requireText(
                            key,
                            "Process Embedded contract path");
            String exactKey =
                    PointerUtils.normalizePointer(
                            suppliedKey);
            if (!exactKey.equals(suppliedKey)) {
                throw new IllegalArgumentException(
                        "Process Embedded contract path must "
                                + "be canonical: " + suppliedKey);
            }
            List<String> children =
                    new ArrayList<String>(
                            Objects.requireNonNull(
                                    supplied.get(key),
                                    "Process Embedded child paths"));
            Set<String> unique =
                    new LinkedHashSet<String>();
            List<String> normalized =
                    new ArrayList<String>();
            for (String child : children) {
                String suppliedChild =
                        requireText(
                                child,
                                "Process Embedded child path");
                String exact =
                        PointerUtils.normalizeScope(
                                suppliedChild);
                if (!exact.equals(suppliedChild)) {
                    throw new IllegalArgumentException(
                            "Process Embedded child path must "
                                    + "be canonical: "
                                    + suppliedChild);
                }
                if (!unique.add(exact)) {
                    throw new IllegalArgumentException(
                            "Duplicate Process Embedded child "
                                    + "path: " + exact);
                }
                normalized.add(exact);
            }
            if (result.put(
                    exactKey,
                    Collections.unmodifiableList(
                            normalized)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate normalized Process Embedded "
                                + "contract path: " + exactKey);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Set<String> immutablePaths(
            Set<String> supplied) {
        Objects.requireNonNull(
                supplied, "prunedScopePaths");
        List<String> ordered =
                new ArrayList<String>();
        for (String path : supplied) {
            String suppliedPath =
                    requireText(
                            path,
                            "pruned scope path");
            String exact =
                    PointerUtils.normalizeScope(
                            suppliedPath);
            if (!exact.equals(suppliedPath)) {
                throw new IllegalArgumentException(
                        "Pruned scope path must be canonical: "
                                + suppliedPath);
            }
            ordered.add(exact);
        }
        Collections.sort(
                ordered,
                ExternalOrderKey::compareTextCodePoints);
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(ordered));
    }

    private static String identity(
            String kind,
            List<String> values) {
        List<Node> items =
                new ArrayList<Node>(
                        values.size());
        for (String value : values) {
            items.add(
                    new Node().value(value));
        }
        return DirectBlueIdCalculator.calculateBlueId(
                new Node()
                        .properties(
                                "kind",
                                new Node().value(kind))
                        .properties(
                                "values",
                                new Node().items(items)));
    }

    private static String requireText(
            String value,
            String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }
}
