package blue.coordination.integration;

import blue.language.model.Node;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Focused helpers for the clean basic-engine acceptance tests. */
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
        assertEquals(0L, delta.counter("append.requestFragments"));
        assertEquals(0L, delta.counter("append.eventFragments"));
        assertEquals(0L, delta.counter("layout.ordinaryNodeFragments"));
        assertEquals(0L, delta.counter("requestSplitterCalls"));
        assertEquals(0L, delta.counter("entrySplitterCalls"));
        assertEquals(0L, delta.counter("ordinaryNodeSplitterCalls"));
        assertEquals(0L, delta.counter("broadSubscriptionProjectionCalls"));
        assertEquals(0L, delta.counter(
                "process.concreteSubscriptionProjections"));
        assertEquals(0L, delta.counter("workflowBodiesScannedOnHotPath"));
        assertEquals(0L, delta.counter("parentOwnedChildSourceCalls"));
    }

    record MetricDelta(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        long counter(String name) {
            return counters.getOrDefault(name, 0L);
        }

        long nanos(String phase) {
            return phaseNanos.getOrDefault(phase, 0L);
        }

        double millis(String phase) {
            return nanos(phase) / 1_000_000.0;
        }
    }
}
