package blue.coordination.engine.fastpath;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Real work counters; none are inferred from a test helper. */
public final class ReferenceCutMetrics {
    private final LongAdder compilations = new LongAdder();
    private final LongAdder inventoryCompilations = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder sparseUses = new LongAdder();
    private final LongAdder fullRootUses = new LongAdder();
    private final LongAdder plannedArtifactReuses = new LongAdder();
    private final LongAdder plannedArtifactFallbacks = new LongAdder();
    private final LongAdder plannedArtifactNotApplicable = new LongAdder();
    private final LongAdder processRootSelections = new LongAdder();
    private final LongAdder processActivePaths = new LongAdder();
    private final LongAdder processInventoryFragments = new LongAdder();
    private final LongAdder processMaterializedFragments = new LongAdder();
    private final LongAdder processSparseNodes = new LongAdder();
    private final LongAdder cutEdges = new LongAdder();
    private final LongAdder fullNodes = new LongAdder();
    private final LongAdder sparseNodes = new LongAdder();
    private final LongAdder inventoryFragments = new LongAdder();
    private final LongAdder materializedFragments = new LongAdder();
    private final LongAdder canonicalFragmentsRead = new LongAdder();
    private final LongAdder fullRootMaterializationsAvoided = new LongAdder();
    private final LongAdder identityChecks = new LongAdder();
    private final LongAdder identityFailures = new LongAdder();
    private final LongAdder canonicalBatchReads = new LongAdder();
    private final LongAdder canonicalSingleReads = new LongAdder();
    private final LongAdder verifiedHandleBatches = new LongAdder();
    private final LongAdder portableCanonicalBatches = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheFlightLeaders = new LongAdder();
    private final LongAdder cacheFlightWaiters = new LongAdder();
    private final LongAdder cacheFailures = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final LongAdder cacheLoadNanos = new LongAdder();

    void compilation() { compilations.increment(); }
    void inventoryCompilation() { inventoryCompilations.increment(); }
    void cacheHit() { cacheHits.increment(); }
    public void sparseUsed() { sparseUses.increment(); }
    public void fullRootUsed() { fullRootUses.increment(); }
    public void plannedArtifactReused() {
        plannedArtifactReuses.increment();
    }
    public void plannedArtifactFallback() {
        plannedArtifactFallbacks.increment();
    }
    public void plannedArtifactNotApplicable() {
        plannedArtifactNotApplicable.increment();
    }
    /** Records exact PROCESS-shape counts already present on the artifact. */
    public void processSelection(
            int activePathCount,
            ReferenceCutRootArtifact artifact) {
        processRootSelections.increment();
        processActivePaths.add(requireNonNegative(
                activePathCount, "activePathCount"));
        if (artifact != null) {
            processInventoryFragments.add(
                    artifact.inventoryFragmentCount());
            processMaterializedFragments.add(
                    artifact.materializedFragmentCount());
            processSparseNodes.add(artifact.sparseStats().nodes());
        }
    }
    void cutEdges(long count) { cutEdges.add(count); }
    void fullNodes(long count) { fullNodes.add(count); }
    void sparseNodes(long count) { sparseNodes.add(count); }
    void inventorySelection(long total, long materialized) {
        long checkedTotal = requireNonNegative(total, "total");
        long checkedMaterialized = requireNonNegative(
                materialized, "materialized");
        if (checkedMaterialized > checkedTotal) {
            throw new IllegalArgumentException(
                    "materialized fragments exceed inventory fragments");
        }
        inventoryFragments.add(checkedTotal);
        materializedFragments.add(checkedMaterialized);
    }
    void canonicalFragmentsRead(long count) {
        canonicalFragmentsRead.add(count);
    }
    void fullRootMaterializationAvoided() {
        fullRootMaterializationsAvoided.increment();
    }
    void identityCheck() { identityChecks.increment(); }
    void identityFailure() { identityFailures.increment(); }
    void canonicalBatchReads(long count) {
        canonicalBatchReads.add(requireNonNegative(count, "count"));
    }
    void canonicalSingleReads(long count) {
        canonicalSingleReads.add(requireNonNegative(count, "count"));
    }
    void verifiedHandleBatches(long count) {
        verifiedHandleBatches.add(requireNonNegative(count, "count"));
    }
    void portableCanonicalBatches(long count) {
        portableCanonicalBatches.add(requireNonNegative(count, "count"));
    }
    void cacheMiss() { cacheMisses.increment(); }
    void cacheFlightLeader() { cacheFlightLeaders.increment(); }
    void cacheFlightWaiter() { cacheFlightWaiters.increment(); }
    void cacheFailure() { cacheFailures.increment(); }
    void cacheEvictions(long count) {
        cacheEvictions.add(requireNonNegative(count, "count"));
    }
    void cacheLoadNanos(long nanos) {
        cacheLoadNanos.add(requireNonNegative(nanos, "nanos"));
    }

