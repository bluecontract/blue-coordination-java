package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.lang.reflect.Field;
import java.util.*;

/** Test-only component replacement; never replays commands or reconstructs source authority. */
public final class CatchUpWorkStorageFixture {
    private static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(32 * 1024 * 1024, 16384, 30 * 1024 * 1024, 4096, 16);
    private final DefaultCoordinationEngine engine;
    private Bytes bytes = new Bytes();
    private StoredCatchUpWorkIndexes storage = new StoredCatchUpWorkIndexes(bytes, LIMITS, 256);

    public CatchUpWorkStorageFixture(CoordinationEngine engine) { this.engine = (DefaultCoordinationEngine) engine; }

    /** Fresh byte wrapper and row codecs, with the original current plans/barriers left untouched. */
    public void reopen() {
        try {
            Field field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true);
            var state = (InMemoryDocumentStore.StoreState) field.get(engine.documents());
            var original = state.catchUpPlans().storedState(); var retained = storage.retainPartition(original.work());
            var roots = new EnumMap<StoredCatchUpWorkIndexes.Root, byte[]>(StoredCatchUpWorkIndexes.Root.class);
            for (var root : StoredCatchUpWorkIndexes.Root.values()) roots.put(root, storage.root(retained, root));
            bytes = bytes.copy(); storage = new StoredCatchUpWorkIndexes(bytes, LIMITS, 256);
            var cold = storage.open(roots::get, retained.lastMutationComparisons(), retained.lastMutationNodeCopies());
            int writes = bytes.writes;
            for (var id : original.work().storedIndexes().work().keys()) storage.work(cold, id);
            for (var id : original.work().storedIndexes().applications().keys()) {
                var application = storage.application(cold, id).receipt();
                var work = storage.work(cold, application.workIdentity()).work();
                if (cold != cold.withApplication(work, application)) throw new AssertionError("Duplicate application mutated cold work indexes");
            }
            var selected = storage.nextDue(cold, Set.of(), id -> state.catchUpPlans().barrier(id).barrier(),
                    work -> engine.documents().managedEpochEvidence(work.sourceDocumentId(), work.sourceEpoch()).receipt());
            var before = original.work().nextDueWork();
            if (selected.found() != before.found() || selected.found() && !selected.work().workIdentity().equals(before.work().workIdentity())
                    || selected.indexRowsRead() != before.indexRowsRead() || selected.workRowsRead() != before.workRowsRead())
                throw new AssertionError("Cold component changed canonical due selection or logical counters");
            if (writes != bytes.writes) throw new AssertionError("Cold validation wrote storage");
            var changed = CatchUpPlanStore.restoreStored(new CatchUpPlanStore.StoredState(original.plans(), original.barriers(), cold,
                    original.activeBarriers(), original.comparisons(), original.copiedNodes()));
            field.set(engine.documents(), state.withCatchUpPlans(changed));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** Complete immutable application evidence including the associated original work and private representation causes. */
    public List<String> applications() {
        var rows = engine.documents().catchUpPlansSnapshot().storedState().work().storedIndexes().applications();
        return rows.values().stream().map(row -> Base64.getEncoder().encodeToString(storage.applicationsCodec.encode(row))).toList();
    }
    public long representationApplications() {
        return engine.documents().catchUpPlansSnapshot().storedState().work().storedIndexes().applications().values().stream()
                .filter(row -> row.receipt().representationCauseIdentity().isPresent()).count();
    }
    public long numberedSuccessorApplications() {
        return engine.documents().catchUpPlansSnapshot().storedState().work().storedIndexes().applications().values().stream()
                .filter(row -> row.receipt().successorRepresentationCauseIdentity().isPresent()).count();
    }

    /** Uses actual completed work/receipts to reject a mixed original work or altered representation cursor. */
    public void rejectMismatchedApplicationEvidence() {
        var rows = engine.documents().catchUpPlansSnapshot().storedState().work().storedIndexes().applications().values();
        var representation = rows.stream().filter(row -> row.receipt().representationCauseIdentity().isPresent()
                && row.receipt().resultingRepresentationCursor().isPresent()).findFirst().orElseThrow();
        var other = rows.stream().filter(row -> !row.work().workIdentity().equals(representation.work().workIdentity())).findFirst().orElseThrow();
        reject(applicationPacket(representation.receipt(), other.work(), representation.receipt().resultingRepresentationCursor().orElse(null)));
        var original = representation.receipt().resultingRepresentationCursor().orElseThrow();
        var changed = new blue.language.processor.closure.ManagedRepresentationCursor(original.anchorReceiptIdentity(), original.positionIdentity(),
                "sha256:" + "0".repeat(64), original.nextRevisionReceiptIdentity());
        reject(applicationPacket(representation.receipt(), representation.work(), changed));
    }
    private void reject(byte[] packet) {
        try { storage.applicationsCodec.decode(packet); }
        catch (CoordinationObjectStorageException expected) { return; }
        throw new AssertionError("Malformed exact application evidence was accepted");
    }
    private byte[] applicationPacket(blue.coordination.api.ManagedEpochApplicationReceipt a,
            blue.coordination.api.ManagedEpochApplicationWork w, blue.language.processor.closure.ManagedRepresentationCursor cursor) {
        return SessionStorageWire.encode(LIMITS.valueBytes(), out -> {
            new ManagedWorkStorageCodec(LIMITS.valueBytes(), 256).work(out, w);
            out.text(a.applicationReceiptIdentity()); out.text(a.workIdentity()); out.text(a.planIdentity()); out.text(a.sourceReceiptIdentity());
            out.text(a.contractsInvocationIdentity()); out.text(a.contractsResultIdentity()); out.text(a.commitCompanionIdentity());
            out.text(a.consumerDocumentId().value()); out.longValue(a.consumerRevisionEpoch()); out.text(a.consumerRevisionReceiptIdentity());
            out.text(a.consumerCommittedBlueId()); out.longValue(a.resultingSourceCursor());
            out.nullableText(a.representationCauseIdentity().orElse(null)); out.nullableText(a.successorRepresentationCauseIdentity().orElse(null));
            SessionRecordCodec.optional(out, cursor, (x, c) -> {
                x.text(c.anchorReceiptIdentity()); x.text(c.positionIdentity()); x.text(c.targetPositionIdentity()); x.nullableText(c.nextRevisionReceiptIdentity());
            });
        });
    }
    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> rows = new LinkedHashMap<>(); int writes;
        public byte[] putIfAbsent(String key, byte[] value) {
            writes++; byte[] existing = rows.putIfAbsent(key, value.clone()); return (existing == null ? value : existing).clone();
        }
        public Optional<byte[]> get(String key, int maximumBytes) {
            byte[] value = rows.get(key); if (value == null) return Optional.empty();
            if (value.length > maximumBytes) throw new CoordinationObjectStorageException("Physical byte bound");
            return Optional.of(value.clone());
        }
        Bytes copy() { var next = new Bytes(); rows.forEach((key, value) -> next.rows.put(key, value.clone())); return next; }
    }
}
