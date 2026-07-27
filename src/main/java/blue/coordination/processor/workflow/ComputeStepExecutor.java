package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.result.BexExecutionResult;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.BexWorkflowContextFactory;
import blue.language.model.Node;
import blue.language.processor.ProcessorFatalException;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;

public final class ComputeStepExecutor implements WorkflowStepExecutor<Compute>, AutoCloseable {
    private final BexEngine bexEngine;
    private final long defaultGasLimit;
    private final ComputeDefinitionResolver definitionResolver;
    private final BexWorkflowContextFactory contextFactory;
    private final ComputeResultEmitter resultEmitter;
    private final ComputeProgramNormalizer normalizer;
    private final ComputeProgramPlanCache planCache;
    private final BexProcessingMetrics metrics;

    public ComputeStepExecutor() {
        this(BexEngine.builder().build(), 100_000L);
    }

    public ComputeStepExecutor(BexEngine bexEngine, long defaultGasLimit) {
        this(bexEngine,
                defaultGasLimit,
                new ComputeDefinitionResolver(),
                new BexWorkflowContextFactory(),
                new ComputeResultEmitter(),
                null);
    }

    ComputeStepExecutor(BexEngine bexEngine,
                        long defaultGasLimit,
                        ComputeDefinitionResolver definitionResolver,
                        BexWorkflowContextFactory contextFactory,
                        ComputeResultEmitter resultEmitter,
                        BexProcessingMetrics metrics) {
        if (bexEngine == null) {
            throw new IllegalArgumentException("bexEngine must not be null");
        }
        if (defaultGasLimit <= 0L) {
            throw new IllegalArgumentException("defaultGasLimit must be positive");
        }
        if (definitionResolver == null) {
            throw new IllegalArgumentException("definitionResolver must not be null");
        }
        if (contextFactory == null) {
            throw new IllegalArgumentException("contextFactory must not be null");
        }
        if (resultEmitter == null) {
            throw new IllegalArgumentException("resultEmitter must not be null");
        }
        this.bexEngine = bexEngine;
        this.defaultGasLimit = defaultGasLimit;
        this.definitionResolver = definitionResolver;
        this.contextFactory = contextFactory;
        this.resultEmitter = resultEmitter;
        this.normalizer = new ComputeProgramNormalizer(metrics);
        this.planCache = new ComputeProgramPlanCache(
                ComputeProgramPlanCache.DEFAULT_MAX_ENTRIES,
                ComputeProgramPlanCache.DEFAULT_MAX_WEIGHT_BYTES,
                metrics);
        this.metrics = metrics;
    }

    @Override
    public boolean supports(SequentialWorkflowStep step) {
        return step instanceof Compute;
    }

    @Override
    public WorkflowStepResult execute(Compute step, StepExecutionContext context) {
        long stepStart = System.nanoTime();
        try {
            if (metrics != null) {
                metrics.incrementComputeStepsExecuted();
            }
            if (!supportsManifestBoundRuntimeCounters()) {
                context.processorContext().throwFatal(
                        "Compute runtime capability is unavailable: "
                                + "blue-bex-java 1.1 does not expose "
                                + "manifest-bound named runtime counters");
                return WorkflowStepResult.none();
            }
            FrozenNode rawStepNode = context.stepFrozenNode();
            if (rawStepNode == null) {
                Node mutableStepNode = context.stepNodeRef();
                if (mutableStepNode == null) {
                    context.processorContext().throwFatal("Compute step must have a raw step node");
                    return WorkflowStepResult.none();
                }
                rawStepNode = FrozenNode.fromResolvedNode(mutableStepNode);
            }
            long resolveStart = System.nanoTime();
            final FrozenNode resolvedDefinitionNode = definitionResolver.resolve(rawStepNode,
                    context,
                    metrics);
            if (metrics != null) {
                metrics.addComputeDefinitionResolveNanos(System.nanoTime() - resolveStart);
            }
            final FrozenNode exactRawStepNode = rawStepNode;
            final String effectiveEntry = FrozenNodeUtil.textProperty(rawStepNode, "entry");
            ComputeProgramPlanCache.Key planKey = ComputeProgramPlanCache.Key.from(
                    rawStepNode,
                    resolvedDefinitionNode,
                    effectiveEntry,
                    normalizer.normalizationVersion());
            ComputeProgramPlanCache.Lookup lookup = planCache.lookup(planKey,
                    new ComputeProgramPlanCache.PlanFactory() {
                        @Override
                        public ComputeProgramPlan create() {
                            return buildPlan(exactRawStepNode,
                                    resolvedDefinitionNode,
                                    effectiveEntry);
                        }
                    });
            ComputeProgramPlan computePlan = lookup.plan();
            long contextStart = System.nanoTime();
            BexExecutionContext bexContext = contextFactory.create(context, computePlan.gasLimit());
            if (metrics != null) {
                metrics.addComputeContextBuildNanos(System.nanoTime() - contextStart);
            }
            long executeStart = System.nanoTime();
            BexExecutionResult result = bexEngine.compileAndExecute(computePlan.source(), bexContext);
            if (metrics != null) {
                metrics.addComputeCompileExecuteNanos(System.nanoTime() - executeStart);
                metrics.addBexMetrics(result.metrics());
            }
            ComputeEffectPlan effectPlan = resultEmitter.plan(result,
                    context,
                    computePlan.emitEvents());
            resultEmitter.buffer(effectPlan, context);
            WorkflowStepResult stepResult;
            if (effectPlan.terminationRequested()) {
                stepResult = computePlan.returnResult()
                        ? WorkflowStepResult.terminalValue(result, effectPlan.changesetHandled())
                        : WorkflowStepResult.terminal();
            } else {
                stepResult = computePlan.returnResult()
                        ? WorkflowStepResult.value(result, effectPlan.changesetHandled())
                        : WorkflowStepResult.none();
            }
            // Failed compilation, execution, result validation, gas handling,
            // or effect buffering never publishes a candidate plan.
            planCache.publish(lookup);
            return stepResult;
        } catch (ComputeResultValidationException ex) {
            if (metrics != null) {
                metrics.incrementComputeResultValidationFailures();
            }
            context.processorContext().throwFatal("Invalid Compute result: " + ex.getMessage());
            return WorkflowStepResult.none();
        } catch (ProcessorFatalException ex) {
            throw ex;
        } catch (BexException ex) {
            context.processorContext().throwFatal("Compute failed: " + ex.getMessage());
            return WorkflowStepResult.none();
        } catch (RuntimeException ex) {
            context.processorContext().throwFatal("Compute failed: " + ex.getMessage());
            return WorkflowStepResult.none();
        } finally {
            if (metrics != null) {
                metrics.addComputeStepNanos(System.nanoTime() - stepStart);
            }
        }
    }