    public Snapshot snapshot() {
        return new Snapshot(
                compilations.sum(), inventoryCompilations.sum(),
                cacheHits.sum(), sparseUses.sum(), fullRootUses.sum(),
                plannedArtifactReuses.sum(),
                plannedArtifactFallbacks.sum(),
                plannedArtifactNotApplicable.sum(),
                processRootSelections.sum(), processActivePaths.sum(),
                processInventoryFragments.sum(),
                processMaterializedFragments.sum(),
                processSparseNodes.sum(),
                cutEdges.sum(), fullNodes.sum(), sparseNodes.sum(),
                inventoryFragments.sum(), materializedFragments.sum(),
                canonicalFragmentsRead.sum(),
                fullRootMaterializationsAvoided.sum(),
                identityChecks.sum(), identityFailures.sum(),
                canonicalBatchReads.sum(), canonicalSingleReads.sum(),
                verifiedHandleBatches.sum(),
                portableCanonicalBatches.sum(),
                cacheMisses.sum(), cacheFlightLeaders.sum(),
                cacheFlightWaiters.sum(), cacheFailures.sum(),
                cacheEvictions.sum(), cacheLoadNanos.sum());
    }

    /** Immutable Java-8-compatible snapshot. */
    public static final class Snapshot {
        private final long compilations;
        private final long inventoryCompilations;
        private final long cacheHits;
        private final long sparseUses;
        private final long fullRootUses;
        private final long plannedArtifactReuses;
        private final long plannedArtifactFallbacks;
        private final long plannedArtifactNotApplicable;
        private final long processRootSelections;
        private final long processActivePaths;
        private final long processInventoryFragments;
        private final long processMaterializedFragments;
        private final long processSparseNodes;
        private final long cutEdges;
        private final long fullNodes;
        private final long sparseNodes;
        private final long inventoryFragments;
        private final long materializedFragments;
        private final long canonicalFragmentsRead;
        private final long fullRootMaterializationsAvoided;
        private final long identityChecks;
        private final long identityFailures;
        private final long canonicalBatchReads;
        private final long canonicalSingleReads;
        private final long verifiedHandleBatches;
        private final long portableCanonicalBatches;
        private final long cacheMisses;
        private final long cacheFlightLeaders;
        private final long cacheFlightWaiters;
        private final long cacheFailures;
        private final long cacheEvictions;
        private final long cacheLoadNanos;

