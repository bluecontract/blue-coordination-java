package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Private session/2 physical delta, never a shortcut for current-session or publication validation. */
final class DocumentSessionRevisionStorageTest {
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(4 * 1024 * 1024, 256, 32L * 1024 * 1024);
    private static final ExternalOrderKey ORDER = ExternalOrderKey.of(List.of(BigInteger.ONE, "timeline", "entry"));

    @ParameterizedTest
    @ValueSource(ints = {5, 50, 100})
    void appendWritesOnlyNewRevisionAndMetadataWithOrWithoutProcessCache(int prefixEpochs) throws Exception {
        for (boolean enabled : List.of(false, true)) {
            var bytes = new ObservedBytes();
            try (var cache = new RootedStorageCache(enabled ? 32L * 1024 * 1024 : 0, 256, 8L * 1024 * 1024)) {
                var storage = new DocumentSessionStorage(bytes, LIMITS, cache);
                var initial = history("delta", prefixEpochs);
                String prior = storage.retain(initial);
                List<String> prefix = addresses(initial);
                long prefixBytes = prefix.stream().mapToLong(key -> bytes.delegate.records.get(key).length).sum();
                try (var owner = storage.openScope()) {
                    var selected = owner.open(initial.documentId(), prior);
                    var next = selected.copyForAtomicPublication();
                    append(next);
                    bytes.clear();
                    String address = owner.retain(next);
                    assertEquals(prefix, bytes.reads, "Every selected old revision is physically authenticated");
                    assertEquals(List.of(revisionAddress(next.currentRevision()), address), bytes.writes);
                    long expectedBytes = (long) bytes.delegate.records.get(revisionAddress(next.currentRevision())).length
                            + bytes.delegate.records.get(address).length;
                    assertEquals(expectedBytes, bytes.writtenBytes, "Only one new revision frame and current metadata are written");
                    assertTrue(bytes.writtenBytes < prefixBytes, "No old revision bodies are rewritten in the metadata");
                    assertEquals(prior, storage.retain(selected), "The prior detached session remains unchanged");
                    try (var cold = storage.openScope()) {
                        assertEquivalent(next, cold.open(next.documentId(), address));
                    }
                    cache.clear();
                    bytes.clear();
                    try (var cold = storage.openScope()) {
                        assertEquivalent(next, cold.open(next.documentId(), address));
                        assertEquals(next.revisions().size() + 1, bytes.reads.size(), "Eviction is an ordinary full read fallback");
                    }
                }
            }
        }
    }

    @Test void sameEpochRepresentationChangesOnlySessionMetadata() throws Exception {
        var bytes = new ObservedBytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var original = history("representation", 4);
        String prior = storage.retain(original);
        try (var owner = storage.openScope()) {
            var selected = owner.open(original.documentId(), prior);
            long epoch = selected.epoch();
            selected.rebindComponentRepresentation(epoch, layout(value("representation-change")), List.of(), "rebind", "publication");
            bytes.clear();
            String address = owner.retain(selected);
            assertEquals(addresses(original), bytes.reads);
            assertEquals(List.of(address), bytes.writes);
            try (var cold = storage.openScope()) {
                var restored = cold.open(selected.documentId(), address);
                assertEquals(epoch, restored.epoch());
                assertEquals(original.revisions().size(), restored.revisions().size());
                assertEquals(selected.representationTransitions(), restored.representationTransitions());
                assertEquivalent(selected, restored);
            }
        }
    }

    @SuppressWarnings("try") // Explicit early close is the lifecycle control; the final close remains idempotent.
    @Test void warmRevisionNeverHidesMissingCorruptOrUnavailableCurrentBytes() throws Exception {
        var bytes = new ObservedBytes();
        try (var cache = new RootedStorageCache(32L * 1024 * 1024, 256, 8L * 1024 * 1024)) {
            var storage = new DocumentSessionStorage(bytes, LIMITS, cache);
            var original = history("missing", 3); String address = storage.retain(original);
            String revision = addresses(original).get(0);
            byte[] valid = bytes.delegate.records.get(revision).clone();
            try (var owner = storage.openScope()) {
                var selected = owner.open(original.documentId(), address);
                for (String fault : List.of("missing", "corrupt", "unavailable")) {
                    if (fault.equals("missing")) bytes.delegate.records.remove(revision);
                    if (fault.equals("corrupt")) bytes.delegate.records.get(revision)[0] ^= 1;
                    if (fault.equals("unavailable")) bytes.delegate.failRead = true;
                    bytes.clear();
                    assertThrows(CoordinationObjectStorageException.class, () -> owner.retain(selected), fault);
                    assertTrue(bytes.writes.isEmpty(), "Every dependency is checked before any new prewrite");
                    assertThrows(CoordinationObjectStorageException.class, () -> owner.open(original.documentId(), address), fault);
                    try (var another = storage.openScope()) {
                        assertThrows(CoordinationObjectStorageException.class, () -> another.open(original.documentId(), address), fault);
                    }
                    bytes.delegate.failRead = false; bytes.delegate.records.put(revision, valid.clone());
                }
                assertEquivalent(selected, owner.open(original.documentId(), address));
                bytes.delegate.badAck = true;
                assertThrows(CoordinationObjectStorageException.class, () -> owner.retain(selected), "Known dependencies do not waive metadata acknowledgement");
                bytes.delegate.badAck = false;
                owner.close();
                assertThrows(CoordinationObjectStorageException.class, () -> owner.retain(selected));
            }
        }
    }

