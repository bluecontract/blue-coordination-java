package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.ExactValue;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ScopeAddress;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedOccurrenceResolverTest {
    private static final DocumentId A = DocumentId.of("runtime-a");
    private static final DocumentId B = DocumentId.of("runtime-b");
    private static final DocumentId C = DocumentId.of("runtime-c");
    private static final String CAUSE = hash('1');
    private static final String CLOSURE = hash('2');
    private static final String DECLARATION = blueId('3');
    private static final String BINDING_POLICY = hash('4');

    @Test
    void newAuthoredValueUsesExactIdentityAndNeverReadsAuthoredDocumentId() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue authored = engine.exactValue("""
                    documentId: arbitrary-user-content
                    name: duplicate names are also ordinary content
                    state: initial
                    """);
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", authored);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            // then
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
            // given
            DocumentSession a = start(engine, A, "one");
            appendNoOpRevision(engine, a);
            ExactValue current = engine.documents().require(A)
                    .currentRevision().after();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            // then
            assertTrue(result.complete());
            assertEquals(A, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING,
                    result.resolvedOccurrences().get(0).targetKind());
            assertEquals(1L, result.resolvedOccurrences().get(0)
                    .admittedSourceEpoch());
            assertEquals(List.of(0L, 1L), engine.documents().lineageIndex()
                    .byDocumentId(A).epochsFor(current.blueId()));
        }
    }

    @Test
    void existingAuthoredInitialSelectsPreInitializationEpoch() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue authored = exact("authored");
            DocumentSession lineage = lineage(
                    A, authored, exact("initialized"));
            appendRevision(lineage, exact("current"));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", authored);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineages(
                            engine, Set.of(A), List.of(demand), lineage));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(A, occurrence.targetDocumentId());
            assertEquals(
                    ManagedOccurrenceResolver.TargetKind
                            .EXISTING_AUTHORED_INITIAL,
                    occurrence.targetKind());
            assertEquals(-1L, occurrence.admittedSourceEpoch());
        }
    }

    @Test
    void uniqueInitializedEpochZeroSelectsEpochZero() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue initialized = exact("initialized");
            DocumentSession lineage = lineage(
                    A, exact("authored"), initialized);
            appendRevision(lineage, exact("current"));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", initialized);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineages(
                            engine, Set.of(A), List.of(demand), lineage));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(A, occurrence.targetDocumentId());
            assertEquals(
                    ManagedOccurrenceResolver.TargetKind
                            .EXISTING_INITIALIZED_EPOCH_ZERO,
                    occurrence.targetKind());
            assertEquals(0L, occurrence.admittedSourceEpoch());
        }
    }

    @Test
    void uniqueRetainedEpochSelectsItsExactEpoch() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession lineage = lineage(
                    A, exact("authored"), exact("initialized"));
            ExactValue retained = exact("retained");
            appendRevision(lineage, retained);
            appendRevision(lineage, exact("current"));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", retained);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineages(
                            engine, Set.of(A), List.of(demand), lineage));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(A, occurrence.targetDocumentId());
            assertEquals(
                    ManagedOccurrenceResolver.TargetKind
                            .EXISTING_RETAINED_EPOCH,
                    occurrence.targetKind());
            assertEquals(1L, occurrence.admittedSourceEpoch());
        }
    }

    @Test
    void repeatedHistoricalBlueIdIsAmbiguous() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession lineage = lineage(
                    A, exact("authored"), exact("initialized"));
            ExactValue repeated = exact("repeated");
            appendRevision(lineage, repeated);
            appendRevision(lineage, exact("intermediate"));
            appendRevision(lineage, repeated);
            appendRevision(lineage, exact("current"));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", repeated);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineages(
                            engine, Set.of(A), List.of(demand), lineage));

            // then
            assertFalse(result.complete());
            assertTrue(result.resolvedOccurrences().isEmpty());
            assertEquals(
                    ManagedOccurrenceResolver.ResolutionStatus
                            .AMBIGUOUS_MANAGED_EPOCH,
                    result.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void exactSelectorChoosesOneRepeatedHistoricalEpoch() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession lineage = lineage(
                    A, exact("authored"), exact("initialized"));
            ExactValue repeated = exact("repeated");
            appendRevision(lineage, repeated);
            appendRevision(lineage, exact("intermediate"));
            appendRevision(lineage, repeated);
            appendRevision(lineage, exact("current"));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    B, "/child", repeated);
            ContractsManagedEpochSelectionPlan plan = selectionPlan(
                    B, A, 1L, repeated, "/child");

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineagesAndSelection(
                            engine,
                            Set.of(B),
                            List.of(demand),
                            plan,
                            lineage));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(A, occurrence.targetDocumentId());
            assertEquals(1L, occurrence.admittedSourceEpoch());
            assertEquals(ManagedOccurrenceResolver.TargetKind
                    .EXISTING_RETAINED_EPOCH, occurrence.targetKind());
            assertEquals(Set.of("/child"),
                    result.resolvedSelectorPaths());
        }
    }

    @Test
    void exactSelectorDisambiguatesIdenticalStateAcrossLineages() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue shared = exact("shared");
            DocumentSession first = lineage(A, exact("first"), shared);
            DocumentSession second = lineage(B, exact("second"), shared);
            ManagedOccurrenceEvidenceDemand demand = demand(
                    C, "/child", shared);
            ContractsManagedEpochSelectionPlan plan = selectionPlan(
                    C, B, 0L, shared, "/child");

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineagesAndSelection(
                            engine,
                            Set.of(C),
                            List.of(demand),
                            plan,
                            first,
                            second));

            // then
            assertTrue(result.complete());
            assertEquals(B, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind
                    .CURRENT_EXISTING, result.resolvedOccurrences().get(0)
                    .targetKind());
        }
    }

    @Test
    void exactSelectorRejectsWrongEpochStateAndOccurrenceState() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue initialized = exact("initialized");
            ExactValue retained = exact("retained");
            DocumentSession lineage = lineage(
                    A, exact("authored"), initialized);
            appendRevision(lineage, retained);
            ManagedOccurrenceEvidenceDemand demand = demand(
                    B, "/child", retained);

            // when
            ManagedOccurrenceResolver.Resolution wrongEpoch = resolver(engine)
                    .resolve(requestWithLineagesAndSelection(
                            engine,
                            Set.of(B),
                            List.of(demand),
                            selectionPlan(
                                    B, A, 0L, retained, "/child"),
                            lineage));
            ManagedOccurrenceResolver.Resolution wrongOccurrence =
                    resolver(engine).resolve(
                            requestWithLineagesAndSelection(
                                    engine,
                                    Set.of(B),
                                    List.of(demand),
                                    selectionPlan(
                                            B,
                                            A,
                                            0L,
                                            initialized,
                                            "/child"),
                                    lineage));

            // then
            assertFalse(wrongEpoch.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .EXACT_STATE_MISMATCH, wrongEpoch.unresolvedDemands()
                    .get(0).status());
            assertFalse(wrongOccurrence.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .EXACT_STATE_MISMATCH, wrongOccurrence
                    .unresolvedDemands().get(0).status());
        }
    }

    @Test
    void exactSelectorUsesCurrentRepresentationAtTheCurrentSourceEpoch() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue initialized = exact("immutable-source-epoch-zero");
            DocumentSession source = lineage(
                    A, exact("authored"), initialized);
            ExactValue representation = exact("component-representation");
            DocumentSession rebound = source.copyForAtomicPublication();
            rebound.rebindComponentRepresentation(
                    source.epoch(),
                    layout(representation),
                    source.activeSubscriptions(),
                    hash('7'));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    B, "/child", representation);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(requestWithLineagesAndSelection(
                            engine,
                            Set.of(B),
                            List.of(demand),
                            selectionPlan(
                                    B,
                                    A,
                                    source.epoch(),
                                    representation,
                                    "/child"),
                            rebound));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence occurrence = result
                    .resolvedOccurrences().get(0);
            assertEquals(A, occurrence.targetDocumentId());
            assertEquals(0L, occurrence.admittedSourceEpoch());
            assertEquals(ManagedOccurrenceResolver.TargetKind
                    .CURRENT_EXISTING, occurrence.targetKind());
            ManagedLineageIndex.Lineage indexed = ManagedLineageIndex.empty()
                    .withNewLineage(rebound).byDocumentId(A);
            assertEquals(representation.blueId(), indexed.currentBlueId());
            assertEquals(initialized.blueId(),
                    indexed.retainedStates().get(0).blueId(),
                    "component representation must not rewrite source epoch");
        }
    }

    @Test
    void sameEpochRebindExposesOnlyRepresentationContiguousHistory() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue authored = exact("authored");
            ExactValue epochZero = managedExact("epoch-zero");
            DocumentSession source = lineage(A, authored, epochZero);
            ExactValue reboundHead = managedExact("rebound-epoch-zero");
            DocumentSession rebound = source.copyForAtomicPublication();
            rebound.rebindComponentRepresentation(
                    source.epoch(),
                    layout(reboundHead),
                    source.activeSubscriptions(),
                    hash('8'));

            // when
            ManagedOccurrenceResolver.Resolution staleAtCurrent =
                    resolver(engine).resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(demand(B, "/child", epochZero)),
                            rebound));
            ManagedOccurrenceResolver.Resolution authoredAtCurrent =
                    resolver(engine).resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(demand(B, "/authored", authored)),
                            rebound));
            ManagedOccurrenceResolver.Resolution current = resolver(engine)
                    .resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(demand(B, "/child", reboundHead)),
                            rebound));
            ManagedOccurrenceResolver.Resolution explicitStale =
                    resolver(engine).resolve(
                            requestWithLineagesAndSelection(
                                    engine,
                                    Set.of(B),
                                    List.of(demand(
                                            B, "/child", epochZero)),
                                    selectionPlan(
                                            B, A, 0L, epochZero, "/child"),
                                    rebound));
            ExactValue epochOne = managedExact("epoch-one");
            appendRevision(rebound, epochOne);
            ManagedOccurrenceResolver.Resolution staleAfterAdvance =
                    resolver(engine).resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(demand(B, "/child", epochZero)),
                            rebound));
            ManagedOccurrenceResolver.Resolution authoredAfterAdvance =
                    resolver(engine).resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(demand(B, "/authored", authored)),
                            rebound));
            ExactValue epochTwo = managedExact("epoch-two");
            appendRevision(rebound, epochTwo);
            ManagedOccurrenceResolver.Resolution contiguousRetained =
                    resolver(engine).resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                                    List.of(demand(B, "/child", epochOne)),
                                    rebound));

            // then
            assertFalse(staleAtCurrent.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .UNPROVEN_MANAGED_HISTORY,
                    staleAtCurrent.unresolvedDemands().get(0).status());
            assertFalse(authoredAtCurrent.complete());
            assertTrue(authoredAtCurrent.newDrafts().isEmpty(),
                    "known non-replayable authored content must not create a "
                            + "duplicate lineage");
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .UNPROVEN_MANAGED_HISTORY,
                    authoredAtCurrent.unresolvedDemands().get(0).status());
            assertTrue(current.complete());
            assertEquals(ManagedOccurrenceResolver.TargetKind
                    .CURRENT_EXISTING,
                    current.resolvedOccurrences().get(0).targetKind());
            assertFalse(explicitStale.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .EXACT_STATE_MISMATCH,
                    explicitStale.unresolvedDemands().get(0).status());
            assertFalse(staleAfterAdvance.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .UNPROVEN_MANAGED_HISTORY,
                    staleAfterAdvance.unresolvedDemands().get(0).status());
            assertFalse(authoredAfterAdvance.complete());
            assertTrue(authoredAfterAdvance.newDrafts().isEmpty());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .UNPROVEN_MANAGED_HISTORY,
                    authoredAfterAdvance.unresolvedDemands().get(0).status());
            assertTrue(contiguousRetained.complete());
            assertEquals(1L, contiguousRetained.resolvedOccurrences().get(0)
                    .admittedSourceEpoch());
            assertEquals(ManagedOccurrenceResolver.TargetKind
                    .EXISTING_RETAINED_EPOCH,
                    contiguousRetained.resolvedOccurrences().get(0)
                            .targetKind());
        }
    }

    @Test
    void fencedAuthoredSentinelCannotBecomeANewLineage() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue authored = exact("fenced-authored");
            DocumentSession source = lineage(
                    A, authored, managedExact("fenced-epoch-zero"));
            DocumentSession rebound = source.copyForAtomicPublication();
            rebound.rebindComponentRepresentation(
                    0L,
                    layout(managedExact("fenced-representation")),
                    rebound.activeSubscriptions(),
                    hash('9'));
            ManagedOccurrenceEvidenceDemand implicitDemand = demand(
                    B, "/implicit-authored", authored);
            ManagedOccurrenceEvidenceDemand explicitDemand = demand(
                    B, "/explicit-authored", authored);
            ManagedOccurrenceEvidenceDemand stableDemand = demand(
                    B, "/stable-authored", authored);
            ManagedOccurrenceBinding stableAuthored =
                    ManagedOccurrenceBinding.derived(
                            BINDING_POLICY,
                            closureId(B),
                            ScopeAddress.embedded("/stable-authored", 1L),
                            closureId(A),
                            authored.blueId(),
                            false,
                            -1L);

            // when
            ManagedOccurrenceResolver.Resolution implicit = resolver(engine)
                    .resolve(requestWithLineages(
                            engine,
                            Set.of(B),
                            List.of(implicitDemand),
                            rebound));
            ManagedOccurrenceResolver.Resolution explicit = resolver(engine)
                    .resolve(requestWithLineagesAndSelection(
                            engine,
                            Set.of(B),
                            List.of(explicitDemand),
                            selectionPlan(
                                    B,
                                    A,
                                    -1L,
                                    authored,
                                    "/explicit-authored"),
                            rebound));
            ManagedOccurrenceResolver.Resolution stable = resolver(engine)
                    .resolve(requestWithLineagesAndBinding(
                            engine,
                            Set.of(B),
                            List.of(stableDemand),
                            stableAuthored,
                            rebound));

            // then
            assertUnresolvedWithoutDraft(
                    implicit,
                    ManagedOccurrenceResolver.ResolutionStatus
                            .UNPROVEN_MANAGED_HISTORY);
            assertUnresolvedWithoutDraft(
                    explicit,
                    ManagedOccurrenceResolver.ResolutionStatus
                            .EXACT_STATE_MISMATCH);
            assertUnresolvedWithoutDraft(
                    stable,
                    ManagedOccurrenceResolver.ResolutionStatus
                            .EXACT_STATE_MISMATCH);
            assertEquals(-1L,
                    stableAuthored.pendingHistoricalEpoch().longValue());
        }
    }

    @Test
    void identicalExactStateAcrossLineagesIsAmbiguous() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            start(engine, A, "same");
            start(engine, B, "same");
            ExactValue current = engine.document(A).current();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            // then
            assertFalse(result.complete());
            assertTrue(result.resolvedOccurrences().isEmpty());
            assertEquals(
                    ManagedOccurrenceResolver.ResolutionStatus
                            .AMBIGUOUS_MANAGED_LINEAGE,
                    result.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void inactiveReservationIsStableIdentityEvidenceAheadOfAmbiguity() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
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

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(new ManagedOccurrenceResolver.ResolutionRequest(
                            CAUSE,
                            CLOSURE,
                            0L,
                            Set.of(A),
                            List.of(demand),
                            reservedState));

            // then
            assertTrue(result.complete());
            assertEquals(A, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
        }
    }

    @Test
    void detachedReservationDiscoversForeignCurrentOutsideTheInputMembers() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue oldHead = exact("detached-b-current");
            ExactValue foreignHead = exact("detached-c-current");
            DocumentSession old = lineage(B, exact("detached-b-authored"), oldHead);
            DocumentSession foreign = lineage(C, exact("detached-c-authored"), foreignHead);
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);
            var request = requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", foreignHead)), reserved, old, foreign);

            // when
            var result = resolver(engine).resolve(request);

            // then
            assertTrue(result.complete());
            assertTrue(result.newDrafts().isEmpty());
            assertEquals(1, result.resolvedOccurrences().size());
            var selected = result.resolvedOccurrences().get(0);
            assertEquals(C, selected.targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind.CURRENT_EXISTING, selected.targetKind());
            assertEquals(0L, selected.admittedSourceEpoch());
            assertEquals(foreignHead.blueId(), selected.expectedTargetBlueId());
            assertFalse(request.inputMembers().contains(C));
            assertEquals(reserved.bindingIdentity(), request.storeState().occurrenceInventory()
                    .find(A, "/child").orElseThrow().bindingIdentity());
        }
    }

    @Test
    void detachedReservationDiscoversForeignAuthoredInitialWithoutCreatingADraft() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue oldHead = exact("authored-retarget-b-current");
            ExactValue foreignAuthored = exact("authored-retarget-c-authored");
            DocumentSession old = lineage(B, exact("authored-retarget-b-authored"), oldHead);
            DocumentSession foreign = lineage(C, foreignAuthored, exact("authored-retarget-c-initialized"));
            appendRevision(foreign, exact("authored-retarget-c-current"));
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", foreignAuthored)), reserved, old, foreign));

            // then
            assertTrue(result.complete());
            assertTrue(result.newDrafts().isEmpty());
            assertEquals(1, result.resolvedOccurrences().size());
            var selected = result.resolvedOccurrences().get(0);
            assertEquals(C, selected.targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind.EXISTING_AUTHORED_INITIAL, selected.targetKind());
            assertEquals(-1L, selected.admittedSourceEpoch());
            assertEquals(Long.valueOf(-1L), selected.pendingHistoricalEpoch());
            assertEquals(foreignAuthored.blueId(), selected.expectedTargetBlueId());
        }
    }

    @Test
    void pendingReservationCannotDiscoverForeignCurrentWithoutAReceiptEvent() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue oldHead = exact("pending-b-current");
            ExactValue foreignHead = exact("pending-c-current");
            DocumentSession old = lineage(B, exact("pending-b-authored"), oldHead);
            DocumentSession foreign = lineage(C, exact("pending-c-authored"), foreignHead);
            ManagedOccurrenceBinding pending = detachedBReservation(oldHead, 0L);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", foreignHead)), pending, old, foreign));

            // then
            assertUnresolvedWithoutDraft(result, ManagedOccurrenceResolver.ResolutionStatus.EXACT_STATE_MISMATCH);
            assertEquals(Long.valueOf(0L), pending.pendingHistoricalEpoch());
        }
    }

    @Test
    void detachedReservationDoesNotChooseBetweenTwoForeignLineages() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue oldHead = exact("ambiguous-foreign-b-current");
            ExactValue shared = exact("ambiguous-foreign-current");
            DocumentSession old = lineage(B, exact("ambiguous-foreign-b-authored"), oldHead);
            DocumentSession first = lineage(C, exact("ambiguous-foreign-c-authored"), shared);
            DocumentSession second = lineage(DocumentId.of("runtime-d"),
                    exact("ambiguous-foreign-d-authored"), shared);
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", shared)), reserved, old, first, second));

            // then
            assertUnresolvedWithoutDraft(result, ManagedOccurrenceResolver.ResolutionStatus.AMBIGUOUS_MANAGED_LINEAGE);
        }
    }

    @Test
    void detachedReservationKeepsItsOldHistoricalLineageAheadOfForeignCurrent() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue saved = exact("stable-b-retained");
            ExactValue oldHead = exact("stable-b-current");
            DocumentSession old = lineage(B, exact("stable-b-authored"), exact("stable-b-initialized"));
            appendRevision(old, saved);
            appendRevision(old, oldHead);
            DocumentSession foreign = lineage(C, exact("stable-c-authored"), saved);
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", saved)), reserved, old, foreign));

            // then
            assertTrue(result.complete());
            assertTrue(result.newDrafts().isEmpty());
            var selected = result.resolvedOccurrences().get(0);
            assertEquals(B, selected.targetDocumentId());
            assertEquals(ManagedOccurrenceResolver.TargetKind.EXISTING_RETAINED_EPOCH, selected.targetKind());
            assertEquals(1L, selected.admittedSourceEpoch());
        }
    }

    @Test
    void detachedReservationKeepsOldEpochAmbiguityInsteadOfSelectingForeignCurrent() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue repeated = exact("stable-b-repeated");
            ExactValue oldHead = exact("stable-b-final");
            DocumentSession old = lineage(B, exact("repeated-b-authored"), exact("repeated-b-initialized"));
            appendRevision(old, repeated);
            appendRevision(old, exact("stable-b-between"));
            appendRevision(old, repeated);
            appendRevision(old, oldHead);
            DocumentSession foreign = lineage(C, exact("repeated-c-authored"), repeated);
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", repeated)), reserved, old, foreign));

            // then
            assertUnresolvedWithoutDraft(result, ManagedOccurrenceResolver.ResolutionStatus.AMBIGUOUS_MANAGED_EPOCH);
        }
    }

    @Test
    void detachedReservationKeepsUnreplayableOldHistoryInsteadOfSelectingForeignCurrent() {
        try (DefaultCoordinationEngine engine = DefaultCoordinationEngine.create()) {
            // given
            ExactValue saved = managedExact("unreplayable-b-initialized");
            ExactValue oldHead = managedExact("unreplayable-b-representation");
            DocumentSession old = lineage(B, exact("unreplayable-b-authored"), saved);
            old.rebindComponentRepresentation(0L, layout(oldHead), old.activeSubscriptions(), hash('8'));
            DocumentSession foreign = lineage(C, exact("unreplayable-c-authored"), saved);
            ManagedOccurrenceBinding reserved = detachedBReservation(oldHead, null);

            // when
            var result = resolver(engine).resolve(requestWithLineagesAndBinding(engine, Set.of(A, B),
                    List.of(demand(A, "/child", saved)), reserved, old, foreign));

            // then
            assertUnresolvedWithoutDraft(result, ManagedOccurrenceResolver.ResolutionStatus.EXACT_STATE_MISMATCH);
        }
    }

    private static ManagedOccurrenceBinding detachedBReservation(ExactValue expected, Long pendingEpoch) {
        return ManagedOccurrenceBinding.derived(BINDING_POLICY, closureId(A), ScopeAddress.embedded("/child", 2L),
                closureId(B), expected.blueId(), false, pendingEpoch);
    }

    @Test
    void exactNodeDemandCompletesOnlyWhenProviderHasVerifiedContent() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue available = engine.exactValue("value: available");
            ExactValue absent = ExactValue.verified(
                    new Node().properties("value", new Node().value("absent")));
            ExactNodeDemand found = ExactNodeDemand.derived(
                    available.blueId(), closureId(A), "/found");
            ExactNodeDemand missing = ExactNodeDemand.derived(
                    absent.blueId(), closureId(A), "/missing");

            // when
            ManagedOccurrenceResolver.Resolution foundResult = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(found)));
            ManagedOccurrenceResolver.Resolution missingResult = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(missing)));

            // then
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
    void exactNodeResolutionCarriesOneShotProviderContentForTheRetry() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue available = engine.exactValue("value: one-shot");
            AtomicInteger reads = new AtomicInteger();
            ManagedOccurrenceResolver resolver =
                    new ManagedOccurrenceResolver(
                            blueId -> reads.getAndIncrement() == 0
                                    ? List.of(available.copyNode())
                                    : List.of(),
                            engine.engineMetrics());
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    available.blueId(), closureId(A), "/one-shot");

            // when
            ManagedOccurrenceResolver.Resolution result = resolver.resolve(
                    request(engine, Set.of(A), List.of(demand)));

            // then
            assertTrue(result.complete());
            assertEquals(1, reads.get());
            assertTrue(available.sameExactValue(
                    result.resolvedExactNodes().get(0).exactValue()));
        }
    }

    @Test
    void cyclicExactNodeCarriesTheVerifiedBodyAndCompleteProof() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            CyclicFixture fixture = cyclicFixture();
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    fixture.memberBlueId(), closureId(A), "/cyclic");
            ManagedOccurrenceResolver resolver =
                    new ManagedOccurrenceResolver(
                            fixture.provider(), engine.engineMetrics());

            // when
            ManagedOccurrenceResolver.Resolution result = resolver.resolve(
                    request(engine, Set.of(A), List.of(demand)));

            // then
            assertTrue(result.complete());
            ManagedOccurrenceResolver.ResolvedExactNode resolved = result
                    .resolvedExactNodes().get(0);
            assertEquals(fixture.memberBlueId(),
                    resolved.exactValue().blueId());
            assertTrue(resolved.exactValue().isCyclicMember());
            assertTrue(resolved.cyclicProof() != null);
            assertEquals("resolver-cycle-a",
                    resolved.exactValue().copyNode().getName());
            Node detachedProviderBody = resolved.providerBody();
            detachedProviderBody.name("mutated-copy");
            assertEquals("resolver-cycle-a",
                    resolved.providerBody().getName());

            engine.objects().putVerifiedProviderEvidence(
                    resolved.exactValue(),
                    resolved.providerBody(),
                    resolved.cyclicProof(),
                    "resolver-cyclic-test");
            assertEquals(NodeProviderOutcome.FOUND,
                    new VerifyingNodeProvider(engine.objects())
                            .fetchResultByBlueId(fixture.memberBlueId())
                            .outcome(),
                    "retry cache must retain the complete proof with body");
        }
    }

    @Test
    void cyclicOccurrenceCreatesDraftOnlyFromAuthenticatedProviderEvidence() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            CyclicFixture fixture = cyclicFixture();
            ManagedOccurrenceEvidenceDemand demand =
                    ManagedOccurrenceEvidenceDemand.derived(
                            CAUSE,
                            CLOSURE,
                            0L,
                            closureId(A),
                            "/cyclic",
                            DECLARATION,
                            fixture.memberBlueId(),
                            0L);

            // when
            ManagedOccurrenceResolver.Resolution result =
                    new ManagedOccurrenceResolver(
                            fixture.provider(), engine.engineMetrics())
                            .resolve(request(
                                    engine, Set.of(A), List.of(demand)));

            // then
            assertTrue(result.complete());
            assertEquals(1, result.resolvedOccurrences().size());
            ExactValue initial = result.newDrafts().values().iterator()
                    .next().initial();
            assertEquals(fixture.memberBlueId(), initial.blueId());
            assertTrue(initial.isCyclicMember());
            assertTrue(initial.cyclicSetProof().isPresent());
        }
    }

    @Test
    void cyclicExactNodeRejectsMissingAndInvalidProofEvidence() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            CyclicFixture fixture = cyclicFixture();
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    fixture.memberBlueId(), closureId(A), "/cyclic");
            NodeProvider missingProof = proofOverride(
                    fixture, CyclicSetProofResult.notFound());
            NodeProvider invalidProof = proofOverride(
                    fixture, CyclicSetProofResult.invalidEvidence(
                            "invalid test proof"));

            // when
            Runnable resolveMissing = () -> new ManagedOccurrenceResolver(
                            missingProof, engine.engineMetrics()).resolve(
                                    request(engine, Set.of(A),
                                            List.of(demand)));
            Runnable resolveInvalid = () -> new ManagedOccurrenceResolver(
                    invalidProof, engine.engineMetrics()).resolve(
                            request(engine, Set.of(A), List.of(demand)));

            // then
            CoordinationException missing = assertThrows(
                    CoordinationException.class,
                    resolveMissing::run);
            CoordinationException invalid = assertThrows(
                    CoordinationException.class,
                    resolveInvalid::run);
            assertEquals(CoordinationErrorCode.MISSING_EXACT_VALUE_PROOF,
                    missing.code());
            assertEquals(CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                    invalid.code());
            assertTrue(invalid.getMessage().contains("invalid test proof"));
            assertEquals(A.value(),
                    invalid.details().get("sourceDocumentId"));
            assertEquals("/cyclic", invalid.details().get("sourcePath"));
        }
    }

    @Test
    void cyclicExactNodeKeepsAbsentBodyAndUnavailableProofUnresolved() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            CyclicFixture fixture = cyclicFixture();
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    fixture.memberBlueId(), closureId(A), "/cyclic");
            NodeProvider absentBody = ignored -> List.of();
            NodeProvider unavailableProof = proofOverride(
                    fixture,
                    CyclicSetProofResult.unavailable("proof store offline"));

            // when
            ManagedOccurrenceResolver.Resolution absent =
                    new ManagedOccurrenceResolver(
                            absentBody, engine.engineMetrics()).resolve(
                                    request(engine, Set.of(A),
                                            List.of(demand)));
            ManagedOccurrenceResolver.Resolution unavailable =
                    new ManagedOccurrenceResolver(
                            unavailableProof, engine.engineMetrics()).resolve(
                                    request(engine, Set.of(A),
                                            List.of(demand)));

            // then
            assertFalse(absent.complete());
            assertFalse(unavailable.complete());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .MISSING_EXACT_CONTENT,
                    absent.unresolvedDemands().get(0).status());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .MISSING_EXACT_CONTENT,
                    unavailable.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void cyclicExactNodeRejectsACompleteButWrongProof() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            CyclicFixture fixture = cyclicFixture();
            CyclicFixture unrelated = cyclicFixture("unrelated-cycle");
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    fixture.memberBlueId(), closureId(A), "/cyclic");
            NodeProvider wrongProof = proofOverride(
                    fixture,
                    unrelated.provider().cyclicSetProofFor(
                            unrelated.memberBlueId()));

            // when
            Runnable resolve = () -> new ManagedOccurrenceResolver(
                    wrongProof, engine.engineMetrics()).resolve(
                            request(engine, Set.of(A), List.of(demand)));

            // then
            CoordinationException invalid = assertThrows(
                    CoordinationException.class, resolve::run);
            assertEquals(CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                    invalid.code());
        }
    }

    @Test
    void ordinaryExactNodeStillRejectsMismatchedProviderContent() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue expected = exact("expected");
            Node wrong = exact("wrong").copyNode();
            ExactNodeDemand demand = ExactNodeDemand.derived(
                    expected.blueId(), closureId(A), "/ordinary");

            // when
            Runnable resolve = () -> new ManagedOccurrenceResolver(
                    ignored -> List.of(wrong),
                    engine.engineMetrics()).resolve(
                            request(engine, Set.of(A), List.of(demand)));

            // then
            assertThrows(IllegalArgumentException.class, resolve::run);
        }
    }

    @Test
    void duplicateNewOccurrencesShareOnePendingLineage() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue authored = engine.exactValue("state: initial");
            ManagedOccurrenceEvidenceDemand first = demand(
                    A, "/children/0", authored, 0L);
            ManagedOccurrenceEvidenceDemand second = demand(
                    A, "/children/1", authored, 1L);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(
                            engine, Set.of(A), List.of(first, second)));

            // then
            assertTrue(result.complete());
            assertEquals(2, result.resolvedOccurrences().size());
            assertEquals(1, result.newDrafts().size());
            assertEquals(result.resolvedOccurrences().get(0).targetDocumentId(),
                    result.resolvedOccurrences().get(1).targetDocumentId());
        }
    }

    @Test
    void unknownInitializedValueIsUnprovenHistoryNotANewLineage() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            ExactValue progressed = ExactValue.verified(new Node().properties(
                    "initialized", new Node().value(true),
                    "state", new Node().value("unknown-progressed")));
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", progressed);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            // then
            assertFalse(result.complete());
            assertTrue(result.newDrafts().isEmpty());
            assertEquals(ManagedOccurrenceResolver.ResolutionStatus
                    .UNPROVEN_MANAGED_HISTORY,
                    result.unresolvedDemands().get(0).status());
        }
    }

    @Test
    void inlineAndPureReferenceResolveToTheSameManagedPosition() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            DocumentSession lineage = start(engine, A, "parity");
            ExactValue current = lineage.currentRevision().after();
            ManagedOccurrenceEvidenceDemand inline = demand(
                    B, "/child", current);
            ManagedOccurrenceEvidenceDemand reference =
                    ManagedOccurrenceEvidenceDemand.derived(
                            CAUSE,
                            CLOSURE,
                            0L,
                            closureId(B),
                            "/child",
                            DECLARATION,
                            current.blueId(),
                            0L);

            // when
            ManagedOccurrenceResolver.Resolution inlineResult =
                    resolver(engine).resolve(request(
                            engine, Set.of(B), List.of(inline)));
            ManagedOccurrenceResolver.Resolution referenceResult =
                    resolver(engine).resolve(request(
                            engine, Set.of(B), List.of(reference)));

            // then
            assertTrue(inlineResult.complete());
            assertTrue(referenceResult.complete());
            ManagedOccurrenceResolver.ResolvedOccurrence inlineOccurrence =
                    inlineResult.resolvedOccurrences().get(0);
            ManagedOccurrenceResolver.ResolvedOccurrence referenceOccurrence =
                    referenceResult.resolvedOccurrences().get(0);
            assertEquals(inlineOccurrence.targetDocumentId(),
                    referenceOccurrence.targetDocumentId());
            assertEquals(inlineOccurrence.targetKind(),
                    referenceOccurrence.targetKind());
            assertEquals(inlineOccurrence.admittedSourceEpoch(),
                    referenceOccurrence.admittedSourceEpoch());
        }
    }

    @Test
    void exactResolutionAndLineageAdvanceRemainLocalWithOneThousandAmbient() {
        try (DefaultCoordinationEngine engine =
                DefaultCoordinationEngine.create()) {
            // given
            for (int index = 0; index < 1_000; index++) {
                start(
                        engine,
                        DocumentId.of("ambient-" + index),
                        "ambient-" + index);
            }
            DocumentSession target = start(engine, A, "selected");
            ManagedLineageIndex before = engine.documents().lineageIndex();
            ManagedLineageIndex.Lineage ambientBefore = before.byDocumentId(
                    DocumentId.of("ambient-500"));

            appendNoOpRevision(engine, target);

            ManagedLineageIndex after = engine.documents().lineageIndex();
            ExactValue current = engine.documents().require(A)
                    .currentRevision().after();
            ManagedOccurrenceEvidenceDemand demand = demand(
                    A, "/child", current);

            // when
            ManagedOccurrenceResolver.Resolution result = resolver(engine)
                    .resolve(request(engine, Set.of(A), List.of(demand)));

            // then
            assertTrue(result.complete());
            assertEquals(A, result.resolvedOccurrences().get(0)
                    .targetDocumentId());
            assertSame(ambientBefore, after.byDocumentId(
                    DocumentId.of("ambient-500")),
                    "unrelated lineage rows remain structurally shared");
            assertTrue(after.lastMutationNodeCopies() < 256,
                    () -> "lineage advance copied "
                            + after.lastMutationNodeCopies()
                            + " persistent nodes for 1,000 unrelated rows");
            assertTrue(after.exactLookupSteps(current.blueId()) < 64,
                    () -> "exact indexes required "
                            + after.exactLookupSteps(current.blueId())
                            + " balanced-tree comparisons");
            assertTrue(after.documentLookupSteps(A) < 16,
                    () -> "document index required "
                            + after.documentLookupSteps(A)
                            + " balanced-tree comparisons");
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

    private static ManagedOccurrenceResolver.ResolutionRequest
            requestWithLineages(
                    DefaultCoordinationEngine engine,
                    Set<DocumentId> members,
                    List<? extends blue.language.processor.closure
                            .ClosureResourceDemand> demands,
                    DocumentSession... lineages) {
        ManagedLineageIndex index = ManagedLineageIndex.empty();
        for (DocumentSession lineage : lineages) {
            index = index.withNewLineage(lineage);
        }
        InMemoryDocumentStore.OccurrenceResolutionSnapshot base = engine
                .documents().occurrenceResolutionSnapshot();
        InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState =
                new InMemoryDocumentStore.OccurrenceResolutionSnapshot(
                        index,
                        base.occurrenceInventory(),
                        base.componentIndex(),
                        base.occurrenceInventoryGeneration(),
                        base.componentIndexGeneration());
        return new ManagedOccurrenceResolver.ResolutionRequest(
                CAUSE,
                CLOSURE,
                0L,
                members,
                List.copyOf(demands),
                storeState);
    }

    private static ManagedOccurrenceResolver.ResolutionRequest
            requestWithLineagesAndSelection(
                    DefaultCoordinationEngine engine,
                    Set<DocumentId> members,
                    List<? extends blue.language.processor.closure
                            .ClosureResourceDemand> demands,
                    ContractsManagedEpochSelectionPlan selectionPlan,
                    DocumentSession... lineages) {
        ManagedOccurrenceResolver.ResolutionRequest base =
                requestWithLineages(engine, members, demands, lineages);
        return new ManagedOccurrenceResolver.ResolutionRequest(
                base.logicalCauseIdentity(),
                base.inputClosureIdentity(),
                base.inputGraphGeneration(),
                base.inputMembers(),
                base.demands(),
                base.storeState(),
                selectionPlan);
    }

    private static ManagedOccurrenceResolver.ResolutionRequest
            requestWithLineagesAndBinding(
                    DefaultCoordinationEngine engine,
                    Set<DocumentId> members,
                    List<? extends blue.language.processor.closure
                            .ClosureResourceDemand> demands,
                    ManagedOccurrenceBinding binding,
                    DocumentSession... lineages) {
        ManagedOccurrenceResolver.ResolutionRequest base =
                requestWithLineages(engine, members, demands, lineages);
        InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState =
                new InMemoryDocumentStore.OccurrenceResolutionSnapshot(
                        base.storeState().lineageIndex(),
                        ManagedOccurrenceInventory.of(List.of(binding)),
                        base.storeState().componentIndex(),
                        base.storeState().occurrenceInventoryGeneration(),
                        base.storeState().componentIndexGeneration());
        return new ManagedOccurrenceResolver.ResolutionRequest(
                base.logicalCauseIdentity(),
                base.inputClosureIdentity(),
                base.inputGraphGeneration(),
                base.inputMembers(),
                base.demands(),
                storeState);
    }

    private static void assertUnresolvedWithoutDraft(
            ManagedOccurrenceResolver.Resolution resolution,
            ManagedOccurrenceResolver.ResolutionStatus expectedStatus) {
        assertFalse(resolution.complete());
        assertTrue(resolution.resolvedOccurrences().isEmpty());
        assertTrue(resolution.newDrafts().isEmpty());
        assertEquals(expectedStatus,
                resolution.unresolvedDemands().get(0).status());
    }

    private static ContractsManagedEpochSelectionPlan selectionPlan(
            DocumentId target,
            DocumentId source,
            long epoch,
            ExactValue expected,
            String path) {
        return new ContractsManagedEpochSelectionPlan(
                target,
                0L,
                exact("target").blueId(),
                List.of(new ContractsManagedEpochSelectionPlan.Selection(
                        source,
                        epoch,
                        expected.blueId(),
                        path)));
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

    private static DocumentSession lineage(
            DocumentId documentId,
            ExactValue authored,
            ExactValue initialized) {
        ExternalOrderKey admission = ExternalOrderKey.of(List.of(
                0L, "resolver-admission", documentId.value()));
        DocumentRevision initialization = new DocumentRevision(
                documentId,
                0L,
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                authored,
                initialized,
                null,
                admission,
                initialized.blueId(),
                null,
                List.of(),
                0L);
        return new DocumentSession(
                documentId,
                authored,
                layout(initialized),
                List.of(),
                admission,
                initialization);
    }

    private static void appendRevision(
            DocumentSession session,
            ExactValue after) {
        long epoch = Math.addExact(session.epoch(), 1L);
        DocumentRevision revision = new DocumentRevision(
                session.documentId(),
                epoch,
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
                "resolver-revision|" + session.documentId().value()
                        + "|" + epoch);
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

    private static ExactValue managedExact(String state) {
        return ExactValue.verified(new Node().properties(
                "initialized", new Node().value(true),
                "state", new Node().value(state)));
    }

    private static CyclicFixture cyclicFixture() {
        return cyclicFixture("resolver-cycle");
    }

    private static CyclicFixture cyclicFixture(String namePrefix) {
        Node documents = new Node().items(List.of(
                new Node().name(namePrefix + "-a").properties(
                        "peer", new Node().blueId("this#1")),
                new Node().name(namePrefix + "-b").properties(
                        "peer", new Node().blueId("this#0"))));
        BasicNodeProvider provider = new BasicNodeProvider(documents);
        return new CyclicFixture(
                provider,
                provider.getBlueIdByName(namePrefix + "-a"));
    }

    private static NodeProvider proofOverride(
            CyclicFixture fixture,
            CyclicSetProofResult proofResult) {
        return new ProofOverrideProvider(
                fixture.provider(), proofResult);
    }

    private record CyclicFixture(
            BasicNodeProvider provider,
            String memberBlueId) {
    }

    private static final class ProofOverrideProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final NodeProvider content;
        private final CyclicSetProofResult proofResult;

        private ProofOverrideProvider(
                NodeProvider content,
                CyclicSetProofResult proofResult) {
            this.content = content;
            this.proofResult = proofResult;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return content.fetchByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return proofResult;
        }
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
