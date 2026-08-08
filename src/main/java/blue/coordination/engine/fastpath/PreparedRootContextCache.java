package blue.coordination.engine.fastpath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Entry- and retained-byte-bounded LRU keyed by the complete immutable
 * session generation.
 *
 * <p>Context construction is single-flight per exact key and runs outside
 * the cache monitor. Running and retained generations share the entry cap;
 * admission evicts retained LRU contexts and fails fast when every slot is
 * running. Failed builds are never retained. A context larger than the entire
 * byte budget is returned to its current callers but is not cached.</p>
 */
public final class PreparedRootContextCache {
    public static final long DEFAULT_MAXIMUM_WEIGHT_BYTES =
            256L * 1024L * 1024L;

    private final int maximumSize;
    private final long maximumWeightBytes;
    private final LinkedHashMap<Key, Entry> entries;
    private final Map<Key, Flight> inFlight;
    private final Map<String, Generation> authoritativeBySession;
    private long retainedWeightBytes;
    private long hits;
    private long misses;
    private long evictions;
    private long builds;
    private long coalesced;
    private long failures;
    private long rejections;
    private int currentInFlight;
    private int peakInFlight;
    private int peakTotalSize;
    private int peakRetainedSize;
    private long peakRetainedWeightBytes;
    private int peakAuthoritativeGenerations;

    public PreparedRootContextCache(int maximumSize) {
        this(maximumSize, DEFAULT_MAXIMUM_WEIGHT_BYTES);
    }

