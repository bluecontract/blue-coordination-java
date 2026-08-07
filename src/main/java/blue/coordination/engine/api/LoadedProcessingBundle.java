package blue.coordination.engine.api;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact request-local PROCESS provider plus deterministic load diagnostics. */
public final class LoadedProcessingBundle {

    private final NodeProvider exactProvider;
    private final Set<String> backendLoadedBlueIds;
    private final List<String> prefetchedBlueIds;
    private final int batchCount;
    private final long loadedBytes;
    private final ProcessingBundlePlanBinding planBinding;

    /**
     * Retains the original binary surface for diagnostic-only loaders.
     * Bundles built this way are intentionally unbound and cannot be executed
     * by {@code CoordinationProcessingEngine}.
     */
    public LoadedProcessingBundle(
            NodeProvider exactProvider,
            Collection<String> backendLoadedBlueIds,
            Collection<String> prefetchedBlueIds,
            int batchCount,
            long loadedBytes) {
        this(
                exactProvider,
                backendLoadedBlueIds,
                prefetchedBlueIds,
                batchCount,
                loadedBytes,
                null);
    }

    /** Creates a request-local bundle bound to one exact immutable plan. */
    public LoadedProcessingBundle(
            NodeProvider exactProvider,
            Collection<String> backendLoadedBlueIds,
            Collection<String> prefetchedBlueIds,
            int batchCount,
            long loadedBytes,
            ProcessingBundlePlanBinding planBinding) {
        this.exactProvider = Objects.requireNonNull(
                exactProvider, "exactProvider");
        this.backendLoadedBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(immutableText(
                        backendLoadedBlueIds, "backendLoadedBlueIds")));
        this.prefetchedBlueIds = immutableText(
                prefetchedBlueIds, "prefetchedBlueIds");
        if (batchCount < 0 || loadedBytes < 0L) {
            throw new IllegalArgumentException(
                    "batchCount and loadedBytes must be non-negative");
        }
        this.batchCount = batchCount;
        this.loadedBytes = loadedBytes;
        this.planBinding = planBinding;
    }

    public NodeProvider exactProvider() { return exactProvider; }
    public Set<String> backendLoadedBlueIds() {
        return backendLoadedBlueIds;
    }
    public List<String> prefetchedBlueIds() { return prefetchedBlueIds; }
    public int batchCount() { return batchCount; }
    public long loadedBytes() { return loadedBytes; }
    public Optional<ProcessingBundlePlanBinding> planBinding() {
        return Optional.ofNullable(planBinding);
    }

    private static List<String> immutableText(
            Collection<String> source,
            String label) {
        List<String> result = new ArrayList<String>(
                Objects.requireNonNull(source, label));
        for (String value : result) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        label + " entries must be non-empty");
            }
        }
        return Collections.unmodifiableList(result);
    }
}
