package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.spi.CoordinationLocalityDiagnosticsProvider;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

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
 * Request-local exact provider built from prepared handles. Each selected
 * body is copied once when the request starts, then repeated provider lookups
 * return the same request-owned immutable-by-convention body. No backend
 * access, hashing, wire serialization, or repeated cloning occurs.
 */
public final class PreparedRequestNodeProvider
        implements CoordinationLocalityDiagnosticsProvider {
    private final Map<String, List<Node>> exact;
    private final Map<String, ExactNodeHandle> availableHandles;
    private final Set<String> initiallyLoaded;
    private final Set<String> selected;
    private final NodeProvider runtimeProvider;
    private final Set<String> knownFragments;
    private final Set<String> allowedFragments;
    private final Set<String> externalReferences;
    private final int batchCount;
    private final long loadedBytes;
    private final Set<String> requested = new LinkedHashSet<String>();
    private final Set<String> missed = new LinkedHashSet<String>();
    private final Set<String> used = new LinkedHashSet<String>();
    private final Map<String, RuntimeResolution> externalResults =
            new LinkedHashMap<String, RuntimeResolution>();
    private int forbiddenReadCount;

    public PreparedRequestNodeProvider(
            Map<String, ExactNodeHandle> selectedHandles) {
        this(
                selectedHandles,
                selectedHandles.keySet(),
                selectedHandles,
                null,
                selectedHandles.keySet(),
                selectedHandles.keySet(),
                Collections.<String>emptySet(),
                0,
                0L);
    }

    public PreparedRequestNodeProvider(
            Map<String, ExactNodeHandle> selectedHandles,
            Collection<String> selectedBlueIds,
            NodeProvider runtimeProvider,
            Collection<String> knownFragmentBlueIds,
            Collection<String> allowedFragmentBlueIds,
            Collection<String> externallyManagedReferenceBlueIds,
            int batchCount,
            long loadedBytes) {
        this(
                selectedHandles,
                selectedBlueIds,
                selectedHandles,
                runtimeProvider,
                knownFragmentBlueIds,
                allowedFragmentBlueIds,
                externallyManagedReferenceBlueIds,
                batchCount,
                loadedBytes);
    }

    public PreparedRequestNodeProvider(
            Map<String, ExactNodeHandle> selectedHandles,
            Collection<String> selectedBlueIds,
            Map<String, ExactNodeHandle> availableHandles,
            NodeProvider runtimeProvider,
            Collection<String> knownFragmentBlueIds,
            Collection<String> allowedFragmentBlueIds,
            Collection<String> externallyManagedReferenceBlueIds,
            int batchCount,
            long loadedBytes) {
        Map<String, List<Node>> values =
                new LinkedHashMap<String, List<Node>>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : Objects.requireNonNull(
                        selectedHandles, "selectedHandles").entrySet()) {
            if (!entry.getKey().equals(entry.getValue().blueId())) {
                throw new IllegalArgumentException(
                        "Provider key/handle mismatch for " + entry.getKey());
            }
            Node requestCopy = entry.getValue().copy();
            values.put(
                    entry.getKey(),
                    Collections.singletonList(requestCopy));
        }
        this.exact = values;
        this.initiallyLoaded = Collections.unmodifiableSet(
                new LinkedHashSet<String>(values.keySet()));
        Map<String, ExactNodeHandle> available =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (Map.Entry<String, ExactNodeHandle> entry
                : Objects.requireNonNull(
                        availableHandles,
                        "availableHandles").entrySet()) {
            if (!entry.getKey().equals(entry.getValue().blueId())) {
                throw new IllegalArgumentException(
                        "Available provider key/handle mismatch for "
                                + entry.getKey());
            }
            available.put(entry.getKey(), entry.getValue());
        }
        if (!available.keySet().containsAll(values.keySet())) {
            throw new IllegalArgumentException(
                    "Available handles do not cover the selected bundle");
        }
        this.availableHandles = Collections.unmodifiableMap(available);
        this.selected = immutableTextSet(
                selectedBlueIds, "selectedBlueIds");
        this.runtimeProvider = runtimeProvider;
        this.knownFragments = immutableTextSet(
                knownFragmentBlueIds, "knownFragmentBlueIds");
        this.allowedFragments = immutableTextSet(
                allowedFragmentBlueIds, "allowedFragmentBlueIds");
        this.externalReferences = immutableTextSet(
                externallyManagedReferenceBlueIds,
                "externallyManagedReferenceBlueIds");
        if (!this.allowedFragments.containsAll(this.exact.keySet())
                || !this.allowedFragments.containsAll(
                        this.availableHandles.keySet())
                || !this.selected.containsAll(this.exact.keySet())
                || !this.allowedFragments.containsAll(
                        this.externalReferences)
                || batchCount < 0
                || loadedBytes < 0L) {
            throw new IllegalArgumentException(
                    "Prepared provider bindings are inconsistent");
        }
        this.batchCount = batchCount;
        this.loadedBytes = loadedBytes;
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        String identity = Objects.requireNonNull(blueId, "blueId");
        requested.add(identity);
        List<Node> result = prepared(identity);
        if (result != null) {
            used.add(identity);
            // This provider is request-local. The frozen invocation treats
            // its candidates as immutable, so the direct API can reuse the
            // one request-owned materialization.
            return result;
        }
        missed.add(identity);
        if (selected.contains(identity)) used.add(identity);
        if (externalReferences.contains(identity)) {
            return resolveExternal(identity).nodes;
        }
        if (knownFragments.contains(identity)) {
            forbiddenReadCount++;
            return Collections.emptyList();
        }
        return runtimeProvider == null
                ? Collections.<Node>emptyList()
                : runtimeProvider.fetchByBlueId(identity);
    }

    @Override
    public synchronized NodeProviderResult fetchResultByBlueId(
            String blueId) {
        String identity = Objects.requireNonNull(blueId, "blueId");
        requested.add(identity);
        List<Node> result = prepared(identity);
        if (result != null) {
            used.add(identity);
            return NodeProviderResult.found(result);
        }
        missed.add(identity);
        if (selected.contains(identity)) used.add(identity);
        if (externalReferences.contains(identity)) {
            return resolveExternal(identity).portable();
        }
        if (knownFragments.contains(identity)) {
            forbiddenReadCount++;
            return NodeProviderResult.invalidEvidence(
                    "Fragment demand is outside the prepared selected "
                            + "bundle: " + identity);
        }
        return runtimeProvider == null
                ? NodeProviderResult.notFound()
                : runtimeProvider.fetchResultByBlueId(identity);
    }

    public synchronized Set<String> requestedBlueIds() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(requested));
    }

    public synchronized Set<String> missedBlueIds() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(missed));
    }

    public int loadedIdentityCount() {
        return initiallyLoaded.size();
    }

    public synchronized List<String> loadedBlueIds() {
        return Collections.unmodifiableList(
                new ArrayList<String>(initiallyLoaded));
    }

    @Override
    public synchronized LocalityDiagnostics diagnostics() {
        List<String> unused = new ArrayList<String>(selected);
        unused.removeAll(used);
        return new LocalityDiagnostics(
                requested,
                initiallyLoaded,
                batchCount,
                0,
                loadedBytes,
                unused,
                Collections.<String>emptyList(),
                forbiddenReadCount);
    }

    private List<Node> prepared(String identity) {
        List<Node> ready = exact.get(identity);
        if (ready != null) return ready;
        ExactNodeHandle handle = availableHandles.get(identity);
        if (handle == null) return null;
        List<Node> materialized = Collections.singletonList(handle.copy());
        exact.put(identity, materialized);
        return materialized;
    }

    private RuntimeResolution resolveExternal(String identity) {
        RuntimeResolution ready = externalResults.get(identity);
        if (ready != null) return ready;
        if (runtimeProvider == null) {
            ready = RuntimeResolution.notFound();
        } else {
            NodeProviderResult result = runtimeProvider
                    .fetchResultByBlueId(identity);
            ready = RuntimeResolution.from(result == null
                    ? NodeProviderResult.notFound()
                    : result);
        }
        externalResults.put(identity, ready);
        return ready;
    }

    private static Set<String> immutableTextSet(
            Collection<String> source, String label) {
        Set<String> result = new LinkedHashSet<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " entries must be non-empty");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static final class RuntimeResolution {
        private final NodeProviderOutcome outcome;
        private final List<Node> nodes;
        private final String diagnostic;

        private RuntimeResolution(
                NodeProviderOutcome outcome,
                List<Node> nodes,
                String diagnostic) {
            this.outcome = outcome;
            this.nodes = Collections.unmodifiableList(
                    new ArrayList<Node>(nodes));
            this.diagnostic = diagnostic;
        }

        private static RuntimeResolution from(NodeProviderResult result) {
            return new RuntimeResolution(
                    result.outcome(),
                    result.outcome() == NodeProviderOutcome.FOUND
                            ? result.nodes()
                            : Collections.<Node>emptyList(),
                    result.diagnostic().orElse(null));
        }

        private static RuntimeResolution notFound() {
            return new RuntimeResolution(
                    NodeProviderOutcome.NOT_FOUND,
                    Collections.<Node>emptyList(),
                    null);
        }

        private NodeProviderResult portable() {
            switch (outcome) {
                case FOUND:
                    return NodeProviderResult.found(nodes);
                case NOT_FOUND:
                    return NodeProviderResult.notFound();
                case UNAVAILABLE:
                    return NodeProviderResult.unavailable(diagnostic);
                case INVALID_EVIDENCE:
                    return NodeProviderResult.invalidEvidence(diagnostic);
                default:
                    throw new IllegalStateException(
                            "Unknown provider outcome " + outcome);
            }
        }
    }
}
