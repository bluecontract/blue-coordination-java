package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedScopeKey;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MultiDocumentPublicationTransactionTest {
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");
    private static final String A_ROOT_SCOPE_IDENTITY =
            "sha256:1daa50609cd58f0d3ffb483be2ed6b3cf340e3d1f3de96d5101778a58fa4aa9e";

    @Test
    void publishesMultipleHeadsAndAllTypedEvidenceWithOneSwap() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession originalA = start(engine, A);
            DocumentSession originalB = start(engine, B);
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot before =
                    store.publicationSnapshot();
            ManagedOccurrenceInventory inventory = inventory(
                    originalB.currentRevision().after().blueId());
            PublicEventOccurrence event = event();
            CheckpointWrite checkpoint = checkpoint(
                    originalA.currentRevision().after().blueId());

            transaction(store, "multi-head", before)
                    .expectHead(A, 0L, head(originalA))
                    .expectHead(B, 0L, head(originalB))
                    .stageDocument(update(originalA), originalA.layout(), null,
                            originalA.activeSubscriptions(), "multi|a|1")
                    .stageDocument(update(originalB), originalB.layout(), null,
                            originalB.activeSubscriptions(), "multi|b|1")
                    .stageOccurrenceInventory(
                            inventory,
                            before.occurrenceInventoryGeneration() + 1L,
                            before.componentIndexGeneration() + 1L)
                    .stageComponentStates(List.of(
                            component(originalA, '1', '2'),
                            component(originalB, '3', '4')))
                    .stageOutbox(List.of(event))
                    .stageCheckpointEvidence(List.of(checkpoint))
                    .commit();

            // when
            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();

            // then
            assertEquals(1L, after.requireHead(A).epoch());
            assertEquals(1L, after.requireHead(B).epoch());
            assertEquals(head(originalA), after.requireHead(A).blueId());
            assertEquals(head(originalB), after.requireHead(B).blueId());
            assertEquals(1L, after.occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration() + 1L,
                    after.componentIndexGeneration());
            assertEquals(inventory.rows(),
                    after.occurrenceInventory().rows());
            assertEquals(List.of(event), after.outbox());
            assertEquals(List.of(checkpoint), after.checkpointEvidence());
            assertEquals(2, after.componentStates().size());
            assertEquals(List.of(B.value(), A.value()),
                    after.componentStates().stream()
                            .flatMap(component -> component
                                    .orderedMemberDocumentIds().stream())
                            .map(blue.language.processor.closure.DocumentId
                                    ::value)
                            .toList(),
                    "target component must precede its embedding source");
            assertTrue(after.publicationReceipts().contains("multi-head"));
            assertEquals(List.of(B), after.componentIndex().targets(
                    after.componentIndex().component(A)).stream()
                    .flatMap(component -> component.members().stream())
                    .toList());

            assertEquals(0L, originalA.epoch(),
                    "the previously published session image must not mutate");
            assertEquals(0L, originalB.epoch(),
                    "the previously published session image must not mutate");
            assertEquals(0L, before.requireHead(A).epoch());
            assertEquals(0L, before.requireHead(B).epoch());
        }
    }

    @Test
    void staleHeadCasPublishesNothingFromTheLosingAttempt() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession original = start(engine, A);
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot base =
                    store.publicationSnapshot();

            MultiDocumentPublicationTransaction stale = transaction(
                    store, "stale", base)
                    .expectHead(A, 0L, head(original))
                    .stageDocument(update(original), original.layout(), null,
                            original.activeSubscriptions(), "stale|a|1")
                    .stageComponentStates(List.of(
                            component(original, '3', '4')));

            transaction(store, "winner", base)
                    .expectHead(A, 0L, head(original))
                    .stageDocument(update(original), original.layout(), null,
                            original.activeSubscriptions(), "winner|a|1")
                    .stageComponentStates(List.of(
                            component(original, '1', '2')))
                    .commit();
            InMemoryDocumentStore.PublicationSnapshot winner =
                    store.publicationSnapshot();

            // when
            MultiDocumentPublicationTransaction.AtomicPublicationCasException

                    // then
                    failure = assertThrows(
                            MultiDocumentPublicationTransaction
                                    .AtomicPublicationCasException.class,
                            stale::commit);
            assertTrue(failure.getMessage().contains("Stale document head"));
            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();
            assertEquals(winner.documentHeads(), after.documentHeads());
            assertEquals(winner.componentStates(), after.componentStates());
            assertEquals(winner.publicationReceipts(),
                    after.publicationReceipts());
            assertEquals(List.of("winner"),
                    after.publicationReceipts().stream().toList());
        }
    }

    @Test
    void staleManagedTopologyGenerationPublishesNothing() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession originalA = start(engine, A);
            DocumentSession originalB = start(engine, B);
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot base =
                    store.publicationSnapshot();
            MultiDocumentPublicationTransaction stale = transaction(
                    store, "stale-topology", base)
                    .expectHead(A, 0L, head(originalA));

            transaction(store, "topology-winner", base)
                    .expectHead(A, 0L, head(originalA))
                    .expectHead(B, 0L, head(originalB))
                    .stageOccurrenceInventory(
                            inventory(head(originalB)),
                            base.occurrenceInventoryGeneration() + 1L,
                            base.componentIndexGeneration() + 1L)
                    .commit();
            InMemoryDocumentStore.PublicationSnapshot winner =
                    store.publicationSnapshot();

            // when
            MultiDocumentPublicationTransaction.AtomicPublicationCasException

                    // then
                    failure = assertThrows(
                            MultiDocumentPublicationTransaction
                                    .AtomicPublicationCasException.class,
                            stale::commit);
            assertTrue(failure.getMessage().contains(
                    "Stale occurrence inventory generation"));
            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();
            assertEquals(winner.documentHeads(), after.documentHeads());
            assertEquals(winner.occurrenceInventoryGeneration(),
                    after.occurrenceInventoryGeneration());
            assertEquals(winner.componentIndexGeneration(),
                    after.componentIndexGeneration());
            assertEquals(List.of("topology-winner"),
                    after.publicationReceipts().stream().toList());
        }
    }

    @Test
    void injectedFailureAfterCompleteStagingRollsBackEverySurface() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession originalA = start(engine, A);
            DocumentSession originalB = start(engine, B);
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot before =
                    store.publicationSnapshot();

            // when
            ManagedOccurrenceInventory inventory = inventory(
                    originalB.currentRevision().after().blueId());

            // then
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> transaction(store, "injected", before)
                            .expectHead(A, 0L, head(originalA))
                            .expectHead(B, 0L, head(originalB))
                            .stageDocument(
                                    update(originalA), originalA.layout(), null,
                                    originalA.activeSubscriptions(),
                                    "injected|a|1")
                            .stageDocument(
                                    update(originalB), originalB.layout(), null,
                                    originalB.activeSubscriptions(),
                                    "injected|b|1")
                            .stageOccurrenceInventory(
                                    inventory,
                                    before.occurrenceInventoryGeneration() + 1L,
                                    before.componentIndexGeneration() + 1L)
                            .stageComponentStates(List.of(
                                    component(originalA, '1', '2'),
                                    component(originalB, '3', '4')))
                            .stageOutbox(List.of(event()))
                            .stageCheckpointEvidence(List.of(checkpoint(
                                    originalA.currentRevision().after()
                                            .blueId())))
                            .onFailurePoint(point -> {
                                if (point == MultiDocumentPublicationTransaction
                                        .FailurePoint.BEFORE_SWAP) {
                                    throw new RuntimeException(
                                            "injected before swap");
                                }
                            })
                            .commit());
            assertEquals("injected before swap", failure.getMessage());

            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();
            assertEquals(before.documentHeads(), after.documentHeads());
            assertEquals(before.occurrenceInventoryGeneration(),
                    after.occurrenceInventoryGeneration());
            assertEquals(before.componentIndexGeneration(),
                    after.componentIndexGeneration());
            assertSame(before.occurrenceInventory(),
                    after.occurrenceInventory());
            assertSame(before.componentIndex(), after.componentIndex());
            assertEquals(before.componentStates(), after.componentStates());
            assertEquals(before.outbox(), after.outbox());
            assertEquals(before.checkpointEvidence(),
                    after.checkpointEvidence());
            assertEquals(before.publicationReceipts(),
                    after.publicationReceipts());
            assertSame(originalA, store.require(A));
            assertSame(originalB, store.require(B));
        }
    }

    @Test
    void disconnectedTransactionsFenceOnlyTheirOwnDurableHeads() {
        // given

        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession originalA = start(engine, A);
            DocumentSession originalB = start(engine, B);
            InMemoryDocumentStore store = engine.documents();
            InMemoryDocumentStore.PublicationSnapshot base =
                    store.publicationSnapshot();
            InMemoryDocumentStore.StoreStructureSnapshot baseStructure =
                    store.storeStructureSnapshotForTesting();

            MultiDocumentPublicationTransaction updateA = transaction(
                    store, "disconnected-a", base)
                    .expectHead(A, 0L, head(originalA))
                    .stageDocument(update(originalA), originalA.layout(), null,
                            originalA.activeSubscriptions(), "isolated|a|1")
                    .stageOutbox(List.of(event()))
                    .stageCheckpointEvidence(List.of(checkpoint(
                            originalA.currentRevision().after().blueId())))
                    .stageComponentStates(List.of(
                            component(originalA, '1', '2')));
            MultiDocumentPublicationTransaction updateB = transaction(
                    store, "disconnected-b", base)
                    .expectHead(B, 0L, head(originalB))
                    .stageDocument(update(originalB), originalB.layout(), null,
                            originalB.activeSubscriptions(), "isolated|b|1")
                    .stageComponentStates(List.of(
                            component(originalB, '3', '4')));

            updateA.commit();
            InMemoryDocumentStore.StoreStructureSnapshot afterA =
                    store.storeStructureSnapshotForTesting();
            updateB.commit();

            // when
            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();

            // then
            assertEquals(1L, after.requireHead(A).epoch());
            assertEquals(1L, after.requireHead(B).epoch());
            assertEquals(base.occurrenceInventoryGeneration(),
                    after.occurrenceInventoryGeneration());
            assertEquals(base.componentIndexGeneration(),
                    after.componentIndexGeneration());
            assertEquals(2, after.componentStates().size());
            assertEquals(List.of("disconnected-a", "disconnected-b"),
                    after.publicationReceipts().stream().toList());
            assertTrue(afterA.sharedSessionNodes(baseStructure) > 0,
                    "updating A must retain B's exact persistent session node");
            assertTrue(afterA.evidenceExtends(baseStructure),
                    "new evidence must retain the exact prior log roots");
            assertEquals(baseStructure.outboxSize() + 1,
                    afterA.outboxSize());
            assertEquals(baseStructure.checkpointSize() + 1,
                    afterA.checkpointSize());
        }
    }

    private static MultiDocumentPublicationTransaction transaction(
            InMemoryDocumentStore store,
            String identity,
            InMemoryDocumentStore.PublicationSnapshot snapshot) {
        return store.beginAtomicPublication(
                identity,
                snapshot.occurrenceInventoryGeneration(),
                snapshot.componentIndexGeneration());
    }

    private static DocumentSession start(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        return engine.start(documentId, """
                documentId: %s
                state: initial
                """.formatted(documentId.value()));
    }

    private static DocumentRevision update(DocumentSession session) {
        return new DocumentRevision(
                session.documentId(),
                session.epoch() + 1L,
                session.nextApplicationOrder(),
                DocumentRevision.Kind.CATCH_UP_COMPLETED,
                session.currentRevision().after(),
                session.currentRevision().after(),
                null,
                null,
                List.of(),
                0L);
    }

    private static ComponentSnapshot component(
            DocumentSession session,
            char lineageDigit,
            char stateDigit) {
        return new ComponentSnapshot(
                hash(lineageDigit),
                hash(stateDigit),
                session.epoch() + 1L,
                ComponentKind.ACYCLIC,
                List.of(new blue.language.processor.closure.DocumentId(
                        session.documentId().value())),
                List.of(session.currentRevision().after().blueId()),
                null,
                null,
                null);
    }

    private static ManagedOccurrenceInventory inventory(
            String targetBlueId) {
        ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(
                hash('a'),
                new blue.language.processor.closure.DocumentId(A.value()),
                ScopeAddress.embedded("/b", 1L),
                new blue.language.processor.closure.DocumentId(B.value()),
                targetBlueId,
                true,
                null);
        return ManagedOccurrenceInventory.of(List.of(binding));
    }

    private static PublicEventOccurrence event() {
        Node event = new Node().properties(
                "kind", new Node().value("atomic-publication"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        return new PublicEventOccurrence(
                0L,
                0L,
                new blue.language.processor.closure.DocumentId(A.value()),
                hash('e'),
                IntegrationEventEvidence.verify(event, eventBlueId));
    }

    private static CheckpointWrite checkpoint(String subjectBlueId) {
        CheckpointDomainValue domain = new CheckpointDomainValue(
                subjectBlueId,
                List.of(subjectBlueId),
                List.of(),
                "atomic-publication-test");
        CheckpointWrite.State state = new CheckpointWrite.State(
                domain.blueId(), domain, subjectBlueId);
        return new CheckpointWrite(
                0L,
                ManagedScopeKey.root(
                        new blue.language.processor.closure.DocumentId(
                                A.value())),
                A_ROOT_SCOPE_IDENTITY,
                "test-channel",
                null,
                state);
    }

    private static String head(DocumentSession session) {
        return session.currentRevision().after().blueId();
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }
}
