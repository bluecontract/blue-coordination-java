package blue.coordination.fastpath;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, already verified event-time view of one subscription generation.
 * It moves scope traversal, chain hashing, dependency indexing and
 * subscription-key inversion out of the hot event loop.
 */
public final class AdmittedProjection {
    private final ProjectionGenerationKey generation;
    private final List<AdmittedOccurrence> canonicalOccurrences;
    private final Map<String, AdmittedOccurrence> byPublicKey;
    private final Map<String, AdmittedOccurrence> byLanguageKey;
    private final Map<String, List<String>> publicKeysBySubscriptionKey;
    private final PathDependencyIndex dependencyIndex;
    private final String projectionIdentity;
    private final long estimatedWeight;

    public AdmittedProjection(
            ProjectionGenerationKey generation,
            Collection<AdmittedOccurrence> occurrences) {
        this.generation = Objects.requireNonNull(generation, "generation");
        List<AdmittedOccurrence> ordered = new ArrayList<AdmittedOccurrence>(
                Objects.requireNonNull(occurrences, "occurrences"));
        Collections.sort(ordered);
        Map<String, AdmittedOccurrence> publicIndex = new LinkedHashMap<String, AdmittedOccurrence>();
        Map<String, AdmittedOccurrence> languageIndex = new LinkedHashMap<String, AdmittedOccurrence>();
        Map<String, List<String>> subscriptions = new HashMap<String, List<String>>();
        for (AdmittedOccurrence occurrence : ordered) {
            AdmittedOccurrence exact = Objects.requireNonNull(occurrence, "occurrence");
            if (publicIndex.put(exact.publicKey(), exact) != null) {
                throw new IllegalArgumentException(
                        "duplicate public occurrence key: " + exact.publicKey());
            }
            if (languageIndex.put(exact.languageKey(), exact) != null) {
                throw new IllegalArgumentException(
                        "duplicate Language occurrence key: " + exact.languageKey());
            }
            for (String subscriptionKey : exact.subscriptionKeys()) {
                List<String> members = subscriptions.get(subscriptionKey);
                if (members == null) {
                    members = new ArrayList<String>();
                    subscriptions.put(subscriptionKey, members);
                }
                members.add(exact.publicKey());
            }
        }
        this.canonicalOccurrences = Collections.unmodifiableList(ordered);
        this.byPublicKey = Collections.unmodifiableMap(publicIndex);
        this.byLanguageKey = Collections.unmodifiableMap(languageIndex);
        this.publicKeysBySubscriptionKey = freezeInverted(subscriptions, publicIndex);
        this.dependencyIndex = PathDependencyIndex.from(ordered);
        this.projectionIdentity = identity();
        this.estimatedWeight = estimateWeight();
    }

    public ProjectionGenerationKey generation() { return generation; }
    public List<AdmittedOccurrence> occurrences() { return canonicalOccurrences; }
    public String projectionIdentity() { return projectionIdentity; }
    public long estimatedWeight() { return estimatedWeight; }

    public AdmittedOccurrence requirePublic(String publicKey) {
        AdmittedOccurrence result = byPublicKey.get(
                AdmittedOccurrence.text(publicKey, "publicKey"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "stale or unknown occurrence: " + publicKey);
        }
        return result;
    }

    public AdmittedOccurrence requireLanguage(String languageKey) {
        AdmittedOccurrence result = byLanguageKey.get(
                AdmittedOccurrence.text(languageKey, "languageKey"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "unknown Language occurrence: " + languageKey);
        }
        return result;
    }

    /** Returns already canonical candidate rows for exact subscription keys. */
    public List<String> candidatesForSubscriptionKeys(Collection<String> keys) {
        Set<String> union = new LinkedHashSet<String>();
        for (String key : Objects.requireNonNull(keys, "subscriptionKeys")) {
            List<String> matches = publicKeysBySubscriptionKey.get(
                    AdmittedOccurrence.text(key, "subscriptionKey"));
            if (matches != null) union.addAll(matches);
        }
        List<AdmittedOccurrence> occurrences = new ArrayList<AdmittedOccurrence>();
        for (String publicKey : union) occurrences.add(byPublicKey.get(publicKey));
        Collections.sort(occurrences);
        List<String> result = new ArrayList<String>(occurrences.size());
        for (AdmittedOccurrence occurrence : occurrences) {
            result.add(occurrence.publicKey());
        }
        return Collections.unmodifiableList(result);
    }

    /** Validates only k selected rows and returns their precomputed closure. */
    public SelectedSurface select(Collection<String> orderedCandidateKeys) {
        List<String> supplied = new ArrayList<String>(
                Objects.requireNonNull(orderedCandidateKeys, "orderedCandidateKeys"));
        List<AdmittedOccurrence> selected = new ArrayList<AdmittedOccurrence>(supplied.size());
        Set<String> unique = new LinkedHashSet<String>();
        for (String key : supplied) {
            if (!unique.add(key)) {
                throw new IllegalArgumentException("duplicate candidate: " + key);
            }
            AdmittedOccurrence occurrence = requirePublic(key);
            selected.add(occurrence);
        }
        return new SelectedSurface(generation, selected);
    }

