package blue.coordination.api.storage;

import java.util.*;
import blue.coordination.api.storage.CoordinationRecords.*;

/**
 * Thread-confined tracked record access for one mutable execution attempt.
 * Records every point, absence and complete predicate consulted through this
 * boundary. Preparation detaches the packet and retires the coherent read scope.
 * An execution failure must close/discard the attempt without preparing it.
 */
public final class CoordinationRecordAttempt implements AutoCloseable {
    private enum State { OPEN, PREPARED, FAILED, CLOSED }
    private final Thread owner = Thread.currentThread();
    private final CoordinationRecordStore.ReadScope scope;
    private final Address address;
    private final Map<Key, Value> observed = new TreeMap<>();
    private final Map<Range, Query> predicates = new LinkedHashMap<>();
    private final Map<Key, Mutation> writes = new TreeMap<>();
    private final Map<Key, ImmutableFact> facts = new TreeMap<>();
    private final Map<Bytes, Artifact> requiredArtifacts = new TreeMap<>();
    private State state = State.OPEN;

    /** Takes exclusive ownership of a new coherent host scope. */
    public CoordinationRecordAttempt(CoordinationRecordStore.ReadScope scope) {
        this.scope = Objects.requireNonNull(scope);
        Address selected;
        try { selected = Objects.requireNonNull(scope.address()); }
        catch (RuntimeException | Error failure) {
            try { scope.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        address = selected;
    }

    /** Exact instance being read, independent of semantic document identity. */
    public Address address() { ensureOpen(); return address; }

    /** Reads a tracked point; includes pending changes within this attempt. */
    public Value read(Key key) {
        ensureOpen(); Objects.requireNonNull(key);
        var original = original(key);
        var write = writes.get(key);
        return write == null ? original : new Value(Math.addExact(original.revision(), 1), write.content());
    }

    /** Reads a tracked complete predicate, with this attempt's changes overlaid. */
    public List<Row> query(Range range) {
        ensureOpen(); Objects.requireNonNull(range);
        Query query = predicates.get(range);
        if (query == null) {
            try { query = new Query(range, scope.query(range)); predicates.put(range, query); }
            catch (RuntimeException | Error failure) { throw retire(failure); }
        }
        var rows = new TreeMap<Key, Row>();
        query.expected().forEach(row -> rows.put(row.key(), row));
        for (var write : writes.values()) if (range.contains(write.key())) {
            if (write.content() == null) rows.remove(write.key());
            else rows.put(write.key(), new Row(write.key(), read(write.key())));
        }
        return List.copyOf(rows.values());
    }

    /**
     * Selects the earliest live member while tracking only the complete prefix
     * through that member. Later unrelated insertions do not invalidate it.
     * Pending deletions are skipped; pending insertions compete in key order.
     * An empty selection tracks the whole requested empty range.
     */
    public Optional<Row> first(Range range) {
        ensureOpen(); Objects.requireNonNull(range);
        try {
            Range remaining = range;
            Optional<Row> selected;
            while (true) {
                selected = Objects.requireNonNull(scope.first(remaining));
                if (selected.isEmpty()) break;
                var row = selected.orElseThrow();
                if (!remaining.contains(row.key())) throw new IllegalArgumentException("First row is outside its predicate");
                var pending = writes.get(row.key());
                if (pending == null || pending.content() != null) break;
                var next = after(row.key().key());
                if (range.upper() != null && next.compareTo(range.upper()) >= 0) { selected = Optional.empty(); break; }
                remaining = new Range(range.family(), range.scope(), next, range.upper());
            }
            Key candidate = selected.map(Row::key).orElse(null);
            for (var pending : writes.values()) if (pending.content() != null && range.contains(pending.key())
                    && (candidate == null || pending.key().compareTo(candidate) < 0)) candidate = pending.key();
            var prefix = candidate == null ? range : new Range(range.family(), range.scope(), range.lower(), after(candidate.key()));
            var observedPrefix = query(prefix);
            if (candidate == null) {
                if (!observedPrefix.isEmpty()) throw new IllegalArgumentException("First-row absence contradicts its complete predicate");
                return Optional.empty();
            }
            if (observedPrefix.isEmpty() || !observedPrefix.get(0).key().equals(candidate))
                throw new IllegalArgumentException("First-row response contradicts its complete prefix");
            var result = observedPrefix.get(0);
            if (selected.isPresent() && selected.orElseThrow().key().equals(candidate) && !writes.containsKey(candidate)
                    && !selected.orElseThrow().equals(result)) throw new IllegalArgumentException("First-row value changed within its snapshot");
            return Optional.of(result);
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    private static Bytes after(Bytes key) {
        byte[] original = key.copy();
        return new Bytes(Arrays.copyOf(original, Math.addExact(original.length, 1)));
    }

    /**
     * Reads an insert-once physical lookup fact without a mutable observation.
     * Absence is only a cache miss, never a semantic absence condition. Call
     * {@link #read(Key)} when logic actually depends on absence. Hosts must
     * prevent every writer from replacing or deleting these families.
     */
    public Optional<Bytes> immutableFact(Key key) {
        ensureOpen(); requireImmutable(key.family());
        var pending = facts.get(key); if (pending != null) return Optional.of(pending.content());
        try { return immutableValue(Objects.requireNonNull(scope.read(key))); }
        catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    /** Complete physical cache membership, not a semantic completeness/absence assertion. */
    public List<Row> immutableFacts(Range range) {
        ensureOpen(); requireImmutable(range.family());
        try {
            var observed = new Query(range, scope.query(range)); var rows = new TreeMap<Key, Row>();
            for (var row : observed.expected()) { immutableValue(row.value()); rows.put(row.key(), row); }
            for (var fact : facts.values()) if (range.contains(fact.key())) rows.put(fact.key(), new Row(fact.key(), new Value(1, fact.content())));
            return List.copyOf(rows.values());
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    /** Indexed physical cache hint; never substitutes for a semantic range condition. */
    public Optional<Row> firstImmutableFact(Range range) {
        ensureOpen(); requireImmutable(range.family());
        try {
            Row selected = scope.first(range).orElse(null);
            if (selected != null) {
                if (!range.contains(selected.key())) throw new IllegalArgumentException("Immutable row is outside the requested range");
                immutableValue(selected.value());
            }
            for (var fact : facts.values()) if (range.contains(fact.key()) && (selected == null || fact.key().compareTo(selected.key()) < 0))
                selected = new Row(fact.key(), new Value(1, fact.content()));
            return Optional.ofNullable(selected);
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    /** Retains an immutable lookup fact; equal concurrent insertion must not conflict. */
    public void retainImmutableFact(Key key, Bytes content) {
        ensureOpen(); var fact = new ImmutableFact(key, content);
        var prior = immutableFact(key);
        if (prior.isPresent() && !prior.orElseThrow().equals(content))
            throw retire(new IllegalArgumentException("Immutable lookup identity has different bytes"));
        facts.put(key, fact);
    }

    private static Optional<Bytes> immutableValue(Value value) {
        if (value.revision() != (value.present() ? 1 : 0)) throw new IllegalArgumentException("Immutable fact was changed or deleted");
        return Optional.ofNullable(value.content());
    }
    private static void requireImmutable(Family family) {
        if (!CoordinationRecords.immutableFamily(family)) throw new IllegalArgumentException("Not an immutable lookup family");
    }

    /** Installs a pending replacement and automatically captures its point condition. */
    public void put(Key key, Bytes content) {
        ensureOpen(); Objects.requireNonNull(key); Objects.requireNonNull(content);
        original(key); writes.put(key, new Mutation(key, content));
    }

    /** Records deletion; the host must retain a positive monotonic tombstone revision. */
    public void delete(Key key) {
        ensureOpen(); Objects.requireNonNull(key);
        original(key); writes.put(key, new Mutation(key, null));
    }

    /**
     * Binds a required immutable dependency before preparation. Repeated identical
     * requirements coalesce; a different length under the same digest retires the attempt.
     * The caller cannot omit these dependencies from the eventual packet.
     * @param artifact authenticated physical object digest and length
     */
    public void requireArtifact(Artifact artifact) {
        ensureOpen();
        try {
            Objects.requireNonNull(artifact);
            var prior = requiredArtifacts.putIfAbsent(artifact.sha256(), artifact);
            if (prior != null && !prior.equals(artifact)) throw new IllegalArgumentException("Conflicting artifact requirement");
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    /**
     * Detaches the closed packet after all current-stage materialization completes.
     * Must not be called after a failed semantic invocation. No SDK handle belongs
     * in the evidence; the caller must encode it before calling this method.
     * Closing the snapshot occurs even when construction/cleanup fails. A failure
     * never returns a packet or authorizes use of the mutable attempt again.
     */
    public Publication prepare(String publicationId, Collection<Artifact> artifacts, Bytes evidence) {
        ensureOpen();
        try {
            var supplied = new HashSet<Bytes>();
            for (var artifact : Objects.requireNonNull(artifacts)) {
                if (!supplied.add(artifact.sha256())) throw new IllegalArgumentException("Duplicate supplied artifact");
                requireArtifact(artifact);
            }
            var points = observed.entrySet().stream().map(e -> new Point(e.getKey(), e.getValue())).toList();
            var packet = new Publication(address, publicationId, points, predicates.values(), writes.values(), facts.values(), requiredArtifacts.values(), evidence);
            state = State.PREPARED;
            scope.close();
            return packet;
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    private Value original(Key key) {
        if (observed.containsKey(key)) return observed.get(key);
        try {
            var value = Objects.requireNonNull(scope.read(key), "Missing versioned point response");
            observed.put(key, value); return value;
        } catch (RuntimeException | Error failure) { throw retire(failure); }
    }

    private RuntimeException retire(Throwable failure) {
        state = State.FAILED;
        try { scope.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        if (failure instanceof Error error) throw error;
        return (RuntimeException) failure;
    }
    private void ensureOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Record attempt belongs to another thread");
    }
    private void ensureOpen() {
        ensureOwner();
        if (state != State.OPEN) throw new IllegalStateException("Record attempt is " + state);
    }
    /** Discards an unfinished attempt, or closes an already retired one idempotently. */
    @Override public void close() {
        ensureOwner();
        if (state != State.OPEN) return;
        state = State.CLOSED;
        scope.close();
    }
}
