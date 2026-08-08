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
    private final LongAdder affectedOccurrences = new LongAdder();
    private final LongAdder refreshedOccurrences = new LongAdder();
    private final LongAdder unrelatedOccurrences = new LongAdder();
    private final LongAdder snapshotSerializations = new LongAdder();
    private final LongAdder snapshotSerializedOccurrences = new LongAdder();
    private final LongAdder fullProjectorFallbacks = new LongAdder();
    private final LongAdder catalogFallbacks = new LongAdder();
    private final LongAdder merkleOccurrenceUpdates = new LongAdder();

    public void admittedProjectionBuilt(long occurrences) {
        admittedProjectionBuilds.increment();
        admittedOccurrences.add(occurrences);
    }
    public void candidatesLookedUp(long count) { candidateLookups.add(count); }
    public void scopeTraversed() { scopeTraversals.increment(); }
    public void rootIdentityCalculated() { rootIdentityCalculations.increment(); }
    public void coldProjectionFallback() { coldProjectionFallbacks.increment(); }
    public void deltaProjectionUpdated() { deltaProjectionUpdates.increment(); }
    public void deltaProjectionUpdated(
            long affected,
            long refreshed,
            long unrelated) {
        deltaProjectionUpdates.increment();
        affectedOccurrences.add(nonNegative(affected, "affected"));
        refreshedOccurrences.add(nonNegative(refreshed, "refreshed"));
        unrelatedOccurrences.add(nonNegative(unrelated, "unrelated"));
    }
    public void snapshotSerialized(long occurrences) {
        snapshotSerializations.increment();
        snapshotSerializedOccurrences.add(
                nonNegative(occurrences, "occurrences"));
    }
    public void fullProjectorFallback() {
        coldProjectionFallbacks.increment();
        fullProjectorFallbacks.increment();
    }
    public void catalogFallback() { catalogFallbacks.increment(); }
    public void merkleOccurrencesUpdated(long count) {
        merkleOccurrenceUpdates.add(nonNegative(count, "count"));
    }

    public Snapshot snapshot() {
        return new Snapshot(
                admittedProjectionBuilds.sum(),
                admittedOccurrences.sum(),
                candidateLookups.sum(),
                scopeTraversals.sum(),
                rootIdentityCalculations.sum(),
                coldProjectionFallbacks.sum(),
                deltaProjectionUpdates.sum(),
                affectedOccurrences.sum(),
                refreshedOccurrences.sum(),
                unrelatedOccurrences.sum(),
                snapshotSerializations.sum(),
                snapshotSerializedOccurrences.sum(),
                fullProjectorFallbacks.sum(),
                catalogFallbacks.sum(),
                merkleOccurrenceUpdates.sum());
    }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }

    public static final class Snapshot {
        private final long admittedProjectionBuilds;
        private final long admittedOccurrences;
        private final long candidateLookups;
        private final long scopeTraversals;
        private final long rootIdentityCalculations;
        private final long coldProjectionFallbacks;
        private final long deltaProjectionUpdates;
        private final long affectedOccurrences;
        private final long refreshedOccurrences;
        private final long unrelatedOccurrences;
        private final long snapshotSerializations;
        private final long snapshotSerializedOccurrences;
        private final long fullProjectorFallbacks;
        private final long catalogFallbacks;
        private final long merkleOccurrenceUpdates;

        Snapshot(long admittedProjectionBuilds, long admittedOccurrences,
                long candidateLookups, long scopeTraversals,
                long rootIdentityCalculations, long coldProjectionFallbacks,
                long deltaProjectionUpdates, long affectedOccurrences,
                long refreshedOccurrences, long unrelatedOccurrences,
                long snapshotSerializations,
                long snapshotSerializedOccurrences,
                long fullProjectorFallbacks, long catalogFallbacks,
                long merkleOccurrenceUpdates) {
            this.admittedProjectionBuilds = admittedProjectionBuilds;
            this.admittedOccurrences = admittedOccurrences;
            this.candidateLookups = candidateLookups;
            this.scopeTraversals = scopeTraversals;
            this.rootIdentityCalculations = rootIdentityCalculations;
            this.coldProjectionFallbacks = coldProjectionFallbacks;
            this.deltaProjectionUpdates = deltaProjectionUpdates;
            this.affectedOccurrences = affectedOccurrences;
            this.refreshedOccurrences = refreshedOccurrences;
            this.unrelatedOccurrences = unrelatedOccurrences;
            this.snapshotSerializations = snapshotSerializations;
            this.snapshotSerializedOccurrences = snapshotSerializedOccurrences;
            this.fullProjectorFallbacks = fullProjectorFallbacks;
            this.catalogFallbacks = catalogFallbacks;
            this.merkleOccurrenceUpdates = merkleOccurrenceUpdates;
        }

        public long admittedProjectionBuilds() { return admittedProjectionBuilds; }
        public long admittedOccurrences() { return admittedOccurrences; }
        public long candidateLookups() { return candidateLookups; }
        public long scopeTraversals() { return scopeTraversals; }
        public long rootIdentityCalculations() { return rootIdentityCalculations; }
        public long coldProjectionFallbacks() { return coldProjectionFallbacks; }
        public long deltaProjectionUpdates() { return deltaProjectionUpdates; }
        public long affectedOccurrences() { return affectedOccurrences; }
        public long refreshedOccurrences() { return refreshedOccurrences; }
        public long unrelatedOccurrences() { return unrelatedOccurrences; }
        public long snapshotSerializations() { return snapshotSerializations; }
        public long snapshotSerializedOccurrences() {
            return snapshotSerializedOccurrences;
        }
        public long fullProjectorFallbacks() { return fullProjectorFallbacks; }
        public long catalogFallbacks() { return catalogFallbacks; }
        public long merkleOccurrenceUpdates() {
            return merkleOccurrenceUpdates;
        }

        /**
         * Returns validated same-source per-operation evidence.
         *
         * @throws IllegalArgumentException when {@code earlier} is not an
         *         earlier snapshot from counters that only advance
         */
        public Snapshot minus(Snapshot earlier) {
            if (earlier == null) {
                throw new NullPointerException("earlier");
            }
            return new Snapshot(
                    difference(admittedProjectionBuilds,
                            earlier.admittedProjectionBuilds,
                            "admittedProjectionBuilds"),
                    difference(admittedOccurrences,
                            earlier.admittedOccurrences,
                            "admittedOccurrences"),
                    difference(candidateLookups, earlier.candidateLookups,
                            "candidateLookups"),
                    difference(scopeTraversals, earlier.scopeTraversals,
                            "scopeTraversals"),
                    difference(rootIdentityCalculations,
                            earlier.rootIdentityCalculations,
                            "rootIdentityCalculations"),
                    difference(coldProjectionFallbacks,
                            earlier.coldProjectionFallbacks,
                            "coldProjectionFallbacks"),
                    difference(deltaProjectionUpdates,
                            earlier.deltaProjectionUpdates,
                            "deltaProjectionUpdates"),
                    difference(affectedOccurrences,
                            earlier.affectedOccurrences,
                            "affectedOccurrences"),
                    difference(refreshedOccurrences,
                            earlier.refreshedOccurrences,
                            "refreshedOccurrences"),
                    difference(unrelatedOccurrences,
                            earlier.unrelatedOccurrences,
                            "unrelatedOccurrences"),
                    difference(snapshotSerializations,
                            earlier.snapshotSerializations,
                            "snapshotSerializations"),
                    difference(snapshotSerializedOccurrences,
                            earlier.snapshotSerializedOccurrences,
                            "snapshotSerializedOccurrences"),
                    difference(fullProjectorFallbacks,
                            earlier.fullProjectorFallbacks,
                            "fullProjectorFallbacks"),
                    difference(catalogFallbacks, earlier.catalogFallbacks,
                            "catalogFallbacks"),
                    difference(merkleOccurrenceUpdates,
                            earlier.merkleOccurrenceUpdates,
                            "merkleOccurrenceUpdates"));
        }

        private static long difference(
                long current,
                long earlier,
                String label) {
            if (earlier < 0L || current < earlier) {
                throw new IllegalArgumentException(
                        label + " did not advance monotonically");
            }
            return current - earlier;
        }
    }
}
