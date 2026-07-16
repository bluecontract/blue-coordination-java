package blue.coordination.processor.workflow;

import blue.bex.result.BexChangeset;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexNodeWriter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

final class ComputeResultEmitter {
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
            List<JsonPatch> patches;
            try {
                patches = changesetPatches(result, context);
            } catch (ComputeResultValidationException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw conversionFailure("changeset", ex);
            }
            List<Node> events = emitEvents
                    ? validatedEventNodes(result)
                    : Collections.<Node>emptyList();
            Termination termination = termination(result);
            return new ComputeEffectPlan(patches,
                    events,
                    termination.requested,
                    termination.reason,
                    returnedChangeset || !patches.isEmpty());
        } catch (ComputeResultValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
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
            throw conversionFailure("events", ex);
        }
    }

    void buffer(ComputeEffectPlan plan, StepExecutionContext context) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        plan.claimForBuffering();
        List<JsonPatch> patches = plan.patches();
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
            context.processorContext().terminateGracefully(plan.terminationReason());
            if (metrics != null) {
                metrics.incrementSuccessfulComputeTerminationRequests();
            }
        }
    }

    private boolean hasReturnedChangeset(BexExecutionResult result) {
        BexValue changeset = result.value() != null ? result.value().get("changeset") : BexValues.undefined();
        return !changeset.isUndefined() && !changeset.isNull();
    }

    private List<Node> eventNodes(BexExecutionResult result) {
        BexValue events = result.value() != null ? result.value().get("events") : BexValues.undefined();
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
            if (!event.isObject()) {
                throw invalid("Compute result events must contain object entries");
            }
            try {
                converted.add(BexNodeWriter.toNode(event));
            } catch (RuntimeException ex) {
                throw new ComputeResultValidationException(
                        "Compute result event entry could not be converted", ex);
            }
        }
        return converted;
    }

    private Termination termination(BexExecutionResult result) {
        BexValue termination = result.value() != null
                ? result.value().get("termination")
                : BexValues.undefined();
        if (termination == null || termination.isUndefined() || termination.isNull()) {
            return Termination.absent();
        }
        if (!termination.isObject()) {
            throw invalid("Compute result termination must be an object");
        }
        for (String key : termination.keys()) {
            if (!"reason".equals(key)) {
                throw invalid("Compute result termination contains unsupported properties");
            }
        }
        BexValue reason = termination.get("reason");
        if (reason == null || reason.isUndefined() || reason.isNull()) {
            return Termination.requested(null);
        }
        if (!"text".equals(BexValues.kind(reason))) {
            throw invalid("Compute result termination reason must be Text");
        }
        return Termination.requested(reason.asText());
    }

    private List<JsonPatch> changesetPatches(BexExecutionResult result, StepExecutionContext context) {
        BexValue changeset = result.value() != null ? result.value().get("changeset") : BexValues.undefined();
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
        List<JsonPatch> patches = new ArrayList<JsonPatch>(changeset.size());
        for (int i = 0; i < changeset.size(); i++) {
            WorkflowPatchEntry entry = patchEntry(changeset.get(String.valueOf(i)), i);
            patches.add(toPatch(entry, context));
        }
        return patches;
    }

    private List<JsonPatch> patchesFromBexChangeset(BexChangeset changeset, StepExecutionContext context) {
        if (changeset == null || changeset.entries().isEmpty()) {
            return Collections.emptyList();
        }
        if (metrics != null) {
            metrics.incrementDirectBexChangesetHits();
        }
        long conversionStart = System.nanoTime();
        try {
            List<JsonPatch> patches = new ArrayList<JsonPatch>(changeset.entries().size());
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
        String op = textValue(item.get("op"));
        String path = textValue(item.get("path"));
        if (!"add".equals(op) && !"replace".equals(op) && !"remove".equals(op)) {
            throw invalid("Invalid patch op in Compute result changeset");
        }
        if (path == null || path.trim().isEmpty()) {
            throw invalid("Compute result changeset entry " + index + " missing path");
        }
        Node nodeValue = null;
        if (!"remove".equals(op)) {
            BexValue val = item.get("val");
            if (val.isUndefined()) {
                throw invalid("Compute result changeset entry " + index + " missing val");
            }
            long writerStart = System.nanoTime();
            try {
                nodeValue = BexNodeWriter.toNode(val);
            } finally {
                if (metrics != null) {
                    metrics.addBexNodeWriterNanos(System.nanoTime() - writerStart);
                }
            }
        }
        return new WorkflowPatchEntry(op, path, nodeValue);
    }

    private JsonPatch toPatch(WorkflowPatchEntry entry, StepExecutionContext context) {
        String normalizedOp = entry.op().trim().toLowerCase();
        String path = resolvedPointer(entry.path(), context);
        if ("remove".equals(normalizedOp)) {
            return JsonPatch.remove(path);
        }
        if ("add".equals(normalizedOp)) {
            return JsonPatch.add(path, entry.val());
        }
        // patchEntry has already restricted this branch to replace.
        return JsonPatch.replace(path, entry.val());
    }

    private JsonPatch toPatch(BexPatchEntry entry, StepExecutionContext context) {
        if (entry == null) {
            throw invalid("Compute result accumulated patch is incomplete");
        }
        String normalizedOp = entry.op().trim().toLowerCase();
        boolean remove = "remove".equals(normalizedOp);
        if (!remove && (entry.val() == null || entry.val().isUndefined())) {
            throw invalid("Compute result patch value is required");
        }
        String path = resolvedPointer(entry.authoredPath(), context);
        if (remove) {
            return JsonPatch.remove(path);
        }
        long writerStart = System.nanoTime();
        Node value;
        try {
            value = BexNodeWriter.toNode(entry.val());
        } finally {
            if (metrics != null) {
                metrics.addBexNodeWriterNanos(System.nanoTime() - writerStart);
            }
        }
        if ("add".equals(normalizedOp)) {
            return JsonPatch.add(path, value);
        }
        // BexPatchEntry has already restricted this branch to replace.
        return JsonPatch.replace(path, value);
    }

    private String resolvedPointer(String authoredPath, StepExecutionContext context) {
        try {
            return context.processorContext().resolvePointer(authoredPath);
        } catch (RuntimeException ex) {
            throw new ComputeResultValidationException("Compute result patch path is invalid", ex);
        }
    }

    private void applyPatches(List<JsonPatch> patches, StepExecutionContext context) {
        long applyStart = System.nanoTime();
        boolean applied = false;
        try {
            WorkingDocument.Preview preview = context.advanceWorkingDocument(patches);
            context.processorContext().applyPreviewedPatches(patches, preview);
            applied = true;
        } finally {
            if (metrics != null) {
                metrics.addUpdatePatchApplyNanos(System.nanoTime() - applyStart);
                if (applied) {
                    metrics.addPatchesApplied(patches.size());
                    metrics.incrementUpdateBatchPatchApplications();
                }
            }
        }
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
            if (!entry.op().equals(textValue(item.get("op")))) {
                return false;
            }
            String path = textValue(item.get("path"));
            if (!entry.authoredPath().equals(path) && !entry.absolutePath().equals(path)) {
                return false;
            }
            BexValue val = item.get("val");
            if (entry.val() == null || entry.val().isUndefined()) {
                if (val != null && !val.isUndefined() && !val.isNull()) {
                    return false;
                }
            } else if (val == null || val.isUndefined() || !Objects.equals(entry.val().toSimple(), val.toSimple())) {
                return false;
            }
        }
        return true;
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
        private static final Termination ABSENT = new Termination(false, null);

        private final boolean requested;
        private final String reason;

        private Termination(boolean requested, String reason) {
            this.requested = requested;
            this.reason = reason;
        }

        private static Termination absent() {
            return ABSENT;
        }

        private static Termination requested(String reason) {
            return new Termination(true, reason);
        }
    }
}
