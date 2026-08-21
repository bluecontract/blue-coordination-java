package blue.coordination.integration;

import blue.language.model.Node;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused assertions shared by integration and scenario tests. */
final class EngineTestSupport {
    private EngineTestSupport() {
    }

    static String resource(String path) throws Exception {
        return TestResources.read(path);
    }

    static String payloadRequest(String payloadYaml) {
        return "payload:\n" + indent(payloadYaml.strip(), 2) + "\n";
    }

    static String indent(String text, int spaces) {
        String prefix = " ".repeat(spaces);
        return text.lines()
                .map(line -> prefix + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse(prefix);
    }

    static long integer(TestEngine engine, String doc, String path) {
        Node node = engine.value(doc, path);
        assertNotNull(node.getValue(), "Missing scalar at " + doc + path);
        Object value = node.getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected Integer at " + doc + path
                + " but got " + value);
    }

    static String text(TestEngine engine, String doc, String path) {
        Object value = engine.value(doc, path).getValue();
        if (!(value instanceof String text)) {
            throw new AssertionError("Expected Text at " + doc + path
                    + " but got " + value);
        }
        return text;
    }

    static MetricDelta delta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after) {
        Map<String, Long> counters = new LinkedHashMap<>();
        after.counters().forEach((key, value) -> counters.put(
                key, value - before.counters().getOrDefault(key, 0L)));
        Map<String, Long> phases = new LinkedHashMap<>();
        after.phaseNanos().forEach((key, value) -> phases.put(
                key, value - before.phaseNanos().getOrDefault(key, 0L)));
        return new MetricDelta(counters, phases);
    }

    static void assertNoGenericSplitting(MetricDelta delta) {
        assertEquals(0L, delta.counter("UNRELATED_DOCUMENT_READS"));
        assertEquals(0L, delta.counter("REQUEST_FRAGMENTS"));
        assertEquals(0L, delta.counter("TIMELINE_ENTRY_FRAGMENTS"));
        assertEquals(0L, delta.counter("ORDINARY_NODE_FRAGMENTS"));
        assertEquals(0L, delta.counter("FULL_ENVIRONMENT_SCANS"));
        assertEquals(0L, delta.counter("SOURCE_REPLAYS_PER_PARENT"));
        assertEquals(0L, delta.counter("POST_PROCESS_FULL_PROJECTIONS"));
        assertEquals(0L, delta.counter(
                "PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY"));
        assertEquals(0L, delta.counter(
                "CHILD_PROCESS_RERUNS_ON_PARENT_RETRY"));
    }

    /** Proves that user-visible frozen time is neither lost nor called host work. */
    static void assertProcessingTimeAttribution(MetricDelta delta) {
        long frozen = delta.nanos("process.frozen");
        assertTrue(frozen > 0L, "PROCESS must record frozen semantic time");
        assertEquals(frozen,
                delta.nanos("process.frozenContractsOnce")
                        + delta.nanos("process.embeddedFrozen"),
                "aggregate frozen time must include direct and embedded lanes");
        long frozenInternals = delta.nanos("process.deliveryPlanDerivation")
                + delta.nanos("process.platformCommit");
        assertTrue(frozenInternals > 0L,
                "frozen PROCESS must expose its two upstream macro phases");
        assertTrue(frozenInternals <= frozen,
                "nested frozen timers cannot exceed their parent timer");
        assertTrue(delta.nanos("process.hostBeforeFrozen") > 0L);
        assertTrue(delta.nanos("process.hostAfterFrozen") > 0L);
    }

    record MetricDelta(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        /*
         * Raw diagnostic metrics are sparse: production creates their map
         * entry only when the corresponding work happens. These names have
         * real production sites and are intentionally asserted as zero on
         * paths that must avoid that work. Keep this exception list closed so
         * a misspelling cannot become an accidental zero.
         */
        private static final Set<String> SPARSE_ZERO_COUNTERS = Set.of(
                "childHistoricalProcessCalls",
                "journal.rollbacks",
                "layout.externalManagedChildMutationsRejected",
                "temporal.graphGenerationsPublished");
        private static final Set<String> SPARSE_ZERO_PHASES = Set.of(
                "process.embeddedFrozen");

        long counter(String name) {
            return requireMeasurement(
                    counters, SPARSE_ZERO_COUNTERS, name, "counter");
        }

        long nanos(String phase) {
            return requireMeasurement(
                    phaseNanos, SPARSE_ZERO_PHASES, phase, "phase timer");
        }

        double millis(String phase) {
            return nanos(phase) / 1_000_000.0;
        }

        private static long requireMeasurement(
                Map<String, Long> measurements,
                Set<String> sparseZeroVocabulary,
                String name,
                String kind) {
            if (measurements.containsKey(name)) {
                return measurements.get(name);
            }
            if (sparseZeroVocabulary.contains(name)) {
                return 0L;
            }
            throw new AssertionError("Unknown or unproduced " + kind
                    + " '" + name + "'. Structural metric assertions "
                    + "must use a name present in the engine snapshot or "
                    + "the closed sparse-zero vocabulary.");
        }
    }
}
