package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Thread-safe, entry- and weight-bounded LRU for immutable Compute plans. */
final class ComputeProgramPlanCache implements AutoCloseable {
    static final int DEFAULT_MAX_ENTRIES = 256;
    static final long DEFAULT_MAX_WEIGHT_BYTES = 16L * 1024L * 1024L;

    interface PlanFactory {
        ComputeProgramPlan create();
    }

    static final class Key {
        private final FrozenNode.ResolvedStructuralKey rawStepIdentity;
        private final FrozenNode.ResolvedStructuralKey definitionIdentity;
        private final String effectiveEntry;
        private final String normalizationVersion;
        private final int hashCode;

        private Key(FrozenNode rawStepNode,
                    FrozenNode rawDefinitionNode,
                    String effectiveEntry,
                    String normalizationVersion) {
            if (rawStepNode == null) {
                throw new IllegalArgumentException("rawStepNode must not be null");
            }
            if (normalizationVersion == null || normalizationVersion.isEmpty()) {
                throw new IllegalArgumentException("normalizationVersion must not be empty");
            }
            this.rawStepIdentity = rawStepNode.resolvedStructuralKey();
            this.definitionIdentity = rawDefinitionNode != null
                    ? rawDefinitionNode.resolvedStructuralKey()
                    : null;
            this.effectiveEntry = effectiveEntry;
            this.normalizationVersion = normalizationVersion;
            this.hashCode = Objects.hash(rawStepIdentity,
                    definitionIdentity,
                    effectiveEntry,
                    normalizationVersion);
        }

        static Key from(FrozenNode rawStepNode,
                        FrozenNode rawDefinitionNode,
                        String effectiveEntry,
                        String normalizationVersion) {
            return new Key(rawStepNode,
                    rawDefinitionNode,
                    effectiveEntry,
                    normalizationVersion);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return rawStepIdentity.equals(that.rawStepIdentity)
                    && Objects.equals(definitionIdentity, that.definitionIdentity)
                    && Objects.equals(effectiveEntry, that.effectiveEntry)
                    && normalizationVersion.equals(that.normalizationVersion);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    static final class Lookup {
        private final Key key;
        private final ComputeProgramPlan plan;
        private final boolean cacheHit;
        private final long generation;

        private Lookup(Key key,
                       ComputeProgramPlan plan,
                       boolean cacheHit,
                       long generation) {
            this.key = key;
            this.plan = plan;
            this.cacheHit = cacheHit;
            this.generation = generation;
        }

        ComputeProgramPlan plan() {
            return plan;
        }

        boolean cacheHit() {
            return cacheHit;
        }
    }

    private static final class Entry {
        private final ComputeProgramPlan plan;
        private final long weightBytes;

        private Entry(ComputeProgramPlan plan) {
            this.plan = plan;
            this.weightBytes = plan.approximateWeightBytes();
        }
    }

    private final int maxEntries;
    private final long maxWeightBytes;
    private final BexProcessingMetrics metrics;
    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<Key, Entry>(16, 0.75f, true);
    private long weightBytes;
    private long generation;
    private boolean closed;

    ComputeProgramPlanCache() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_WEIGHT_BYTES, null);
    }

    ComputeProgramPlanCache(int maxEntries,
                            long maxWeightBytes,
                            BexProcessingMetrics metrics) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxWeightBytes <= 0L) {
            throw new IllegalArgumentException("maxWeightBytes must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxWeightBytes = maxWeightBytes;
        this.metrics = metrics;
    }

    Lookup lookup(Key key, PlanFactory factory) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        long lookupGeneration;
        synchronized (this) {
            Entry cached = entries.get(key);
            if (cached != null) {
                if (metrics != null) {
                    metrics.incrementComputePlanCacheHits();
                }
                return new Lookup(key, cached.plan, true, generation);
            }
            if (metrics != null) {
                metrics.incrementComputePlanCacheMisses();
            }
            lookupGeneration = generation;
        }

        ComputeProgramPlan plan = factory.create();
        if (plan == null) {
            throw new IllegalStateException("Compute plan factory returned null");
        }
        if (metrics != null) {
            metrics.incrementComputePlansBuilt();
        }
        return new Lookup(key, plan, false, lookupGeneration);
    }

    void publish(Lookup lookup) {
        if (lookup == null || lookup.cacheHit) {
            return;
        }
        Entry added = new Entry(lookup.plan);
        if (added.weightBytes > maxWeightBytes) {
            return;
        }
        synchronized (this) {
            if (closed || lookup.generation != generation) {
                return;
            }
            Entry existing = entries.get(lookup.key);
            if (existing != null) {
                return;
            }
            entries.put(lookup.key, added);
            weightBytes = saturatedAdd(weightBytes, added.weightBytes);
            if (metrics != null) {
                metrics.addComputePlanWeightBytes(added.weightBytes);
            }
            evictIfNeeded();
        }
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long weightBytes() {
        return weightBytes;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    synchronized void clear() {
        if (weightBytes != 0L && metrics != null) {
            metrics.addComputePlanWeightBytes(-weightBytes);
        }
        entries.clear();
        weightBytes = 0L;
        generation++;
    }

    @Override
    public synchronized void close() {
        clear();
        closed = true;
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || weightBytes > maxWeightBytes)
                && iterator.hasNext()) {
            Map.Entry<Key, Entry> eldest = iterator.next();
            long evictedWeight = eldest.getValue().weightBytes;
            iterator.remove();
            weightBytes -= evictedWeight;
            if (metrics != null) {
                metrics.incrementComputePlanCacheEvictions();
                metrics.addComputePlanWeightBytes(-evictedWeight);
            }
        }
    }

    private long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
