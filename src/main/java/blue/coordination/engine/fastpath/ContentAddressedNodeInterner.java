package blue.coordination.engine.fastpath;

import blue.language.model.Node;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded engine-owned content interner. Values are verified once on entry;
 * repeated fragments and processing views are represented by handles rather
 * than cloned, hashed Node graphs. This class does not evict content which is
 * still referenced by an inventory; the caller explicitly retains/releases.
 */
public final class ContentAddressedNodeInterner {
    public static final String PHYSICAL = "physical";
    private final Object owner = new Object();
    private final int maximumUnpinned;
    private final LinkedHashMap<String, Entry> entries;

    public ContentAddressedNodeInterner(int maximumUnpinned) {
        if (maximumUnpinned < 0) {
            throw new IllegalArgumentException(
                    "maximumUnpinned must be non-negative");
        }
        this.maximumUnpinned = maximumUnpinned;
        this.entries = new LinkedHashMap<String, Entry>(16, 0.75f, true);
    }

    public synchronized ExactNodeHandle internCopy(
            String blueId, Node supplied) {
        return internCopy(PHYSICAL, blueId, supplied);
    }

    public synchronized ExactNodeHandle internCopy(
            String namespace, String blueId, Node supplied) {
        String key = key(namespace, blueId);
        Entry current = entries.get(key);
        if (current != null) {
            // Interning is a trust boundary. Even an already-present key may
            // not turn a conflicting caller value into an apparent cache hit.
            ExactNodeHandle.copyAndVerify(blueId, supplied, owner);
            return current.handle;
        }
        ExactNodeHandle verified = ExactNodeHandle.copyAndVerify(
                blueId, supplied, owner);
        makeRoomForInsertion();
        entries.put(key, new Entry(verified));
        return verified;
    }

    public synchronized ExactNodeHandle internOwned(
            String blueId, Node requestOwned) {
        return internOwned(PHYSICAL, blueId, requestOwned);
    }

    public synchronized ExactNodeHandle internOwned(
            String namespace, String blueId, Node requestOwned) {
        String key = key(namespace, blueId);
        Entry current = entries.get(key);
        if (current != null) {
            ExactNodeHandle.adoptAndVerify(blueId, requestOwned, owner);
            return current.handle;
        }
        ExactNodeHandle verified = ExactNodeHandle.adoptAndVerify(
                blueId, requestOwned, owner);
        makeRoomForInsertion();
        entries.put(key, new Entry(verified));
        return verified;
    }

    /** Interns a body whose identity was calculated by this request. */
    public synchronized ExactNodeHandle internBound(
            String blueId,
            Node requestOwned,
            RequestDigestMemo digests) {
        return internBound(PHYSICAL, blueId, requestOwned, digests);
    }

    public synchronized ExactNodeHandle internBound(
            String namespace,
            String blueId,
            Node requestOwned,
            RequestDigestMemo digests) {
        String key = key(namespace, blueId);
        Objects.requireNonNull(digests, "digests").requireBound(
                Objects.requireNonNull(requestOwned, "requestOwned"),
                blueId);
        Entry current = entries.get(key);
        if (current != null) return current.handle;
        ExactNodeHandle verified = ExactNodeHandle.adoptBound(
                blueId,
                requestOwned,
                owner,
                digests);
        makeRoomForInsertion();
        entries.put(key, new Entry(verified));
        return verified;
    }

    public synchronized ExactNodeHandle find(String blueId) {
        return find(PHYSICAL, blueId);
    }

    public synchronized ExactNodeHandle find(
            String namespace, String blueId) {
        Entry entry = entries.get(key(namespace, blueId));
        return entry == null ? null : entry.handle;
    }

    public synchronized void retainAll(Collection<String> blueIds) {
        retainAll(PHYSICAL, blueIds);
    }

    public synchronized void retainAll(
            String namespace, Collection<String> blueIds) {
        for (String blueId : Objects.requireNonNull(blueIds, "blueIds")) {
            Entry entry = entries.get(key(namespace, blueId));
            if (entry == null) {
                throw new IllegalStateException(
                        "Cannot pin absent interned identity " + blueId);
            }
            entry.references++;
        }
    }

    public synchronized void releaseAll(Collection<String> blueIds) {
        releaseAll(PHYSICAL, blueIds);
    }

    public synchronized void releaseAll(
            String namespace, Collection<String> blueIds) {
        for (String blueId : Objects.requireNonNull(blueIds, "blueIds")) {
            Entry entry = entries.get(key(namespace, blueId));
            if (entry == null || entry.references == 0) {
                throw new IllegalStateException(
                        "Unbalanced release for " + blueId);
            }
            entry.references--;
        }
        evictUnpinned();
    }

    public synchronized Map<String, ExactNodeHandle> snapshotHandles(
            Collection<String> blueIds) {
        return snapshotHandles(PHYSICAL, blueIds);
    }

    public synchronized Map<String, ExactNodeHandle> snapshotHandles(
            String namespace, Collection<String> blueIds) {
        Map<String, ExactNodeHandle> result =
                new LinkedHashMap<String, ExactNodeHandle>();
        for (String blueId : Objects.requireNonNull(blueIds, "blueIds")) {
            Entry entry = entries.get(key(namespace, blueId));
            if (entry == null) {
                throw new IllegalStateException(
                        "Missing interned identity " + blueId);
            }
            result.put(blueId, entry.handle);
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized int size() {
        return entries.size();
    }

    Object ownershipToken() {
        return owner;
    }

    private static String key(String namespace, String blueId) {
        return requireText(namespace, "namespace") + '\u0000'
                + requireText(blueId, "blueId");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return value;
    }

    private void evictUnpinned() {
        evictUnpinnedTo(maximumUnpinned);
    }

    private void makeRoomForInsertion() {
        // The newly returned handle must remain available long enough for the
        // caller to pin it. A zero-retention interner therefore permits the
        // one just-returned unpinned entry until retain/release or the next
        // insertion boundary.
        evictUnpinnedTo(Math.max(0, maximumUnpinned - 1));
    }

    private void evictUnpinnedTo(int target) {
        int unpinned = 0;
        for (Entry entry : entries.values()) {
            if (entry.references == 0) unpinned++;
        }
        if (unpinned <= target) return;
        Iterator<Map.Entry<String, Entry>> iterator =
                entries.entrySet().iterator();
        while (iterator.hasNext() && unpinned > target) {
            Entry entry = iterator.next().getValue();
            if (entry.references == 0) {
                iterator.remove();
                unpinned--;
            }
        }
    }

    private static final class Entry {
        private final ExactNodeHandle handle;
        private int references;

        private Entry(ExactNodeHandle handle) {
            this.handle = handle;
        }
    }
}
