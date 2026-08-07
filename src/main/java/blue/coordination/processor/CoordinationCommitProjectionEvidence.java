package blue.coordination.processor;

import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete commit-local input for delta subscription projection. The producer
 * must derive affected retained keys from persisted dependency pointers and
 * the exact committed changes, and must supply refreshed evidence for every
 * such key. Missing evidence is a cold-path signal, never permission to guess.
 */
public final class CoordinationCommitProjectionEvidence {
    private final String resultingRootBlueId;
    private final long resultingRootRevision;
    private final ExternalOrderKey transitionOrderKey;
    private final SubscriptionDelta membershipDelta;
    private final List<CoordinationSubscriptionOccurrence> currentEvidence;
    private final Set<String> affectedRetainedOccurrenceKeys;
    private final Map<String, List<String>> processEmbeddedRoutes;
    private final Set<String> prunedScopePaths;
    private final EffectiveFragmentationCatalog fragmentationCatalog;
    private final boolean complete;

    public CoordinationCommitProjectionEvidence(
            String resultingRootBlueId,
            long resultingRootRevision,
            ExternalOrderKey transitionOrderKey,
            SubscriptionDelta membershipDelta,
            Collection<CoordinationSubscriptionOccurrence> currentEvidence,
            Collection<String> affectedRetainedOccurrenceKeys,
            Map<String, List<String>> processEmbeddedRoutes,
            Set<String> prunedScopePaths,
            EffectiveFragmentationCatalog fragmentationCatalog,
            boolean complete) {
        this.resultingRootBlueId = text(resultingRootBlueId, "resultingRootBlueId");
        if (resultingRootRevision < 0L) {
            throw new IllegalArgumentException("resultingRootRevision must be non-negative");
        }
        this.resultingRootRevision = resultingRootRevision;
        this.transitionOrderKey = Objects.requireNonNull(
                transitionOrderKey, "transitionOrderKey");
        this.membershipDelta = Objects.requireNonNull(membershipDelta, "membershipDelta");
        this.currentEvidence = immutableOccurrences(currentEvidence);
        this.affectedRetainedOccurrenceKeys = immutableKeys(
                affectedRetainedOccurrenceKeys);
        this.processEmbeddedRoutes = immutableRoutes(processEmbeddedRoutes);
        this.prunedScopePaths = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        prunedScopePaths, "prunedScopePaths")));
        this.fragmentationCatalog = fragmentationCatalog;
        this.complete = complete;
        if (fragmentationCatalog != null
                && !this.resultingRootBlueId.equals(fragmentationCatalog.rootBlueId())) {
            throw new IllegalArgumentException("fragmentation catalog Root mismatch");
        }
    }

    public String resultingRootBlueId() { return resultingRootBlueId; }
    public long resultingRootRevision() { return resultingRootRevision; }
    public ExternalOrderKey transitionOrderKey() { return transitionOrderKey; }
    public SubscriptionDelta membershipDelta() { return membershipDelta; }
    public List<CoordinationSubscriptionOccurrence> currentEvidence() {
        return currentEvidence;
    }
    public Set<String> affectedRetainedOccurrenceKeys() {
        return affectedRetainedOccurrenceKeys;
    }
    public Map<String, List<String>> processEmbeddedRoutes() {
        return processEmbeddedRoutes;
    }
    public Set<String> prunedScopePaths() { return prunedScopePaths; }
    public EffectiveFragmentationCatalog fragmentationCatalog() {
        return fragmentationCatalog;
    }
    public boolean complete() { return complete; }

    private static List<CoordinationSubscriptionOccurrence> immutableOccurrences(
            Collection<CoordinationSubscriptionOccurrence> supplied) {
        List<CoordinationSubscriptionOccurrence> result =
                new ArrayList<CoordinationSubscriptionOccurrence>(
                        Objects.requireNonNull(supplied, "currentEvidence"));
        for (CoordinationSubscriptionOccurrence value : result) {
            Objects.requireNonNull(value, "current evidence occurrence");
        }
        Collections.sort(result, CoordinationSubscriptionOccurrence.CANONICAL_ORDER);
        return Collections.unmodifiableList(result);
    }

    private static Set<String> immutableKeys(Collection<String> supplied) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(supplied, "affectedRetainedOccurrenceKeys"));
        for (String value : result) text(value, "affected occurrence key");
        Collections.sort(result);
        Set<String> unique = new LinkedHashSet<String>(result);
        if (unique.size() != result.size()) {
            throw new IllegalArgumentException("duplicate affected occurrence key");
        }
        return Collections.unmodifiableSet(unique);
    }

    private static Map<String, List<String>> immutableRoutes(
            Map<String, List<String>> supplied) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : Objects.requireNonNull(
                supplied, "processEmbeddedRoutes").entrySet()) {
            result.put(text(entry.getKey(), "route key"),
                    Collections.unmodifiableList(new ArrayList<String>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String text(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }
}
