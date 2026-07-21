package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.snapshot.FrozenNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-runner, entry- and weight-bounded LRU for immutable workflow plans. */
final class SequentialWorkflowPlanCache implements AutoCloseable {
    static final int DEFAULT_MAX_ENTRIES = 512;
    static final long DEFAULT_MAX_WEIGHT_BYTES = 32L * 1024L * 1024L;
    private static final long CACHE_ENTRY_BYTES = 64L;

    private final int maxEntries;
    private final long maxWeightBytes;
    private final BexProcessingMetrics metrics;
    private final LinkedHashMap<FrozenNode.ResolvedStructuralKey, CacheEntry> entries =
            new LinkedHashMap<FrozenNode.ResolvedStructuralKey, CacheEntry>(16, 0.75f, true);
    private long weightBytes;
    private boolean closed;

    SequentialWorkflowPlanCache(BexProcessingMetrics metrics) {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_WEIGHT_BYTES, metrics);
    }

    SequentialWorkflowPlanCache(int maxEntries,
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

    synchronized SequentialWorkflowPlan getOrBuild(FrozenNode.ResolvedStructuralKey identity,
                                                    PlanFactory factory) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        CacheEntry cached = entries.get(identity);
        if (cached != null) {
            if (metrics != null) {
                metrics.incrementWorkflowPlanCacheHits();
            }
            return cached.plan;
        }
        if (metrics != null) {
            metrics.incrementWorkflowPlanCacheMisses();
        }
        SequentialWorkflowPlan plan = factory.build();
        if (plan == null) {
            throw new IllegalStateException("workflow plan factory returned null");
        }
        if (!identity.equals(plan.contractIdentity())) {
            throw new IllegalStateException("workflow plan identity does not match cache key");
        }
        if (metrics != null) {
            metrics.incrementWorkflowPlansBuilt();
        }
        long entryWeight = entryWeight(plan);
        if (closed || entryWeight > maxWeightBytes) {
            return plan;
        }
        entries.put(identity, new CacheEntry(plan, entryWeight));
        adjustWeight(entryWeight);
        evictToBounds();
        return plan;
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
        if (entries.isEmpty()) {
            return;
        }
        entries.clear();
        long removed = weightBytes;
        weightBytes = 0L;
        if (metrics != null && removed != 0L) {
            metrics.addWorkflowPlanWeightBytes(-removed);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }

    private void evictToBounds() {
        Iterator<Map.Entry<FrozenNode.ResolvedStructuralKey, CacheEntry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || weightBytes > maxWeightBytes) && iterator.hasNext()) {
            CacheEntry eldest = iterator.next().getValue();
            iterator.remove();
            adjustWeight(-eldest.weightBytes);
            if (metrics != null) {
                metrics.incrementWorkflowPlanCacheEvictions();
            }
        }
    }

    private long entryWeight(SequentialWorkflowPlan plan) {
        long planWeight = plan.approximateWeightBytes();
        return planWeight > Long.MAX_VALUE - CACHE_ENTRY_BYTES
                ? Long.MAX_VALUE
                : planWeight + CACHE_ENTRY_BYTES;
    }

    private void adjustWeight(long delta) {
        weightBytes += delta;
        if (metrics != null) {
            metrics.addWorkflowPlanWeightBytes(delta);
        }
    }

    interface PlanFactory {
        SequentialWorkflowPlan build();
    }

    private static final class CacheEntry {
        private final SequentialWorkflowPlan plan;
        private final long weightBytes;

        private CacheEntry(SequentialWorkflowPlan plan, long weightBytes) {
            this.plan = plan;
            this.weightBytes = weightBytes;
        }
    }
}
