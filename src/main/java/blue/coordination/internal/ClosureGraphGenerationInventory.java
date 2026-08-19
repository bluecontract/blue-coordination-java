package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Durable cohort-local Contracts graph generations, keyed by document. */
final class ClosureGraphGenerationInventory {
    private final Map<DocumentId, Long> generations;

    private ClosureGraphGenerationInventory(Map<DocumentId, Long> values) {
        TreeMap<DocumentId, Long> canonical = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        Objects.requireNonNull(values, "values").forEach((documentId,
                generation) -> canonical.put(
                        Objects.requireNonNull(documentId, "documentId"),
                        MultiDocumentPublicationTransaction.requireSafeInteger(
                                Objects.requireNonNull(
                                        generation, "graphGeneration"),
                                "graphGeneration")));
        this.generations = Collections.unmodifiableMap(
                new LinkedHashMap<>(canonical));
    }

    static ClosureGraphGenerationInventory empty() {
        return new ClosureGraphGenerationInventory(Map.of());
    }

    /** Retains known lineages and initializes newly admitted documents at zero. */
    ClosureGraphGenerationInventory retainingDocuments(
            Collection<DocumentId> documentIds) {
        TreeMap<DocumentId, Long> retained = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : Objects.requireNonNull(
                documentIds, "documentIds")) {
            DocumentId exact = Objects.requireNonNull(
                    documentId, "documentId");
            retained.put(exact, generations.getOrDefault(exact, 0L));
        }
        return new ClosureGraphGenerationInventory(retained);
    }

    long require(DocumentId documentId) {
        DocumentId selected = Objects.requireNonNull(
                documentId, "documentId");
        Long generation = generations.get(selected);
        if (generation == null) {
            throw new IllegalArgumentException(
                    "No durable graph generation for " + selected);
        }
        return generation.longValue();
    }

    /** Requires one connected cohort to share exactly one durable generation. */
    long requireCohortGeneration(Collection<DocumentId> members) {
        ArrayList<DocumentId> exact = new ArrayList<>(Objects.requireNonNull(
                members, "members"));
        if (exact.isEmpty()) {
            throw new IllegalArgumentException(
                    "A graph-generation cohort must not be empty");
        }
        long generation = require(exact.get(0));
        for (int index = 1; index < exact.size(); index++) {
            DocumentId member = exact.get(index);
            long candidate = require(member);
            if (candidate != generation) {
                throw new IllegalStateException(
                        "Connected cohort has divergent durable graph "
                                + "generations: " + exact);
            }
        }
        return generation;
    }

    /**
     * Applies one already-validated committing closure result. Every resulting
     * member receives the result generation, including members separated by a
     * split; disconnected lineages remain untouched.
     */
    ClosureGraphGenerationInventory apply(ClosureProcessResult result) {
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing closure result can advance graph state");
        }
        ClosureCommitCompanion companion =
                selected.platformCommitCompanion();
        Set<DocumentId> expectedMembers = new LinkedHashSet<>();
        companion.expectedInputDocuments().forEach(document -> {
            DocumentId member = DocumentId.of(document.documentId().value());
            if (!expectedMembers.add(member)) {
                throw new IllegalArgumentException(
                        "Duplicate expected graph-generation member "
                                + member);
            }
            long actual = require(member);
            if (actual != companion.expectedInputGraphGeneration()) {
                throw new MultiDocumentPublicationTransaction
                        .AtomicPublicationCasException(
                                "Stale graph generation for " + member
                                        + ": expected "
                                        + companion
                                                .expectedInputGraphGeneration()
                                        + " but found " + actual);
            }
        });
        Set<DocumentId> resultingMembers = new LinkedHashSet<>();
        for (ResultingDocument document : selected.resultingDocuments()) {
            resultingMembers.add(DocumentId.of(
                    document.documentId().value()));
        }
        if (!resultingMembers.equals(expectedMembers)) {
            throw new IllegalArgumentException(
                    "Closure result graph members differ from its input "
                            + "generation cohort");
        }

        TreeMap<DocumentId, Long> replacement = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        replacement.putAll(generations);
        for (DocumentId member : resultingMembers) {
            replacement.put(member, selected.graphGeneration());
        }
        return new ClosureGraphGenerationInventory(replacement);
    }

    /**
     * Installs graph generations for one verified all-new closure admission.
     * Existing lineages are rejected rather than silently treated as updates.
     */
    ClosureGraphGenerationInventory admit(
            ClosureProcessResult result,
            Collection<DocumentId> expectedAbsent) {
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing closure admission can install graph state");
        }
        LinkedHashSet<DocumentId> admitted = new LinkedHashSet<>(
                Objects.requireNonNull(expectedAbsent, "expectedAbsent"));
        if (admitted.isEmpty()) {
            throw new IllegalArgumentException(
                    "Closure admission must contain a new document");
        }
        for (DocumentId documentId : admitted) {
            if (generations.containsKey(documentId)) {
                throw new MultiDocumentPublicationTransaction
                        .AtomicPublicationCasException(
                                "Closure admission graph lineage already exists "
                                        + documentId);
            }
        }
        LinkedHashSet<DocumentId> companionMembers = new LinkedHashSet<>();
        selected.platformCommitCompanion().expectedInputDocuments()
                .forEach(document -> companionMembers.add(DocumentId.of(
                        document.documentId().value())));
        LinkedHashSet<DocumentId> resultingMembers = new LinkedHashSet<>();
        selected.resultingDocuments().forEach(document ->
                resultingMembers.add(DocumentId.of(
                        document.documentId().value())));
        if (!admitted.equals(companionMembers)
                || !admitted.equals(resultingMembers)) {
            throw new IllegalArgumentException(
                    "Closure admission graph members are incomplete");
        }
        TreeMap<DocumentId, Long> replacement = new TreeMap<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        replacement.putAll(generations);
        admitted.forEach(documentId -> replacement.put(
                documentId, selected.graphGeneration()));
        return new ClosureGraphGenerationInventory(replacement);
    }

    Map<DocumentId, Long> generations() {
        return generations;
    }

    List<DocumentId> documents() {
        return List.copyOf(generations.keySet());
    }
}
