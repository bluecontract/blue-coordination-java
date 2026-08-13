package blue.coordination.integration;

import java.util.Map;

/** Immutable test projection of the engine's public metric snapshot. */
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
