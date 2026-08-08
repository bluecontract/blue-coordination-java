package blue.coordination.engine.internal;

import blue.coordination.engine.api.CoordinationFragmentTransitionWorkSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free counters wired to the verified fragment transition planner. */
final class CoordinationFragmentTransitionMetrics {
    private final AtomicLong deltaHits = new AtomicLong();
    private final AtomicLong sparseFrontierNodes = new AtomicLong();
    private final AtomicLong changedFragmentsHashed = new AtomicLong();
    private final AtomicLong unchangedFragmentsShared = new AtomicLong();
    private final AtomicLong fullBlueprintAttempts = new AtomicLong();
    private final AtomicLong fullResultClones = new AtomicLong();
    private final AtomicLong inventoryRecordsReused = new AtomicLong();
    private final AtomicLong inventoryRecordsRebuilt = new AtomicLong();
    private final AtomicLong edgeRecordsReused = new AtomicLong();
    private final AtomicLong edgeRecordsRebuilt = new AtomicLong();
    private final Map<CoordinationIncrementalFragmentAssembler.ColdGraftReason,
            AtomicLong> fallbacks =
            new java.util.EnumMap<
                    CoordinationIncrementalFragmentAssembler.ColdGraftReason,
                    AtomicLong>(
                    CoordinationIncrementalFragmentAssembler
                            .ColdGraftReason.class);

    CoordinationFragmentTransitionMetrics() {
        for (CoordinationIncrementalFragmentAssembler.ColdGraftReason reason
                : CoordinationIncrementalFragmentAssembler
                        .ColdGraftReason.values()) {
            fallbacks.put(reason, new AtomicLong());
        }
    }

    void deltaHit(
            long frontierNodes,
            CoordinationIncrementalFragmentAssembler.AssembledDocument
                    assembled) {
        deltaHits.incrementAndGet();
        sparseFrontierNodes.addAndGet(frontierNodes);
        changedFragmentsHashed.addAndGet(assembled.hashedFragmentCount());
        unchangedFragmentsShared.addAndGet(assembled.reusedFragmentCount());
        inventoryRecordsReused.addAndGet(
                assembled.reusedInventoryRecordCount());
        inventoryRecordsRebuilt.addAndGet(
                assembled.rebuiltInventoryRecordCount());
        edgeRecordsReused.addAndGet(assembled.reusedEdgeRecordCount());
        edgeRecordsRebuilt.addAndGet(assembled.rebuiltEdgeRecordCount());
    }

    void fallback(
            CoordinationIncrementalFragmentAssembler.ColdGraftReason reason) {
        fallbacks.get(reason).incrementAndGet();
    }

    void fullBlueprintAttempt() {
        fullBlueprintAttempts.incrementAndGet();
    }

    void fullResultClones(long count) {
        if (count < 0L) {
            throw new IllegalArgumentException(
                    "full result clone count must be non-negative");
        }
        fullResultClones.addAndGet(count);
    }

    CoordinationFragmentTransitionWorkSnapshot snapshot() {
        Map<String, Long> byReason = new LinkedHashMap<String, Long>();
        for (Map.Entry<CoordinationIncrementalFragmentAssembler
                .ColdGraftReason, AtomicLong> entry : fallbacks.entrySet()) {
            long count = entry.getValue().get();
            if (count != 0L) {
                byReason.put(entry.getKey().name(), Long.valueOf(count));
            }
        }
        return new CoordinationFragmentTransitionWorkSnapshot(
                deltaHits.get(),
                byReason,
                fullResultClones.get(),
                sparseFrontierNodes.get(),
                changedFragmentsHashed.get(),
                unchangedFragmentsShared.get(),
                fullBlueprintAttempts.get(),
                0L,
                0L,
                0L,
                0L,
                inventoryRecordsReused.get(),
                inventoryRecordsRebuilt.get(),
                edgeRecordsReused.get(),
                edgeRecordsRebuilt.get());
    }
}
