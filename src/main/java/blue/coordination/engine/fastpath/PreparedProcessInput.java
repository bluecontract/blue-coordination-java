package blue.coordination.engine.fastpath;

import blue.language.provider.NodeProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact request-local provider plus precomputed accounting. */
public final class PreparedProcessInput {
    private final String rootInventoryIdentity;
    private final String eventInventoryIdentity;
    private final Map<String, ExactNodeHandle> handles;
    private final Set<String> selectedBlueIds;
    private final long encodedBytes;

    PreparedProcessInput(
            String rootInventoryIdentity,
            String eventInventoryIdentity,
            Map<String, ExactNodeHandle> handles,
            Set<String> selectedBlueIds,
            long encodedBytes) {
        this.rootInventoryIdentity = Objects.requireNonNull(
                rootInventoryIdentity, "rootInventoryIdentity");
        this.eventInventoryIdentity = Objects.requireNonNull(
                eventInventoryIdentity, "eventInventoryIdentity");
        this.handles = Collections.unmodifiableMap(
                new LinkedHashMap<String, ExactNodeHandle>(handles));
        this.selectedBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<String>(selectedBlueIds));
        if (encodedBytes < 0L) {
            throw new IllegalArgumentException(
                    "encodedBytes must be non-negative");
        }
        this.encodedBytes = encodedBytes;
    }

    public PreparedRequestNodeProvider newProvider() {
        return new PreparedRequestNodeProvider(handles);
    }

    /** Creates the strict, locality-observable provider used by PROCESS. */
    public PreparedRequestNodeProvider newProvider(
            NodeProvider runtimeProvider,
            Collection<String> knownFragmentBlueIds,
            Collection<String> allowedFragmentBlueIds,
            Collection<String> externallyManagedReferenceBlueIds,
            int batchCount) {
        return newProvider(
                runtimeProvider,
                knownFragmentBlueIds,
                allowedFragmentBlueIds,
                externallyManagedReferenceBlueIds,
                handles,
                batchCount);
    }

    /**
     * Creates a provider with lazily materialized, admission-verified handles
     * for every locally allowed identity. Only {@code handles} contribute to
     * initial bundle accounting; an allowed handle is copied at most once if
     * frozen PROCESS actually requests it.
     */
    public PreparedRequestNodeProvider newProvider(
            NodeProvider runtimeProvider,
            Collection<String> knownFragmentBlueIds,
            Collection<String> allowedFragmentBlueIds,
            Collection<String> externallyManagedReferenceBlueIds,
            Map<String, ExactNodeHandle> availableHandles,
            int batchCount) {
        return new PreparedRequestNodeProvider(
                handles,
                selectedBlueIds,
                availableHandles,
                runtimeProvider,
                knownFragmentBlueIds,
                allowedFragmentBlueIds,
                externallyManagedReferenceBlueIds,
                batchCount,
                encodedBytes);
    }

    public String rootInventoryIdentity() { return rootInventoryIdentity; }
    public String eventInventoryIdentity() { return eventInventoryIdentity; }
    public Set<String> selectedBlueIds() { return selectedBlueIds; }
    public int identityCount() { return handles.size(); }
    public long encodedBytes() { return encodedBytes; }
}
