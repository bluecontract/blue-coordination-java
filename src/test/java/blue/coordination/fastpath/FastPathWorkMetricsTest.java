package blue.coordination.fastpath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FastPathWorkMetricsTest {
    @Test
    void shouldReturnValidatedSameSourceOperationDelta() {
        FastPathWorkMetrics metrics = new FastPathWorkMetrics();
        FastPathWorkMetrics.Snapshot before = metrics.snapshot();

        metrics.admittedProjectionBuilt(7L);
        metrics.candidatesLookedUp(3L);
        metrics.scopeTraversed();
        metrics.rootIdentityCalculated();
        metrics.deltaProjectionUpdated(2L, 1L, 5L);
        metrics.snapshotSerialized(7L);
        metrics.fullProjectorFallback();
        metrics.catalogFallback();
        metrics.merkleOccurrencesUpdated(4L);

        FastPathWorkMetrics.Snapshot after = metrics.snapshot();
        FastPathWorkMetrics.Snapshot operation = after.minus(before);

        assertEquals(1L, operation.admittedProjectionBuilds());
        assertEquals(7L, operation.admittedOccurrences());
        assertEquals(3L, operation.candidateLookups());
        assertEquals(1L, operation.scopeTraversals());
        assertEquals(1L, operation.rootIdentityCalculations());
        assertEquals(1L, operation.deltaProjectionUpdates());
        assertEquals(2L, operation.affectedOccurrences());
        assertEquals(1L, operation.refreshedOccurrences());
        assertEquals(5L, operation.unrelatedOccurrences());
        assertEquals(1L, operation.snapshotSerializations());
        assertEquals(7L, operation.snapshotSerializedOccurrences());
        assertEquals(1L, operation.coldProjectionFallbacks());
        assertEquals(1L, operation.fullProjectorFallbacks());
        assertEquals(1L, operation.catalogFallbacks());
        assertEquals(4L, operation.merkleOccurrenceUpdates());
        assertThrows(IllegalArgumentException.class,
                () -> before.minus(after));
    }
}
