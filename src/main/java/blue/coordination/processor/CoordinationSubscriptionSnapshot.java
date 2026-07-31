package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
            "blue.coordination/subscription-snapshot/1.0";

    /** Identity of the exact deterministic projection algorithm. */
    public static final String ALGORITHM_IDENTITY =
            identity(
                    "blue.coordination/"
                            + "subscription-projection-algorithm/1.0",
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
    private final Map<String, CoordinationSubscriptionOccurrence>
            occurrencesByKey;
    private final Map<String, List<String>>
            processEmbeddedRoutes;
    private final Set<String> prunedScopePaths;
    private final String digest;

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
                null);
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
            String suppliedDigest) {
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
        List<CoordinationSubscriptionOccurrence> ordered =
                new ArrayList<
                        CoordinationSubscriptionOccurrence>(
                        Objects.requireNonNull(
                                occurrences, "occurrences"));
        Collections.sort(
                ordered,
                CoordinationSubscriptionOccurrence
                        .CANONICAL_ORDER);
        Map<String, CoordinationSubscriptionOccurrence>
                indexed =
                new LinkedHashMap<
                        String,
                        CoordinationSubscriptionOccurrence>();
        for (CoordinationSubscriptionOccurrence occurrence
                : ordered) {
            CoordinationSubscriptionOccurrence exact =
                    Objects.requireNonNull(
                            occurrence,
                            "subscription occurrence");
            if (exact.endAtRootRevision() != null) {
                throw new IllegalArgumentException(
                        "Snapshot contains a retired occurrence: "
                                + exact.occurrenceKey());
            }
            if (indexed.put(
                    exact.occurrenceKey(),
                    exact) != null) {
                throw new IllegalArgumentException(
                        "Duplicate subscription occurrence: "
                                + exact.occurrenceKey());
            }
        }
        this.occurrences =
                Collections.unmodifiableList(ordered);
        this.occurrencesByKey =
                Collections.unmodifiableMap(indexed);
        this.processEmbeddedRoutes =
                immutableRoutes(processEmbeddedRoutes);
        this.prunedScopePaths =
                immutablePaths(prunedScopePaths);
        this.digest =
                CoordinationSubscriptionSerialization
                        .digest(toCanonicalMap());
        if (suppliedDigest != null
                && !this.digest.equals(
                        suppliedDigest)) {
            throw new IllegalArgumentException(
                    "Persisted subscription snapshot digest "
                            + "does not match its content");
        }
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
        return occurrencesByKey.get(occurrenceKey);
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
     * Serializes the snapshot to application-independent scalar/list/map
     * values.
     *
     * @return immutable canonical persistence map including the digest
     */
    public Map<String, Object> toMap() {
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
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "projectionVersion"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "languageRuntimeRegistryIdentity"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "coordinationRuntimeRegistryIdentity"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        persisted,
                                        "algorithmIdentity"),
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
                                .text(persisted, "digest"));
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
        return BlueIdCalculator.calculateBlueId(
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
