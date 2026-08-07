package blue.coordination.engine.internal;

import blue.coordination.engine.api.LocalityDiagnostics;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationLocalityDiagnosticsProvider;
import blue.language.api.NodeProviderOutcome;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Request-local strict fragment boundary with a verified runtime fallback. */
public final class RequestLocalNodeProvider
        implements CoordinationLocalityDiagnosticsProvider {

    private final CoordinationFragmentStore fragmentStore;
    private final NodeProvider runtimeProvider;
    private final NodeProvider selectedFragmentProvider;
    private final NodeProvider fallbackFragmentProvider;
    private final Map<String, NodeProviderResult> prefetched;
    private final Set<String> allowedFragmentBlueIds;
    private final Set<String> knownFragmentBlueIds;
    private final Set<String> externallyManagedReferenceBlueIds;
    private final int batchCount;
    private final long initialLoadedBytes;
    private final List<String> requests = new ArrayList<String>();
    private final Set<String> backendLoaded = new LinkedHashSet<String>();
    private final Map<String, NodeProviderResult> fallbackResults =
            new LinkedHashMap<String, NodeProviderResult>();
    private final Map<String, NodeProviderResult> externalResults =
            new LinkedHashMap<String, NodeProviderResult>();
    private final Set<String> usedPrefetch = new LinkedHashSet<String>();
    private final Set<String> causallySelected = new LinkedHashSet<String>();
    private int fallbackReadCount;
    private int forbiddenReadCount;
    private long fallbackLoadedBytes;

    public RequestLocalNodeProvider(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            Map<String, NodeProviderResult> prefetched,
            Set<String> allowedFragmentBlueIds,
            Set<String> knownFragmentBlueIds,
            int batchCount,
            long initialLoadedBytes) {
        this(fragmentStore,
                runtimeProvider,
                prefetched,
                allowedFragmentBlueIds,
                knownFragmentBlueIds,
                batchCount,
                initialLoadedBytes,
                requestedBlueId -> prefetched.get(requestedBlueId) != null
                        ? prefetched.get(requestedBlueId).nodes()
                        : Collections.<Node>emptyList(),
                fragmentStore);
    }

    public RequestLocalNodeProvider(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            Map<String, NodeProviderResult> prefetched,
            Set<String> allowedFragmentBlueIds,
            Set<String> knownFragmentBlueIds,
            int batchCount,
            long initialLoadedBytes,
            NodeProvider selectedFragmentProvider) {
        this(fragmentStore,
                runtimeProvider,
                prefetched,
                allowedFragmentBlueIds,
                knownFragmentBlueIds,
                batchCount,
                initialLoadedBytes,
                selectedFragmentProvider,
                fragmentStore);
    }

    public RequestLocalNodeProvider(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            Map<String, NodeProviderResult> prefetched,
            Set<String> allowedFragmentBlueIds,
            Set<String> knownFragmentBlueIds,
            int batchCount,
            long initialLoadedBytes,
            NodeProvider selectedFragmentProvider,
            NodeProvider fallbackFragmentProvider) {
        this(fragmentStore,
                runtimeProvider,
                prefetched,
                allowedFragmentBlueIds,
                knownFragmentBlueIds,
                batchCount,
                initialLoadedBytes,
                selectedFragmentProvider,
                fallbackFragmentProvider,
                foundBlueIds(prefetched),
                Collections.<String>emptySet());
    }

    public RequestLocalNodeProvider(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            Map<String, NodeProviderResult> prefetched,
            Set<String> allowedFragmentBlueIds,
            Set<String> knownFragmentBlueIds,
            int batchCount,
            long initialLoadedBytes,
            NodeProvider selectedFragmentProvider,
            NodeProvider fallbackFragmentProvider,
            Collection<String> initiallyBackendLoadedBlueIds) {
        this(fragmentStore,
                runtimeProvider,
                prefetched,
                allowedFragmentBlueIds,
                knownFragmentBlueIds,
                batchCount,
                initialLoadedBytes,
                selectedFragmentProvider,
                fallbackFragmentProvider,
                initiallyBackendLoadedBlueIds,
                Collections.<String>emptySet());
    }

    public RequestLocalNodeProvider(
            CoordinationFragmentStore fragmentStore,
            NodeProvider runtimeProvider,
            Map<String, NodeProviderResult> prefetched,
            Set<String> allowedFragmentBlueIds,
            Set<String> knownFragmentBlueIds,
            int batchCount,
            long initialLoadedBytes,
            NodeProvider selectedFragmentProvider,
            NodeProvider fallbackFragmentProvider,
            Collection<String> initiallyBackendLoadedBlueIds,
            Collection<String> externallyManagedReferenceBlueIds) {
        this.fragmentStore = Objects.requireNonNull(
                fragmentStore, "fragmentStore");
        this.runtimeProvider = Objects.requireNonNull(
                runtimeProvider, "runtimeProvider");
        this.selectedFragmentProvider = Objects.requireNonNull(
                selectedFragmentProvider, "selectedFragmentProvider");
        this.fallbackFragmentProvider = Objects.requireNonNull(
                fallbackFragmentProvider, "fallbackFragmentProvider");
        this.prefetched = Collections.unmodifiableMap(
                new LinkedHashMap<String, NodeProviderResult>(
                        Objects.requireNonNull(prefetched, "prefetched")));
        this.allowedFragmentBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        allowedFragmentBlueIds, "allowedFragmentBlueIds")));
        this.knownFragmentBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(Objects.requireNonNull(
                        knownFragmentBlueIds, "knownFragmentBlueIds")));
        Set<String> externalReferences = new LinkedHashSet<String>(
                Objects.requireNonNull(
                        externallyManagedReferenceBlueIds,
                        "externallyManagedReferenceBlueIds"));
        for (String externalReference : externalReferences) {
            if (externalReference == null || externalReference.isEmpty()) {
                throw new IllegalArgumentException(
                        "External reference identity must be non-empty");
            }
            if (!this.allowedFragmentBlueIds.contains(externalReference)) {
                throw new IllegalArgumentException(
                        "External reference is outside the admitted scope: "
                                + externalReference);
            }
        }
        this.externallyManagedReferenceBlueIds =
                Collections.unmodifiableSet(externalReferences);
        if (batchCount < 0 || initialLoadedBytes < 0L) {
            throw new IllegalArgumentException(
                    "Load counters must be non-negative");
        }
        this.batchCount = batchCount;
        this.initialLoadedBytes = initialLoadedBytes;
        backendLoaded.addAll(Objects.requireNonNull(
                initiallyBackendLoadedBlueIds,
                "initiallyBackendLoadedBlueIds"));
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        NodeProviderResult result = fetchResultByBlueId(blueId);
        return result.outcome() == NodeProviderOutcome.FOUND
                ? result.nodes()
                : Collections.<Node>emptyList();
    }

    @Override
    public synchronized NodeProviderResult fetchResultByBlueId(
            String blueId) {
        String identity = Objects.requireNonNull(blueId, "blueId");
        requests.add(identity);
        NodeProviderResult ready = prefetched.get(identity);
        if (ready != null) {
            usedPrefetch.add(identity);
            if (ready.outcome() != NodeProviderOutcome.FOUND) {
                /* Only an authored, provenance-checked external reference may
                 * escape a conclusive batch miss to the verified runtime
                 * provider. Admitted inventory members, store outages, and
                 * invalid evidence remain authoritative and fail closed. */
                if (ready.outcome() == NodeProviderOutcome.NOT_FOUND
                        && externallyManagedReferenceBlueIds.contains(
                                identity)) {
                    return resolveExternal(identity, ready);
                }
                return copy(ready);
            }
            NodeProviderResult selected =
                    selectedFragmentProvider.fetchResultByBlueId(identity);
            NodeProviderResult result = selected != null
                    ? copy(selected)
                    : copy(ready);
            if (result.outcome() == NodeProviderOutcome.FOUND
                    && result.nodes().size() == 1
                    && result.nodes().get(0).isReferenceOnly()
                    && externallyManagedReferenceBlueIds.contains(identity)) {
                return resolveExternal(identity, result);
            }
            return result;
        }
        if (knownFragmentBlueIds.contains(identity)) {
            if (!allowedFragmentBlueIds.contains(identity)) {
                forbiddenReadCount++;
                return NodeProviderResult.invalidEvidence(
                        "Fragment demand is outside the selected scope "
                                + "boundary: " + identity);
            }
            causallySelected.add(identity);
            NodeProviderResult memoized = fallbackResults.get(identity);
            if (memoized != null) {
                return copy(memoized);
            }
            fallbackReadCount++;
            NodeProviderResult loaded =
                    fallbackFragmentProvider.fetchResultByBlueId(identity);
            NodeProviderResult retained = loaded == null
                    ? NodeProviderResult.notFound()
                    : copy(loaded);
            recordLoaded(identity, retained);
            NodeProviderResult served = resolveExternal(identity, retained);
            fallbackResults.put(identity, served);
            return copy(served);
        }
        return runtimeProvider.fetchResultByBlueId(identity);
    }

    private NodeProviderResult resolveExternal(
            String identity,
            NodeProviderResult inventoryResult) {
        if (!externallyManagedReferenceBlueIds.contains(identity)
                || !isMissingOrReference(inventoryResult)) {
            return copy(inventoryResult);
        }
        NodeProviderResult memoized = externalResults.get(identity);
        if (memoized != null) return copy(memoized);
        NodeProviderResult semantic =
                runtimeProvider.fetchResultByBlueId(identity);
        NodeProviderResult resolved = semantic != null
                && semantic.outcome() == NodeProviderOutcome.FOUND
                && semantic.nodes().size() == 1
                && !semantic.nodes().get(0).isReferenceOnly()
                ? copy(semantic)
                : copy(inventoryResult);
        externalResults.put(identity, resolved);
        return copy(resolved);
    }

    private static boolean isMissingOrReference(NodeProviderResult result) {
        return result.outcome() == NodeProviderOutcome.NOT_FOUND
                || (result.outcome() == NodeProviderOutcome.FOUND
                && result.nodes().size() == 1
                && result.nodes().get(0).isReferenceOnly());
    }

    @Override
    public synchronized LocalityDiagnostics diagnostics() {
        List<String> unused = new ArrayList<String>(prefetched.keySet());
        unused.removeAll(usedPrefetch);
        return new LocalityDiagnostics(
                requests,
                backendLoaded,
                batchCount,
                fallbackReadCount,
                initialLoadedBytes + fallbackLoadedBytes,
                unused,
                causallySelected,
                forbiddenReadCount);
    }

    private void recordLoaded(String identity, NodeProviderResult result) {
        if (result.outcome() != NodeProviderOutcome.FOUND) return;
        backendLoaded.add(identity);
        for (Node node : result.nodes()) {
            fallbackLoadedBytes += bytes(node);
        }
    }

    private static NodeProviderResult copy(NodeProviderResult result) {
        switch (result.outcome()) {
            case FOUND:
                return NodeProviderResult.found(result.nodes());
            case NOT_FOUND:
                return NodeProviderResult.notFound();
            case UNAVAILABLE:
                return NodeProviderResult.unavailable(
                        result.diagnostic().orElse(null));
            case INVALID_EVIDENCE:
                return NodeProviderResult.invalidEvidence(
                        result.diagnostic().orElse(null));
            default:
                throw new IllegalStateException(
                        "Unknown provider outcome " + result.outcome());
        }
    }

    private static Collection<String> foundBlueIds(
            Map<String, NodeProviderResult> results) {
        List<String> found = new ArrayList<String>();
        for (Map.Entry<String, NodeProviderResult> entry
                : Objects.requireNonNull(results, "prefetched").entrySet()) {
            if (entry.getValue().outcome() == NodeProviderOutcome.FOUND) {
                found.add(entry.getKey());
            }
        }
        return found;
    }

    public static long bytes(Node node) {
        return UncheckedObjectMapper.JSON_MAPPER
                .writeValueAsString(NodeWireForm.get(node))
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }
}
