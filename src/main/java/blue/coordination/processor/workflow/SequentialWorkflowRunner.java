package blue.coordination.processor.workflow;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationBexIntrinsics;
import blue.coordination.processor.CoordinationRuntimeLimits;
import blue.coordination.processor.CoordinationRuntimeGas;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.BexWorkflowContextFactory;
import blue.coordination.processor.bex.ProcessingEventIdentityObserver;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.WorkingDocument;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Runs selected Sequential Workflow steps in declaration order within one
 * atomic Contracts processing invocation.
 *
 * <p>Each successful step publishes its effects to later steps. Termination
 * stops the remaining steps, while any failure is delegated to the parent
 * processor so the complete Root transition rolls back.</p>
 */
public final class SequentialWorkflowRunner implements AutoCloseable {
    private final List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors;
    private final BexProcessingMetrics metrics;
    private final ProcessingEventIdentityObserver
            processingEventIdentityObserver;
    private final SequentialWorkflowPlanCache planCache;

    public SequentialWorkflowRunner() {
        this(defaultExecutors());
    }

    public SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors) {
        this(executors, null);
    }

    private SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                                     BexProcessingMetrics metrics) {
        this(executors, metrics, null);
    }

    private SequentialWorkflowRunner(
            List<WorkflowStepExecutor<? extends SequentialWorkflowStep>>
                    executors,
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver) {
        this(executors,
                metrics,
                processingEventIdentityObserver,
                SequentialWorkflowPlanCache.DEFAULT_MAX_ENTRIES,
                SequentialWorkflowPlanCache.DEFAULT_MAX_WEIGHT_BYTES);
    }

    SequentialWorkflowRunner(List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                             BexProcessingMetrics metrics,
                             int planCacheMaxEntries,
                             long planCacheMaxWeightBytes) {
        this(
                executors,
                metrics,
                null,
                planCacheMaxEntries,
                planCacheMaxWeightBytes);
    }

    SequentialWorkflowRunner(
            List<WorkflowStepExecutor<? extends SequentialWorkflowStep>>
                    executors,
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver,
            int planCacheMaxEntries,
            long planCacheMaxWeightBytes) {
        if (executors == null) {
            throw new IllegalArgumentException("executors must not be null");
        }
        this.executors = Collections.unmodifiableList(new ArrayList<WorkflowStepExecutor<? extends SequentialWorkflowStep>>(executors));
        this.metrics = metrics;
        this.processingEventIdentityObserver =
                processingEventIdentityObserver;
        this.planCache = new SequentialWorkflowPlanCache(planCacheMaxEntries,
                planCacheMaxWeightBytes,
                metrics);
    }

    public void execute(SequentialWorkflow workflow, ProcessorExecutionContext context) {
        long start = System.nanoTime();
        WorkflowBexGasLedgerHost bexGasLedgerHost =
                new WorkflowBexGasLedgerHost(context);
        CoordinationRuntimeGas.Ledger coordinationGas = null;
        Throwable failure = null;
        try {
            observeProcessingEvent(context);
            List<SequentialWorkflowStep> steps = workflow.getSteps();
            if (steps == null) {
                return;
            }
            coordinationGas = CoordinationRuntimeGas.open(
                    context.runtimeWorkSession());
            FrozenNode contractNode = rawContractNode(context);
            if (steps.size() > CoordinationRuntimeLimits.MAX_WORKFLOW_STEPS) {
                context.throwFatal("Sequential Workflow exceeds the portable "
                        + "step limit of "
                        + CoordinationRuntimeLimits.MAX_WORKFLOW_STEPS);
                return;
            }
            SequentialWorkflowPlan plan = null;
            WorkflowExecutionState executionState = new WorkflowExecutionState();
            try (WorkingDocument workingDocument = rootWorkingDocument(context)) {
                for (int i = 0; i < steps.size(); i++) {
                    charge(
                            coordinationGas,
                            context,
                            "workflowStepVisited",
                            "visit Sequential Workflow step");
                    SequentialWorkflowStep step = steps.get(i);
                    charge(
                            coordinationGas,
                            context,
                            "workflowStepExecuted",
                            "execute Sequential Workflow step");
                    chargeStepKind(
                            coordinationGas,
                            context,
                            step);
                    if (metrics != null) {
                        metrics.incrementWorkflowStepsExecuted();
                    }
                    if (plan == null) {
                        plan = workflowPlanAfterAdmission(
                                contractNode,
                                steps);
                    }
                    SequentialWorkflowPlan.PlannedStep planned =
                            plan.planAdmittedStep(
                                    step,
                                    i,
                                    executors,
                                    metrics);
                    if (planned.published()
                            && contractNode != null) {
                        planCache.refreshWeight(plan);
                    }
                    SequentialWorkflowPlan.StepPlan stepPlan =
                            planned.step();
                    WorkflowStepResult result = executeStep(workflow,
                            step,
                            stepPlan,
                            contractNode,
                            executionState,
                            context,
                            bexGasLedgerHost,
                            workingDocument);
                    if (result != null && result.hasValue()) {
                        executionState.record(stepPlan.key(), result.value(), result.changesetHandled());
                    }
                    if (result != null && result.isTerminal()) {
                        break;
                    }
                }
            }
        } catch (ExecutionEvidenceUnavailableException ex) {
            bexGasLedgerHost.discardForUnavailableEvidence();
            failure = ex;
            throw ex;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            if (coordinationGas != null
                    && coordinationGas.isSessionOpen()
                    && !(failure
                    instanceof GasLimitExceededException)) {
                try {
                    coordinationGas.submit();
                } catch (RuntimeException | Error gasFailure) {
                    if (failure != null
                            && failure != gasFailure) {
                        failure.addSuppressed(gasFailure);
                    } else {
                        throw gasFailure;
                    }
                }
            }
            bexGasLedgerHost.submitToParent(failure);
            if (metrics != null) {
                metrics.addWorkflowRunnerNanos(System.nanoTime() - start);
            }
        }
    }

    private void observeProcessingEvent(
            ProcessorExecutionContext context) {
        if (processingEventIdentityObserver == null
                || !context.hasProcessEvent()) {
            return;
        }
        FrozenNode processingEvent =
                context.frozenProcessEvent();
        processingEventIdentityObserver.observe(
                processingEvent,
                processingEvent.blueId(),
                ProcessingEventIdentityObserver.Boundary
                        .WORKFLOW);
    }

    private static void chargeStepKind(
            CoordinationRuntimeGas.Ledger gas,
            ProcessorExecutionContext context,
            SequentialWorkflowStep step) {
        if (step instanceof UpdateDocument) {
            charge(gas, context, "updateDocumentStep",
                    "normalize Update Document step");
        } else if (step instanceof TriggerEvent) {
            charge(gas, context, "triggerEventStep",
                    "normalize Trigger Event step");
        } else if (step instanceof TerminateProcessing) {
            charge(gas, context, "terminateProcessingStep",
                    "normalize Terminate Processing step");
        } else if (step instanceof Compute) {
            charge(gas, context, "computeStepEntered",
                    "enter Compute step");
            charge(gas, context, "computeDefinitionResolved",
                    "resolve Compute definition");
        }
    }

    private static void charge(
            CoordinationRuntimeGas.Ledger gas,
            ProcessorExecutionContext context,
            String counter,
            String reason) {
        gas.charge(
                counter,
                1L,
                GasChargeContext.of(
                        context.scopePath(),
                        context.contractKey(),
                        null,
                        reason));
    }

    private WorkflowStepResult executeStep(SequentialWorkflow workflow,
                                           SequentialWorkflowStep step,
                                           SequentialWorkflowPlan.StepPlan stepPlan,
                                           FrozenNode contractNode,
                                           WorkflowExecutionState executionState,
                                           ProcessorExecutionContext context,
                                           WorkflowBexGasLedgerHost bexGasLedgerHost,
                                           WorkingDocument workingDocument) {
        if (step == null) {
            bexGasLedgerHost.submitToParent();
            context.throwFatal("Unsupported null sequential workflow step");
            return WorkflowStepResult.none();
        }
        WorkflowStepExecutor<? extends SequentialWorkflowStep> executor = stepPlan.executor();
        if (executor == null) {
            bexGasLedgerHost.submitToParent();
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
                bexGasLedgerHost,
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
        return executorsFor(BexEngine.builder()
                .intrinsics(CoordinationBexIntrinsics.common())
                .build(), 100_000L);
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
        return withBexEngine(
                bexEngine,
                computeGasLimit,
                metrics,
                null);
    }

    public static SequentialWorkflowRunner withBexEngine(
            BexEngine bexEngine,
            long computeGasLimit,
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver) {
        if (bexEngine == null) {
            throw new IllegalArgumentException("bexEngine must not be null");
        }
        if (computeGasLimit <= 0L) {
            throw new IllegalArgumentException("computeGasLimit must be positive");
        }
        return new SequentialWorkflowRunner(
                executorsFor(
                        bexEngine,
                        computeGasLimit,
                        metrics,
                        processingEventIdentityObserver),
                metrics,
                processingEventIdentityObserver);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executorsFor(BexEngine bexEngine,
                                                                                            long computeGasLimit) {
        return executorsFor(bexEngine, computeGasLimit, null);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executorsFor(BexEngine bexEngine,
                                                                                            long computeGasLimit,
                                                                                            BexProcessingMetrics metrics) {
        return executorsFor(
                bexEngine,
                computeGasLimit,
                metrics,
                null);
    }

    private static List<WorkflowStepExecutor<? extends SequentialWorkflowStep>>
    executorsFor(
            BexEngine bexEngine,
            long computeGasLimit,
            BexProcessingMetrics metrics,
            ProcessingEventIdentityObserver
                    processingEventIdentityObserver) {
        BexWorkflowContextFactory bexContextFactory =
                new BexWorkflowContextFactory(
                        metrics,
                        processingEventIdentityObserver);
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

    private SequentialWorkflowPlan workflowPlanAfterAdmission(
            final FrozenNode contractNode,
            final List<SequentialWorkflowStep> steps) {
        if (contractNode == null) {
            if (metrics != null) {
                metrics.incrementWorkflowPlanCacheMisses();
            }
            SequentialWorkflowPlan plan =
                    SequentialWorkflowPlan.build(
                            null,
                            steps);
            if (metrics != null) {
                metrics.incrementWorkflowPlansBuilt();
            }
            return plan;
        }
        return planCache.getOrBuild(contractNode.resolvedStructuralKey(),
                new SequentialWorkflowPlanCache.PlanFactory() {
                    @Override
                    public SequentialWorkflowPlan build() {
                        return SequentialWorkflowPlan.build(
                                contractNode,
                                steps);
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

    /**
     * Current number of retained workflow plans.
     *
     * @return number of plans retained by the workflow plan cache
     */
    public int workflowPlanCacheSize() {
        return planCache.size();
    }

    /**
     * Current approximate retained workflow-plan weight.
     *
     * @return approximate retained plan weight in bytes
     */
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
