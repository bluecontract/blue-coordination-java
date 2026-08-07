package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.processor.WorkingDocument;
import blue.language.processor.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.UpdateDocument;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a fixed Update Document step into frozen patches and delegates
 * patch validation and application to the generic Contracts engine.
 */
public final class UpdateDocumentStepExecutor implements WorkflowStepExecutor<UpdateDocument> {
    private final BexProcessingMetrics metrics;

    public UpdateDocumentStepExecutor() {
        this(null);
    }

    public UpdateDocumentStepExecutor(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean supports(SequentialWorkflowStep step) {
        return step instanceof UpdateDocument;
    }

    @Override
    public WorkflowStepResult execute(UpdateDocument step, StepExecutionContext context) {
        long stepStart = System.nanoTime();
        try {
            if (metrics != null) {
                metrics.incrementUpdateDocumentStepsExecuted();
            }
            StaticUpdatePlan staticPlan = context.staticUpdatePlan();
            if (staticPlan != null) {
                if (metrics != null) {
                    metrics.incrementUpdateStaticTemplateHits();
                }
                applyStaticPlan(
                        staticPlan, step, context);
                return WorkflowStepResult.none();
            }
            FrozenNode rawFrozenChangeset = FrozenNodeUtil.property(context.stepFrozenNode(), "changeset");
            if (rawFrozenChangeset != null
                    && rawFrozenChangeset.getItems() == null
                    && step.getChangeset() == null) {
                context.throwFatal("Update Document changeset must be a static patch list");
                return WorkflowStepResult.none();
            }
            List<WorkflowPatchEntry> changeset = literalChangeset(step, context);
            if (changeset.isEmpty()) {
                return WorkflowStepResult.none();
            }
            long conversionStart = System.nanoTime();
            List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>(changeset.size());
            for (WorkflowPatchEntry entry : changeset) {
                patches.add(toPatch(entry, context));
            }
            if (metrics != null) {
                metrics.addUpdatePatchConversionNanos(System.nanoTime() - conversionStart);
            }
            applyPatches(patches, context);
            return WorkflowStepResult.none();
        } finally {
            if (metrics != null) {
                metrics.addUpdateStepNanos(System.nanoTime() - stepStart);
            }
        }
    }

    private List<WorkflowPatchEntry> literalChangeset(UpdateDocument step, StepExecutionContext context) {
        FrozenNode frozenChangeset = FrozenNodeUtil.property(context.stepFrozenNode(), "changeset");
        if (frozenChangeset != null && frozenChangeset.getItems() != null) {
            List<WorkflowPatchEntry> entries =
                    new ArrayList<WorkflowPatchEntry>(frozenChangeset.getItems().size());
            for (int i = 0; i < frozenChangeset.getItems().size(); i++) {
                FrozenNode item = frozenChangeset.getItems().get(i);
                Node literal =
                        FrozenNodeUtil.authoredOverlay(item);
                literal =
                        resolveReferencedLiteralValue(
                                literal,
                                step,
                                i,
                                context);
                entries.add(literalPatchEntry(literal, i, context));
            }
            return entries;
        }
        if (step == null || step.getChangeset() == null) {
            return java.util.Collections.emptyList();
        }
        List<Node> rawChangeset = step.getChangeset();
        List<WorkflowPatchEntry> entries = new ArrayList<WorkflowPatchEntry>(rawChangeset.size());
        for (int i = 0; i < rawChangeset.size(); i++) {
            entries.add(literalPatchEntry(rawChangeset.get(i), i, context));
        }
        return entries;
    }

    private WorkflowPatchEntry literalPatchEntry(Node item, int index, StepExecutionContext context) {
        if (item == null) {
            return null;
        }
        if (item.getProperties() == null) {
            context.throwFatal("Update Document changeset entry " + index
                    + " must be a static patch object");
            return null;
        }
        String op = stringProperty(item, "op", index, context);
        String path = stringProperty(item, "path", index, context);
        if ("remove".equals(op)
                && item.getProperties().containsKey("val")) {
            context.throwFatal(
                    "Update Document patch value must be absent for remove");
            return null;
        }
        Node val = item.getProperties().get("val");
        return new WorkflowPatchEntry(op, path, val);
    }

    private String stringProperty(Node item, String key, int index, StepExecutionContext context) {
        Node property = item.getProperties().get(key);
        Object value = property != null ? property.getValue() : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            context.throwFatal("Update Document changeset entry " + index
                    + " field '" + key + "' must be text");
            return null;
        }
        return (String) value;
    }

