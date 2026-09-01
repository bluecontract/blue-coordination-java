package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void representationRebindMovesOnlyCurrentIdentityAndPreservesSourceEpoch() {
        // given
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession session = start(engine, A);
            ManagedLineageIndex before = engine.documents().lineageIndex();
            String retainedBlueId = session.currentRevision().after().blueId();
            ExactValue representation = exact("component-representation");
            DocumentSession rebound = session.copyForAtomicPublication();

            // when
            rebound.rebindComponentRepresentation(
                    session.epoch(),
                    layout(representation),
                    session.activeSubscriptions(),
                    "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            ManagedLineageIndex after = before
                    .withComponentRepresentationRebound(rebound);

            // then
            assertEquals(session.epoch(), rebound.epoch());
            assertEquals(session.revisions(), rebound.revisions());
            assertEquals(retainedBlueId,
                    after.byDocumentId(A).retainedStates().get(0).blueId());
            assertEquals(representation.blueId(),
                    after.byDocumentId(A).currentBlueId());
            assertTrue(after.currentMatches(representation.blueId()).stream()
                    .anyMatch(lineage -> lineage.documentId().equals(A)));
            assertFalse(after.currentMatches(retainedBlueId).stream()
                    .anyMatch(lineage -> lineage.documentId().equals(A)));
            assertEquals(List.of(0L),
                    after.byDocumentId(A).epochsFor(retainedBlueId));
            assertEquals(SessionStatus.CATCHING_UP, rebound.status());
            assertEquals(retainedBlueId,
                    rebound.readyRepresentation().blueId());
            rebound.markGraphPublished();
            rebound.markReady(session.readyThrough());
            assertEquals(representation.blueId(),
                    rebound.readyRepresentation().blueId());
        }
    }

    @Test
    void representationRebindBackToRetainedHeadClearsTransientFence() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession source = start(engine, A);
            String retainedHead = source.currentRevision().after().blueId();
            DocumentSession rebound = source.copyForAtomicPublication();
            ManagedLineageIndex incremental = engine.documents()
                    .lineageIndex();

            // when
            rebound.rebindComponentRepresentation(
                    0L,
                    layout(exact("merged-cycle-representation")),
                    rebound.activeSubscriptions(),
                    hash('a'));
            incremental = incremental.withComponentRepresentationRebound(
                    rebound);
            rebound.rebindComponentRepresentation(
                    0L,
                    source.layout(),
                    rebound.activeSubscriptions(),
                    hash('b'));
            incremental = incremental.withComponentRepresentationRebound(
                    rebound);

            // rebind-back checkpoint
            ManagedLineageIndex.Lineage restored = incremental
                    .byDocumentId(A);
            assertEquals(retainedHead, restored.currentBlueId());
            assertEquals(-1L, restored.lastNonReplayableEpoch());
            assertEquals(ManagedLineageIndex.Lineage.from(rebound), restored,
                    "incremental and reconstructed continuity must agree");
            assertTrue(restored.isReplayableHistoricalPosition(-1L));

            // Advance the restored lineage to its next retained epoch.
            advance(
                    rebound,
                    exact("ordinary-epoch-one"),
                    "rebind-back|epoch-one");
            incremental = incremental.withAdvancedRevision(rebound);

            // then
            ManagedLineageIndex.Lineage advanced = incremental
                    .byDocumentId(A);
            assertEquals(ManagedLineageIndex.Lineage.from(rebound), advanced);
            assertEquals(-1L, advanced.lastNonReplayableEpoch());
            assertTrue(advanced.isReplayableHistoricalPosition(0L),
                    "the restored epoch-zero head must become a valid "
                            + "retained predecessor after epoch one");
        }
    }

    @Test
    void rebindBackAtLaterHeadPreservesEarlierAnchoredFence() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession session = start(engine, A)
                    .copyForAtomicPublication();
            ManagedLineageIndex incremental = engine.documents()
                    .lineageIndex();
            session.rebindComponentRepresentation(
                    0L,
                    layout(exact("anchored-epoch-zero-representation")),
                    session.activeSubscriptions(),
                    hash('c'));
            incremental = incremental.withComponentRepresentationRebound(
                    session);
            advance(session, exact("epoch-one"), "anchor|epoch-one");
            incremental = incremental.withAdvancedRevision(session);
            ExactValue retainedEpochOne = session.currentRevision().after();

            // when
            session.rebindComponentRepresentation(
                    1L,
                    layout(exact("transient-epoch-one-representation")),
                    session.activeSubscriptions(),
                    hash('d'));
            incremental = incremental.withComponentRepresentationRebound(
                    session);
            session.rebindComponentRepresentation(
                    1L,
                    layout(retainedEpochOne),
                    session.activeSubscriptions(),
                    hash('e'));
            incremental = incremental.withComponentRepresentationRebound(
                    session);

            // rebind-back checkpoint
            ManagedLineageIndex.Lineage restored = incremental
                    .byDocumentId(A);
            assertEquals(0L, restored.lastNonReplayableEpoch(),
                    "rebind-back must clear only the transient epoch-one "
                            + "boundary");
            assertEquals(ManagedLineageIndex.Lineage.from(session), restored);

            // Advance beyond the restored retained head.
            advance(session, exact("epoch-two"), "anchor|epoch-two");
            incremental = incremental.withAdvancedRevision(session);

            // then
            ManagedLineageIndex.Lineage advanced = incremental
                    .byDocumentId(A);
            assertEquals(ManagedLineageIndex.Lineage.from(session), advanced);
            assertFalse(advanced.isReplayableHistoricalPosition(0L));
            assertTrue(advanced.isReplayableHistoricalPosition(1L),
                    "the restored epoch-one head must remain contiguous");
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
            // given
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

            // when
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

            // then
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

    private static void advance(
            DocumentSession session,
            ExactValue after,
            String receipt) {
        DocumentRevision revision = new DocumentRevision(
                session.documentId(),
                Math.addExact(session.epoch(), 1L),
                session.nextApplicationOrder(),
                DocumentRevision.Kind.CATCH_UP_COMPLETED,
                session.currentRepresentation(),
                after,
                null,
                null,
                List.of(),
                0L);
        session.commit(
                revision,
                layout(after),
                null,
                session.activeSubscriptions(),
                receipt);
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

    private static EmbeddedOnlyLayout layout(ExactValue value) {
        return new EmbeddedOnlyLayout(
                value,
                value.frozen(),
                Map.of(JsonPointer.ROOT, value),
                List.of(),
                List.of(),
                EmbeddedLayoutPlan.managedRoot(
                        new RoutingSurface(List.of(), false)));
    }

    private static ExactValue exact(String state) {
        return ExactValue.verified(new Node().properties(
                "state", new Node().value(state)));
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }
}
