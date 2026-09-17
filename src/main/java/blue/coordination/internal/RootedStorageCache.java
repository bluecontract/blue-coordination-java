package blue.coordination.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Host-lifetime, weighted LRU of library-verified immutable storage artifacts.
 * Load/insert access is package-private: the host controls capacity and lifetime,
 * not verification certificates. No mutable sessions or publication decisions
 * may be retained. Weight is an estimate, not a JVM retained-heap guarantee.
 */
final class RootedStorageCache implements AutoCloseable {
    private final long maximumWeightBytes;
    private final int maximumEntries;
    private final long maximumEntryWeightBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<ValueKey, Set<Key>> canonicalValues = new HashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> loading = new ConcurrentHashMap<>();
    private long weight, hits, misses, loads, evictions, incompatibleHits, waits, failedLoads, generation;
    private boolean closed;

    private record Key(String family, String digest, boolean canonical) { }
    private record Entry(byte[] bytes, Object value, long weight, Object encodingIdentity) { }
    private record ValueKey(String family, Object value) {
        @Override public boolean equals(Object other) {
            return other instanceof ValueKey key && family.equals(key.family) && value == key.value;
        }
        @Override public int hashCode() { return 31 * family.hashCode() + System.identityHashCode(value); }
    }

    /**
     * Creates an empty cache. Zero total budget disables retention.
     * @param maximumWeightBytes process-wide estimated retained-weight budget
     * @param maximumEntries maximum retained artifact count
     * @param maximumEntryWeightBytes estimated weight above which one artifact is not retained
     */
    RootedStorageCache(long maximumWeightBytes, int maximumEntries, long maximumEntryWeightBytes) {
        if (maximumWeightBytes < 0 || maximumEntries < 0 || maximumEntryWeightBytes < 0
                || maximumWeightBytes > 0 && (maximumEntries == 0 || maximumEntryWeightBytes == 0))
            throw new IllegalArgumentException("Invalid runtime decoded-cache capacity");
        this.maximumWeightBytes = maximumWeightBytes;
        this.maximumEntries = maximumEntries;
        this.maximumEntryWeightBytes = maximumEntryWeightBytes;
    }

    /** Process totals; do not add these once per retired operation. */
    record Statistics(long hits, long misses, long loads, long evictions,
            int retainedEntries, long retainedWeightBytes, long incompatibleHits, long waits, long failedLoads) { }

    /** Returns counters without retaining cache keys or operation histories. @return process cache totals */
    synchronized Statistics statistics() {
        return new Statistics(hits, misses, loads, evictions, entries.size(), weight, incompatibleHits, waits, failedLoads);
    }

    /** Avoid constructing a combined memo key that cannot fit; never changes read admission. */
    synchronized boolean canRetainEncodedBytes(long bytes) {
        requireOpen();
        long estimated = estimatedWeight(bytes, 0);
        return bytes >= 0 && maximumWeightBytes > 0
                && estimated <= maximumWeightBytes && estimated <= maximumEntryWeightBytes;
    }

    <T> T decode(String family, byte[] bytes, Supplier<T> fullyVerifiedDecode) {
        return decode(family, bytes, ignored -> 0, ignored -> true, fullyVerifiedDecode);
    }

    /**
     * Called only after authenticating the selected physical frame. The family
     * includes format and decoding limits. Cross-record artifacts must account
     * for all retained dependencies and adopt them into the new operation scope.
     * Callers own the private frame and must not mutate it while the synchronous
     * decoder runs. Neither the frame nor the loader is exposed by the host API.
     */
    <T> T decode(String family, byte[] bytes, ToLongFunction<T> dependencyEncodedBytes,
            Predicate<T> acceptCached, Supplier<T> fullyVerifiedDecode) {
        return decode(family, bytes, dependencyEncodedBytes, acceptCached, fullyVerifiedDecode, null);
    }

    /** Only eligible immutable, self-contained codecs may issue canonical frame evidence. */
    <T> T decodeCanonical(String family, byte[] bytes, Function<byte[], T> rawDecode,
            Function<T, byte[]> rawEncode) {
        return decodeCanonical(family, bytes, rawDecode, rawEncode, value -> value);
    }

    /** Opaque library certificates may bind an immutable value without a second retaining map. */
    <T> T decodeCanonical(String family, byte[] bytes, Function<byte[], T> rawDecode,
            Function<T, byte[]> rawEncode, Function<T, Object> encodingIdentity) {
        return decodeCanonical(family, bytes, rawDecode, rawEncode, encodingIdentity, ignored -> 0);
    }

