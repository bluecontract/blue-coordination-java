package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Immutable execution structure for one exact frozen workflow contract. */
final class SequentialWorkflowPlan {
    private static final long PLAN_BASE_BYTES = 128L;
    private static final long STEP_PLAN_BYTES = 96L;

    private final FrozenNode.ResolvedStructuralKey contractIdentity;
    private final List<StepPlan> steps;
    private final long approximateWeightBytes;

    private SequentialWorkflowPlan(FrozenNode contractNode, List<StepPlan> steps) {
        this.contractIdentity = contractNode != null ? contractNode.resolvedStructuralKey() : null;
        this.steps = Collections.unmodifiableList(new ArrayList<StepPlan>(steps));
        this.approximateWeightBytes = estimateWeight(contractNode, this.steps);
    }

    static SequentialWorkflowPlan build(FrozenNode contractNode,
                                        List<SequentialWorkflowStep> workflowSteps,
                                        List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                                        BexProcessingMetrics metrics) {
        List<FrozenNode> frozenSteps = stepNodes(contractNode);
        int plannedStepCount = Math.max(workflowSteps.size(), frozenSteps.size());
        List<StepPlan> planned = new ArrayList<StepPlan>(plannedStepCount);
        for (int i = 0; i < plannedStepCount; i++) {
            FrozenNode frozenStep = i < frozenSteps.size() ? frozenSteps.get(i) : null;
            SequentialWorkflowStep workflowStep = i < workflowSteps.size() ? workflowSteps.get(i) : null;
            planned.add(planStep(workflowStep, frozenStep, i, executors, metrics));
        }
        return new SequentialWorkflowPlan(contractNode, planned);
    }

    static StepPlan planStep(SequentialWorkflowStep step,
                             FrozenNode frozenStep,
                             int index,
                             List<WorkflowStepExecutor<? extends SequentialWorkflowStep>> executors,
                             BexProcessingMetrics metrics) {
        WorkflowStepExecutor<? extends SequentialWorkflowStep> selected = null;
        if (step != null) {
            for (WorkflowStepExecutor<? extends SequentialWorkflowStep> executor : executors) {
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
                    FrozenNodeUtil.property(frozenStep, "changeset"), metrics);
            if (candidate.valid()) {
                staticUpdatePlan = candidate;
                if (metrics != null) {
                    metrics.incrementUpdateStaticTemplatesBuilt();
                }
            }
        }
        return new StepPlan(index,
                stepKey(frozenStep, index),
                stepName(step),
                frozenStep,
                step != null ? step.getClass() : null,
                selected,
                step instanceof TerminateProcessing,
                staticUpdatePlan);
    }

    FrozenNode.ResolvedStructuralKey contractIdentity() {
        return contractIdentity;
    }

    int stepCount() {
        return steps.size();
    }

    StepPlan step(int index) {
        return steps.get(index);
    }

    long approximateWeightBytes() {
        return approximateWeightBytes;
    }

    private static List<FrozenNode> stepNodes(FrozenNode contractNode) {
        if (contractNode == null || contractNode.getProperties() == null) {
            return Collections.emptyList();
        }
        FrozenNode stepsNode = contractNode.getProperties().get("steps");
        if (stepsNode == null || stepsNode.getItems() == null) {
            return Collections.emptyList();
        }
        return stepsNode.getItems();
    }

    private static String stepKey(FrozenNode stepNode, int index) {
        if (stepNode != null && stepNode.getName() != null && !stepNode.getName().trim().isEmpty()) {
            return stepNode.getName().trim();
        }
        return "Step" + (index + 1);
    }

