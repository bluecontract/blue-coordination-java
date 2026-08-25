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
            PersistentOrderedMap.empty(EmbeddingBinding.DOCUMENT_ORDER),
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
            PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER),
            0);

    private final PersistentOrderedMap<DocumentId, Lineage> byDocumentId;
    private final PersistentOrderedMap<String,
            PersistentOrderedMap<DocumentId, Lineage>>
            byAuthoredInitialBlueId;
    private final PersistentOrderedMap<String,
            PersistentOrderedMap<DocumentId, Lineage>>
            byInitializedBlueId;
    private final PersistentOrderedMap<String,
            PersistentOrderedMap<RetainedKey, RetainedState>>
            byRetainedBlueId;
    private final PersistentOrderedMap<String,
            PersistentOrderedMap<DocumentId, Lineage>>
            byCurrentBlueId;
    private final int lastMutationNodeCopies;

    private ManagedLineageIndex(
            PersistentOrderedMap<DocumentId, Lineage> byDocumentId,
            PersistentOrderedMap<String,
                    PersistentOrderedMap<DocumentId, Lineage>>
                    byAuthoredInitialBlueId,
            PersistentOrderedMap<String,
                    PersistentOrderedMap<DocumentId, Lineage>>
                    byInitializedBlueId,
            PersistentOrderedMap<String,
                    PersistentOrderedMap<RetainedKey, RetainedState>>
                    byRetainedBlueId,
            PersistentOrderedMap<String,
                    PersistentOrderedMap<DocumentId, Lineage>>
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
        PersistentOrderedMap<RetainedKey, RetainedState> bucket =
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
        PersistentOrderedMap.Mutation<DocumentId, Lineage> documents =
                byDocumentId.put(lineage.documentId(), lineage);
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
        PersistentOrderedMap<String,
                PersistentOrderedMap<RetainedKey, RetainedState>> retained =
                byRetainedBlueId;
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
        PersistentOrderedMap.Mutation<DocumentId, Lineage> documents =
                byDocumentId.put(
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
        PersistentOrderedMap<String,
                PersistentOrderedMap<RetainedKey, RetainedState>> retained =
                byRetainedBlueId;
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
        PersistentOrderedMap.Mutation<DocumentId, Lineage> documents =
                byDocumentId.remove(lineage.documentId());
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
        PersistentOrderedMap<String,
                PersistentOrderedMap<RetainedKey, RetainedState>> retained =
                byRetainedBlueId;
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
            PersistentOrderedMap<String,
                    PersistentOrderedMap<DocumentId, Lineage>> index,
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
            PersistentOrderedMap<String,
                    PersistentOrderedMap<DocumentId, Lineage>> index,
            String blueId) {
        PersistentOrderedMap<DocumentId, Lineage> bucket = index.get(
                requireBlueId(blueId));
        return bucket == null ? List.of() : bucket.values();
    }

    private static <K, V> BucketMutation<K, V> putBucket(
            PersistentOrderedMap<String, PersistentOrderedMap<K, V>> index,
            String blueId,
            Comparator<? super K> keyOrder,
            K key,
            V value) {
        String selected = requireBlueId(blueId);
        PersistentOrderedMap<K, V> bucket = index.get(selected);
        if (bucket == null) {
            bucket = PersistentOrderedMap.empty(keyOrder);
        }
        PersistentOrderedMap.Mutation<K, V> inner = bucket.put(
                Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, "value"));
        PersistentOrderedMap.Mutation<String, PersistentOrderedMap<K, V>>
                outer = index.put(selected, inner.map());
        return new BucketMutation<>(outer.map(), Math.addExact(
                inner.copiedNodes(), outer.copiedNodes()));
    }

    private static <K, V> BucketMutation<K, V> removeBucket(
            PersistentOrderedMap<String, PersistentOrderedMap<K, V>> index,
            String blueId,
            K key) {
        String selected = requireBlueId(blueId);
        PersistentOrderedMap<K, V> bucket = index.get(selected);
        if (bucket == null || !bucket.containsKey(key)) {
            throw new IllegalStateException(
                    "Indexed lineage bucket is incomplete for " + selected);
        }
        PersistentOrderedMap.Mutation<K, V> inner = bucket.remove(key);
        PersistentOrderedMap.Mutation<String, PersistentOrderedMap<K, V>>
                outer = inner.map().isEmpty()
                        ? index.remove(selected)
                        : index.put(selected, inner.map());
        return new BucketMutation<>(outer.map(), Math.addExact(
                inner.copiedNodes(), outer.copiedNodes()));
    }

    private static <K, V> void assertBucketsValid(
            PersistentOrderedMap<String, PersistentOrderedMap<K, V>> index) {
        index.assertStructurallyValid();
        index.values().forEach(
                PersistentOrderedMap::assertStructurallyValid);
    }

    private record BucketMutation<K, V>(
            PersistentOrderedMap<String, PersistentOrderedMap<K, V>> index,
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
