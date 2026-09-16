package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRepresentationCursor;
import blue.language.processor.closure.ScopeAddress;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Closed current-rooted index rows. Codec reads/re-encodings never retain or execute a session. */
final class StoreIndexCodecs {
    private final CoordinationImmutableObjectStore objects;
    private final PersistentMapStorage.Limits limits;
    final PersistentMapCodec<DocumentId> documents;
    final PersistentMapCodec<String> text;
    final PersistentMapCodec<Long> generations;
    final PersistentMapCodec<SessionAddress> sessions;
    final PersistentMapCodec<ManagedLineageIndex.Lineage> lineages;
    final PersistentMapCodec<ManagedLineageIndex.RetainedKey> retainedKeys;
    final PersistentMapCodec<ManagedLineageIndex.RetainedState> retainedStates;
    final PersistentMapCodec<ManagedOccurrenceInventory.OccurrenceKey> occurrenceKeys;
    final PersistentMapCodec<ManagedOccurrenceInventory.RowOrderKey> occurrenceOrder;
    final PersistentMapCodec<ManagedOccurrenceBinding> occurrences;
    final PersistentMapCodec<Boolean> membership;
    final PersistentMapCodec<ProcessEmbeddedComponentIndex.Component> components;
    final PersistentMapCodec<ClosureSubscriptionInventory.Slot> subscriptionSlots;
    final PersistentMapCodec<blue.language.processor.closure.SubscriptionState> subscriptions;
    final PersistentMapCodec<ClosureSubscriptionInventory.EmbeddedDemand> embeddedDemands;

    record SessionAddress(DocumentId documentId, String address) {
        SessionAddress {
            Objects.requireNonNull(documentId, "documentId");
            require(address != null && address.matches("[0-9a-f]{64}"), "Invalid selected session address");
        }
    }

    StoreIndexCodecs(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        this.objects = Objects.requireNonNull(objects); this.limits = Objects.requireNonNull(limits);
        documents = codec("document-id", (w, id) -> w.text(id.value()), r -> DocumentId.of(text(r)));
        text = codec("text", Writer::text, r -> {
            String value = text(r); require(!value.isBlank(), "Blank exact index identity"); return value;
        });
        generations = codec("graph-generation", Writer::longValue, r ->
                MultiDocumentPublicationTransaction.requireSafeInteger(r.longValue(), "graphGeneration"));
        sessions = codec("session-address", (w, row) -> { w.text(row.documentId().value()); w.text(row.address()); },
                r -> new SessionAddress(DocumentId.of(text(r)), text(r)));
        retainedKeys = codec("retained-key", (w, row) -> { w.text(row.documentId().value()); w.longValue(row.epoch()); },
                r -> new ManagedLineageIndex.RetainedKey(DocumentId.of(text(r)), r.longValue()));
        retainedStates = codec("retained-state", StoreIndexCodecs::retainedState, StoreIndexCodecs::retainedState);
        lineages = codec("lineage", (w, row) -> {
            w.text(row.documentId().value()); w.text(row.authoredInitialBlueId()); w.text(row.initializedBlueId());
            w.longValue(row.currentEpoch()); w.text(row.currentBlueId());
            list(w, row.retainedStates(), StoreIndexCodecs::retainedState); w.longValue(row.lastAnchoredNonReplayableEpoch());
        }, r -> {
            var row = new ManagedLineageIndex.Lineage(DocumentId.of(text(r)), text(r), text(r), r.longValue(), text(r),
                    list(r, StoreIndexCodecs::retainedState), r.longValue());
            long epoch = 0;
            for (var state : row.retainedStates()) {
                require(state.documentId().equals(row.documentId()) && state.epoch() == epoch++, "Stored lineage is not contiguous for its owner");
            }
            require(row.initializedBlueId().equals(row.retainedStates().get(0).blueId()), "Stored lineage initialization differs");
            return row;
        });
        occurrenceKeys = codec("occurrence-key", (w, row) -> { w.text(row.sourceDocumentId().value()); w.text(row.sourcePath()); },
                r -> ManagedOccurrenceInventory.OccurrenceKey.of(DocumentId.of(text(r)), text(r)));
        occurrenceOrder = codec("occurrence-order", (w, row) -> { w.text(row.occurrenceIdentity()); w.text(row.bindingIdentity()); },
                r -> new ManagedOccurrenceInventory.RowOrderKey(text(r), text(r)));
        occurrences = codec("occurrence", StoreIndexCodecs::occurrence, StoreIndexCodecs::occurrence);
        membership = codec("membership", (w, value) -> { require(Boolean.TRUE.equals(value), "Index membership must be true"); w.bool(true); },
                r -> { require(r.bool(), "Index membership must be true"); return true; });
        components = codec("topology-component", (w, value) -> { list(w, value.members(), (x, id) -> x.text(id.value())); w.bool(value.cyclic()); },
                r -> {
                    var members = list(r, x -> DocumentId.of(text(x)));
                    for (int i = 1; i < members.size(); i++) require(EmbeddingBinding.DOCUMENT_ORDER.compare(members.get(i - 1), members.get(i)) < 0,
                            "Stored component members are not canonically ordered");
                    boolean cyclic = r.bool(); require(cyclic || members.size() == 1, "Acyclic stored component has multiple members");
                    return new ProcessEmbeddedComponentIndex.Component(members, cyclic);
                });
        subscriptionSlots = codec("subscription-slot", (w, value) -> { w.text(value.documentId()); w.text(value.rawChannelKey()); },
                r -> new ClosureSubscriptionInventory.Slot(text(r), text(r)));
        subscriptions = codec("subscription-state", (w, row) -> {
            w.text(row.subscriptionIdentity()); var channel = row.channelOccurrence();
            w.text(channel.channelOccurrenceIdentity()); w.text(channel.managedDocumentId().value()); w.text(channel.scopePath());
            w.longValue(channel.scopeActivationGeneration()); w.text(channel.rawChannelKey()); w.text(channel.effectiveRuntimeContributionBlueId());
            w.text(channel.subscriptionHeaderBlueId()); w.text(row.documentBlueId()); w.longValue(row.graphGeneration()); w.longValue(row.componentGeneration());
        }, r -> new blue.language.processor.closure.SubscriptionState(text(r), new blue.language.processor.closure.ChannelOccurrence(text(r),
                new blue.language.processor.closure.DocumentId(text(r)), text(r), r.longValue(), text(r), text(r), text(r)), text(r), r.longValue(), r.longValue()));
        embeddedDemands = codec("embedded-demand", (w, row) -> {
            w.text(row.rawChannelKey()); w.text(row.selectorPath()); w.text(row.mode().name()); w.text(row.effectiveRuntimeContributionBlueId());
        }, r -> new ClosureSubscriptionInventory.EmbeddedDemand(text(r), text(r), ClosureSubscriptionInventory.EmbeddedDemandMode.valueOf(text(r)), text(r)));
    }

