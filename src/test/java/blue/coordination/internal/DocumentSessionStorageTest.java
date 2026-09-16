package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Per-session cold bytes; the other engine stores intentionally remain unchanged in next-action controls. */
final class DocumentSessionStorageTest {
    private static final DocumentSessionStorage.Limits LIMITS = new DocumentSessionStorage.Limits(32 * 1024 * 1024, 256, 256L * 1024 * 1024);

    @Test void selectedViewIsFreshlyAuthenticatedWhileEveryCurrentSessionIsAcknowledged() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String original = f.storage.retain(f.engine.documents().require(handle.id()));
            var observed = new ObservedBytes(f.bytes);
            var storage = new DocumentSessionStorage(observed, LIMITS);
            try (var scope = storage.openScope()) {
                var session = scope.open(handle.id(), original);
                String view = scope.addressOf(session.rootedView());
                for (var status : List.of(SessionStatus.READY, SessionStatus.CATCHING_UP, SessionStatus.BLOCKED)) {
                    if (status == SessionStatus.CATCHING_UP) session.markCatchingUp();
                    if (status == SessionStatus.BLOCKED) session.markBlocked();
                    observed.clear();
                    String address = scope.retain(session);
                    assertEquals(List.of(revisionAddress(session.currentRevision()), view), observed.readKeys,
                            "Every retention rechecks the physical dependency, including repeated retention in one owner");
                    assertEquals(List.of(address), observed.writeKeys,
                            "Even a byte-identical mutable session is acknowledged; its known view is not rewritten");
                    assertEquals(f.storage.retain(session), address, "Ordinary complete serialization is the exact-byte oracle");
                    try (var cold = storage.openScope()) {
                        var restored = cold.open(handle.id(), address);
                        assertEquals(status, restored.status());
                        assertEquals(session.currentRepresentation().blueId(), restored.currentRepresentation().blueId());
                    }
                }
            }
        }
    }

    @Test void missingCorruptAndUnavailableKnownViewsFailBeforeSessionPrewrites() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String original = f.storage.retain(f.engine.documents().require(handle.id()));
            var observed = new ObservedBytes(f.bytes);
            var storage = new DocumentSessionStorage(observed, LIMITS);
            try (var scope = storage.openScope()) {
                var session = scope.open(handle.id(), original);
                String view = scope.addressOf(session.rootedView());
                byte[] valid = f.bytes.records.get(view).clone();
                session.markCatchingUp();
                for (String fault : List.of("missing", "corrupt", "unavailable")) {
                    observed.clear();
                    if (fault.equals("missing")) f.bytes.records.remove(view);
                    if (fault.equals("corrupt")) f.bytes.records.get(view)[0] ^= 1;
                    if (fault.equals("unavailable")) f.bytes.failRead = true;
                    assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session), fault);
                    assertEquals(fault.equals("unavailable") ? List.of(revisionAddress(session.currentRevision()))
                            : List.of(revisionAddress(session.currentRevision()), view), observed.readKeys, fault);
                    assertTrue(observed.writeKeys.isEmpty(), fault + " must precede every prewrite");
                    f.bytes.failRead = false; f.bytes.records.put(view, valid.clone());
                }
                String changed = scope.retain(session);
                try (var cold = storage.openScope()) {
                    assertEquals(SessionStatus.CATCHING_UP, cold.open(handle.id(), changed).status());
                    assertEquals(SessionStatus.READY, cold.open(handle.id(), original).status());
                }
            }
        }
    }

    @Test void authenticatedViewBytesStillCountTowardsRetentionScopeAndRecordBounds() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var session = f.engine.documents().require(handle.id());
            String original = f.storage.retain(session);
            String view = f.storage.viewAddress(session.rootedView());
            int viewBytes = f.bytes.records.get(view).length;
            int sessionBytes = f.bytes.records.get(original).length;
            String revision = revisionAddress(session.currentRevision());
            int revisionBytes = f.bytes.records.get(revision).length;
            int maximumRecord = Math.max(1024, Math.max(revisionBytes, Math.max(viewBytes, sessionBytes)));
            long total = (long) viewBytes + revisionBytes + sessionBytes;
            assertTrue(total - 1 >= maximumRecord, "The fixture must exercise the combined scope bound");
            var observed = new ObservedBytes(f.bytes);
            var bounded = new DocumentSessionStorage(observed,
                    new DocumentSessionStorage.Limits(maximumRecord, 256, total - 1));
            try (var scope = bounded.openScope()) {
                var restored = scope.open(handle.id(), original);
                observed.clear();
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(restored));
                assertEquals(List.of(revision, view), observed.readKeys);
                assertTrue(observed.writeKeys.isEmpty(), "Authenticated bytes count even though they require no PUT");
                byte[] valid = f.bytes.records.get(view);
                f.bytes.records.put(view, new byte[maximumRecord + 1]); observed.clear();
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(restored));
                assertTrue(observed.writeKeys.isEmpty(), "A selected read must still obey the physical record limit");
                f.bytes.records.put(view, valid);
            }
            var exact = new DocumentSessionStorage(observed,
                    new DocumentSessionStorage.Limits(maximumRecord, 256, total));
            try (var scope = exact.openScope()) {
                var restored = scope.open(handle.id(), original); observed.clear();
                assertEquals(original, scope.retain(restored));
                assertEquals(List.of(original), observed.writeKeys, "The inclusive exact scope boundary still succeeds");
            }
        }
    }

    @Test void failedFirstDependencyAcknowledgementNeverLendsEvidenceToRetryOrAnotherStage() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var session = f.engine.documents().require(handle.id());
            var bytes = new Bytes(); var observed = new ObservedBytes(bytes);
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(observed, LIMITS, encodes::incrementAndGet);
            String view = f.storage.viewAddress(session.rootedView());
            String revision = revisionAddress(session.currentRevision());
            try (var scope = storage.openScope()) {
                String address;
                try (var stage = scope.openRetentionStage()) {
                    bytes.badAck = true;
                    assertThrows(CoordinationObjectStorageException.class, () -> stage.retain(session));
                    assertEquals(List.of(revision), observed.writeKeys); assertEquals(1, encodes.get());
                    bytes.badAck = false; bytes.failAtWrite = bytes.writes + 1; observed.clear();
                    assertThrows(CoordinationObjectStorageException.class, () -> stage.retain(session));
                    assertEquals(List.of(revision), observed.writeKeys); assertEquals(2, encodes.get());
                    assertTrue(observed.readKeys.isEmpty(), "A failed retention never supplies stage-local evidence");
                    bytes.failAtWrite = -1; observed.clear();
                    address = stage.retain(session);
                    assertEquals(List.of(revision, view, address), observed.writeKeys); assertEquals(3, encodes.get());
                    observed.clear();
                    assertEquals(address, stage.retain(session));
                    assertEquals(List.of(revision, view), observed.readKeys); assertEquals(List.of(address), observed.writeKeys);
                    assertEquals(3, encodes.get(), "Only the successful earlier retention can supply exact dependency bytes");
                }
                try (var next = scope.openRetentionStage()) {
                    observed.clear();
                    assertEquals(address, next.retain(session));
                    assertEquals(List.of(revision, view, address), observed.writeKeys); assertTrue(observed.readKeys.isEmpty());
                    assertEquals(4, encodes.get(), "Presence evidence does not escape its explicit stage");
                }
                try (var cold = storage.openScope()) {
                    assertEquals(session.currentRepresentation().blueId(),
                            cold.open(handle.id(), address).currentRepresentation().blueId());
                }
            }
        }
    }

    @Test void realExactHistoryColdSessionAndNextRootActionEqualUninterruptedExecution() throws Exception {
        assertEquals(counterRun(false), counterRun(true));
    }

    @Test void originalEventFrozenEvidenceRestoresAfterTheProducingRuntimeCloses() throws Exception {
        var results = new blue.language.processor.closure.ClosureProcessResultStorageCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth());
        byte[] originalResult;
        int eventCount;
        DocumentId document;
        String address;
        Bytes retained;
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var session = f.engine.documents().require(source.id());
            document = source.id();
            eventCount = session.rootedView().result().publicEvents().size();
            assertTrue(eventCount > 0, "The actual processor must have emitted retained event evidence");
            originalResult = results.encode(session.rootedView().result());
            address = f.storage.retain(session);
            retained = f.bytes.copy();
        }
        // This component has no runtime/provider parameter to recover or re-admit an event.
        var storage = new DocumentSessionStorage(retained, LIMITS);
        try (var scope = storage.openScope()) {
            var restored = scope.open(document, address);
            assertEquals(eventCount, restored.rootedView().result().publicEvents().size());
            assertArrayEquals(originalResult, results.encode(restored.rootedView().result()),
                    "Complete Language result bytes include the original private event Frozen evidence");
            assertEquals(address, storage.retain(restored), "Every complete session/view record remains identical");
        }
    }

    private List<String> counterRun(boolean cold) throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.importFullHistory());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var before = f.engine.documents().require(source.id());
            assertEquals(2L, before.epoch());
            assertFalse(before.currentRevision().emittedEvents().isEmpty());
            assertTrue(before.currentRevision().managedEpochReceipt().isPresent());
            assertTrue(before.rootedView().result().totalGas() > 0);
            if (cold) f.coldSessions();
            f.process(source, f.append(source, "rcp2/source", "tick"));
            return f.completeEvidence(source.id());
        }
    }

    @Test void twoSameEpochCheckpointRepresentationsKeepOriginalWitnessAndNextAction() throws Exception {
        assertEquals(representationRun(false), representationRun(true));
    }

    private List<String> representationRun(boolean cold) throws Exception {
        try (var f = new Fixture()) {
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
            f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            String initial = parent.snapshot().blueId();
            f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
            f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
            var session = f.engine.documents().require(parent.id());
            assertEquals(0L, session.epoch()); assertEquals(1, session.revisions().size());
            assertEquals(2, session.representationTransitions().size());
            assertNotEquals(initial, session.currentRepresentation().blueId());
            assertEquals(0L, f.engine.documents().require(source.id()).epoch(), "Root-local processing never publishes the source");
            if (cold) f.coldSessions();
            f.process(parent, f.append(source, "rcp2/source", "emitUnmatched"));
            return f.completeEvidence(parent.id());
        }
    }

    @Test void staticAdmissionSourceAndItsSessionShareExactAddressAcrossEitherOpenOrder() throws Exception {
        assertAdmissionSourceColdOpen(ActivationPolicy.fromNow(), "FROM_NOW");
    }

    @Test void fullHistoryAdmissionKeepsTheExactLaterSourceEndpointAcrossColdOpen() throws Exception {
        assertAdmissionSourceColdOpen(ActivationPolicy.importFullHistory(), "FULL_HISTORY");
    }

    private void assertAdmissionSourceColdOpen(ActivationPolicy policy, String mode) throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick")); f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", policy);
            var originalParent = f.engine.documents().require(parent.id());
            assertTrue(originalParent.requireRootedHistory().admissionSources().storedViews().containsKey(source.id()));
            assertEquals(mode, ((Map<?, ?>) originalParent.requireRootedHistory().descriptor().get("admission")).get("mode"));
            if (mode.equals("FULL_HISTORY")) {
                var sources = originalParent.requireRootedHistory().admissionSources();
                assertTrue(sources.storedViews().get(source.id()).logicalBoundary().compareTo(sources.storedBoundary()) > 0,
                        "The replay beginning is not the imported source endpoint");
            }
            String sourceAddress = f.storage.retain(f.engine.documents().require(source.id()));
            String parentAddress = f.storage.retain(originalParent);
            for (boolean sourceFirst : List.of(true, false)) try (var scope = f.storage.openScope()) {
                int before = f.providerReads.get();
                DocumentSession sourceSession = sourceFirst ? scope.open(source.id(), sourceAddress) : null;
                DocumentSession parentSession = scope.open(parent.id(), parentAddress);
                assertEquals(originalParent.requireRootedHistory().descriptor(), parentSession.requireRootedHistory().descriptor());
                assertEquals(parentAddress, f.storage.retain(parentSession), "Exact history, policy and source proof bytes survive cold open");
                if (!sourceFirst) sourceSession = scope.open(source.id(), sourceAddress);
                assertEquals(before, f.providerReads.get(), "No external provider resolution on restore");
                var selected = parentSession.requireRootedHistory().admissionSources().storedViews().get(source.id());
                assertSame(sourceSession.rootedView(), selected);
                DocumentSession exactSource = sourceSession;
                assertDoesNotThrow(() -> exactSource.rootedPublicationPrefix(selected));
                assertNotSame(f.engine.documents().require(source.id()).rootedView(), selected);
                DocumentSession second = scope.open(source.id(), sourceAddress);
                assertNotSame(sourceSession, second); assertSame(sourceSession.rootedView(), second.rootedView());
                second.markBlocked(); assertNotEquals(second.status(), sourceSession.status());
            }
        }
    }

    @Test void catchingUpReadyLayoutAndAbsentPrepublicationFieldsAreNotRecomputed() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            DocumentSession current = f.engine.documents().require(handle.id());
            DocumentSession.StoredState state = current.storedState();
            var before = state.layout();
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            DocumentSession next = f.engine.documents().require(handle.id());
            // Directly retain a real detached publication image between commit and READY publication.
            DocumentSession staged = DocumentSession.restoreStored(change(next.storedState(), Map.of(
                    "readyLayout", before, "readyEpoch", 0L, "status", SessionStatus.CATCHING_UP,
                    "graphPublishedEpoch", 0L, "readyEmbeddedChildren", state.readyEmbeddedChildren())));
            try (var scope = f.storage.openScope()) {
                var restored = scope.open(handle.id(), f.storage.retain(staged));
                assertEquals(1L, restored.epoch()); assertEquals(0L, restored.readyEpoch());
                assertEquals(before.rootBlueId(), restored.readyRepresentation().blueId());
                assertNotEquals(restored.readyRepresentation().blueId(), restored.currentRepresentation().blueId());
                restored.markGraphPublished(); restored.markReady(restored.readyThrough());
                assertEquals(restored.currentRepresentation().blueId(), restored.readyRepresentation().blueId());
            }
            var nullable = new HashMap<String, Object>();
            nullable.put("rootedHistory", null); nullable.put("rootedView", null); nullable.put("rootedViewPositions", List.of());
            DocumentSession unpublished = DocumentSession.restoreStored(change(state, nullable));
            try (var scope = f.storage.openScope()) {
                var restored = scope.open(handle.id(), f.storage.retain(unpublished));
                assertNull(restored.rootedView()); assertThrows(IllegalStateException.class, restored::requireRootedHistory);
            }
        }
    }

    @Test void storedAdmissionSourcesRejectMissingEarlierForeignAndUnpublishedPositions() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var view = f.engine.documents().require(source.id()).rootedView();
            assertNotNull(view.logicalBoundary());
            assertThrows(IllegalArgumentException.class, () -> new RootedAdmissionSources(null, Map.of(source.id(), view)));
            var earlier = blue.language.processor.ExternalOrderKey.of(List.of(java.math.BigInteger.ZERO));
            assertThrows(IllegalArgumentException.class, () -> new RootedAdmissionSources(earlier, Map.of(source.id(), view)));
            assertThrows(IllegalArgumentException.class, () -> new RootedAdmissionSources(view.logicalBoundary(), Map.of(DocumentId.of("foreign"), view)));
            var unpublished = new RootedDocumentView(view.result(), view.subscriptions(), view.storedState().routes(), view.logicalBoundary());
            assertThrows(IllegalArgumentException.class, () -> new RootedAdmissionSources(view.logicalBoundary(), Map.of(source.id(), unpublished)));
            assertDoesNotThrow(() -> new RootedAdmissionSources(view.logicalBoundary(), Map.of(source.id(), view)));
            assertDoesNotThrow(() -> new RootedAdmissionSources(null, Map.of()));
            var full = Map.<String, Object>of("admission", Map.of("mode", "FULL_HISTORY"));
            assertDoesNotThrow(() -> RootedAdmissionSources.restoreStored(earlier, Map.of(source.id(), view), full));
            assertThrows(IllegalArgumentException.class, () -> RootedAdmissionSources.restoreStored(null, Map.of(source.id(), view), full));
            assertThrows(IllegalArgumentException.class, () -> RootedAdmissionSources.restoreStored(earlier, Map.of(DocumentId.of("foreign"), view), full));
            assertThrows(IllegalArgumentException.class, () -> RootedAdmissionSources.restoreStored(earlier, Map.of(source.id(), unpublished), full));
            for (String mode : List.of("FROM_NOW", "FROM_FRONTIER", "CREATED_IN_OPERATION", "UNKNOWN")) {
                assertThrows(IllegalArgumentException.class, () -> RootedAdmissionSources.restoreStored(earlier,
                        Map.of(source.id(), view), Map.of("admission", Map.of("mode", mode))));
            }
            assertThrows(IllegalArgumentException.class, () -> RootedAdmissionSources.restoreStored(earlier, Map.of(source.id(), view), Map.of()));
        }
    }

    @Test void invalidHistoryIndexesReadinessAndViewMembershipFailClosed() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            var state = f.engine.documents().require(handle.id()).storedState();
            for (Map<String, Object> corruption : List.<Map<String, Object>>of(
                    Map.of("epoch", 7L), Map.of("readyEpoch", 2L), Map.of("graphPublishedEpoch", 2L),
                    Map.of("stateEpochs", Map.of()), Map.of("terminalEntryBlueIds", Set.of()),
                    Map.of("transitionReceipts", Set.of()), Map.of("rootedViewPositions", List.of()))) {
                assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreStored(change(state, corruption)));
            }
            try (var scope = f.storage.openScope()) {
                String address = f.storage.retain(f.engine.documents().require(handle.id()));
                assertThrows(CoordinationObjectStorageException.class, () -> scope.open(DocumentId.of("wrong-document"), address));
            }
        }
    }

    @Test void missingCorruptUnavailableAckAndPhysicalLimitsNeverReturnPartialSession() throws Exception {
        try (var f = new Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            DocumentSession session = f.engine.documents().require(handle.id());
            String address = f.storage.retain(session); var complete = f.bytes.copy();
            try (var scope = f.storage.openScope()) {
                f.bytes.records.remove(address);
                assertThrows(CoordinationObjectStorageException.class, () -> scope.open(handle.id(), address));
                f.bytes.records.putAll(complete.copy().records); f.bytes.records.get(address)[0] ^= 1;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.open(handle.id(), address));
                f.bytes.records.clear(); f.bytes.records.putAll(complete.copy().records);
                f.bytes.failRead = true;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.open(handle.id(), address));
                f.bytes.failRead = false;
                assertEquals(address, f.storage.retain(scope.open(handle.id(), address)));
            }
            f.bytes.badAck = true; assertThrows(CoordinationObjectStorageException.class, () -> f.storage.retain(session)); f.bytes.badAck = false;
            int writes = f.bytes.writes;
            var small = new DocumentSessionStorage(f.bytes, new DocumentSessionStorage.Limits(1024, 256, 1024));
            assertThrows(CoordinationObjectStorageException.class, () -> small.retain(session));
            assertEquals(writes, f.bytes.writes, "All serialization is prevalidated before immutable prewrites");
            var scope = f.storage.openScope(); scope.close();
            assertThrows(CoordinationObjectStorageException.class, () -> scope.open(handle.id(), address));
        }
    }

    @Test void selectingOneSessionOpensOnlyItsAddressedViewsAndNoOtherSession() throws Exception {
        try (var f = new Fixture()) {
            var selected = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String address = f.storage.retain(f.engine.documents().require(selected.id()));
            for (int n = 0; n < 8; n++) {
                var other = f.start("name: untouched-" + n + "\nvalue: " + n, "unrelated/" + n, ActivationPolicy.fromNow());
                f.storage.retain(f.engine.documents().require(other.id()));
            }
            int reads = f.bytes.reads;
            try (var scope = f.storage.openScope()) {
                scope.open(selected.id(), address);
                assertEquals(3, f.bytes.reads - reads, "Exactly selected session, its revision and one rooted view");
                scope.open(selected.id(), address);
                assertEquals(5, f.bytes.reads - reads, "Second session copy reauthenticates its revision and shares the immutable view");
            }
        }
    }

    @Test void verifiedNestedViewPayloadsKeepFullCrossLinksAndCanonicalEnvelopeChecks() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var before = f.engine.documents().require(source.id()).rootedView();
            byte[] beforeBytes = f.bytes.records.get(f.storage.retainView(before));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var after = f.engine.documents().require(source.id()).rootedView();
            String address = f.storage.retainView(after);
            byte[] complete = f.bytes.records.get(address);
            // The first length-prefixed member follows the UTF-16 physical format label.
            int member = 4 + java.nio.ByteBuffer.wrap(complete).getInt();
            int beforeMember = 4 + java.nio.ByteBuffer.wrap(beforeBytes).getInt();
            int beforeLength = java.nio.ByteBuffer.wrap(beforeBytes, beforeMember, 4).getInt();
            byte[] foreignResult = Arrays.copyOfRange(beforeBytes, beforeMember + 4, beforeMember + 4 + beforeLength);
            byte[] mixedResult = replaceViewMember(complete, member, foreignResult);
            var snapshots = new blue.language.processor.closure.AffectedClosureSnapshotStorageCodec(
                    LIMITS.maximumRecordBytes(), LIMITS.maximumDepth());
            byte[] retainedAfter = snapshots.encode(after.retainedSnapshot());
            int retainedMember = complete.length - retainedAfter.length - 4;
            assertEquals(retainedAfter.length, java.nio.ByteBuffer.wrap(complete, retainedMember, 4).getInt());
            byte[] mixedRetained = replaceViewMember(complete, retainedMember, snapshots.encode(before.retainedSnapshot()));
            byte[] damagedNested = complete.clone();
            int memberLength = java.nio.ByteBuffer.wrap(complete, member, 4).getInt();
            damagedNested[member + 4 + memberLength - 1] ^= 1;
            byte[] noncanonicalBoundary = complete.clone();
            noncanonicalBoundary[member + 4 + memberLength] = 2;
            byte[] trailing = Arrays.copyOf(complete, complete.length + 1);
            try (var scope = f.storage.openScope()) {
                for (byte[] invalid : List.of(mixedResult, mixedRetained, damagedNested, noncanonicalBoundary, trailing)) {
                    String invalidAddress = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(invalid));
                    f.bytes.records.put(invalidAddress, invalid);
                    assertThrows(CoordinationObjectStorageException.class, () -> scope.view(invalidAddress),
                            "Even correctly addressed records must pass nested, cross-link and outer canonical validation");
                }
                var restored = scope.view(address);
                assertEquals(address, f.storage.retainView(restored), "Failed reads must not seed a partial validated view");
                assertArrayEquals(complete, f.bytes.records.get(address));
                restored.snapshot().managedDocuments().get(0).document().name("mutated detached copy");
                assertEquals(address, f.storage.retainView(restored), "Caller-owned Node copies cannot change verified payloads");
                f.bytes.badAck = true;
                assertThrows(CoordinationObjectStorageException.class, () -> f.storage.retainView(restored),
                        "A successful decode never grants an immutable-write acknowledgement");
            }
        }
    }

    private static byte[] replaceViewMember(byte[] original, int offset, byte[] replacement) {
        int length = java.nio.ByteBuffer.wrap(original, offset, 4).getInt();
        var encoded = java.nio.ByteBuffer.allocate(original.length - length + replacement.length);
        encoded.put(original, 0, offset).putInt(replacement.length).put(replacement);
        encoded.put(original, offset + 4 + length, original.length - offset - 4 - length);
        return encoded.array();
    }

    @Test void failedLaterImmutablePrewriteDoesNotPublishANewSessionReference() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String published = f.storage.retain(f.engine.documents().require(source.id()));
            f.process(source, f.append(source, "rcp2/source", "tick"));
            f.bytes.failAtWrite = f.bytes.writes + 2;
            assertThrows(CoordinationObjectStorageException.class, () -> f.storage.retain(f.engine.documents().require(source.id())));
            f.bytes.failAtWrite = -1;
            try (var scope = f.storage.openScope()) {
                assertEquals(0L, scope.open(source.id(), published).epoch(), "Previously published exact reference remains complete");
                String next = f.storage.retain(f.engine.documents().require(source.id()));
                assertNotEquals(published, next); assertEquals(1L, scope.open(source.id(), next).epoch());
            }
        }
    }

    @Test void coldComponentValuesMatchButChangedGenerationOrderAndCompleteProofDoNot() throws Exception {
        try (var f = new Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow()); f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var view = f.engine.documents().require(parent.id()).rootedView(); var snapshot = view.retainedSnapshot();
            var copies = snapshot.components().stream().map(component -> copyComponent(component, component.componentGeneration())).toList();
            assertTrue(view.matchesCapture(snapshot.graphGeneration(), snapshot.managedDocuments(), snapshot.occurrences(), copies, snapshot.publicRootDocumentIds()));
            assertNotSame(snapshot.components().get(0), copies.get(0));
            var changed = new ArrayList<>(copies); changed.set(0, copyComponent(copies.get(0), copies.get(0).componentGeneration() + 1));
            assertFalse(view.matchesCapture(snapshot.graphGeneration(), snapshot.managedDocuments(), snapshot.occurrences(), changed, snapshot.publicRootDocumentIds()));
            assertTrue(copies.size() > 1); var reversed = new ArrayList<>(copies); Collections.reverse(reversed);
            assertFalse(view.matchesCapture(snapshot.graphGeneration(), snapshot.managedDocuments(), snapshot.occurrences(), reversed, snapshot.publicRootDocumentIds()));
        }
        var provider = new blue.language.preprocess.provider.BasicNodeProvider(new blue.language.model.Node().items(List.of(
                new blue.language.model.Node().name("stored-proof-a").properties("peer", new blue.language.model.Node().blueId("this#1")),
                new blue.language.model.Node().name("stored-proof-b").properties("peer", new blue.language.model.Node().blueId("this#0")))));
        String member = provider.getBlueIdByName("stored-proof-a"); String master = blue.language.identity.BlueIds.cyclicSetMasterBlueId(member);
        var proof = provider.cyclicSetProofFor(member).proof().orElseThrow();
        var ids = List.of(new blue.language.processor.closure.DocumentId("a"), new blue.language.processor.closure.DocumentId("b"));
        var exact = new blue.language.processor.closure.ComponentSnapshot("sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64), 1,
                blue.language.processor.closure.ComponentKind.CYCLIC, ids, List.of(master + "#0", master + "#1"), master, proof, "sha256:" + "c".repeat(64));
        var identical = copyComponent(exact, exact.componentGeneration());
        var alteredRows = new ArrayList<>(proof.declaredPlaceholderSet()); alteredRows.set(0, alteredRows.get(0).clone().name("different-proof-body"));
        var altered = new blue.language.processor.closure.ComponentSnapshot(exact.componentIdentity(), exact.componentStateIdentity(), 1,
                exact.kind(), ids, exact.orderedMemberBlueIds(), master,
                blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(alteredRows), exact.cyclicProofIdentity());
        var comparison = RootedDocumentView.class.getDeclaredMethod("sameComponents", List.class, List.class); comparison.setAccessible(true);
        assertEquals(true, comparison.invoke(null, List.of(exact), List.of(identical)));
        assertEquals(false, comparison.invoke(null, List.of(exact), List.of(altered)), "Equal asserted hashes must not hide changed proof bytes");
    }

    private static blue.language.processor.closure.ComponentSnapshot copyComponent(blue.language.processor.closure.ComponentSnapshot row, long generation) {
        return new blue.language.processor.closure.ComponentSnapshot(row.componentIdentity(), row.componentStateIdentity(), generation, row.kind(),
                row.orderedMemberDocumentIds(), row.orderedMemberBlueIds(), row.masterBlueId(), row.completeCyclicProof(), row.cyclicProofIdentity());
    }

    @Test void recurrentStateIndexAndAdvancedBoundaryAreRetainedWithoutInventingAnEpoch() throws Exception {
        try (var f = new Fixture()) {
            var x = blue.coordination.api.ExactValue.verified(new blue.language.model.Node().value("X"));
            var y = blue.coordination.api.ExactValue.verified(new blue.language.model.Node().value("Y"));
            var id = DocumentId.of("recurrent-storage");
            var order = blue.language.processor.ExternalOrderKey.of(List.of(java.math.BigInteger.ONE, "timeline", "entry"));
            var initial = new blue.coordination.api.DocumentRevision(id, 0, 0, blue.coordination.api.DocumentRevision.Kind.INITIALIZATION,
                    null, x, null, order, x.blueId(), null, List.of(), 0, null);
            var session = new DocumentSession(id, x, valueLayout(x), List.of(), order, initial);
            for (int epoch = 1; epoch <= 2; epoch++) {
                var after = epoch == 1 ? y : x;
                var revision = new blue.coordination.api.DocumentRevision(id, epoch, epoch, blue.coordination.api.DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                        session.currentRevision().after(), after, null, null, List.of(), 0);
                session.commit(revision, valueLayout(after), order, List.of(), "step-" + epoch);
            }
            try (var scope = f.storage.openScope()) {
                var restored = scope.open(id, f.storage.retain(session));
                assertThrows(IllegalStateException.class, () -> restored.resolveAdmissionEpoch(x.blueId(), null));
                assertEquals(0, restored.resolveAdmissionEpoch(x.blueId(), 0L));
                assertEquals(2, restored.resolveAdmissionEpoch(x.blueId(), 2L));
            }
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var state = f.engine.documents().require(source.id()).storedState();
            var first = state.rootedViewPositions().get(0);
            var invented = new DocumentSession.RootedViewPosition(first.view(), order);
            assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreStored(change(state, Map.of("rootedViewPositions", List.of(invented)))));
        }
    }
    private static EmbeddedOnlyLayout valueLayout(blue.coordination.api.ExactValue value) {
        return new EmbeddedOnlyLayout(value, value.frozen(), Map.of("/", value), List.of(), List.of(),
                EmbeddedLayoutPlan.managedRoot(new RoutingSurface(List.of(), false)));
    }

    private static String revisionAddress(blue.coordination.api.DocumentRevision revision) throws Exception {
        byte[] frame = new SessionRecordCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth()).encodeRevision(revision);
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frame));
    }

    static DocumentSession.StoredState change(DocumentSession.StoredState original, Map<String, Object> replacements) {
        try {
            var fields = DocumentSession.StoredState.class.getRecordComponents(); var types = new Class<?>[fields.length]; var values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType(); values[i] = replacements.containsKey(fields[i].getName()) ? replacements.get(fields[i].getName()) : fields[i].getAccessor().invoke(original);
            }
            return DocumentSession.StoredState.class.getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    static final class Fixture implements AutoCloseable {
        final AtomicInteger providerReads = new AtomicInteger();
        final Map<String, String> exact = new LinkedHashMap<>();
        final BlueCoordination blue = BlueCoordination.builder().contentDerivedDocumentIds().exactNodeProvider(id -> {
            providerReads.incrementAndGet(); return Optional.ofNullable(exact.get(id));
        }).build();
        final DefaultCoordinationEngine engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
        final Bytes bytes = new Bytes();
        final DocumentSessionStorage storage = new DocumentSessionStorage(bytes, LIMITS);
        final Map<String, TimelineHandle> timelines = new LinkedHashMap<>();
        final Map<String, String> previous = new LinkedHashMap<>();
        final List<DocumentSessionStorage.OpenScope> scopes = new ArrayList<>();
        long clock = 100;

        DocumentHandle start(String yaml, String timeline, ActivationPolicy policy) {
            timelines.computeIfAbsent(timeline, id -> blue.timelines().register(id, "alice"));
            var document = blue.documents().admitStaticProcessEmbedded(yaml, policy).document("root"); retain(document); return document;
        }
        void retain(DocumentHandle handle) { exact.put(handle.snapshot().blueId(), handle.snapshot().exact().json()); }
        EntryHandle append(DocumentHandle target, String timeline, String operation) {
            String yaml = """
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                    timestamp: %d
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      operation: %s
                      channel: owner
                      request: {}
                    """.formatted(timeline, clock, target.snapshot().blueId(), operation);
            clock += 100;
            if (previous.containsKey(timeline)) yaml += "\nprevEntry: {blueId: " + previous.get(timeline) + "}\n";
            var entry = blue.events().from(timelines.get(timeline)).exact(blue.values().yaml(yaml)).submit();
            previous.put(timeline, entry.blueId()); return entry;
        }
        void process(DocumentHandle root, EntryHandle entry) {
            var result = blue.advanced().process(root, entry, ContractsExecutionPolicy.releaseDefault());
            assertEquals(EntryDisposition.APPLIED, result.entry(entry).disposition(), result.diagnostic().toString());
        }
        void coldSessions() throws Exception {
            Field field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true);
            var before = (InMemoryDocumentStore.StoreState) field.get(engine.documents());
            var addresses = new LinkedHashMap<DocumentId, String>();
            before.sessions().forEach((id, session) -> addresses.put(id, storage.retain(session)));
            // Fresh physical-store wrapper and codec; no resident decoded session/result is passed to restoration.
            var coldStorage = new DocumentSessionStorage(bytes.copy(), LIMITS);
            var scope = coldStorage.openScope(); scopes.add(scope);
            int reads = providerReads.get(); var restored = new LinkedHashMap<DocumentId, DocumentSession>();
            addresses.forEach((id, address) -> {
                DocumentSession session = scope.open(id, address); restored.put(id, session);
                assertNotSame(before.sessions().get(id), session);
                assertEquals(address, coldStorage.retain(session), "Every retained field survives exact canonical re-encoding");
            });
            assertEquals(reads, providerReads.get(), "Session restoration performs no external provider reads");
            field.set(engine.documents(), before.withSessions(restored, before.lineageIndex(), before.componentIndex(), before.componentIndexGeneration()));
            engine.restartFromStores();
        }
        List<String> completeEvidence(DocumentId id) {
            DocumentSession session = engine.documents().require(id);
            var evidence = new ArrayList<String>(); var rowCodec = new SessionRecordCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth());
            for (var revision : session.revisions()) evidence.add(Base64.getEncoder().encodeToString(SessionStorageWire.encode(LIMITS.maximumRecordBytes(), w -> rowCodec.revision(w, revision))));
            evidence.add(storage.retain(session)); return List.copyOf(evidence);
        }
        @Override public void close() { scopes.forEach(DocumentSessionStorage.OpenScope::close); blue.close(); }
    }

    private static final class ObservedBytes implements CoordinationImmutableObjectStore {
        private final Bytes delegate;
        private final List<String> readKeys = new ArrayList<>();
        private final List<String> writeKeys = new ArrayList<>();
        private ObservedBytes(Bytes delegate) { this.delegate = delegate; }
        private void clear() { readKeys.clear(); writeKeys.clear(); }
        @Override public Optional<byte[]> get(String key, int maximum) {
            readKeys.add(key); return delegate.get(key, maximum);
        }
        @Override public byte[] putIfAbsent(String key, byte[] value) {
            writeKeys.add(key); return delegate.putIfAbsent(key, value);
        }
    }

    static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> records = new LinkedHashMap<>(); int reads; int writes; int failAtWrite = -1; boolean failRead; boolean badAck;
        @Override public byte[] putIfAbsent(String digest, byte[] value) {
            writes++; if (writes == failAtWrite) throw new CoordinationObjectStorageException("Injected later prewrite failure");
            byte[] prior = records.putIfAbsent(digest, value.clone()); return badAck ? new byte[0] : (prior == null ? value : prior).clone();
        }
        @Override public Optional<byte[]> get(String digest, int maximumBytes) {
            reads++; if (failRead) throw new CoordinationObjectStorageException("Injected unavailable bytes");
            byte[] found = records.get(digest); if (found == null) return Optional.empty();
            if (found.length > maximumBytes) throw new CoordinationObjectStorageException("Byte bound"); return Optional.of(found.clone());
        }
        Bytes copy() { var copy = new Bytes(); records.forEach((key, value) -> copy.records.put(key, value.clone())); return copy; }
    }
    static String resource(String name) throws Exception {
        try (var input = DocumentSessionStorageTest.class.getResourceAsStream("/rooted/" + name)) {
            return new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
