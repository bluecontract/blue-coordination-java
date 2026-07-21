package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.MergeReverser;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.UpdateDocument;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                applyStaticPlan(staticPlan, context);
                return WorkflowStepResult.none();
            }
            FrozenNode rawFrozenChangeset = FrozenNodeUtil.property(context.stepFrozenNode(), "changeset");
            if (StaticPayloadValidator.rejectBexOperators(rawFrozenChangeset,
                    context,
                    "Update Document changeset")) {
                return WorkflowStepResult.none();
            }
            if (rawFrozenChangeset != null
                    && rawFrozenChangeset.getItems() == null
                    && step.getChangeset() == null) {
                context.processorContext().throwFatal("Update Document changeset must be a static patch list");
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
            MergeReverser mergeReverser = new MergeReverser();
            for (int i = 0; i < frozenChangeset.getItems().size(); i++) {
                FrozenNode item = frozenChangeset.getItems().get(i);
                Node literal = item == null ? null : item.toNode();
                if (item != null && !item.isStrictCanonical()) {
                    literal = mergeReverser.reverseToMinimizedOverlay(literal);
                }
                entries.add(literalPatchEntry(literal, i, context));
            }
            return entries;
        }
        if (step == null || step.getChangeset() == null) {
            return java.util.Collections.emptyList();
        }
        List<?> rawChangeset = step.getChangeset();
        List<WorkflowPatchEntry> entries = new ArrayList<WorkflowPatchEntry>(rawChangeset.size());
        for (int i = 0; i < rawChangeset.size(); i++) {
            entries.add(literalPatchEntry(rawChangeset.get(i), i, context));
        }
        return entries;
    }

    private WorkflowPatchEntry literalPatchEntry(Object item, int index, StepExecutionContext context) {
        if (item == null) {
            return null;
        }
        if (item instanceof WorkflowPatchEntry) {
            return (WorkflowPatchEntry) item;
        }
        if (item instanceof Node) {
            return literalPatchEntry((Node) item, index, context);
        }
        try {
            if (metrics != null) {
                metrics.incrementUpdateReflectionFallbacks();
            }
            String op = (String) invokeNoArg(item, "getOp");
            String path = (String) invokeNoArg(item, "getPath");
            if (isRemove(op)) {
                return new WorkflowPatchEntry(op, path, (FrozenNode) null);
            }
            Object val = invokeNoArg(item, "getVal");
            if (val == null || val instanceof Node) {
                return new WorkflowPatchEntry(op, path, (Node) val);
            }
            if (val instanceof FrozenNode) {
                return new WorkflowPatchEntry(op, path, (FrozenNode) val);
            }
            context.processorContext().throwFatal("Update Document changeset entry " + index
                    + " field 'val' must be a node");
            return null;
        } catch (ReflectiveOperationException ex) {
            context.processorContext().throwFatal("Update Document changeset entry " + index
                    + " cannot be read as a patch entry: " + ex.getMessage());
            return null;
        } catch (ClassCastException ex) {
            context.processorContext().throwFatal("Update Document changeset entry " + index
                    + " has invalid patch entry field types");
            return null;
        }
    }

    private WorkflowPatchEntry literalPatchEntry(Node item, int index, StepExecutionContext context) {
        if (item.getProperties() == null) {
            context.processorContext().throwFatal("Update Document changeset entry " + index
                    + " must be a static patch object");
            return null;
        }
        String op = stringProperty(item, "op", index, context);
        String path = stringProperty(item, "path", index, context);
        Node val = isRemove(op) ? null : item.getProperties().get("val");
        return new WorkflowPatchEntry(op, path, val);
    }

    private String stringProperty(Node item, String key, int index, StepExecutionContext context) {
        Node property = item.getProperties().get(key);
        Object value = property != null ? property.getValue() : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            context.processorContext().throwFatal("Update Document changeset entry " + index
                    + " field '" + key + "' must be text");
            return null;
        }
        return (String) value;
    }

    private Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private FrozenJsonPatch toPatch(WorkflowPatchEntry entry, StepExecutionContext context) {
        if (entry == null) {
            context.processorContext().throwFatal("Update Document changeset contains a null patch entry");
            return null;
        }
        String op = entry.op();
        String path = entry.path();
        if (op == null || op.trim().isEmpty()) {
            context.processorContext().throwFatal("Update Document patch operation is required");
            return null;
        }
        if (path == null || path.trim().isEmpty()) {
            context.processorContext().throwFatal("Update Document patch path is required");
            return null;
        }
        String absolutePath = context.processorContext().resolvePointer(path);
        String normalizedOp = op.trim().toLowerCase(Locale.ROOT);
        if ("remove".equals(normalizedOp)) {
            return FrozenJsonPatch.remove(absolutePath);
        }
        FrozenNode value = entry.val();
        if (value == null) {
            context.processorContext().throwFatal("Update Document patch value is required for operation: " + op);
            return null;
        }
        if ("add".equals(normalizedOp)) {
            return FrozenJsonPatch.add(absolutePath, value);
        }
        if ("replace".equals(normalizedOp)) {
            return FrozenJsonPatch.replace(absolutePath, value);
        }
        context.processorContext().throwFatal("Unsupported Update Document patch operation: " + op);
        return null;
    }

    private static boolean isRemove(String op) {
        return op != null && "remove".equals(op.trim().toLowerCase(Locale.ROOT));
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
            if (metrics != null) {
                metrics.addMetric("frozenPatchValuesHandedToLanguage", valuePatchCount(patches));
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

    private void applyStaticPlan(StaticUpdatePlan plan, StepExecutionContext context) {
        if (plan.patches().isEmpty()) {
            return;
        }
        long conversionStart = System.nanoTime();
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>(plan.patches().size());
        for (StaticUpdatePlan.PatchTemplate template : plan.patches()) {
            String absolutePath = context.processorContext().resolvePointer(template.authoredPath());
            patches.add(template.bind(absolutePath));
        }
        if (metrics != null) {
            metrics.addUpdatePatchConversionNanos(System.nanoTime() - conversionStart);
        }
        applyPatches(patches, context);
    }
}
