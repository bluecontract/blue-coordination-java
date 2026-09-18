package blue.coordination.internal;

import blue.coordination.api.*;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Closed current work/application rows; caller-pinned roots and selected source evidence remain required. */
final class StoredCatchUpWorkIndexes {
    enum Root { WORK, PENDING, APPLICATION, APPLICATION_BY_WORK, DUE }
    private final CoordinationImmutableObjectStore objects;
    private final PersistentMapStorage.Limits limits;
    private final ManagedWorkStorageCodec workRows;
    private final PersistentMapCodec<ManagedCatchUpWorkIndex.DueKey> dueKeys;
    private final PersistentMapCodec<String> textRows;
    final PersistentMapCodec<ManagedCatchUpWorkIndex.RegisteredApplication> applicationsCodec;
    private final StoreIndexCodecs.Binding<String, ManagedCatchUpWorkIndex.RegisteredWork> work;
    private final StoreIndexCodecs.Binding<String, String> pending, applicationsByWork;
    private final StoreIndexCodecs.Binding<String, ManagedCatchUpWorkIndex.RegisteredApplication> applications;
    private static final String DUE_BINDING = "blue-coordination/index/catch-up-work/due/1";

    StoredCatchUpWorkIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits, int maximumDepth) {
        this(objects, limits, maximumDepth, null);
    }

    StoredCatchUpWorkIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits, int maximumDepth,
            RootedStorageCache cache) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        workRows = new ManagedWorkStorageCodec(limits.valueBytes(), maximumDepth, cache);
        var c = new StoreIndexCodecs(objects, limits); textRows = c.text;
        dueKeys = codec("due-key", (w, k) -> {
            order(w, k.barrierCauseOrder()); order(w, k.sourceOrder()); w.text(k.sourceDocumentId().value());
            w.longValue(k.sourceEpoch()); w.text(k.consumerDocumentId().value()); w.text(k.targetPath()); w.longValue(k.activationGeneration());
        }, r -> new ManagedCatchUpWorkIndex.DueKey(order(r), order(r), DocumentId.of(text(r)), r.longValue(), DocumentId.of(text(r)), text(r), r.longValue()));
        PersistentMapCodec<ManagedCatchUpWorkIndex.RegisteredWork> registered = codec("registered-work", (w, value) -> {
            workRows.work(w, value.work()); w.bytes(dueKeys.encode(value.dueKey()));
        }, r -> {
            var value = new ManagedCatchUpWorkIndex.RegisteredWork(workRows.work(r), dueKeys.decode(r.bytes(limits.keyBytes())));
            requireCoordinates(value); return value;
        });
        var applicationRows = new ManagedApplicationStorageCodec(limits.valueBytes(), maximumDepth, cache);
        registered = new CanonicalStorageCodec<>(registered, cache, limits.valueBytes(), maximumDepth,
                "keyBytes=" + limits.keyBytes());
        applicationsCodec = new CanonicalStorageCodec<>(codec("application", applicationRows::application,
                applicationRows::application), cache, limits.valueBytes(), maximumDepth);
        work = c.binding("catch-up-work/work", EmbeddingBinding.TEXT_ORDER, c.text, registered);
        pending = c.binding("catch-up-work/pending", EmbeddingBinding.TEXT_ORDER, c.text, c.text);
        applications = c.binding("catch-up-work/application", EmbeddingBinding.TEXT_ORDER, c.text, applicationsCodec);
        applicationsByWork = c.binding("catch-up-work/application-by-work", EmbeddingBinding.TEXT_ORDER, c.text, c.text);
    }

    ManagedCatchUpWorkIndex retainPartition(ManagedCatchUpWorkIndex index) {
        return physical(() -> {
            var s = index.storedIndexes();
            return ManagedCatchUpWorkIndex.restoreIndexes(new ManagedCatchUpWorkIndex.StoredIndexes(work.retain(s.work()),
                    pending.retain(s.pending()), applications.retain(s.applications()), applicationsByWork.retain(s.applicationsByWork()),
                    s.due().storedCopy(DUE_BINDING, dueKeys, textRows, objects, limits), s.comparisons(), s.copiedNodes()));
        });
    }
    ManagedCatchUpWorkIndex open(Function<Root, byte[]> selected, int comparisons, int copiedNodes) {
        return physical(() -> ManagedCatchUpWorkIndex.restoreIndexes(new ManagedCatchUpWorkIndex.StoredIndexes(
                work.open(selected.apply(Root.WORK)), pending.open(selected.apply(Root.PENDING)), applications.open(selected.apply(Root.APPLICATION)),
                applicationsByWork.open(selected.apply(Root.APPLICATION_BY_WORK)), PersistentMinimumMap.stored(ManagedCatchUpWorkIndex.DUE_ORDER,
                        DUE_BINDING, dueKeys, textRows, objects, limits, selected.apply(Root.DUE)), comparisons, copiedNodes)));
    }
    byte[] root(ManagedCatchUpWorkIndex index, Root root) {
        var s = index.storedIndexes();
        return switch (root) {
            case WORK -> s.work().storedRootDescriptor(); case PENDING -> s.pending().storedRootDescriptor();
            case APPLICATION -> s.applications().storedRootDescriptor(); case APPLICATION_BY_WORK -> s.applicationsByWork().storedRootDescriptor();
            case DUE -> s.due().storedRootDescriptor();
        };
    }

    ManagedCatchUpWorkIndex.WorkRead work(ManagedCatchUpWorkIndex index, String id) {
        return physical(() -> {
            var selected = index.work(id);
            if (selected.found()) requireWork(index, id, selected.work());
            return selected;
        });
    }
    ManagedCatchUpWorkIndex.WorkRead pending(ManagedCatchUpWorkIndex index, String planId) {
        return physical(() -> {
            var selected = index.pendingWorkForPlan(planId);
            if (selected.found()) {
                require(planId.equals(selected.work().planIdentity()), "Pending work belongs to another plan");
                requirePending(index, selected.work());
            }
            return selected;
        });
    }
    ManagedCatchUpWorkIndex.ApplicationRead application(ManagedCatchUpWorkIndex index, String id) {
        return physical(() -> {
            var read = index.application(id);
            if (read.found()) requireApplication(index, id, read.receipt());
            return read;
        });
    }
    ManagedCatchUpWorkIndex.ApplicationRead applicationByWork(ManagedCatchUpWorkIndex index, String workId) {
        return physical(() -> {
            var read = index.applicationByWork(workId);
            if (read.found()) {
                require(workId.equals(read.receipt().workIdentity()), "Application-by-work selects another work");
                requireApplication(index, read.receipt().applicationReceiptIdentity(), read.receipt());
            }
            return read;
        });
    }

    /** No new selection: validates the existing minimum result against its exact immutable source/barrier. */
    ManagedCatchUpWorkIndex.DueWorkRead nextDue(ManagedCatchUpWorkIndex index, Set<DocumentId> excluded,
            Function<String, ManagedCatchUpBarrier> barriers, Function<ManagedEpochApplicationWork, ManagedEpochReceipt> sourceReceipts) {
        return physical(() -> {
            var selected = index.nextDueWorkExcluding(excluded);
            if (selected.found()) {
                var value = requirePending(index, selected.work());
                var barrier = Objects.requireNonNull(barriers.apply(selected.work().barrierIdentity()), "selected barrier");
                var source = Objects.requireNonNull(sourceReceipts.apply(selected.work()), "selected source receipt");
                require(barrier.barrierIdentity().equals(selected.work().barrierIdentity())
                        && barrier.consumerDocumentId().equals(selected.work().consumerDocumentId())
                        && barrier.planIdentities().contains(selected.work().planIdentity())
                        && source.receiptIdentity().equals(selected.work().sourceReceiptIdentity())
                        && source.documentId().equals(selected.work().sourceDocumentId()) && source.epoch() == selected.work().sourceEpoch()
                        && value.dueKey().barrierCauseOrder().equals(barrier.causeOrder())
                        && value.dueKey().sourceOrder().equals(source.sourceOrder().orElseThrow()),
                        "Selected due key differs from its exact source/barrier order");
            }
            return selected;
        });
    }

    ManagedCatchUpWorkIndex.RegisteredWork requirePending(ManagedCatchUpWorkIndex index, ManagedEpochApplicationWork selected) {
        var value = requireWork(index, selected.workIdentity(), selected); var s = index.storedIndexes();
        require(selected.workIdentity().equals(s.pending().get(selected.planIdentity()))
                && selected.workIdentity().equals(s.due().read(value.dueKey()).value())
                && s.applicationsByWork().get(selected.workIdentity()) == null,
                "Selected due work is missing, mismatched, or already applied");
        return value;
    }
    ManagedCatchUpWorkIndex.RegisteredWork requireWork(ManagedCatchUpWorkIndex index, String id, ManagedEpochApplicationWork selected) {
        var s = index.storedIndexes(); var row = s.work().get(id);
        require(row != null && id.equals(row.work().workIdentity()) && sameWork(row.work(), selected),
                "Work index selects different complete work evidence");
        requireCoordinates(row);
        boolean pendingHere = id.equals(s.pending().get(row.work().planIdentity()));
        boolean dueHere = id.equals(s.due().read(row.dueKey()).value());
        require(pendingHere == dueHere, "Work has inconsistent pending/due memberships");
        require(!pendingHere || s.applicationsByWork().get(id) == null, "Applied work still owns a due slot");
        return row;
    }
    void requireApplication(ManagedCatchUpWorkIndex index, String id, ManagedEpochApplicationReceipt selected) {
        var s = index.storedIndexes(); var row = s.applications().get(id);
        require(row != null && id.equals(row.receipt().applicationReceiptIdentity()) && id.equals(s.applicationsByWork().get(row.work().workIdentity()))
                && selected.applicationReceiptIdentity().equals(id), "Application indexes select different receipt/work associations");
        var original = requireWork(index, row.work().workIdentity(), row.work());
        require(sameWork(original.work(), row.work()), "Application retained another original work");
        require(!row.work().workIdentity().equals(s.pending().get(row.work().planIdentity()))
                && !row.work().workIdentity().equals(s.due().read(original.dueKey()).value()), "Applied work remains pending");
    }
    private boolean sameWork(ManagedEpochApplicationWork first, ManagedEpochApplicationWork second) {
        // These are immutable complete values. Identity is sufficient only for
        // the same object, never for two values carrying the same logical work ID.
        return first == second || Arrays.equals(workRows.encode(first), workRows.encode(second));
    }
    private static void requireCoordinates(ManagedCatchUpWorkIndex.RegisteredWork row) {
        var w = row.work(); var k = row.dueKey();
        require(w.sourceDocumentId().equals(k.sourceDocumentId()) && w.sourceEpoch() == k.sourceEpoch()
                && w.consumerDocumentId().equals(k.consumerDocumentId()) && w.targetPath().equals(k.targetPath())
                && w.activationGeneration() == k.activationGeneration(), "Registered due key differs from exact work coordinates");
    }

    private <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> writer, Function<Reader, T> reader) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/catch-up-work/" + name + "/1"; }
            public byte[] encode(T value) { return SessionStorageWire.encode(limits.valueBytes(), w -> writer.accept(w, value)); }
            public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.valueBytes(), reader); }
        };
    }
}
