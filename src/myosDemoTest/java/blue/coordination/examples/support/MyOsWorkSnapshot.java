package blue.coordination.examples.support;

/** Deterministic work evidence; timing belongs in benchmarks, not semantics. */
public record MyOsWorkSnapshot(
        long sourceParses,
        long documentInitializations,
        long eventPreparations,
        long eventSplits,
        long fullRootReconstructions,
        long routeIndexProbes,
        long evidenceWrites,
        long fanoutChunks) {

    public MyOsWorkSnapshot minus(MyOsWorkSnapshot before) {
        return new MyOsWorkSnapshot(
                sourceParses - before.sourceParses,
                documentInitializations - before.documentInitializations,
                eventPreparations - before.eventPreparations,
                eventSplits - before.eventSplits,
                fullRootReconstructions - before.fullRootReconstructions,
                routeIndexProbes - before.routeIndexProbes,
                evidenceWrites - before.evidenceWrites,
                fanoutChunks - before.fanoutChunks);
    }
}
