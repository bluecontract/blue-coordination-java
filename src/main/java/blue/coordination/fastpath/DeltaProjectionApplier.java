package blue.coordination.fastpath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies an authoritative commit delta without re-projecting the full Root.
 * Dependency evidence is mandatory for every affected retained occurrence;
 * otherwise this path fails closed and the caller must use the cold semantic
 * projector. It never silently assumes an unchanged header.
 */
public final class DeltaProjectionApplier {
    private final FastPathWorkMetrics metrics;

    public DeltaProjectionApplier() {
        this(new FastPathWorkMetrics());
    }

    public DeltaProjectionApplier(FastPathWorkMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public AdmittedProjection apply(
            AdmittedProjection previous,
            ProjectionGenerationKey resultingGeneration,
            ProjectionDelta delta) {
        AdmittedProjection prior = Objects.requireNonNull(previous, "previous");
        ProjectionGenerationKey generation = Objects.requireNonNull(
                resultingGeneration, "resultingGeneration");
        ProjectionDelta exact = Objects.requireNonNull(delta, "delta");
        requireSuccessor(prior.generation(), generation);

        Set<String> affected = prior.affectedOccurrences(exact.changedPaths());
        Map<String, AdmittedOccurrence> refreshed = index(exact.refreshed());
        Set<String> removed = exact.retiredPublicKeys();
        if (!exact.dependencyEvidenceComplete()) {
            throw new ColdProjectionRequiredException(
                    "platform delta does not carry complete retained dependency evidence");
        }
        Set<String> missingEvidence = new LinkedHashSet<String>(affected);
        missingEvidence.removeAll(removed);
        missingEvidence.removeAll(refreshed.keySet());
        if (!missingEvidence.isEmpty()) {
            throw new ColdProjectionRequiredException(
                    "changed paths affect retained occurrences without refreshed evidence: "
                            + missingEvidence);
        }

        for (String retired : removed) {
            if (prior.findPublic(retired) == null) {
                throw new IllegalArgumentException(
                        "delta retires inactive occurrence: " + retired);
            }
        }

        PathDependencyIndex dependencyIndex =
                prior.dependencyIndexForSuccessor();
        for (String retired : removed) {
            AdmittedOccurrence old = prior.findPublic(retired);
            dependencyIndex = dependencyIndex.updated(
                    retired,
                    old.dependencyPaths(),
                    java.util.Collections.<String>emptySet());
        }

        for (AdmittedOccurrence replacement : exact.refreshed()) {
            AdmittedOccurrence old = prior.findPublic(
                    replacement.publicKey());
            if (old == null) {
                throw new IllegalArgumentException(
                        "delta refreshes inactive occurrence: "
                                + replacement.publicKey());
            }
            dependencyIndex = dependencyIndex.updated(
                    replacement.publicKey(),
                    old.dependencyPaths(),
                    replacement.dependencyPaths());
        }
        for (AdmittedOccurrence addition : exact.added()) {
            if (prior.findPublic(addition.publicKey()) != null) {
                throw new IllegalArgumentException(
                        "delta adds active occurrence: " + addition.publicKey());
            }
            dependencyIndex = dependencyIndex.updated(
                    addition.publicKey(),
                    java.util.Collections.<String>emptySet(),
                    addition.dependencyPaths());
        }
        AdmittedProjection result = prior.successor(
                generation,
                removed,
                exact.refreshed(),
                exact.added(),
                dependencyIndex);
        Set<String> lookedUpPrior = new LinkedHashSet<String>(affected);
        lookedUpPrior.addAll(removed);
        lookedUpPrior.addAll(refreshed.keySet());
        metrics.candidatesLookedUp(
                lookedUpPrior.size() + exact.added().size());
        metrics.deltaProjectionUpdated(
                affected.size(),
                exact.refreshed().size(),
                0L);
        metrics.merkleOccurrencesUpdated(
                removed.size()
                        + exact.refreshed().size()
                        + exact.added().size());
        return result;
    }

    private static Map<String, AdmittedOccurrence> index(
            Collection<AdmittedOccurrence> values) {
        Map<String, AdmittedOccurrence> result = new LinkedHashMap<String, AdmittedOccurrence>();
        for (AdmittedOccurrence value : values) {
            if (result.put(value.publicKey(), value) != null) {
                throw new IllegalArgumentException(
                        "duplicate refreshed occurrence: " + value.publicKey());
            }
        }
        return result;
    }

    private static void requireSuccessor(
            ProjectionGenerationKey previous,
            ProjectionGenerationKey resulting) {
        List<String> differences = new ArrayList<String>();
        if (!previous.environmentIdentity().equals(resulting.environmentIdentity())) {
            differences.add("environmentIdentity");
        }
        if (!previous.runtimeIdentity().equals(resulting.runtimeIdentity())) differences.add("runtimeIdentity");
        if (resulting.rootRevision() != previous.rootRevision() + 1L) differences.add("rootRevision");
        if (previous.rootBlueId().equals(resulting.rootBlueId())) differences.add("rootBlueId");
        if (previous.inventoryIdentity().equals(resulting.inventoryIdentity())) differences.add("inventoryIdentity");
        if (previous.subscriptionDigest().equals(resulting.subscriptionDigest())) differences.add("subscriptionDigest");
        if (!differences.isEmpty()) {
            throw new IllegalArgumentException(
                    "resulting projection generation is not an exact successor: " + differences);
        }
    }

    /** Signals a deliberate semantic fallback, never a partial fast result. */
    public static final class ColdProjectionRequiredException
            extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public ColdProjectionRequiredException(String message) { super(message); }
    }
}
