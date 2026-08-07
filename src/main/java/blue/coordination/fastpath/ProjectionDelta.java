package blue.coordination.fastpath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Exact, fail-closed changes to an admitted projection generation. */
public final class ProjectionDelta {
    private final List<AdmittedOccurrence> added;
    private final Set<String> retiredPublicKeys;
    private final List<AdmittedOccurrence> refreshed;
    private final Set<String> changedPaths;
    private final boolean dependencyEvidenceComplete;

    public ProjectionDelta(
            Collection<AdmittedOccurrence> added,
            Collection<String> retiredPublicKeys,
            Collection<AdmittedOccurrence> refreshed,
            Collection<String> changedPaths,
            boolean dependencyEvidenceComplete) {
        this.added = immutableOccurrences(added, "added");
        this.retiredPublicKeys = immutableKeys(retiredPublicKeys, "retiredPublicKey");
        this.refreshed = immutableOccurrences(refreshed, "refreshed");
        this.changedPaths = immutablePaths(changedPaths);
        this.dependencyEvidenceComplete = dependencyEvidenceComplete;
        Set<String> writes = new LinkedHashSet<String>();
        for (AdmittedOccurrence value : this.added) {
            if (!writes.add(value.publicKey())) duplicate(value.publicKey());
        }
        for (String value : this.retiredPublicKeys) {
            if (!writes.add(value)) duplicate(value);
        }
        for (AdmittedOccurrence value : this.refreshed) {
            if (!writes.add(value.publicKey())) duplicate(value.publicKey());
        }
    }

    public List<AdmittedOccurrence> added() { return added; }
    public Set<String> retiredPublicKeys() { return retiredPublicKeys; }
    public List<AdmittedOccurrence> refreshed() { return refreshed; }
    public Set<String> changedPaths() { return changedPaths; }
    public boolean dependencyEvidenceComplete() { return dependencyEvidenceComplete; }

    private static List<AdmittedOccurrence> immutableOccurrences(
            Collection<AdmittedOccurrence> values, String name) {
        List<AdmittedOccurrence> result = new ArrayList<AdmittedOccurrence>(
                Objects.requireNonNull(values, name));
        for (AdmittedOccurrence value : result) Objects.requireNonNull(value, name + " value");
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static Set<String> immutableKeys(Collection<String> values, String name) {
        List<String> ordered = new ArrayList<String>(
                Objects.requireNonNull(values, name));
        for (String value : ordered) AdmittedOccurrence.text(value, name);
        Collections.sort(ordered, AdmittedOccurrence::codePointCompare);
        Set<String> unique = new LinkedHashSet<String>(ordered);
        if (unique.size() != ordered.size()) throw new IllegalArgumentException("duplicate " + name);
        return Collections.unmodifiableSet(unique);
    }

    private static Set<String> immutablePaths(Collection<String> paths) {
        return AdmittedOccurrence.canonicalPathSet(paths);
    }

    private static void duplicate(String key) {
        throw new IllegalArgumentException("delta writes occurrence more than once: " + key);
    }
}