    @Test void allOldRevisionBytesCountTowardScopeEvenWhenNoOldRevisionIsWritten() throws Exception {
        var bytes = new ObservedBytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var original = history("bounds", 5); String address = storage.retain(original);
        long dependencies = addresses(original).stream().mapToLong(key -> bytes.delegate.records.get(key).length).sum();
        long total = dependencies + bytes.delegate.records.get(address).length;
        int maximum = bytes.delegate.records.values().stream().mapToInt(frame -> frame.length).max().orElseThrow();
        var bounded = new DocumentSessionStorage(bytes, new DocumentSessionStorage.Limits(maximum, 256, total - 1));
        try (var owner = bounded.openScope()) {
            var selected = owner.open(original.documentId(), address); bytes.clear();
            assertThrows(CoordinationObjectStorageException.class, () -> owner.retain(selected));
            assertTrue(bytes.writes.isEmpty());
            assertEquals(addresses(original), bytes.reads);
        }
        var exact = new DocumentSessionStorage(bytes, new DocumentSessionStorage.Limits(maximum, 256, total));
        try (var owner = exact.openScope()) {
            var selected = owner.open(original.documentId(), address); bytes.clear();
            assertEquals(address, owner.retain(selected)); assertEquals(List.of(address), bytes.writes);
        }
        var tooSmall = new DocumentSessionStorage(bytes, new DocumentSessionStorage.Limits(maximum, 256, dependencies - 1));
        try (var owner = tooSmall.openScope()) {
            assertThrows(CoordinationObjectStorageException.class, () -> owner.open(original.documentId(), address));
        }
    }

    @Test void legacyInlineCanonicalRecordReadsAndMigratesWithoutChangingEvidence() throws Exception {
        var bytes = new ObservedBytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var original = history("legacy", 3);
        // The legacy writer is not a production entry point; reflection only creates a fixed-format reader control.
        var encoder = DocumentSessionStorage.class.getDeclaredMethod("encodeSession", DocumentSession.StoredState.class, Function.class, Function.class);
        encoder.setAccessible(true);
        Function<RootedDocumentView, String> noViews = ignored -> { throw new AssertionError("Unexpected rooted view"); };
        byte[] legacy = (byte[]) encoder.invoke(storage, original.storedState(), noViews, null);
        String oldAddress = put(bytes, legacy);
        try (var owner = storage.openScope()) {
            var restored = owner.open(original.documentId(), oldAddress);
            assertEquivalent(original, restored);
            String upgraded = owner.retain(restored);
            assertNotEquals(oldAddress, upgraded);
            assertEquals(storage.retain(original), upgraded);
            try (var cold = storage.openScope()) { assertEquivalent(original, cold.open(original.documentId(), upgraded)); }
            byte[] trailing = Arrays.copyOf(legacy, legacy.length + 1);
            String invalid = put(bytes, trailing);
            assertThrows(CoordinationObjectStorageException.class, () -> owner.open(original.documentId(), invalid));
        }
    }

    @Test void correctlyAddressedInvalidOrMisorderedRevisionStillFailsFullSessionValidation() throws Exception {
        var bytes = new ObservedBytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var original = history("tamper", 3); String address = storage.retain(original);
        List<String> revisions = addresses(original);
        byte[] frame = bytes.delegate.records.get(revisions.get(1));
        String trailingRevision = put(bytes, Arrays.copyOf(frame, frame.length + 1));
        byte[] metadata = bytes.delegate.records.get(address);
        String malformed = put(bytes, replaceReference(metadata, revisions.get(1), trailingRevision));
        String repeated = put(bytes, replaceReference(metadata, revisions.get(1), revisions.get(2)));
        var foreign = history("foreign", 3); storage.retain(foreign);
        String foreignRow = put(bytes, replaceReference(metadata, revisions.get(1), addresses(foreign).get(1)));
        try (var owner = storage.openScope()) {
            owner.open(original.documentId(), address);
            for (String invalid : List.of(malformed, repeated, foreignRow)) {
                assertThrows(CoordinationObjectStorageException.class, () -> owner.open(original.documentId(), invalid));
            }
            assertEquivalent(original, owner.open(original.documentId(), address));
        }
    }

