package blue.coordination.processor;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.ProcessingEventIdentityObserver;

/**
 * Test-only access to diagnostic processor options that are intentionally
 * absent from the production public API.
 */
public final class CoordinationTestProcessorOptions {

    private CoordinationTestProcessorOptions() {
    }

    public static CoordinationProcessorOptions
    withProcessingEventIdentityEvidence(
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver observer) {
        return CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .processingEventIdentityObserver(observer)
                .build();
    }
}
