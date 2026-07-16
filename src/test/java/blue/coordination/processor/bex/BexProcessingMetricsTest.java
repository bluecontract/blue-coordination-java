package blue.coordination.processor.bex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BexProcessingMetricsTest {

    @Test
    void bexProcessingMetricsExposeProcessEventSnapshotCounters() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(-1L);

        assertEquals(1L, metrics.processEventSnapshotAttempts());
        assertEquals(1L, metrics.processEventSnapshotBuilds());
        assertEquals(1L, metrics.processEventSnapshotFailures());
        assertEquals(0L, metrics.processEventSnapshotConstructionNanos(),
                "host-provided timing samples must be bounded at zero");
    }

    @Test
    void bexProcessingMetricsSnapshotIsImmutableAndAccumulates() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.addProcessEventSnapshotConstructionNanos(11L);
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.incrementProcessEventSnapshotAttempts();
        metrics.incrementProcessEventSnapshotBuilds();
        metrics.incrementProcessEventSnapshotFailures();
        metrics.addProcessEventSnapshotConstructionNanos(13L);
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertSnapshot(first, 1L, 1L, 0L, 11L);
        assertSnapshot(second, 2L, 2L, 1L, 24L);
    }

    @Test
    void terminationMetricsAreBoundedCountersAndSnapshotsAreImmutable() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot first = metrics.snapshot();

        metrics.incrementSuccessfulComputeTerminationRequests();
        metrics.incrementDeclarativeTerminationSteps();
        metrics.incrementComputeResultValidationFailures();
        BexProcessingMetrics.Snapshot second = metrics.snapshot();

        assertTerminationSnapshot(first, 1L);
        assertTerminationSnapshot(second, 2L);
    }

    private static void assertSnapshot(BexProcessingMetrics.Snapshot snapshot,
                                       long attempts,
                                       long builds,
                                       long failures,
                                       long constructionNanos) {
        assertEquals(attempts, snapshot.processEventSnapshotAttempts);
        assertEquals(builds, snapshot.processEventSnapshotBuilds);
        assertEquals(failures, snapshot.processEventSnapshotFailures);
        assertEquals(constructionNanos, snapshot.processEventSnapshotConstructionNanos);
    }

    private static void assertTerminationSnapshot(BexProcessingMetrics.Snapshot snapshot,
                                                  long expected) {
        assertEquals(expected, snapshot.successfulComputeTerminationRequests);
        assertEquals(expected, snapshot.declarativeTerminationSteps);
        assertEquals(expected, snapshot.computeResultValidationFailures);
    }
}