    <T> T decodeCanonical(String family, byte[] bytes, Function<byte[], T> rawDecode,
            Function<T, byte[]> rawEncode, Function<T, Object> encodingIdentity, ToLongFunction<T> dependencyEncodedBytes) {
        byte[] frame = Objects.requireNonNull(bytes).clone();
        return decode(family, frame, dependencyEncodedBytes, ignored -> true, () -> {
            T value = Objects.requireNonNull(rawDecode.apply(frame.clone()), "Missing canonical value");
            if (!Arrays.equals(frame, rawEncode.apply(value)))
                throw new blue.coordination.api.storage.CoordinationObjectStorageException("Noncanonical immutable value frame");
            return value;
        }, Objects.requireNonNull(encodingIdentity));
    }

    /**
     * Library-internal codecs only: the decoder must fully verify this same frame,
     * including exact canonical byte equality under the family/current decoding
     * profile, before returning its immutable value. Retains that completed proof
     * without another encode; the host cannot supply this decoder or a trust flag.
     * Cross-record hits still authenticate and adopt dependencies in the new scope.
     */
    <T> T decodeVerifiedCanonical(String family, byte[] bytes, Function<byte[], T> fullyCanonicalDecode,
            Function<T, Object> encodingIdentity, ToLongFunction<T> dependencyEncodedBytes, Predicate<T> acceptCached) {
        byte[] frame = Objects.requireNonNull(bytes).clone();
        Objects.requireNonNull(fullyCanonicalDecode);
        return decode(family, frame, dependencyEncodedBytes, acceptCached,
                () -> Objects.requireNonNull(fullyCanonicalDecode.apply(frame.clone()), "Missing canonical value"),
                Objects.requireNonNull(encodingIdentity));
    }

    /** Exact retained identity, not equals(), a logical ID, or an arbitrary encoder's assertion. */
    synchronized byte[] canonicalEncoding(String family, Object value) {
        Entry entry = canonicalEntry(family, value);
        return entry == null ? null : entry.bytes().clone();
    }

    /** No frame allocation: only an existing library-issued canonical certificate can match. */
    synchronized boolean hasCanonicalEncoding(String family, Object value, String digest, int length) {
        requireOpen();
        var keys = canonicalValues.get(new ValueKey(Objects.requireNonNull(family), Objects.requireNonNull(value)));
        if (keys == null || keys.size() != 1) return false;
        var key = keys.iterator().next();
        var entry = entries.get(key);
        if (entry == null || entry.encodingIdentity() != value || !key.digest().equals(digest)
                || entry.bytes().length != length) return false;
        hits++;
        diagnosticEvent(family, digest, "CANONICAL_DESCRIPTOR_HIT", true, entry.weight());
        return true;
    }

    /** Owner has already authenticated this address; keep the artifact under the same process budget. */
    synchronized boolean retainsDecoded(String family, String digest, Object value) {
        requireOpen();
        Entry entry = entries.get(new Key(family, digest, false));
        if (entry == null || entry.value() != value) return false;
        hits++;
        diagnosticEvent(family, digest, "AUTHENTICATED_OWNER_HIT", false, entry.weight());
        return true;
    }

    /** Returns only the retained opaque certificate for its exact immutable subject. */
    synchronized <T> T canonicalValue(String family, Object value) {
        Entry entry = canonicalEntry(family, value);
        return entry == null ? null : cast(entry.value());
    }

    private Entry canonicalEntry(String family, Object value) {
        requireOpen();
        var keys = canonicalValues.get(new ValueKey(Objects.requireNonNull(family), Objects.requireNonNull(value)));
        // An identity that a codec maps to multiple frames is not usable as an encoding certificate.
        if (keys == null || keys.size() != 1) {
            diagnosticEvent(family, "", "CANONICAL_IDENTITY_MISS", true, -1);
            return null;
        }
        var key = keys.iterator().next();
        var entry = entries.get(key);
        if (entry == null || entry.encodingIdentity() != value) {
            diagnosticEvent(family, key.digest(), "CANONICAL_IDENTITY_MISS", true, -1);
            return null;
        }
        hits++;
        diagnosticEvent(family, key.digest(), "CANONICAL_IDENTITY_HIT", true, entry.weight());
        return entry;
    }

