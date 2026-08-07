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

        Map<String, AdmittedOccurrence> result = new LinkedHashMap<String, AdmittedOccurrence>();
        for (AdmittedOccurrence occurrence : prior.occurrences()) {
            String key = occurrence.publicKey();
            if (removed.contains(key)) continue;
            AdmittedOccurrence replacement = refreshed.remove(key);
            result.put(key, replacement != null ? replacement : occurrence);
        }
        if (!refreshed.isEmpty()) {
            throw new IllegalArgumentException(
                    "delta refreshes inactive occurrence(s): " + refreshed.keySet());
        }
        for (AdmittedOccurrence addition : exact.added()) {
            if (result.put(addition.publicKey(), addition) != null) {
                throw new IllegalArgumentException(
                        "delta adds active occurrence: " + addition.publicKey());
            }
        }
        for (String retired : removed) {
            boolean existed = false;
            for (AdmittedOccurrence occurrence : prior.occurrences()) {
                if (retired.equals(occurrence.publicKey())) {
                    existed = true;
                    break;
                }
            }
            if (!existed) {
                throw new IllegalArgumentException(
                        "delta retires inactive occurrence: " + retired);
            }
        }
        return new AdmittedProjection(generation, result.values());
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
        if (!previous.sessionId().equals(resulting.sessionId())) differences.add("sessionId");
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
