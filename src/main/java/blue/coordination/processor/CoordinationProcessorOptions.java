package blue.coordination.processor;

import blue.bex.api.BexEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.ProcessingEventIdentityObserver;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.runtime.BlueLanguage;

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
    private final BlueLanguage language;
    private final long defaultComputeGasLimit;
    private final BexProcessingMetrics processingMetrics;
    private final ProcessingEventIdentityObserver
            processingEventIdentityObserver;
    private final CoordinationSemanticTypeIdentities
            semanticTypeIdentities;

    private CoordinationProcessorOptions(Builder builder) {
        this.sequentialWorkflowRunner = builder.sequentialWorkflowRunner;
        this.bexEngine = builder.bexEngine;
        this.language = builder.language;
        this.defaultComputeGasLimit = builder.defaultComputeGasLimit;
        this.processingMetrics = builder.processingMetrics;
        this.processingEventIdentityObserver =
                builder.processingEventIdentityObserver;
        CoordinationSemanticTypeIdentities configured =
                builder.semanticTypeIdentities;
        if (configured.custom()) {
            if (builder.language == null) {
                throw new IllegalArgumentException(
                        "Custom Coordination semantic type identities require "
                                + "the exact Language runtime");
            }
            configured.validatedAgainst(
                    builder.language.processing()
                            .runtimeAccess()
                            .getNodeProvider());
        }
        this.semanticTypeIdentities = configured;
    }

    public SequentialWorkflowRunner sequentialWorkflowRunner() {
        return sequentialWorkflowRunner;
    }

    public BexEngine bexEngine() {
        return bexEngine;
    }

    /**
     * Returns the exact Language runtime shared with hosted BEX, when the
     * caller did not supply a preconfigured engine.
     */
    public BlueLanguage language() {
        return language;
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

    /** Returns the immutable semantic event identities for this generation. */
    public CoordinationSemanticTypeIdentities semanticTypeIdentities() {
        return semanticTypeIdentities;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builds an immutable set of Coordination processor installation options. */
    public static final class Builder {
        private SequentialWorkflowRunner sequentialWorkflowRunner;
        private BexEngine bexEngine;
        private BlueLanguage language;
        private long defaultComputeGasLimit = 100_000L;
        private BexProcessingMetrics processingMetrics;
        private ProcessingEventIdentityObserver
                processingEventIdentityObserver;
        private CoordinationSemanticTypeIdentities semanticTypeIdentities =
                CoordinationSemanticTypeIdentities.publishedDefaults();

        public Builder sequentialWorkflowRunner(SequentialWorkflowRunner sequentialWorkflowRunner) {
            this.sequentialWorkflowRunner = sequentialWorkflowRunner;
            return this;
        }

        public Builder bexEngine(BexEngine bexEngine) {
            this.bexEngine = bexEngine;
            return this;
        }

        /**
         * Selects the exact Language runtime that a default hosted BEX engine
         * must borrow. The options object never closes this borrowed runtime.
         */
        public Builder language(BlueLanguage language) {
            this.language = language;
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

        /**
         * Selects exact Timeline Entry and Operation Request identities for
         * the assembled immutable runtime generation.
         */
        public Builder semanticTypeIdentities(
                CoordinationSemanticTypeIdentities identities) {
            this.semanticTypeIdentities = java.util.Objects.requireNonNull(
                    identities, "identities");
            return this;
        }

        public CoordinationProcessorOptions build() {
            return new CoordinationProcessorOptions(this);
        }
    }
}
