package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Physical current plan/barrier rows, not a complete catch-up store or a selection authority. */
final class StoredCatchUpPlanIndexes {
    enum Root { IDENTITY, CONSUMER, SOURCE, OCCURRENCE, BARRIER, ACTIVE_SOURCE }

    private final PersistentMapStorage.Limits limits;
    private final StoreIndexCodecs.Binding<String, ManagedOccurrenceCatchUpPlan> identities;
    private final StoreIndexCodecs.Binding<DocumentId, ManagedCatchUpPlanIndex.IdBucket> consumers, sources;
    private final StoreIndexCodecs.Binding<String, ManagedCatchUpPlanIndex.IdBucket> occurrences, planBarriers;
    private final StoreIndexCodecs.Binding<DocumentId, Integer> activeSources;
    private final StoreIndexCodecs.Binding<String, ManagedCatchUpBarrier> barriers;
    final PersistentMapCodec<ManagedOccurrenceCatchUpPlan> planCodec;
    final PersistentMapCodec<ManagedCatchUpBarrier> barrierCodec;

    StoredCatchUpPlanIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        this.limits = Objects.requireNonNull(limits);
        var c = new StoreIndexCodecs(objects, limits);
        planCodec = codec("plan", (w, p) -> {
            w.text(p.planIdentity()); w.text(p.snapshotIdentity()); w.text(p.barrierIdentity());
            w.text(p.consumerDocumentId().value()); w.text(p.targetOccurrenceIdentity()); w.text(p.targetPath());
            w.longValue(p.activationGeneration()); w.text(p.sourceDocumentId().value());
            w.longValue(p.admittedSourceEpoch()); w.text(p.admittedSourceBlueId());
            w.longValue(p.nextSourceEpoch()); w.longValue(p.requiredThroughSourceEpoch());
            w.text(p.causedByIdentity()); w.text(p.status().name());
            w.nullableText(p.waitingCode().orElse(null)); w.nullableText(p.waitingMessage().orElse(null));
        }, r -> new ManagedOccurrenceCatchUpPlan(text(r), text(r), text(r), DocumentId.of(text(r)), text(r), text(r),
                r.longValue(), DocumentId.of(text(r)), r.longValue(), text(r), r.longValue(), r.longValue(), text(r),
                ManagedCatchUpStatus.valueOf(text(r)), nullableText(r), nullableText(r)));
        barrierCodec = codec("barrier", (w, b) -> {
            w.text(b.barrierIdentity()); w.text(b.snapshotIdentity()); w.text(b.consumerDocumentId().value());
            w.text(b.causedByIdentity()); order(w, b.causeOrder()); list(w, b.planIdentities(), Writer::text);
            w.text(b.status().name()); w.nullableText(b.waitingCode().orElse(null)); w.nullableText(b.waitingMessage().orElse(null));
        }, r -> new ManagedCatchUpBarrier(text(r), text(r), DocumentId.of(text(r)), text(r), order(r),
                list(r, SessionRecordCodec::text), ManagedCatchUpBarrierStatus.valueOf(text(r)), nullableText(r), nullableText(r)));
        var membership = codec("membership", Writer::bool, r -> {
            require(r.bool(), "Stored plan membership must be true"); return Boolean.TRUE;
        });
        var members = c.binding("catch-up-plan/members", EmbeddingBinding.TEXT_ORDER, c.text, membership);
        var bucket = new PersistentMapCodec<ManagedCatchUpPlanIndex.IdBucket>() {
            public String identity() { return "blue-coordination/catch-up-plan-bucket/1"; }
            public ManagedCatchUpPlanIndex.IdBucket prepareForStorage(ManagedCatchUpPlanIndex.IdBucket value) {
                return ManagedCatchUpPlanIndex.IdBucket.restoreStored(members.retain(value.storedIdentities()),
                        value.lastMutationComparisons(), value.lastMutationNodeCopies());
            }
            public byte[] encode(ManagedCatchUpPlanIndex.IdBucket value) {
                return SessionStorageWire.encode(limits.valueBytes(), w -> {
                    w.bytes(value.storedIdentities().storedRootDescriptor());
                    w.integer(value.lastMutationComparisons()); w.integer(value.lastMutationNodeCopies());
                });
            }
            public ManagedCatchUpPlanIndex.IdBucket decode(byte[] bytes) {
                return SessionStorageWire.decode(bytes, limits.valueBytes(), r ->
                        ManagedCatchUpPlanIndex.IdBucket.restoreStored(members.open(r.bytes(r.remaining())), r.integer(), r.integer()));
            }
        };
        var count = codec("positive-active-source-count", Writer::integer, r -> {
            int value = r.integer(); require(value > 0, "Stored active plan count must be positive"); return value;
        });
        identities = c.binding("catch-up-plan/identity", EmbeddingBinding.TEXT_ORDER, c.text, planCodec);
        consumers = c.binding("catch-up-plan/consumer", EmbeddingBinding.DOCUMENT_ORDER, c.documents, bucket);
        sources = c.binding("catch-up-plan/source", EmbeddingBinding.DOCUMENT_ORDER, c.documents, bucket);
        occurrences = c.binding("catch-up-plan/occurrence", EmbeddingBinding.TEXT_ORDER, c.text, bucket);
        planBarriers = c.binding("catch-up-plan/barrier", EmbeddingBinding.TEXT_ORDER, c.text, bucket);
        activeSources = c.binding("catch-up-plan/active-source", EmbeddingBinding.DOCUMENT_ORDER, c.documents, count);
        barriers = c.binding("catch-up-barrier/identity", EmbeddingBinding.TEXT_ORDER, c.text, barrierCodec);
    }

    /** Explicit resident partition conversion; not an automatic whole-catalog startup operation. */
    ManagedCatchUpPlanIndex retainPartition(ManagedCatchUpPlanIndex index) {
        return physical(() -> {
            var s = index.storedIndexes();
            return ManagedCatchUpPlanIndex.restoreIndexes(new ManagedCatchUpPlanIndex.StoredIndexes(
                    identities.retain(s.identities()), consumers.retain(s.consumers()), sources.retain(s.sources()),
                    occurrences.retain(s.occurrences()), planBarriers.retain(s.barriers()), activeSources.retain(s.activeSources()),
                    s.comparisons(), s.copiedNodes()));
        });
    }

    ManagedCatchUpPlanIndex open(Function<Root, byte[]> selected, int comparisons, int copiedNodes) {
        return physical(() -> ManagedCatchUpPlanIndex.restoreIndexes(new ManagedCatchUpPlanIndex.StoredIndexes(
                identities.open(selected.apply(Root.IDENTITY)), consumers.open(selected.apply(Root.CONSUMER)),
                sources.open(selected.apply(Root.SOURCE)), occurrences.open(selected.apply(Root.OCCURRENCE)),
                planBarriers.open(selected.apply(Root.BARRIER)), activeSources.open(selected.apply(Root.ACTIVE_SOURCE)),
                comparisons, copiedNodes)));
    }

    byte[] root(ManagedCatchUpPlanIndex index, Root root) {
        var s = index.storedIndexes();
        return switch (root) {
            case IDENTITY -> s.identities().storedRootDescriptor(); case CONSUMER -> s.consumers().storedRootDescriptor();
            case SOURCE -> s.sources().storedRootDescriptor(); case OCCURRENCE -> s.occurrences().storedRootDescriptor();
            case BARRIER -> s.barriers().storedRootDescriptor(); case ACTIVE_SOURCE -> s.activeSources().storedRootDescriptor();
        };
    }

    PersistentOrderedMap<String, ManagedCatchUpBarrier> retainBarriers(PersistentOrderedMap<String, ManagedCatchUpBarrier> rows) {
        return barriers.retain(rows);
    }
    PersistentOrderedMap<String, ManagedCatchUpBarrier> openBarriers(byte[] descriptor) { return barriers.open(descriptor); }

    ManagedCatchUpPlanIndex.PlanRead exact(ManagedCatchUpPlanIndex index, String id) {
        return physical(() -> {
            var read = index.exact(id);
            if (read.found()) { require(id.equals(read.plan().planIdentity()), "Stored plan key differs"); requireMembership(index, read.plan()); }
            return read;
        });
    }

    ManagedCatchUpPlanIndex.PlanAudit forConsumer(ManagedCatchUpPlanIndex index, DocumentId id) {
        return physical(() -> checked(index, index.forConsumer(id), p -> p.consumerDocumentId().equals(id)));
    }

    ManagedCatchUpPlanIndex.PlanAudit forSource(ManagedCatchUpPlanIndex index, DocumentId id) {
        return physical(() -> {
            var audit = checked(index, index.forSource(id), p -> p.sourceDocumentId().equals(id));
            int count = Math.toIntExact(audit.plans().stream().filter(StoredCatchUpPlanIndexes::active).count());
            Integer retained = index.storedIndexes().activeSources().get(id);
            require(count == (retained == null ? 0 : retained), "Selected active source count differs from complete plan bucket");
            return audit;
        });
    }

    ManagedCatchUpPlanIndex.PlanAudit forOccurrence(ManagedCatchUpPlanIndex index, String id) {
        return physical(() -> checked(index, index.forOccurrence(id), p -> p.targetOccurrenceIdentity().equals(id)));
    }

    ManagedCatchUpPlanIndex.PlanAudit forBarrier(ManagedCatchUpPlanIndex index, String id) {
        return physical(() -> checked(index, index.forBarrier(id), p -> p.barrierIdentity().equals(id)));
    }

    ManagedCatchUpBarrier barrier(ManagedCatchUpPlanIndex index, PersistentOrderedMap<String, ManagedCatchUpBarrier> rows, String id) {
        return physical(() -> {
            ManagedCatchUpBarrier row = rows.get(id);
            var members = forBarrier(index, id);
            if (row == null) { require(members.plans().isEmpty(), "Selected plans have no retained barrier"); return null; }
            require(id.equals(row.barrierIdentity()), "Stored barrier key differs");
            for (var member : members.plans()) {
                require(member.consumerDocumentId().equals(row.consumerDocumentId()) && member.causedByIdentity().equals(row.causedByIdentity()),
                        "Stored barrier member belongs to another consumer/cause");
            }
            require(row.snapshotIdentity().equals(CatchUpPlanStore.canonicalBarrier(index, row).snapshotIdentity()),
                    "Stored barrier progress differs from complete selected member plans");
            return row;
        });
    }

    private ManagedCatchUpPlanIndex.PlanAudit checked(ManagedCatchUpPlanIndex index, ManagedCatchUpPlanIndex.PlanAudit audit,
            java.util.function.Predicate<ManagedOccurrenceCatchUpPlan> matches) {
        for (var p : audit.plans()) { require(matches.test(p), "Stored plan bucket has another owner"); requireMembership(index, p); }
        return audit;
    }

    void requireMembership(ManagedCatchUpPlanIndex index, ManagedOccurrenceCatchUpPlan p) {
        var s = index.storedIndexes();
        var primary = s.identities().get(p.planIdentity());
        require(primary != null && java.util.Arrays.equals(planCodec.encode(primary), planCodec.encode(p)), "Stored plan row differs from primary");
        require(contains(s.consumers().get(p.consumerDocumentId()), p.planIdentity())
                && contains(s.sources().get(p.sourceDocumentId()), p.planIdentity())
                && contains(s.occurrences().get(p.targetOccurrenceIdentity()), p.planIdentity())
                && contains(s.barriers().get(p.barrierIdentity()), p.planIdentity()), "Stored plan is missing an exact reverse membership");
        Integer count = s.activeSources().get(p.sourceDocumentId());
        require(!active(p) || count != null && count > 0, "Active plan is absent from its source count");
    }

    private static boolean contains(ManagedCatchUpPlanIndex.IdBucket bucket, String id) {
        return bucket != null && Boolean.TRUE.equals(bucket.storedIdentities().get(id));
    }
    private static boolean active(ManagedOccurrenceCatchUpPlan p) {
        return p.status() != ManagedCatchUpStatus.COMPLETE && p.status() != ManagedCatchUpStatus.CANCELLED_OCCURRENCE_RETIRED;
    }
    private <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> writer, Function<Reader, T> reader) {
        return new PersistentMapCodec<>() {
            public String identity() { return "blue-coordination/catch-up-index/" + name + "/1"; }
            public byte[] encode(T value) { return SessionStorageWire.encode(limits.valueBytes(), w -> writer.accept(w, value)); }
            public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.valueBytes(), reader); }
        };
    }
}
