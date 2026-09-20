package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.language.processor.closure.ClosureExecutionEvidenceStorageCodec;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;

final class StoredManagedEpochIndexesTest {
    private static final int MAX = 8 * 1024 * 1024;
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(MAX, 4096, MAX - 8192, 8192, 32);

    @Test void oldLogicalReceiptWitnessDoesNotFenceALaterSourceEpoch() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var original = state(f).managedEpochReceipts(); var storage = storage(f.bytes);
            var execution = new ClosureExecutionEvidenceStorageCodec(MAX, 128);
            var store = new LogicalRecordMapTest.Store(); var seed = store.attempt(); var seedContext = new LogicalRecordContext(seed);
            var initial = storage.openLogical(seedContext);
            for (long epoch = 0; epoch <= 1; epoch++) {
                var row = original.exactEvidence(source.id(), epoch); initial = initial.withReceipt(row.receipt(), row.transitionReceipt());
            }
            storage.selectLogical(initial); seedContext.flush(); var evidence = new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1});
            assertTrue(store.publish(seed.prepare("seed", List.of(), evidence)));
            var reader = store.attempt(); var writer = store.attempt();
            var rc = new LogicalRecordContext(reader); var wc = new LogicalRecordContext(writer);
            // when
            var old = storage.exact(storage.openLogical(rc), source.id(), 1);
            var newer = original.exactEvidence(source.id(), 2);
            storage.selectLogical(storage.openLogical(wc).withReceipt(newer.receipt(), newer.transitionReceipt()));
            rc.flush(); wc.flush(); var read = reader.prepare("witness", List.of(), evidence); var write = writer.prepare("advance", List.of(), evidence);
            // then
            assertArrayEquals(execution.encodeTransitionReceipt(original.exactEvidence(source.id(), 1).transitionReceipt()),
                    execution.encodeTransitionReceipt(old.transitionReceipt()));
            assertTrue(read.points().stream().noneMatch(point -> OrderedRecordKey.text().decode(point.key().scope().copy()).endsWith("receipt-heads")));
            assertTrue(store.publish(write)); assertTrue(store.publish(read));
            try (var cold = store.attempt()) {
                var retained = storage.openLogical(new LogicalRecordContext(cold));
                assertEquals(2, retained.latestEpoch(source.id()));
                assertArrayEquals(execution.encodeTransitionReceipt(newer.transitionReceipt()),
                        execution.encodeTransitionReceipt(storage.exact(retained, source.id(), 2).transitionReceipt()));
            }
        }
    }

    @Test void actualEventsAndCompleteReceiptEvidenceSurviveProducerClosure() throws Exception {
        // given
        DocumentSessionStorageTest.Bytes bytes; Map<StoredManagedEpochIndexes.Root, byte[]> roots;
        DocumentId sourceId; List<byte[]> expected = new ArrayList<>(); int comparisons; int copies;
        var execution = new ClosureExecutionEvidenceStorageCodec(MAX, 128);
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory()); sourceId = source.id();
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var resident = state(f).managedEpochReceipts();
            for (long epoch = 0; epoch <= 2; epoch++)
            // when
            expected.add(execution.encodeTransitionReceipt(resident.exactTransition(sourceId, epoch).transitionReceipt()));
            // then
            assertFalse(resident.exactTransition(sourceId, 1).transitionReceipt().emittedRootEvents().isEmpty());
            var storage = storage(f.bytes); var retained = storage.retainPartition(resident);
            roots = roots(storage, retained); bytes = f.bytes.copy(); comparisons = resident.storedState().comparisons(); copies = resident.storedState().copiedNodes();
            for (long epoch = 0; epoch <= 2; epoch++) assertEquals(resident.exactEvidence(sourceId, epoch).indexComparisons(),
                    retained.exactEvidence(sourceId, epoch).indexComparisons(), "Storage preserves logical balanced-tree shape");
        }
        var cold = storage(bytes); var opened = cold.open(roots::get, comparisons, copies);
        int writes = bytes.writes;
        for (long epoch = 0; epoch <= 2; epoch++) {
            var read = cold.exact(opened, sourceId, epoch);
            assertArrayEquals(expected.get((int) epoch), execution.encodeTransitionReceipt(read.transitionReceipt()));
            assertEquals(read.receipt().receiptIdentity(), opened.byIdentity(read.receipt().receiptIdentity()).receipt().receiptIdentity());
            assertEquals(0, read.unrelatedDocumentReads());
        }
        assertFalse(cold.exact(opened, sourceId, 3).found());
        assertFalse(cold.exact(opened, DocumentId.of("unrelated-absent"), 0).found());
        assertEquals(writes, bytes.writes, "Selected reads never retain objects");
    }

    @Test void runtimeCacheDecodesEachCompleteReceiptOnceAcrossIndexesAndFreshStorageOwners() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var original = state(f).managedEpochReceipts();
            var writer = storage(f.bytes); var retained = writer.retainPartition(original); var descriptors = roots(writer, retained);
            var execution = new ClosureExecutionEvidenceStorageCodec(MAX, 128);
            var previous = new ArrayList<ManagedEpochReceiptStore.EvidenceRead>();
            int writes = f.bytes.writes;
            for (int owner = 0; owner < 3; owner++) {
                var storage = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache);
                var opened = storage.open(descriptors::get, original.storedState().comparisons(), original.storedState().copiedNodes());
                for (long epoch = 0; epoch <= 2; epoch++) {
                    var read = storage.exact(opened, source.id(), epoch);
                    // when
                    var expected = original.exactEvidence(source.id(), epoch);
                    // then
                    assertArrayEquals(execution.encodeTransitionReceipt(expected.transitionReceipt()),
                            execution.encodeTransitionReceipt(read.transitionReceipt()));
                    assertEquals(expected.receipt().sourceOrder(), read.receipt().sourceOrder());
                    assertEquals(expected.receipt().processingGas(), read.receipt().processingGas());
                    assertEquals(expected.indexComparisons(), read.indexComparisons());
                    assertSame(read.receipt(), opened.byIdentity(read.receipt().receiptIdentity()).receipt());
                    if (owner == 0) previous.add(read);
                    else {
                        assertSame(previous.get((int) epoch).receipt(), read.receipt());
                        assertSame(previous.get((int) epoch).transitionReceipt(), read.transitionReceipt());
                    }
                }
                assertEquals(3, cache.statistics().loads(), "Only this artifact family uses the test cache: E0/E1/E2 decode once");
                assertEquals(3, cache.statistics().retainedEntries());
                assertFalse(storage.exact(opened, source.id(), 3).found());
            }
            assertTrue(cache.statistics().hits() > 3);
            assertEquals(writes, f.bytes.writes);
        }
    }

    @Test void warmCacheDoesNotAuthorizeMissingIdentitySwappedReceiptWrongOwnerOrInteriorHole() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick")); f.process(source, f.append(source, "rcp2/source", "tick"));
            var writer = storage(f.bytes); var retained = writer.retainPartition(state(f).managedEpochReceipts());
            var descriptors = roots(writer, retained); var storage = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache);
            var opened = storage.open(descriptors::get, 0, 0); var good = storage.exact(opened, source.id(), 1);
            storage.exact(opened, source.id(), 2);
            var s = opened.storedState(); var h = s.documents().get(source.id()).storedState();
            String identity = good.receipt().receiptIdentity();
            // when
            var missing = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(s.documents(),
                    s.identities().remove(identity).map(), s.comparisons(), s.copiedNodes()));
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(missing, source.id(), 1));
            var swapped = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(s.documents(),
                    s.identities().put(identity, h.receipts().get(2L)).map(), s.comparisons(), s.copiedNodes()));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(swapped, source.id(), 1));
            var other = DocumentId.of("wrong-cached-receipt-owner");
            var wrongOwner = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(
                    s.documents().put(other, s.documents().get(source.id())).map(), s.identities(), s.comparisons(), s.copiedNodes()));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(wrongOwner, other, 1));
            var moved = h.receipts().remove(1L).map().put(3L, h.receipts().get(1L)).map();
            var broken = ManagedEpochReceiptStore.DocumentHistory.restoreStored(new ManagedEpochReceiptStore.DocumentHistory.StoredState(
                    h.document(), moved, h.latestEpoch(), h.currentRepresentation(), h.comparisons(), h.copiedNodes()));
            var hole = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(
                    s.documents().put(source.id(), broken).map(), s.identities(), s.comparisons(), s.copiedNodes()));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(hole, source.id(), 1));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(hole, source.id(), 3));
            assertEquals(identity, storage.exact(opened, source.id(), 1).receipt().receiptIdentity());

            // Authenticate selected physical bytes before consulting cached decoded receipts.
            var damaged = f.bytes.copy();
            damaged.records.values().stream().filter(value -> source.id().equals(epochNodeOwner(value)))
                    .forEach(value -> value[0] ^= 1);
            var corrupted = new StoredManagedEpochIndexes(damaged, LIMITS, cache);
            var corruptedStore = corrupted.open(descriptors::get, 0, 0);
            long loads = cache.statistics().loads();
            assertThrows(CoordinationObjectStorageException.class, () -> corrupted.exact(corruptedStore, source.id(), 1));
            assertEquals(loads, cache.statistics().loads(), "Physical mismatch is not an invitation to trust a warm artifact");
        }
    }

    @Test void invalidFramesAreNotRetainedAndProfilesDoNotShareValidation() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var storage = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache); var codec = receiptCodec(storage);
            var row = state(f).managedEpochReceipts().storedState().documents().get(source.id()).storedState().receipts().get(0L);
            byte[] encoded = codec.encode(row);
            // when
            var verified = codec.decode(encoded);
            // then
            assertSame(row, verified, "A successful complete cold roundtrip preserves the immutable producer receipt");
            assertSame(verified, codec.decode(encoded)); assertEquals(1, cache.statistics().loads());
            int retained = cache.statistics().retainedEntries();
            for (byte[] invalid : List.of(Arrays.copyOf(encoded, encoded.length - 1), Arrays.copyOf(encoded, encoded.length + 1))) {
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(invalid));
                assertThrows(CoordinationObjectStorageException.class, () -> codec.decode(invalid));
            }
            assertEquals(4, cache.statistics().failedLoads()); assertEquals(retained, cache.statistics().retainedEntries());
            assertSame(verified, codec.decode(encoded));
            var different = new PersistentMapStorage.Limits(MAX, 4096, LIMITS.valueBytes() - 1, 8192, 32);
            var otherCodec = receiptCodec(new StoredManagedEpochIndexes(f.bytes, different, cache));
            assertNotSame(verified, otherCodec.decode(encoded));
            assertEquals(6, cache.statistics().loads(), "A distinct byte-bound profile needs its own complete validation");
            assertArrayEquals(encoded, otherCodec.encode(otherCodec.decode(encoded)));
        }
    }

    @Test void receiptEvictionAndDisabledCacheChangeReconstructionCountNotEvidence() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var history = state(f).managedEpochReceipts().storedState().documents().get(source.id()).storedState();
            var plain = receiptCodec(storage(f.bytes)); byte[] first = plain.encode(history.receipts().get(0L));
            byte[] second = plain.encode(history.receipts().get(1L));
            try (var cache = new RootedStorageCache(512L * 1024 * 1024, 1, 512L * 1024 * 1024)) {
                var codec = receiptCodec(new StoredManagedEpochIndexes(f.bytes, LIMITS, cache));
                var original = codec.decode(first); codec.decode(second);
                // when
                var reloaded = codec.decode(first);
                // then
                assertNotSame(original, reloaded); assertArrayEquals(first, codec.encode(reloaded));
                assertEquals(3, cache.statistics().loads()); assertEquals(2, cache.statistics().evictions());
            }
            try (var disabled = new RootedStorageCache(0, 0, 0)) {
                var codec = receiptCodec(new StoredManagedEpochIndexes(f.bytes, LIMITS, disabled));
                var original = codec.decode(first); var reloaded = codec.decode(first);
                assertNotSame(original, reloaded); assertArrayEquals(first, codec.encode(reloaded));
                assertEquals(2, disabled.statistics().loads()); assertEquals(0, disabled.statistics().retainedEntries());
            }
        }
    }

    @Test void actualCyclicReceiptReusesItsCompleteVerifiedProofAcrossOwners() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var b = f.start(resource("cycle-b.yaml"), "rcp2/cycle", ActivationPolicy.importFullHistory());
            String yaml = resource("cycle-a.yaml") + "\npeer: {blueId: " + b.snapshot().blueId() + "}\n";
            var authored = f.blue.values().yaml(yaml); f.exact.put(authored.blueId(), authored.json());
            var a = f.start(yaml, "rcp2/cycle", ActivationPolicy.importFullHistory());
            // when
            var connect = appendCycle(f, b, "connectA", 90, "a: {blueId: " + authored.blueId() + "}");
            // then
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(b).entry(connect).disposition());
            assertTrue(f.blue.processing().processNext(b).quiescent());
            var finite = appendCycle(f, a, "startFinite", 100, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(a).entry(finite).disposition());
            assertEquals("done", a.snapshot().textAt("/phase")); assertEquals("relayed", b.snapshot().textAt("/phase"));
            var before = List.of(f.completeEvidence(a.id()), f.completeEvidence(b.id()));
            var original = state(f).managedEpochReceipts(); long epoch = original.latestEpoch(a.id());
            assertTrue(original.exact(a.id(), epoch).receipt().afterDocument().isCyclicMember());
            var writer = storage(f.bytes); var retained = writer.retainPartition(original); var descriptors = roots(writer, retained);
            var cold = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache); var opened = cold.open(descriptors::get, 0, 0);
            var first = cold.exact(opened, a.id(), epoch);
            assertTrue(first.receipt().afterDocument().isCyclicMember());
            long loads = cache.statistics().loads();
            var next = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache);
            var repeated = next.exact(next.open(descriptors::get, 0, 0), a.id(), epoch);
            assertSame(first.receipt(), repeated.receipt()); assertSame(first.transitionReceipt(), repeated.transitionReceipt());
            assertEquals(loads, cache.statistics().loads(), "The complete cyclic-set calculation is not repeated for the same frames");
            assertEquals(before, List.of(f.completeEvidence(a.id()), f.completeEvidence(b.id())));
        }
    }

    @Test void replacingOnlyReceiptIndexesPreservesTheNextActualProcessorResult() throws Exception {
        // given
        var uninterrupted = next(false);
        // when
        var restored = next(true);
        // then
        assertEquals(uninterrupted, restored);
    }

    @Test void selectedReceiptDoesNotHydrateUnrelatedNestedHistoriesOnItsOuterSearchPath() throws Exception {
        // given
        DocumentSessionStorageTest.Bytes bytes; Map<StoredManagedEpochIndexes.Root, byte[]> descriptors;
        DocumentId selected; Set<String> denied = new HashSet<>();
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var ids = new ArrayList<DocumentId>();
            for (int i = 0; i < 7; i++) ids.add(f.start(resource("source.yaml").replace("rcp2/source", "rcp2/independent-" + i),
                    "rcp2/independent-" + i, ActivationPolicy.importFullHistory()).id());
            ids.sort(EmbeddingBinding.DOCUMENT_ORDER); selected = ids.get(0);
            var storage = storage(f.bytes);
            // when
            var retained = storage.retainPartition(state(f).managedEpochReceipts());
            // then
            assertTrue(retained.storedState().documents().read(selected).comparisons() > 1,
                    "The selected lookup must traverse at least one unrelated outer node");
            descriptors = roots(storage, retained); bytes = f.bytes.copy();
            // Only nested epoch nodes are denied. Outer AVL frames remain available,
            // including frames whose uninterpreted value contains another history descriptor.
            bytes.records.forEach((address, value) -> {
                DocumentId owner = epochNodeOwner(value);
                if (owner != null && !owner.equals(selected)) denied.add(address);
            });
            assertTrue(denied.size() >= 6, "Every unrelated independent epoch history must be unavailable");
        }
        var blockedReads = new java.util.concurrent.atomic.AtomicInteger();
        CoordinationImmutableObjectStore guarded = new CoordinationImmutableObjectStore() {
            public byte[] putIfAbsent(String digest, byte[] value) { throw new AssertionError("Read-only restore must not write"); }
            public Optional<byte[]> get(String digest, int maximumBytes) {
                if (denied.contains(digest)) { blockedReads.incrementAndGet(); throw new AssertionError("Unrelated history hydrated"); }
                return bytes.get(digest, maximumBytes);
            }
        };
        var cold = new StoredManagedEpochIndexes(guarded, LIMITS);
        var reopened = cold.open(descriptors::get, 0, 0);
        assertEquals(selected, cold.exact(reopened, selected, 0).receipt().documentId());
        assertEquals(0, blockedReads.get());
        try (var cache = cache()) {
            var warm = new StoredManagedEpochIndexes(guarded, LIMITS, cache);
            var next = warm.open(descriptors::get, 0, 0);
            assertEquals(selected, warm.exact(next, selected, 0).receipt().documentId());
            long loads = cache.statistics().loads();
            assertEquals(selected, warm.exact(next, selected, 0).receipt().documentId());
            assertEquals(loads, cache.statistics().loads()); assertEquals(0, blockedReads.get());
        }
    }

    private List<String> next(boolean cold) throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            if (cold) {
                var before = state(f); var original = before.managedEpochReceipts(); var storage = storage(f.bytes);
                var retained = storage.retainPartition(original); var descriptors = roots(storage, retained);
                var reopened = storage(f.bytes.copy()).open(descriptors::get, original.storedState().comparisons(), original.storedState().copiedNodes());
                stateField().set(f.engine.documents(), before.withManagedEpochReceipts(reopened));
            }
            f.process(source, f.append(source, "rcp2/source", "tick"));
            return f.completeEvidence(source.id());
        }
    }

    @Test void sameEpochRepresentationIsNotMistakenForTheLastNumberedReceipt() throws Exception {
        // given
        RootedStorageCache cache = null;
        // when
        org.junit.jupiter.api.function.Executable scenario = () -> sameEpochRepresentation(cache);
        // then
        assertDoesNotThrow(scenario);
    }

    @Test void warmNumberedReceiptDoesNotReplaceTheCurrentSameEpochRepresentation() throws Exception {
        // given
        try (var cache = cache()) {
            // when
            org.junit.jupiter.api.function.Executable scenario = () -> sameEpochRepresentation(cache);
            // then
            assertDoesNotThrow(scenario);
        }
    }

    private void sameEpochRepresentation(RootedStorageCache cache) throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml") + """
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
                """, "rcp2/source", ActivationPolicy.importFullHistory());
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            var before = state(f).managedEpochReceipts(); long numberedEpoch = before.latestEpoch(parent.id());
            String numbered = before.exact(parent.id(), numberedEpoch).receipt().afterBlueId();
            blue.coordination.api.ManagedEpochReceipt cachedNumbered = null;
            if (cache != null) {
                var writer = storage(f.bytes); var earlier = writer.retainPartition(before); var roots = roots(writer, earlier);
                var reader = new StoredManagedEpochIndexes(f.bytes, LIMITS, cache);
                cachedNumbered = reader.exact(reader.open(roots::get, 0, 0), parent.id(), numberedEpoch).receipt();
            }
            f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
            var original = state(f).managedEpochReceipts();
            assertEquals(numberedEpoch, original.latestEpoch(parent.id()));
            String representation = original.storedState().documents().get(parent.id()).storedState().currentRepresentation();
            assertNotEquals(numbered, representation);
            var storage = storage(f.bytes); var retained = storage.retainPartition(original); var descriptors = roots(storage, retained);
            var cold = new StoredManagedEpochIndexes(f.bytes.copy(), LIMITS, cache);
            var opened = cold.open(descriptors::get, original.storedState().comparisons(), original.storedState().copiedNodes());
            assertEquals(representation, opened.storedState().documents().get(parent.id()).storedState().currentRepresentation());
            assertEquals(numbered, cold.exact(opened, parent.id(), numberedEpoch).receipt().afterBlueId());
            if (cache != null) assertSame(cachedNumbered, cold.exact(opened, parent.id(), numberedEpoch).receipt());
        }
    }

    @Test void missingOrSwappedSelectedIndexesFailClosed() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var storage = storage(f.bytes); var retained = storage.retainPartition(state(f).managedEpochReceipts());
            var s = retained.storedState();
            // when
            var withoutIdentity = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(s.documents(),
                    s.identities().remove(retained.exact(source.id(), 0).receipt().receiptIdentity()).map(), s.comparisons(), s.copiedNodes()));
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(withoutIdentity, source.id(), 0));
            var wrongOwner = DocumentId.of("wrong-owner");
            var swapped = ManagedEpochReceiptStore.restoreStored(new ManagedEpochReceiptStore.StoredState(
                    s.documents().put(wrongOwner, s.documents().get(source.id())).map(), s.identities(), s.comparisons(), s.copiedNodes()));
            assertThrows(CoordinationObjectStorageException.class, () -> storage.exact(swapped, wrongOwner, 0));
            var descriptors = roots(storage, retained); var missingBytes = f.bytes.copy(); missingBytes.records.clear();
            assertThrows(CoordinationObjectStorageException.class, () -> storage(missingBytes).open(descriptors::get, 0, 0));
        }
    }

    private static StoredManagedEpochIndexes storage(DocumentSessionStorageTest.Bytes bytes) { return new StoredManagedEpochIndexes(bytes, LIMITS); }
    private static RootedStorageCache cache() { return new RootedStorageCache(1024L * 1024 * 1024, 4096, 512L * 1024 * 1024); }
    @SuppressWarnings("unchecked")
    private static PersistentMapCodec<ManagedEpochReceiptStore.StoredReceipt> receiptCodec(StoredManagedEpochIndexes storage) throws Exception {
        var field = StoredManagedEpochIndexes.class.getDeclaredField("receipts"); field.setAccessible(true);
        return (PersistentMapCodec<ManagedEpochReceiptStore.StoredReceipt>) field.get(storage);
    }
    private static EntryHandle appendCycle(DocumentSessionStorageTest.Fixture f, DocumentHandle target,
            String operation, long timestamp, String request) {
        String yaml = """
                type: Coordination/Timeline Entry
                timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/cycle}
                timestamp: %d
                actor: {type: MyOS/Principal Actor, accountId: alice}
                message:
                  type: Coordination/Operation Request
                  document: {blueId: %s}
                  requireExactDocumentVersion: false
                  operation: %s
                  channel: ownerChannel
                  request:
                %s
                """.formatted(timestamp, target.snapshot().blueId(), operation, request.indent(4));
        String previous = f.previous.get("rcp2/cycle");
        if (previous != null) yaml += "\nprevEntry: {blueId: " + previous + "}\n";
        var entry = f.blue.events().from(f.timelines.get("rcp2/cycle")).exact(f.blue.values().yaml(yaml)).submit();
        f.previous.put("rcp2/cycle", entry.blueId()); return entry;
    }
    private static DocumentId epochNodeOwner(byte[] bytes) {
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0x504d4e31) return null;
            if (!new String(input.readNBytes(input.readInt()), java.nio.charset.StandardCharsets.UTF_8)
                    .equals("blue-coordination/index/managed-receipts/epochs/1")) return null;
            for (int i = 0; i < 3; i++) input.skipNBytes(input.readInt()); // key/value codec bindings and key frame
            return SessionStorageWire.decode(input.readNBytes(input.readInt()), MAX, r -> {
                var receipt = new SessionRecordCodec(MAX, 128).receipt(r);
                SessionRecordCodec.optional(r, x -> x.bytes(MAX)); // retained private transition frame
                return receipt.documentId();
            });
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    private static Map<StoredManagedEpochIndexes.Root, byte[]> roots(StoredManagedEpochIndexes storage, ManagedEpochReceiptStore store) {
        var roots = new EnumMap<StoredManagedEpochIndexes.Root, byte[]>(StoredManagedEpochIndexes.Root.class);
        for (var root : StoredManagedEpochIndexes.Root.values()) roots.put(root, storage.root(store, root)); return roots;
    }
    private static Field stateField() throws Exception { var field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true); return field; }
    private static InMemoryDocumentStore.StoreState state(DocumentSessionStorageTest.Fixture f) throws Exception {
        return (InMemoryDocumentStore.StoreState) stateField().get(f.engine.documents());
    }
}