        private Snapshot(
                long compilations,
                long inventoryCompilations,
                long cacheHits,
                long sparseUses,
                long fullRootUses,
                long plannedArtifactReuses,
                long plannedArtifactFallbacks,
                long plannedArtifactNotApplicable,
                long processRootSelections,
                long processActivePaths,
                long processInventoryFragments,
                long processMaterializedFragments,
                long processSparseNodes,
                long cutEdges,
                long fullNodes,
                long sparseNodes,
                long inventoryFragments,
                long materializedFragments,
                long canonicalFragmentsRead,
                long fullRootMaterializationsAvoided,
                long identityChecks,
                long identityFailures,
                long canonicalBatchReads,
                long canonicalSingleReads,
                long verifiedHandleBatches,
                long portableCanonicalBatches,
                long cacheMisses,
                long cacheFlightLeaders,
                long cacheFlightWaiters,
                long cacheFailures,
                long cacheEvictions,
                long cacheLoadNanos) {
            this.compilations = requireNonNegative(
                    compilations, "compilations");
            this.inventoryCompilations = requireNonNegative(
                    inventoryCompilations, "inventoryCompilations");
            this.cacheHits = requireNonNegative(cacheHits, "cacheHits");
            this.sparseUses = requireNonNegative(sparseUses, "sparseUses");
            this.fullRootUses = requireNonNegative(
                    fullRootUses, "fullRootUses");
            this.plannedArtifactReuses = requireNonNegative(
                    plannedArtifactReuses, "plannedArtifactReuses");
            this.plannedArtifactFallbacks = requireNonNegative(
                    plannedArtifactFallbacks, "plannedArtifactFallbacks");
            this.plannedArtifactNotApplicable = requireNonNegative(
                    plannedArtifactNotApplicable,
                    "plannedArtifactNotApplicable");
            this.processRootSelections = requireNonNegative(
                    processRootSelections, "processRootSelections");
            this.processActivePaths = requireNonNegative(
                    processActivePaths, "processActivePaths");
            this.processInventoryFragments = requireNonNegative(
                    processInventoryFragments,
                    "processInventoryFragments");
            this.processMaterializedFragments = requireNonNegative(
                    processMaterializedFragments,
                    "processMaterializedFragments");
            if (this.processMaterializedFragments
                    > this.processInventoryFragments) {
                throw new IllegalArgumentException(
                        "processMaterializedFragments exceed "
                                + "processInventoryFragments");
            }
            this.processSparseNodes = requireNonNegative(
                    processSparseNodes, "processSparseNodes");
            this.cutEdges = requireNonNegative(cutEdges, "cutEdges");
            this.fullNodes = requireNonNegative(fullNodes, "fullNodes");
            this.sparseNodes = requireNonNegative(
                    sparseNodes, "sparseNodes");
            this.inventoryFragments = requireNonNegative(
                    inventoryFragments, "inventoryFragments");
            this.materializedFragments = requireNonNegative(
                    materializedFragments, "materializedFragments");
            if (this.materializedFragments > this.inventoryFragments) {
                throw new IllegalArgumentException(
                        "materializedFragments exceed inventoryFragments");
            }
            this.canonicalFragmentsRead = requireNonNegative(
                    canonicalFragmentsRead, "canonicalFragmentsRead");
            this.fullRootMaterializationsAvoided = requireNonNegative(
                    fullRootMaterializationsAvoided,
                    "fullRootMaterializationsAvoided");
            this.identityChecks = requireNonNegative(
                    identityChecks, "identityChecks");
            this.identityFailures = requireNonNegative(
                    identityFailures, "identityFailures");
            this.canonicalBatchReads = requireNonNegative(
                    canonicalBatchReads, "canonicalBatchReads");
            this.canonicalSingleReads = requireNonNegative(
                    canonicalSingleReads, "canonicalSingleReads");
            this.verifiedHandleBatches = requireNonNegative(
                    verifiedHandleBatches, "verifiedHandleBatches");
            this.portableCanonicalBatches = requireNonNegative(
                    portableCanonicalBatches,
                    "portableCanonicalBatches");
            this.cacheMisses = requireNonNegative(
                    cacheMisses, "cacheMisses");
            this.cacheFlightLeaders = requireNonNegative(
                    cacheFlightLeaders, "cacheFlightLeaders");
            this.cacheFlightWaiters = requireNonNegative(
                    cacheFlightWaiters, "cacheFlightWaiters");
            this.cacheFailures = requireNonNegative(
                    cacheFailures, "cacheFailures");
            this.cacheEvictions = requireNonNegative(
                    cacheEvictions, "cacheEvictions");
            this.cacheLoadNanos = requireNonNegative(
                    cacheLoadNanos, "cacheLoadNanos");
        }

        public long compilations() { return compilations; }
        public long inventoryCompilations() {
            return inventoryCompilations;
        }
        public long cacheHits() { return cacheHits; }
        public long sparseUses() { return sparseUses; }
        public long fullRootUses() { return fullRootUses; }
        public long plannedArtifactReuses() {
            return plannedArtifactReuses;
        }
        public long plannedArtifactFallbacks() {
            return plannedArtifactFallbacks;
        }
        public long plannedArtifactNotApplicable() {
            return plannedArtifactNotApplicable;
        }
        public long processRootSelections() {
            return processRootSelections;
        }
        public long processActivePaths() { return processActivePaths; }
        public long processInventoryFragments() {
            return processInventoryFragments;
        }
        public long processMaterializedFragments() {
            return processMaterializedFragments;
        }
        public long processSparseNodes() { return processSparseNodes; }
        public long cutEdges() { return cutEdges; }
        public long fullNodes() { return fullNodes; }
        public long sparseNodes() { return sparseNodes; }
        public long inventoryFragments() { return inventoryFragments; }
        public long materializedFragments() {
            return materializedFragments;
        }
        public long canonicalFragmentsRead() {
            return canonicalFragmentsRead;
        }
        public long fullRootMaterializationsAvoided() {
            return fullRootMaterializationsAvoided;
        }
        public long identityChecks() { return identityChecks; }
        public long identityFailures() { return identityFailures; }
        public long canonicalBatchReads() { return canonicalBatchReads; }
        public long canonicalSingleReads() { return canonicalSingleReads; }
        public long verifiedHandleBatches() {
            return verifiedHandleBatches;
        }
        public long portableCanonicalBatches() {
            return portableCanonicalBatches;
        }
        public long cacheMisses() { return cacheMisses; }
        public long cacheFlightLeaders() { return cacheFlightLeaders; }
        public long cacheFlightWaiters() { return cacheFlightWaiters; }
        public long cacheFailures() { return cacheFailures; }
        public long cacheEvictions() { return cacheEvictions; }
        public long cacheLoadNanos() { return cacheLoadNanos; }

