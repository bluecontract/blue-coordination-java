package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ManagedLineageIndexTest {
    private static final DocumentId A = DocumentId.of("runtime-a");
    private static final DocumentId B = DocumentId.of("runtime-b");

    @Test
    void insertionIndexesRuntimeIdentityAndExactLifecycleStatesOnly() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession a = start(engine, A);

            // when
            ManagedLineageIndex index = engine.documents().lineageIndex();
            ManagedLineageIndex.Lineage lineage = index.byDocumentId(A);

            // then
            assertEquals(1, index.lineageCount());
            assertEquals(A, lineage.documentId());
            assertEquals(a.authoredInitialBlueId(),
                    lineage.authoredInitialBlueId());
            assertEquals(a.revision(0L).after().blueId(),
                    lineage.initializedBlueId());
            assertEquals(0L, lineage.currentEpoch());
            assertEquals(a.currentRevision().after().blueId(),
                    lineage.currentBlueId());
            assertNull(index.byDocumentId(DocumentId.of("user-authored-value")),
                    "ordinary authored content must never become runtime identity");
        }
    }

    @Test
    void identicalAuthoredDocumentsRemainDistinctIndexedLineages() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession a = start(engine, A);
            DocumentSession b = start(engine, B);

            // when
            ManagedLineageIndex index = engine.documents().lineageIndex();

            // then
            assertEquals(a.authoredInitialBlueId(), b.authoredInitialBlueId());
            assertEquals(List.of(A, B), index.authoredInitialMatches(
                    a.authoredInitialBlueId()).stream()
                    .map(ManagedLineageIndex.Lineage::documentId)
                    .toList());
        }
    }

    @Test
    void atomicPublicationAdvancesOnlyChangedLineageAndRetainsOldIndexImage() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession a = start(engine, A);
            DocumentSession b = start(engine, B);
            InMemoryDocumentStore store = engine.documents();
            ManagedLineageIndex before = store.lineageIndex();
            ManagedLineageIndex.Lineage bBefore = before.byDocumentId(B);
            InMemoryDocumentStore.PublicationSnapshot snapshot =
                    store.publicationSnapshot();

            // when
            store.beginAtomicPublication(
                            "lineage-advance",
                            snapshot.occurrenceInventoryGeneration(),
                            snapshot.componentIndexGeneration())
                    .expectHead(A, a.epoch(),
                            a.currentRevision().after().blueId())
                    .stageDocument(
                            update(a),
                            a.layout(),
                            null,
                            a.activeSubscriptions(),
                            "lineage|a|1")
                    .stageComponentStates(List.of(component(a)))
                    .commit();
            ManagedLineageIndex after = store.lineageIndex();

            // then
            assertEquals(0L, before.byDocumentId(A).currentEpoch());
            assertEquals(1L, after.byDocumentId(A).currentEpoch());
            assertEquals(List.of(0L, 1L), after.byDocumentId(A).epochsFor(
                    a.currentRevision().after().blueId()));
            assertSame(bBefore, after.byDocumentId(B),
                    "an unrelated lineage row must be structurally retained");
        }
    }

    @Test
    void removalDeletesEveryExactLookupForOnlyThatLineage() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession a = start(engine, A);
            DocumentSession b = start(engine, B);
            InMemoryDocumentStore store = engine.documents();

            // when
            store.remove(A);
            ManagedLineageIndex after = store.lineageIndex();

            // then
            assertNull(after.byDocumentId(A));
            assertEquals(List.of(B), after.authoredInitialMatches(
                    b.authoredInitialBlueId()).stream()
                    .map(ManagedLineageIndex.Lineage::documentId)
                    .toList());
            assertEquals(List.of(), after.currentMatches(
                    a.currentRevision().after().blueId()).stream()
                    .filter(lineage -> lineage.documentId().equals(A))
                    .toList());
        }
    }

    @Test
    void randomizedPersistentUpdatesMatchReferenceAndPreserveAvlInvariants() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            LinkedHashMap<DocumentId, DocumentSession> reference =
                    new LinkedHashMap<>();
            ManagedLineageIndex index = ManagedLineageIndex.empty();
            for (int ordinal = 0; ordinal < 128; ordinal++) {
                DocumentId documentId = DocumentId.of(
                        "randomized-" + ordinal);
                DocumentSession session = engine.start(documentId, """
                        state: randomized-%d
                        """.formatted(ordinal));
                reference.put(documentId, session);
                index = index.withNewLineage(session);
                index.assertStructurallyValid();
            }
            List<DocumentId> randomized = new java.util.ArrayList<>(
                    reference.keySet());
            Collections.shuffle(randomized, new Random(0x5eedL));

            for (DocumentId documentId : randomized.subList(0, 64)) {
                DocumentSession prior = reference.get(documentId);
                DocumentSession advanced = prior.copyForAtomicPublication();
                advanced.commit(
                        update(prior),
                        prior.layout(),
                        null,
                        prior.activeSubscriptions(),
                        "randomized-advance|" + documentId.value());
                reference.put(documentId, advanced);
                index = index.withAdvancedRevision(advanced);
                index.assertStructurallyValid();
            }
            for (DocumentId documentId : randomized.subList(32, 112)) {
                reference.remove(documentId);
                index = index.withoutLineage(documentId);
                index.assertStructurallyValid();
            }

            assertEquals(reference.size(), index.lineageCount());
            assertEquals(
                    new TreeSet<>(reference.keySet()),
                    new TreeSet<>(index.documentIds()));
            for (Map.Entry<DocumentId, DocumentSession> entry
                    : reference.entrySet()) {
                ManagedLineageIndex.Lineage lineage = index.byDocumentId(
                        entry.getKey());
                assertEquals(entry.getValue().epoch(), lineage.currentEpoch());
                assertEquals(entry.getValue().currentRevision().after()
                        .blueId(), lineage.currentBlueId());
                assertEquals(List.of(entry.getKey()), index.currentMatches(
                        lineage.currentBlueId()).stream()
                        .map(ManagedLineageIndex.Lineage::documentId)
                        .toList());
            }
        }
    }

    private static DocumentSession start(
            DefaultCoordinationEngine engine,
            DocumentId runtimeDocumentId) {
        return engine.start(runtimeDocumentId, """
                userDocumentId: user-authored-value
                state: initial
                """);
    }

    private static DocumentRevision update(DocumentSession session) {
        return new DocumentRevision(
                session.documentId(),
                Math.addExact(session.epoch(), 1L),
                session.nextApplicationOrder(),
                DocumentRevision.Kind.CATCH_UP_COMPLETED,
                session.currentRevision().after(),
                session.currentRevision().after(),
                null,
                null,
                List.of(),
                0L);
    }

    private static ComponentSnapshot component(DocumentSession session) {
        return new ComponentSnapshot(
                hash('1'),
                hash('2'),
                Math.addExact(session.epoch(), 1L),
                ComponentKind.ACYCLIC,
                List.of(new blue.language.processor.closure.DocumentId(
                        session.documentId().value())),
                List.of(session.currentRevision().after().blueId()),
                null,
                null,
                null);
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }
}
