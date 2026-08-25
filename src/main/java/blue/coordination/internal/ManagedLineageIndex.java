package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable exact-state indexes for managed-document lineage resolution.
 *
 * <p>The store advances this value from the sessions changed by one durable
 * publication. Reads are therefore bounded index lookups and publication does
 * not rebuild lineage evidence by scanning the ambient session catalog.</p>
 */
final class ManagedLineageIndex {
    private static final Comparator<RetainedKey> RETAINED_ORDER = Comparator
            .comparing(RetainedKey::documentId,
                    EmbeddingBinding.DOCUMENT_ORDER)
            .thenComparingLong(RetainedKey::epoch);
    private static final ManagedLineageIndex EMPTY = new ManagedLineageIndex(
            PersistentMap.empty(EmbeddingBinding.DOCUMENT_ORDER),
            PersistentMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentMap.empty(EmbeddingBinding.TEXT_ORDER),
            0);

    private final PersistentMap<DocumentId, Lineage> byDocumentId;
    private final PersistentMap<String, PersistentMap<DocumentId, Lineage>>
            byAuthoredInitialBlueId;
    private final PersistentMap<String, PersistentMap<DocumentId, Lineage>>
            byInitializedBlueId;
    private final PersistentMap<String,
            PersistentMap<RetainedKey, RetainedState>> byRetainedBlueId;
    private final PersistentMap<String, PersistentMap<DocumentId, Lineage>>
            byCurrentBlueId;
    private final int lastMutationNodeCopies;

    private ManagedLineageIndex(
            PersistentMap<DocumentId, Lineage> byDocumentId,
            PersistentMap<String, PersistentMap<DocumentId, Lineage>>
                    byAuthoredInitialBlueId,
            PersistentMap<String, PersistentMap<DocumentId, Lineage>>
                    byInitializedBlueId,
            PersistentMap<String, PersistentMap<RetainedKey, RetainedState>>
                    byRetainedBlueId,
            PersistentMap<String, PersistentMap<DocumentId, Lineage>>
                    byCurrentBlueId,
            int lastMutationNodeCopies) {
        this.byDocumentId = Objects.requireNonNull(
                byDocumentId, "byDocumentId");
        this.byAuthoredInitialBlueId = Objects.requireNonNull(
                byAuthoredInitialBlueId, "byAuthoredInitialBlueId");
        this.byInitializedBlueId = Objects.requireNonNull(
                byInitializedBlueId, "byInitializedBlueId");
        this.byRetainedBlueId = Objects.requireNonNull(
                byRetainedBlueId, "byRetainedBlueId");
        this.byCurrentBlueId = Objects.requireNonNull(
                byCurrentBlueId, "byCurrentBlueId");
        if (lastMutationNodeCopies < 0) {
            throw new IllegalArgumentException(
                    "lastMutationNodeCopies must be non-negative");
        }
        this.lastMutationNodeCopies = lastMutationNodeCopies;
    }

    static ManagedLineageIndex empty() {
        return EMPTY;
    }

    /** Adds one newly durable lineage without inspecting any other session. */
    ManagedLineageIndex withNewLineage(DocumentSession session) {
        Lineage lineage = Lineage.from(Objects.requireNonNull(
                session, "session"));
        if (byDocumentId.containsKey(lineage.documentId())) {
            throw new IllegalArgumentException(
                    "Duplicate managed lineage " + lineage.documentId());
        }
        return adding(lineage);
    }

