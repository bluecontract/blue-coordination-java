package blue.coordination.engine.spi;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Storage-neutral immutable fragment and inventory boundary. */
public interface CoordinationFragmentStore
        extends NodeProvider,
        CoordinationFragmentAdmissionVerifier.AtomicImmutableFragmentStore {

    /** Returns the single physical fragmentation-profile namespace. */
    String fragmentationProfileIdentity();

    /**
     * Returns the exact immutable storage generation/authority used by this
     * store instance.
     *
     * <p>Derived kernels and admission evidence must not be shared merely
     * because two stores have the same implementation class and profile. The
     * compatibility default therefore fails closed; portable stores opt in by
     * supplying an identifier which changes whenever their canonical content
     * authority changes.</p>
     */
    default String storageGenerationAuthority() {
        throw new IllegalStateException(
                "CoordinationFragmentStore must expose an exact storage "
                        + "generation authority");
    }

    /** Reads exact outcomes for every requested identity. */
    Map<String, NodeProviderResult> readAll(Collection<String> blueIds);

    /**
     * Reads the identity-equivalent PROCESS representation for every
     * requested identity in one storage round trip.
     *
     * <p>A retained PROCESS header view is preferred over the canonical
     * physical fragment.  This is the batch counterpart of the ordinary
     * {@link NodeProvider} surface.  Keeping it distinct from
     * {@link #readAll(Collection)} prevents reconstruction and integrity
     * checks from accidentally consuming non-physical views. Existing store
     * implementations retain a binary-compatible canonical fallback; stores
     * that persist PROCESS views override this method.</p>
     */
    default Map<String, NodeProviderResult> readProcessingAll(
            Collection<String> blueIds) {
        return readAll(blueIds);
    }

    /** Reads PROCESS views bound to one exact fragment inventory. */
    default Map<String, NodeProviderResult> readProcessingAll(
            String inventoryIdentity,
            Collection<String> blueIds) {
        CoordinationFragmentInventory inventory = requireInventory(
                Objects.requireNonNull(
                        inventoryIdentity, "inventoryIdentity"));
        Collection<String> requested = Objects.requireNonNull(
                blueIds, "blueIds");
        Set<String> members = new HashSet<String>(
                inventory.fragmentBlueIds());
        List<String> admitted = new ArrayList<String>();
        for (String blueId : requested) {
            if (members.contains(Objects.requireNonNull(blueId, "blueId"))) {
                admitted.add(blueId);
            }
        }
        Map<String, NodeProviderResult> canonical = readAll(admitted);
        Map<String, NodeProviderResult> result =
                new LinkedHashMap<String, NodeProviderResult>();
        for (String blueId : requested) {
            NodeProviderResult value = members.contains(blueId)
                    ? canonical.get(blueId)
                    : null;
            result.put(blueId, value == null
                    ? NodeProviderResult.notFound()
                    : value);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Reads one canonical physical representation. */
    default NodeProviderResult readCanonical(String blueId) {
        NodeProviderResult result = readAll(
                Collections.singletonList(blueId)).get(blueId);
        return result == null ? NodeProviderResult.notFound() : result;
    }

    /** Reads one PROCESS view bound to one exact fragment inventory. */
    default NodeProviderResult readProcessing(
            String inventoryIdentity,
            String blueId) {
        NodeProviderResult result = readProcessingAll(
                inventoryIdentity,
                Collections.singletonList(blueId)).get(blueId);
        return result == null ? NodeProviderResult.notFound() : result;
    }

    /**
     * Reads both identity-equivalent PROCESS views and canonical physical
     * fragments for one request.
     *
     * <p>Stores that can retrieve both representations in one backend call
     * override this method. The compatibility default preserves the SPI for
     * durable stores that have not yet added a combined projection.</p>
     */
    default FragmentRepresentations readRepresentations(
            String inventoryIdentity,
            Collection<String> blueIds) {
        CoordinationFragmentInventory inventory = requireInventory(
                Objects.requireNonNull(
                        inventoryIdentity, "inventoryIdentity"));
        Collection<String> requested = Objects.requireNonNull(
                blueIds, "blueIds");
        Set<String> members = new HashSet<String>(
                inventory.fragmentBlueIds());
        List<String> admitted = new ArrayList<String>();
        for (String blueId : requested) {
            if (members.contains(Objects.requireNonNull(blueId, "blueId"))) {
                admitted.add(blueId);
            }
        }
        Map<String, NodeProviderResult> read = readAll(admitted);
        Map<String, NodeProviderResult> canonical =
                new LinkedHashMap<String, NodeProviderResult>();
        for (String blueId : requested) {
            NodeProviderResult value = members.contains(blueId)
                    ? read.get(blueId)
                    : null;
            canonical.put(blueId, value == null
                    ? NodeProviderResult.notFound()
                    : value);
        }
        return new FragmentRepresentations(canonical, canonical);
    }

    /**
     * Reads inventory-partitioned PROCESS and physical representations.
     *
     * <p>The compatibility implementation performs one backend read for every
     * non-empty inventory partition. Stores with a true multi-inventory
     * projection override this method and report the actual backend read
     * count.</p>
     */
    default InventoryFragmentRepresentations readRepresentationsByInventory(
            Map<String, Collection<String>> blueIdsByInventory) {
        Map<String, FragmentRepresentations> representations =
                new LinkedHashMap<String, FragmentRepresentations>();
        int backendReadCount = 0;
        for (Map.Entry<String, Collection<String>> entry
                : Objects.requireNonNull(
                        blueIdsByInventory,
                        "blueIdsByInventory").entrySet()) {
            String inventoryIdentity = Objects.requireNonNull(
                    entry.getKey(), "inventoryIdentity");
            Collection<String> blueIds = Objects.requireNonNull(
                    entry.getValue(), "inventoryBlueIds");
            requireInventory(inventoryIdentity);
            if (blueIds.isEmpty()) {
                continue;
            }
            representations.put(
                    inventoryIdentity,
                    readRepresentations(inventoryIdentity, blueIds));
            backendReadCount++;
        }
        return new InventoryFragmentRepresentations(
                representations, backendReadCount);
    }

    /** Immutable result of one combined representation read. */
    final class FragmentRepresentations {
        private final Map<String, NodeProviderResult> processing;
        private final Map<String, NodeProviderResult> physical;

        public FragmentRepresentations(
                Map<String, NodeProviderResult> processing,
                Map<String, NodeProviderResult> physical) {
            this.processing = immutableCopy(processing, "processing");
            this.physical = immutableCopy(physical, "physical");
        }

        public Map<String, NodeProviderResult> processing() {
            return processing;
        }

        public Map<String, NodeProviderResult> physical() {
            return physical;
        }

        private static Map<String, NodeProviderResult> immutableCopy(
                Map<String, NodeProviderResult> source,
                String label) {
            return Collections.unmodifiableMap(
                    new LinkedHashMap<String, NodeProviderResult>(
                            Objects.requireNonNull(source, label)));
        }
    }

    /** Immutable inventory-partitioned representation read. */
    final class InventoryFragmentRepresentations {
        private final Map<String, FragmentRepresentations> byInventory;
        private final int backendReadCount;

        public InventoryFragmentRepresentations(
                Map<String, FragmentRepresentations> byInventory,
                int backendReadCount) {
            if (backendReadCount < 0) {
                throw new IllegalArgumentException(
                        "backendReadCount must not be negative");
            }
            this.byInventory = Collections.unmodifiableMap(
                    new LinkedHashMap<String, FragmentRepresentations>(
                            Objects.requireNonNull(
                                    byInventory, "byInventory")));
            this.backendReadCount = backendReadCount;
        }

        public Map<String, FragmentRepresentations> byInventory() {
            return byInventory;
        }

        public int backendReadCount() {
            return backendReadCount;
        }
    }

    /**
     * Returns a provider over canonical physical fragments only.
     *
     * <p>The store's ordinary NodeProvider surface may expose an
     * identity-equivalent PROCESS header view. Inventory reconstruction must
     * use this explicit physical namespace.</p>
     */
    default NodeProvider canonicalFragmentProvider() {
        final CoordinationFragmentStore store = this;
        return new NodeProvider() {
            @Override
            public java.util.List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome()
                        == blue.language.api.NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : Collections.<Node>emptyList();
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return store.readCanonical(blueId);
            }
        };
    }

    /**
     * Idempotently persists identity-equivalent, body-free PROCESS views.
     *
     * <p>These values live in
     * {@code CoordinationDocumentSplitter.PROCESS_HEADER_VIEW_PROFILE_ID},
     * not in the canonical physical-fragment namespace. NodeProvider reads
     * prefer a retained PROCESS view; profile-bound {@link #read} continues
     * to return only canonical physical content.</p>
     */
    void putProcessingViews(Map<String, Node> exactProcessingViews);

    /** Persists identity-equivalent PROCESS views for one exact inventory. */
    default void putProcessingViews(
            String inventoryIdentity,
            Map<String, Node> exactProcessingViews) {
        Objects.requireNonNull(inventoryIdentity, "inventoryIdentity");
        Objects.requireNonNull(
                exactProcessingViews, "exactProcessingViews");
    }

    /** Idempotently persists one verified body-free inventory. */
    void putInventory(CoordinationFragmentInventory inventory);

    /** Returns one inventory or fails when it is absent or invalid. */
    CoordinationFragmentInventory requireInventory(String inventoryIdentity);

    @Override
    Node read(String profileIdentity, String blueId);
}