    public PreparedRootContextCache(
            int maximumSize, long maximumWeightBytes) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        this.maximumSize = maximumSize;
        this.maximumWeightBytes = maximumWeightBytes;
        this.entries = new LinkedHashMap<Key, Entry>(
                Math.min(16, maximumSize), 0.75f, true);
        this.inFlight = new LinkedHashMap<Key, Flight>();
        this.authoritativeBySession =
                new LinkedHashMap<String, Generation>(
                        Math.min(16, maximumSize), 0.75f, true);
    }

    public synchronized PreparedRootExecutionContext get(
            String sessionId,
            long epoch,
            String rootBlueId,
            String inventoryIdentity) {
        Entry retained = entries.get(
                new Key(sessionId, epoch, rootBlueId, inventoryIdentity));
        if (retained == null) misses++;
        else hits++;
        return retained == null ? null : retained.context;
    }

    public PreparedRootExecutionContext getOrBuild(
            String sessionId,
            long epoch,
            String rootBlueId,
            String inventoryIdentity,
            Supplier<PreparedRootExecutionContext> builder) {
        Key key = new Key(
                sessionId, epoch, rootBlueId, inventoryIdentity);
        PreparedRootExecutionContext ready = get(
                sessionId, epoch, rootBlueId, inventoryIdentity);
        if (ready != null) return ready;
        Supplier<PreparedRootExecutionContext> checkedBuilder =
                Objects.requireNonNull(builder, "builder");
        Flight flight;
        boolean owner;
        synchronized (this) {
            Entry race = entries.get(key);
            if (race != null) return race.context;
            flight = inFlight.get(key);
            owner = flight == null;
            if (owner) {
                admitFlightLocked();
                flight = new Flight();
                inFlight.put(key, flight);
                currentInFlight++;
                builds++;
                peakInFlight = Math.max(
                        peakInFlight, currentInFlight);
                peakTotalSize = Math.max(
                        peakTotalSize, totalSizeLocked());
            } else {
                coalesced++;
            }
        }
        if (owner) {
            try {
                PreparedRootExecutionContext built = Objects.requireNonNull(
                        checkedBuilder.get(), "built context");
                if (!built.matches(
                        sessionId,
                        epoch,
                        rootBlueId,
                        inventoryIdentity)) {
                    throw new IllegalArgumentException(
                            "Built context changed session generation");
                }
                PreparedRootExecutionContext result;
                synchronized (this) {
                    Entry race = entries.get(key);
                    result = flight.invalidated || race == null
                            ? built : race.context;
                    boolean mayRetain = !flight.invalidated
                            && inFlight.get(key) == flight;
                    inFlight.remove(key, flight);
                    finishFlightLocked(flight);
                    if (race == null && mayRetain) {
                        installBuiltLocked(key, built);
                    }
                }
                flight.future.complete(result);
            } catch (Throwable failure) {
                synchronized (this) {
                    failures++;
                    inFlight.remove(key, flight);
                    finishFlightLocked(flight);
                }
                flight.future.completeExceptionally(failure);
                throw propagate(failure);
            }
        }
        try {
            return flight.future.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause());
        }
    }

    public synchronized void install(PreparedRootExecutionContext context) {
        installIfNotOlder(context);
    }

    /**
     * Installs only when this cache does not already hold a newer generation
     * for the same session. This makes delayed post-publication callbacks
     * converge to the newest context regardless of callback order.
     */
    public synchronized boolean installIfNotOlder(
            PreparedRootExecutionContext context) {
        PreparedRootExecutionContext checked = Objects.requireNonNull(
                context, "context");
        return installIfNotOlderLocked(checked);
    }

    /** Installs only when the engine watermark still names this generation. */
    public synchronized boolean installIfCurrent(
            PreparedRootExecutionContext context) {
        PreparedRootExecutionContext checked = Objects.requireNonNull(
                context, "context");
        Generation current = authoritativeBySession.get(
                checked.sessionId());
        return current != null
                && current.matches(checked)
                && installIfNotOlderLocked(checked);
    }

    /** Advances one session watermark and discards its older warm contexts. */
    public synchronized void markAuthoritativeGeneration(
            String sessionId,
            long epoch,
            String rootBlueId,
            String inventoryIdentity) {
        Generation next = new Generation(
                sessionId, epoch, rootBlueId, inventoryIdentity);
        Generation current = authoritativeBySession.get(next.sessionId);
        if (current != null && current.epoch > next.epoch) return;
        if (current == null
                && authoritativeBySession.size() >= maximumSize) {
            Iterator<Map.Entry<String, Generation>> eldest =
                    authoritativeBySession.entrySet().iterator();
            eldest.next();
            eldest.remove();
        }
        authoritativeBySession.put(next.sessionId, next);
        peakAuthoritativeGenerations = Math.max(
                peakAuthoritativeGenerations,
                authoritativeBySession.size());
        Iterator<Map.Entry<Key, Entry>> iterator =
                entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry retained = iterator.next().getValue();
            if (retained.context.sessionId().equals(next.sessionId)
                    && !next.matches(retained.context)) {
                iterator.remove();
                retainedWeightBytes -= retained.weightBytes;
                evictions++;
            }
        }
        invalidateFlightsLocked(key -> key.sessionId.equals(next.sessionId)
                && !next.matches(key));
    }

    /** Removes one inactive session's cache entry and generation watermark. */
    public synchronized void removeSession(String sessionId) {
        String checked = requireText(sessionId, "sessionId");
        authoritativeBySession.remove(checked);
        Iterator<Map.Entry<Key, Entry>> iterator =
                entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry retained = iterator.next().getValue();
            if (retained.context.sessionId().equals(checked)) {
                iterator.remove();
                retainedWeightBytes -= retained.weightBytes;
                evictions++;
            }
        }
        invalidateFlightsLocked(key -> key.sessionId.equals(checked));
    }

    private boolean installIfNotOlderLocked(
            PreparedRootExecutionContext checked) {
        for (Entry retained : entries.values()) {
            if (!retained.context.sessionId().equals(
                    checked.sessionId())) continue;
            if (retained.context.epoch() > checked.epoch()
                    || (retained.context.epoch() == checked.epoch()
                    && (!retained.context.rootBlueId().equals(
                            checked.rootBlueId())
                            || !retained.context.inventoryIdentity().equals(
                                    checked.inventoryIdentity())))) {
                return false;
            }
        }
        long weight = checked.approximateRetainedWeightBytes();
        if (weight <= 0L) {
            throw new IllegalArgumentException(
                    "prepared context weight must be positive");
        }
        if (weight > maximumWeightBytes) return false;
        Key key = Key.of(checked);
        Entry previous = entries.get(key);
        if (previous == null && !makeRetainedSlotLocked()) {
            return false;
        }
        previous = entries.remove(key);
        if (previous != null) {
            retainedWeightBytes -= previous.weightBytes;
        }
        evictUntilWeightFitsLocked(weight);
        entries.put(key, new Entry(checked, weight));
        retainedWeightBytes += weight;
        peakRetainedSize = Math.max(peakRetainedSize, entries.size());
        peakRetainedWeightBytes = Math.max(
                peakRetainedWeightBytes, retainedWeightBytes);
        peakTotalSize = Math.max(peakTotalSize, totalSizeLocked());
        return true;
    }

    private boolean installBuiltLocked(
            Key key, PreparedRootExecutionContext built) {
        if (!key.equals(Key.of(built))) {
            throw new IllegalArgumentException(
                    "Built context changed cache key");
        }
        return installIfNotOlderLocked(built);
    }

    private void evictUntilWeightFitsLocked(long incomingWeightBytes) {
        while (!entries.isEmpty()
                && retainedWeightBytes
                > maximumWeightBytes - incomingWeightBytes) {
            evictEldestRetainedLocked();
        }
    }

    private void admitFlightLocked() {
        while (totalSizeLocked() >= maximumSize) {
            if (entries.isEmpty()) {
                rejections++;
                throw new RejectedExecutionException(
                        "prepared-context cache capacity exhausted");
            }
            evictEldestRetainedLocked();
        }
    }

    private boolean makeRetainedSlotLocked() {
        while (totalSizeLocked() >= maximumSize) {
            if (entries.isEmpty()) {
                rejections++;
                return false;
            }
            evictEldestRetainedLocked();
        }
        return true;
    }

    private void evictEldestRetainedLocked() {
        Iterator<Map.Entry<Key, Entry>> iterator =
                entries.entrySet().iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException(
                    "prepared-context eviction has no retained entry");
        }
        Entry eldest = iterator.next().getValue();
        iterator.remove();
        retainedWeightBytes -= eldest.weightBytes;
        evictions++;
    }

    private void invalidateFlightsLocked(Predicate<Key> remove) {
        Iterator<Map.Entry<Key, Flight>> iterator =
                inFlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Flight> candidate = iterator.next();
            if (!remove.test(candidate.getKey())) continue;
            candidate.getValue().invalidated = true;
            iterator.remove();
        }
    }

    private void finishFlightLocked(Flight flight) {
        if (flight.finished) return;
        flight.finished = true;
        currentInFlight--;
        if (currentInFlight < 0) {
            throw new IllegalStateException(
                    "prepared-context in-flight accounting became negative");
        }
    }

    private int totalSizeLocked() {
        return Math.addExact(entries.size(), currentInFlight);
    }

    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized int size() { return entries.size(); }
    public synchronized int maximumSize() { return maximumSize; }
    /** Includes invalidated generations still physically building. */
    public synchronized int inFlightCount() { return currentInFlight; }
    public synchronized int totalSize() { return totalSizeLocked(); }
    public synchronized long retainedWeightBytes() {
        return retainedWeightBytes;
    }
    public synchronized long maximumWeightBytes() {
        return maximumWeightBytes;
    }
    public synchronized long evictions() { return evictions; }
    public synchronized long rejections() { return rejections; }

    /**
     * Immutable bounded view for checkpoint capture. This returns only
     * contexts already resident in the LRU; it never awaits or triggers a
     * builder and never expands the retained session set.
     */
    public synchronized List<PreparedRootExecutionContext>
            retainedContextsSnapshot() {
        List<PreparedRootExecutionContext> retained =
                new ArrayList<PreparedRootExecutionContext>(entries.size());
        for (Entry entry : entries.values()) {
            retained.add(entry.context);
        }
        return Collections.unmodifiableList(retained);
    }

    /** Immutable operational evidence for the bounded cache generation. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maximumSize,
                maximumWeightBytes,
                entries.size(),
                retainedWeightBytes,
                currentInFlight,
                peakInFlight,
                totalSizeLocked(),
                peakTotalSize,
                peakRetainedSize,
                peakRetainedWeightBytes,
                authoritativeBySession.size(),
                peakAuthoritativeGenerations,
                hits,
                misses,
                builds,
                coalesced,
                failures,
                evictions,
                rejections);
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must not be empty");
        }
        return checked;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) throw (Error) failure;
        return new IllegalStateException(
                "Prepared context construction failed", failure);
    }

    /** Immutable Java-8-compatible cache evidence. */
    public static final class Snapshot {
        private final int maximumSize;
        private final long maximumWeightBytes;
        private final int size;
        private final long retainedWeightBytes;
        private final int inFlight;
        private final int peakInFlight;
        private final int totalSize;
        private final int peakTotalSize;
        private final int peakRetainedSize;
        private final long peakRetainedWeightBytes;
        private final int authoritativeGenerations;
        private final int peakAuthoritativeGenerations;
        private final long hits;
        private final long misses;
        private final long builds;
        private final long coalesced;
        private final long failures;
        private final long evictions;
        private final long rejections;

        private Snapshot(
                int maximumSize,
                long maximumWeightBytes,
                int size,
                long retainedWeightBytes,
                int inFlight,
                int peakInFlight,
                int totalSize,
                int peakTotalSize,
                int peakRetainedSize,
                long peakRetainedWeightBytes,
                int authoritativeGenerations,
                int peakAuthoritativeGenerations,
                long hits,
                long misses,
                long builds,
                long coalesced,
                long failures,
                long evictions,
                long rejections) {
            this.maximumSize = maximumSize;
            this.maximumWeightBytes = maximumWeightBytes;
            this.size = size;
            this.retainedWeightBytes = retainedWeightBytes;
            this.inFlight = inFlight;
            this.peakInFlight = peakInFlight;
            this.totalSize = totalSize;
            this.peakTotalSize = peakTotalSize;
            this.peakRetainedSize = peakRetainedSize;
            this.peakRetainedWeightBytes = peakRetainedWeightBytes;
            this.authoritativeGenerations = authoritativeGenerations;
            this.peakAuthoritativeGenerations =
                    peakAuthoritativeGenerations;
            this.hits = hits;
            this.misses = misses;
            this.builds = builds;
            this.coalesced = coalesced;
            this.failures = failures;
            this.evictions = evictions;
            this.rejections = rejections;
        }

        public int maximumSize() { return maximumSize; }
        public long maximumWeightBytes() { return maximumWeightBytes; }
        public int size() { return size; }
        public long retainedWeightBytes() { return retainedWeightBytes; }
        public int inFlight() { return inFlight; }
        public int peakInFlight() { return peakInFlight; }
        public int totalSize() { return totalSize; }
        public int peakTotalSize() { return peakTotalSize; }
        public int peakRetainedSize() { return peakRetainedSize; }
        public long peakRetainedWeightBytes() {
            return peakRetainedWeightBytes;
        }
        public int authoritativeGenerations() {
            return authoritativeGenerations;
        }
        public int peakAuthoritativeGenerations() {
            return peakAuthoritativeGenerations;
        }
        public long hits() { return hits; }
        public long misses() { return misses; }
        public long builds() { return builds; }
        public long coalesced() { return coalesced; }
        public long failures() { return failures; }
        public long evictions() { return evictions; }
        public long rejections() { return rejections; }
    }

    private static final class Flight {
        private final CompletableFuture<PreparedRootExecutionContext> future =
                new CompletableFuture<PreparedRootExecutionContext>();
        private boolean invalidated;
        private boolean finished;
    }

    private static final class Entry {
        private final PreparedRootExecutionContext context;
        private final long weightBytes;

        private Entry(
                PreparedRootExecutionContext context,
                long weightBytes) {
            this.context = Objects.requireNonNull(context, "context");
            this.weightBytes = weightBytes;
        }
    }

    private static final class Generation {
        private final String sessionId;
        private final long epoch;
        private final String rootBlueId;
        private final String inventoryIdentity;

        private Generation(
                String sessionId,
                long epoch,
                String rootBlueId,
                String inventoryIdentity) {
            this.sessionId = requireText(sessionId, "sessionId");
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "epoch must be non-negative");
            }
            this.epoch = epoch;
            this.rootBlueId = requireText(rootBlueId, "rootBlueId");
            this.inventoryIdentity = requireText(
                    inventoryIdentity, "inventoryIdentity");
        }

        private boolean matches(PreparedRootExecutionContext context) {
            return sessionId.equals(context.sessionId())
                    && epoch == context.epoch()
                    && rootBlueId.equals(context.rootBlueId())
                    && inventoryIdentity.equals(
                            context.inventoryIdentity());
        }

        private boolean matches(Key key) {
            return sessionId.equals(key.sessionId)
                    && epoch == key.epoch
                    && rootBlueId.equals(key.rootBlueId)
                    && inventoryIdentity.equals(key.inventoryIdentity);
        }
    }

    private static final class Key {
        private final String sessionId;
        private final long epoch;
        private final String rootBlueId;
        private final String inventoryIdentity;

        private Key(
                String sessionId,
                long epoch,
                String rootBlueId,
                String inventoryIdentity) {
            this.sessionId = requireText(sessionId, "sessionId");
            if (epoch < 0L) {
                throw new IllegalArgumentException(
                        "epoch must be non-negative");
            }
            this.epoch = epoch;
            this.rootBlueId = requireText(rootBlueId, "rootBlueId");
            this.inventoryIdentity = requireText(
                    inventoryIdentity, "inventoryIdentity");
        }

        private static Key of(PreparedRootExecutionContext context) {
            return new Key(
                    context.sessionId(),
                    context.epoch(),
                    context.rootBlueId(),
                    context.inventoryIdentity());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return epoch == that.epoch
                    && sessionId.equals(that.sessionId)
                    && rootBlueId.equals(that.rootBlueId)
                    && inventoryIdentity.equals(that.inventoryIdentity);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    sessionId, epoch, rootBlueId, inventoryIdentity);
        }
    }
}
