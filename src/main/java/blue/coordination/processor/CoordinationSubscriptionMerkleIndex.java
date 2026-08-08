package blue.coordination.processor;

import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.coordination.processor.delivery.CoordinationIndexedDeliveryEngine;
import blue.coordination.processor.delivery.CoordinationSubscriptionOccurrenceView;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * Persistent, history-independent Merkle index of canonical occurrences.
 *
 * <p>The treap's search order is the public snapshot order and its heap order
 * is the already content-addressed occurrence key.  Both orders are therefore
 * functions of the resulting content, rather than mutation history.  An
 * insert, retirement, or refresh copies and re-hashes only one treap spine.</p>
 */
public final class CoordinationSubscriptionMerkleIndex {
    private static final String EMPTY_DIGEST = digest(
            singleton("kind",
                    "blue.coordination/subscription-occurrences/empty/1.0"));

    private final Node root;
    private final KeyNode byPublicKey;
    private final KeyNode byInternalKey;

    private CoordinationSubscriptionMerkleIndex(
            Node root,
            KeyNode byPublicKey,
            KeyNode byInternalKey) {
        this.root = root;
        this.byPublicKey = byPublicKey;
        this.byInternalKey = byInternalKey;
    }

    static CoordinationSubscriptionMerkleIndex empty() {
        return new CoordinationSubscriptionMerkleIndex(null, null, null);
    }

    static CoordinationSubscriptionMerkleIndex from(
            Iterable<CoordinationSubscriptionOccurrence> occurrences) {
        CoordinationSubscriptionMerkleIndex result = empty();
        for (CoordinationSubscriptionOccurrence occurrence
                : Objects.requireNonNull(occurrences, "occurrences")) {
            result = result.updated(null, Objects.requireNonNull(
                    occurrence, "subscription occurrence"));
        }
        return result;
    }

    CoordinationSubscriptionMerkleIndex updated(
            CoordinationSubscriptionOccurrence previous,
            CoordinationSubscriptionOccurrence resulting) {
        if (previous == resulting) return this;
        Node changed = root;
        KeyNode changedByPublic = byPublicKey;
        KeyNode changedByInternal = byInternalKey;
        if (previous != null) {
            Removal removal = remove(changed, previous);
            if (!removal.removed) {
                throw new IllegalArgumentException(
                        "Previous occurrence is absent from Merkle index: "
                                + previous.occurrenceKey());
            }
            changed = removal.root;
            KeyRemoval publicRemoval = remove(
                    changedByPublic, previous.occurrenceKey());
            KeyRemoval internalRemoval = remove(
                    changedByInternal, internalKey(previous));
            if (!publicRemoval.removed || !internalRemoval.removed) {
                throw new IllegalArgumentException(
                        "Previous occurrence lookup binding is absent: "
                                + previous.occurrenceKey());
            }
            changedByPublic = publicRemoval.root;
            changedByInternal = internalRemoval.root;
        }
        if (resulting != null) {
            Insertion insertion = put(changed, resulting);
            if (!insertion.inserted) {
                throw new IllegalArgumentException(
                        "Resulting occurrence already exists in Merkle index: "
                                + resulting.occurrenceKey());
            }
            changed = insertion.root;
            KeyInsertion publicInsertion = put(
                    changedByPublic,
                    resulting.occurrenceKey(),
                    resulting);
            KeyInsertion internalInsertion = put(
                    changedByInternal,
                    internalKey(resulting),
                    resulting);
            if (!publicInsertion.inserted || !internalInsertion.inserted) {
                throw new IllegalArgumentException(
                        "Resulting occurrence lookup binding already exists: "
                                + resulting.occurrenceKey());
            }
            changedByPublic = publicInsertion.root;
            changedByInternal = internalInsertion.root;
        }
        return changed == root
                ? this
                : new CoordinationSubscriptionMerkleIndex(
                        changed, changedByPublic, changedByInternal);
    }

    int size() {
        return size(root);
    }

    String digest() {
        return root == null ? EMPTY_DIGEST : root.digest;
    }

    CoordinationSubscriptionOccurrence occurrence(String publicKey) {
        return get(byPublicKey, Objects.requireNonNull(
                publicKey, "publicKey"));
    }

