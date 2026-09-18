package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.EntryDisposition;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hash-correct index nodes still have to bind to the exact selected session and metadata rows. */
final class IndexedLineageBindingTest {
    private static final PersistentMapStorage.Limits MAP =
            new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);
    private static final DocumentSessionStorage.Limits SESSION =
            new DocumentSessionStorage.Limits(40 * 1024 * 1024, 256, 512L * 1024 * 1024);

    @Test void sameHeaderAndEndpointsCannotBindADifferentMiddleMetadataRootToTheSelectedSession() throws Exception {
        // given
        try (var f = scenario()) {
            for (boolean controlled : List.of(false, true)) {
                var bytes = new RootedHistoryAccessObjects();
                var namespace = controlled ? RootedEngineStorage.controlledNamespace(bytes) : bytes;
                var storage = new StoredDocumentIndexes(namespace, MAP, SESSION);
                var state = f.engine.documents().storedState();
                var sessions = storage.retainSessionPartition(state.sessionIndex());
                var lineages = storage.retainLineagePartition(state.lineageIndex());
                var generations = storage.retainGenerationPartition(state.graphGenerations());
                var retained = lineages.storedState(); var original = retained.documents().get(f.parent.id());
                var history = RetainedStateHistory.indexed(original.documentId(), original.retainedStates());
                var changedRoot = history.index().put(1L, new ManagedLineageIndex.RetainedState(original.documentId(), 1L,
                        history.get(0).blueId())).map();
                // when
                var changed = replaceHistory(original, new RetainedStateHistory(original.documentId(), changedRoot));
                // then
                assertEquals(original.currentEpoch(), changed.currentEpoch());
                assertEquals(original.currentBlueId(), changed.currentBlueId());
                assertEquals(original.lastAnchoredNonReplayableEpoch(), changed.lastAnchoredNonReplayableEpoch());
                assertEquals(original.retainedStates().size(), changed.retainedStates().size());
                assertEquals(original.retainedStates().get(0), changed.retainedStates().get(0));
                assertEquals(original.retainedStates().get(3), changed.retainedStates().get(3));
                assertNotEquals(original.retainedStates().get(1), changed.retainedStates().get(1));
                var conflicting = ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(
                        retained.documents().put(original.documentId(), changed).map(), retained.authored(), retained.initialized(),
                        retained.retained(), retained.current(), retained.copiedNodes()));
                try (var owner = storage.openOwner(2)) {
                    assertThrows(CoordinationObjectStorageException.class,
                            () -> owner.find(sessions, conflicting, generations, original.documentId()),
                            "Equal counts, endpoints and gap scalar cannot substitute for the exact metadata root");
                    assertEquals(3L, owner.find(sessions, lineages, generations, original.documentId()).orElseThrow().session().epoch(),
                            "Failed cross-root verification must not pin a poisoned selection");
                }
            }
        }
    }

    @Test void strictSelectionRejectsCorrectlyAddressedOmissionFromSecondaryRetainedIndex() throws Exception {
        // given
        try (var f = scenario()) {
            var bytes = new RootedHistoryAccessObjects(); var storage = new StoredDocumentIndexes(bytes, MAP, SESSION);
            var state = f.engine.documents().storedState();
            var sessions = storage.retainSessionPartition(state.sessionIndex());
            var lineages = storage.retainLineagePartition(state.lineageIndex());
            var generations = storage.retainGenerationPartition(state.graphGenerations());
            var retained = lineages.storedState(); var lineage = retained.documents().get(f.parent.id());
            // when
            String omitted = lineage.retainedStates().get(1).blueId();
            // then
            assertNotNull(retained.retained().get(omitted));
            var missing = ManagedLineageIndex.restoreStored(new ManagedLineageIndex.StoredState(retained.documents(),
                    retained.authored(), retained.initialized(), retained.retained().remove(omitted).map(),
                    retained.current(), retained.copiedNodes()));
            try (var owner = storage.openOwner(2)) {
                assertThrows(CoordinationObjectStorageException.class,
                        () -> owner.find(sessions, missing, generations, f.parent.id()),
                        "All nodes are canonically encoded at their correct addresses; completeness is still required");
                assertEquals(3L, owner.find(sessions, lineages, generations, f.parent.id()).orElseThrow().session().epoch());
            }
        }
    }

    @Test void strictLineageCodecChecksInteriorOwnerAndControlledSelectionChecksTheRequestedRow() throws Exception {
        // given
        try (var f = scenario()) {
            var bytes = new RootedHistoryAccessObjects();
            var controlled = new StoreIndexCodecs(RootedEngineStorage.controlledNamespace(bytes), MAP);
            var strict = new StoreIndexCodecs(bytes, MAP);
            var lineage = ManagedLineageIndex.Lineage.from(f.engine.documents().require(f.parent.id()));
            var history = RetainedStateHistory.indexed(lineage.documentId(), lineage.retainedStates());
            var wrong = history.index().put(1L, new ManagedLineageIndex.RetainedState(DocumentId.of("foreign-owner"), 1L,
                    history.get(1).blueId())).map();
            var malformed = replaceHistory(lineage, new RetainedStateHistory(lineage.documentId(), wrong));
            // when
            byte[] frame = controlled.lineages.encode(controlled.lineages.prepareForStorage(malformed));
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> strict.lineages.decode(frame));
            var selected = controlled.lineages.decode(frame);
            assertEquals(lineage.retainedStates().get(0), selected.retainedStates().get(0));
            assertThrows(CoordinationObjectStorageException.class, () -> selected.retainedStates().get(1),
                    "Controlled-origin does not waive selected owner/epoch checks");
        }
    }

    private static ManagedLineageIndex.Lineage replaceHistory(ManagedLineageIndex.Lineage original, RetainedStateHistory history) {
        return new ManagedLineageIndex.Lineage(original.documentId(), original.authoredInitialBlueId(), original.initializedBlueId(),
                original.currentEpoch(), original.currentBlueId(), history, original.lastAnchoredNonReplayableEpoch());
    }
    private static ManagedRepresentationVerificationMemoTest.Scenario scenario() throws Exception {
        var f = new ManagedRepresentationVerificationMemoTest.Scenario();
        try {
            for (long time : List.of(300L, 400L, 500L)) {
                var entry = f.append(time, "tick");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            }
            assertEquals(3L, f.parent.snapshot().epoch()); return f;
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
    }
}
