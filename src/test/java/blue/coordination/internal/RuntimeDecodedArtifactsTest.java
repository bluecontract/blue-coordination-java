package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import blue.language.processor.closure.ManagedRepresentationTransition;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** Real complete processor artifacts, reused across disposable physical owners only. */
final class RuntimeDecodedArtifactsTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS =
            new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 8);

    @Test void viewReuseSurvivesOwnerCloseButDoesNotReuseMutableSessionOrBypassSelectedBytes() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, cache);
            String address = storage.retain(f.engine.documents().require(handle.id()));
            DocumentSession first;
            String viewAddress;
            try (var owner = storage.openScope()) {
                first = owner.open(handle.id(), address);
                viewAddress = owner.addressOf(first.rootedView());
                first.markCatchingUp();
            }
            try (var owner = storage.openScope()) {
                var next = owner.open(handle.id(), address);
                assertNotSame(first, next);
                assertSame(first.currentRevision().after(), next.currentRevision().after(),
                        "Complete inline exact frames may be shared, never mutable sessions");
                assertSame(first.rootedView(), next.rootedView(), "Complete immutable views are shared");
                assertEquals(SessionStatus.READY, next.status());
                assertEquals(address, owner.retain(next));
                next.rootedView().snapshot().managedDocuments().get(0).document().name("caller copy");
                assertEquals(address, owner.retain(next), "Public document access cannot mutate the cached frame");
            }
            // Even a warm immutable cache does not lend a new codec profile its old validation.
            var otherDepth = new DocumentSessionStorage(f.bytes,
                    new DocumentSessionStorage.Limits(MAX, 255, LIMITS.maximumScopeBytes()), cache);
            try (var owner = otherDepth.openScope()) {
                assertNotSame(first.rootedView(), owner.open(handle.id(), address).rootedView());
            }
            byte[] viewBytes = f.bytes.records.remove(viewAddress);
            try (var owner = storage.openScope()) {
                assertThrows(CoordinationObjectStorageException.class, () -> owner.open(handle.id(), address));
            }
            f.bytes.records.put(viewAddress, viewBytes.clone()); f.bytes.records.get(viewAddress)[0] ^= 1;
            try (var owner = storage.openScope()) {
                assertThrows(CoordinationObjectStorageException.class, () -> owner.open(handle.id(), address));
            }
            f.bytes.records.put(viewAddress, viewBytes);
            try (var owner = storage.openScope()) {
                assertSame(first.rootedView(), owner.open(handle.id(), address).rootedView());
            }
        }
    }

    @Test void resultReuseReregistersOriginalMemberProvenanceInEveryOwner() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            var original = f.engine.documents().require(handle.id()).rootedView().result();
            assertFalse(original.publicEvents().isEmpty()); assertFalse(original.checkpointWrites().isEmpty());
            String address; byte[] event, checkpoint;
            try (var writer = new StoredResultRows(f.bytes, LIMITS)) {
                address = writer.retain(original);
                event = writer.outboxCodec().encode(original.publicEvents().get(0));
                checkpoint = writer.checkpointCodec().encode(original.checkpointWrites().get(0));
            }
            ClosureProcessResult first;
            var retired = new StoredResultRows(f.bytes, LIMITS, cache);
            first = retired.openResult(address); retired.close();
            try (var next = new StoredResultRows(f.bytes, LIMITS, cache)) {
                assertSame(first, next.openResult(address));
                assertSame(first.publicEvents().get(0), next.outboxCodec().decode(event));
                assertArrayEquals(event, next.outboxCodec().encode(first.publicEvents().get(0)));
                assertArrayEquals(checkpoint, next.checkpointCodec().encode(first.checkpointWrites().get(0)));
                var codec = new ClosureProcessResultStorageCodec(MAX, 256);
                var unregistered = codec.decode(codec.encode(first));
                assertThrows(CoordinationObjectStorageException.class,
                        () -> next.outboxCodec().encode(unregistered.publicEvents().get(0)));
            }
            assertThrows(CoordinationObjectStorageException.class, () -> retired.openResult(address));
            byte[] encoded = f.bytes.records.remove(address);
            try (var next = new StoredResultRows(f.bytes, LIMITS, cache)) {
                assertThrows(CoordinationObjectStorageException.class, () -> next.openResult(address));
            }
            f.bytes.records.put(address, encoded);
        }
    }

    @Test void completeResultFamilySharesCompatibleByteCapsButKeepsDepthAndRequestedBounds() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var root = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(root, f.append(root, "rcp2/source", "tick"));
            var original = f.engine.documents().require(root.id()).rootedView().result();
            byte[] bytes = new ClosureProcessResultStorageCodec(MAX, 256).encode(original);
            assertFalse(original.publicEvents().isEmpty()); assertFalse(original.checkpointWrites().isEmpty());
            int larger = 40 * 1024 * 1024;
            for (int[] caps : new int[][] {{MAX, larger}, {larger, MAX}, {bytes.length, larger}, {larger, bytes.length}}) {
                try (var cache = cache()) {
                    AtomicInteger fullDecodes = new AtomicInteger(), fullEncodes = new AtomicInteger();
                    var issuer = new StoredClosureResultCodec(caps[0], 256, cache, fullDecodes::incrementAndGet, null);
                    var requester = new StoredClosureResultCodec(caps[1], 256, cache,
                            fullDecodes::incrementAndGet, fullEncodes::incrementAndGet);
                    var restored = issuer.decode(bytes);
                    assertSame(restored, requester.decode(bytes.clone()));
                    assertArrayEquals(bytes, requester.encode(restored));
                    assertEquals(1, fullDecodes.get()); assertEquals(0, fullEncodes.get());
                    assertEquals(1, cache.statistics().retainedEntries(), "One frame, not one entry per byte-cap profile");
                    assertEquals(original.totalGas(), restored.totalGas());
                    assertEquals(original.gasTraceIdentity(), restored.gasTraceIdentity());
                    assertEquals(original.publicEventsIdentity(), restored.publicEventsIdentity());
                    var below = new StoredClosureResultCodec(bytes.length - 1, 256, cache,
                            fullDecodes::incrementAndGet, fullEncodes::incrementAndGet);
                    assertThrows(IllegalArgumentException.class, () -> below.decode(bytes));
                    assertThrows(IllegalArgumentException.class, () -> below.encode(restored));
                    assertEquals(1, fullDecodes.get()); assertEquals(0, fullEncodes.get());
                    assertEquals(1, cache.statistics().retainedEntries());
                    var anotherDepth = new StoredClosureResultCodec(MAX, 255, cache, fullDecodes::incrementAndGet, null);
                    var independent = anotherDepth.decode(bytes);
                    assertNotSame(restored, independent, "Different depth profiles remain separate cache families");
                    assertEquals(2, fullDecodes.get());
                    assertArrayEquals(bytes, anotherDepth.encode(independent));
                    assertEquals(2, cache.statistics().retainedEntries());
                }
            }
        }
    }

    @Test void completeResultReuseCrossesViewsCoreReceiptsPublicationIndexesAndOriginalRows() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var root = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var entry = f.append(root, "rcp2/source", "tick"); f.process(root, entry);
            var session = f.engine.documents().require(root.id()); var result = session.rootedView().result();
            var receipt = f.engine.documents().publicationSnapshot().closurePublicationReceipts().values().stream()
                    .filter(value -> value.attempt().processResult().invocationIdentity().equals(result.invocationIdentity()))
                    .findFirst().orElseThrow();
            String sessionAddress, resultAddress; byte[] closureRoot;
            try (var writer = new Binding(f.bytes, null, null)) {
                sessionAddress = writer.sessions.retain(session); resultAddress = writer.results.retain(result);
                closureRoot = writer.indexes.retainClosures(PersistentOrderedMap.<String, ContractsClosurePublicationReceipt>
                        empty(EmbeddingBinding.TEXT_ORDER).put(receipt.publicationIdentity(), receipt).map()).storedRootDescriptor();
            }
            var core = new CoreReceiptStorageCodec(MAX, 256, cache);
            byte[] attemptBytes = SessionStorageWire.encode(MAX, out -> core.attempt(out, receipt.attempt()));
            var selected = SessionStorageWire.decode(attemptBytes, MAX, core::attempt).processResult();
            for (int i = 0; i < 2; i++) try (var owner = new Binding(f.bytes, cache, cache)) {
                var view = owner.scope.open(root.id(), sessionAddress).rootedView();
                var publication = owner.indexes.openClosures(closureRoot).get(receipt.publicationIdentity());
                assertSame(selected, view.result(), "Distinct complete view frame shares the same verified result");
                assertSame(selected, publication.attempt().processResult(), "Publication factory receives the same host cache");
                assertSame(selected, owner.results.openResult(resultAddress));
                assertEquals(sessionAddress, owner.scope.retain(owner.scope.open(root.id(), sessionAddress)));
                assertArrayEquals(attemptBytes, SessionStorageWire.encode(MAX, out -> core.attempt(out, publication.attempt())));
            }
            var raw = new PublicationReceiptStorageCodec(MAX, 256);
            byte[] publicationBytes = raw.encodePublication(receipt, f.storage::retainView);
            // Change only the enclosing publication key while leaving the warm result frame intact.
            byte[] foreign = publicationBytes.clone();
            int keyStart = 4 + "blue-coordination/publication-receipt-storage/1".length() * 2 + 4;
            foreign[keyStart + receipt.publicationIdentity().length() * 2 - 1] ^= 1;
            try (var owner = new Binding(f.bytes, cache, cache)) {
                var cached = new PublicationReceiptStorageCodec(MAX, 256, cache);
                assertThrows(CoordinationObjectStorageException.class, () -> cached.decodePublication(foreign, owner.scope));
                assertSame(selected, cached.decodePublication(publicationBytes, owner.scope).attempt().processResult());
            }
        }
    }

    @Test void receiptReuseRebindsAllHistoryViewsAndStillChecksSelectedPublicationMembership() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            var entry = f.append(source, "rcp2/source", "tick");
            var drain = f.engine.processRootInput(parent.id(), f.engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            var receipt = f.engine.documents().closurePublicationReceipt(
                    drain.contractsAttemptsFor(entry.blueId()).get(0).publicationIdentity()).orElseThrow();
            String key = receipt.publicationIdentity(); byte[] root; String sourceAddress, sourceViewAddress;
            try (var writer = new Binding(f.bytes, null, null)) {
                sourceAddress = writer.sessions.retain(f.engine.documents().require(source.id()));
                sourceViewAddress = writer.sessions.viewAddress(f.engine.documents().require(source.id()).rootedView());
                root = writer.indexes.retainClosures(PersistentOrderedMap.<String, ContractsClosurePublicationReceipt>
                        empty(EmbeddingBinding.TEXT_ORDER).put(key, receipt).map()).storedRootDescriptor();
            }
            ContractsClosurePublicationReceipt first;
            try (var owner = new Binding(f.bytes, cache, cache)) {
                first = owner.indexes.openClosures(root).get(key);
                assertSame(owner.scope.open(source.id(), sourceAddress).rootedView(), sourceView(first, source.id()));
            }
            try (var owner = new Binding(f.bytes, cache, cache)) {
                var cached = owner.indexes.openClosures(root).get(key);
                assertSame(first, cached, "Retired owner was not retained; its immutable receipt frame was");
                var selectedSource = owner.scope.open(source.id(), sourceAddress);
                assertSame(selectedSource.rootedView(), sourceView(cached, source.id()));
                assertEquals(sourceViewAddress, owner.scope.addressOf(sourceView(cached, source.id())));
                assertEquals(sourceAddress, owner.scope.retain(selectedSource));
                var members = PersistentOrderedMap.<String, Boolean>empty(EmbeddingBinding.TEXT_ORDER).put(key, true).map();
                var admissions = PersistentOrderedMap.<String, ContractsClosureAdmissionReceipt>empty(EmbeddingBinding.TEXT_ORDER);
                assertSame(cached, StoredPublicationIndexes.checkedClosure(key, cached, members, admissions));
                assertThrows(CoordinationObjectStorageException.class, () -> StoredPublicationIndexes.checkedClosure(
                        key, cached, PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER), admissions));
                assertThrows(CoordinationObjectStorageException.class,
                        () -> StoredPublicationIndexes.checkedClosure("wrong", cached, members, admissions));
            }
            // A separately restored local identity must win: fall back to whole-frame decode,
            // preserving the already-open session's alias rather than silently replacing it.
            try (var owner = new Binding(f.bytes, null, cache)) {
                var selectedSource = owner.scope.open(source.id(), sourceAddress);
                assertNotSame(sourceView(first, source.id()), selectedSource.rootedView());
                var restored = owner.indexes.openClosures(root).get(key);
                assertNotSame(first, restored);
                assertSame(selectedSource.rootedView(), sourceView(restored, source.id()));
            }
            byte[] bytes = f.bytes.records.get(sourceViewAddress).clone();
            f.bytes.records.get(sourceViewAddress)[0] ^= 1;
            try (var owner = new Binding(f.bytes, cache, cache)) {
                assertThrows(CoordinationObjectStorageException.class, () -> owner.indexes.openClosures(root).get(key));
            }
            f.bytes.records.put(sourceViewAddress, bytes);
        }
    }

    @Test void viewManifestPreflightsWholeScopeBudgetBeforeBindingAnyReferences() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            var earlier = f.engine.documents().require(handle.id()).rootedView();
            String earlierAddress = f.storage.retainView(earlier);
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            String laterAddress = f.storage.retainView(f.engine.documents().require(handle.id()).rootedView());
            DocumentSessionStorage.WithViews<RootedDocumentView> packet;
            try (var origin = f.storage.openScope()) {
                packet = origin.captureViews(() -> { origin.view(earlierAddress); return origin.view(laterAddress); });
            }
            int largest = Math.max(f.bytes.records.get(earlierAddress).length, f.bytes.records.get(laterAddress).length);
            var tight = new DocumentSessionStorage(f.bytes, new DocumentSessionStorage.Limits(largest, 256, largest));
            try (var owner = tight.openScope()) {
                assertTrue(packet.views().encodedBytes() > largest);
                assertThrows(CoordinationObjectStorageException.class, () -> owner.acceptViews(packet.views()));
                assertThrows(CoordinationObjectStorageException.class, () -> owner.addressOf(packet.value()));
                assertNotNull(owner.view(laterAddress), "Failed batch binding did not consume capacity or partially bind views");
            }
        }
    }

    @Test void canonicalPublicationEncodingSurvivesOwnerMemoMissWithoutSkippingPhysicalReads() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture(); var cache = cache()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            var entry = f.append(source, "rcp2/source", "tick");
            var drain = f.engine.processRootInput(parent.id(), f.engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            var original = f.engine.documents().closurePublicationReceipt(
                    drain.contractsAttemptsFor(entry.blueId()).get(0).publicationIdentity()).orElseThrow();
            String key = original.publicationIdentity(), sourceViewAddress; byte[] root, payload;
            try (var writer = new Binding(f.bytes, null, null)) {
                writer.sessions.retain(f.engine.documents().require(source.id()));
                sourceViewAddress = writer.sessions.viewAddress(f.engine.documents().require(source.id()).rootedView());
                root = writer.indexes.retainClosures(PersistentOrderedMap.<String, ContractsClosurePublicationReceipt>
                        empty(EmbeddingBinding.TEXT_ORDER).put(key, original).map()).storedRootDescriptor();
                payload = new PublicationReceiptStorageCodec(MAX, 256).encodePublication(original, writer.sessions::viewAddress);
            }
            String address = PersistentMapStorage.digest(payload);
            var encodes = new AtomicInteger(); ContractsClosurePublicationReceipt first;
            // Zero owner capacity deterministically exercises the same miss as an oversized (>8 MiB) receipt.
            try (var owner = new Binding(f.bytes, cache, cache, new StoredPublicationReceiptReuse(0, 0), encodes::incrementAndGet)) {
                var rows = owner.indexes.openClosures(root); int writes = f.bytes.writes;
                byte[] coldPayload = f.bytes.records.get(address).clone(); f.bytes.records.get(address)[0] ^= 1;
                assertThrows(CoordinationObjectStorageException.class, () -> rows.get(key), "Cold physical corruption must still fail");
                assertEquals(0, encodes.get()); f.bytes.records.put(address, coldPayload);
                first = rows.get(key); assertEquals(0, encodes.get(), "Cold issuance reuses the decoder's canonical check, with no extra encode");
                assertEquals(0, owner.indexes.closureReuse().retainedEntries());
                int reads = f.bytes.reads;
                assertSame(first, rows.get(key)); assertEquals(0, encodes.get(), "A warm process certificate avoids the enclosing encoder");
                assertTrue(f.bytes.reads > reads, "Warm receipt reads still select physical payload and dependency bytes");
                assertEquals(writes, f.bytes.writes);
                byte[] retained = f.bytes.records.remove(address);
                assertNotNull(retained); assertThrows(CoordinationObjectStorageException.class, () -> rows.get(key));
                f.bytes.records.put(address, retained.clone()); f.bytes.records.get(address)[0] ^= 1;
                assertThrows(CoordinationObjectStorageException.class, () -> rows.get(key));
                f.bytes.records.put(address, retained);
                byte[] view = f.bytes.records.get(sourceViewAddress).clone(); f.bytes.records.get(sourceViewAddress)[0] ^= 1;
                assertThrows(CoordinationObjectStorageException.class, () -> rows.get(key), "Canonical bytes do not skip acceptViews");
                f.bytes.records.put(sourceViewAddress, view);
                assertSame(first, rows.get(key)); assertEquals(0, encodes.get());
            }
            try (var next = new Binding(f.bytes, cache, cache, new StoredPublicationReceiptReuse(0, 0), encodes::incrementAndGet)) {
                assertSame(first, next.indexes.openClosures(root).get(key));
                assertEquals(0, encodes.get(), "A new owner must adopt dependencies but need not re-encode the verified frame");
                assertNotNull(next.scope.addressOf(sourceView(first, source.id())));
                cache.clear();
                assertNotSame(first, next.indexes.openClosures(root).get(key));
                assertEquals(0, encodes.get(), "Eviction requires the decoder's canonical check again, never an extra encode");
            }
        }
    }

    @Test void completeRepresentationProofSurvivesZeroOwnerBudgetButNotProcessEvictionOrMissingMembership() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario(); var views = cache();
                var receipts = new RootedStorageCache(1024L * 1024 * 1024, 1, 512L * 1024 * 1024)) {
            var bytes = new DocumentSessionStorageTest.Bytes(); var state = f.engine.documents().storedState();
            var expected = f.chain().transitions().get(0); var original = f.publication(expected);
            assertTrue(expected.rootedCheckpointReferenceProofIdentity().isPresent());
            String key = original.publicationIdentity(), payloadAddress; byte[] root;
            try (var writer = new Binding(bytes, null, null)) {
                root = writer.indexes.retainClosures(state.closurePublicationReceiptIndex()).storedRootDescriptor();
                payloadAddress = PersistentMapStorage.digest(new PublicationReceiptStorageCodec(MAX, 256)
                        .encodePublication(original, writer.sessions::viewAddress));
            }
            ContractsClosurePublicationReceipt first; ManagedRepresentationTransition proved;
            // The same deterministic miss as a receipt larger than the normal 8 MiB owner budget.
            var retiredReuse = new StoredPublicationReceiptReuse(0, 0);
            try (var owner = new Binding(bytes, views, receipts, retiredReuse, null)) {
                var documents = selectedDocuments(state, owner, root, state.publicationReceiptIndex());
                first = documents.closurePublicationReceipt(key).orElseThrow();
                proved = proveRetained(documents, key, expected);
                assertEquals(expected.positionIdentity(), proved.positionIdentity());
                assertSame(proved, prove(documents, first, expected),
                        "The retained value is issued only after constructor AND exact rooted-authority checks finish");
                assertEquals(0, retiredReuse.retainedEntries());
                assertNotNull(retiredReuse.processProofs(first));
                int reads = bytes.reads;
                assertSame(proved, prove(documents, first, expected));
                assertTrue(bytes.reads > reads, "A proof hit still selects the physical receipt and adopts dependencies");
                byte[] physical = bytes.records.remove(payloadAddress);
                assertNotNull(physical);
                assertThrows(CoordinationObjectStorageException.class, () -> prove(documents, first, expected));
                assertThrows(CoordinationObjectStorageException.class, () -> proveRetained(documents, key, expected));
                bytes.records.put(payloadAddress, physical);
                assertSame(proved, prove(documents, first, expected));
            }
            assertNull(retiredReuse.processProofs(first), "A closed owner cannot lend the process certificate");
            try (var owner = new Binding(bytes, views, receipts, new StoredPublicationReceiptReuse(0, 0), null)) {
                var documents = selectedDocuments(state, owner, root, state.publicationReceiptIndex());
                var selected = documents.closurePublicationReceipt(key).orElseThrow();
                assertSame(first, selected);
                assertSame(proved, proveRetained(documents, key, expected), "Complete pure proof survives owner retirement");
                var missing = selectedDocuments(state, owner, root, state.publicationReceiptIndex().remove(key).map());
                assertThrows(CoordinationObjectStorageException.class, () -> prove(missing, selected, expected),
                        "A process certificate is not current publication membership");
                assertThrows(CoordinationObjectStorageException.class, () -> proveRetained(missing, key, expected));
                long evictions = receipts.statistics().evictions();
                receipts.decode("test/force-publication-eviction", new byte[] {1}, Object::new);
                assertTrue(receipts.statistics().evictions() > evictions);
                assertNull(owner.indexes.closureReuse().processProofs(selected));
                var afterEviction = documents.closurePublicationReceipt(key).orElseThrow();
                var coldProof = proveRetained(documents, key, expected);
                assertNotSame(proved, coldProof); assertEquals(proved.positionIdentity(), coldProof.positionIdentity());
                receipts.clear();
                assertNull(owner.indexes.closureReuse().processProofs(afterEviction));
                var afterClear = documents.closurePublicationReceipt(key).orElseThrow();
                assertNotSame(afterEviction, afterClear);
                var clearedProof = proveRetained(documents, key, expected);
                assertNotSame(coldProof, clearedProof); assertEquals(coldProof.positionIdentity(), clearedProof.positionIdentity());
            }
        }
    }

    @Test void retainedHistorySelectsEachPhysicalRowOnceUnlessAColdMemoInsertionNeedsReauthentication() throws Exception {
        try (var f = new ManagedRepresentationVerificationMemoTest.Scenario(); var views = cache(); var receipts = cache()) {
            var bytes = new DocumentSessionStorageTest.Bytes();
            var state = f.engine.documents().storedState();
            var expected = f.chain();
            byte[] root;
            try (var writer = new Binding(bytes, null, null)) {
                root = writer.indexes.retainClosures(state.closurePublicationReceiptIndex()).storedRootDescriptor();
            }
            for (boolean processMemo : java.util.List.of(false, true)) {
                var ownerReuse = new StoredPublicationReceiptReuse(0, 0);
                try (var owner = new Binding(bytes, views, processMemo ? receipts : null, ownerReuse, null)) {
                    var selections = new AtomicInteger();
                    var documents = selectedDocuments(state, owner, root, state.publicationReceiptIndex(), selections::incrementAndGet);
                    var history = new ManagedRepresentationHistory(documents);
                    var first = history.at(f.parent.id(), 0);
                    assertEquals(processMemo ? 4 : 2, selections.get(),
                            "Two rows: one physical selection each, plus cold rechecks only when a memo can retain proof");
                    selections.set(0);
                    var repeated = history.at(f.parent.id(), 0);
                    assertEquals(2, selections.get(), "Neither a warm memo nor disabled retention skips current selections");
                    assertEquals(0, ownerReuse.retainedEntries());
                    for (int i = 0; i < first.transitions().size(); i++) {
                        assertEquals(expected.transitions().get(i).positionIdentity(), first.transitions().get(i).positionIdentity());
                        assertEquals(first.transitions().get(i).positionIdentity(), repeated.transitions().get(i).positionIdentity());
                        if (processMemo) assertSame(first.transitions().get(i), repeated.transitions().get(i));
                        else assertNotSame(first.transitions().get(i), repeated.transitions().get(i));
                    }
                    assertEquals(expected.targetPositionIdentity(), repeated.targetPositionIdentity());
                    assertEquals(expected.nextRevisionReceiptIdentity(), repeated.nextRevisionReceiptIdentity());
                }
            }
        }
    }

    private static ManagedRepresentationTransition proveRetained(InMemoryDocumentStore documents, String publication,
            ManagedRepresentationTransition expected) {
        return documents.proveRetainedRepresentation(publication, expected.documentId(), expected.epoch(), expected.anchorReceiptIdentity(),
                expected.predecessorPositionIdentity(), expected.transitionReceipt().transitionReceiptIdentity());
    }

    private static ManagedRepresentationTransition prove(InMemoryDocumentStore documents,
            ContractsClosurePublicationReceipt receipt, ManagedRepresentationTransition expected) {
        return documents.proveRepresentation(receipt, expected.documentId(), expected.epoch(), expected.anchorReceiptIdentity(),
                expected.predecessorPositionIdentity(), expected.transitionReceipt().transitionReceiptIdentity());
    }

    private static InMemoryDocumentStore selectedDocuments(InMemoryDocumentStore.StoreState s, Binding owner,
            byte[] root, PersistentOrderedMap<String, Boolean> membership) {
        return selectedDocuments(s, owner, root, membership, () -> { });
    }

    private static InMemoryDocumentStore selectedDocuments(InMemoryDocumentStore.StoreState s, Binding owner,
            byte[] root, PersistentOrderedMap<String, Boolean> membership, Runnable selectionObserver) {
        var closures = owner.indexes.openClosures(root).projectValues((key, row) -> {
            selectionObserver.run();
            StoredPublicationIndexes.checkedClosure(key, row, membership, s.admissionReceiptIndex());
            InMemoryDocumentStore.StoreState.requireRetainedClosureReceipt(row, s.sessions(), "Selected test receipt");
            return row;
        });
        var selected = InMemoryDocumentStore.StoreState.trustedTransition(s.sessionIndex(), s.lineageIndex(), s.occurrenceInventory(),
                s.occurrenceInventoryGeneration(), s.componentIndex(), s.componentIndexGeneration(), s.graphGenerations(),
                s.componentStateInventory(), s.closureSubscriptions(), s.outboxLog(), s.checkpointEvidenceLog(),
                membership, s.admissionReceiptIndex(), closures.open(), s.rootedProviderFrontiers(),
                s.managedEpochReceipts(), s.catchUpPlans());
        var documents = new InMemoryDocumentStore(new EngineMetrics(), selected);
        documents.bindStoredPublicationReuse(owner.indexes.closureReuse());
        return documents;
    }

    private static RootedDocumentView sourceView(ContractsClosurePublicationReceipt receipt, blue.coordination.api.DocumentId source) {
        return receipt.rootedTerminalEvidence().storedState().rooted().histories().values().stream()
                .map(history -> history.admissionSources().storedViews().get(source)).filter(Objects::nonNull).findFirst().orElseThrow();
    }

    private static RootedStorageCache cache() { return new RootedStorageCache(1024L * 1024 * 1024, 4096, 512L * 1024 * 1024); }

    private static final class Binding implements AutoCloseable {
        final DocumentSessionStorage sessions;
        final DocumentSessionStorage.OpenScope scope;
        final StoredResultRows results;
        final StoredPublicationIndexes indexes;
        Binding(DocumentSessionStorageTest.Bytes bytes, RootedStorageCache viewCache, RootedStorageCache receiptCache) {
            this(bytes, viewCache, receiptCache, new StoredPublicationReceiptReuse(), null);
        }
        Binding(DocumentSessionStorageTest.Bytes bytes, RootedStorageCache viewCache, RootedStorageCache receiptCache,
                StoredPublicationReceiptReuse ownerReuse, Runnable enclosingEncodeObserver) {
            sessions = new DocumentSessionStorage(bytes, LIMITS, viewCache); scope = sessions.openScope();
            results = new StoredResultRows(bytes, LIMITS, receiptCache);
            indexes = new StoredPublicationIndexes(bytes, MAPS, sessions, scope, results, 256, receiptCache, ownerReuse, enclosingEncodeObserver);
        }
        public void close() { indexes.close(); scope.close(); results.close(); }
    }
}
