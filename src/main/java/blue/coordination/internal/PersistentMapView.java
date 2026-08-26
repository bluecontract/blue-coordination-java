package blue.coordination.internal;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only {@link Map} facade over one immutable persistent map root. */
final class PersistentMapView<K, V> extends AbstractMap<K, V> {
    private final PersistentOrderedMap<K, V> index;

    PersistentMapView(PersistentOrderedMap<K, V> index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public V get(Object key) {
        try {
            @SuppressWarnings("unchecked")
            K selected = (K) Objects.requireNonNull(key, "key");
            return index.get(selected);
        } catch (ClassCastException mismatch) {
            return null;
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public int size() {
        return index.size();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return Collections.unmodifiableSet(
                new LinkedHashSet<>(index.entries()));
    }
}
