package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.processor.closure.SubscriptionState;
import java.util.*;
import java.util.function.Function;
import static blue.coordination.internal.SessionStorageWire.*;

/** Selected complete subscription slots and demand rows; preserves existing mutation order/counters. */
final class StoredSubscriptionIndexes {
    enum Root { SLOT, IDENTITY, DOCUMENT, DEMAND }
    private final StoreIndexCodecs.Binding<ClosureSubscriptionInventory.Slot, SubscriptionState> slots;
    private final StoreIndexCodecs.Binding<String, ClosureSubscriptionInventory.Slot> identities;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<String, SubscriptionState>> documents;
    private final StoreIndexCodecs.Binding<DocumentId, PersistentOrderedMap<String, ClosureSubscriptionInventory.EmbeddedDemand>> demands;
    private final PersistentMapCodec<SubscriptionState> rows;
    private final StoreIndexCodecs.Binding<String, SubscriptionState> documentRows;
    private final StoreIndexCodecs.Binding<String, ClosureSubscriptionInventory.EmbeddedDemand> demandRows;

    StoredSubscriptionIndexes(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits); rows = c.subscriptions;
        slots = c.binding("subscription/slot", ClosureSubscriptionInventory.SLOT_ORDER, c.subscriptionSlots, rows);
        identities = c.binding("subscription/identity", EmbeddingBinding.TEXT_ORDER, c.text, c.subscriptionSlots);
        documentRows = c.binding("subscription/document-bucket", EmbeddingBinding.TEXT_ORDER, c.text, rows);
        documents = c.binding("subscription/document", EmbeddingBinding.TEXT_ORDER, c.text, documentRows.nested());
        demandRows = c.binding("subscription/demand-bucket", EmbeddingBinding.TEXT_ORDER, c.text, c.embeddedDemands);
        demands = c.binding("subscription/demand", EmbeddingBinding.DOCUMENT_ORDER, c.documents, demandRows.nested());
    }
    ClosureSubscriptionInventory openLogical(LogicalRecordContext context) {
        var scope = LogicalRecordContext.runtimeScope();
        var slotKeys = OrderedRecordKey.pair("subscription-slot", OrderedRecordKey.text(), OrderedRecordKey.text(),
                ClosureSubscriptionInventory.Slot::documentId, ClosureSubscriptionInventory.Slot::rawChannelKey, ClosureSubscriptionInventory.Slot::new);
        return ClosureSubscriptionInventory.restoreIndexes(new ClosureSubscriptionInventory.StoredIndexes(
                slots.openLogical(context, Family.SUBSCRIPTION_SLOT, scope, slotKeys),
                identities.openLogical(context, Family.SUBSCRIPTION_IDENTITY, scope, OrderedRecordKey.text()),
                documentRows.openLogicalBuckets(context, Family.SUBSCRIPTION_DOCUMENT, scope, EmbeddingBinding.TEXT_ORDER, OrderedRecordKey.text(), OrderedRecordKey.text()),
                demandRows.openLogicalBuckets(context, Family.SUBSCRIPTION_DEMAND, scope, EmbeddingBinding.DOCUMENT_ORDER, OrderedRecordKey.document(), OrderedRecordKey.text()),
                0, 0, 0));
    }

    void selectLogical(ClosureSubscriptionInventory value) {
        var s = value.storedIndexes(); s.slots().selectLogicalRecords(); s.identities().selectLogicalRecords();
        s.documents().selectLogicalRecords(); s.demands().selectLogicalRecords();
    }

    ClosureSubscriptionInventory retainPartition(ClosureSubscriptionInventory value) {
        return physical(() -> {
            var s = value.storedIndexes(); return ClosureSubscriptionInventory.restoreIndexes(new ClosureSubscriptionInventory.StoredIndexes(
                    slots.retain(s.slots()), identities.retain(s.identities()), documents.retain(s.documents()), demands.retain(s.demands()),
                    s.comparisons(), s.copiedNodes(), s.visitedRows()));
        });
    }
    ClosureSubscriptionInventory open(Function<Root, byte[]> selected, int comparisons, int copiedNodes, int visitedRows) {
        return physical(() -> ClosureSubscriptionInventory.restoreIndexes(new ClosureSubscriptionInventory.StoredIndexes(
                slots.open(selected.apply(Root.SLOT)), identities.open(selected.apply(Root.IDENTITY)), documents.open(selected.apply(Root.DOCUMENT)),
                demands.open(selected.apply(Root.DEMAND)), comparisons, copiedNodes, visitedRows)));
    }
    byte[] root(ClosureSubscriptionInventory value, Root root) {
        var s = value.storedIndexes(); return switch (root) {
            case SLOT -> s.slots().storedRootDescriptor(); case IDENTITY -> s.identities().storedRootDescriptor();
            case DOCUMENT -> s.documents().storedRootDescriptor(); case DEMAND -> s.demands().storedRootDescriptor();
        };
    }
    List<SubscriptionState> statesFor(ClosureSubscriptionInventory value, DocumentId document) {
        return physical(() -> {
            var s = value.storedIndexes(); var bucket = s.documents().get(document.value()); if (bucket == null) return List.of();
            var result = new ArrayList<SubscriptionState>();
            for (var entry : bucket.entries()) {
                var row = entry.getValue(); var slot = ClosureSubscriptionInventory.Slot.from(row);
                require(slot.documentId().equals(document.value()) && slot.rawChannelKey().equals(entry.getKey())
                        && slot.equals(s.identities().get(row.subscriptionIdentity())) && same(row, s.slots().get(slot)),
                        "Selected subscription slot/identity/document membership differs");
                result.add(row);
            }
            return List.copyOf(result);
        });
    }
    List<ClosureSubscriptionInventory.EmbeddedDemand> demandsFor(ClosureSubscriptionInventory value, DocumentId document) {
        return physical(() -> {
            var bucket = value.storedIndexes().demands().get(document); if (bucket == null) return List.of();
            for (var entry : bucket.entries()) require(entry.getKey().equals(entry.getValue().rawChannelKey()), "Selected embedded-demand slot differs");
            return bucket.values();
        });
    }
    private boolean same(SubscriptionState a, SubscriptionState b) { return b != null && Arrays.equals(rows.encode(a), rows.encode(b)); }
}
