package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;

import java.util.List;

/**
 * Lazily populated execution structure for one exact frozen workflow
 * contract.
 *
 * <p>The shell retains only the contract and one slot per runtime step. An
 * executor and any static Update Document template are selected only after the
 * runner has admitted that exact step's portable gas charges. Published step
 * plans are immutable and may then be reused safely by concurrent
 * executions.</p>
 */
final class SequentialWorkflowPlan {
    private static final long PLAN_BASE_BYTES = 128L;
    private static final long STEP_SLOT_BYTES = 8L;
    private static final long STEP_PLAN_BYTES = 96L;
    private static final long RETAINED_EXACT_CONTRACT_BYTES = 96L;
    private static final long RETAINED_EXACT_STEP_BYTES = 96L;

    private final FrozenNode contractNode;
    private final FrozenNode.ResolvedStructuralKey contractIdentity;
    private final StepPlan[] steps;
    private long approximateWeightBytes;

    private SequentialWorkflowPlan(
            FrozenNode contractNode,
            int stepCount) {
        this.contractNode = contractNode;
        this.contractIdentity = contractNode != null
                ? contractNode.resolvedStructuralKey()
                : null;
        this.steps = new StepPlan[stepCount];
        this.approximateWeightBytes =
                estimateShellWeight(contractNode, stepCount);
    }

    static SequentialWorkflowPlan build(
            FrozenNode contractNode,
            List<SequentialWorkflowStep> workflowSteps) {
        if (workflowSteps == null) {
            throw new IllegalArgumentException(
                    "workflowSteps must not be null");
        }
        return new SequentialWorkflowPlan(
                contractNode,
                workflowSteps.size());
    }

    synchronized PlannedStep planAdmittedStep(
            SequentialWorkflowStep step,
            int index,
            List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
            BexProcessingMetrics metrics) {
        if (index < 0) {
            throw new IndexOutOfBoundsException(
                    "step index must not be negative");
        }
        StepPlan cached = index < steps.length
                ? steps[index]
                : null;
        if (cached != null && cached.matches(step)) {
            return new PlannedStep(cached, false);
        }
        StepPlan planned = planStep(
                step,
                frozenStep(index),
                index,
                executors,
                metrics);
        if (cached == null && index < steps.length) {
            steps[index] = planned;
            approximateWeightBytes = saturatedAdd(
                    approximateWeightBytes,
                    estimateStepWeight(planned));
            return new PlannedStep(planned, true);
        }
        /*
         * A runtime-class mismatch cannot be shared under the structural cache
         * key. Execute the exact fallback without replacing a plan that may be
         * in use concurrently.
         */
        return new PlannedStep(planned, false);
    }

    static StepPlan planStep(
            SequentialWorkflowStep step,
            FrozenNode frozenStep,
            int index,
            List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
            BexProcessingMetrics metrics) {
        WorkflowStepExecutor<? extends SequentialWorkflowStep> selected = null;
        if (step != null) {
            for (WorkflowStepExecutor<? extends SequentialWorkflowStep> executor
                    : executors) {
                if (metrics != null) {
                    metrics.incrementWorkflowExecutorLookups();
                }
                if (executor.supports(step)) {
                    selected = executor;
                    break;
                }
            }
        }
        StaticUpdatePlan staticUpdatePlan = null;
        if (step instanceof UpdateDocument && frozenStep != null) {
            StaticUpdatePlan candidate = StaticUpdatePlan.compile(
                    FrozenNodeUtil.property(
                            frozenStep,
                            "changeset"),
                    metrics);
            if (candidate.valid()) {
                staticUpdatePlan = candidate;
                if (metrics != null) {
                    metrics.incrementUpdateStaticTemplatesBuilt();
                }
            }
        }
        return new StepPlan(
                index,
                stepKey(frozenStep, index),
                stepName(step),
                frozenStep,
                step != null ? step.getClass() : null,
                selected,
                staticUpdatePlan);
    }

    FrozenNode.ResolvedStructuralKey contractIdentity() {
        return contractIdentity;
    }

    synchronized StepPlan step(int index) {
        return steps[index];
    }