        /** Returns exact non-negative work performed after an earlier snapshot. */
        public Snapshot minus(Snapshot before) {
            Snapshot checked = Objects.requireNonNull(before, "before");
            return new Snapshot(
                    compilations - checked.compilations,
                    inventoryCompilations - checked.inventoryCompilations,
                    cacheHits - checked.cacheHits,
                    sparseUses - checked.sparseUses,
                    fullRootUses - checked.fullRootUses,
                    plannedArtifactReuses
                            - checked.plannedArtifactReuses,
                    plannedArtifactFallbacks
                            - checked.plannedArtifactFallbacks,
                    plannedArtifactNotApplicable
                            - checked.plannedArtifactNotApplicable,
                    processRootSelections
                            - checked.processRootSelections,
                    processActivePaths - checked.processActivePaths,
                    processInventoryFragments
                            - checked.processInventoryFragments,
                    processMaterializedFragments
                            - checked.processMaterializedFragments,
                    processSparseNodes - checked.processSparseNodes,
                    cutEdges - checked.cutEdges,
                    fullNodes - checked.fullNodes,
                    sparseNodes - checked.sparseNodes,
                    inventoryFragments - checked.inventoryFragments,
                    materializedFragments - checked.materializedFragments,
                    canonicalFragmentsRead - checked.canonicalFragmentsRead,
                    fullRootMaterializationsAvoided
                            - checked.fullRootMaterializationsAvoided,
                    identityChecks - checked.identityChecks,
                    identityFailures - checked.identityFailures,
                    canonicalBatchReads - checked.canonicalBatchReads,
                    canonicalSingleReads - checked.canonicalSingleReads,
                    verifiedHandleBatches - checked.verifiedHandleBatches,
                    portableCanonicalBatches
                            - checked.portableCanonicalBatches,
                    cacheMisses - checked.cacheMisses,
                    cacheFlightLeaders - checked.cacheFlightLeaders,
                    cacheFlightWaiters - checked.cacheFlightWaiters,
                    cacheFailures - checked.cacheFailures,
                    cacheEvictions - checked.cacheEvictions,
                    cacheLoadNanos - checked.cacheLoadNanos);
        }

        public double meanNodeReduction() {
            return fullNodes == 0L
                    ? 0.0d
                    : 1.0d - ((double) sparseNodes / (double) fullNodes);
        }

        /** Exact selected/inventory fragment fraction for direct assembly. */
        public double fragmentMaterializationFraction() {
            return inventoryFragments == 0L
                    ? 0.0d
                    : materializedFragments / (double) inventoryFragments;
        }

        /** Exact PROCESS-only selected/inventory fragment fraction. */
        public double processFragmentMaterializationFraction() {
            return processInventoryFragments == 0L
                    ? 0.0d
                    : processMaterializedFragments
                            / (double) processInventoryFragments;
        }

