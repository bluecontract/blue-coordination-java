package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Immutable tentative map over tracked logical points and complete range predicates. */
final class LogicalRecordMap<K, V> {
    interface Source<K, V> {
        V get(K key);
        Map.Entry<K, V> first(K lower, boolean exclusive, K upper);
        List<Map.Entry<K, V>> entries();
        boolean contains(K key);
    }
    private record Change<V>(V value) { }
    private record Identity(LogicalRecordContext context, Family family, Bytes scope, Bytes lower) { }
    private final Identity binding;
    boolean bindingIs(LogicalRecordContext owner, Family family, Bytes scope, Bytes lower) {
        context.checkOpen(); return new Identity(owner, family, scope, lower).equals(binding);
    }
    private LogicalRecordMap<K, V> withBinding(Identity identity) {
        return new LogicalRecordMap<>(order, context, source, changes, selection, visible, identity);
    }
    private LogicalRecordMap<K, V> inheritBinding(LogicalRecordMap<K, V> changed) { return changed.withBinding(binding); }
    private final Comparator<? super K> order;
    private final LogicalRecordContext context;
    private final Source<K, V> source;
    private final PersistentOrderedMap<K, Change<V>> changes;
    private final java.util.function.BiConsumer<K, V> selection;
    private final java.util.function.Predicate<V> visible;

    private LogicalRecordMap(Comparator<? super K> order, LogicalRecordContext context, Source<K, V> source,
            PersistentOrderedMap<K, Change<V>> changes, java.util.function.BiConsumer<K, V> selection,
            java.util.function.Predicate<V> visible) {
        this(order, context, source, changes, selection, visible, null);
    }

    private LogicalRecordMap(Comparator<? super K> order, LogicalRecordContext context, Source<K, V> source,
            PersistentOrderedMap<K, Change<V>> changes, java.util.function.BiConsumer<K, V> selection,
            java.util.function.Predicate<V> visible, Identity binding) {
        this.binding = binding;
        this.order = order; this.context = context; this.source = source; this.changes = changes; this.selection = selection; this.visible = visible;
    }

    static <K, V> LogicalRecordMap<K, V> virtual(Comparator<? super K> order, LogicalRecordContext context,
            Source<K, V> source, java.util.function.BiConsumer<K, V> selection, java.util.function.Predicate<V> visible) {
        return new LogicalRecordMap<>(order, context, source, PersistentOrderedMap.empty(order), selection, visible);
    }

    static <K, V> LogicalRecordMap<K, V> open(Comparator<? super K> order, LogicalRecordContext context,
            Family family, Bytes scope, OrderedRecordKey<K> keys, PersistentMapCodec<V> values,
            int maximumKeyBytes, int maximumValueBytes) {
        return open(order, context, family, scope, keys, values, maximumKeyBytes, maximumValueBytes, null, null);
    }

