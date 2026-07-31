package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.ProcessingEventIdentityObserver;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;

/**
 * Optional dependency overrides used while installing Coordination
 * processors.
 *
 * <p>Absent values select production defaults. Supplying a workflow runner or
 * BEX engine transfers lifecycle ownership to the caller; the registration
 * facade does not close caller-owned components.</p>
 */
public final class CoordinationProcessorOptions {
    private final SequentialWorkflowRunner sequentialWorkflowRunner;
    private final BexEngine bexEngine;
    private final long defaultComputeGasLimit;
    private final BexProcessingMetrics processingMetrics;
    private final ProcessingEventIdentityObserver
            processingEventIdentityObserver;

    private CoordinationProcessorOptions(Builder builder) {
        this.sequentialWorkflowRunner = builder.sequentialWorkflowRunner;
        this.bexEngine = builder.bexEngine;
        this.defaultComputeGasLimit = builder.defaultComputeGasLimit;
        this.processingMetrics = builder.processingMetrics;
        this.processingEventIdentityObserver =
                builder.processingEventIdentityObserver;
    }

    public SequentialWorkflowRunner sequentialWorkflowRunner() {
        return sequentialWorkflowRunner;
    }

    public BexEngine bexEngine() {
        return bexEngine;
    }

    public long defaultComputeGasLimit() {
        return defaultComputeGasLimit;
    }

    public BexProcessingMetrics processingMetrics() {
        return processingMetrics;
    }

    ProcessingEventIdentityObserver
    processingEventIdentityObserver() {
        return processingEventIdentityObserver;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds an immutable set of Coordination processor installation options. */
    public static final class Builder {
        private SequentialWorkflowRunner sequentialWorkflowRunner;
        private BexEngine bexEngine;
        private long defaultComputeGasLimit = 100_000L;
        private BexProcessingMetrics processingMetrics;
        private ProcessingEventIdentityObserver
                processingEventIdentityObserver;

        public Builder sequentialWorkflowRunner(SequentialWorkflowRunner sequentialWorkflowRunner) {
            this.sequentialWorkflowRunner = sequentialWorkflowRunner;
            return this;
        }

        public Builder bexEngine(BexEngine bexEngine) {
            this.bexEngine = bexEngine;
            return this;
        }

        public Builder defaultComputeGasLimit(long defaultComputeGasLimit) {
            if (defaultComputeGasLimit <= 0L) {
                throw new IllegalArgumentException("defaultComputeGasLimit must be positive");
            }
            this.defaultComputeGasLimit = defaultComputeGasLimit;
            return this;
        }

        public Builder processingMetrics(BexProcessingMetrics processingMetrics) {
            this.processingMetrics = processingMetrics;
            return this;
        }

        Builder processingEventIdentityObserver(
                ProcessingEventIdentityObserver observer) {
            this.processingEventIdentityObserver = observer;
            return this;
        }

        public CoordinationProcessorOptions build() {
            return new CoordinationProcessorOptions(this);
        }
    }
}
