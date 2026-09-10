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
import java.util.TreeSet;

/** Durable cohort-local Contracts graph generations, keyed by document. */
final class ClosureGraphGenerationInventory {
    private final PersistentOrderedMap<DocumentId, Long> generations;
    private final int lastOperationComparisons;
    private final int lastOperationCopiedNodes;

    private ClosureGraphGenerationInventory(
            PersistentOrderedMap<DocumentId, Long> generations,
            Work work) {
        this.generations = Objects.requireNonNull(generations, "generations");
        Work exact = Objects.requireNonNull(work, "work");
        this.lastOperationComparisons = exact.comparisons;
        this.lastOperationCopiedNodes = exact.copiedNodes;
    }

    static ClosureGraphGenerationInventory empty() {
        return new ClosureGraphGenerationInventory(
                PersistentOrderedMap.empty(
                        EmbeddingBinding.DOCUMENT_ORDER),
                new Work());
    }

    /** Retains known lineages and initializes newly admitted documents at zero. */
    ClosureGraphGenerationInventory retainingDocuments(
            Collection<DocumentId> documentIds) {
        TreeSet<DocumentId> canonical = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        for (DocumentId documentId : Objects.requireNonNull(
                documentIds, "documentIds")) {
            canonical.add(Objects.requireNonNull(
                    documentId, "documentId"));
        }
        PersistentOrderedMap<DocumentId, Long> retained =
                PersistentOrderedMap.empty(
                        EmbeddingBinding.DOCUMENT_ORDER);
        Work work = new Work();
        for (DocumentId documentId : canonical) {
            PersistentOrderedMap.ReadResult<Long> existing =
                    generations.read(documentId);
            work.read(existing);
            PersistentOrderedMap.Mutation<DocumentId, Long> mutation =
                    retained.put(
                            documentId,
                            existing.found() ? existing.value() : 0L);
            work.mutation(mutation);
            retained = mutation.map();
        }
        return new ClosureGraphGenerationInventory(retained, work);
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
        long expectedGeneration = selected.platformCommitCompanion()
                .expectedInputGraphGeneration();
        LinkedHashMap<DocumentId, Long> expected = new LinkedHashMap<>();
        selected.platformCommitCompanion().expectedInputDocuments()
                .forEach(document -> {
                    DocumentId member = DocumentId.of(
                            document.documentId().value());
                    if (expected.putIfAbsent(
                            member, expectedGeneration) != null) {
                        throw new IllegalArgumentException(
                                "Duplicate expected graph-generation member "
                                        + member);
                    }
                });
        return apply(selected, expected);
    }

    /**
     * Applies one committing result after fencing each previously independent
     * member at its exact durable generation. Contracts receives the maximum
     * captured generation as its single deterministic merge generation; the
     * lower member generations remain exact CAS evidence rather than being
     * rewritten before publication.
     */
    ClosureGraphGenerationInventory apply(
            ClosureProcessResult result,
            Map<DocumentId, Long> expectedGenerations) {
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing closure result can advance graph state");
        }
        ClosureCommitCompanion companion =
                selected.platformCommitCompanion();
        LinkedHashMap<DocumentId, Long> expected =
                canonicalExpectedGenerations(expectedGenerations);
        LinkedHashSet<DocumentId> companionMembers = new LinkedHashSet<>();
        companion.expectedInputDocuments().forEach(document -> {
            DocumentId member = DocumentId.of(document.documentId().value());
            if (!companionMembers.add(member)) {
                throw new IllegalArgumentException(
                        "Duplicate expected graph-generation member "
                                + member);
            }
        });
        if (!companionMembers.equals(expected.keySet())) {
            throw new IllegalArgumentException(
                    "Closure result graph members differ from its exact "
                            + "generation fences");
        }
        long maximumExpected = maximumGeneration(expected.values());
        if (companion.expectedInputGraphGeneration() != maximumExpected) {
            throw new IllegalArgumentException(
                    "Closure result does not bind the maximum captured graph "
                            + "generation");
        }
        Work work = new Work();
        for (Map.Entry<DocumentId, Long> entry : expected.entrySet()) {
            long actual = require(entry.getKey(), work);
            if (actual != entry.getValue().longValue()) {
                throw new MultiDocumentPublicationTransaction
                        .AtomicPublicationCasException(
                                "Stale graph generation for " + entry.getKey()
                                        + ": expected " + entry.getValue()
                                        + " but found " + actual);
            }
        }
        Set<DocumentId> resultingMembers = new LinkedHashSet<>();
        for (ResultingDocument document : selected.resultingDocuments()) {
            resultingMembers.add(DocumentId.of(
                    document.documentId().value()));
        }
        if (!resultingMembers.equals(expected.keySet())) {
            throw new IllegalArgumentException(
                    "Closure result graph members differ from its input "
                            + "generation cohort");
        }