    /**
     * Advances one existing lineage from its already indexed head.
     *
     * <p>Only revisions after the indexed head are opened. This supports a
     * transaction that appends one revision and recovery adapters that may
     * install several already-verified revisions in one store swap.</p>
     */
    ManagedLineageIndex withAdvancedRevision(DocumentSession session) {
        DocumentSession selected = Objects.requireNonNull(session, "session");
        Lineage prior = byDocumentId.get(selected.documentId());
        if (prior == null) {
            throw new IllegalArgumentException(
                    "Unknown managed lineage " + selected.documentId());
        }
        if (!prior.authoredInitialBlueId().equals(
                selected.authoredInitialBlueId())) {
            throw new IllegalArgumentException(
                    "Authored initial identity changed for "
                            + selected.documentId());
        }
        List<DocumentRevision> additions = selected.revisionsAfter(
                prior.currentEpoch());
        if (additions.isEmpty()) {
            if (selected.epoch() == prior.currentEpoch()
                    && selected.currentRevision().after().blueId()
                            .equals(prior.currentBlueId())) {
                return this;
            }
            throw new IllegalArgumentException(
                    "Session head does not advance indexed lineage "
                            + selected.documentId());
        }
        ArrayList<RetainedState> retained = new ArrayList<>(
                prior.retainedStates());
        long expectedEpoch = Math.addExact(prior.currentEpoch(), 1L);
        for (DocumentRevision revision : additions) {
            if (!revision.documentId().equals(prior.documentId())
                    || revision.epoch() != expectedEpoch) {
                throw new IllegalArgumentException(
                        "Noncontiguous retained revision for "
                                + prior.documentId());
            }
            retained.add(new RetainedState(
                    prior.documentId(),
                    revision.epoch(),
                    revision.after().blueId()));
            expectedEpoch = Math.addExact(expectedEpoch, 1L);
        }
        DocumentRevision current = additions.get(additions.size() - 1);
        Lineage advanced = new Lineage(
                prior.documentId(),
                prior.authoredInitialBlueId(),
                prior.initializedBlueId(),
                current.epoch(),
                current.after().blueId(),
                retained);
        if (selected.epoch() != advanced.currentEpoch()
                || !selected.currentRevision().after().blueId()
                        .equals(advanced.currentBlueId())) {
            throw new IllegalArgumentException(
                    "Indexed additions do not reach the session head "
                            + selected.documentId());
        }
        return advancing(prior, advanced, retained.subList(
                prior.retainedStates().size(), retained.size()));
    }

    /** Removes one retired lineage without inspecting any other session. */
    ManagedLineageIndex withoutLineage(DocumentId documentId) {
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        Lineage prior = byDocumentId.get(selected);
        return prior == null ? this : removing(prior);
    }

    Lineage byDocumentId(DocumentId documentId) {
        return byDocumentId.get(Objects.requireNonNull(
                documentId, "documentId"));
    }

    List<Lineage> authoredInitialMatches(String blueId) {
        return lineageMatches(byAuthoredInitialBlueId, blueId);
    }

    List<Lineage> initializedMatches(String blueId) {
        return lineageMatches(byInitializedBlueId, blueId);
    }

    List<RetainedState> retainedMatches(String blueId) {
        PersistentMap<RetainedKey, RetainedState> bucket =
                byRetainedBlueId.get(requireBlueId(blueId));
        return bucket == null ? List.of() : bucket.values();
    }

    List<Lineage> currentMatches(String blueId) {
        return lineageMatches(byCurrentBlueId, blueId);
    }

