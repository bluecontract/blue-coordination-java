package blue.coordination.processor.bex;

import blue.language.snapshot.FrozenNode;
import blue.language.identity.BlueIds;

import java.util.Objects;

/**
 * Test-only evidence collector for the Processing Event identity assertions
 * used by the executable conformance harness.
 */
public final class ProcessingEventIdentityEvidence
        implements ProcessingEventIdentityObserver {
    private String admittedBlueId;
    private boolean stable = true;
    private long workflowObservations;
    private long bexBindingObservations;

    @Override
    public synchronized void observe(
            FrozenNode processingEvent,
            String exposedBlueId,
            Boundary boundary) {
        FrozenNode exactEvent = Objects.requireNonNull(
                processingEvent, "processingEvent");
        String exactExposedBlueId = requireBlueId(
                exposedBlueId, "exposedBlueId");
        Boundary exactBoundary = Objects.requireNonNull(
                boundary, "boundary");
        String snapshotBlueId = requireBlueId(
                exactEvent.blueId(), "processingEvent.blueId");

        if (!snapshotBlueId.equals(exactExposedBlueId)) {
            stable = false;
        }
        if (admittedBlueId == null) {
            admittedBlueId = snapshotBlueId;
        } else if (!admittedBlueId.equals(snapshotBlueId)) {
            stable = false;
        }

        if (exactBoundary == Boundary.WORKFLOW) {
            workflowObservations++;
        } else {
            bexBindingObservations++;
        }
    }

    /**
     * Returns an immutable point-in-time evidence view.
     *
     * @return current evidence snapshot
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                admittedBlueId,
                stable,
                workflowObservations,
                bexBindingObservations);
    }

    private static String requireBlueId(
            String blueId,
            String name) {
        return BlueIds.requirePlainBlueId(
                blueId, name);
    }

    /** Immutable Processing Event identity evidence. */
    public static final class Snapshot {
        private final String admittedBlueId;
        private final boolean stable;
        private final long workflowObservations;
        private final long bexBindingObservations;

        private Snapshot(
                String admittedBlueId,
                boolean stable,
                long workflowObservations,
                long bexBindingObservations) {
            this.admittedBlueId = admittedBlueId;
            this.stable = stable;
            this.workflowObservations = workflowObservations;
            this.bexBindingObservations =
                    bexBindingObservations;
        }

        /**
         * Whether at least one exact Coordination boundary was observed.
         *
         * @return {@code true} only when evidence is present
         */
        public boolean observed() {
            return workflowObservations
                    + bexBindingObservations > 0L;
        }

        /**
         * Whether every observed boundary retained the admitted identity.
         *
         * <p>Callers must also require {@link #observed()} before treating this
         * value as proof.</p>
         *
         * @return identity-stability result for the observations
         */
        public boolean stable() {
            return stable;
        }

        /** @return first exact Processing Event BlueId, or {@code null} */
        public String admittedBlueId() {
            return admittedBlueId;
        }

        /** @return number of workflow-boundary observations */
        public long workflowObservations() {
            return workflowObservations;
        }

        /** @return number of hosted BEX binding observations */
        public long bexBindingObservations() {
            return bexBindingObservations;
        }
    }
}
