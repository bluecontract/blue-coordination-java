package blue.coordination.examples.support;

import blue.coordination.engine.memory.CoordinationEngineWorkSnapshot;

import java.util.Objects;

/**
 * Exact work composed only from counters wired to live work sites.
 *
 * <p>Entry and route counters are advanced by the demo host at the authored
 * append/dispatch sites. Engine counters come from lifecycle callbacks and
 * store counters come from the physical fragment-store boundary. Unsupported
 * measurements are intentionally absent instead of silently reading zero.</p>
 */
public record MyOsMeasuredWork(
        long sourceParses,
        long documentInitializations,
        long eventPreparations,
        long eventSplits,
        long routeIndexProbes,
        long fanoutPages,
        CoordinationEngineWorkSnapshot engine,
        long storeSingleReads,
        long storeBatchReads,
        long storeRequestedIdentities) {

    public MyOsMeasuredWork {
        nonNegative(sourceParses, "sourceParses");
        nonNegative(documentInitializations, "documentInitializations");
        nonNegative(eventPreparations, "eventPreparations");
        nonNegative(eventSplits, "eventSplits");
        nonNegative(routeIndexProbes, "routeIndexProbes");
        nonNegative(fanoutPages, "fanoutPages");
        engine = Objects.requireNonNull(engine, "engine");
        nonNegative(storeSingleReads, "storeSingleReads");
        nonNegative(storeBatchReads, "storeBatchReads");
        nonNegative(storeRequestedIdentities, "storeRequestedIdentities");
    }

    public MyOsMeasuredWork minus(MyOsMeasuredWork before) {
        MyOsMeasuredWork checked = Objects.requireNonNull(before, "before");
        return new MyOsMeasuredWork(
                sourceParses - checked.sourceParses,
                documentInitializations - checked.documentInitializations,
                eventPreparations - checked.eventPreparations,
                eventSplits - checked.eventSplits,
                routeIndexProbes - checked.routeIndexProbes,
                fanoutPages - checked.fanoutPages,
                engine.minus(checked.engine),
                storeSingleReads - checked.storeSingleReads,
                storeBatchReads - checked.storeBatchReads,
                storeRequestedIdentities
                        - checked.storeRequestedIdentities);
    }

    private static void nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
    }
}