    private static String stepName(SequentialWorkflowStep step) {
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

    private static long estimateWeight(FrozenNode contractNode, List<StepPlan> steps) {
        WeightEstimator identityEstimator = new WeightEstimator();
        WeightEstimator retainedStepEstimator = new WeightEstimator();
        long weight = PLAN_BASE_BYTES;
        // The exact structural key retains representation data comparable to
        // one traversal of the frozen graph.
        weight = saturatedAdd(weight, identityEstimator.node(contractNode));
        for (StepPlan step : steps) {
            weight = saturatedAdd(weight, STEP_PLAN_BYTES);
            weight = saturatedAdd(weight, retainedStepEstimator.string(step.key));
            weight = saturatedAdd(weight, retainedStepEstimator.string(step.kind));
            weight = saturatedAdd(weight, retainedStepEstimator.node(step.frozenStep));
            if (step.staticUpdatePlan != null) {
                weight = saturatedAdd(weight, step.staticUpdatePlan.approximateWeightBytes());
            }
        }
        return Math.max(1L, weight);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    static final class StepPlan {
        private final int index;
        private final String key;
        private final String kind;
        private final FrozenNode frozenStep;
        private final Class<?> runtimeStepClass;
        private final WorkflowStepExecutor<? extends SequentialWorkflowStep> executor;
        private final boolean declarativelyTerminal;
        private final StaticUpdatePlan staticUpdatePlan;

        private StepPlan(int index,
                         String key,
                         String kind,
                         FrozenNode frozenStep,
                         Class<?> runtimeStepClass,
                         WorkflowStepExecutor<? extends SequentialWorkflowStep> executor,
                         boolean declarativelyTerminal,
                         StaticUpdatePlan staticUpdatePlan) {
            this.index = index;
            this.key = key;
            this.kind = kind;
            this.frozenStep = frozenStep;
            this.runtimeStepClass = runtimeStepClass;
            this.executor = executor;
            this.declarativelyTerminal = declarativelyTerminal;
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

        boolean matches(SequentialWorkflowStep step) {
            return step == null ? runtimeStepClass == null : step.getClass() == runtimeStepClass;
        }

        boolean declarativelyTerminal() {
            return declarativelyTerminal;
        }

        StaticUpdatePlan staticUpdatePlan() {
            return staticUpdatePlan;
        }
    }

    private static final class WeightEstimator {
        private final IdentityHashMap<FrozenNode, Boolean> visited = new IdentityHashMap<FrozenNode, Boolean>();

        private long node(FrozenNode node) {
            if (node == null || visited.put(node, Boolean.TRUE) != null) {
                return 0L;
            }
            long weight = 160L;
            weight = saturatedAdd(weight, string(node.getName()));
            weight = saturatedAdd(weight, string(node.getDescription()));
            weight = saturatedAdd(weight, string(node.getReferenceBlueId()));
            weight = saturatedAdd(weight, string(node.getMergePolicy()));
            weight = saturatedAdd(weight, string(node.getPreviousBlueId()));
            weight = saturatedAdd(weight, scalar(node.getValue()));
            weight = saturatedAdd(weight, node(node.getType()));
            weight = saturatedAdd(weight, node(node.getItemType()));
            weight = saturatedAdd(weight, node(node.getKeyType()));
            weight = saturatedAdd(weight, node(node.getValueType()));
            weight = saturatedAdd(weight, node(node.getContracts()));
            weight = saturatedAdd(weight, node(node.getBlue()));
            if (node.getSchema() != null) {
                weight = saturatedAdd(weight, 256L);
            }
            if (node.getItems() != null) {
                weight = saturatedAdd(weight, 24L + 8L * node.getItems().size());
                for (FrozenNode item : node.getItems()) {
                    weight = saturatedAdd(weight, node(item));
                }
            }
            if (node.getProperties() != null) {
                weight = saturatedAdd(weight, 48L + 48L * node.getProperties().size());
                for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                    weight = saturatedAdd(weight, string(entry.getKey()));
                    weight = saturatedAdd(weight, node(entry.getValue()));
                }
            }
            return weight;
        }

        private long string(String value) {
            return value == null ? 0L : 40L + 2L * value.length();
        }

        private long scalar(Object value) {
            if (value == null) {
                return 0L;
            }
            if (value instanceof String) {
                return string((String) value);
            }
            if (value instanceof BigInteger) {
                return 48L + ((BigInteger) value).bitLength() / 8L;
            }
            if (value instanceof BigDecimal) {
                return 64L;
            }
            return 24L;
        }
    }
}
