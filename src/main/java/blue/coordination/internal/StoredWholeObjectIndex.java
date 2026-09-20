package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.Family;
import blue.language.identity.BlueIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import static blue.coordination.internal.SessionStorageWire.*;

/** Three selected physical indexes beneath WholeObjectStorage; never a publication authority. */
final class StoredWholeObjectIndex {
    record Selection(byte[] entries, byte[] proofs, byte[] members) {
        Selection { entries = entries.clone(); proofs = proofs.clone(); members = members.clone(); }
        @Override public byte[] entries() { return entries.clone(); }
        @Override public byte[] proofs() { return proofs.clone(); }
        @Override public byte[] members() { return members.clone(); }
    }
    private final StoreIndexCodecs.Binding<String, String> entries;
    private final StoreIndexCodecs.Binding<String, String> proofs;
    private final StoreIndexCodecs.Binding<String, PersistentOrderedMap<String, Boolean>> members;
    private final StoreIndexCodecs.Binding<String, Boolean> memberSet;

    StoredWholeObjectIndex(CoordinationImmutableObjectStore objects, PersistentMapStorage.Limits limits) {
        var c = new StoreIndexCodecs(objects, limits);
        var address = c.codec("whole-object/address", (Writer w, String value) -> {
            require(value.matches("[0-9a-f]{64}"), "Invalid exact-object physical address"); w.text(value);
        }, r -> {
            String value = r.text(r.remaining());
            require(value.matches("[0-9a-f]{64}"), "Invalid exact-object physical address"); return value;
        });
        entries = c.binding("whole-object/entries", EmbeddingBinding.TEXT_ORDER, c.text, address);
        proofs = c.binding("whole-object/proofs", EmbeddingBinding.TEXT_ORDER, c.text, address);
        memberSet = c.binding("whole-object/member-set", EmbeddingBinding.TEXT_ORDER, c.text, c.membership);
        members = c.binding("whole-object/members", EmbeddingBinding.TEXT_ORDER, c.text, memberSet.nested());
    }

    Opened openLogical(LogicalRecordContext context) {
        var scope = LogicalRecordContext.runtimeScope();
        return new Opened(entries.openLogical(context, Family.OBJECT_ENTRY, scope, OrderedRecordKey.text()),
                proofs.openLogical(context, Family.OBJECT_PROOF, scope, OrderedRecordKey.text()),
                memberSet.openLogicalBuckets(context, Family.OBJECT_MEMBER, scope, EmbeddingBinding.TEXT_ORDER,
                        OrderedRecordKey.text(), OrderedRecordKey.text()));
    }

    Opened empty() { return new Opened(entries.open(null), proofs.open(null), members.open(null)); }
    Opened open(Selection selected) {
        Objects.requireNonNull(selected);
        return new Opened(entries.open(selected.entries()), proofs.open(selected.proofs()), members.open(selected.members()));
    }

    final class Opened implements WholeObjectStorage.Index {
        private final PersistentOrderedMap<String, String> entryRows;
        private final PersistentOrderedMap<String, String> proofRows;
        private final PersistentOrderedMap<String, PersistentOrderedMap<String, Boolean>> memberRows;
        private Opened(PersistentOrderedMap<String, String> entries, PersistentOrderedMap<String, String> proofs,
                PersistentOrderedMap<String, PersistentOrderedMap<String, Boolean>> members) {
            entryRows = entries; proofRows = proofs; memberRows = members;
        }
        @Override public Optional<String> entryAddress(String blueId) { return Optional.ofNullable(entryRows.get(blueId)); }
        @Override public Optional<String> proofAddress(String master) { return Optional.ofNullable(proofRows.get(master)); }
        @Override public int size() { return entryRows.size(); }
        @Override public Iterable<String> cyclicMembers(String master) {
            return physical(() -> {
                var selected = memberRows.get(master); if (selected == null || selected.isEmpty()) return List.of();
                require(proofRows.containsKey(master), "Retained cyclic member set has no proof");
                var result = new ArrayList<String>();
                for (var row : selected.entries()) {
                    String id = row.getKey();
                    require(Boolean.TRUE.equals(row.getValue()) && master.equals(BlueIds.cyclicSetMasterBlueId(id))
                            && entryRows.containsKey(id), "Cyclic member index has foreign or missing object");
                    result.add(id);
                }
                return List.copyOf(result);
            });
        }
        void selectLogical() { entryRows.selectLogicalRecords(); proofRows.selectLogicalRecords(); memberRows.selectLogicalRecords(); }
        Selection selection() { return new Selection(entryRows.storedRootDescriptor(), proofRows.storedRootDescriptor(), memberRows.storedRootDescriptor()); }

        /** Stage a new immutable view; even a later write failure cannot change this selected view. */
        Opened stage(WholeObjectStorage.References delta) {
            return physical(() -> {
                var newEntries = entryRows; var newProofs = proofRows; var newMembers = memberRows;
                for (var row : new java.util.TreeMap<>(delta.entries()).entrySet()) newEntries = newEntries.put(row.getKey(), row.getValue()).map();
                for (var row : new java.util.TreeMap<>(delta.proofs()).entrySet()) newProofs = newProofs.put(row.getKey(), row.getValue()).map();
                for (var row : new java.util.TreeMap<>(delta.cyclicMembers()).entrySet()) {
                    String master = row.getKey(); require(newProofs.containsKey(master), "New cyclic membership lacks original proof");
                    var selected = newMembers.get(master); if (selected == null) selected = memberSet.open(null);
                    for (String id : row.getValue()) {
                        require(master.equals(BlueIds.cyclicSetMasterBlueId(id)) && newEntries.containsKey(id), "Foreign or missing cyclic member");
                        selected = selected.put(id, true).map();
                    }
                    if (selected.isLogical() || !selected.isEmpty()) newMembers = newMembers.put(master, selected).map();
                }
                return new Opened(newEntries, newProofs, newMembers);
            });
        }
    }
}