    Set<DocumentId> documentIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                byDocumentId.keys()));
    }

    int lineageCount() {
        return byDocumentId.size();
    }

    /** Actual persistent nodes allocated by the mutation that made this view. */
    int lastMutationNodeCopies() {
        return lastMutationNodeCopies;
    }

    /** Actual balanced-tree comparisons used by the resolver's exact indexes. */
    int exactLookupSteps(String blueId) {
        String selected = requireBlueId(blueId);
        return Math.addExact(
                Math.addExact(
                        byAuthoredInitialBlueId.lookupSteps(selected),
                        byInitializedBlueId.lookupSteps(selected)),
                Math.addExact(
                        byRetainedBlueId.lookupSteps(selected),
                        byCurrentBlueId.lookupSteps(selected)));
    }

    int documentLookupSteps(DocumentId documentId) {
        return byDocumentId.lookupSteps(Objects.requireNonNull(
                documentId, "documentId"));
    }

    /** Exhaustive invariant check used only by deterministic structure tests. */
    void assertStructurallyValid() {
        byDocumentId.assertStructurallyValid();
        assertBucketsValid(byAuthoredInitialBlueId);
        assertBucketsValid(byInitializedBlueId);
        assertBucketsValid(byRetainedBlueId);
        assertBucketsValid(byCurrentBlueId);
    }

    private ManagedLineageIndex adding(Lineage lineage) {
        MapMutation<DocumentId, Lineage> documents = byDocumentId.put(
                lineage.documentId(), lineage);
        BucketMutation<DocumentId, Lineage> authored = putBucket(
                byAuthoredInitialBlueId,
                lineage.authoredInitialBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                lineage.documentId(),
                lineage);
        BucketMutation<DocumentId, Lineage> initialized = putBucket(
                byInitializedBlueId,
                lineage.initializedBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                lineage.documentId(),
                lineage);
        BucketMutation<DocumentId, Lineage> current = putBucket(
                byCurrentBlueId,
                lineage.currentBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                lineage.documentId(),
                lineage);
        PersistentMap<String, PersistentMap<RetainedKey, RetainedState>>
                retained = byRetainedBlueId;
        int copies = Math.addExact(documents.copiedNodes(), Math.addExact(
                authored.copiedNodes(), Math.addExact(
                        initialized.copiedNodes(), current.copiedNodes())));
        for (RetainedState state : lineage.retainedStates()) {
            BucketMutation<RetainedKey, RetainedState> added = putBucket(
                    retained,
                    state.blueId(),
                    RETAINED_ORDER,
                    RetainedKey.from(state),
                    state);
            retained = added.index();
            copies = Math.addExact(copies, added.copiedNodes());
        }
        return new ManagedLineageIndex(
                documents.map(),
                authored.index(),
                initialized.index(),
                retained,
                current.index(),
                copies);
    }

    private ManagedLineageIndex advancing(
            Lineage prior,
            Lineage replacement,
            List<RetainedState> additions) {
        MapMutation<DocumentId, Lineage> documents = byDocumentId.put(
                replacement.documentId(), replacement);
        BucketMutation<DocumentId, Lineage> authored = putBucket(
                byAuthoredInitialBlueId,
                replacement.authoredInitialBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                replacement.documentId(),
                replacement);
        BucketMutation<DocumentId, Lineage> initialized = putBucket(
                byInitializedBlueId,
                replacement.initializedBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                replacement.documentId(),
                replacement);
        BucketMutation<DocumentId, Lineage> current = moveLineage(
                byCurrentBlueId, prior, replacement);
        PersistentMap<String, PersistentMap<RetainedKey, RetainedState>>
                retained = byRetainedBlueId;
        int copies = Math.addExact(documents.copiedNodes(), Math.addExact(
                authored.copiedNodes(), Math.addExact(
                        initialized.copiedNodes(), current.copiedNodes())));
        for (RetainedState state : additions) {
            BucketMutation<RetainedKey, RetainedState> added = putBucket(
                    retained,
                    state.blueId(),
                    RETAINED_ORDER,
                    RetainedKey.from(state),
                    state);
            retained = added.index();
            copies = Math.addExact(copies, added.copiedNodes());
        }
        return new ManagedLineageIndex(
                documents.map(),
                authored.index(),
                initialized.index(),
                retained,
                current.index(),
                copies);
    }

    private ManagedLineageIndex removing(Lineage lineage) {
        MapMutation<DocumentId, Lineage> documents = byDocumentId.remove(
                lineage.documentId());
        BucketMutation<DocumentId, Lineage> authored = removeBucket(
                byAuthoredInitialBlueId,
                lineage.authoredInitialBlueId(),
                lineage.documentId());
        BucketMutation<DocumentId, Lineage> initialized = removeBucket(
                byInitializedBlueId,
                lineage.initializedBlueId(),
                lineage.documentId());
        BucketMutation<DocumentId, Lineage> current = removeBucket(
                byCurrentBlueId,
                lineage.currentBlueId(),
                lineage.documentId());
        PersistentMap<String, PersistentMap<RetainedKey, RetainedState>>
                retained = byRetainedBlueId;
        int copies = Math.addExact(documents.copiedNodes(), Math.addExact(
                authored.copiedNodes(), Math.addExact(
                        initialized.copiedNodes(), current.copiedNodes())));
        for (RetainedState state : lineage.retainedStates()) {
            BucketMutation<RetainedKey, RetainedState> removed = removeBucket(
                    retained, state.blueId(), RetainedKey.from(state));
            retained = removed.index();
            copies = Math.addExact(copies, removed.copiedNodes());
        }
        return new ManagedLineageIndex(
                documents.map(),
                authored.index(),
                initialized.index(),
                retained,
                current.index(),
                copies);
    }

    private static BucketMutation<DocumentId, Lineage> moveLineage(
            PersistentMap<String, PersistentMap<DocumentId, Lineage>> index,
            Lineage prior,
            Lineage replacement) {
        if (prior.currentBlueId().equals(replacement.currentBlueId())) {
            return putBucket(
                    index,
                    replacement.currentBlueId(),
                    EmbeddingBinding.DOCUMENT_ORDER,
                    replacement.documentId(),
                    replacement);
        }
        BucketMutation<DocumentId, Lineage> removed = removeBucket(
                index, prior.currentBlueId(), prior.documentId());
        BucketMutation<DocumentId, Lineage> added = putBucket(
                removed.index(),
                replacement.currentBlueId(),
                EmbeddingBinding.DOCUMENT_ORDER,
                replacement.documentId(),
                replacement);
        return new BucketMutation<>(
                added.index(),
                Math.addExact(removed.copiedNodes(), added.copiedNodes()));
    }

    private static List<Lineage> lineageMatches(
            PersistentMap<String, PersistentMap<DocumentId, Lineage>> index,
            String blueId) {
        PersistentMap<DocumentId, Lineage> bucket = index.get(
                requireBlueId(blueId));
        return bucket == null ? List.of() : bucket.values();
    }

    private static <K, V> BucketMutation<K, V> putBucket(
            PersistentMap<String, PersistentMap<K, V>> index,
            String blueId,
            Comparator<? super K> keyOrder,
            K key,
            V value) {
        String selected = requireBlueId(blueId);
        PersistentMap<K, V> bucket = index.get(selected);
        if (bucket == null) {
            bucket = PersistentMap.empty(keyOrder);
        }
        MapMutation<K, V> inner = bucket.put(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"));
        MapMutation<String, PersistentMap<K, V>> outer = index.put(
                selected, inner.map());
        return new BucketMutation<>(outer.map(), Math.addExact(
                inner.copiedNodes(), outer.copiedNodes()));
    }

    private static <K, V> BucketMutation<K, V> removeBucket(
            PersistentMap<String, PersistentMap<K, V>> index,
            String blueId,
            K key) {
        String selected = requireBlueId(blueId);
        PersistentMap<K, V> bucket = index.get(selected);
        if (bucket == null || !bucket.containsKey(key)) {
            throw new IllegalStateException(
                    "Indexed lineage bucket is incomplete for " + selected);
        }
        MapMutation<K, V> inner = bucket.remove(key);
        MapMutation<String, PersistentMap<K, V>> outer = inner.map().isEmpty()
                ? index.remove(selected)
                : index.put(selected, inner.map());
        return new BucketMutation<>(outer.map(), Math.addExact(
                inner.copiedNodes(), outer.copiedNodes()));
    }

    private static <K, V> void assertBucketsValid(
            PersistentMap<String, PersistentMap<K, V>> index) {
        index.assertStructurallyValid();
        index.values().forEach(PersistentMap::assertStructurallyValid);
    }

    private record MapMutation<K, V>(
            PersistentMap<K, V> map,
            int copiedNodes) {
        private MapMutation {
            map = Objects.requireNonNull(map, "map");
            if (copiedNodes < 0) {
                throw new IllegalArgumentException(
                        "copiedNodes must be non-negative");
            }
        }
    }

    private record BucketMutation<K, V>(
            PersistentMap<String, PersistentMap<K, V>> index,
            int copiedNodes) {
        private BucketMutation {
            index = Objects.requireNonNull(index, "index");
            if (copiedNodes < 0) {
                throw new IllegalArgumentException(
                        "copiedNodes must be non-negative");
            }
        }
    }

    private record RetainedKey(DocumentId documentId, long epoch) {
        private RetainedKey {
            documentId = Objects.requireNonNull(documentId, "documentId");
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "epoch must be non-negative");
            }
        }

        private static RetainedKey from(RetainedState state) {
            RetainedState selected = Objects.requireNonNull(state, "state");
            return new RetainedKey(selected.documentId(), selected.epoch());
        }
    }

    /**
     * Persistent AVL map. One mutation copies only its search path and any
     * rotation nodes; untouched subtrees remain shared by identity.
     */
    private static final class PersistentMap<K, V> {
        private final Comparator<? super K> order;
        private final TreeNode<K, V> root;

        private PersistentMap(
                Comparator<? super K> order,
                TreeNode<K, V> root) {
            this.order = Objects.requireNonNull(order, "order");
            this.root = root;
        }

        private static <K, V> PersistentMap<K, V> empty(
                Comparator<? super K> order) {
            return new PersistentMap<>(order, null);
        }

        private V get(K key) {
            K selected = Objects.requireNonNull(key, "key");
            TreeNode<K, V> node = root;
            while (node != null) {
                int comparison = order.compare(selected, node.key);
                if (comparison == 0) {
                    return node.value;
                }
                node = comparison < 0 ? node.left : node.right;
            }
            return null;
        }

        private boolean containsKey(K key) {
            return get(key) != null;
        }

        private int lookupSteps(K key) {
            K selected = Objects.requireNonNull(key, "key");
            int steps = 0;
            TreeNode<K, V> node = root;
            while (node != null) {
                steps = Math.addExact(steps, 1);
                int comparison = order.compare(selected, node.key);
                if (comparison == 0) {
                    return steps;
                }
                node = comparison < 0 ? node.left : node.right;
            }
            return steps;
        }

        private MapMutation<K, V> put(K key, V value) {
            K selectedKey = Objects.requireNonNull(key, "key");
            V selectedValue = Objects.requireNonNull(value, "value");
            CopyCounter copies = new CopyCounter();
            TreeNode<K, V> changed = put(
                    root, selectedKey, selectedValue, copies);
            return new MapMutation<>(
                    new PersistentMap<>(order, changed), copies.value);
        }

        private MapMutation<K, V> remove(K key) {
            K selected = Objects.requireNonNull(key, "key");
            CopyCounter copies = new CopyCounter();
            ChangeFlag removed = new ChangeFlag();
            TreeNode<K, V> changed = remove(
                    root, selected, removed, copies);
            return removed.value
                    ? new MapMutation<>(
                            new PersistentMap<>(order, changed), copies.value)
                    : new MapMutation<>(this, 0);
        }

        private int size() {
            return TreeNode.size(root);
        }

        private boolean isEmpty() {
            return root == null;
        }

        private List<K> keys() {
            ArrayList<K> values = new ArrayList<>(size());
            collectKeys(root, values);
            return List.copyOf(values);
        }

        private List<V> values() {
            ArrayList<V> values = new ArrayList<>(size());
            collectValues(root, values);
            return List.copyOf(values);
        }

        private void assertStructurallyValid() {
            Validation<K> validation = validate(root);
            if (validation.size() != size()) {
                throw new IllegalStateException(
                        "Persistent-map size metadata is inconsistent");
            }
        }

        private TreeNode<K, V> put(
                TreeNode<K, V> node,
                K key,
                V value,
                CopyCounter copies) {
            if (node == null) {
                return copiedNode(key, value, null, null, copies);
            }
            int comparison = order.compare(key, node.key);
            if (comparison == 0) {
                return copiedNode(
                        key, value, node.left, node.right, copies);
            }
            TreeNode<K, V> changed = comparison < 0
                    ? copiedNode(
                            node.key,
                            node.value,
                            put(node.left, key, value, copies),
                            node.right,
                            copies)
                    : copiedNode(
                            node.key,
                            node.value,
                            node.left,
                            put(node.right, key, value, copies),
                            copies);
            return balance(changed, copies);
        }

        private TreeNode<K, V> remove(
                TreeNode<K, V> node,
                K key,
                ChangeFlag removed,
                CopyCounter copies) {
            if (node == null) {
                return null;
            }
            int comparison = order.compare(key, node.key);
            if (comparison < 0) {
                TreeNode<K, V> changedLeft = remove(
                        node.left, key, removed, copies);
                if (!removed.value) {
                    return node;
                }
                return balance(copiedNode(
                        node.key,
                        node.value,
                        changedLeft,
                        node.right,
                        copies), copies);
            }
            if (comparison > 0) {
                TreeNode<K, V> changedRight = remove(
                        node.right, key, removed, copies);
                if (!removed.value) {
                    return node;
                }
                return balance(copiedNode(
                        node.key,
                        node.value,
                        node.left,
                        changedRight,
                        copies), copies);
            }
            removed.value = true;
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            TreeNode<K, V> successor = minimum(node.right);
            TreeNode<K, V> changedRight = removeMinimum(
                    node.right, copies);
            return balance(copiedNode(
                    successor.key,
                    successor.value,
                    node.left,
                    changedRight,
                    copies), copies);
        }

        private TreeNode<K, V> removeMinimum(
                TreeNode<K, V> node,
                CopyCounter copies) {
            if (node.left == null) {
                return node.right;
            }
            return balance(copiedNode(
                    node.key,
                    node.value,
                    removeMinimum(node.left, copies),
                    node.right,
                    copies), copies);
        }

        private TreeNode<K, V> balance(
                TreeNode<K, V> node,
                CopyCounter copies) {
            int balance = TreeNode.height(node.left)
                    - TreeNode.height(node.right);
            if (balance > 1) {
                if (TreeNode.height(node.left.left)
                        < TreeNode.height(node.left.right)) {
                    node = copiedNode(
                            node.key,
                            node.value,
                            rotateLeft(node.left, copies),
                            node.right,
                            copies);
                }
                return rotateRight(node, copies);
            }
            if (balance < -1) {
                if (TreeNode.height(node.right.right)
                        < TreeNode.height(node.right.left)) {
                    node = copiedNode(
                            node.key,
                            node.value,
                            node.left,
                            rotateRight(node.right, copies),
                            copies);
                }
                return rotateLeft(node, copies);
            }
            return node;
        }

        private TreeNode<K, V> rotateLeft(
                TreeNode<K, V> node,
                CopyCounter copies) {
            TreeNode<K, V> promoted = node.right;
            TreeNode<K, V> changedLeft = copiedNode(
                    node.key,
                    node.value,
                    node.left,
                    promoted.left,
                    copies);
            return copiedNode(
                    promoted.key,
                    promoted.value,
                    changedLeft,
                    promoted.right,
                    copies);
        }

        private TreeNode<K, V> rotateRight(
                TreeNode<K, V> node,
                CopyCounter copies) {
            TreeNode<K, V> promoted = node.left;
            TreeNode<K, V> changedRight = copiedNode(
                    node.key,
                    node.value,
                    promoted.right,
                    node.right,
                    copies);
            return copiedNode(
                    promoted.key,
                    promoted.value,
                    promoted.left,
                    changedRight,
                    copies);
        }

        private static <K, V> TreeNode<K, V> minimum(
                TreeNode<K, V> node) {
            TreeNode<K, V> selected = node;
            while (selected.left != null) {
                selected = selected.left;
            }
            return selected;
        }

        private static <K, V> TreeNode<K, V> copiedNode(
                K key,
                V value,
                TreeNode<K, V> left,
                TreeNode<K, V> right,
                CopyCounter copies) {
            copies.increment();
            return new TreeNode<>(key, value, left, right);
        }

        private static <K, V> void collectKeys(
                TreeNode<K, V> node,
                List<K> destination) {
            if (node == null) {
                return;
            }
            collectKeys(node.left, destination);
            destination.add(node.key);
            collectKeys(node.right, destination);
        }

        private static <K, V> void collectValues(
                TreeNode<K, V> node,
                List<V> destination) {
            if (node == null) {
                return;
            }
            collectValues(node.left, destination);
            destination.add(node.value);
            collectValues(node.right, destination);
        }

        private Validation<K> validate(TreeNode<K, V> node) {
            if (node == null) {
                return Validation.empty();
            }
            Validation<K> left = validate(node.left);
            Validation<K> right = validate(node.right);
            if (left.maximum() != null
                    && order.compare(left.maximum(), node.key) >= 0) {
                throw new IllegalStateException(
                        "Persistent-map left subtree is out of order");
            }
            if (right.minimum() != null
                    && order.compare(node.key, right.minimum()) >= 0) {
                throw new IllegalStateException(
                        "Persistent-map right subtree is out of order");
            }
            int expectedHeight = Math.addExact(
                    Math.max(left.height(), right.height()), 1);
            int expectedSize = Math.addExact(
                    Math.addExact(left.size(), right.size()), 1);
            if (node.height != expectedHeight || node.size != expectedSize
                    || Math.abs(left.height() - right.height()) > 1) {
                throw new IllegalStateException(
                        "Persistent-map AVL metadata is inconsistent");
            }
            return new Validation<>(
                    left.minimum() == null ? node.key : left.minimum(),
                    right.maximum() == null ? node.key : right.maximum(),
                    expectedHeight,
                    expectedSize);
        }
    }

    private record Validation<K>(
            K minimum,
            K maximum,
            int height,
            int size) {
        private Validation {
            if (height < 0 || size < 0) {
                throw new IllegalArgumentException(
                        "validation dimensions must be non-negative");
            }
        }

        private static <K> Validation<K> empty() {
            return new Validation<>(null, null, 0, 0);
        }
    }

    private static final class TreeNode<K, V> {
        private final K key;
        private final V value;
        private final TreeNode<K, V> left;
        private final TreeNode<K, V> right;
        private final int height;
        private final int size;

        private TreeNode(
                K key,
                V value,
                TreeNode<K, V> left,
                TreeNode<K, V> right) {
            this.key = Objects.requireNonNull(key, "key");
            this.value = Objects.requireNonNull(value, "value");
            this.left = left;
            this.right = right;
            this.height = Math.addExact(
                    Math.max(height(left), height(right)), 1);
            this.size = Math.addExact(
                    Math.addExact(size(left), size(right)), 1);
        }

        private static int height(TreeNode<?, ?> node) {
            return node == null ? 0 : node.height;
        }

        private static int size(TreeNode<?, ?> node) {
            return node == null ? 0 : node.size;
        }
    }

    private static final class ChangeFlag {
        private boolean value;
    }

    private static final class CopyCounter {
        private int value;

        private void increment() {
            value = Math.addExact(value, 1);
        }
    }

    private static String requireBlueId(String blueId) {
        String selected = Objects.requireNonNull(blueId, "blueId");
        if (selected.isBlank()) {
            throw new IllegalArgumentException("blueId must not be blank");
        }
        return selected;
    }

    /** Immutable exact evidence for one managed lineage. */
    record Lineage(
            DocumentId documentId,
            String authoredInitialBlueId,
            String initializedBlueId,
            long currentEpoch,
            String currentBlueId,
            List<RetainedState> retainedStates) {
        Lineage {
            documentId = Objects.requireNonNull(documentId, "documentId");
            authoredInitialBlueId = requireBlueId(authoredInitialBlueId);
            initializedBlueId = requireBlueId(initializedBlueId);
            if (currentEpoch < 0L) {
                throw new IllegalArgumentException(
                        "currentEpoch must be non-negative");
            }
            currentBlueId = requireBlueId(currentBlueId);
            retainedStates = List.copyOf(Objects.requireNonNull(
                    retainedStates, "retainedStates"));
            if (retainedStates.isEmpty()
                    || retainedStates.get(0).epoch() != 0L
                    || retainedStates.get(retainedStates.size() - 1).epoch()
                            != currentEpoch
                    || !retainedStates.get(retainedStates.size() - 1)
                            .blueId().equals(currentBlueId)) {
                throw new IllegalArgumentException(
                        "Retained states do not describe the lineage head");
            }
        }

        static Lineage from(DocumentSession session) {
            DocumentSession selected = Objects.requireNonNull(
                    session, "session");
            List<DocumentRevision> revisions = selected.revisions();
            if (revisions.isEmpty() || revisions.get(0).epoch() != 0L) {
                throw new IllegalArgumentException(
                        "Managed lineage has no epoch-zero revision "
                                + selected.documentId());
            }
            ArrayList<RetainedState> retained = new ArrayList<>();
            long expectedEpoch = 0L;
            for (DocumentRevision revision : revisions) {
                if (!revision.documentId().equals(selected.documentId())
                        || revision.epoch() != expectedEpoch) {
                    throw new IllegalArgumentException(
                            "Managed lineage history is not contiguous "
                                    + selected.documentId());
                }
                retained.add(new RetainedState(
                        selected.documentId(),
                        revision.epoch(),
                        revision.after().blueId()));
                expectedEpoch = Math.addExact(expectedEpoch, 1L);
            }
            DocumentRevision current = revisions.get(revisions.size() - 1);
            if (selected.epoch() != current.epoch()
                    || !selected.currentRevision().after().blueId()
                            .equals(current.after().blueId())) {
                throw new IllegalArgumentException(
                        "Managed lineage history does not reach its head "
                                + selected.documentId());
            }
            return new Lineage(
                    selected.documentId(),
                    selected.authoredInitialBlueId(),
                    revisions.get(0).after().blueId(),
                    current.epoch(),
                    current.after().blueId(),
                    retained);
        }

        List<Long> epochsFor(String blueId) {
            String selected = requireBlueId(blueId);
            return retainedStates.stream()
                    .filter(state -> state.blueId().equals(selected))
                    .map(RetainedState::epoch)
                    .toList();
        }
    }

    /** One exact retained epoch row. */
    record RetainedState(
            DocumentId documentId,
            long epoch,
            String blueId) {
        RetainedState {
            documentId = Objects.requireNonNull(documentId, "documentId");
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "epoch must be non-negative");
            }
            blueId = requireBlueId(blueId);
        }
    }
}
