package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedOccurrenceResolverTest {
    private static final DocumentId A = DocumentId.of("runtime-a");
    private static final DocumentId B = DocumentId.of("runtime-b");
    private static final String CAUSE = hash('1');
    private static final String CLOSURE = hash('2');
    private static final String DECLARATION = blueId('3');
    private static final String BINDING_POLICY = hash('4');

    @Test
    void newAuthoredValueUsesExactIdentityAndNeverReadsAuthoredDocumentId() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            ExactValue authored = engine.exactValue("""
                    documentId: arbitrary-user-content
                    name: duplicate names are also ordinary content
                    state: initial
                    """);
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", authored);

            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            assertTrue(result.complete());
            assertEquals(1, result.newDrafts().size());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(DocumentId.of(authored.blueId()),
                    occurrence.targetDocumentId());
            assertEquals("arbitrary-user-content",
                    occurrence.newDraft().initial().copyNode()
                            .getProperties().get("documentId").getValue());
            assertFalse(occurrence.targetDocumentId().equals(
                    DocumentId.of("arbitrary-user-content")));
        }
    }

    @Test
    void currentExactWinsWhenTheSameStateAlsoOccursAtOlderEpochs() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            DocumentSession a = start(engine, A, "one");
            appendNoOpRevision(engine, a);
            ExactValue current = engine.documents().require(A)
                    .currentRevision().after();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);

            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            assertTrue(result.complete());
            assertEquals(A, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING,
                    result.resolvedOccurrences().get(0).targetKind());
            assertEquals(List.of(0L, 1L), engine.documents().lineageIndex()
                    .byDocumentId(A).epochsFor(current.blueId()));
        }
    }

    @Test
    void identicalExactStateAcrossLineagesIsAmbiguous() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            start(engine, A, "same");
            start(engine, B, "same");
            ExactValue current = engine.document(A).current();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);

            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            assertFalse(result.complete());
            assertEquals(
                    ManagedOccurrenceResolver.ResolutionStatus
                            .AMBIGUOUS_LINEAGE,
                    result.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void inactiveReservationIsStableIdentityEvidenceAheadOfAmbiguity() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            start(engine, A, "same");
            start(engine, B, "same");
            ExactValue current = engine.document(A).current();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);
            ManagedOccurrenceBinding reserved = ManagedOccurrenceBinding
                    .derived(
                            BINDING_POLICY,
                            closureId(A),
                            ScopeAddress.embedded("/child", 1L),
                            closureId(A),
                            current.blueId(),
                            false,
                            null);
            InMemoryDocumentStore.OccurrenceResolutionSnapshot base = engine
                    .documents().occurrenceResolutionSnapshot();
            InMemoryDocumentStore.OccurrenceResolutionSnapshot reservedState =
                    new InMemoryDocumentStore.OccurrenceResolutionSnapshot(
                            base.lineageIndex(),
                            ManagedOccurrenceInventory.of(List.of(reserved)),
                            base.componentIndex(),
                            base.occurrenceInventoryGeneration(),
                            base.componentIndexGeneration());

            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(new ManagedOccurrenceResolver.ResolutionRequest(
                            CAUSE,
                            CLOSURE,
                            0L,
                            Set.of(A),
                            List.of(demand),
                            reservedState));

            assertTrue(result.complete());
            assertEquals(A, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
        }
    }

    @Test
    void exactNodeDemandCompletesOnlyWhenProviderHasVerifiedContent() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            ExactValue available = engine.exactValue("value: available");
            ExactValue absent = ExactValue.verified(
                    new Node().properties("value", new Node().value("absent")));
            ExactNodeDemand found = ExactNodeDemand.derived(
                    available.blueId(), closureId(A), "/found");
            ExactNodeDemand missing = ExactNodeDemand.derived(
                    absent.blueId(), closureId(A), "/missing");

            ManagedOccurrenceResolver.Resolution foundResult = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(found)));
            ManagedOccurrenceResolver.Resolution missingResult = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(missing)));

            assertTrue(foundResult.complete());
            assertEquals(List.of(found), foundResult.resolvedExactNodes()
                    .stream()
                    .map(ManagedOccurrenceResolver.ResolvedExactNode::demand)
                    .toList());
            assertTrue(available.sameExactValue(foundResult
                    .resolvedExactNodes().get(0).exactValue()));
            assertFalse(missingResult.complete());
            assertEquals(
                    ManagedOccurrenceResolver.ResolutionStatus
                            .MISSING_EXACT_CONTENT,
                    missingResult.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void duplicateNewOccurrencesShareOnePendingLineage() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            ExactValue authored = engine.exactValue("state: initial");
            ManagedOccurrenceEvidenceDemand first = demand(
                    A, "/children/0", authored, 0L);
            ManagedOccurrenceEvidenceDemand second = demand(
                    A, "/children/1", authored, 1L);

            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(
                            engine, Set.of(A), List.of(first, second)));

            assertTrue(result.complete());
            assertEquals(2, result.resolvedOccurrences().size());
            assertEquals(1, result.newDrafts().size());
            assertEquals(result.resolvedOccurrences().get(0).targetDocumentId(),
                    result.resolvedOccurrences().get(1).targetDocumentId());
        }
    }

    private static ManagedOccurrenceResolver resolver(
            DefaultCoordinationEngine engine) {
        return new ManagedOccurrenceResolver(
                engine.objects(), engine.engineMetrics());
    }

    private static ManagedOccurrenceResolver.ResolutionRequest request(
            DefaultCoordinationEngine engine,
            Set<DocumentId> members,
            List<? extends blue.language.processor.closure
                    .ClosureResourceDemand> demands) {
        return new ManagedOccurrenceResolver.ResolutionRequest(
                CAUSE,
                CLOSURE,
                0L,
                members,
                List.copyOf(demands),
                engine.documents().occurrenceResolutionSnapshot());
    }

    private static ManagedOccurrenceEvidenceDemand demand(
            DocumentId source,
            String path,
            ExactValue value) {
        return demand(source, path, value, 0L);
    }

    private static ManagedOccurrenceEvidenceDemand demand(
            DocumentId source,
            String path,
            ExactValue value,
            long ordinal) {
        return ManagedOccurrenceEvidenceDemand.derived(
                CAUSE,
                CLOSURE,
                0L,
                closureId(source),
                path,
                DECLARATION,
                value.blueId(),
                ordinal,
                value.copyNode());
    }

    private static DocumentSession start(
            DefaultCoordinationEngine engine,
            DocumentId documentId,
            String state) {
        return engine.start(documentId, """
                userDocumentId: arbitrary
                state: %s
                """.formatted(state));
    }

    private static void appendNoOpRevision(
            DefaultCoordinationEngine engine,
            DocumentSession session) {
        InMemoryDocumentStore store = engine.documents();
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        DocumentRevision revision = new DocumentRevision(
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
        store.beginAtomicPublication(
                        "resolver-no-op-revision",
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(session.documentId(), session.epoch(),
                        session.currentRevision().after().blueId())
                .stageDocument(
                        revision,
                        session.layout(),
                        null,
                        session.activeSubscriptions(),
                        "resolver-no-op|" + session.documentId().value())
                .stageComponentStates(List.of(new ComponentSnapshot(
                        hash('5'),
                        hash('6'),
                        Math.addExact(session.epoch(), 1L),
                        ComponentKind.ACYCLIC,
                        List.of(closureId(session.documentId())),
                        List.of(session.currentRevision().after().blueId()),
                        null,
                        null,
                        null)))
                .commit();
    }

    private static blue.language.processor.closure.DocumentId closureId(
            DocumentId documentId) {
        return new blue.language.processor.closure.DocumentId(
                documentId.value());
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }

    private static String blueId(char digit) {
        char[] digits = new char[48];
        Arrays.fill(digits, digit);
        return new String(digits);
    }
}