        long resultingGeneration = safeGeneration(
                selected.graphGeneration());
        PersistentOrderedMap<DocumentId, Long> replacement = generations;
        for (DocumentId member : resultingMembers) {
            PersistentOrderedMap.Mutation<DocumentId, Long> mutation =
                    replacement.put(member, resultingGeneration);
            work.mutation(mutation);
            replacement = mutation.map();
        }
        return new ClosureGraphGenerationInventory(replacement, work);
    }

    /** Installs only the processor-derived owned projection; dependency generations remain local. */
    ClosureGraphGenerationInventory applyOwned(ClosureProcessResult result,
            Map<DocumentId, Long> expectedGenerations) {
        return applyOwned(result, expectedGenerations, List.of());
    }

    /** Adds only authenticated owned births, with distinct absent fences for every new lineage. */
    ClosureGraphGenerationInventory applyOwned(ClosureProcessResult result,
            Map<DocumentId, Long> expectedGenerations, Collection<DocumentId> expectedAbsent) {
        var absent = new LinkedHashSet<>(expectedAbsent);
        var owners = new LinkedHashSet<>(expectedGenerations.keySet());
        if (absent.size() != expectedAbsent.size() || absent.stream().anyMatch(owners::contains)) {
            throw new IllegalArgumentException("Rooted graph fences repeat or overlap a lineage");
        }
        owners.addAll(absent);
        if (!result.commits() || result.rootedProjection() == null
                || !owners.equals(new LinkedHashSet<>(RootedResultScope.members(result)))) {
            throw new IllegalArgumentException("Rooted graph publication requires exactly the derived owner fences");
        }
        var input = result.rootedProjection().inputSnapshot();
        if (!input.closureIdentity().equals(result.commitCompanion().inputClosureIdentity())
                || input.graphGeneration() != result.commitCompanion().expectedInputGraphGeneration()) {
            throw new IllegalArgumentException("Rooted graph evidence differs from the exact companion input");
        }
        Work work = new Work();
        PersistentOrderedMap<DocumentId, Long> replacement = generations;
        for (Map.Entry<DocumentId, Long> fence : canonicalExpectedGenerations(expectedGenerations).entrySet()) {
            long actual = require(fence.getKey(), work);
            if (actual != fence.getValue()) {
                throw new MultiDocumentPublicationTransaction.AtomicPublicationCasException(
                        "Stale rooted graph generation for " + fence.getKey());
            }
            if (result.graphGeneration() < actual) {
                throw new IllegalArgumentException("A rooted publication cannot rewind an owned graph generation");
            }
            var mutation = replacement.put(fence.getKey(), safeGeneration(result.graphGeneration()));
            work.mutation(mutation);
            replacement = mutation.map();
        }
        for (DocumentId id : absent) {
            var existing = generations.read(id);
            work.read(existing);
            if (existing.found()) {
                throw new MultiDocumentPublicationTransaction.AtomicPublicationCasException(
                        "Rooted graph birth already exists " + id);
            }
            var before = input.managedDocument(ContractsClosureAdapter.closureId(id));
            var after = result.rootedProjection().ownedDocuments().stream()
                    .filter(document -> document.documentId().value().equals(id.value())).findFirst().orElseThrow();
            if (before == null || before.initialized() || before.epoch() != 0L
                    || !after.initialized() || after.epoch() != 0L || !before.blueId().equals(after.beforeBlueId())) {
                throw new IllegalArgumentException("Rooted graph birth lacks exact epoch-zero initialization " + id);
            }
            var mutation = replacement.put(id, safeGeneration(result.graphGeneration()));
            work.mutation(mutation);
            replacement = mutation.map();
        }
        return new ClosureGraphGenerationInventory(replacement, work);
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
        Work work = new Work();
        for (DocumentId documentId : admitted) {
            PersistentOrderedMap.ReadResult<Long> existing =
                    generations.read(documentId);
            work.read(existing);
            if (existing.found()) {
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
        long resultingGeneration = safeGeneration(
                selected.graphGeneration());
        PersistentOrderedMap<DocumentId, Long> replacement = generations;
        for (DocumentId documentId : admitted) {
            PersistentOrderedMap.Mutation<DocumentId, Long> mutation =
                    replacement.put(
                            documentId, resultingGeneration);
            work.mutation(mutation);
            replacement = mutation.map();
        }
        return new ClosureGraphGenerationInventory(replacement, work);
    }

    /**
     * Applies one verified PROCESS result that atomically expands an existing
     * cohort with lineages which were absent at capture time.
     *
     * <p>Existing members retain the ordinary exact graph-generation CAS.
     * New members have no durable predecessor generation; they are installed
     * only when the same result and transaction also prove their absence.</p>
     */
    ClosureGraphGenerationInventory applyExpansion(
            ClosureProcessResult result,
            Collection<DocumentId> expectedPresent,
            Collection<DocumentId> expectedAbsent) {
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing closure result can expand graph state");
        }
        long expectedGeneration = selected.platformCommitCompanion()
                .expectedInputGraphGeneration();
        LinkedHashMap<DocumentId, Long> present = new LinkedHashMap<>();
        for (DocumentId documentId : Objects.requireNonNull(
                expectedPresent, "expectedPresent")) {
            if (present.putIfAbsent(
                    Objects.requireNonNull(documentId, "documentId"),
                    expectedGeneration) != null) {
                throw new IllegalArgumentException(
                        "Duplicate present closure expansion member "
                                + documentId);
            }
        }
        return applyExpansion(selected, present, expectedAbsent);
    }

    /** Applies an expansion while retaining exact per-member generations. */
    ClosureGraphGenerationInventory applyExpansion(
            ClosureProcessResult result,
            Map<DocumentId, Long> expectedPresent,
            Collection<DocumentId> expectedAbsent) {
        ClosureProcessResult selected = Objects.requireNonNull(
                result, "result");
        if (!selected.commits()
                || selected.platformCommitCompanion() == null) {
            throw new IllegalArgumentException(
                    "Only a committing closure result can expand graph state");
        }
        LinkedHashMap<DocumentId, Long> present =
                canonicalExpectedGenerations(expectedPresent);
        LinkedHashSet<DocumentId> absent = new LinkedHashSet<>(
                Objects.requireNonNull(expectedAbsent, "expectedAbsent"));
        if (present.isEmpty() || absent.isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure expansion requires present and absent members");
        }
        LinkedHashSet<DocumentId> overlap = new LinkedHashSet<>(
                present.keySet());
        overlap.retainAll(absent);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "Closure expansion fences overlap " + overlap);
        }
        long maximumExpected = maximumGeneration(present.values());
        if (selected.platformCommitCompanion()
                .expectedInputGraphGeneration() != maximumExpected) {
            throw new IllegalArgumentException(
                    "Closure expansion does not bind the maximum captured "
                            + "graph generation");
        }
        Work work = new Work();
        for (Map.Entry<DocumentId, Long> entry : present.entrySet()) {
            long actual = require(entry.getKey(), work);
            if (actual != entry.getValue().longValue()) {
                throw new MultiDocumentPublicationTransaction
                        .AtomicPublicationCasException(
                                "Stale graph generation for " + entry.getKey()
                                        + ": expected " + entry.getValue()
                                        + " but found " + actual);
            }
        }
        for (DocumentId documentId : absent) {
            PersistentOrderedMap.ReadResult<Long> existing =
                    generations.read(documentId);
            work.read(existing);
            if (existing.found()) {
                throw new MultiDocumentPublicationTransaction
                        .AtomicPublicationCasException(
                                "Closure expansion graph lineage already exists "
                                        + documentId);
            }
        }

        LinkedHashSet<DocumentId> expectedMembers = new LinkedHashSet<>(
                present.keySet());
        expectedMembers.addAll(absent);
        LinkedHashSet<DocumentId> companionMembers = new LinkedHashSet<>();
        selected.platformCommitCompanion().expectedInputDocuments()
                .forEach(document -> companionMembers.add(DocumentId.of(
                        document.documentId().value())));
        LinkedHashSet<DocumentId> resultingMembers = new LinkedHashSet<>();
        selected.resultingDocuments().forEach(document ->
                resultingMembers.add(DocumentId.of(
                        document.documentId().value())));
        if (!expectedMembers.equals(companionMembers)
                || !expectedMembers.equals(resultingMembers)) {
            throw new IllegalArgumentException(
                    "Closure expansion graph members are incomplete");
        }

        long resultingGeneration = safeGeneration(
                selected.graphGeneration());
        PersistentOrderedMap<DocumentId, Long> replacement = generations;
        for (DocumentId documentId : expectedMembers) {
            PersistentOrderedMap.Mutation<DocumentId, Long> mutation =
                    replacement.put(
                            documentId, resultingGeneration);
            work.mutation(mutation);
            replacement = mutation.map();
        }
        return new ClosureGraphGenerationInventory(replacement, work);
    }

    private static LinkedHashMap<DocumentId, Long>
            canonicalExpectedGenerations(
                    Map<DocumentId, Long> expectedGenerations) {
        Map<DocumentId, Long> selected = Objects.requireNonNull(
                expectedGenerations, "expectedGenerations");
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Exact graph-generation fences must not be empty");
        }
        TreeSet<DocumentId> ordered = new TreeSet<>(
                EmbeddingBinding.DOCUMENT_ORDER);
        ordered.addAll(selected.keySet());
        LinkedHashMap<DocumentId, Long> result = new LinkedHashMap<>();
        for (DocumentId documentId : ordered) {
            Long generation = Objects.requireNonNull(
                    selected.get(documentId), "graphGeneration");
            result.put(
                    Objects.requireNonNull(documentId, "documentId"),
                    safeGeneration(generation.longValue()));
        }
        return result;
    }

    private static long maximumGeneration(Collection<Long> generations) {
        long maximum = -1L;
        for (Long generation : Objects.requireNonNull(
                generations, "generations")) {
            maximum = Math.max(maximum, safeGeneration(Objects.requireNonNull(
                    generation, "graphGeneration").longValue()));
        }
        if (maximum < 0L) {
            throw new IllegalArgumentException(
                    "A graph-generation cohort must not be empty");
        }
        return maximum;
    }

    Map<DocumentId, Long> generations() {
        List<DocumentId> documents = generations.keys();
        List<Long> values = generations.values();
        LinkedHashMap<DocumentId, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < documents.size(); index++) {
            result.put(documents.get(index), values.get(index));
        }
        return Collections.unmodifiableMap(result);
    }

    List<DocumentId> documents() {
        return generations.keys();
    }

    /** Comparator calls made by persistent-index reads and mutations only. */
    int lastOperationComparisonsForTesting() {
        return lastOperationComparisons;
    }

    /** Persistent tree nodes allocated by the operation that built this view. */
    int lastOperationCopiedNodesForTesting() {
        return lastOperationCopiedNodes;
    }

    int lookupStepsForTesting(DocumentId documentId) {
        return generations.lookupSteps(Objects.requireNonNull(
                documentId, "documentId"));
    }

    Object rootIdentityForTesting() {
        return generations.rootIdentityForTesting();
    }

    int sharedNodeCountForTesting(
            ClosureGraphGenerationInventory other) {
        return generations.sharedNodeCountForTesting(
                Objects.requireNonNull(other, "other").generations);
    }

    void assertStructurallyValidForTesting() {
        generations.assertStructurallyValid();
    }

    private long require(DocumentId documentId, Work work) {
        PersistentOrderedMap.ReadResult<Long> result = generations.read(
                Objects.requireNonNull(documentId, "documentId"));
        work.read(result);
        if (!result.found()) {
            throw new IllegalArgumentException(
                    "No durable graph generation for " + documentId);
        }
        return result.value();
    }

    private static long safeGeneration(long generation) {
        return MultiDocumentPublicationTransaction.requireSafeInteger(
                generation, "graphGeneration");
    }

    private static final class Work {
        private int comparisons;
        private int copiedNodes;

        private void read(PersistentOrderedMap.ReadResult<?> read) {
            comparisons = Math.addExact(
                    comparisons,
                    Objects.requireNonNull(read, "read").comparisons());
        }

        private void mutation(PersistentOrderedMap.Mutation<?, ?> mutation) {
            PersistentOrderedMap.Mutation<?, ?> exact =
                    Objects.requireNonNull(mutation, "mutation");
            comparisons = Math.addExact(
                    comparisons, exact.comparisons());
            copiedNodes = Math.addExact(
                    copiedNodes, exact.copiedNodes());
        }
    }
}