    synchronized long approximateWeightBytes() {
        return approximateWeightBytes;
    }

    private FrozenNode frozenStep(int index) {
        if (contractNode == null
                || contractNode.getProperties() == null) {
            return null;
        }
        FrozenNode stepsNode =
                contractNode.getProperties().get("steps");
        if (stepsNode == null
                || stepsNode.getItems() == null
                || index >= stepsNode.getItems().size()) {
            return null;
        }
        return stepsNode.getItems().get(index);
    }

    private static String stepKey(
            FrozenNode stepNode,
            int index) {
        if (stepNode != null
                && stepNode.getName() != null
                && !stepNode.getName().trim().isEmpty()) {
            return stepNode.getName().trim();
        }
        return "Step" + (index + 1);
    }

    private static String stepName(
            SequentialWorkflowStep step) {
        if (step == null) {
            return "null sequential workflow step";
        }
        if (step instanceof TriggerEvent) {
            return "Coordination/Trigger Event";
        }
        if (step instanceof Compute) {
            return "Coordination/Compute";
        }
        if (step instanceof TerminateProcessing) {
            return "Coordination/Terminate Processing";
        }
        return step.getClass().getName();
    }

    private static long estimateShellWeight(
            FrozenNode contractNode,
            int stepCount) {
        long weight = PLAN_BASE_BYTES;
        if (contractNode != null) {
            weight = saturatedAdd(
                    weight,
                    RETAINED_EXACT_CONTRACT_BYTES);
        }
        for (int index = 0; index < stepCount; index++) {
            weight = saturatedAdd(
                    weight,
                    STEP_SLOT_BYTES);
        }
        return Math.max(1L, weight);
    }

    private static long estimateStepWeight(
            StepPlan step) {
        long weight = STEP_PLAN_BYTES;
        weight = saturatedAdd(
                weight,
                stringWeight(step.key));
        weight = saturatedAdd(
                weight,
                stringWeight(step.kind));
        if (step.frozenStep != null) {
            weight = saturatedAdd(
                    weight,
                    RETAINED_EXACT_STEP_BYTES);
        }
        if (step.staticUpdatePlan != null) {
            weight = saturatedAdd(
                    weight,
                    step.staticUpdatePlan
                            .approximateWeightBytes());
        }
        return weight;
    }

    private static long saturatedAdd(
            long left,
            long right) {
        if (right > 0L
                && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long stringWeight(String value) {
        return value == null
                ? 0L
                : 40L + 2L * value.length();
    }

    static final class PlannedStep {
        private final StepPlan step;
        private final boolean published;

        private PlannedStep(
                StepPlan step,
                boolean published) {
            this.step = step;
            this.published = published;
        }

        StepPlan step() {
            return step;
        }

        boolean published() {
            return published;
        }
    }

    static final class StepPlan {
        private final int index;
        private final String key;
        private final String kind;
        private final FrozenNode frozenStep;
        private final Class<?> runtimeStepClass;
        private final WorkflowStepExecutor<? extends SequentialWorkflowStep> executor;
        private final StaticUpdatePlan staticUpdatePlan;

        private StepPlan(
                int index,
                String key,
                String kind,
                FrozenNode frozenStep,
                Class<?> runtimeStepClass,
                WorkflowStepExecutor<? extends SequentialWorkflowStep> executor,
                StaticUpdatePlan staticUpdatePlan) {
            this.index = index;
            this.key = key;
            this.kind = kind;
            this.frozenStep = frozenStep;
            this.runtimeStepClass = runtimeStepClass;
            this.executor = executor;
            this.staticUpdatePlan = staticUpdatePlan;
        }

        int index() {
            return index;
        }

        String key() {
            return key;
        }

        String kind() {
            return kind;
        }

        FrozenNode frozenStep() {
            return frozenStep;
        }

        WorkflowStepExecutor<? extends SequentialWorkflowStep> executor() {
            return executor;
        }

        boolean matches(
                SequentialWorkflowStep step) {
            return step == null
                    ? runtimeStepClass == null
                    : step.getClass() == runtimeStepClass;
        }

        StaticUpdatePlan staticUpdatePlan() {
            return staticUpdatePlan;
        }
    }
}
