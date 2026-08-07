package blue.coordination.engine;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationRootViewCacheSnapshot;
import blue.coordination.engine.fastpath.RetainedNodeWeight;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded access-ordered cache of exact semantic Roots by inventory identity.
 *
 * <p>Untrusted values are defensively cloned on admission and every ordinary
 * read is defensive because {@link Node} is mutable. A verified
 * request-owned PROCESS result may instead cross an explicit private
 * ownership-transfer boundary. The cache is engine-owned; historical
 * inventory values therefore remain body-free regardless of the number of
 * committed revisions.</p>
 */
final class CoordinationInventoryRootViewCache {

    static final int DEFAULT_MAXIMUM_SIZE = 64;
    static final long DEFAULT_MAXIMUM_WEIGHT_BYTES =
            256L * 1024L * 1024L;

    private final int maximumSize;
    private final long maximumWeightBytes;
    private final LinkedHashMap<String, Entry> roots;
    private long retainedWeightBytes;
    private long hitCount;
    private long missCount;
    private long installationCount;
    private long evictionCount;

    CoordinationInventoryRootViewCache(int maximumSize) {
        this(maximumSize, DEFAULT_MAXIMUM_WEIGHT_BYTES);
    }

    CoordinationInventoryRootViewCache(
            int maximumSize, long maximumWeightBytes) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException(
                    "Root-view cache maximum size must be positive");
        }
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Root-view cache maximum weight must be positive");
        }
        this.maximumSize = maximumSize;
        this.maximumWeightBytes = maximumWeightBytes;
        this.roots = new LinkedHashMap<String, Entry>(
                Math.min(maximumSize, 16), 0.75f, true);
    }

    synchronized void install(
            CoordinationFragmentInventory inventory,
            Node exactRoot) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        Node root = verifiedRoot(checked, exactRoot);
        installEntry(
                checked,
                root,
                RetainedNodeWeight.approximateRetainedWeightBytes(root));
    }

    /**
     * Installs a Root whose identity was calculated at the verified PROCESS
     * boundary. The inventory binding is still checked, and the mutable Node
     * is copied once, but the complete graph is not hashed again.
     */
    synchronized void installVerified(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String verifiedRootBlueId) {
        installVerified(inventory, exactRoot, verifiedRootBlueId, true);
    }

    /**
     * Takes ownership of a request-local Root whose identity was already
     * verified by PROCESS and independently rebound by fragmentation.
     *
     * <p>This is an ownership-transfer boundary: the caller must not mutate
     * or publish {@code exactRoot} afterwards. Unlike {@link
     * #installVerified(CoordinationFragmentInventory, Node, String)}, it does
     * not clone a complete Root merely to move it between two engine-private
     * components.</p>
     */
    synchronized void installOwnedVerified(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String verifiedRootBlueId) {
        installVerified(inventory, exactRoot, verifiedRootBlueId, false);
    }

    /**
     * Ownership-transfer overload for a weight already collected by the
     * prepared result graph walk.
     */
    synchronized void installOwnedVerified(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String verifiedRootBlueId,
            long approximateRetainedWeightBytes) {
        installVerified(
                inventory,
                exactRoot,
                verifiedRootBlueId,
                false,
                approximateRetainedWeightBytes);
    }

    private void installVerified(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String verifiedRootBlueId,
            boolean copy) {
        installVerified(
                inventory,
                exactRoot,
                verifiedRootBlueId,
                copy,
                -1L);
    }

    private void installVerified(
            CoordinationFragmentInventory inventory,
            Node exactRoot,
            String verifiedRootBlueId,
            boolean copy,
            long suppliedWeightBytes) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        String identity = Objects.requireNonNull(
                verifiedRootBlueId, "verifiedRootBlueId");
        if (!checked.rootBlueId().equals(identity)) {
            throw new IllegalArgumentException(
                    "Verified Root identity does not match inventory");
        }
        Entry current = roots.get(checked.inventoryIdentity());
        if (current != null) {
            if (!identity.equals(current.rootBlueId)) {
                throw new IllegalStateException(
                        "Root-view cache identity conflict");
            }
            return;
        }
        Node supplied = Objects.requireNonNull(exactRoot, "exactRoot");
        Node retained = copy ? supplied.clone() : supplied;
        if (retained.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Verified Root view must be expanded");
        }
        long weightBytes = suppliedWeightBytes > 0L
                ? suppliedWeightBytes
                : RetainedNodeWeight.approximateRetainedWeightBytes(
                        retained);
        installEntry(checked, retained, weightBytes);
    }

    synchronized Node find(CoordinationFragmentInventory inventory) {
        Node retained = findRetained(inventory);
        return retained == null ? null : retained.clone();
    }

    /**
     * Returns the engine-owned immutable-by-convention Root for one internal
     * read-only planning invocation.
     *
     * <p>The caller must take a defensive snapshot before crossing a public
     * or mutable boundary. This avoids cloning the complete Root twice when
     * the indexed planner's exact-lookup boundary immediately snapshots it.</p>
     */
    synchronized Node findRetained(
            CoordinationFragmentInventory inventory) {
        CoordinationFragmentInventory checked = Objects.requireNonNull(
                inventory, "inventory");
        Entry retained = roots.get(checked.inventoryIdentity());
        if (retained == null
                || !checked.rootBlueId().equals(retained.rootBlueId)) {
            missCount++;
            return null;
        }
        hitCount++;
        return retained.root;
    }

    synchronized CoordinationRootViewCacheSnapshot snapshot() {
        return new CoordinationRootViewCacheSnapshot(
                maximumSize,
                roots.size(),
                hitCount,
                missCount,
                installationCount,
                evictionCount,
                maximumWeightBytes,
                retainedWeightBytes);
    }

    private void installEntry(
            CoordinationFragmentInventory inventory,
            Node retained,
            long weightBytes) {
        if (weightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "Root-view cache weight must be positive");
        }
        Entry current = roots.get(inventory.inventoryIdentity());
        if (current != null) {
            if (!inventory.rootBlueId().equals(current.rootBlueId)) {
                throw new IllegalStateException(
                        "Root-view cache identity conflict");
            }
            return;
        }
        installationCount++;
        if (weightBytes > maximumWeightBytes) {
            /* The value is valid for the current caller, but retaining it
             * would violate the hard budget. Existing warm entries remain
             * undisturbed. */
            evictionCount++;
            return;
        }
        while (!roots.isEmpty()
                && (roots.size() >= maximumSize
                || retainedWeightBytes
                > maximumWeightBytes - weightBytes)) {
            Map.Entry<String, Entry> eldest =
                    roots.entrySet().iterator().next();
            retainedWeightBytes -= eldest.getValue().weightBytes;
            roots.remove(eldest.getKey());
            evictionCount++;
        }
        roots.put(
                inventory.inventoryIdentity(),
                new Entry(
                        inventory.rootBlueId(),
                        retained,
                        weightBytes));
        retainedWeightBytes += weightBytes;
    }

    private static Node verifiedRoot(
            CoordinationFragmentInventory inventory,
            Node supplied) {
        Node root = Objects.requireNonNull(supplied, "exactRoot").clone();
        String actual = DirectBlueIdCalculator.calculateBlueId(root);
        if (root.isReferenceOnly()
                || !inventory.rootBlueId().equals(actual)) {
            throw new IllegalArgumentException(
                    "Root view does not match inventory "
                            + inventory.inventoryIdentity());
        }
        return root;
    }

    private static final class Entry {
        private final String rootBlueId;
        private final Node root;
        private final long weightBytes;

        private Entry(
                String rootBlueId, Node root, long weightBytes) {
            this.rootBlueId = rootBlueId;
            this.root = root;
            this.weightBytes = weightBytes;
        }
    }
}
