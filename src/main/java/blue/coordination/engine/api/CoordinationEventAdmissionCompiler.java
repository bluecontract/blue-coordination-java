package blue.coordination.engine.api;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.fastpath.BoundedSingleFlightCache;
import blue.coordination.fastpath.CacheMetrics;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Compiles exact events into immutable, one-pass admission artifacts. */
public final class CoordinationEventAdmissionCompiler {

    public static final long DEFAULT_EVENT_CACHE_MAXIMUM_WEIGHT_BYTES =
            128L * 1024L * 1024L;
    public static final long DEFAULT_FRAGMENT_CACHE_MAXIMUM_WEIGHT_BYTES =
            256L * 1024L * 1024L;

    private final String environmentIdentity;
    private final String languageGenerationIdentity;
    private final String providerGenerationIdentity;
    private final String admissionDomainIdentity;
    private final CoordinationDocumentSplitter splitter;
    private final BoundedSingleFlightCache<
            CoordinationEventAdmissionCacheKey,
            CoordinationVerifiedEventAdmission> cache;
    private final BoundedSingleFlightCache<
            CoordinationFragmentEvidenceCacheKey,
            CoordinationCanonicalFragment> fragmentEvidence;
    private final CoordinationEventAdmissionMetrics metrics;

    public CoordinationEventAdmissionCompiler(
            String environmentIdentity,
            String languageGenerationIdentity,
            String providerGenerationIdentity,
            CoordinationDocumentSplitter splitter,
            int maximumCachedEvents,
            int maximumCachedFragments,
            CoordinationEventAdmissionMetrics metrics) {
        this(
                environmentIdentity,
                languageGenerationIdentity,
                providerGenerationIdentity,
                splitter,
                maximumCachedEvents,
                DEFAULT_EVENT_CACHE_MAXIMUM_WEIGHT_BYTES,
                maximumCachedFragments,
                DEFAULT_FRAGMENT_CACHE_MAXIMUM_WEIGHT_BYTES,
                metrics);
    }

