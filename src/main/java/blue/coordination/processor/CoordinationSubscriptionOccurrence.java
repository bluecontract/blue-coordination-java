package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable persistence-neutral identity of one active external Channel
 * occurrence.
 *
 * <p>The value contains only selected-scope identity, sanitized immutable
 * header identities, subscription keys, interval bounds, and exact
 * revalidation dependencies. Executable bodies and provider transport state
 * are deliberately absent.</p>
 */
public final class CoordinationSubscriptionOccurrence {
    /** Stable policy name for Language's lower-exclusive activation bound. */
    public static final String SUBSCRIPTION_START_POLICY =
            "blue.coordination/subscription-start/"
                    + "external-order-exclusive/1.0";

    static final Comparator<CoordinationSubscriptionOccurrence>
            CANONICAL_ORDER =
            new Comparator<CoordinationSubscriptionOccurrence>() {
                @Override
                public int compare(
                        CoordinationSubscriptionOccurrence left,
                        CoordinationSubscriptionOccurrence right) {
                    int compared =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.scopePath,
                                    right.scopePath);
                    if (compared != 0) {
                        return compared;
                    }
                    compared =
                            Integer.compare(
                                    left.order, right.order);
                    if (compared != 0) {
                        return compared;
                    }
                    compared =
                            ExternalOrderKey.compareTextCodePoints(
                                    left.channelKey,
                                    right.channelKey);
                    if (compared != 0) {
                        return compared;
                    }
                    return ExternalOrderKey.compareTextCodePoints(
                            left.effectiveTypeBlueId,
                            right.effectiveTypeBlueId);
                }
            };

    private final String occurrenceKey;
    private final String scopePath;
    private final String scopeBlueId;
    private final String channelKey;
    private final List<String> sourceContributionNodeBlueIds;
    private final String effectiveTypeBlueId;
    private final int order;
    private final String checkpointDomainBlueId;
    private final String headerIdentityBlueId;
    private final Map<String, String> headerFieldBlueIds;
    private final List<String> subscriptionKeys;
    private final Long activationRootRevision;
    private final ExternalOrderKey activationFrontier;
    private final Long endAtRootRevision;
    private final ExternalChannelDependencySnapshot dependencies;
    private final List<String> dependencyNodeBlueIds;

    CoordinationSubscriptionOccurrence(
            String scopePath,
            String scopeBlueId,
            String channelKey,
            List<String> sourceContributionNodeBlueIds,
            String effectiveTypeBlueId,
            int order,
            String checkpointDomainBlueId,
            String headerIdentityBlueId,
            Map<String, String> headerFieldBlueIds,
            List<String> subscriptionKeys,
            Long activationRootRevision,
            ExternalOrderKey activationFrontier,
            Long endAtRootRevision,
            ExternalChannelDependencySnapshot dependencies) {
        String suppliedScopePath =
                requireText(scopePath, "scopePath");
        String exactScopePath =
                PointerUtils.normalizeScope(
                        suppliedScopePath);
        if (!exactScopePath.equals(suppliedScopePath)) {
            throw new IllegalArgumentException(
                    "scopePath must be canonical: "
                            + suppliedScopePath);
        }
        this.scopePath = exactScopePath;
        this.scopeBlueId =
                requireText(scopeBlueId, "scopeBlueId");
        this.channelKey =
                requireText(channelKey, "channelKey");
        this.sourceContributionNodeBlueIds =
                immutableText(
                        sourceContributionNodeBlueIds,
                        "source contribution");
        this.effectiveTypeBlueId =
                requireText(
                        effectiveTypeBlueId,
                        "effectiveTypeBlueId");
        this.order = order;
        this.checkpointDomainBlueId =
                requireText(
                        checkpointDomainBlueId,
                        "checkpointDomainBlueId");
        this.headerIdentityBlueId =
                requireText(
                        headerIdentityBlueId,
                        "headerIdentityBlueId");
        this.headerFieldBlueIds =
                immutableTextMap(headerFieldBlueIds);
        this.subscriptionKeys =
                immutableText(
                        subscriptionKeys,
                        "subscription key");
        requireRevision(
                activationRootRevision,
                "activationRootRevision");
        requireRevision(
                endAtRootRevision,
                "endAtRootRevision");
        if (activationRootRevision != null
                && endAtRootRevision != null
                && endAtRootRevision.longValue()
                < activationRootRevision.longValue()) {
            throw new IllegalArgumentException(
                    "Subscription occurrence ends before activation");
        }
        this.activationRootRevision =
                activationRootRevision;
        this.activationFrontier = activationFrontier;
        this.endAtRootRevision = endAtRootRevision;
        this.dependencies =
                Objects.requireNonNull(
                        dependencies, "dependencies");
        this.dependencyNodeBlueIds =
                Collections.unmodifiableList(
                        new ArrayList<String>(
                                dependencies
                                        .deterministicDependencyNodeBlueIds()));
        this.occurrenceKey =
                keyFor(this.scopePath, this.channelKey);
    }

    /**
     * Calculates the stable public occurrence key for a scope/raw-key pair.
     *
     * @param scopePath absolute owning scope
     * @param channelKey exact raw Channel key
     * @return canonical Blue identity for the occurrence selector
     */
    public static String keyFor(
            String scopePath,
            String channelKey) {
        Node descriptor = new Node()
                .properties(
                        "kind",
                        new Node().value(
                                "blue.coordination/"
                                        + "external-channel-occurrence/1.0"))
                .properties(
                        "scopePath",
                        new Node().value(
                                PointerUtils.normalizeScope(
                                        requireText(
                                                scopePath,
                                                "scopePath"))))
                .properties(
                        "channelKey",
                        new Node().value(
                                requireText(
                                        channelKey,
                                        "channelKey")));
        return BlueIdCalculator.calculateBlueId(descriptor);
    }

    /** @return stable public occurrence key */
    public String occurrenceKey() {
        return occurrenceKey;
    }

    /** @return absolute selected scope path */
    public String scopePath() {
        return scopePath;
    }

    /** @return exact selected scope BlueId */
    public String scopeBlueId() {
        return scopeBlueId;
    }

    /** @return raw same-scope Channel key */
    public String channelKey() {
        return channelKey;
    }

    /** @return ordered exact Source-contribution BlueIds */
    public List<String> sourceContributionNodeBlueIds() {
        return sourceContributionNodeBlueIds;
    }

    /** @return effective Channel runtime type BlueId */
    public String effectiveTypeBlueId() {
        return effectiveTypeBlueId;
    }

    /** @return canonical effective-contract order */
    public int order() {
        return order;
    }

    /** @return exact checkpoint-domain identity */
    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    /** @return exact sanitized effective-header identity */
    public String headerIdentityBlueId() {
        return headerIdentityBlueId;
    }

    /**
     * Returns exact sanitized header fields as field-to-BlueId entries.
     *
     * @return immutable canonically ordered header field identities
     */
    public Map<String, String> headerFieldBlueIds() {
        return headerFieldBlueIds;
    }

    /** @return immutable ordered logical subscription keys */
    public List<String> subscriptionKeys() {
        return subscriptionKeys;
    }

    /** @return Root revision at which this interval activated */
    public Long activationRootRevision() {
        return activationRootRevision;
    }

    /**
     * Returns the exclusive order frontier declared when this interval
     * activated.
     *
     * @return immutable activation frontier, or {@code null}
     */
    public ExternalOrderKey activationFrontier() {
        return activationFrontier;
    }

    /**
     * Returns the stable Channel interval-start policy represented by
     * {@link #activationFrontier()}.
     *
     * @return lower-exclusive external-order policy identity
     */
    public String subscriptionStartPolicy() {
        return SUBSCRIPTION_START_POLICY;
    }

    /** @return retirement Root revision, or {@code null} while active */
    public Long endAtRootRevision() {
        return endAtRootRevision;
    }

    /**
     * Returns exact dependency identities required to revalidate this
     * occurrence.
     *
     * @return immutable ordered dependency BlueIds
     */
    public List<String> dependencyNodeBlueIds() {
        return dependencyNodeBlueIds;
    }

    /**
     * Returns the complete immutable Language dependency evidence.
     *
     * @return exact dependency snapshot
     */
    public ExternalChannelDependencySnapshot dependencyEvidence() {
        return dependencies;
    }

    /**
     * Converts this public value back to Language's immutable active-interval
     * evidence without weakening its dependencies.
     *
     * @return exact Language interval entry
     */
    public SubscriptionDelta.Entry toSubscriptionDeltaEntry() {
        return new SubscriptionDelta.Entry(
                scopePath,
                channelKey,
                effectiveTypeBlueId,
                sourceContributionNodeBlueIds,
                order,
                subscriptionKeys,
                checkpointDomainBlueId,
                dependencies,
                activationRootRevision,
                activationFrontier,
                endAtRootRevision);
    }

    CoordinationSubscriptionOccurrence withScopeAndInterval(
            String nextScopeBlueId,
            SubscriptionDelta.Entry entry) {
        return new CoordinationSubscriptionOccurrence(
                entry.scopePath(),
                nextScopeBlueId,
                entry.channelKey(),
                entry.sourceContributionNodeBlueIds(),
                entry.effectiveTypeBlueId(),
                entry.order(),
                entry.checkpointDomainBlueId(),
                headerIdentityBlueId,
                headerFieldBlueIds,
                entry.subscriptionKeys(),
                entry.activationRootRevision(),
                entry.startAfterExternalOrderKey(),
                entry.endAtRootRevision(),
                entry.dependencies());
    }

    Map<String, Object> toCanonicalMap() {
        Map<String, Object> result =
                new LinkedHashMap<String, Object>();
        result.put("occurrenceKey", occurrenceKey);
        result.put("scopePath", scopePath);
        result.put("scopeBlueId", scopeBlueId);
        result.put("channelKey", channelKey);
        result.put(
                "sourceContributionNodeBlueIds",
                sourceContributionNodeBlueIds);
        result.put(
                "effectiveTypeBlueId",
                effectiveTypeBlueId);
        result.put("order", order);
        result.put(
                "checkpointDomainBlueId",
                checkpointDomainBlueId);
        result.put(
                "headerIdentityBlueId",
                headerIdentityBlueId);
        result.put(
                "headerFieldBlueIds",
                headerFieldBlueIds);
        result.put("subscriptionKeys", subscriptionKeys);
        result.put(
                "subscriptionStartPolicy",
                SUBSCRIPTION_START_POLICY);
        if (activationRootRevision != null) {
            result.put(
                    "activationRootRevision",
                    activationRootRevision);
        }
        if (activationFrontier != null) {
            result.put(
                    "activationFrontier",
                    CoordinationSubscriptionSerialization
                            .orderKeyToList(
                                    activationFrontier));
        }
        if (endAtRootRevision != null) {
            result.put(
                    "endAtRootRevision",
                    endAtRootRevision);
        }
        result.put(
                "dependencies",
                CoordinationSubscriptionSerialization
                        .dependencyToMap(dependencies));
        return CoordinationSubscriptionSerialization
                .immutableMap(result);
    }

    static CoordinationSubscriptionOccurrence fromCanonicalMap(
            Map<String, ?> map) {
        CoordinationSubscriptionSerialization.requireFields(
                map,
                "occurrence",
                new String[] {
                        "occurrenceKey",
                        "scopePath",
                        "scopeBlueId",
                        "channelKey",
                        "sourceContributionNodeBlueIds",
                        "effectiveTypeBlueId",
                        "order",
                        "checkpointDomainBlueId",
                        "headerIdentityBlueId",
                        "headerFieldBlueIds",
                        "subscriptionKeys",
                        "subscriptionStartPolicy",
                        "dependencies"
                },
                "activationRootRevision",
                "activationFrontier",
                "endAtRootRevision");
        CoordinationSubscriptionOccurrence occurrence =
                new CoordinationSubscriptionOccurrence(
                        CoordinationSubscriptionSerialization
                                .text(map, "scopePath"),
                        CoordinationSubscriptionSerialization
                                .text(map, "scopeBlueId"),
                        CoordinationSubscriptionSerialization
                                .text(map, "channelKey"),
                        CoordinationSubscriptionSerialization
                                .textList(
                                        map,
                                        "sourceContributionNodeBlueIds"),
                        CoordinationSubscriptionSerialization
                                .text(map, "effectiveTypeBlueId"),
                        CoordinationSubscriptionSerialization
                                .integer(map, "order"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        map,
                                        "checkpointDomainBlueId"),
                        CoordinationSubscriptionSerialization
                                .text(
                                        map,
                                        "headerIdentityBlueId"),
                        CoordinationSubscriptionSerialization
                                .textMap(
                                        map,
                                        "headerFieldBlueIds"),
                        CoordinationSubscriptionSerialization
                                .textList(
                                        map,
                                        "subscriptionKeys"),
                        CoordinationSubscriptionSerialization
                                .optionalLong(
                                        map,
                                        "activationRootRevision"),
                        CoordinationSubscriptionSerialization
                                .optionalOrderKey(
                                        map,
                                        "activationFrontier"),
                        CoordinationSubscriptionSerialization
                                .optionalLong(
                                        map,
                                        "endAtRootRevision"),
                        CoordinationSubscriptionSerialization
                                .dependencyFromMap(
                                        CoordinationSubscriptionSerialization
                                                .map(
                                                        map,
                                                        "dependencies")));
        String suppliedKey =
                CoordinationSubscriptionSerialization
                        .text(map, "occurrenceKey");
        String suppliedPolicy =
                CoordinationSubscriptionSerialization
                        .text(
                                map,
                                "subscriptionStartPolicy");
        if (!occurrence.occurrenceKey.equals(suppliedKey)) {
            throw new IllegalArgumentException(
                    "Persisted occurrenceKey does not match "
                            + "scopePath/channelKey");
        }
        if (!SUBSCRIPTION_START_POLICY.equals(
                suppliedPolicy)) {
            throw new IllegalArgumentException(
                    "Unsupported subscriptionStartPolicy: "
                            + suppliedPolicy);
        }
        return occurrence;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other
                instanceof CoordinationSubscriptionOccurrence)) {
            return false;
        }
        CoordinationSubscriptionOccurrence occurrence =
                (CoordinationSubscriptionOccurrence) other;
        return toCanonicalMap().equals(
                occurrence.toCanonicalMap());
    }

    @Override
    public int hashCode() {
        return toCanonicalMap().hashCode();
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

    private static List<String> immutableText(
            List<String> source,
            String label) {
        Objects.requireNonNull(source, label);
        List<String> copy =
                new ArrayList<String>(source.size());
        java.util.Set<String> unique =
                new java.util.LinkedHashSet<String>();
        for (String value : source) {
            if (value == null
                    || value.isEmpty()
                    || !unique.add(value)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate "
                                + label + ": " + value);
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, String> immutableTextMap(
            Map<String, String> source) {
        Objects.requireNonNull(
                source, "headerFieldBlueIds");
        List<String> keys =
                new ArrayList<String>(source.keySet());
        Collections.sort(
                keys,
                ExternalOrderKey::compareTextCodePoints);
        Map<String, String> copy =
                new LinkedHashMap<String, String>();
        for (String key : keys) {
            copy.put(
                    requireText(key, "header field"),
                    requireText(
                            source.get(key),
                            "header field BlueId"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static void requireRevision(
            Long revision,
            String label) {
        if (revision != null
                && revision.longValue() < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
    }
}
