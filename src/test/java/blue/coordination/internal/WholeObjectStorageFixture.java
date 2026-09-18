package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.language.provider.CyclicSetProof;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Test owner of metadata publication; production codec and bytes are exercised unchanged. */
final class WholeObjectStorageFixture implements CoordinationImmutableObjectStore {
    final Map<String, byte[]> objects = new LinkedHashMap<>();
    final WholeObjectStorage storage = new WholeObjectStorage(this, 1024 * 1024, 128);
    private Index selected = new Index(Map.of(), Map.of(), Map.of());
    int physicalReads;

    @Override public byte[] putIfAbsent(String address, byte[] bytes) {
        byte[] existing = objects.putIfAbsent(address, bytes.clone());
        if (existing != null && !Arrays.equals(existing, bytes)) throw new IllegalStateException("conflict");
        return bytes.clone();
    }
    @Override public Optional<byte[]> get(String address, int maximumBytes) {
        physicalReads++;
        byte[] bytes = objects.get(address);
        if (bytes != null && bytes.length > maximumBytes) throw new IllegalStateException("capacity");
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }
    void publish(WholeObjectBacking.Changes changes) {
        WholeObjectStorage.References refs = storage.retain(changes);
        var entries = new LinkedHashMap<>(selected.entries()); entries.putAll(refs.entries());
        var proofs = new LinkedHashMap<>(selected.proofs()); proofs.putAll(refs.proofs());
        var members = new LinkedHashMap<>(selected.members());
        refs.cyclicMembers().forEach((master, additions) -> {
            var keys = new LinkedHashSet<>(members.getOrDefault(master, List.of()));
            keys.addAll(additions); members.put(master, List.copyOf(keys));
        });
        selected = new Index(entries, proofs, members);
    }
    View view() { return new View(selected); }
    String entryAddress(String id) { return selected.entries().get(id); }
    String proofAddress(String master) { return selected.proofs().get(master); }
    void remove(String id) { objects.remove(entryAddress(id)); }

    final class View implements WholeObjectBacking {
        private final Index index;
        private final WholeObjectBacking backing;
        final Set<String> readKeys = new LinkedHashSet<>();
        int reads;
        View(Index index) { this.index = index; this.backing = storage.open(index); }
        @Override public Optional<Entry> find(String id) {
            if (index.entries().containsKey(id)) { reads++; readKeys.add(id); }
            return backing.find(id);
        }
        @Override public Optional<CyclicSetProof> proof(String id) { return backing.proof(id); }
        @Override public Iterable<String> cyclicMembers(String id) { return backing.cyclicMembers(id); }
        @Override public int size() { return backing.size(); }
        void corrupt(String id) { objects.put(index.entries().get(id), new byte[]{1, 2, 3}); }
    }

    private record Index(Map<String, String> entries, Map<String, String> proofs,
                         Map<String, List<String>> members) implements WholeObjectStorage.Index {
        Index { entries = Map.copyOf(entries); proofs = Map.copyOf(proofs); members = Map.copyOf(members); }
        @Override public Optional<String> entryAddress(String id) { return Optional.ofNullable(entries.get(id)); }
        @Override public Optional<String> proofAddress(String id) { return Optional.ofNullable(proofs.get(id)); }
        @Override public Iterable<String> cyclicMembers(String id) { return members.getOrDefault(id, List.of()); }
        @Override public int size() { return entries.size(); }
    }
}