    public CoordinationEventAdmissionCompiler(
            String environmentIdentity,
            String languageGenerationIdentity,
            String providerGenerationIdentity,
            CoordinationDocumentSplitter splitter,
            int maximumCachedEvents,
            long maximumCachedEventWeightBytes,
            int maximumCachedFragments,
            long maximumCachedFragmentWeightBytes,
            CoordinationEventAdmissionMetrics metrics) {
        this.environmentIdentity = requireText(
                environmentIdentity, "environmentIdentity");
        this.languageGenerationIdentity = requireText(
                languageGenerationIdentity,
                "languageGenerationIdentity");
        this.providerGenerationIdentity = requireText(
                providerGenerationIdentity,
                "providerGenerationIdentity");
        this.admissionDomainIdentity = CoordinationEventAdmissionCacheKey
                .admissionDomainIdentity(
                this.environmentIdentity,
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                this.languageGenerationIdentity,
                this.providerGenerationIdentity);
        this.splitter = Objects.requireNonNull(splitter, "splitter");
        this.cache = new BoundedSingleFlightCache<
                CoordinationEventAdmissionCacheKey,
                CoordinationVerifiedEventAdmission>(
                maximumCachedEvents,
                maximumCachedEventWeightBytes,
                CoordinationVerifiedEventAdmission
                        ::approximateRetainedWeightBytes);
        this.fragmentEvidence = new BoundedSingleFlightCache<
                CoordinationFragmentEvidenceCacheKey,
                CoordinationCanonicalFragment>(
                maximumCachedFragments,
                maximumCachedFragmentWeightBytes,
                CoordinationCanonicalFragment
                        ::approximateRetainedWeightBytes);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Checks the claimed canonical identity, then compiles this exact event
     * at most once for the complete evidence domain.
     */
    public CoordinationVerifiedEventAdmission compile(
            String claimedEventBlueId,
            Node exactEvent) {
        String claimed = requireText(
                claimedEventBlueId, "claimedEventBlueId");
        Node checked = Objects.requireNonNull(exactEvent, "exactEvent");
        return compileKnownIdentity(claimed, checked, true);
    }

    /** Calculates the canonical Root identity once and compiles its graph. */
    public CoordinationVerifiedEventAdmission compile(Node exactEvent) {
        Node checked = Objects.requireNonNull(exactEvent, "exactEvent");
        metrics.blueIdCalculation();
        String actual = DirectBlueIdCalculator.calculateBlueId(checked);
        return compileKnownIdentity(actual, checked, false);
    }

    public int cachedEventCount() {
        return cache.retainedSize();
    }

    public CacheMetrics eventCacheMetrics() {
        return cache.metrics();
    }

    public CacheMetrics fragmentCacheMetrics() {
        return fragmentEvidence.metrics();
    }

    /** Complete opaque domain captured by every compiled admission. */
    public String admissionDomainIdentity() {
        return admissionDomainIdentity;
    }

    private CoordinationVerifiedEventAdmission compileKnownIdentity(
            String eventBlueId,
            final Node exactEvent,
            boolean verifyCacheHit) {
        final CoordinationEventAdmissionCacheKey key =
                new CoordinationEventAdmissionCacheKey(
                        environmentIdentity,
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID,
                        languageGenerationIdentity,
                        providerGenerationIdentity,
                        eventBlueId);
        final boolean[] compiled = new boolean[]{false};
        CoordinationVerifiedEventAdmission result = cache.getOrCompute(
                key,
                ignored -> {
                    compiled[0] = true;
                    return compileUncached(key, exactEvent);
                });
        if (compiled[0]) {
            metrics.templateMiss();
            metrics.templateCompiled();
        } else {
            metrics.templateHit();
            if (verifyCacheHit) {
                /* The cache key starts with an untrusted claimed identity.
                 * A miss is verified by the canonical split below.  A hit
                 * must still bind this caller's exact value to the cached
                 * winner, but needs only one direct identity calculation. */
                metrics.blueIdCalculation();
                String actual = DirectBlueIdCalculator.calculateBlueId(
                        exactEvent);
                if (!eventBlueId.equals(actual)) {
                    throw new IllegalArgumentException(
                            "Claimed event BlueId differs from exact event");
                }
            }
        }
        return result;
    }

    private CoordinationVerifiedEventAdmission compileUncached(
            CoordinationEventAdmissionCacheKey key,
            Node exactEvent) {
        metrics.fullEventSplit();
        CoordinationDocumentSplitter.SplitGraph graph =
                splitter.splitEvent(exactEvent);
        if (!key.eventBlueId().equals(graph.rootBlueId())) {
            throw new IllegalArgumentException(
                    "Claimed event BlueId differs from exact event");
        }
        CoordinationFragmentInventory inventory =
                CoordinationFragmentInventory.from(graph);
        final Map<String, CoordinationCanonicalFragment> fragments =
                new LinkedHashMap<String, CoordinationCanonicalFragment>();
        for (String fragmentBlueId : graph.fragmentBlueIds()) {
            final String checkedFragmentBlueId = fragmentBlueId;
            CoordinationFragmentEvidenceCacheKey fragmentKey =
                    new CoordinationFragmentEvidenceCacheKey(
                            key.environmentIdentity(),
                            key.fragmentationProfileIdentity(),
                            key.languageGenerationIdentity(),
                            key.providerGenerationIdentity(),
                            checkedFragmentBlueId);
            final boolean[] physicalCompilation = new boolean[]{false};
            CoordinationCanonicalFragment evidence =
                    fragmentEvidence.getOrCompute(
                            fragmentKey,
                            ignored -> {
                                physicalCompilation[0] = true;
                                return compileFragmentEvidence(
                                        graph,
                                        checkedFragmentBlueId);
                            });
            if (physicalCompilation[0]) {
                metrics.fragmentEvidenceMiss();
            } else {
                metrics.fragmentEvidenceHit();
            }
            fragments.put(checkedFragmentBlueId, evidence);
        }
        /* splitEvent uses the canonical exact-fragment provider directly.
         * Unlike a document split it cannot have nonsemantic PROCESS header
         * views, so scanning, materializing, re-hashing, and wire-comparing
         * every event fragment here can only produce an empty map. */
        Map<String, Node> views = Collections.emptyMap();
        return new CoordinationVerifiedEventAdmission(
                key,
                inventory,
                graph.frozenOriginalRoot(),
                fragments,
                views);
    }

    private CoordinationCanonicalFragment compileFragmentEvidence(
            CoordinationDocumentSplitter.SplitGraph graph,
            String fragmentBlueId) {
        metrics.wireFingerprint();
        Node fragment = graph.fragment(fragmentBlueId);
        if (fragment == null) {
            throw new IllegalStateException(
                    "Canonical event fragment is unavailable: "
                            + fragmentBlueId);
        }
        String fingerprint = CoordinationFragmentAdmissionVerifier
                .physicalFragmentIdentity(fragment);
        return new CoordinationCanonicalFragment(
                fragmentBlueId,
                fingerprint,
                fragment);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