    CoordinationSubscriptionOccurrence occurrenceByInternalKey(
            String scopePath,
            String channelKey) {
        return occurrenceByInternalKey(
                CoordinationIndexedDeliveryEngine.languageOccurrenceKey(
                        Objects.requireNonNull(scopePath, "scopePath"),
                        Objects.requireNonNull(channelKey, "channelKey")));
    }

    CoordinationSubscriptionOccurrence occurrenceByInternalKey(String key) {
        return get(byInternalKey, Objects.requireNonNull(key, "key"));
    }

    List<CoordinationSubscriptionOccurrence> occurrences() {
        return new PersistentOccurrenceList(this);
    }

    static boolean isPersistentOccurrenceList(List<?> supplied) {
        return supplied instanceof PersistentOccurrenceList;
    }

    private static Insertion put(
            Node node,
            CoordinationSubscriptionOccurrence occurrence) {
        if (node == null) {
            return new Insertion(new Node(occurrence, null, null), true);
        }
        int compared = CoordinationSubscriptionOccurrence.CANONICAL_ORDER
                .compare(occurrence, node.occurrence);
        if (compared == 0) {
            return new Insertion(node, false);
        }
        if (compared < 0) {
            Insertion insertion = put(node.left, occurrence);
            if (!insertion.inserted) return new Insertion(node, false);
            Node changed = new Node(
                    node.occurrence, insertion.root, node.right);
            return new Insertion(
                    higherPriority(insertion.root, changed)
                            ? rotateRight(changed)
                            : changed,
                    true);
        }
        Insertion insertion = put(node.right, occurrence);
        if (!insertion.inserted) return new Insertion(node, false);
        Node changed = new Node(
                node.occurrence, node.left, insertion.root);
        return new Insertion(
                higherPriority(insertion.root, changed)
                        ? rotateLeft(changed)
                        : changed,
                true);
    }

    private static Removal remove(
            Node node,
            CoordinationSubscriptionOccurrence occurrence) {
        if (node == null) return new Removal(null, false);
        int compared = CoordinationSubscriptionOccurrence.CANONICAL_ORDER
                .compare(occurrence, node.occurrence);
        if (compared < 0) {
            Removal removal = remove(node.left, occurrence);
            return removal.removed
                    ? new Removal(new Node(
                            node.occurrence, removal.root, node.right), true)
                    : new Removal(node, false);
        }
        if (compared > 0) {
            Removal removal = remove(node.right, occurrence);
            return removal.removed
                    ? new Removal(new Node(
                            node.occurrence, node.left, removal.root), true)
                    : new Removal(node, false);
        }
        if (!occurrence.occurrenceKey().equals(
                node.occurrence.occurrenceKey())) {
            return new Removal(node, false);
        }
        return new Removal(merge(node.left, node.right), true);
    }

