package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

/** Complete StoreState only: other engine stores remain live; this is not the full cold SDK factory. */
final class StoredDocumentStoreTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
    private static final PersistentAppendLogStorage.Limits LOGS = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);

    @Test void selectedAuthorityPreflightPrecedesReceiptPrewritesEvenForRemovedOrReplacedRows() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
            var original = storage.retainPartition(f.engine.documents().storedState());
            String reverse = StoredDocumentIndexesTest.rootNodeAddress(original.root(StoredDocumentStore.Root.LINEAGE_CURRENT));
            byte[] valid = bytes.records.get(reverse).clone();
            for (boolean remove : List.of(false, true)) {
                try (var opened = storage.open(original, 4, 128)) {
                    var state = opened.state(); var selected = state.sessionIndex().get(source.id());
                    var admission = state.admissionReceiptIndex().minimum().entry(); assertNotNull(admission);
                    var admissions = state.admissionReceiptIndex().put(admission.getKey(), admission.getValue()).map();
                    var sessions = remove ? state.sessionIndex().remove(source.id()).map()
                            : state.sessionIndex().put(source.id(), f.engine.documents().require(source.id())).map();
                    assertNotSame(selected, sessions.get(source.id()));
                    var changed = withWorkingRows(state, sessions, admissions);
                    bytes.records.remove(reverse); int writes = bytes.writes;
                    assertThrows(CoordinationObjectStorageException.class, () -> opened.stage(changed));
                    assertEquals(writes, bytes.writes, "Every selected original must be checked before even the pending admission row is written");
                    assertThrows(CoordinationObjectStorageException.class, () -> state.sessionIndex().get(source.id()),
                            "Failure in the outer preflight must clear the positive selection");
                    bytes.records.put(reverse, valid.clone());
                    var retry = opened.stage(withWorkingRows(state, state.sessionIndex(), admissions));
                    assertTrue(bytes.writes > writes, "The valid retry actually exercises receipt prewrites");
                    for (var root : StoredDocumentStore.Root.values()) assertArrayEquals(original.root(root), retry.root(root));
                }
            }
        }
    }

    @Test void receiptPrewriteFailureBeforeSessionStagingRetiresWorkingSelectionFacts() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
            var original = storage.retainPartition(f.engine.documents().storedState());
            String reverse = StoredDocumentIndexesTest.rootNodeAddress(original.root(StoredDocumentStore.Root.LINEAGE_CURRENT));
            byte[] valid = bytes.records.get(reverse).clone();
            try (var opened = storage.open(original, 4, 128)) {
                var state = opened.state(); var selected = state.sessionIndex().get(source.id());
                var admission = state.admissionReceiptIndex().minimum().entry(); assertNotNull(admission);
                var changed = withWorkingRows(state, state.sessionIndex(),
                        state.admissionReceiptIndex().put(admission.getKey(), admission.getValue()).map());
                int writes = bytes.writes; bytes.failAtWrite = writes + 1;
                var failure = assertThrows(CoordinationObjectStorageException.class, () -> opened.stage(changed));
                assertEquals(writes + 1, bytes.writes);
                assertTrue(Arrays.stream(failure.getStackTrace()).noneMatch(frame ->
                        frame.getClassName().contains("StoredDocumentIndexes$WorkingSessions")),
                        "The injected receipt write fails before direct session staging starts");
                bytes.failAtWrite = -1; bytes.records.remove(reverse);
                assertThrows(CoordinationObjectStorageException.class, () -> state.sessionIndex().get(source.id()));
                bytes.records.put(reverse, valid.clone());
                assertSame(selected, state.sessionIndex().get(source.id()));
                var retry = opened.stage(changed);
                for (var root : StoredDocumentStore.Root.values()) assertArrayEquals(original.root(root), retry.root(root));
                bytes.records.get(reverse)[0] ^= 1;
                assertThrows(CoordinationObjectStorageException.class, () -> state.sessionIndex().get(source.id()),
                        "The complete successful outer stage also retires the selection facts");
                bytes.records.put(reverse, valid.clone());
            }
        }
    }

    @Test void completeColdStoreContinuesTheActualRootedProcessorWithExactRowsRootsAndCounters() throws Exception {
        assertEquals(next(false, false), next(true, false));
    }

    @Test void sameEpochRepresentationsRemainSeparateFromNumberedHistoryAcrossCompleteStoreOpen() throws Exception {
        assertEquals(next(false, true), next(true, true));
    }

    @Test void repeatedColdPublicationReadsReuseOnlyTheSameOwnersVerifiedReceiptAndProof() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var expectedState = f.state(); var expectedChain = f.chain();
            var original = f.publication(expectedChain.transitions().get(0));
            var bytes = new DocumentSessionStorageTest.Bytes();
            var partition = storage(bytes).retainPartition(f.engine.documents().storedState());
            var results = new blue.language.processor.closure.ClosureProcessResultStorageCodec(MAX, 256);
            var inputs = new blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec(MAX, 256);
            var publications = new PublicationReceiptStorageCodec(MAX, 256);
            ContractsClosurePublicationReceipt first;
            try (var cold = storage(bytes.copy()).open(partition, 8, 256)) {
                var documents = new InMemoryDocumentStore(new EngineMetrics(), cold.state());
                documents.bindStoredPublicationReuse(cold.publicationReuse());
                first = documents.closurePublicationReceipt(original.publicationIdentity()).orElseThrow();
                var repeated = documents.closurePublicationReceipt(original.publicationIdentity()).orElseThrow();
                assertNotSame(original, first, "A new owner must not reuse the resident producer's object");
                assertArrayEquals(publications.encodePublication(first, cold.viewScope()::addressOf),
                        publications.encodePublication(repeated, cold.viewScope()::addressOf));
                assertArrayEquals(results.encode(original.attempt().processResult()), results.encode(first.attempt().processResult()));
                var firstChain = new ManagedRepresentationHistory(documents).at(f.parent.id(), 0);
                var repeatedChain = new ManagedRepresentationHistory(documents).at(f.parent.id(), 0);
                assertEquals(expectedChain.transitions().size(), firstChain.transitions().size());
                assertEquals(firstChain.transitions().size(), repeatedChain.transitions().size());
                for (int i = 0; i < firstChain.transitions().size(); i++) {
                    var expected = expectedChain.transitions().get(i); var actual = firstChain.transitions().get(i);
                    assertEquals(expected.positionIdentity(), actual.positionIdentity());
                    assertEquals(actual.positionIdentity(), repeatedChain.transitions().get(i).positionIdentity());
                    assertArrayEquals(results.encode(expected.originalResult()), results.encode(actual.originalResult()));
                    assertArrayEquals(inputs.encodeInvocation(expected.originalInput()), inputs.encodeInvocation(actual.originalInput()));
                }
                var unchanged = cold.stage(cold.state());
                for (var root : StoredDocumentStore.Root.values()) assertArrayEquals(partition.root(root), unchanged.root(root));
                assertEquals(expectedState, f.state(), "Cold reads and pure proofs do not change producer history, events or gas");
                // Deterministic typed-instance/proof reuse oracle, not a wall-time or physical-read count.
                assertAll("one selected immutable receipt per cold owner",
                        () -> assertSame(first, repeated, "Repeated gets must not decode another complete receipt"),
                        () -> assertSame(first.attempt().processResult(), repeated.attempt().processResult()),
                        () -> assertSame(first.rootedTerminalEvidence().input(), repeated.rootedTerminalEvidence().input()),
                        () -> {
                            for (int i = 0; i < firstChain.transitions().size(); i++)
                                assertSame(firstChain.transitions().get(i), repeatedChain.transitions().get(i),
                                        "The durable identity check must allow reuse of the same exact constructor proof");
                        });
                cold.publicationReuse().clear();
                var afterEviction = new ManagedRepresentationHistory(documents).at(f.parent.id(), 0);
                for (int i = 0; i < firstChain.transitions().size(); i++) {
                    assertNotSame(firstChain.transitions().get(i), afterEviction.transitions().get(i),
                            "The proof memo must retire graphs evicted by its physical identity scope");
                    assertEquals(firstChain.transitions().get(i).positionIdentity(), afterEviction.transitions().get(i).positionIdentity());
                }
            }
            try (var fresh = storage(bytes.copy()).open(partition, 8, 256)) {
                var newOwner = fresh.state().closurePublicationReceipts().get(original.publicationIdentity());
                assertNotSame(first, newOwner, "Closing an owner retires its disposable typed identity scope");
                assertArrayEquals(results.encode(original.attempt().processResult()), results.encode(newOwner.attempt().processResult()));
            }
        }
    }

    private List<String> next(boolean cold, boolean representation) throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            String sourceYaml = resource("source.yaml");
            if (representation) sourceYaml += """
                  emitUnmatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: owner
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendEvent:
                          type: Coordination/Event
                          kind: RCP2/Unmatched
                      - $return: true
                """;
            var source = f.start(sourceYaml, "rcp2/source", ActivationPolicy.importFullHistory());
            var root = source; String operation = "tick";
            if (representation) {
                f.retain(source);
                root = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                        "rcp2/parent", ActivationPolicy.importFullHistory()); operation = "emitUnmatched";
            }
            f.process(root, f.append(source, "rcp2/source", operation));
            f.process(root, f.append(source, "rcp2/source", operation));
            var before = f.engine.documents().storedState();
            assertEquals(representation ? 0L : 2L, before.sessions().get(root.id()).epoch());
            if (representation) assertEquals(2, before.sessions().get(root.id()).representationTransitions().size());
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
            StoredDocumentStore.Opened opened = null;
            try {
                if (cold) {
                    var selection = storage.retainPartition(before); bytes = bytes.copy(); storage = storage(bytes);
                    int providers = f.providerReads.get(), writes = bytes.writes;
                    opened = storage.open(selection, 8, 256);
                    assertEquals(writes, bytes.writes); assertEquals(providers, f.providerReads.get());
                    var state = opened.state();
                    assertNotSame(before.sessions().get(root.id()), state.sessions().get(root.id()));
                    assertSame(state.sessions().get(root.id()), state.sessions().get(root.id()));
                    assertEquals(before.sessionIndex().read(root.id()).comparisons(), state.sessionIndex().read(root.id()).comparisons());
                    install(f, state);
                }
                f.process(root, f.append(source, "rcp2/source", operation));
                var actual = f.engine.documents().storedState();
                assertEquals(representation ? 0L : 3L, actual.sessions().get(root.id()).epoch());
                if (representation) {
                    assertEquals(3, actual.sessions().get(root.id()).representationTransitions().size());
                    assertEquals(0L, actual.sessions().get(source.id()).epoch());
                }
                var finalSelection = cold ? opened.stage(actual) : storage.retainPartition(actual);
                var evidence = new ArrayList<>(f.completeEvidence(root.id()));
                evidence.add(finalSelection.metadata().toString());
                for (var key : StoredDocumentStore.Root.values()) evidence.add(key + ":" + Base64.getEncoder().encodeToString(finalSelection.root(key)));
                try (var again = storage(bytes.copy()).open(finalSelection, 8, 256)) {
                    var restored = again.state().sessions().get(root.id());
                    assertEquals(f.storage.retain(actual.sessions().get(root.id())), new DocumentSessionStorage(bytes, SESSIONS).retain(restored));
                    assertEquals(actual.outbox().size(), again.state().outbox().size());
                    assertEquals(actual.checkpointEvidence().size(), again.state().checkpointEvidence().size());
                    for (var publication : actual.closurePublicationReceipts().keySet())
                        assertEquals(publication, again.state().closurePublicationReceipts().get(publication).publicationIdentity());
                }
                return List.copyOf(evidence);
            } finally { if (opened != null) opened.close(); }
        }
    }

    @Test void openDoesNotLoadAnySessionAndSelectedMissingAuthorityFailsInsteadOfBecomingAbsence() throws Exception {
        StoredDocumentStore.Selection selection; DocumentId selected; Set<String> sessionAddresses = new HashSet<>();
        DocumentSessionStorageTest.Bytes bytes;
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            selected = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow()).id();
            for (int i = 0; i < 5; i++) f.start(resource("source.yaml").replace("rcp2/source", "independent/" + i), "independent/" + i, ActivationPolicy.fromNow());
            bytes = new DocumentSessionStorageTest.Bytes(); selection = storage(bytes).retainPartition(f.engine.documents().storedState());
            var addressRows = new StoredDocumentIndexes(bytes, MAPS, SESSIONS).openSessions(selection.root(StoredDocumentStore.Root.SESSIONS));
            addressRows.values().forEach(row -> sessionAddresses.add(row.address()));
        }
        var writes = bytes.writes; var permitted = new HashSet<String>(); var retained = bytes;
        CoordinationImmutableObjectStore guarded = new CoordinationImmutableObjectStore() {
            public byte[] putIfAbsent(String digest, byte[] value) { throw new AssertionError("Read-only open wrote bytes"); }
            public Optional<byte[]> get(String digest, int bound) {
                if (sessionAddresses.contains(digest) && !permitted.contains(digest)) throw new CoordinationObjectStorageException("Unselected session unavailable");
                return retained.get(digest, bound);
            }
        };
        var addressRows = new StoredDocumentIndexes(bytes, MAPS, SESSIONS).openSessions(selection.root(StoredDocumentStore.Root.SESSIONS));
        try (var opened = storage(guarded).open(selection, 2, 128)) {
            assertThrows(CoordinationObjectStorageException.class, () -> opened.state().sessions().get(selected));
            permitted.add(addressRows.get(selected).address());
            assertEquals(selected, opened.state().sessions().get(selected).documentId());
            assertEquals(writes, bytes.writes);
        }
        var missing = replace(selection, StoredDocumentStore.Root.SESSIONS, addressRows.remove(selected).map().storedRootDescriptor());
        try (var opened = storage(bytes).open(missing, 2, 128)) {
            assertThrows(CoordinationObjectStorageException.class, () -> opened.state().sessions().get(selected));
        }
        var incomplete = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
        assertThrows(CoordinationObjectStorageException.class, () -> new StoredDocumentStore.Selection(incomplete, selection.metadata()));
    }

    @Test void failedFinalStagingKeepsTheOriginalSelectionAndActualWorkingSessionForExactRetry() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var bytes = new DocumentSessionStorageTest.Bytes(); var storage = storage(bytes);
            var selected = storage.retainPartition(f.engine.documents().storedState());
            try (var opened = storage.open(selected, 4, 128)) {
                install(f, opened.state());
                f.process(source, f.append(source, "rcp2/source", "tick"));
                var working = f.engine.documents().storedState(); var actual = working.sessions().get(source.id());
                int writes = bytes.writes; bytes.failAtWrite = writes + 3;
                assertThrows(CoordinationObjectStorageException.class, () -> opened.stage(working));
                assertEquals(writes + 3, bytes.writes, "The failure follows successful immutable prewrites");
                bytes.failAtWrite = -1;
                assertSame(actual, working.sessions().get(source.id())); assertEquals(2L, actual.epoch());
                try (var old = storage.open(selected, 4, 128)) { assertEquals(1L, old.state().sessions().get(source.id()).epoch()); }
                var committed = opened.stage(working);
                try (var restored = storage(bytes.copy()).open(committed, 4, 128)) {
                    assertEquals(2L, restored.state().sessions().get(source.id()).epoch());
                    assertEquals(working.outbox().size(), restored.state().outbox().size());
                }
                var repeated = opened.stage(working);
                assertEquals(committed.metadata(), repeated.metadata());
                for (var key : StoredDocumentStore.Root.values()) assertArrayEquals(committed.root(key), repeated.root(key));
            }
        }
    }

    @Test void selectedReceiptCrosslinksAndKnownRangeHolesFailBelowRawStoreSnapshotReads() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var bytes = new DocumentSessionStorageTest.Bytes(); var selected = storage(bytes).retainPartition(f.engine.documents().storedState());
            var receiptStorage = new StoredManagedEpochIndexes(bytes, MAPS);
            var raw = receiptStorage.open(key -> selected.root(StoredDocumentStore.Root.valueOf("RECEIPT_" + key)), 0, 0);
            var s = raw.storedState(); var h = s.documents().get(source.id()).storedState();
            String receiptId = h.receipts().get(1L).publicReceipt().receiptIdentity();
            var missingIdentity = replace(selected, StoredDocumentStore.Root.RECEIPT_IDENTITY, s.identities().remove(receiptId).map().storedRootDescriptor());
            try (var cold = storage(bytes).open(missingIdentity, 4, 128)) {
                assertThrows(CoordinationObjectStorageException.class, () -> cold.state().managedEpochReceipts().exactEvidence(source.id(), 1));
                assertTrue(cold.state().managedEpochReceipts().exactEvidence(source.id(), 2).found());
            }
            // Preserve count and the valid first/last rows: only selecting missing epoch1 must reveal the forged interior.
            var brokenEpochs = h.receipts().remove(1L).map().put(3L, h.receipts().get(1L)).map();
            var brokenHistory = ManagedEpochReceiptStore.DocumentHistory.restoreStored(new ManagedEpochReceiptStore.DocumentHistory.StoredState(
                    h.document(), brokenEpochs, h.latestEpoch(), h.currentRepresentation(), h.comparisons(), h.copiedNodes()));
            var hole = replace(selected, StoredDocumentStore.Root.RECEIPT_DOCUMENT, s.documents().put(source.id(), brokenHistory).map().storedRootDescriptor());
            try (var cold = storage(bytes).open(hole, 4, 128)) {
                assertTrue(cold.state().managedEpochReceipts().exactEvidence(source.id(), 0).found());
                assertThrows(CoordinationObjectStorageException.class, () -> cold.state().managedEpochReceipts().exact(source.id(), 1));
                assertThrows(CoordinationObjectStorageException.class, () -> cold.state().managedEpochReceipts().exactEvidence(source.id(), 3));
            }
            try (var cold = storage(bytes).open(selected, 4, 128)) {
                assertFalse(cold.state().managedEpochReceipts().exactEvidence(source.id(), 3).found(), "Real out-of-range absence stays absence");
            }
            assertThrows(CoordinationObjectStorageException.class, () -> storage(bytes).open(selected, 4, 1));
        }
    }

    @Test void keyOnlyPendingJoinLookupStillValidatesTheSelectedReverseBucket() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var bytes = new DocumentSessionStorageTest.Bytes(); var selected = storage(bytes).retainPartition(f.engine.documents().storedState());
            var topology = new StoredTopologyIndexes(bytes, MAPS);
            var raw = topology.open(key -> selected.root(StoredDocumentStore.Root.valueOf("TOPOLOGY_" + key)), true);
            var member = DocumentId.of("unrelated-member");
            assertTrue(raw.pendingJoinRootsFor(member).isEmpty());
            var forgedBucket = PersistentOrderedMap.<DocumentId, Boolean>empty(EmbeddingBinding.DOCUMENT_ORDER).put(source.id(), true).map();
            var forged = raw.storedIndexes().joins().storedIndexes().roots().put(member, forgedBucket).map();
            var mixed = replace(selected, StoredDocumentStore.Root.TOPOLOGY_JOIN_ROOTS, forged.storedRootDescriptor());
            try (var cold = storage(bytes).open(mixed, 4, 128)) {
                assertThrows(CoordinationObjectStorageException.class, () -> cold.state().componentIndex().pendingJoinRootsFor(member),
                        "The consumer enumerates keys, not values; reverse membership must still be checked");
            }
            try (var cold = storage(bytes).open(selected, 4, 128)) { assertTrue(cold.state().componentIndex().pendingJoinRootsFor(member).isEmpty()); }
        }
    }

    private static StoredDocumentStore.Selection replace(StoredDocumentStore.Selection original, StoredDocumentStore.Root key, byte[] value) {
        var roots = new EnumMap<StoredDocumentStore.Root, byte[]>(StoredDocumentStore.Root.class);
        for (var root : StoredDocumentStore.Root.values()) roots.put(root, root == key ? value : original.root(root));
        return new StoredDocumentStore.Selection(roots, original.metadata());
    }
    private static StoredDocumentStore storage(CoordinationImmutableObjectStore bytes) { return new StoredDocumentStore(bytes, MAPS, LOGS, SESSIONS); }
    private static InMemoryDocumentStore.StoreState withWorkingRows(InMemoryDocumentStore.StoreState s,
            PersistentOrderedMap<DocumentId, DocumentSession> sessions,
            PersistentOrderedMap<String, blue.coordination.api.ContractsClosureAdmissionReceipt> admissions) {
        return InMemoryDocumentStore.StoreState.trustedTransition(sessions, s.lineageIndex(), s.occurrenceInventory(),
                s.occurrenceInventoryGeneration(), s.componentIndex(), s.componentIndexGeneration(), s.graphGenerations(),
                s.componentStateInventory(), s.closureSubscriptions(), s.outboxLog(), s.checkpointEvidenceLog(),
                s.publicationReceiptIndex(), admissions, s.closurePublicationReceiptIndex(), s.rootedProviderFrontiers(),
                s.closureApplicationResults(),
                s.managedEpochReceipts(), s.catchUpPlans());
    }
    private static void install(DocumentSessionStorageTest.Fixture f, InMemoryDocumentStore.StoreState state) throws Exception {
        var field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true); field.set(f.engine.documents(), state);
    }
}