    static <K, V> LogicalRecordMap<K, V> open(Comparator<? super K> order, LogicalRecordContext context,
            Family family, Bytes scope, OrderedRecordKey<K> keys, PersistentMapCodec<V> values,
            int maximumKeyBytes, int maximumValueBytes, Bytes lowerBoundary, Bytes upperBoundary) {
        Objects.requireNonNull(order); Objects.requireNonNull(context); Objects.requireNonNull(family);
        Objects.requireNonNull(scope); Objects.requireNonNull(keys); Objects.requireNonNull(values);
        if (maximumKeyBytes < 1 || maximumValueBytes < 1) throw new IllegalArgumentException("Invalid record codec limits");
        class Binding implements Source<K, V> {
            Bytes key(K value) {
                byte[] encoded = keys.encode(Objects.requireNonNull(value));
                if (encoded.length > maximumKeyBytes || order.compare(value, keys.decode(encoded.clone())) != 0)
                    throw new IllegalArgumentException("Invalid ordered runtime key");
                return new Bytes(encoded);
            }
            Key address(K value) { return new Key(family, scope, key(value)); }
            V decode(Bytes bytes) {
                byte[] encoded = bytes.copy();
                if (encoded.length > maximumValueBytes) throw new IllegalArgumentException("Runtime value bound exceeded");
                V decoded = Objects.requireNonNull(values.decode(encoded.clone()));
                if (!Arrays.equals(encoded, values.encode(decoded))) throw new IllegalArgumentException("Noncanonical runtime value");
                return decoded;
            }
            Map.Entry<K, V> row(Row row) {
                byte[] bytes = row.key().key().copy();
                if (bytes.length > maximumKeyBytes) throw new IllegalArgumentException("Runtime key bound exceeded");
                K key = Objects.requireNonNull(keys.decode(bytes));
                if (!key(key).equals(row.key().key())) throw new IllegalArgumentException("Noncanonical runtime key");
                return Map.entry(key, decode(row.value().content()));
            }
            public V get(K key) {
                var content = context.read(address(key)).content(); return content == null ? null : decode(content);
            }
            public boolean contains(K key) { return context.read(address(key)).content() != null; }
            public Map.Entry<K, V> first(K lower, boolean exclusive, K upper) {
                Bytes start = lower == null ? lowerBoundary : key(lower);
                if (exclusive && lower != null) start = after(start);
                Bytes end = upper == null ? upperBoundary : key(upper);
                if (start != null && end != null && start.compareTo(end) >= 0) return null;
                return context.first(new Range(family, scope, start, end)).map(this::row).orElse(null);
            }
            public List<Map.Entry<K, V>> entries() {
                var result = context.query(new Range(family, scope, lowerBoundary, upperBoundary)).stream().map(this::row).toList();
                for (int i = 1; i < result.size(); i++) if (order.compare(result.get(i - 1).getKey(), result.get(i).getKey()) >= 0)
                    throw new IllegalArgumentException("Runtime key encoding disagrees with semantic order");
                return result;
            }
            Mutation encode(K key, V value) {
                Key address = address(key);
                if (value == null) return new Mutation(address, null);
                byte[] bytes = values.prepareEncoding(value).consume(values);
                if (bytes.length > maximumValueBytes) throw new IllegalArgumentException("Runtime value bound exceeded");
                decode(new Bytes(bytes));
                return new Mutation(address, new Bytes(bytes));
            }
        }
        var binding = new Binding();
        var map = virtual(order, context, binding, (key, value) -> {
            var mutation = binding.encode(key, value);
            context.select(mutation.key(), mutation.content());
        }, value -> true);
        return map.withBinding(new Identity(context, family, scope, lowerBoundary));
    }

    V get(K key) { return context.protect(() -> getUnchecked(key)); }

    private V getUnchecked(K key) { context.checkOpen(); var change = changes.get(key); return change == null ? source.get(key) : change.value(); }
    boolean contains(K key) { return context.protect(() -> containsUnchecked(key)); }

    private boolean containsUnchecked(K key) { context.checkOpen(); var change = changes.get(key); return change == null ? source.contains(key) : change.value() != null && visible.test(change.value()); }
    LogicalRecordMap<K, V> put(K key, V value) { return context.protect(() -> putUnchecked(key, value)); }

    private LogicalRecordMap<K, V> putUnchecked(K key, V value) {
        context.checkOpen(); Objects.requireNonNull(key); Objects.requireNonNull(value);
        return inheritBinding(new LogicalRecordMap<>(order, context, source, changes.put(key, new Change<>(value)).map(), selection, visible));
    }
    LogicalRecordMap<K, V> remove(K key) { return context.protect(() -> removeUnchecked(key)); }

    private LogicalRecordMap<K, V> removeUnchecked(K key) {
        context.checkOpen(); Objects.requireNonNull(key);
        return inheritBinding(new LogicalRecordMap<>(order, context, source, changes.put(key, new Change<V>(null)).map(), selection, visible));
    }
    LogicalRecordMap<K, V> empty() { return context.protect(() -> emptyUnchecked()); }

    private LogicalRecordMap<K, V> emptyUnchecked() {
        var result = this;
        for (var row : entries()) result = result.remove(row.getKey());
        return result;
    }
    List<Map.Entry<K, V>> entries() { return context.protect(() -> entriesUnchecked()); }

