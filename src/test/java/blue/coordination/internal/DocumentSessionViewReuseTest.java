package blue.coordination.internal;

import blue.coordination.api.SessionStatus;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.ActivationPolicy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** Physical serialization reuse only; all fixtures are produced by the real rooted SDK. */
final class DocumentSessionViewReuseTest {
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(32 * 1024 * 1024, 256, 256L * 1024 * 1024);

    @Test void oneStageReusesTheSameNewImmutableViewAcrossSourceAndParentWithoutGrantingDecodedAuthority() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var s = f.engine.documents().require(source.id());
            var p = f.engine.documents().require(parent.id());
            assertSame(s.rootedView(), p.requireRootedHistory().admissionSources().storedViews().get(source.id()));
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String sourceAddress = storage.retain(s);
            assertEquals(1, encodes.get(), "The source control has exactly one new immutable view");
            String parentAddress = storage.retain(p);
            int ordinaryEncodes = encodes.get();
            var oracle = f.bytes.copy();
            try (var scope = storage.openScope()) {
                encodes.set(0); int writes = f.bytes.writes;
                try (var retained = scope.openRetentionStage()) {
                    assertEquals(sourceAddress, retained.retain(s));
                    assertEquals(parentAddress, retained.retain(p));
                    assertEquals(ordinaryEncodes - 1, encodes.get(), "The shared source view is encoded once rather than twice");
                    int before = encodes.get();
                    var mutable = s.copyForAtomicPublication(); mutable.markCatchingUp();
                    assertNotEquals(sourceAddress, retained.retain(mutable), "Mutable session fields are still serialized");
                    assertEquals(before, encodes.get(), "Only immutable view serialization is reused");
                    assertThrows(CoordinationObjectStorageException.class, () -> scope.addressOf(s.rootedView()),
                            "Successful retention is not decoded view membership");
                    var derived = s.rootedView().withPublishedHeads(s.rootedView().storedState().publishedHeads());
                    var separate = s.copyForAtomicPublication(); separate.retainRootedView(derived);
                    retained.retain(separate);
                    assertEquals(before + 1, encodes.get(), "Equal-but-distinct derived views still require their first full encoding");
                }
                assertTrue(f.bytes.writes - writes >= 6, "All actual new-view and mutable-session writes remain acknowledged");
                oracle.records.forEach((key, value) -> assertArrayEquals(value, f.bytes.records.get(key)));
                encodes.set(0); assertEquals(sourceAddress, scope.retain(s)); assertEquals(1, encodes.get(),
                        "Transport evidence ends with the stage rather than becoming a permanent owner cache");
            }
        }
    }

    @Test void stageLocalReuseAuthenticatesKnownBytesAndAcknowledgesEveryActualSessionWrite() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var session = f.engine.documents().require(handle.id());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String expected = storage.retain(session);
            String view = storage.viewAddress(session.rootedView());
            byte[] valid = f.bytes.records.get(view).clone();
            try (var scope = storage.openScope()) {
                encodes.set(0);
                try (var retained = scope.openRetentionStage()) {
                    assertEquals(expected, retained.retain(session)); assertEquals(1, encodes.get());
                    int writes = f.bytes.writes;
                    f.bytes.records.remove(view);
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    byte[] corrupt = valid.clone(); corrupt[0] ^= 1; f.bytes.records.put(view, corrupt);
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    f.bytes.records.put(view, valid); f.bytes.failRead = true;
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    f.bytes.failRead = false;
                    assertEquals(writes, f.bytes.writes); assertEquals(1, encodes.get());
                    f.bytes.badAck = true;
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    f.bytes.badAck = false;
                    int before = f.bytes.writes;
                    f.bytes.failAtWrite = before + 1;
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    f.bytes.failAtWrite = -1;
                    assertEquals(before + 1, f.bytes.writes, "The authenticated view is read; only the session is written");
                    before = f.bytes.writes; int reads = f.bytes.reads;
                    assertEquals(expected, retained.retain(session)); assertEquals(1, encodes.get());
                    assertEquals(before + 1, f.bytes.writes); assertEquals(reads + 2, f.bytes.reads,
                            "The selected revision and view are both authenticated");
                }
            }
        }
    }

    @Test void failedFirstRetentionCannotLendStageEvidence() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var session = f.engine.documents().require(handle.id());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            try (var scope = storage.openScope()) {
                var retained = scope.openRetentionStage();
                try (retained) {
                    f.bytes.badAck = true;
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    f.bytes.badAck = false;
                    retained.retain(session); assertEquals(2, encodes.get(),
                            "Failed acknowledgement never makes the first encoding reusable");
                }
                assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session),
                        "A closed transport token cannot lend successful retained evidence");
            }
        }
    }

    @Test void stageLocalReuseStillChargesTheWholeNextSessionBeforeAnyPrewrite() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var session = f.engine.documents().require(handle.id());
            f.storage.retain(session); assertEquals(3, f.bytes.records.size(), "Revision, view and session metadata");
            int maximum = f.bytes.records.values().stream().mapToInt(value -> value.length).max().orElseThrow();
            long total = f.bytes.records.values().stream().mapToLong(value -> value.length).sum();
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes,
                    new DocumentSessionStorage.Limits(maximum, 256, total), encodes::incrementAndGet);
            try (var scope = storage.openScope()) {
                try (var retained = scope.openRetentionStage()) {
                    retained.retain(session); assertEquals(1, encodes.get());
                    session.markCatchingUp(); // The longer status makes the complete session exceed the same bound.
                    int writes = f.bytes.writes;
                    assertThrows(CoordinationObjectStorageException.class, () -> retained.retain(session));
                    assertEquals(1, encodes.get()); assertEquals(writes, f.bytes.writes);
                    assertThrows(CoordinationObjectStorageException.class, () -> storage.retain(session),
                            "The ordinary path rejects the identical complete session under the same bounds");
                    assertEquals(writes, f.bytes.writes);
                }
            }
        }
    }

    @Test void knownHistoryUsesZeroFullViewEncodesAndAcknowledgesOnlyTheCurrentSession() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            f.process(handle, f.append(handle, "rcp2/source", "tick"));
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String address = storage.retain(f.engine.documents().require(handle.id()));
            int uniqueViews = encodes.get();
            assertTrue(uniqueViews > 1, "The control must contain real earlier history views");
            var originalBytes = f.bytes.copy();
            try (var scope = storage.openScope()) {
                var restored = scope.open(handle.id(), address);
                int providerReads = f.providerReads.get();
                for (int repeat = 0; repeat < 2; repeat++) {
                    // Public Node access is defensive; it cannot mutate a selected immutable view.
                    restored.rootedView().snapshot().managedDocuments().get(0).document().name("caller clone");
                    encodes.set(0); int reads = f.bytes.reads, writes = f.bytes.writes;
                    assertEquals(address, scope.retain(restored));
                    assertEquals(0, encodes.get(), "Observe the actual full-encode branch, not elapsed time");
                    assertEquals(uniqueViews + restored.revisions().size(), f.bytes.reads - reads,
                            "Every known revision and view is authenticated again");
                    assertEquals(1, f.bytes.writes - writes,
                            "Every known view is already retained; only the fully serialized session needs an acknowledgement");
                    assertEquals(originalBytes.records.keySet(), f.bytes.records.keySet());
                    originalBytes.records.forEach((key, value) -> assertArrayEquals(value, f.bytes.records.get(key)));
                }
                assertEquals(providerReads, f.providerReads.get());
            }
        }
    }

    @Test void sharedAdmissionSourceAliasesAndWholeSessionBytesArePreserved() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick")); f.retain(source);
            var parent = f.start(resource("parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.importFullHistory());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String sourceAddress = storage.retain(f.engine.documents().require(source.id()));
            String parentAddress = storage.retain(f.engine.documents().require(parent.id()));
            try (var scope = storage.openScope()) {
                var p = scope.open(parent.id(), parentAddress);
                var s = scope.open(source.id(), sourceAddress);
                assertSame(s.rootedView(), p.requireRootedHistory().admissionSources().storedViews().get(source.id()));
                encodes.set(0);
                assertEquals(parentAddress, scope.retain(p)); assertEquals(sourceAddress, scope.retain(s));
                assertEquals(0, encodes.get());
                try (var cold = storage.openScope()) {
                    var nextSource = cold.open(source.id(), sourceAddress);
                    var nextParent = cold.open(parent.id(), parentAddress);
                    assertNotSame(s.rootedView(), nextSource.rootedView());
                    assertSame(nextSource.rootedView(), nextParent.requireRootedHistory().admissionSources().storedViews().get(source.id()));
                    assertEquals(parentAddress, storage.retain(nextParent), "Independent full encoding remains the oracle");
                }
            }
        }
    }

    @Test void mutableSessionStatusReadinessAndLaterRealRevisionAreAlwaysSerialized() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String initial = storage.retain(f.engine.documents().require(handle.id()));
            try (var scope = storage.openScope()) {
                var working = scope.open(handle.id(), initial);
                working.markCatchingUp(); encodes.set(0);
                String catchingUp = scope.retain(working);
                assertEquals(0, encodes.get()); assertNotEquals(initial, catchingUp);
                assertEquals(storage.retain(working), catchingUp);
                try (var cold = storage.openScope()) {
                    assertEquals(SessionStatus.CATCHING_UP, cold.open(handle.id(), catchingUp).status());
                }
                working.markReady(working.readyThrough());
                assertEquals(initial, scope.retain(working));

                var oldState = working.storedState();
                f.process(handle, f.append(handle, "rcp2/source", "tick"));
                var next = f.engine.documents().require(handle.id());
                var added = new HashSet<>(next.storedState().transitionReceipts());
                added.removeAll(oldState.transitionReceipts()); assertEquals(1, added.size());
                working.commit(next.currentRevision(), next.storedState().layout(), next.readyThrough(),
                        next.activeSubscriptions(), added.iterator().next());
                working.retainRootedView(next.rootedView()); working.markGraphPublished(); working.markReady(next.readyThrough());
                encodes.set(0); String advanced = scope.retain(working);
                assertEquals(1, encodes.get(), "Only the actual new result view requires full encoding");
                assertNotEquals(initial, advanced); assertEquals(storage.retain(working), advanced);
                try (var cold = storage.openScope()) {
                    var restored = cold.open(handle.id(), advanced);
                    assertEquals(1L, restored.epoch());
                    assertEquals(working.currentRevision().after().blueId(), restored.currentRevision().after().blueId());
                    assertEquals(advanced, storage.retain(restored), "All mutable fields retain their ordinary byte oracle");
                }
            }
        }
    }

    @Test void foreignAndDerivedIdentitiesFallBackAndClosedScopeCannotLendItsAuthority() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String address = storage.retain(f.engine.documents().require(handle.id()));
            var origin = storage.openScope();
            var foreign = origin.open(handle.id(), address);
            origin.close();
            int writes = f.bytes.writes, reads = f.bytes.reads;
            assertThrows(CoordinationObjectStorageException.class, () -> origin.retain(foreign));
            assertEquals(writes, f.bytes.writes); assertEquals(reads, f.bytes.reads);
            try (var fresh = storage.openScope()) {
                encodes.set(0); assertEquals(address, fresh.retain(foreign)); assertEquals(1, encodes.get());
                var own = fresh.open(handle.id(), address);
                assertNotSame(foreign.rootedView(), own.rootedView());
                encodes.set(0); assertEquals(address, fresh.retain(own)); assertEquals(0, encodes.get());
                var derived = own.rootedView().withPublishedHeads(own.rootedView().storedState().publishedHeads());
                assertNotSame(own.rootedView(), derived);
                own.retainRootedView(derived);
                encodes.set(0); String changed = fresh.retain(own);
                assertEquals(1, encodes.get(), "Matching head values do not confer scope identity");
                assertEquals(storage.retain(own), changed);
                encodes.set(0); assertEquals(changed, fresh.retain(own)); assertEquals(1, encodes.get(),
                        "Staging never registers a newly encoded view as a decoded scope member");
            }
        }
    }

    @Test void missingCorruptOrUnavailableKnownBytesFailBeforeAnyPrewriteAndNeverFallBack() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes, LIMITS, encodes::incrementAndGet);
            String address = storage.retain(f.engine.documents().require(handle.id()));
            try (var scope = storage.openScope()) {
                var session = scope.open(handle.id(), address);
                String view = scope.addressOf(session.rootedView()); byte[] valid = f.bytes.records.get(view).clone();
                int writes = f.bytes.writes; encodes.set(0);
                f.bytes.records.remove(view);
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                byte[] corrupt = valid.clone(); corrupt[0] ^= 1; f.bytes.records.put(view, corrupt);
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                f.bytes.records.put(view, valid); f.bytes.failRead = true;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                f.bytes.failRead = false;
                assertEquals(writes, f.bytes.writes); assertEquals(0, encodes.get());
                assertEquals(address, scope.retain(session));
                try (var cold = storage.openScope()) { assertEquals(address, cold.retain(cold.open(handle.id(), address))); }
            }
        }
    }

    @Test void reusedRecordsStillCountTowardTheSameRetentionScopeBoundBeforeWriting() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String address = f.storage.retain(f.engine.documents().require(handle.id()));
            assertEquals(3, f.bytes.records.size(), "Revision, view and session metadata");
            int maximum = f.bytes.records.values().stream().mapToInt(value -> value.length).max().orElseThrow();
            long total = f.bytes.records.values().stream().mapToLong(value -> value.length).sum();
            var encodes = new AtomicInteger();
            var storage = new DocumentSessionStorage(f.bytes,
                    new DocumentSessionStorage.Limits(maximum, 256, total - 1), encodes::incrementAndGet);
            try (var scope = storage.openScope()) {
                var session = scope.open(handle.id(), address);
                int writes = f.bytes.writes;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                assertEquals(0, encodes.get()); assertEquals(writes, f.bytes.writes);
                assertThrows(CoordinationObjectStorageException.class, () -> storage.retain(session),
                        "The uncached path enforces the identical total bound");
                assertEquals(writes, f.bytes.writes);
            }
        }
    }

    @Test void knownReadRejectsOversizedBackingReturnAndKeepsPrivateByteOwnership() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String address = f.storage.retain(f.engine.documents().require(handle.id()));
            int maximum = f.bytes.records.values().stream().mapToInt(value -> value.length).max().orElseThrow();
            var oversized = new java.util.concurrent.atomic.AtomicBoolean();
            var store = new blue.coordination.api.storage.CoordinationImmutableObjectStore() {
                @Override public java.util.Optional<byte[]> get(String key, int cap) {
                    return oversized.get() ? java.util.Optional.of(new byte[cap + 1]) : f.bytes.get(key, cap);
                }
                @Override public byte[] putIfAbsent(String key, byte[] value) {
                    byte[] acknowledged = f.bytes.putIfAbsent(key, value);
                    Arrays.fill(value, (byte) 0); // A hostile port must not mutate private staged records.
                    return acknowledged;
                }
            };
            long total = f.bytes.records.values().stream().mapToLong(value -> value.length).sum();
            var storage = new DocumentSessionStorage(store, new DocumentSessionStorage.Limits(maximum, 256, total));
            try (var scope = storage.openScope()) {
                var session = scope.open(handle.id(), address);
                oversized.set(true); int writes = f.bytes.writes;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                assertEquals(writes, f.bytes.writes);
                oversized.set(false); assertEquals(address, scope.retain(session));
                assertEquals(address, f.storage.retain(session));
            }
        }
    }

    @Test void knownViewsAreReadWhileNewViewsAndEverySessionWriteStillRequireAcknowledgements() throws Exception {
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var handle = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            String address = f.storage.retain(f.engine.documents().require(handle.id()));
            try (var scope = f.storage.openScope()) {
                var session = scope.open(handle.id(), address); session.markCatchingUp();
                int writes = f.bytes.writes;
                f.bytes.badAck = true;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                f.bytes.badAck = false;
                assertEquals(writes + 1, f.bytes.writes, "Bad current-session acknowledgement is still rejected");
                f.bytes.failAtWrite = f.bytes.writes + 1;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                f.bytes.failAtWrite = -1;
                try (var cold = f.storage.openScope()) {
                    assertEquals(SessionStatus.READY, cold.open(handle.id(), address).status(),
                            "A failed current-session write never changes the selected old reference");
                }
                String catchingUp = scope.retain(session);
                assertNotEquals(address, catchingUp); assertEquals(f.storage.retain(session), catchingUp);
                try (var cold = f.storage.openScope()) {
                    assertEquals(SessionStatus.CATCHING_UP, cold.open(handle.id(), catchingUp).status());
                }

                // Retain the selected old view while introducing one genuinely new SDK result.
                // The new revision, new view and session are independently acknowledged.
                session.markReady(session.readyThrough());
                var oldState = session.storedState();
                f.process(handle, f.append(handle, "rcp2/source", "tick"));
                var advanced = f.engine.documents().require(handle.id());
                var added = new HashSet<>(advanced.storedState().transitionReceipts());
                added.removeAll(oldState.transitionReceipts()); assertEquals(1, added.size());
                session.commit(advanced.currentRevision(), advanced.storedState().layout(), advanced.readyThrough(),
                        advanced.activeSubscriptions(), added.iterator().next());
                session.retainRootedView(advanced.rootedView()); session.markGraphPublished(); session.markReady(session.readyThrough());
                writes = f.bytes.writes;
                f.bytes.badAck = true;
                assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                f.bytes.badAck = false;
                assertEquals(writes + 1, f.bytes.writes, "A new-revision acknowledgement is still mandatory");
                for (int offset : List.of(1, 2, 3)) {
                    writes = f.bytes.writes; f.bytes.failAtWrite = writes + offset;
                    assertThrows(CoordinationObjectStorageException.class, () -> scope.retain(session));
                    f.bytes.failAtWrite = -1;
                    assertEquals(writes + offset, f.bytes.writes, "Failure targets the actual new-revision, new-view or session write");
                    try (var cold = f.storage.openScope()) {
                        var old = cold.open(handle.id(), address);
                        assertEquals(SessionStatus.READY, old.status()); assertEquals(0L, old.epoch(),
                                "An unsuccessful stage never publishes a changed session reference");
                    }
                }
                writes = f.bytes.writes;
                String next = scope.retain(session);
                assertEquals(writes + 3, f.bytes.writes, "Retry acknowledges new revision, new view and complete session");
                assertNotEquals(address, next); assertEquals(f.storage.retain(session), next);
                try (var cold = f.storage.openScope()) {
                    var restored = cold.open(handle.id(), next);
                    assertEquals(1L, restored.epoch()); assertEquals(SessionStatus.READY, restored.status());
                    assertEquals(advanced.currentRepresentation().blueId(), restored.currentRepresentation().blueId());
                }
            }
        }
    }
}
