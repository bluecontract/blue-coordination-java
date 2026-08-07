package blue.coordination.examples.support;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe counters so a future dispatcher can retain the same evidence. */
public final class MyOsWorkRecorder {

    private final LongAdder sourceParses = new LongAdder();
    private final LongAdder documentInitializations = new LongAdder();
    private final LongAdder eventPreparations = new LongAdder();
    private final LongAdder eventSplits = new LongAdder();
    private final LongAdder fullRootReconstructions = new LongAdder();
    private final LongAdder routeIndexProbes = new LongAdder();
    private final LongAdder evidenceWrites = new LongAdder();
    private final LongAdder fanoutChunks = new LongAdder();

    public void sourceParsed() {
        sourceParses.increment();
    }

    public void documentInitialized() {
        documentInitializations.increment();
    }

    public void eventPrepared() {
        eventPreparations.increment();
    }

    /** Records canonical splits observed at the engine's real work site. */
    public void eventSplits(long count) {
        if (count < 0L) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        eventSplits.add(count);
    }

    public void routeIndexProbed() {
        routeIndexProbes.increment();
    }

    public void fullRootReconstructed() {
        fullRootReconstructions.increment();
    }

    public void evidenceWritten() {
        evidenceWrites.increment();
    }

    public void fanoutChunkProcessed() {
        fanoutChunks.increment();
    }

    public MyOsWorkSnapshot snapshot() {
        return new MyOsWorkSnapshot(
                sourceParses.sum(),
                documentInitializations.sum(),
                eventPreparations.sum(),
                eventSplits.sum(),
                fullRootReconstructions.sum(),
                routeIndexProbes.sum(),
                evidenceWrites.sum(),
                fanoutChunks.sum());
    }
}