    private FrozenJsonPatch toPatch(WorkflowPatchEntry entry, StepExecutionContext context) {
        if (entry == null) {
            context.throwFatal("Update Document changeset contains a null patch entry");
            return null;
        }
        String op = entry.op();
        String path = entry.path();
        if (op == null || op.isEmpty()) {
            context.throwFatal("Update Document patch operation is required");
            return null;
        }
        if (path == null || path.isEmpty()) {
            context.throwFatal("Update Document patch path is required");
            return null;
        }
        String absolutePath = context.processorContext().resolvePointer(path);
        if ("remove".equals(op)) {
            return FrozenJsonPatch.remove(absolutePath);
        }
        if (!"add".equals(op) && !"replace".equals(op)) {
            context.throwFatal(
                    "Unsupported Update Document patch operation: " + op);
            return null;
        }
        FrozenNode value = entry.val();
        if (value == null) {
            context.throwFatal("Update Document patch value is required for operation: " + op);
            return null;
        }
        if ("add".equals(op)) {
            return FrozenJsonPatch.add(absolutePath, value);
        }
        if ("replace".equals(op)) {
            return FrozenJsonPatch.replace(absolutePath, value);
        }
        throw new IllegalStateException("Unreachable Update Document patch operation");
    }

    private Node resolveReferencedLiteralValue(
            Node literal,
            UpdateDocument step,
            int index,
            StepExecutionContext context) {
        Node literalValue =
                literal != null
                        && literal.getProperties() != null
                        ? literal.getProperties()
                        .get("val")
                        : null;
        if (literalValue == null
                || !literalValue.isReferenceOnly()) {
            return literal;
        }
        FrozenNode resolved =
                resolvedStepValue(
                        step, index, context);
        Node completed = literal.clone();
        completed.getProperties().put(
                "val", resolved.toNode());
        return completed;
    }

    private void applyPatches(List<FrozenJsonPatch> patches, StepExecutionContext context) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        long applyStart = System.nanoTime();
        boolean applied = false;
        boolean previewTransferred = false;
        WorkingDocument.Preview preview = null;
        try {
            preview = context.advanceWorkingDocumentFrozen(patches);
            if (preview == null) {
                return;
            }
            context.processorContext().applyPreviewedFrozenPatches(patches, preview);
            previewTransferred = true;
            if (metrics != null) {
                metrics.addMetric("frozenPatchesHandedToLanguage", patches.size());
                metrics.addMetric("frozenPatchValuesHandedToLanguage", valuePatchCount(patches));
            }
            applied = true;
        } finally {
            if (!previewTransferred && preview != null) {
                preview.close();
            }
            if (metrics != null) {
                metrics.addUpdatePatchApplyNanos(System.nanoTime() - applyStart);
                if (applied) {
                    metrics.addPatchesApplied(patches.size());
                    metrics.incrementUpdateBatchPatchApplications();
                }
            }
        }
    }

    private static long valuePatchCount(List<FrozenJsonPatch> patches) {
        long count = 0L;
        for (FrozenJsonPatch patch : patches) {
            if (patch != null && patch.getOp() != blue.language.processor.model.JsonPatch.Op.REMOVE) {
                count++;
            }
        }
        return count;
    }

    private void applyStaticPlan(
            StaticUpdatePlan plan,
            UpdateDocument step,
            StepExecutionContext context) {
        if (plan.patches().isEmpty()) {
            return;
        }
        long conversionStart = System.nanoTime();
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>(plan.patches().size());
        for (int index = 0;
                index < plan.patches().size();
                index++) {
            StaticUpdatePlan.PatchTemplate template =
                    plan.patches().get(index);
            String absolutePath = context.processorContext().resolvePointer(template.authoredPath());
            patches.add(
                    template.bind(
                            absolutePath,
                            context,
                            resolvedPatchValue(
                                    template,
                                    step,
                                    index,
                                    context)));
        }
        if (metrics != null) {
            metrics.addUpdatePatchConversionNanos(System.nanoTime() - conversionStart);
        }
        applyPatches(patches, context);
    }

    private FrozenNode resolvedPatchValue(
            StaticUpdatePlan.PatchTemplate template,
            UpdateDocument step,
            int index,
            StepExecutionContext context) {
        if (!template.hasReferencedValue()) {
            return null;
        }
        return resolvedStepValue(
                step, index, context);
    }

    private FrozenNode resolvedStepValue(
            UpdateDocument step,
            int index,
            StepExecutionContext context) {
        List<Node> resolvedChangeset =
                step != null
                        ? step.getChangeset()
                        : null;
        Node resolvedEntry =
                resolvedChangeset != null
                        && index < resolvedChangeset.size()
                        ? resolvedChangeset.get(index)
                        : null;
        Node resolvedValue =
                resolvedEntry != null
                        && resolvedEntry.getProperties() != null
                        ? resolvedEntry.getProperties()
                        .get("val")
                        : null;
        if (resolvedValue == null
                || resolvedValue.isReferenceOnly()) {
            context.throwFatal(
                    "Update Document patch value reference has no "
                            + "resolved selected-body value");
            return null;
        }
        return FrozenNode.fromNode(
                CoordinationProcessHeaderSupport
                        .canonicalExactCopy(
                                resolvedValue));
    }
}
