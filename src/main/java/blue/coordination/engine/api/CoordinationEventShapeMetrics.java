package blue.coordination.engine.api;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Real counters for the first-seen, shape-compiled event admission path. */
public final class CoordinationEventShapeMetrics {
    private final LongAdder templatesCompiled = new LongAdder();
    private final LongAdder instancesCompiled = new LongAdder();
    private final LongAdder exactGraphsMaterialized = new LongAdder();
    private final LongAdder directFragmentsRehashed = new LongAdder();
    private final LongAdder staticFragmentsReused = new LongAdder();
    private final LongAdder fullSplitterOracleRuns = new LongAdder();
    private final LongAdder oracleFailures = new LongAdder();

    void templateCompiled() { templatesCompiled.increment(); }
    void instanceCompiled() { instancesCompiled.increment(); }
    void exactGraphMaterialized() { exactGraphsMaterialized.increment(); }
    void directFragmentsRehashed(long count) {
        directFragmentsRehashed.add(count);
    }
    void staticFragmentsReused(long count) {
        staticFragmentsReused.add(count);
    }
    void fullSplitterOracleRun() { fullSplitterOracleRuns.increment(); }
    void oracleFailure() { oracleFailures.increment(); }

    public Snapshot snapshot() {
        return new Snapshot(
                templatesCompiled.sum(),
                instancesCompiled.sum(),
                exactGraphsMaterialized.sum(),
                directFragmentsRehashed.sum(),
                staticFragmentsReused.sum(),
                fullSplitterOracleRuns.sum(),
                oracleFailures.sum());
    }

    /** Immutable Java-8-compatible metrics snapshot. */
    public static final class Snapshot {
        private final long templatesCompiled;
        private final long instancesCompiled;
        private final long exactGraphsMaterialized;
        private final long directFragmentsRehashed;
        private final long staticFragmentsReused;
        private final long fullSplitterOracleRuns;
        private final long oracleFailures;

        private Snapshot(
                long templatesCompiled,
                long instancesCompiled,
                long exactGraphsMaterialized,
                long directFragmentsRehashed,
                long staticFragmentsReused,
                long fullSplitterOracleRuns,
                long oracleFailures) {
            this.templatesCompiled = nonNegative(
                    templatesCompiled, "templatesCompiled");
            this.instancesCompiled = nonNegative(
                    instancesCompiled, "instancesCompiled");
            this.exactGraphsMaterialized = nonNegative(
                    exactGraphsMaterialized, "exactGraphsMaterialized");
            this.directFragmentsRehashed = nonNegative(
                    directFragmentsRehashed, "directFragmentsRehashed");
            this.staticFragmentsReused = nonNegative(
                    staticFragmentsReused, "staticFragmentsReused");
            this.fullSplitterOracleRuns = nonNegative(
                    fullSplitterOracleRuns, "fullSplitterOracleRuns");
            this.oracleFailures = nonNegative(
                    oracleFailures, "oracleFailures");
        }

        public long templatesCompiled() { return templatesCompiled; }
        public long instancesCompiled() { return instancesCompiled; }
        public long exactGraphsMaterialized() {
            return exactGraphsMaterialized;
        }
        public long directFragmentsRehashed() {
            return directFragmentsRehashed;
        }
        public long staticFragmentsReused() { return staticFragmentsReused; }
        public long fullSplitterOracleRuns() {
            return fullSplitterOracleRuns;
        }
        public long oracleFailures() { return oracleFailures; }

        @Override
        public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Snapshot)) return false;
            Snapshot other = (Snapshot) value;
            return templatesCompiled == other.templatesCompiled
                    && instancesCompiled == other.instancesCompiled
                    && exactGraphsMaterialized
                            == other.exactGraphsMaterialized
                    && directFragmentsRehashed
                            == other.directFragmentsRehashed
                    && staticFragmentsReused == other.staticFragmentsReused
                    && fullSplitterOracleRuns
                            == other.fullSplitterOracleRuns
                    && oracleFailures == other.oracleFailures;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    Long.valueOf(templatesCompiled),
                    Long.valueOf(instancesCompiled),
                    Long.valueOf(exactGraphsMaterialized),
                    Long.valueOf(directFragmentsRehashed),
                    Long.valueOf(staticFragmentsReused),
                    Long.valueOf(fullSplitterOracleRuns),
                    Long.valueOf(oracleFailures));
        }

        @Override
        public String toString() {
            return "Snapshot{templatesCompiled=" + templatesCompiled
                    + ", instancesCompiled=" + instancesCompiled
                    + ", exactGraphsMaterialized="
                    + exactGraphsMaterialized
                    + ", directFragmentsRehashed="
                    + directFragmentsRehashed
                    + ", staticFragmentsReused=" + staticFragmentsReused
                    + ", fullSplitterOracleRuns="
                    + fullSplitterOracleRuns
                    + ", oracleFailures=" + oracleFailures + '}';
        }

        private static long nonNegative(long value, String label) {
            if (value < 0L) {
                throw new IllegalArgumentException(
                        Objects.requireNonNull(label, "label")
                                + " must be non-negative");
            }
            return value;
        }
    }
}
