package blue.coordination.engine.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable production evidence for fragment-transition work. */
public final class CoordinationFragmentTransitionWorkSnapshot {
    private final long deltaHits;
    private final Map<String, Long> typedFallbacksByReason;
    private final long fullBlueprintAttempts;
    private final long sparseFrontierNodes;
    private final long changedFragmentsHashed;
    private final long unchangedFragmentsShared;
    private final long fullResultClones;
    private final long fullRootMaterializations;
    private final long frontierBoundaryGrafts;
    private final long expandedNodesVisited;
    private final long retainedIndexFullScans;
    private final long inventoryRecordsReused;
    private final long inventoryRecordsRebuilt;
    private final long edgeRecordsReused;
    private final long edgeRecordsRebuilt;

    public CoordinationFragmentTransitionWorkSnapshot(
            long deltaHits,
            Map<String, Long> typedFallbacksByReason,
            long fullBlueprintAttempts,
            long sparseFrontierNodes,
            long changedFragmentsHashed,
            long unchangedFragmentsShared,
            long fullResultClones,
            long fullRootMaterializations,
            long frontierBoundaryGrafts,
            long expandedNodesVisited,
            long retainedIndexFullScans,
            long inventoryRecordsReused,
            long inventoryRecordsRebuilt,
            long edgeRecordsReused,
            long edgeRecordsRebuilt) {
        this.deltaHits = nonNegative(deltaHits, "deltaHits");
        Map<String, Long> fallbacks = new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> entry : Objects.requireNonNull(
                typedFallbacksByReason,
                "typedFallbacksByReason").entrySet()) {
            String reason = Objects.requireNonNull(entry.getKey(), "reason");
            if (reason.isEmpty()) {
                throw new IllegalArgumentException("reason must not be empty");
            }
            fallbacks.put(
                    reason,
                    Long.valueOf(nonNegative(
                            Objects.requireNonNull(
                                    entry.getValue(), "fallback count")
                                    .longValue(),
                            "fallback count")));
        }
        this.typedFallbacksByReason = Collections.unmodifiableMap(fallbacks);
        this.fullBlueprintAttempts = nonNegative(
                fullBlueprintAttempts, "fullBlueprintAttempts");
        this.sparseFrontierNodes = nonNegative(
                sparseFrontierNodes, "sparseFrontierNodes");
        this.changedFragmentsHashed = nonNegative(
                changedFragmentsHashed, "changedFragmentsHashed");
        this.unchangedFragmentsShared = nonNegative(
                unchangedFragmentsShared, "unchangedFragmentsShared");
        this.fullResultClones = nonNegative(
                fullResultClones, "fullResultClones");
        this.fullRootMaterializations = nonNegative(
                fullRootMaterializations, "fullRootMaterializations");
        this.frontierBoundaryGrafts = nonNegative(
                frontierBoundaryGrafts, "frontierBoundaryGrafts");
        this.expandedNodesVisited = nonNegative(
                expandedNodesVisited, "expandedNodesVisited");
        this.retainedIndexFullScans = nonNegative(
                retainedIndexFullScans, "retainedIndexFullScans");
        this.inventoryRecordsReused = nonNegative(
                inventoryRecordsReused, "inventoryRecordsReused");
        this.inventoryRecordsRebuilt = nonNegative(
                inventoryRecordsRebuilt, "inventoryRecordsRebuilt");
        this.edgeRecordsReused = nonNegative(
                edgeRecordsReused, "edgeRecordsReused");
        this.edgeRecordsRebuilt = nonNegative(
                edgeRecordsRebuilt, "edgeRecordsRebuilt");
    }

    public long deltaHits() { return deltaHits; }
    public Map<String, Long> typedFallbacksByReason() {
        return typedFallbacksByReason;
    }
    public long typedFallbackCount() {
        long result = 0L;
        for (Long value : typedFallbacksByReason.values()) {
            result += value.longValue();
        }
        return result;
    }
    public long fullBlueprintAttempts() { return fullBlueprintAttempts; }
    public long sparseFrontierNodes() { return sparseFrontierNodes; }
    public long changedFragmentsHashed() { return changedFragmentsHashed; }
    public long unchangedFragmentsShared() {
        return unchangedFragmentsShared;
    }
    public long fullResultClones() { return fullResultClones; }
    public long fullRootMaterializations() {
        return fullRootMaterializations;
    }
    public long frontierBoundaryGrafts() { return frontierBoundaryGrafts; }
    public long expandedNodesVisited() { return expandedNodesVisited; }
    public long retainedIndexFullScans() { return retainedIndexFullScans; }
    public long inventoryRecordsReused() { return inventoryRecordsReused; }
    public long inventoryRecordsRebuilt() { return inventoryRecordsRebuilt; }
    public long edgeRecordsReused() { return edgeRecordsReused; }
    public long edgeRecordsRebuilt() { return edgeRecordsRebuilt; }

    public double unchangedFragmentShareRatio() {
        long total = unchangedFragmentsShared + changedFragmentsHashed;
        return total == 0L
                ? 1.0d
                : ((double) unchangedFragmentsShared) / ((double) total);
    }

    /** Returns exact monotonic work performed after an earlier snapshot. */
    public CoordinationFragmentTransitionWorkSnapshot minus(
            CoordinationFragmentTransitionWorkSnapshot before) {
        CoordinationFragmentTransitionWorkSnapshot checked =
                Objects.requireNonNull(before, "before");
        Map<String, Long> fallbackDelta =
                new LinkedHashMap<String, Long>();
        for (Map.Entry<String, Long> current
                : typedFallbacksByReason.entrySet()) {
            long prior = checked.typedFallbacksByReason.containsKey(
                    current.getKey())
                    ? checked.typedFallbacksByReason
                            .get(current.getKey()).longValue()
                    : 0L;
            long delta = current.getValue().longValue() - prior;
            if (delta != 0L) {
                fallbackDelta.put(current.getKey(), Long.valueOf(delta));
            }
        }
        for (Map.Entry<String, Long> prior
                : checked.typedFallbacksByReason.entrySet()) {
            if (!typedFallbacksByReason.containsKey(prior.getKey())) {
                fallbackDelta.put(
                        prior.getKey(),
                        Long.valueOf(-prior.getValue().longValue()));
            }
        }
        return new CoordinationFragmentTransitionWorkSnapshot(
                deltaHits - checked.deltaHits,
                fallbackDelta,
                fullBlueprintAttempts - checked.fullBlueprintAttempts,
                sparseFrontierNodes - checked.sparseFrontierNodes,
                changedFragmentsHashed - checked.changedFragmentsHashed,
                unchangedFragmentsShared - checked.unchangedFragmentsShared,
                fullResultClones - checked.fullResultClones,
                fullRootMaterializations
                        - checked.fullRootMaterializations,
                frontierBoundaryGrafts - checked.frontierBoundaryGrafts,
                expandedNodesVisited - checked.expandedNodesVisited,
                retainedIndexFullScans - checked.retainedIndexFullScans,
                inventoryRecordsReused - checked.inventoryRecordsReused,
                inventoryRecordsRebuilt - checked.inventoryRecordsRebuilt,
                edgeRecordsReused - checked.edgeRecordsReused,
                edgeRecordsRebuilt - checked.edgeRecordsRebuilt);
    }

    @Override
    public String toString() {
        return "CoordinationFragmentTransitionWorkSnapshot{deltaHits="
                + deltaHits
                + ", typedFallbacksByReason=" + typedFallbacksByReason
                + ", fullBlueprintAttempts=" + fullBlueprintAttempts
                + ", changedFragmentsHashed=" + changedFragmentsHashed
                + ", unchangedFragmentsShared=" + unchangedFragmentsShared
                + ", fullResultClones=" + fullResultClones
                + ", fullRootMaterializations=" + fullRootMaterializations
                + ", retainedIndexFullScans=" + retainedIndexFullScans
                + '}';
    }

    private static long nonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        return value;
    }
}
