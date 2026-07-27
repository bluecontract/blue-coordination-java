package blue.coordination.processor.workflow;

import blue.bex.api.BexEngine;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.BexWorkflowContextFactory;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.WorkingDocument;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SequentialWorkflowRunner implements AutoCloseable {
    private final List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors;
    private final BexProcessingMetrics metrics;
    private final SequentialWorkflowPlanCache planCache;

    public SequentialWorkflowRunner() {
        this(defaultExecutors());
    }

    public SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors) {
        this(executors, null);
    }

    private SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                                     BexProcessingMetrics metrics) {
        this(executors,
                metrics,
                SequentialWorkflowPlanCache.DEFAULT_MAX_ENTRIES,
                SequentialWorkflowPlanCache.DEFAULT_MAX_WEIGHT_BYTES);
    }

    SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                             BexProcessingMetrics metrics,
                             int planCacheMaxEntries,
                             long planCacheMaxWeightBytes) {
        if (executors == null) {
            throw new IllegalArgumentException("executors must not be null");
        }
        this.executors = Collections.unmodifiableList(new ArrayList<WorkflowStepExecutor<? extends SequentialWorkflowStep>>(executors));
        this.metrics = metrics;
        this.planCache = new SequentialWorkflowPlanCache(planCacheMaxEntries,
                planCacheMaxWeightBytes,
                metrics);
    }

    public void execute(SequentialWorkflow workflow, ProcessorExecutionContext context) {
        long start = System.nanoTime();
        try {
            if (workflow.getSteps() == null) {
                return;
            }
            FrozenNode contractNode = rawContractNode(context);
            List<SequentialWorkflowStep> steps = workflow.getSteps();
            SequentialWorkflowPlan plan = workflowPlan(contractNode, steps);
            WorkflowExecutionState executionState = new WorkflowExecutionState();
            try (WorkingDocument workingDocument = rootWorkingDocument(context)) {
                for (int i = 0; i < steps.size(); i++) {
                    SequentialWorkflowStep step = steps.get(i);
                    SequentialWorkflowPlan.StepPlan stepPlan = i < plan.stepCount()
                            ? plan.step(i)
                            : SequentialWorkflowPlan.planStep(step,
                                    null,
                                    i,
                                    executors,
                                    metrics);
                    if (!stepPlan.matches(step)) {
                        stepPlan = SequentialWorkflowPlan.planStep(step,
                                stepPlan.frozenStep(),
                                i,
                                executors,
                                metrics);
                    }
                    if (metrics != null) {
                        metrics.incrementWorkflowStepsExecuted();
                    }
                    WorkflowStepResult result = executeStep(workflow,
                            step,
                            stepPlan,
                            contractNode,
                            executionState,
                            context,
                            workingDocument);
                    if (result != null && result.hasValue()) {
                        executionState.record(stepPlan.key(), result.value(), result.changesetHandled());
                    }
                    if (result != null && result.isTerminal()) {
                        break;
                    }
                }
            }
        } finally {
            if (metrics != null) {
                metrics.addWorkflowRunnerNanos(System.nanoTime() - start);
            }
        }
    }

    private WorkflowStepResult executeStep(SequentialWorkflow workflow,
                                           SequentialWorkflowStep step,
                                           SequentialWorkflowPlan.StepPlan stepPlan,
                                           FrozenNode contractNode,
                                           WorkflowExecutionState executionState,
                                           ProcessorExecutionContext context,
                                           WorkingDocument workingDocument) {
        if (step == null) {
            context.throwFatal("Unsupported null sequential workflow step");
            return WorkflowStepResult.none();
        }
        WorkflowStepExecutor<? extends SequentialWorkflowStep> executor = stepPlan.executor();
        if (executor == null) {
            context.throwFatal("Unsupported sequential workflow step: " + stepPlan.kind());
            return WorkflowStepResult.none();
        }
        WorkflowExecutionState.Snapshot stateView = executionState.snapshotView();
        if (metrics != null) {
            metrics.incrementWorkflowStepResultViewHits();
        }
        StepExecutionContext stepContext = new StepExecutionContext(context,
                workflow,
                step,
                stepPlan.frozenStep(),
                contractNode,
                stepPlan.index(),
                stateView,
                stepPlan.staticUpdatePlan(),
                workingDocument);
        return executeSupported(executor, step, stepContext);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private WorkflowStepResult executeSupported(WorkflowStepExecutor executor,
                                                SequentialWorkflowStep step,
                                                StepExecutionContext context) {
        return executor.execute(step, context);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> defaultExecutors() {
        return executorsFor(BexEngine.builder().build(), 100_000L);
    }

    public static SequentialWorkflowRunner withBexEngine(BexEngine bexEngine) {
        if (bexEngine == null) {
            throw new IllegalArgumentException("bexEngine must not be null");
        }
        return new SequentialWorkflowRunner(executorsFor(bexEngine, 100_000L));
    }

    public static SequentialWorkflowRunner withBexEngine(BexEngine bexEngine,
                                                         long computeGasLimit) {
        return withBexEngine(bexEngine, computeGasLimit, null);
    }

    public static SequentialWorkflowRunner withBexEngine(BexEngine bexEngine,
                                                         long computeGasLimit,
                                                         BexProcessingMetrics metrics) {
        if (bexEngine == null) {
            throw new IllegalArgumentException("bexEngine must not be null");
        }
        if (computeGasLimit <= 0L) {
            throw new IllegalArgumentException("computeGasLimit must be positive");
        }
        return new SequentialWorkflowRunner(executorsFor(bexEngine, computeGasLimit, metrics), metrics);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executorsFor(BexEngine bexEngine,
                                                                                            long computeGasLimit) {
        return executorsFor(bexEngine, computeGasLimit, null);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executorsFor(BexEngine bexEngine,
                                                                                            long computeGasLimit,
                                                                                            BexProcessingMetrics metrics) {
        BexWorkflowContextFactory bexContextFactory = new BexWorkflowContextFactory(metrics);
        return Arrays.<WorkflowStepExecutor<? extends SequentialWorkflowStep>>asList(
                new TriggerEventStepExecutor(metrics),
                new ComputeStepExecutor(bexEngine,
                        computeGasLimit,
                        new ComputeDefinitionResolver(metrics),
                        bexContextFactory,
                        new ComputeResultEmitter(metrics),
                        metrics),
                new TerminateProcessingStepExecutor(metrics),
                new UpdateDocumentStepExecutor(metrics));
    }

    private SequentialWorkflowPlan workflowPlan(final FrozenNode contractNode,
                                                 final List<SequentialWorkflowStep> steps) {
        if (contractNode == null) {
            if (metrics != null) {
                metrics.incrementWorkflowPlanCacheMisses();
            }
            SequentialWorkflowPlan plan = SequentialWorkflowPlan.build(null, steps, executors, metrics);
            if (metrics != null) {
                metrics.incrementWorkflowPlansBuilt();
            }
            return plan;
        }
        return planCache.getOrBuild(contractNode.resolvedStructuralKey(),
                new SequentialWorkflowPlanCache.PlanFactory() {
                    @Override
                    public SequentialWorkflowPlan build() {
                        return SequentialWorkflowPlan.build(contractNode, steps, executors, metrics);
                    }
                });
    }

    /** Clears all immutable workflow plans retained by this runner. */
    public void clearCaches() {
        planCache.clear();
        for (WorkflowStepExecutor<? extends SequentialWorkflowStep> executor : executors) {
            if (executor instanceof ComputeStepExecutor) {
                ((ComputeStepExecutor) executor).clearPlanCache();
            }
        }
    }

    /** Current number of retained workflow plans. */
    public int workflowPlanCacheSize() {
        return planCache.size();
    }

    /** Current approximate retained workflow-plan weight. */
    public long workflowPlanCacheWeightBytes() {
        return planCache.weightBytes();
    }

    @Override
    public void close() {
        planCache.close();
        for (WorkflowStepExecutor<? extends SequentialWorkflowStep> executor : executors) {
            if (executor instanceof ComputeStepExecutor) {
                ((ComputeStepExecutor) executor).close();
            }
        }
    }

    private FrozenNode rawContractNode(ProcessorExecutionContext context) {
        return context.frozenContractNode();
    }

    private WorkingDocument rootWorkingDocument(ProcessorExecutionContext context) {
        WorkingDocument workingDocument = context.newWorkingDocument();
        if (metrics != null) {
            if (workingDocument.usedMaterializedFallback()) {
                metrics.incrementWorkflowDocumentViewsFromDocument();
            } else {
                metrics.incrementWorkflowDocumentViewsFromFrozen();
            }
        }
        return workingDocument;
    }

}
