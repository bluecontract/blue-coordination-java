package blue.coordination.fastpath;

import java.util.concurrent.atomic.LongAdder;

/** Live counters proving that event-time work remains selected-set local. */
public final class FastPathWorkMetrics {
    private final LongAdder admittedProjectionBuilds = new LongAdder();
    private final LongAdder admittedOccurrences = new LongAdder();
    private final LongAdder candidateLookups = new LongAdder();
    private final LongAdder scopeTraversals = new LongAdder();
    private final LongAdder rootIdentityCalculations = new LongAdder();
    private final LongAdder coldProjectionFallbacks = new LongAdder();
    private final LongAdder deltaProjectionUpdates = new LongAdder();

    public void admittedProjectionBuilt(long occurrences) {
        admittedProjectionBuilds.increment();
        admittedOccurrences.add(occurrences);
    }
    public void candidatesLookedUp(long count) { candidateLookups.add(count); }
    public void scopeTraversed() { scopeTraversals.increment(); }
    public void rootIdentityCalculated() { rootIdentityCalculations.increment(); }
    public void coldProjectionFallback() { coldProjectionFallbacks.increment(); }
    public void deltaProjectionUpdated() { deltaProjectionUpdates.increment(); }

    public Snapshot snapshot() {
        return new Snapshot(
                admittedProjectionBuilds.sum(),
                admittedOccurrences.sum(),
                candidateLookups.sum(),
                scopeTraversals.sum(),
                rootIdentityCalculations.sum(),
                coldProjectionFallbacks.sum(),
                deltaProjectionUpdates.sum());
    }

    public static final class Snapshot {
        private final long admittedProjectionBuilds;
        private final long admittedOccurrences;
        private final long candidateLookups;
        private final long scopeTraversals;
        private final long rootIdentityCalculations;
        private final long coldProjectionFallbacks;
        private final long deltaProjectionUpdates;

        Snapshot(long admittedProjectionBuilds, long admittedOccurrences,
                long candidateLookups, long scopeTraversals,
                long rootIdentityCalculations, long coldProjectionFallbacks,
                long deltaProjectionUpdates) {
            this.admittedProjectionBuilds = admittedProjectionBuilds;
            this.admittedOccurrences = admittedOccurrences;
            this.candidateLookups = candidateLookups;
            this.scopeTraversals = scopeTraversals;
            this.rootIdentityCalculations = rootIdentityCalculations;
            this.coldProjectionFallbacks = coldProjectionFallbacks;
            this.deltaProjectionUpdates = deltaProjectionUpdates;
        }

        public long admittedProjectionBuilds() { return admittedProjectionBuilds; }
        public long admittedOccurrences() { return admittedOccurrences; }
        public long candidateLookups() { return candidateLookups; }
        public long scopeTraversals() { return scopeTraversals; }
        public long rootIdentityCalculations() { return rootIdentityCalculations; }
        public long coldProjectionFallbacks() { return coldProjectionFallbacks; }
        public long deltaProjectionUpdates() { return deltaProjectionUpdates; }
    }
}