    private <T> T decode(String family, byte[] bytes, ToLongFunction<T> dependencyEncodedBytes,
            Predicate<T> acceptCached, Supplier<T> fullyVerifiedDecode, Function<T, Object> encodingIdentity) {
        Objects.requireNonNull(family); Objects.requireNonNull(bytes);
        Objects.requireNonNull(dependencyEncodedBytes); Objects.requireNonNull(acceptCached);
        Objects.requireNonNull(fullyVerifiedDecode);
        if (family.isBlank()) throw new IllegalArgumentException("Empty decoded-cache family");
        final long startedGeneration;
        synchronized (this) { requireOpen(); startedGeneration = generation; }
        if (maximumWeightBytes == 0) {
            diagnosticEvent(family, "", "DISABLED", encodingIdentity != null, -1);
            return load(family, "", encodingIdentity != null, bytes, dependencyEncodedBytes, fullyVerifiedDecode).valueAs();
        }
        byte[] owned = bytes.clone();
        Key key = new Key(family, digest(owned), encodingIdentity != null);
        diagnosticEvent(family, key.digest(), "LOOKUP", key.canonical(), -1);
        Entry known;
        synchronized (this) { known = entries.get(key); }
        if (known != null && Arrays.equals(owned, known.bytes())) {
            T value = cast(known.value());
            if (acceptCached.test(value)) {
                synchronized (this) { hits++; }
                diagnosticEvent(family, key.digest(), "HIT", key.canonical(), known.weight());
                return value;
            }
            synchronized (this) { incompatibleHits++; }
            diagnosticEvent(family, key.digest(), "INCOMPATIBLE", key.canonical(), known.weight());
        } else {
            diagnosticEvent(family, key.digest(), known == null ? "MISS_ABSENT" : "MISS_BYTES_DIFFER",
                    key.canonical(), -1);
        }
        synchronized (this) { misses++; }
        var mine = new CompletableFuture<Entry>();
        var prior = loading.putIfAbsent(key, mine);
        if (prior != null) {
            synchronized (this) { waits++; }
            diagnosticEvent(family, key.digest(), "WAIT", key.canonical(), -1);
            Entry shared = join(prior);
            if (Arrays.equals(owned, shared.bytes())) {
                T value = cast(shared.value());
                if (acceptCached.test(value)) {
                    diagnosticEvent(family, key.digest(), "FLIGHT_HIT", key.canonical(), shared.weight());
                    return value;
                }
                synchronized (this) { incompatibleHits++; }
                diagnosticEvent(family, key.digest(), "FLIGHT_INCOMPATIBLE", key.canonical(), shared.weight());
            } else {
                diagnosticEvent(family, key.digest(), "FLIGHT_BYTES_DIFFER", key.canonical(), -1);
            }
            // The other owner's immutable object identities cannot always be
            // adopted into this already-open scope. Preserve ordinary decode.
            return load(family, key.digest(), key.canonical(), owned, dependencyEncodedBytes, fullyVerifiedDecode).valueAs();
        }
        try {
            // A previous loader may have published and retired its flight after
            // our initial lookup. Recheck before starting another cold decode.
            Entry latest;
            synchronized (this) { latest = entries.get(key); }
            if (latest != null && latest != known && Arrays.equals(owned, latest.bytes())) {
                T value = cast(latest.value());
                if (acceptCached.test(value)) {
                    synchronized (this) { hits++; }
                    diagnosticEvent(family, key.digest(), "RECHECK_HIT", key.canonical(), latest.weight());
                    mine.complete(latest); return value;
                }
                diagnosticEvent(family, key.digest(), "RECHECK_INCOMPATIBLE", key.canonical(), latest.weight());
            }
            Loaded<T> decoded = load(family, key.digest(), key.canonical(), owned, dependencyEncodedBytes, fullyVerifiedDecode);
            Entry entry = new Entry(owned, decoded.valueAs(), decoded.weight(), encodingIdentity == null ? null
                    : Objects.requireNonNull(encodingIdentity.apply(decoded.valueAs()), "Missing canonical subject"));
            synchronized (this) {
                if (!closed && generation == startedGeneration) retain(key, entry);
                else diagnosticEvent(family, key.digest(), closed ? "NOT_RETAINED_CLOSED" : "NOT_RETAINED_GENERATION",
                        key.canonical(), entry.weight());
            }
            mine.complete(entry);
            return decoded.valueAs();
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure); throw failure;
        } finally { loading.remove(key, mine); }
    }

    private record Loaded<T>(T valueAs, long weight) { }

    private <T> Loaded<T> load(String family, String digest, boolean canonical,
            byte[] bytes, ToLongFunction<T> dependencies, Supplier<T> decoder) {
        synchronized (this) { loads++; }
        diagnosticEvent(family, digest, "LOAD_BEGIN", canonical, -1);
        try {
            T value = Objects.requireNonNull(decoder.get(), "Missing verified artifact");
            long extra = dependencies.applyAsLong(value);
            if (extra < 0) throw new IllegalArgumentException("Negative decoded dependency weight");
            long estimated = estimatedWeight(bytes.length, extra);
            diagnosticEvent(family, digest, "LOAD_SUCCESS", canonical, estimated);
            return new Loaded<>(value, estimated);
        } catch (RuntimeException | Error failure) {
            synchronized (this) { failedLoads++; }
            diagnosticEvent(family, digest, "LOAD_FAILURE", canonical, -1);
            throw failure;
        }
    }

    private void retain(Key key, Entry entry) {
        if (entry.weight() > maximumEntryWeightBytes) {
            diagnosticEvent(key.family(), key.digest(), "NOT_RETAINED_ENTRY_LIMIT", key.canonical(), entry.weight());
            return;
        }
        if (entry.weight() > maximumWeightBytes) {
            diagnosticEvent(key.family(), key.digest(), "NOT_RETAINED_TOTAL_LIMIT", key.canonical(), entry.weight());
            return;
        }
        Entry old = entries.remove(key);
        if (old != null) {
            removeEvidence(key, old);
            diagnosticEvent(key.family(), key.digest(), "REPLACE", key.canonical(), old.weight());
        }
        while (!entries.isEmpty() && (entries.size() >= maximumEntries
                || weight > maximumWeightBytes - entry.weight())) {
            var oldest = entries.entrySet().iterator();
            var removed = oldest.next();
            removeEvidence(removed.getKey(), removed.getValue()); oldest.remove(); evictions++;
            diagnosticEvent(removed.getKey().family(), removed.getKey().digest(), "EVICT",
                    removed.getKey().canonical(), removed.getValue().weight());
        }
        entries.put(key, entry); weight += entry.weight();
        if (key.canonical()) canonicalValues.computeIfAbsent(new ValueKey(key.family(), entry.encodingIdentity()),
                ignored -> new HashSet<>()).add(key);
        diagnosticEvent(key.family(), key.digest(), "RETAIN", key.canonical(), entry.weight());
    }

    private void removeEvidence(Key key, Entry entry) {
        weight -= entry.weight();
        if (key.canonical()) {
            var value = new ValueKey(key.family(), entry.encodingIdentity());
            var keys = canonicalValues.get(value);
            if (keys != null && keys.remove(key) && keys.isEmpty()) canonicalValues.remove(value);
        }
    }

    /** Drops retained artifacts. In-flight loads cannot repopulate a cleared generation. */
    synchronized void clear() {
        diagnosticEvent("", "", "CLEAR", false, weight);
        entries.clear(); canonicalValues.clear(); weight = 0; generation++;
    }

    /** Retires only this cache; the host must separately stop active operations. */
    @Override public synchronized void close() { closed = true; clear(); }

    private void requireOpen() { if (closed) throw new IllegalStateException("Runtime decoded cache is closed"); }

    /**
     * No-op seam for hash-pinned external diagnostics; no callback or retained state.
     * Digests are already calculated cache keys, never an extra payload hash. An empty
     * digest means no key was available; -1 means the estimated retained weight is unknown.
     * Canonical identity lookups are distinct from decode hits. Clear is cache-wide.
     */
    static void diagnosticEvent(String family, String digest, String outcome, boolean canonical, long weightBytes) { }

    // Starting estimator: own frame/dependency bytes plus seven times that size
    // for decoded nodes, strings, containers and aliases, plus entry overhead.
    // Shared dependencies are deliberately charged again; validate against heap
    // measurements before treating a configured GiB budget as actual residency.
    static long estimatedWeight(long bytes, long dependencies) {
        try { return Math.addExact(512, Math.multiplyExact(8, Math.addExact(bytes, dependencies))); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    @SuppressWarnings("unchecked") private static <T> T cast(Object value) { return (T) value; }
    private static Entry join(CompletableFuture<Entry> future) {
        try { return future.get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new blue.coordination.api.storage.CoordinationObjectStorageException(
                    "Interrupted while awaiting immutable artifact reconstruction", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Immutable artifact reconstruction failed", failure.getCause());
        }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
