package blue.coordination.integration;

import java.util.Map;

/** Immutable metric projection shared by integration and scenario tests. */
final class EngineMetrics {
    private EngineMetrics() {
    }

    record MetricsSnapshot(
            Map<String, Long> counters,
            Map<String, Long> phaseNanos) {
        MetricsSnapshot {
            counters = Map.copyOf(counters);
            phaseNanos = Map.copyOf(phaseNanos);
        }
    }
}