        public long decisions() {
            return Math.addExact(sparseUses, fullRootUses);
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Snapshot)) return false;
            Snapshot other = (Snapshot) value;
            return compilations == other.compilations
                    && inventoryCompilations == other.inventoryCompilations
                    && cacheHits == other.cacheHits
                    && sparseUses == other.sparseUses
                    && fullRootUses == other.fullRootUses
                    && plannedArtifactReuses
                            == other.plannedArtifactReuses
                    && plannedArtifactFallbacks
                            == other.plannedArtifactFallbacks
                    && plannedArtifactNotApplicable
                            == other.plannedArtifactNotApplicable
                    && processRootSelections
                            == other.processRootSelections
                    && processActivePaths == other.processActivePaths
                    && processInventoryFragments
                            == other.processInventoryFragments
                    && processMaterializedFragments
                            == other.processMaterializedFragments
                    && processSparseNodes == other.processSparseNodes
                    && cutEdges == other.cutEdges
                    && fullNodes == other.fullNodes
                    && sparseNodes == other.sparseNodes
                    && inventoryFragments == other.inventoryFragments
                    && materializedFragments == other.materializedFragments
                    && canonicalFragmentsRead == other.canonicalFragmentsRead
                    && fullRootMaterializationsAvoided
                            == other.fullRootMaterializationsAvoided
                    && identityChecks == other.identityChecks
                    && identityFailures == other.identityFailures
                    && canonicalBatchReads == other.canonicalBatchReads
                    && canonicalSingleReads == other.canonicalSingleReads
                    && verifiedHandleBatches == other.verifiedHandleBatches
                    && portableCanonicalBatches
                            == other.portableCanonicalBatches
                    && cacheMisses == other.cacheMisses
                    && cacheFlightLeaders == other.cacheFlightLeaders
                    && cacheFlightWaiters == other.cacheFlightWaiters
                    && cacheFailures == other.cacheFailures
                    && cacheEvictions == other.cacheEvictions
                    && cacheLoadNanos == other.cacheLoadNanos;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    Long.valueOf(compilations),
                    Long.valueOf(inventoryCompilations),
                    Long.valueOf(cacheHits),
                    Long.valueOf(sparseUses),
                    Long.valueOf(fullRootUses),
                    Long.valueOf(plannedArtifactReuses),
                    Long.valueOf(plannedArtifactFallbacks),
                    Long.valueOf(plannedArtifactNotApplicable),
                    Long.valueOf(processRootSelections),
                    Long.valueOf(processActivePaths),
                    Long.valueOf(processInventoryFragments),
                    Long.valueOf(processMaterializedFragments),
                    Long.valueOf(processSparseNodes),
                    Long.valueOf(cutEdges),
                    Long.valueOf(fullNodes),
                    Long.valueOf(sparseNodes),
                    Long.valueOf(inventoryFragments),
                    Long.valueOf(materializedFragments),
                    Long.valueOf(canonicalFragmentsRead),
                    Long.valueOf(fullRootMaterializationsAvoided),
                    Long.valueOf(identityChecks),
                    Long.valueOf(identityFailures),
                    Long.valueOf(canonicalBatchReads),
                    Long.valueOf(canonicalSingleReads),
                    Long.valueOf(verifiedHandleBatches),
                    Long.valueOf(portableCanonicalBatches),
                    Long.valueOf(cacheMisses),
                    Long.valueOf(cacheFlightLeaders),
                    Long.valueOf(cacheFlightWaiters),
                    Long.valueOf(cacheFailures),
                    Long.valueOf(cacheEvictions),
                    Long.valueOf(cacheLoadNanos));
        }

        @Override
        public String toString() {
            return "Snapshot{compilations=" + compilations
                    + ", inventoryCompilations=" + inventoryCompilations
                    + ", cacheHits=" + cacheHits
                    + ", sparseUses=" + sparseUses
                    + ", fullRootUses=" + fullRootUses
                    + ", plannedArtifactReuses="
                    + plannedArtifactReuses
                    + ", plannedArtifactFallbacks="
                    + plannedArtifactFallbacks
                    + ", plannedArtifactNotApplicable="
                    + plannedArtifactNotApplicable
                    + ", processRootSelections="
                    + processRootSelections
                    + ", processActivePaths=" + processActivePaths
                    + ", processInventoryFragments="
                    + processInventoryFragments
                    + ", processMaterializedFragments="
                    + processMaterializedFragments
                    + ", processSparseNodes=" + processSparseNodes
                    + ", cutEdges=" + cutEdges
                    + ", fullNodes=" + fullNodes
                    + ", sparseNodes=" + sparseNodes
                    + ", inventoryFragments=" + inventoryFragments
                    + ", materializedFragments=" + materializedFragments
                    + ", canonicalFragmentsRead=" + canonicalFragmentsRead
                    + ", fullRootMaterializationsAvoided="
                    + fullRootMaterializationsAvoided
                    + ", identityChecks=" + identityChecks
                    + ", identityFailures=" + identityFailures
                    + ", canonicalBatchReads=" + canonicalBatchReads
                    + ", canonicalSingleReads=" + canonicalSingleReads
                    + ", verifiedHandleBatches=" + verifiedHandleBatches
                    + ", portableCanonicalBatches="
                    + portableCanonicalBatches
                    + ", cacheMisses=" + cacheMisses
                    + ", cacheFlightLeaders=" + cacheFlightLeaders
                    + ", cacheFlightWaiters=" + cacheFlightWaiters
                    + ", cacheFailures=" + cacheFailures
                    + ", cacheEvictions=" + cacheEvictions
                    + ", cacheLoadNanos=" + cacheLoadNanos + '}';
        }

        private static long requireNonNegative(long value, String label) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        Objects.requireNonNull(label, "label")
                                + " must be non-negative");
            }
            return value;
        }
    }

    private static long requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    Objects.requireNonNull(label, "label")
                            + " must be non-negative");
        }
        return value;
    }
}