    private List<Map.Entry<K, V>> entriesUnchecked() {
        context.checkOpen(); var rows = new TreeMap<K, V>(order);
        for (var row : source.entries()) rows.put(row.getKey(), row.getValue());
        for (var row : changes.entries()) {
            if (row.getValue().value() == null) rows.remove(row.getKey());
            else rows.put(row.getKey(), row.getValue().value());
        }
        return rows.entrySet().stream().filter(e -> visible.test(e.getValue())).map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    Map.Entry<K, V> first(K lower, boolean exclusive, K upper) { return context.protect(() -> firstUnchecked(lower, exclusive, upper)); }

    private Map.Entry<K, V> firstUnchecked(K lower, boolean exclusive, K upper) {
        context.checkOpen();
        if (lower != null && upper != null && order.compare(lower, upper) >= 0) return null;
        Map.Entry<K, V> pending = null;
        var iterator = changes.range(lower, upper);
        while (iterator.hasNext()) {
            var row = iterator.next();
            if (exclusive && lower != null && order.compare(row.getKey(), lower) == 0) continue;
            if (row.getValue().value() != null && visible.test(row.getValue().value())) { pending = Map.entry(row.getKey(), row.getValue().value()); break; }
        }
        // Never observe a later original row when an earlier tentative insertion already wins.
        K ceiling = pending == null ? upper : pending.getKey();
        K cursor = lower; boolean skip = exclusive;
        while (true) {
            var row = source.first(cursor, skip, ceiling);
            if (row == null) return pending;
            var changed = changes.get(row.getKey());
            if (changed == null) return row;
            if (changed.value() != null && visible.test(changed.value())) return Map.entry(row.getKey(), changed.value());
            cursor = row.getKey(); skip = true;
        }
    }

    Iterator<Map.Entry<K, V>> range(K lower, K upper) {
        if (lower != null && upper != null && order.compare(lower, upper) > 0)
            throw new IllegalArgumentException("Range lower bound follows upper bound");
        return new Iterator<>() {
            K cursor = lower; boolean exclusive; boolean loaded; Map.Entry<K, V> next;
            public boolean hasNext() {
                context.checkOpen();
                if (!loaded) { next = first(cursor, exclusive, upper); loaded = true; }
                return next != null;
            }
            public Map.Entry<K, V> next() {
                if (!hasNext()) throw new NoSuchElementException();
                var result = next; cursor = result.getKey(); exclusive = true; loaded = false; return result;
            }
        };
    }

    void select() { context.protect(() -> { selectUnchecked(); return null; }); }

    private void selectUnchecked() {
        context.checkOpen();
        if (selection == null) throw new IllegalStateException("Project working values back before selecting records");
        // A failed encoding retires this entire attempt, including all earlier selected maps.
        for (var row : changes.entries()) selection.accept(row.getKey(), row.getValue().value());
    }

    LogicalRecordContext context() { context.checkOpen(); return context; }

    <T> LogicalRecordMap<K, T> convert(java.util.function.Function<V, T> read,
            java.util.function.Function<T, V> write, java.util.function.Predicate<T> present) {
        var base = this;
        Source<K, T> mapped = new Source<>() {
            public T get(K key) { V value = base.get(key); return value == null ? null : Objects.requireNonNull(read.apply(value)); }
            public boolean contains(K key) { return base.contains(key); }
            private Map.Entry<K, T> map(Map.Entry<K, V> row) {
                return row == null ? null : Map.entry(row.getKey(), Objects.requireNonNull(read.apply(row.getValue())));
            }
            public Map.Entry<K, T> first(K lower, boolean exclusive, K upper) { return map(base.first(lower, exclusive, upper)); }
            public List<Map.Entry<K, T>> entries() { return base.entries().stream().map(this::map).toList(); }
        };
        return virtual(order, context, mapped, (key, value) -> {
            if (value == null) base.remove(key).select();
            else base.put(key, Objects.requireNonNull(write.apply(value))).select();
        }, present);
    }

    <T> LogicalRecordMap<K, T> project(BiFunction<K, V, T> mapping, Consumer<K> absent) {
        var base = this;
        Source<K, T> projected = new Source<>() {
            public T get(K key) {
                V value = base.get(key);
                if (value == null) { if (absent != null) absent.accept(key); return null; }
                return Objects.requireNonNull(mapping.apply(key, value));
            }
            public boolean contains(K key) {
                boolean found = base.contains(key); if (!found && absent != null) absent.accept(key); return found;
            }
            private Map.Entry<K, T> map(Map.Entry<K, V> row) {
                return row == null ? null : Map.entry(row.getKey(), Objects.requireNonNull(mapping.apply(row.getKey(), row.getValue())));
            }
            public Map.Entry<K, T> first(K lower, boolean exclusive, K upper) { return map(base.first(lower, exclusive, upper)); }
            public List<Map.Entry<K, T>> entries() { return base.entries().stream().map(this::map).toList(); }
        };
        return new LogicalRecordMap<>(order, context, projected, PersistentOrderedMap.empty(order), null, value -> true);
    }

    <T> LogicalRecordMap<K, V> stageProjection(LogicalRecordMap<K, T> working, BiFunction<K, T, V> retain) {
        context.checkOpen(); var result = this;
        for (var row : working.changes.entries()) result = row.getValue().value() == null
                ? result.remove(row.getKey()) : result.put(row.getKey(), Objects.requireNonNull(retain.apply(row.getKey(), row.getValue().value())));
        return result;
    }

    private static Bytes after(Bytes key) { byte[] bytes = key.copy(); return new Bytes(Arrays.copyOf(bytes, bytes.length + 1)); }
}
