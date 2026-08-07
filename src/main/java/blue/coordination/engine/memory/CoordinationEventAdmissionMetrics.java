package blue.coordination.engine.memory;

import java.util.concurrent.atomic.AtomicLong;

/** Low-cost counters attached to real event-admission work sites. */
public final class CoordinationEventAdmissionMetrics {

    private final AtomicLong templateHits = new AtomicLong();
    private final AtomicLong templateMisses = new AtomicLong();
    private final AtomicLong templateCompilations = new AtomicLong();
    private final AtomicLong fullEventSplits = new AtomicLong();
    private final AtomicLong admittedFragments = new AtomicLong();
    private final AtomicLong reusedFragments = new AtomicLong();
    private final AtomicLong wireFingerprints = new AtomicLong();
    private final AtomicLong fragmentEvidenceHits = new AtomicLong();
    private final AtomicLong fragmentEvidenceMisses = new AtomicLong();
    private final AtomicLong blueIdCalculations = new AtomicLong();
    private final AtomicLong winnerReadBacks = new AtomicLong();
    private final AtomicLong nodeMaterializations = new AtomicLong();

    public void templateHit() { templateHits.incrementAndGet(); }
    public void templateMiss() { templateMisses.incrementAndGet(); }
    public void templateCompiled() { templateCompilations.incrementAndGet(); }
    public void fullEventSplit() { fullEventSplits.incrementAndGet(); }
    public void fragmentAdmitted() { admittedFragments.incrementAndGet(); }
    public void fragmentReused() { reusedFragments.incrementAndGet(); }
    public void wireFingerprint() { wireFingerprints.incrementAndGet(); }
    public void fragmentEvidenceHit() {
        fragmentEvidenceHits.incrementAndGet();
    }
    public void fragmentEvidenceMiss() {
        fragmentEvidenceMisses.incrementAndGet();
    }
    public void blueIdCalculation() { blueIdCalculations.incrementAndGet(); }
    public void winnerReadBack() { winnerReadBacks.incrementAndGet(); }
    public void nodeMaterialized() { nodeMaterializations.incrementAndGet(); }

    public Snapshot snapshot() {
        return new Snapshot(
                templateHits.get(),
                templateMisses.get(),
                templateCompilations.get(),
                fullEventSplits.get(),
                admittedFragments.get(),
                reusedFragments.get(),
                wireFingerprints.get(),
                fragmentEvidenceHits.get(),
                fragmentEvidenceMisses.get(),
                blueIdCalculations.get(),
                winnerReadBacks.get(),
                nodeMaterializations.get());
    }

    /** Immutable counter sample with record-style accessors for Java 8. */
    public static final class Snapshot {
        private final long templateHits;
        private final long templateMisses;
        private final long templateCompilations;
        private final long fullEventSplits;
        private final long admittedFragments;
        private final long reusedFragments;
        private final long wireFingerprints;
        private final long fragmentEvidenceHits;
        private final long fragmentEvidenceMisses;
        private final long blueIdCalculations;
        private final long winnerReadBacks;
        private final long nodeMaterializations;

        public Snapshot(
                long templateHits,
                long templateMisses,
                long templateCompilations,
                long fullEventSplits,
                long admittedFragments,
                long reusedFragments,
                long wireFingerprints,
                long fragmentEvidenceHits,
                long fragmentEvidenceMisses,
                long blueIdCalculations,
                long winnerReadBacks,
                long nodeMaterializations) {
            this.templateHits = templateHits;
            this.templateMisses = templateMisses;
            this.templateCompilations = templateCompilations;
            this.fullEventSplits = fullEventSplits;
            this.admittedFragments = admittedFragments;
            this.reusedFragments = reusedFragments;
            this.wireFingerprints = wireFingerprints;
            this.fragmentEvidenceHits = fragmentEvidenceHits;
            this.fragmentEvidenceMisses = fragmentEvidenceMisses;
            this.blueIdCalculations = blueIdCalculations;
            this.winnerReadBacks = winnerReadBacks;
            this.nodeMaterializations = nodeMaterializations;
        }

        public long templateHits() { return templateHits; }
        public long templateMisses() { return templateMisses; }
        public long templateCompilations() { return templateCompilations; }
        public long fullEventSplits() { return fullEventSplits; }
        public long admittedFragments() { return admittedFragments; }
        public long reusedFragments() { return reusedFragments; }
        public long wireFingerprints() { return wireFingerprints; }
        public long fragmentEvidenceHits() { return fragmentEvidenceHits; }
        public long fragmentEvidenceMisses() {
            return fragmentEvidenceMisses;
        }
        public long blueIdCalculations() { return blueIdCalculations; }
        public long winnerReadBacks() { return winnerReadBacks; }
        public long nodeMaterializations() { return nodeMaterializations; }

        public Snapshot minus(Snapshot prior) {
            if (prior == null) {
                throw new NullPointerException("prior");
            }
            return new Snapshot(
                    templateHits - prior.templateHits,
                    templateMisses - prior.templateMisses,
                    templateCompilations - prior.templateCompilations,
                    fullEventSplits - prior.fullEventSplits,
                    admittedFragments - prior.admittedFragments,
                    reusedFragments - prior.reusedFragments,
                    wireFingerprints - prior.wireFingerprints,
                    fragmentEvidenceHits - prior.fragmentEvidenceHits,
                    fragmentEvidenceMisses - prior.fragmentEvidenceMisses,
                    blueIdCalculations - prior.blueIdCalculations,
                    winnerReadBacks - prior.winnerReadBacks,
                    nodeMaterializations - prior.nodeMaterializations);
        }
    }
}
