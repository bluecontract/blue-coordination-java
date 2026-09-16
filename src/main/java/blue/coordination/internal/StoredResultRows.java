package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.closure.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static blue.coordination.internal.SessionStorageWire.*;

/** Explicit bounded result/member identity scope; neither a publication ledger nor a global cache. */
final class StoredResultRows implements AutoCloseable {
    private final CoordinationImmutableObjectStore objects;
    private final DocumentSessionStorage.Limits limits;
    private final StoredClosureResultCodec results;
    private final ResultRowStorageCodec rows;
    private final Map<String, ClosureProcessResult> opened = new HashMap<>();
    private final Map<ClosureProcessResult, String> addresses = new IdentityHashMap<>();
    private final Map<PublicEventOccurrence, Member> events = new IdentityHashMap<>();
    private final Map<CheckpointWrite, Member> checkpoints = new IdentityHashMap<>();
    private long retainedBytes;
    private boolean closed;
    private record Member(ClosureProcessResult result, String address, int position) { }

    StoredResultRows(CoordinationImmutableObjectStore objects, DocumentSessionStorage.Limits limits) {
        this(objects, limits, null);
    }

    StoredResultRows(CoordinationImmutableObjectStore objects, DocumentSessionStorage.Limits limits, RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        results = new StoredClosureResultCodec(limits.maximumRecordBytes(), limits.maximumDepth(), cache);
        rows = new ResultRowStorageCodec(limits.maximumRecordBytes());
    }

    /** Prewrites only the supplied original result and registers its original member objects. */
    synchronized String retain(ClosureProcessResult result) {
        return physical(() -> {
            checkOpen(); String known = addresses.get(result); if (known != null) return known;
            require(Objects.requireNonNull(result).commits(), "Only committing results own published rows");
            byte[] encoded = results.encode(result); String address = digest(encoded);
            long charge = checkedCharge(result, address, encoded.length);
            byte[] acknowledged = objects.putIfAbsent(address, encoded.clone());
            require(acknowledged != null && Arrays.equals(encoded, acknowledged), "Original result acknowledgment differs");
            register(result, address, charge); return address;
        });
    }

    /** Loads exactly the named authenticated result; never consults a provider or another receipt. */
    synchronized ClosureProcessResult openResult(String address) {
        return physical(() -> {
            checkOpen(); require(address != null && address.matches("[0-9a-f]{64}"), "Invalid original result address");
            var known = opened.get(address); if (known != null) return known;
            byte[] returned = objects.get(address, limits.maximumRecordBytes()).orElseThrow(() ->
                    new CoordinationObjectStorageException("Missing selected original result " + address));
            require(returned.length <= limits.maximumRecordBytes(), "Original result exceeds physical bound");
            byte[] encoded = returned.clone(); require(address.equals(digest(encoded)), "Original result digest differs");
            var result = results.decode(encoded);
            // Complete rollback frames are valid in receipts, but never own published rows.
            // This owner-specific guard must run even when another reader populated the cache.
            require(result.commits(), "Noncommitting result cannot own published rows");
            long charge = checkedCharge(result, address, encoded.length);
            register(result, address, charge); opened.put(address, result); return result;
        });
    }

    PersistentMapCodec<PublicEventOccurrence> outboxCodec() {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/outbox-original-result-row/1"; }
            public byte[] encode(PublicEventOccurrence row) { return encodeEvent(row); }
            public PublicEventOccurrence decode(byte[] bytes) {
                synchronized (StoredResultRows.this) { checkOpen(); return rows.decodeOutbox(bytes, StoredResultRows.this::openResult); }
            }
        };
    }
    PersistentMapCodec<CheckpointWrite> checkpointCodec() {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/checkpoint-original-result-row/1"; }
            public byte[] encode(CheckpointWrite row) { return encodeCheckpoint(row); }
            public CheckpointWrite decode(byte[] bytes) {
                synchronized (StoredResultRows.this) { checkOpen(); return rows.decodeCheckpoint(bytes, StoredResultRows.this::openResult); }
            }
        };
    }
    private synchronized byte[] encodeEvent(PublicEventOccurrence row) {
        checkOpen(); var member = events.get(row); require(member != null, "Outbox row has no registered original result");
        return rows.encodeOutbox(row, member.result(), ignored -> member.address());
    }
    private synchronized byte[] encodeCheckpoint(CheckpointWrite row) {
        checkOpen(); var member = checkpoints.get(row); require(member != null, "Checkpoint row has no registered original result");
        return rows.encodeCheckpoint(row, member.result(), ignored -> member.address());
    }

    private long checkedCharge(ClosureProcessResult result, String address, int encodedBytes) {
        long members = Math.addExact((long) result.publicEvents().size(), result.checkpointWrites().size());
        long charge = Math.addExact(encodedBytes, Math.multiplyExact(128L, members + 1));
        require(charge <= limits.maximumScopeBytes() - retainedBytes, "Original result identity scope byte bound exceeded");
        checkMembers(result.publicEvents(), events, address); checkMembers(result.checkpointWrites(), checkpoints, address);
        return charge;
    }
    private <T> void checkMembers(List<T> rows, Map<T, Member> registered, String address) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        for (int index = 0; index < rows.size(); index++) {
            T row = rows.get(index); require(seen.add(row), "Original result repeats an identical row object");
            var old = registered.get(row);
            require(old == null || old.address().equals(address) && old.position() == index,
                    "One original row object has conflicting result provenance");
        }
    }
    private void register(ClosureProcessResult result, String address, long charge) {
        addresses.put(result, address); retainedBytes += charge;
        for (int i = 0; i < result.publicEvents().size(); i++) events.put(result.publicEvents().get(i), new Member(result, address, i));
        for (int i = 0; i < result.checkpointWrites().size(); i++) checkpoints.put(result.checkpointWrites().get(i), new Member(result, address, i));
    }
    private void checkOpen() { require(!closed, "Original result identity scope is closed"); }
    @Override public synchronized void close() { closed = true; opened.clear(); addresses.clear(); events.clear(); checkpoints.clear(); retainedBytes = 0; }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