    private static Node merge(Node left, Node right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new Node(
                    left.occurrence,
                    left.left,
                    merge(left.right, right));
        }
        return new Node(
                right.occurrence,
                merge(left, right.left),
                right.right);
    }

    private static Node rotateRight(Node node) {
        Node pivot = node.left;
        Node moved = new Node(
                node.occurrence, pivot.right, node.right);
        return new Node(pivot.occurrence, pivot.left, moved);
    }

    private static Node rotateLeft(Node node) {
        Node pivot = node.right;
        Node moved = new Node(
                node.occurrence, node.left, pivot.left);
        return new Node(pivot.occurrence, moved, pivot.right);
    }

    private static boolean higherPriority(Node left, Node right) {
        return ExternalOrderKey.compareTextCodePoints(
                left.occurrence.occurrenceKey(),
                right.occurrence.occurrenceKey()) < 0;
    }

    private static int size(Node node) {
        return node == null ? 0 : node.size;
    }

    private static String digest(Map<String, Object> value) {
        return CoordinationSubscriptionSerialization.digest(value);
    }

    private static Map<String, Object> singleton(
            String key,
            String value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return Collections.unmodifiableMap(result);
    }

    private static String internalKey(
            CoordinationSubscriptionOccurrence occurrence) {
        return CoordinationIndexedDeliveryEngine.languageOccurrenceKey(
                occurrence.scopePath(), occurrence.channelKey());
    }

    private static CoordinationSubscriptionOccurrence get(
            KeyNode node,
            String key) {
        KeyNode cursor = node;
        while (cursor != null) {
            int compared = ExternalOrderKey.compareTextCodePoints(
                    key, cursor.key);
            if (compared == 0) return cursor.value;
            cursor = compared < 0 ? cursor.left : cursor.right;
        }
        return null;
    }

    private static KeyInsertion put(
            KeyNode node,
            String key,
            CoordinationSubscriptionOccurrence value) {
        if (node == null) {
            return new KeyInsertion(new KeyNode(
                    key, value, priority(key), null, null), true);
        }
        int compared = ExternalOrderKey.compareTextCodePoints(key, node.key);
        if (compared == 0) return new KeyInsertion(node, false);
        if (compared < 0) {
            KeyInsertion insertion = put(node.left, key, value);
            if (!insertion.inserted) return new KeyInsertion(node, false);
            KeyNode changed = new KeyNode(
                    node.key, node.value, node.priority,
                    insertion.root, node.right);
            return new KeyInsertion(
                    higherPriority(insertion.root, changed)
                            ? rotateRight(changed)
                            : changed,
                    true);
        }
        KeyInsertion insertion = put(node.right, key, value);
        if (!insertion.inserted) return new KeyInsertion(node, false);
        KeyNode changed = new KeyNode(
                node.key, node.value, node.priority,
                node.left, insertion.root);
        return new KeyInsertion(
                higherPriority(insertion.root, changed)
                        ? rotateLeft(changed)
                        : changed,
                true);
    }

    private static KeyRemoval remove(KeyNode node, String key) {
        if (node == null) return new KeyRemoval(null, false);
        int compared = ExternalOrderKey.compareTextCodePoints(key, node.key);
        if (compared < 0) {
            KeyRemoval removal = remove(node.left, key);
            return removal.removed
                    ? new KeyRemoval(new KeyNode(
                            node.key, node.value, node.priority,
                            removal.root, node.right), true)
                    : new KeyRemoval(node, false);
        }
        if (compared > 0) {
            KeyRemoval removal = remove(node.right, key);
            return removal.removed
                    ? new KeyRemoval(new KeyNode(
                            node.key, node.value, node.priority,
                            node.left, removal.root), true)
                    : new KeyRemoval(node, false);
        }
        return new KeyRemoval(merge(node.left, node.right), true);
    }

    private static KeyNode merge(KeyNode left, KeyNode right) {
        if (left == null) return right;
        if (right == null) return left;
        if (higherPriority(left, right)) {
            return new KeyNode(
                    left.key, left.value, left.priority,
                    left.left, merge(left.right, right));
        }
        return new KeyNode(
                right.key, right.value, right.priority,
                merge(left, right.left), right.right);
    }

    private static KeyNode rotateRight(KeyNode node) {
        KeyNode pivot = node.left;
        KeyNode moved = new KeyNode(
                node.key, node.value, node.priority,
                pivot.right, node.right);
        return new KeyNode(
                pivot.key, pivot.value, pivot.priority,
                pivot.left, moved);
    }

    private static KeyNode rotateLeft(KeyNode node) {
        KeyNode pivot = node.right;
        KeyNode moved = new KeyNode(
                node.key, node.value, node.priority,
                node.left, pivot.left);
        return new KeyNode(
                pivot.key, pivot.value, pivot.priority,
                moved, pivot.right);
    }

    private static boolean higherPriority(KeyNode left, KeyNode right) {
        int compared = ExternalOrderKey.compareTextCodePoints(
                left.priority, right.priority);
        return compared < 0 || (compared == 0
                && ExternalOrderKey.compareTextCodePoints(
                        left.key, right.key) < 0);
    }

    private static String priority(String key) {
        Map<String, Object> canonical = new LinkedHashMap<String, Object>();
        canonical.put(
                "kind",
                "blue.coordination/subscription-lookup-priority/1.0");
        canonical.put("key", key);
        return digest(canonical);
    }

    private static CoordinationSubscriptionOccurrence at(
            Node node,
            int index) {
        if (index < 0 || index >= size(node)) {
            throw new IndexOutOfBoundsException(
                    "index=" + index + ", size=" + size(node));
        }
        Node cursor = node;
        int remaining = index;
        while (cursor != null) {
            int leftSize = size(cursor.left);
            if (remaining < leftSize) {
                cursor = cursor.left;
            } else if (remaining == leftSize) {
                return cursor.occurrence;
            } else {
                remaining -= leftSize + 1;
                cursor = cursor.right;
            }
        }
        throw new AssertionError("persistent occurrence index is corrupt");
    }

    /** Immutable ordered list plus an internal constant-time lookup adapter. */
    public static final class PersistentOccurrenceList
            extends AbstractList<CoordinationSubscriptionOccurrence>
            implements RandomAccess {
        private final CoordinationSubscriptionMerkleIndex owner;

        private PersistentOccurrenceList(
                CoordinationSubscriptionMerkleIndex owner) {
            this.owner = owner;
        }

        @Override
        public CoordinationSubscriptionOccurrence get(int index) {
            return at(owner.root, index);
        }

        @Override
        public int size() {
            return owner.size();
        }

        @Override
        public Iterator<CoordinationSubscriptionOccurrence> iterator() {
            return new Iterator<CoordinationSubscriptionOccurrence>() {
                private final Deque<Node> pending = initialize(owner.root);

                @Override
                public boolean hasNext() {
                    return !pending.isEmpty();
                }

                @Override
                public CoordinationSubscriptionOccurrence next() {
                    if (pending.isEmpty()) throw new NoSuchElementException();
                    Node next = pending.removeLast();
                    pushLeft(next.right, pending);
                    return next.occurrence;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException(
                            "immutable occurrence list");
                }
            };
        }

        public CoordinationSubscriptionOccurrenceView occurrence(
                String publicKey) {
            return owner.occurrence(publicKey);
        }

        public CoordinationSubscriptionOccurrenceView occurrence(
                ExternalSubscriptionOccurrenceKey key) {
            ExternalSubscriptionOccurrenceKey exact =
                    Objects.requireNonNull(key, "key");
            return owner.occurrenceByInternalKey(
                    exact.scopePath(), exact.channelKey());
        }

        private static Deque<Node> initialize(Node root) {
            Deque<Node> result = new ArrayDeque<Node>();
            pushLeft(root, result);
            return result;
        }

        private static void pushLeft(Node node, Deque<Node> target) {
            Node cursor = node;
            while (cursor != null) {
                target.addLast(cursor);
                cursor = cursor.left;
            }
        }
    }

    private static final class Node {
        private final CoordinationSubscriptionOccurrence occurrence;
        private final Node left;
        private final Node right;
        private final int size;
        private final String digest;

        private Node(
                CoordinationSubscriptionOccurrence occurrence,
                Node left,
                Node right) {
            this.occurrence = Objects.requireNonNull(
                    occurrence, "occurrence");
            this.left = left;
            this.right = right;
            this.size = 1 + size(left) + size(right);
            Map<String, Object> canonical =
                    new LinkedHashMap<String, Object>();
            canonical.put(
                    "kind",
                    "blue.coordination/subscription-occurrences/node/1.0");
            canonical.put("size", size);
            canonical.put(
                    "left",
                    left == null ? EMPTY_DIGEST : left.digest);
            canonical.put(
                    "occurrence",
                    CoordinationSubscriptionSerialization.digest(
                            occurrence.toCanonicalMap()));
            canonical.put(
                    "right",
                    right == null ? EMPTY_DIGEST : right.digest);
            this.digest = digest(canonical);
        }
    }

    private static final class Insertion {
        private final Node root;
        private final boolean inserted;

        private Insertion(Node root, boolean inserted) {
            this.root = root;
            this.inserted = inserted;
        }
    }

    private static final class Removal {
        private final Node root;
        private final boolean removed;

        private Removal(Node root, boolean removed) {
            this.root = root;
            this.removed = removed;
        }
    }

    private static final class KeyNode {
        private final String key;
        private final CoordinationSubscriptionOccurrence value;
        private final String priority;
        private final KeyNode left;
        private final KeyNode right;

        private KeyNode(
                String key,
                CoordinationSubscriptionOccurrence value,
                String priority,
                KeyNode left,
                KeyNode right) {
            this.key = key;
            this.value = value;
            this.priority = priority;
            this.left = left;
            this.right = right;
        }
    }

    private static final class KeyInsertion {
        private final KeyNode root;
        private final boolean inserted;

        private KeyInsertion(KeyNode root, boolean inserted) {
            this.root = root;
            this.inserted = inserted;
        }
    }

    private static final class KeyRemoval {
        private final KeyNode root;
        private final boolean removed;

        private KeyRemoval(KeyNode root, boolean removed) {
            this.root = root;
            this.removed = removed;
        }
    }
}
