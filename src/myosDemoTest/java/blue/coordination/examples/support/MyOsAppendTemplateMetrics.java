package blue.coordination.examples.support;

import java.util.concurrent.atomic.AtomicLong;

/** Work evidence for canonical entry construction. */
final class MyOsAppendTemplateMetrics {

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong canonicalCompilations = new AtomicLong();
    private final AtomicLong exactMaterializations = new AtomicLong();
    private final AtomicLong patchedLeaves = new AtomicLong();
    private final AtomicLong rootBlueIdCalculations = new AtomicLong();

    void hit() { hits.incrementAndGet(); }
    void miss() { misses.incrementAndGet(); }
    void compiled() { canonicalCompilations.incrementAndGet(); }
    void materialized() { exactMaterializations.incrementAndGet(); }
    void leafPatched() { patchedLeaves.incrementAndGet(); }
    void rootBlueIdCalculated() { rootBlueIdCalculations.incrementAndGet(); }

    Snapshot snapshot() {
        return new Snapshot(
                hits.get(),
                misses.get(),
                canonicalCompilations.get(),
                exactMaterializations.get(),
                patchedLeaves.get(),
                rootBlueIdCalculations.get());
    }

    record Snapshot(
            long hits,
            long misses,
            long canonicalCompilations,
            long exactMaterializations,
            long patchedLeaves,
            long rootBlueIdCalculations) {
    }
}