    /** Exact invalidation set; no occurrence scan is performed here. */
    public Set<String> affectedOccurrences(Collection<String> changedPaths) {
        return dependencyIndex.affected(changedPaths);
    }

    private String identity() {
        MessageDigest digest = AdmittedOccurrence.sha256();
        AdmittedOccurrence.add(digest, "blue.coordination/admitted-projection/1.0");
        AdmittedOccurrence.add(digest, generation.environmentIdentity());
        AdmittedOccurrence.add(digest, generation.sessionId());
        AdmittedOccurrence.add(digest, generation.rootBlueId());
        AdmittedOccurrence.add(digest, Long.toString(generation.rootRevision()));
        AdmittedOccurrence.add(digest, generation.inventoryIdentity());
        AdmittedOccurrence.add(digest, generation.subscriptionDigest());
        AdmittedOccurrence.add(digest, generation.runtimeIdentity());
        for (AdmittedOccurrence occurrence : canonicalOccurrences) {
            AdmittedOccurrence.add(digest, occurrence.semanticFingerprint());
        }
        return "sha256:" + AdmittedOccurrence.hex(digest.digest());
    }

    private long estimateWeight() {
        long characters = 256L;
        for (AdmittedOccurrence occurrence : canonicalOccurrences) {
            characters += 256L;
            characters += occurrence.publicKey().length();
            characters += occurrence.languageKey().length();
            characters += occurrence.scopePath().length();
            characters += occurrence.semanticFingerprint().length();
            for (String value : occurrence.scopeChainBlueIds()) characters += value.length();
            for (String value : occurrence.dependencyBlueIds()) characters += value.length();
            for (String value : occurrence.subscriptionKeys()) characters += value.length();
        }
        return Math.max(1L, Math.multiplyExact(characters, 2L));
    }

    private static Map<String, List<String>> freezeInverted(
            Map<String, List<String>> supplied,
            Map<String, AdmittedOccurrence> occurrences) {
        List<String> keys = new ArrayList<String>(supplied.keySet());
        Collections.sort(keys, AdmittedOccurrence::codePointCompare);
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (String key : keys) {
            List<AdmittedOccurrence> members = new ArrayList<AdmittedOccurrence>();
            for (String publicKey : supplied.get(key)) {
                members.add(occurrences.get(publicKey));
            }
            Collections.sort(members);
            List<String> publicKeys = new ArrayList<String>(members.size());
            for (AdmittedOccurrence member : members) publicKeys.add(member.publicKey());
            result.put(key, Collections.unmodifiableList(publicKeys));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Precomputed per-event resource closure. */
    public static final class SelectedSurface {
        private final ProjectionGenerationKey generation;
        private final List<AdmittedOccurrence> occurrences;
        private final List<String> publicKeys;
        private final List<String> languageKeys;
        private final Map<String, List<String>> scopeChains;
        private final Set<String> requiredIdentities;
        private final List<String> prefetchIdentities;

        private SelectedSurface(
                ProjectionGenerationKey generation,
                List<AdmittedOccurrence> occurrences) {
            this.generation = generation;
            this.occurrences = Collections.unmodifiableList(
                    new ArrayList<AdmittedOccurrence>(occurrences));
            List<String> publicOrder = new ArrayList<String>();
            List<String> languageOrder = new ArrayList<String>();
            Map<String, List<String>> chains = new LinkedHashMap<String, List<String>>();
            Set<String> required = new LinkedHashSet<String>();
            Set<String> prefetch = new java.util.TreeSet<String>(
                    AdmittedOccurrence::codePointCompare);
            required.add(generation.rootBlueId());
            for (AdmittedOccurrence occurrence : occurrences) {
                publicOrder.add(occurrence.publicKey());
                languageOrder.add(occurrence.languageKey());
                chains.putIfAbsent(occurrence.scopePath(), occurrence.scopeChainBlueIds());
                required.addAll(occurrence.scopeChainBlueIds());
                required.addAll(occurrence.sourceContributionBlueIds());
                required.addAll(occurrence.dependencyBlueIds());
                prefetch.addAll(occurrence.sourceContributionBlueIds());
                prefetch.addAll(occurrence.dependencyBlueIds());
            }
            prefetch.remove(generation.rootBlueId());
            this.publicKeys = Collections.unmodifiableList(publicOrder);
            this.languageKeys = Collections.unmodifiableList(languageOrder);
            this.scopeChains = Collections.unmodifiableMap(chains);
            this.requiredIdentities = Collections.unmodifiableSet(required);
            this.prefetchIdentities = Collections.unmodifiableList(
                    new ArrayList<String>(prefetch));
        }

        public ProjectionGenerationKey generation() { return generation; }
        public List<AdmittedOccurrence> occurrences() { return occurrences; }
        public List<String> publicKeys() { return publicKeys; }
        public List<String> languageKeys() { return languageKeys; }
        public Map<String, List<String>> scopeChains() { return scopeChains; }
        public Set<String> requiredIdentities() { return requiredIdentities; }
        public List<String> prefetchIdentities() { return prefetchIdentities; }
    }
}
