package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ComponentSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Eager selected-state inventory checks; no alternate processing graph or execution against forged indexes. */
final class InMemoryDocumentStoreStateTest {
    @Test void rootedInventoryKeepsCanonicalProofsAndAllOriginalReceiptHistory() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var state = f.engine.documents().storedState(); var before = f.state();
            var proofs = state.componentStates();
            assertTrue(state.componentIndex().hasRootedViews()); assertEquals(2, proofs.size());
            var firstMembers = proofs.stream().map(row -> DocumentId.of(row.orderedMemberDocumentIds().get(0).value())).toList();
            assertEquals(firstMembers.stream().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList(), firstMembers);
            var restored = copy(state, proofs, state.componentIndex());
            assertEquals(proofs, restored.componentStates());
            assertEquals(state.closurePublicationReceipts(), restored.closurePublicationReceipts());
            assertEquals(state.admissionReceipts(), restored.admissionReceipts());
            assertEquals(state.outbox(), restored.outbox()); assertEquals(state.checkpointEvidence(), restored.checkpointEvidence());
            assertEquals(before, f.state());
        }
    }

    @Test void rootedInventoryRejectsReversedOrderAndForeignMemberAssociation() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var state = f.engine.documents().storedState(); var proofs = state.componentStates();
            var reversed = new ArrayList<>(proofs); Collections.reverse(reversed);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> copy(state, reversed, state.componentIndex()))
                    .getMessage().contains("first-member scalar order"));
            var indexes = state.componentIndex().storedIndexes();
            var selected = DocumentId.of(proofs.get(0).orderedMemberDocumentIds().get(0).value());
            var other = DocumentId.of(proofs.get(1).orderedMemberDocumentIds().get(0).value());
            var foreign = ProcessEmbeddedComponentIndex.restoreIndexes(new ProcessEmbeddedComponentIndex.StoredIndexes(
                    indexes.components().put(selected, indexes.components().get(other)).map(), indexes.targets(), indexes.sources(),
                    true, indexes.joins()));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> copy(state, proofs, foreign))
                    .getMessage().contains("exact member index"));
            assertDoesNotThrow(() -> copy(state, proofs, state.componentIndex()));
        }
    }

    @Test void legacyInventoryStillRequiresTargetBeforeSourceCondensationOrder() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var state = f.engine.documents().storedState();
            // Isolated legacy ordering control over this actual acyclic inventory;
            // the replacement is never installed in or used to execute a runtime.
            var legacy = ProcessEmbeddedComponentIndex.fromDocumentsAndOccurrenceInventory(
                    state.sessions().keySet(), state.occurrenceInventory());
            assertFalse(legacy.hasRootedViews());
            var ordered = legacy.components().stream()
                    .map(component -> state.componentStateInventory().forDocument(component.members().get(0))).toList();
            assertEquals(2, ordered.size()); assertDoesNotThrow(() -> copy(state, ordered, legacy));
            var reversed = new ArrayList<>(ordered); Collections.reverse(reversed);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> copy(state, reversed, legacy))
                    .getMessage().contains("target-before-source condensation order"));
        }
    }

    private static InMemoryDocumentStore.StoreState copy(InMemoryDocumentStore.StoreState state,
            List<ComponentSnapshot> components, ProcessEmbeddedComponentIndex index) {
        return new InMemoryDocumentStore.StoreState(state.sessions(), state.lineageIndex(), state.occurrenceInventory(),
                state.occurrenceInventoryGeneration(), index, state.componentIndexGeneration(), state.graphGenerations(),
                components, state.closureSubscriptions(), state.outbox(), state.checkpointEvidence(), state.publicationReceipts(),
                state.admissionReceipts(), state.closurePublicationReceipts(), state.managedEpochReceipts(), state.catchUpPlans());
    }
}
