package blue.coordination.integration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.delta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for fail-closed structural metric assertions. */
final class EngineTestSupportMetricVocabularyTest {
    @Test
    void unknownCounterAndTimerNamesCannotMasqueradeAsZero() {
        EngineTestSupport.MetricDelta work = delta(
                snapshot(
                        Map.of(
                                "REQUEST_FRAGMENTS", 0L,
                                "temporal.parentEpochApplications", 7L),
                        Map.of("process.frozen", 11L)),
                snapshot(
                        Map.of(
                                "REQUEST_FRAGMENTS", 0L,
                                "temporal.parentEpochApplications", 7L),
                        Map.of("process.frozen", 11L)));

        assertEquals(0L, work.counter("REQUEST_FRAGMENTS"),
                "a registered canonical counter may legitimately stay zero");
        assertEquals(0L,
                work.counter("temporal.parentEpochApplications"),
                "a produced raw structural counter may stay unchanged");
        assertEquals(0L, work.counter("childHistoricalProcessCalls"),
                "a declared sparse production counter may be absent at zero");
        assertEquals(0L, work.nanos("process.frozen"),
                "a produced phase timer may stay unchanged");
        assertEquals(0L, work.nanos("process.embeddedFrozen"),
                "a declared sparse production timer may be absent at zero");

        AssertionError counterFailure = assertThrows(AssertionError.class,
                () -> work.counter(
                        "PARENT_PROCESS_RERUNS_ON_GRAPH_RETRTY"));
        assertTrue(counterFailure.getMessage().contains(
                "Unknown or unproduced counter"));
        assertThrows(AssertionError.class, () -> work.counter(
                "CHILD_PROCESS_RERUNS_ON_PRAENT_RETRY"));
        AssertionError timerFailure = assertThrows(AssertionError.class,
                () -> work.nanos("process.frozn"));
        assertTrue(timerFailure.getMessage().contains(
                "Unknown or unproduced phase timer"));
    }

    private static EngineMetrics.MetricsSnapshot snapshot(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        return new EngineMetrics.MetricsSnapshot(counters, phaseNanos);
    }
}
