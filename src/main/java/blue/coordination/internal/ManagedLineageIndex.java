package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable exact-state indexes for managed-document lineage resolution.
 *
 * <p>The store advances this value from the sessions changed by one durable
 * publication. Reads are therefore bounded index lookups and publication does
 * not rebuild lineage evidence by scanning the ambient session catalog.</p>
 */
final class ManagedLineageIndex {
    private static final ManagedLineageIndex EMPTY = new ManagedLineageIndex(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    private final Map<DocumentId, Lineage> byDocumentId;
    private final Map<String, List<Lineage>> byAuthoredInitialBlueId;
    private final Map<String, List<Lineage>> byInitializedBlueId;
    private final Map<String, List<RetainedState>> byRetainedBlueId;
    private final Map<String, List<Lineage>> byCurrentBlueId;

    private ManagedLineageIndex(
            Map<DocumentId, Lineage> byDocumentId,
            Map<String, List<Lineage>> byAuthoredInitialBlueId,
            Map<String, List<Lineage>> byInitializedBlueId,
            Map<String, List<RetainedState>> byRetainedBlueId,
            Map<String, List<Lineage>> byCurrentBlueId) {
        this.byDocumentId = immutableDocumentMap(byDocumentId);
        this.byAuthoredInitialBlueId = immutableLineageIndex(
                byAuthoredInitialBlueId);
        this.byInitializedBlueId = immutableLineageIndex(
                byInitializedBlueId);
        this.byRetainedBlueId = immutableRetainedIndex(byRetainedBlueId);
        this.byCurrentBlueId = immutableLineageIndex(byCurrentBlueId);
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
        return replacing(null, lineage);
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
        return replacing(prior, advanced);
    }

    /** Removes one retired lineage without inspecting any other session. */
    ManagedLineageIndex withoutLineage(DocumentId documentId) {
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        Lineage prior = byDocumentId.get(selected);
        return prior == null ? this : replacing(prior, null);
    }

    Lineage byDocumentId(DocumentId documentId) {
        return byDocumentId.get(Objects.requireNonNull(
                documentId, "documentId"));
    }

    List<Lineage> authoredInitialMatches(String blueId) {
        return byAuthoredInitialBlueId.getOrDefault(
                requireBlueId(blueId), List.of());
    }

    List<Lineage> initializedMatches(String blueId) {
        return byInitializedBlueId.getOrDefault(
                requireBlueId(blueId), List.of());
    }

    List<RetainedState> retainedMatches(String blueId) {
        return byRetainedBlueId.getOrDefault(
                requireBlueId(blueId), List.of());
    }

    List<Lineage> currentMatches(String blueId) {
        return byCurrentBlueId.getOrDefault(
                requireBlueId(blueId), List.of());
    }

    Set<DocumentId> documentIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
                byDocumentId.keySet()));
    }

    int lineageCount() {
        return byDocumentId.size();
    }

    private ManagedLineageIndex replacing(
            Lineage prior,
            Lineage replacement) {
        LinkedHashMap<DocumentId, Lineage> documents = new LinkedHashMap<>(
                byDocumentId);
        LinkedHashMap<String, List<Lineage>> authored = mutableLineageIndex(
                byAuthoredInitialBlueId);
        LinkedHashMap<String, List<Lineage>> initialized = mutableLineageIndex(
                byInitializedBlueId);
        LinkedHashMap<String, List<RetainedState>> retained =
                mutableRetainedIndex(byRetainedBlueId);
        LinkedHashMap<String, List<Lineage>> current = mutableLineageIndex(
                byCurrentBlueId);
        if (prior != null) {
            documents.remove(prior.documentId());
            removeLineage(authored, prior.authoredInitialBlueId(), prior);
            removeLineage(initialized, prior.initializedBlueId(), prior);
            removeLineage(current, prior.currentBlueId(), prior);
            for (RetainedState state : prior.retainedStates()) {
                removeRetained(retained, state.blueId(), state);
            }
        }
        if (replacement != null) {
            documents.put(replacement.documentId(), replacement);
            add(authored, replacement.authoredInitialBlueId(), replacement);
            add(initialized, replacement.initializedBlueId(), replacement);
            add(current, replacement.currentBlueId(), replacement);
            for (RetainedState state : replacement.retainedStates()) {
                add(retained, state.blueId(), state);
            }
        }
        return documents.isEmpty()
                ? empty()
                : new ManagedLineageIndex(
                        documents, authored, initialized, retained, current);
    }

    private static <T> void add(
            Map<String, List<T>> index,
            String blueId,
            T value) {
        index.computeIfAbsent(
                requireBlueId(blueId), ignored -> new ArrayList<>())
                .add(Objects.requireNonNull(value, "value"));
    }

    private static void removeLineage(
            Map<String, List<Lineage>> index,
            String blueId,
            Lineage lineage) {
        remove(index, blueId, candidate -> candidate.documentId().equals(
                lineage.documentId()));
    }

    private static void removeRetained(
            Map<String, List<RetainedState>> index,
            String blueId,
            RetainedState state) {
        remove(index, blueId, candidate -> candidate.documentId().equals(
                state.documentId()) && candidate.epoch() == state.epoch());
    }

    private static <T> void remove(
            Map<String, List<T>> index,
            String blueId,
            java.util.function.Predicate<T> selector) {
        String selected = requireBlueId(blueId);
        List<T> bucket = index.get(selected);
        if (bucket == null || !bucket.removeIf(selector)) {
            throw new IllegalStateException(
                    "Indexed lineage bucket is incomplete for " + selected);
        }
        if (bucket.isEmpty()) {
            index.remove(selected);
        }
    }

    private static Map<DocumentId, Lineage> immutableDocumentMap(
            Map<DocumentId, Lineage> source) {
        TreeMap<DocumentId, Lineage> canonical = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        canonical.putAll(Objects.requireNonNull(source, "source"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(canonical));
    }

    private static Map<String, List<Lineage>> immutableLineageIndex(
            Map<String, List<Lineage>> source) {
        TreeMap<String, List<Lineage>> canonical = new TreeMap<>(
                EmbeddingBinding.TEXT_ORDER);
        Objects.requireNonNull(source, "source").forEach((blueId, lineages) ->
                canonical.put(
                        requireBlueId(blueId),
                        lineages.stream()
                                .sorted((left, right) ->
                                        EmbeddingBinding.DOCUMENT_ORDER.compare(
                                                left.documentId(),
                                                right.documentId()))
                                .toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(canonical));
    }

    private static Map<String, List<RetainedState>> immutableRetainedIndex(
            Map<String, List<RetainedState>> source) {
        TreeMap<String, List<RetainedState>> canonical = new TreeMap<>(
                EmbeddingBinding.TEXT_ORDER);
        Objects.requireNonNull(source, "source").forEach((blueId, states) ->
                canonical.put(
                        requireBlueId(blueId),
                        states.stream()
                                .sorted((left, right) -> {
                                    int order = EmbeddingBinding.DOCUMENT_ORDER
                                            .compare(
                                                    left.documentId(),
                                                    right.documentId());
                                    return order != 0
                                            ? order
                                            : Long.compare(
                                                    left.epoch(),
                                                    right.epoch());
                                })
                                .toList()));
        return Collections.unmodifiableMap(new LinkedHashMap<>(canonical));
    }

    private static LinkedHashMap<String, List<Lineage>> mutableLineageIndex(
            Map<String, List<Lineage>> source) {
        LinkedHashMap<String, List<Lineage>> mutable = new LinkedHashMap<>();
        source.forEach((blueId, lineages) -> mutable.put(
                blueId, new ArrayList<>(lineages)));
        return mutable;
    }

    private static LinkedHashMap<String, List<RetainedState>>
            mutableRetainedIndex(Map<String, List<RetainedState>> source) {
        LinkedHashMap<String, List<RetainedState>> mutable =
                new LinkedHashMap<>();
        source.forEach((blueId, states) -> mutable.put(
                blueId, new ArrayList<>(states)));
        return mutable;
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
