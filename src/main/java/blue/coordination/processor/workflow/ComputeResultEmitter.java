package blue.coordination.processor.workflow;

import blue.bex.result.BexChangeset;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.contracts.ProcessorExactBlueValueCapability;
import blue.bex.output.BexExactValueCapability;
import blue.bex.value.BexBlueNodeWriter;
import blue.bex.value.BexFrozenWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.processor.WorkingDocument;
import blue.language.processor.ExactBlueValue;
import blue.language.processor.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Validates hosted-BEX output and translates it into processor-owned effects.
 *
 * <p>{@link #plan(BexExecutionResult, StepExecutionContext, boolean)} performs
 * every structural conversion before the document is mutated. Only the
 * resulting immutable {@link ComputeEffectPlan} may cross into
 * {@link #buffer(ComputeEffectPlan, StepExecutionContext)}, where its one-shot
 * claim prevents duplicate patch, event, or termination delivery.</p>
 */
final class ComputeResultEmitter {
    private static final String CHANGESET_FIELD = "changeset";
    private static final String EVENTS_FIELD = "events";
    private static final String TERMINATION_FIELD = "termination";
    private static final String CAUSE_FIELD = "cause";
    private static final String REASON_FIELD = "reason";
    private static final String PATCH_OPERATION_FIELD = "op";
    private static final String PATCH_PATH_FIELD = "path";
    private static final String PATCH_VALUE_FIELD = "val";
    private static final String TEXT_KIND = "text";
    private static final String ADD_OPERATION = "add";
    private static final String REPLACE_OPERATION = "replace";
    private static final String REMOVE_OPERATION = "remove";

    private final BexProcessingMetrics metrics;

    ComputeResultEmitter() {
        this(null);
    }

    ComputeResultEmitter(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    ComputeEffectPlan plan(BexExecutionResult result,
                           StepExecutionContext context,
                           boolean emitEvents) {
        if (result == null) {
            throw invalid("Compute execution result is required");
        }
        try {
            boolean returnedChangeset = hasReturnedChangeset(result);
            List<FrozenJsonPatch> patches;
            try {
                patches = changesetPatches(result, context);
            } catch (ComputeResultValidationException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw conversionFailure(CHANGESET_FIELD, ex);
            }
            List<Node> events = emitEvents
                    ? validatedEventNodes(result)
                    : Collections.<Node>emptyList();
            Termination termination = termination(result);
            return new ComputeEffectPlan(patches,
                    events,
                    termination.requested,
                    termination.cause,
                    termination.reason,
                    returnedChangeset || !patches.isEmpty());
        } catch (RuntimeException ex) {
            RuntimeException classified =
                    ComputeStepExecutor.classifiedBoundaryFailure(ex);
            if (classified != null) {
                throw classified;
            }
            if (ex instanceof ComputeResultValidationException) {
                throw ex;
            }
            throw new ComputeResultValidationException(
                    "Compute result effects could not be converted: " + boundedDetail(ex), ex);
        }
    }

    private List<Node> validatedEventNodes(BexExecutionResult result) {
        try {
            return eventNodes(result);
        } catch (ComputeResultValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw conversionFailure(EVENTS_FIELD, ex);
        }
    }

    void buffer(ComputeEffectPlan plan, StepExecutionContext context) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        plan.claimForBuffering();
        List<FrozenJsonPatch> patches = plan.patches();
        if (!patches.isEmpty()) {
            applyPatches(patches, context);
        }
        for (FrozenNode event : plan.events()) {
            context.processorContext().emitEvent(event.toNode());
            if (metrics != null) {
                metrics.incrementEventsEmitted();
            }
        }
        if (plan.terminationRequested()) {
            context.processorContext().terminate(
                    plan.terminationCause(),
                    plan.terminationReason());
            if (metrics != null) {
                metrics.incrementSuccessfulComputeTerminationRequests();
            }
        }
    }

    private boolean hasReturnedChangeset(BexExecutionResult result) {
        BexValue value = executionValue(result);
        BexValue changeset = value != null
                ? value.get(CHANGESET_FIELD)
                : BexValues.undefined();
        return !changeset.isUndefined() && !changeset.isNull();
    }

    private List<Node> eventNodes(BexExecutionResult result) {
        BexValue value = executionValue(result);
        BexValue events = value != null
                ? value.get(EVENTS_FIELD)
                : BexValues.undefined();
        if (events.isUndefined() || events.isNull()) {
            events = result.events().asValue();
        }
        if (events.isUndefined() || events.isNull() || (events.isList() && events.size() == 0)) {
            return Collections.emptyList();
        }
        if (!events.isList()) {
            throw invalid("Compute result events must be a list");
        }
        List<Node> converted = new ArrayList<Node>(events.size());
        for (int i = 0; i < events.size(); i++) {
            BexValue event = events.get(String.valueOf(i));
            if (event == null || event.isUndefined() || event.isNull()) {
                throw invalid("Compute result events cannot contain undefined/null entries");
            }
            try {
                converted.add(semanticOutputNode(event));
            } catch (RuntimeException ex) {
                throw new ComputeResultValidationException(
                        "Compute result event entry could not be converted",
                        ex);
            }
        }
        return converted;
    }

    private Termination termination(BexExecutionResult result) {
        BexValue value = executionValue(result);
        BexValue termination = value != null
                ? value.get(TERMINATION_FIELD)
                : BexValues.undefined();
        if (termination == null || termination.isUndefined() || termination.isNull()) {
            return Termination.absent();
        }
        if (!termination.isObject()) {
            throw invalid("Compute result termination must be an object");
        }
        for (String key : termination.keys()) {
            if (!CAUSE_FIELD.equals(key)
                    && !REASON_FIELD.equals(key)) {
                throw invalid("Compute result termination contains unsupported properties");
            }
        }
        BexValue cause = termination.get(CAUSE_FIELD);
        if (cause == null || cause.isUndefined() || cause.isNull()
                || !TEXT_KIND.equals(BexValues.kind(cause))
                || cause.asText().isEmpty()) {
            throw invalid(
                    "Compute result termination cause must be non-empty Text");
        }
        BexValue reason = termination.get(REASON_FIELD);
        if (reason == null || reason.isUndefined() || reason.isNull()) {
            return Termination.requested(cause.asText(), null);
        }
        if (!TEXT_KIND.equals(BexValues.kind(reason))) {
            throw invalid("Compute result termination reason must be Text");
        }
        return Termination.requested(cause.asText(), reason.asText());
    }

    private List<FrozenJsonPatch> changesetPatches(BexExecutionResult result,
                                                   StepExecutionContext context) {
        BexValue value = executionValue(result);
        BexValue changeset = value != null
                ? value.get(CHANGESET_FIELD)
                : BexValues.undefined();
        BexChangeset accumulated = result.changeset();
        if (changeset.isUndefined() || changeset.isNull()) {
            return patchesFromBexChangeset(accumulated, context);
        }
        if (!changeset.isList()) {
            throw invalid("Compute result changeset must be a list");
        }
        if (changeset.size() == 0) {
            return Collections.emptyList();
        }
        if (isAccumulatedChangesetValue(changeset, accumulated)) {
            return patchesFromBexChangeset(accumulated, context);
        }
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>(changeset.size());
        for (int i = 0; i < changeset.size(); i++) {
            WorkflowPatchEntry entry = patchEntry(changeset.get(String.valueOf(i)), i);
            patches.add(toPatch(entry, context));
        }
        return patches;
    }

    private List<FrozenJsonPatch> patchesFromBexChangeset(BexChangeset changeset,
                                                          StepExecutionContext context) {
        if (changeset == null || changeset.entries().isEmpty()) {
            return Collections.emptyList();
        }
        if (metrics != null) {
            metrics.incrementDirectBexChangesetHits();
        }
        long conversionStart = System.nanoTime();
        try {
            List<FrozenJsonPatch> patches =
                    new ArrayList<FrozenJsonPatch>(changeset.entries().size());
            for (BexPatchEntry entry : changeset.entries()) {
                patches.add(toPatch(entry, context));
                if (metrics != null) {
                    metrics.incrementDirectBexPatchEntryConversions();
                }
            }
            return patches;
        } finally {
            if (metrics != null) {
                metrics.addUpdatePatchConversionNanos(System.nanoTime() - conversionStart);
            }
        }
    }

    private WorkflowPatchEntry patchEntry(BexValue item, int index) {
        if (item == null || item.isUndefined() || item.isNull() || !item.isObject()) {
            throw invalid("Compute result changeset entry " + index + " must be an object");
        }
        String op = patchTextValue(
                item.get(PATCH_OPERATION_FIELD),
                index,
                PATCH_OPERATION_FIELD);
        String path = patchTextValue(
                item.get(PATCH_PATH_FIELD),
                index,
                PATCH_PATH_FIELD);
        if (!ADD_OPERATION.equals(op)
                && !REPLACE_OPERATION.equals(op)
                && !REMOVE_OPERATION.equals(op)) {
            throw invalid("Invalid patch op in Compute result changeset");
        }
        if (path == null || path.trim().isEmpty()) {
            throw invalid("Compute result changeset entry " + index + " missing path");
        }
        FrozenNode nodeValue = null;
        BexValue val = item.get(PATCH_VALUE_FIELD);
        if (REMOVE_OPERATION.equals(op)) {
            if (item.keys().contains(PATCH_VALUE_FIELD)) {
                throw invalid("Compute result changeset entry " + index
                        + " val must be absent for remove");
            }
        } else {
            if (val == null || val.isUndefined()) {
                throw invalid("Compute result changeset entry " + index + " missing val");
            }
            nodeValue = freezePatchValue(val);
        }
        return new WorkflowPatchEntry(op, path, nodeValue);
    }

    private String patchTextValue(BexValue value,
                                  int index,
                                  String field) {
        if (value == null || value.isUndefined() || value.isNull()) {
            return null;
        }
        if (!TEXT_KIND.equals(BexValues.kind(value))) {
            throw invalid("Compute result changeset entry " + index
                    + " field '" + field + "' must be Text");
        }
        return value.asText();
    }

    private FrozenJsonPatch toPatch(WorkflowPatchEntry entry,
                                    StepExecutionContext context) {
        String path = resolvedPointer(entry.path(), context);
        if (REMOVE_OPERATION.equals(entry.op())) {
            return FrozenJsonPatch.remove(path);
        }
        if (ADD_OPERATION.equals(entry.op())) {
            return FrozenJsonPatch.add(path, entry.val());
        }
        // patchEntry has already restricted this branch to replace.
        return FrozenJsonPatch.replace(path, entry.val());
    }

    private FrozenJsonPatch toPatch(BexPatchEntry entry,
                                    StepExecutionContext context) {
        if (entry == null) {
            throw invalid("Compute result accumulated patch is incomplete");
        }
        String op = entry.op();
        boolean remove = REMOVE_OPERATION.equals(op);
        if (!remove
                && !ADD_OPERATION.equals(op)
                && !REPLACE_OPERATION.equals(op)) {
            throw invalid("Invalid accumulated patch op in Compute result");
        }
        if (remove && entry.val() != null && !entry.val().isUndefined()) {
            throw invalid("Compute result accumulated remove patch val must be absent");
        }
        if (!remove && (entry.val() == null || entry.val().isUndefined())) {
            throw invalid("Compute result patch value is required");
        }
        String path = resolvedPointer(entry.authoredPath(), context);
        if (remove) {
            return FrozenJsonPatch.remove(path);
        }
        ExactBlueValue exactValue = admittedExactPatchValue(
                entry, context);
        if (ADD_OPERATION.equals(op)) {
            return exactValue != null
                    ? FrozenJsonPatch.add(path, exactValue)
                    : FrozenJsonPatch.add(
                            path, freezePatchValue(entry.val()));
        }
        // BexPatchEntry has already restricted this branch to replace.
        return exactValue != null
                ? FrozenJsonPatch.replace(path, exactValue)
                : FrozenJsonPatch.replace(
                        path, freezePatchValue(entry.val()));
    }

    private ExactBlueValue admitExactPatchValue(
            BexValue value,
            StepExecutionContext context) {
        String exactBlueId = BexValues.frozenBlueId(value);
        if (exactBlueId == null) {
            return null;
        }
        FrozenNode retained = freezePatchValue(value);
        ExactBlueValue admitted = context.processorContext()
                .semanticOutputBoundary()
                .admit(retained);
        if (!exactBlueId.equals(admitted.blueId())) {
            throw invalid(
                    "Compute result exact patch capability changed identity: "
                            + "expected " + exactBlueId + " but found "
                            + admitted.blueId());
        }
        return admitted;
    }

    private ExactBlueValue admittedExactPatchValue(
            BexPatchEntry entry,
            StepExecutionContext context) {
        if (entry.admittedValue() != null) {
            BexExactValueCapability capability =
                    entry.admittedValue().exactCapability();
            if (capability instanceof ProcessorExactBlueValueCapability) {
                ExactBlueValue carried = context.processorContext()
                        .semanticOutputBoundary()
                        .carryExactCapability(
                                ((ProcessorExactBlueValueCapability) capability)
                                .exactValue());
                String exactBlueId = BexValues.frozenBlueId(entry.val());
                if (exactBlueId == null
                        || !exactBlueId.equals(carried.blueId())) {
                    throw invalid(
                            "Compute result exact patch capability does not "
                                    + "match its admitted BEX value");
                }
                return carried;
            }
        }
        return admitExactPatchValue(entry.val(), context);
    }

    private String resolvedPointer(String authoredPath, StepExecutionContext context) {
        try {
            return context.processorContext().resolvePointer(authoredPath);
        } catch (RuntimeException ex) {
            throw new ComputeResultValidationException("Compute result patch path is invalid", ex);
        }
    }

    private void applyPatches(List<FrozenJsonPatch> patches,
                              StepExecutionContext context) {
        long applyStart = System.nanoTime();
        boolean applied = false;
        boolean previewTransferred = false;
        WorkingDocument.Preview preview = null;
        long frozenValueCount = frozenValueCount(patches);
        try {
            /*
             * Keep provider/evidence exceptions visible to the Compute
             * executor. StepExecutionContext's convenience wrapper maps every
             * RuntimeException to runtime-fatal, which would erase Language's
             * deterministic InvalidExecutionEvidence category.
             */
            preview = context.workingDocument()
                    .previewAndApplyFrozenPatches(patches);
            if (preview == null) {
                return;
            }
            context.processorContext().applyPreviewedFrozenPatches(patches, preview);
            previewTransferred = true;
            if (metrics != null) {
                metrics.addMetric("frozenPatchesHandedToLanguage", patches.size());
                metrics.addMetric("frozenPatchValuesHandedToLanguage", frozenValueCount);
            }
            applied = true;
        } catch (RuntimeException ex) {
            RuntimeException classified =
                    ComputeStepExecutor.classifiedBoundaryFailure(ex);
            if (classified != null) {
                throw classified;
            }
            context.throwFatal(
                    "Working document preview failed: "
                            + boundedDetail(ex));
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

    private long frozenValueCount(List<FrozenJsonPatch> patches) {
        long count = 0L;
        for (FrozenJsonPatch patch : patches) {
            if (patch.getValue() != null) {
                count++;
            }
        }
        return count;
    }

    private boolean isAccumulatedChangesetValue(BexValue value, BexChangeset changeset) {
        if (value == null || !value.isList() || changeset == null) {
            return false;
        }
        if (value.size() != changeset.entries().size()) {
            return false;
        }
        for (int i = 0; i < value.size(); i++) {
            BexValue item = value.get(String.valueOf(i));
            BexPatchEntry entry = changeset.entries().get(i);
            if (item == null || !item.isObject()) {
                return false;
            }
            if (!entry.op().equals(
                    textValue(item.get(PATCH_OPERATION_FIELD)))) {
                return false;
            }
            String path = textValue(
                    item.get(PATCH_PATH_FIELD));
            if (!entry.authoredPath().equals(path) && !entry.absolutePath().equals(path)) {
                return false;
            }
            BexValue val = item.get(PATCH_VALUE_FIELD);
            if (entry.val() == null || entry.val().isUndefined()) {
                if (item.keys().contains(PATCH_VALUE_FIELD)) {
                    return false;
                }
            } else if (val == null || val.isUndefined()) {
                return false;
            } else if (!sameAdmittedValue(
                    entry.val(),
                    val)) {
                /*
                 * BEX's accumulated changeset view normally preserves the
                 * exact value object held by each entry. Root output admission
                 * can replace that cursor with another exact value of the
                 * same identity. Both lanes are constant-time; never perform
                 * an unmetered semantic traversal merely to select this fast
                 * path.
                 */
                return false;
            }
        }
        return true;
    }

    private boolean sameAdmittedValue(BexValue left,
                                      BexValue right) {
        if (left == right) {
            return true;
        }
        return left != null
                && right != null
                && left.isExact()
                && right.isExact()
                && left.exactBlueId().equals(
                right.exactBlueId());
    }

    private BexValue executionValue(
            BexExecutionResult result) {
        return result != null
                ? result.value()
                : null;
    }

    FrozenNode freezePatchValue(BexValue value) {
        // BEX exposes the exact FrozenNode only through BexFrozenWriter. Avoid
        // invoking that writer for ordinary values because its general fallback
        // performs Node round trips. A non-null frozen BlueId identifies the
        // zero-materialization FrozenNode-backed lane.
        String exactBlueId = BexValues.frozenBlueId(value);
        if (exactBlueId != null) {
            FrozenNode frozen = BexFrozenWriter.toFrozen(value);
            /*
             * A strict canonical reference returned by the frozen writer is
             * already an exact, provider-verifiable patch value. Preserve it
             * directly: resolving or rehashing that value in Coordination
             * would defeat the released zero-materialization handoff.
             *
             * AdmittedExactBexValue is not FrozenNode-backed, so the writer's
             * general fallback produces a pure reference even when admitted
             * semantic content remains available. The branch below
             * distinguishes that case from a genuinely opaque reference.
             */
            if (frozen.isStrictCanonical()
                    && !frozen.isReferenceOnly()) {
                if (!exactBlueId.equals(frozen.blueId())) {
                    throw invalid(
                            "Compute result exact patch value has mismatched "
                                    + "authenticated content: expected "
                                    + exactBlueId + " but found "
                                    + frozen.blueId());
                }
                if (metrics != null) {
                    metrics.incrementBexPatchFrozenDirectConversions();
                }
                return frozen;
            }
            if (frozen.isReferenceOnly()) {
                /*
                 * Preserve the exact reference authenticated by the host.
                 * Reconstructing its resolved semantic cursor here can
                 * generalize nominal type/schema fields and silently change
                 * the established identity. Language remains responsible for
                 * opening the exact reference if a later patch demands a
                 * descendant.
                 */
                if (!exactBlueId.equals(frozen.getReferenceBlueId())) {
                    throw invalid(
                            "Compute result exact patch reference changed "
                                    + "identity: expected " + exactBlueId
                                    + " but found "
                                    + frozen.getReferenceBlueId());
                }
                if (metrics != null) {
                    metrics.incrementBexPatchFrozenDirectConversions();
                }
                return frozen;
            }
            /*
             * A resolved/non-canonical cursor is evidence for semantic reads,
             * not a second authored representation. Retain the authenticated
             * identity as a strict exact reference instead of normalizing and
             * hashing that cursor again.
             */
            if (metrics != null) {
                metrics.incrementBexPatchFrozenDirectConversions();
            }
            return FrozenNode.fromNode(new Node().blueId(exactBlueId));
        }
        return materializePatchValue(value);
    }

    private FrozenNode materializePatchValue(BexValue value) {
        long writerStart = System.nanoTime();
        try {
            /*
             * This is the required boundary for newly computed values. Use
             * BEX's Blue-aware semantic writer so exact descendants of a
             * transient aggregate retain their available content. The generic
             * writer intentionally emits such descendants as transport
             * references, which would make values read from
             * $event/$processingEvent opaque inside a newly authored patch.
             */
            return FrozenNode.fromNode(
                    semanticOutputNode(value));
        } finally {
            recordPatchValueMaterialization(writerStart);
        }
    }

    private void recordPatchValueMaterialization(long writerStart) {
        if (metrics != null) {
            metrics.addBexNodeWriterNanos(System.nanoTime() - writerStart);
            metrics.incrementBexPatchNodeMaterializations();
        }
    }

    /**
     * Materializes the semantic cursor retained by an admitted BEX value.
     *
     * <p>Local BEX deliberately exposes an admitted exact root as its compact
     * canonical transport node. Its cursor still retains the transient
     * semantic children that were proved by Language at admission. Rebuilding
     * a transient cursor from the simple semantic view lets the Blue-aware
     * writer include those children in the processor effect; otherwise a
     * newly computed aggregate would escape with invocation-local child
     * references that no later provider can open.</p>
     */
    private Node semanticOutputNode(BexValue value) {
        /*
         * BEX's semantic writer deliberately inlines locally available exact
         * descendants. Those resolved views may carry provider BlueIds beside
         * their fields, which is valid evidence internally but is not valid
         * authored input to Language's hosted output boundary. Strip that
         * provenance once, after rebuilding the complete semantic value.
         */
        return CoordinationProcessHeaderSupport
                .canonicalExactCopy(
                        semanticOutputView(value));
    }

    private Node semanticOutputView(BexValue value) {
        if (value == null || !value.isExact()) {
            return BexBlueNodeWriter.toSemanticNode(value);
        }
        try {
            Node semantic = value.toNode();
            if (semantic.getItems() != null && value.isList()) {
                List<Node> items =
                        new ArrayList<Node>(semantic.getItems().size());
                for (int index = 0;
                     index < semantic.getItems().size();
                     index++) {
                    items.add(
                            semanticOutputView(
                                    value.get(
                                            String.valueOf(index))));
                }
                semantic.items(items);
            }
            if (semantic.getProperties() != null
                    && value.isObject()) {
                for (String key :
                        new ArrayList<String>(
                                semantic.getProperties().keySet())) {
                    BexValue child = value.get(key);
                    if (child != null && !child.isUndefined()) {
                        semantic.getProperties().put(
                                key,
                                semanticOutputView(child));
                    }
                }
            }
            if (semantic.getContracts() != null
                    && value.isObject()) {
                BexValue contracts = value.get("contracts");
                if (contracts != null
                        && !contracts.isUndefined()) {
                    semantic.contracts(
                            semanticOutputView(contracts));
                }
            }
            return semantic;
        } catch (RuntimeException ex) {
            if (isUnavailableExactReference(value, ex)) {
                return new Node().blueId(
                        value.exactBlueId());
            }
            throw ex;
        }
    }

    private boolean isUnavailableExactReference(
            BexValue value,
            RuntimeException failure) {
        if (value == null || !value.isExact()) {
            return false;
        }
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.contains(
                    "Semantic content is unavailable for exact Blue reference")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String textValue(BexValue value) {
        if (value == null || value.isUndefined() || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private ComputeResultValidationException invalid(String message) {
        return new ComputeResultValidationException(message);
    }

    private ComputeResultValidationException conversionFailure(String field, RuntimeException exception) {
        return new ComputeResultValidationException(
                "Compute result " + field + " could not be converted: " + boundedDetail(exception), exception);
    }

    private String boundedDetail(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return exception.getClass().getSimpleName();
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private static final class Termination {
        private static final Termination ABSENT =
                new Termination(false, null, null);

        private final boolean requested;
        private final String cause;
        private final String reason;

        private Termination(boolean requested,
                            String cause,
                            String reason) {
            this.requested = requested;
            this.cause = cause;
            this.reason = reason;
        }

        private static Termination absent() {
            return ABSENT;
        }

        private static Termination requested(String cause,
                                             String reason) {
            return new Termination(true, cause, reason);
        }
    }
}