    private long computeGasLimit(FrozenNode stepNode) {
        Long parsed = FrozenNodeUtil.integer(FrozenNodeUtil.property(stepNode, "gasLimit"));
        if (parsed == null) {
            return defaultGasLimit;
        }
        if (parsed.longValue() <= 0L) {
            throw new BexException("Compute gasLimit must be positive");
        }
        return parsed.longValue();
    }

    /**
     * The Contracts 1.0 child-ledger API cannot accept BEX's legacy aggregate
     * {@code gasUsed()} value. Keep the evaluator fail-closed until the runtime
     * supplies a closed, manifest-bound named counter stream that can be
     * admitted live by the parent ledger.
     */
    private boolean supportsManifestBoundRuntimeCounters() {
        return false;
    }

    /** Clears reusable Compute plans while keeping this executor usable. */
    public void clearPlanCache() {
        planCache.clear();
    }

    @Override
    public void close() {
        planCache.close();
    }

    int cachedPlanCount() {
        return planCache.size();
    }

    long cachedPlanWeightBytes() {
        return planCache.weightBytes();
    }

    boolean isPlanCacheClosed() {
        return planCache.isClosed();
    }

    private ComputeProgramPlan buildPlan(FrozenNode rawStepNode,
                                         FrozenNode rawDefinitionNode,
                                         String effectiveEntry) {
        FrozenNode programNode = normalizer.program(rawStepNode);
        FrozenNode definitionNode = rawDefinitionNode != null
                ? normalizer.definition(rawDefinitionNode)
                : null;
        String normalizedEntry = FrozenNodeUtil.textProperty(programNode, "entry");
        // The key is built from the authored effective entry. Retain the
        // normalized value in the source to preserve the pre-cache behavior.
        if (effectiveEntry == null ? normalizedEntry != null : !effectiveEntry.equals(normalizedEntry)) {
            throw new BexException("Compute entry changed during normalization");
        }
        long sourceStart = System.nanoTime();
        BexProgramSource source = definitionNode != null
                ? BexProgramSource.withDefinition(programNode, definitionNode, normalizedEntry)
                : BexProgramSource.inline(programNode);
        if (metrics != null) {
            metrics.incrementComputeProgramSourceBuilds();
            metrics.addComputeProgramSourceBuildNanos(System.nanoTime() - sourceStart);
        }
        return new ComputeProgramPlan(programNode,
                definitionNode,
                source,
                normalizedEntry,
                computeGasLimit(programNode),
                FrozenNodeUtil.booleanProperty(programNode, "emitEvents", true),
                FrozenNodeUtil.booleanProperty(programNode, "returnResult", true),
                rawStepNode,
                rawDefinitionNode);
    }

}
