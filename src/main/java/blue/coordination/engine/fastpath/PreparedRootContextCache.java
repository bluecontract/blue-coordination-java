package blue.coordination.engine.fastpath;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Entry- and retained-byte-bounded LRU keyed by the complete immutable
 * session generation.
 *
 * <p>Context construction is single-flight per exact key and runs outside
 * the cache monitor. Failed builds are never retained. A context larger than
 * the entire byte budget is returned to its current callers but is not
 * cached.</p>
 */
public final class PreparedRootContextCache {
    public static final long DEFAULT_MAXIMUM_WEIGHT_BYTES =
            256L * 1024L * 1024L;

    private final int maximumSize;
    private final long maximumWeightBytes;
    private final LinkedHashMap<Key, Entry> entries;
    private final Map<Key, CompletableFuture<PreparedRootExecutionContext>>
            inFlight;
    private final Map<String, Generation> authoritativeBySession;
    private long retainedWeightBytes;
    private long hits;
    private long misses;
    private long evictions;

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
        this.inFlight = new LinkedHashMap<Key, CompletableFuture<
                PreparedRootExecutionContext>>();
        this.authoritativeBySession =
                new LinkedHashMap<String, Generation>();
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
        CompletableFuture<PreparedRootExecutionContext> future;
        boolean owner;
        synchronized (this) {
            Entry race = entries.get(key);
            if (race != null) return race.context;
            future = inFlight.get(key);
            owner = future == null;
            if (owner) {
                future = new CompletableFuture<
                        PreparedRootExecutionContext>();
                inFlight.put(key, future);
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
                synchronized (this) {
                    Entry race = entries.get(key);
                    PreparedRootExecutionContext result = race == null
                            ? built : race.context;
                    if (race == null) installBuiltLocked(key, built);
                    inFlight.remove(key, future);
                    future.complete(result);
                }
            } catch (Throwable failure) {
                synchronized (this) {
                    inFlight.remove(key, future);
                    future.completeExceptionally(failure);
                }
                throw propagate(failure);
            }
        }
        try {
            return future.join();
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
        authoritativeBySession.put(next.sessionId, next);
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
        Entry previous = entries.remove(key);
        if (previous != null) {
            retainedWeightBytes -= previous.weightBytes;
        }
        evictUntilFits(weight);
        entries.put(key, new Entry(checked, weight));
        retainedWeightBytes += weight;
        return true;
    }

    private void installBuiltLocked(
            Key key, PreparedRootExecutionContext built) {
        if (!key.equals(Key.of(built))) {
            throw new IllegalArgumentException(
                    "Built context changed cache key");
        }
        installIfNotOlderLocked(built);
    }

    private void evictUntilFits(long incomingWeightBytes) {
        while (!entries.isEmpty()
                && (entries.size() >= maximumSize
                || retainedWeightBytes
                > maximumWeightBytes - incomingWeightBytes)) {
            Iterator<Map.Entry<Key, Entry>> iterator =
                    entries.entrySet().iterator();
            Entry eldest = iterator.next().getValue();
            iterator.remove();
            retainedWeightBytes -= eldest.weightBytes;
            evictions++;
        }
    }

    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized int size() { return entries.size(); }
    public synchronized long retainedWeightBytes() {
        return retainedWeightBytes;
    }
    public synchronized long maximumWeightBytes() {
        return maximumWeightBytes;
    }
    public synchronized long evictions() { return evictions; }

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