    static void occurrence(Writer w, ManagedOccurrenceBinding row) {
        w.text(row.occurrenceIdentity()); w.text(row.bindingIdentity()); w.text(row.bindingPolicyIdentity());
        w.text(row.sourceDocumentId().value()); w.text(row.sourceAddress().path()); w.longValue(row.sourceAddress().activationGeneration());
        w.text(row.targetDocumentId().value()); w.text(row.expectedTargetBlueId()); w.bool(row.active());
        optional(w, row.pendingHistoricalEpoch(), Writer::longValue);
        optional(w, row.pendingRepresentationCursor(), (x, cursor) -> {
            x.text(cursor.anchorReceiptIdentity()); x.text(cursor.positionIdentity()); x.text(cursor.targetPositionIdentity());
            x.nullableText(cursor.nextRevisionReceiptIdentity());
        });
    }
    static ManagedOccurrenceBinding occurrence(Reader r) {
        var row = ManagedOccurrenceBinding.verified(text(r), text(r), text(r),
                new blue.language.processor.closure.DocumentId(text(r)), ScopeAddress.embedded(text(r), r.longValue()),
                new blue.language.processor.closure.DocumentId(text(r)), text(r), r.bool(), optional(r, Reader::longValue));
        return row.withRepresentationCursor(optional(r, x -> new ManagedRepresentationCursor(text(x), text(x), text(x), nullableText(x))));
    }

    private static void retainedState(Writer out, ManagedLineageIndex.RetainedState row) {
        out.text(row.documentId().value()); out.longValue(row.epoch()); out.text(row.blueId());
    }
    private static ManagedLineageIndex.RetainedState retainedState(Reader in) {
        return new ManagedLineageIndex.RetainedState(DocumentId.of(text(in)), in.longValue(), text(in));
    }

    <T> PersistentMapCodec<T> codec(String name, BiConsumer<Writer, T> writer, Function<Reader, T> reader) {
        return new PersistentMapCodec<>() {
            @Override public String identity() { return "blue-coordination/store-index/" + name + "/1"; }
            @Override public byte[] encode(T value) { return SessionStorageWire.encode(limits.valueBytes(), w -> writer.accept(w, value)); }
            @Override public T decode(byte[] bytes) { return SessionStorageWire.decode(bytes, limits.valueBytes(), reader); }
        };
    }

    <K, V> Binding<K, V> binding(String name, Comparator<? super K> order, PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
        return new Binding<>(name, order, keys, values);
    }

    final class Binding<K, V> {
        private final String identity;
        private final Comparator<? super K> order;
        private final PersistentMapCodec<K> keys;
        private final PersistentMapCodec<V> values;
        Binding(String name, Comparator<? super K> order, PersistentMapCodec<K> keys, PersistentMapCodec<V> values) {
            this.identity = "blue-coordination/index/" + name + "/1";
            this.order = order; this.keys = keys; this.values = values;
        }
        PersistentOrderedMap<K, V> open(byte[] descriptor) {
            return PersistentOrderedMap.stored(order, identity, keys, values, objects, limits, descriptor);
        }
        PersistentOrderedMap<K, V> retain(PersistentOrderedMap<K, V> map) {
            return map.storedCopy(identity, keys, values, objects, limits);
        }
        PersistentMapCodec<PersistentOrderedMap<K, V>> nested() {
            return new PersistentMapCodec<>() {
                @Override public String identity() { return "blue-coordination/index-root/" + Binding.this.identity; }
                @Override public PersistentOrderedMap<K, V> prepareForStorage(PersistentOrderedMap<K, V> value) { return retain(value); }
                @Override public byte[] encode(PersistentOrderedMap<K, V> value) { return value.storedRootDescriptor(); }
                @Override public PersistentOrderedMap<K, V> decode(byte[] bytes) { return open(bytes); }
            };
        }
    }
}
