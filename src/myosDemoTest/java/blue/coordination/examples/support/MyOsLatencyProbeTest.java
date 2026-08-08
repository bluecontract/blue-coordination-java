package blue.coordination.examples.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Proves the raw-sample percentile contract used by live evidence. */
final class MyOsLatencyProbeTest {

    @Test
    void shouldUseNearestRankAcrossAllRawSamplesWithoutDroppingOutliers() {
        // given: deliberately unsorted so the helper must sort a copy
        List<Long> rawSamples = new ArrayList<>(100);
        for (long value = 100L; value >= 1L; value--) {
            rawSamples.add(value);
        }

        // then
        assertEquals(50L, MyOsLatencyProbe.percentile(rawSamples, 0.50d));
        assertEquals(90L, MyOsLatencyProbe.percentile(rawSamples, 0.90d));
        assertEquals(95L, MyOsLatencyProbe.percentile(rawSamples, 0.95d));
        assertEquals(99L, MyOsLatencyProbe.percentile(rawSamples, 0.99d));
        assertEquals(100L, MyOsLatencyProbe.percentile(rawSamples, 1.00d));
        assertEquals(Long.valueOf(100L), rawSamples.get(0));
        assertEquals(Long.valueOf(1L), rawSamples.get(99));
    }

    @Test
    void shouldRejectInvalidMeasurementAndPercentileInputs() {
        assertThrows(
                NullPointerException.class,
                () -> MyOsLatencyProbe.measureNanos((Runnable) null));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.measureNanos(0, () -> { }));
        assertThrows(
                NullPointerException.class,
                () -> MyOsLatencyProbe.measureNanos(1, null));
        assertThrows(
                NullPointerException.class,
                () -> MyOsLatencyProbe.percentile(null, 0.95d));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.percentile(
                        Collections.emptyList(), 0.95d));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.percentile(
                        Collections.singletonList(1L), 0.0d));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.percentile(
                        Collections.singletonList(1L), 1.01d));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.percentile(
                        Collections.singletonList(1L), Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> MyOsLatencyProbe.percentile(
                        Collections.singletonList(-1L), 0.95d));
        assertThrows(
                NullPointerException.class,
                () -> MyOsLatencyProbe.percentile(
                        Arrays.asList(1L, null, 3L), 0.95d));
    }
}