    @Test void failedMetadataWriteDoesNotLendNewRevisionEvidenceToRetry() throws Exception {
        var bytes = new ObservedBytes(); var storage = new DocumentSessionStorage(bytes, LIMITS);
        var original = history("retry", 3); String prior = storage.retain(original);
        try (var owner = storage.openScope(); var stage = owner.openRetentionStage()) {
            var next = owner.open(original.documentId(), prior); append(next);
            String revision = revisionAddress(next.currentRevision());
            bytes.clear(); bytes.delegate.failAtWrite = bytes.delegate.writes + 2;
            assertThrows(CoordinationObjectStorageException.class, () -> stage.retain(next));
            assertEquals(revision, bytes.writes.get(0)); assertEquals(2, bytes.writes.size());
            bytes.delegate.failAtWrite = -1; bytes.clear();
            String address = stage.retain(next);
            assertEquals(List.of(revision, address), bytes.writes, "Failed acknowledgement supplied no new-revision certificate");
            bytes.clear(); assertEquals(address, stage.retain(next));
            assertEquals(List.of(address), bytes.writes);
            assertEquals(addresses(next), bytes.reads);
        }
    }

    private static DocumentSession history(String id, int epochs) {
        var initial = value("value-0"); var document = DocumentId.of(id);
        var revision = new DocumentRevision(document, 0, 0, DocumentRevision.Kind.INITIALIZATION,
                null, initial, null, ORDER, initial.blueId(), null, List.of(), 0, null);
        var session = new DocumentSession(document, initial, layout(initial), List.of(), ORDER, revision);
        for (int i = 0; i < epochs; i++) append(session);
        return session;
    }

    private static void append(DocumentSession session) {
        long epoch = session.epoch() + 1; var after = value("value-" + epoch);
        var revision = new DocumentRevision(session.documentId(), epoch, epoch, DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                session.currentRevision().after(), after, null, null, List.of(new Node().value("event-" + epoch)), epoch);
        session.commit(revision, layout(after), ORDER, List.of(), "step-" + epoch);
    }

    private static ExactValue value(String name) { return ExactValue.verified(new Node().name(name).value("x".repeat(8192))); }
    private static EmbeddedOnlyLayout layout(ExactValue value) {
        return new EmbeddedOnlyLayout(value, value.frozen(), Map.of("/", value), List.of(), List.of(),
                EmbeddedLayoutPlan.managedRoot(new RoutingSurface(List.of(), false)));
    }
    private static SessionRecordCodec codec() { return new SessionRecordCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth()); }
    private static String revisionAddress(DocumentRevision revision) { return digest(codec().encodeRevision(revision)); }
    private static List<String> addresses(DocumentSession session) { return session.revisions().stream().map(DocumentSessionRevisionStorageTest::revisionAddress).toList(); }
    private static void assertEquivalent(DocumentSession expected, DocumentSession actual) {
        assertEquals(expected.epoch(), actual.epoch()); assertEquals(expected.status(), actual.status());
        assertEquals(expected.currentRepresentation().blueId(), actual.currentRepresentation().blueId());
        assertEquals(expected.readyRepresentation().blueId(), actual.readyRepresentation().blueId());
        assertEquals(expected.representationTransitions(), actual.representationTransitions());
        assertEquals(expected.revisions().size(), actual.revisions().size());
        for (int i = 0; i < expected.revisions().size(); i++)
            assertArrayEquals(codec().encodeRevision(expected.revision(i)), codec().encodeRevision(actual.revision(i)), "Complete history, exact values, events and gas");
    }
    private static String digest(byte[] frame) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frame)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String put(ObservedBytes bytes, byte[] frame) { String address = digest(frame); bytes.delegate.records.put(address, frame); return address; }
    private static byte[] replaceReference(byte[] metadata, String oldAddress, String nextAddress) {
        byte[] old = oldAddress.getBytes(StandardCharsets.UTF_16BE), next = nextAddress.getBytes(StandardCharsets.UTF_16BE);
        byte[] changed = metadata.clone(); int found = 0;
        for (int i = 0; i <= changed.length - old.length; i++) {
            if (Arrays.equals(changed, i, i + old.length, old, 0, old.length)) {
                System.arraycopy(next, 0, changed, i, next.length); found++; i += old.length - 1;
            }
        }
        assertEquals(1, found); return changed;
    }

    private static final class ObservedBytes implements CoordinationImmutableObjectStore {
        final DocumentSessionStorageTest.Bytes delegate = new DocumentSessionStorageTest.Bytes();
        final List<String> reads = new ArrayList<>(), writes = new ArrayList<>(); long writtenBytes;
        void clear() { reads.clear(); writes.clear(); writtenBytes = 0; }
        @Override public Optional<byte[]> get(String address, int maximum) { reads.add(address); return delegate.get(address, maximum); }
        @Override public byte[] putIfAbsent(String address, byte[] frame) {
            writes.add(address); writtenBytes += frame.length; return delegate.putIfAbsent(address, frame);
        }
    }
}
