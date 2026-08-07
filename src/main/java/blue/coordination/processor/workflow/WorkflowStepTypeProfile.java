package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.TriggerEvent;
import blue.repo.coordination.UpdateDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable exact-BlueId dispatch profile for polymorphic workflow steps.
 *
 * <p>Language mapping may deliberately expose an unknown subtype as the
 * generated {@link SequentialWorkflowStep} base class. Coordination still
 * has the selected immutable frozen step, so a host that registered an exact
 * alternate type identity can bind it here without names, aliases, or field
 * shape heuristics.</p>
 */
public final class WorkflowStepTypeProfile {

    private final Map<String, Kind> kindsByBlueId;

    private WorkflowStepTypeProfile(Map<String, Kind> kindsByBlueId) {
        this.kindsByBlueId = Collections.unmodifiableMap(
                new LinkedHashMap<String, Kind>(kindsByBlueId));
    }

    /** Returns the generated Repository type identities. */
    public static WorkflowStepTypeProfile publishedDefaults() {
        return builder()
                .updateDocument(UpdateDocument.blueId())
                .triggerEvent(TriggerEvent.blueId())
                .compute(Compute.blueId())
                .terminateProcessing(TerminateProcessing.blueId())
                .build();
    }

    /** Starts an empty exact identity profile. */
    public static Builder builder() {
        return new Builder();
    }

    SequentialWorkflowStep materialize(
            SequentialWorkflowStep mapped,
            FrozenNode exactStep) {
        return materialize(
                mapped,
                exactStep,
                property(exactStep, "changeset"));
    }

    boolean requiresExactChangeset(
            SequentialWorkflowStep mapped,
            FrozenNode exactStep) {
        if (mapped == null
                || mapped.getClass() != SequentialWorkflowStep.class
                || exactStep == null) {
            return false;
        }
        return Kind.UPDATE_DOCUMENT.equals(
                kindsByBlueId.get(
                        exactTypeBlueId(exactStep)));
    }

    SequentialWorkflowStep materialize(
            SequentialWorkflowStep mapped,
            FrozenNode exactStep,
            FrozenNode exactChangeset) {
        if (mapped == null
                || mapped.getClass() != SequentialWorkflowStep.class
                || exactStep == null) {
            return mapped;
        }
        String typeBlueId = exactTypeBlueId(exactStep);
        Kind kind = kindsByBlueId.get(typeBlueId);
        if (kind == null) {
            return mapped;
        }
        switch (kind) {
            case UPDATE_DOCUMENT:
                UpdateDocument update = new UpdateDocument();
                if (exactChangeset != null
                        && exactChangeset.getItems() != null) {
                    update.changeset(nodes(exactChangeset.getItems()));
                }
                return update;
            case TRIGGER_EVENT:
                TriggerEvent trigger = new TriggerEvent();
                FrozenNode event = property(exactStep, "event");
                if (event != null) {
                    trigger.event(event.toNode());
                }
                return trigger;
            case COMPUTE:
                return new Compute();
            case TERMINATE_PROCESSING:
                return new TerminateProcessing();
            default:
                throw new IllegalStateException(
                        "Unknown workflow step kind " + kind);
        }
    }

    private static String exactTypeBlueId(FrozenNode step) {
        FrozenNode type = step.getType();
        if (type == null) {
            return null;
        }
        String reference = type.getReferenceBlueId();
        return reference != null ? reference : type.blueId();
    }

    private static FrozenNode property(FrozenNode node, String key) {
        return node == null || node.getProperties() == null
                ? null
                : node.getProperties().get(key);
    }

    private static List<Node> nodes(List<FrozenNode> source) {
        List<Node> result = new ArrayList<Node>(source.size());
        for (FrozenNode value : source) {
            result.add(value == null ? null : value.toNode());
        }
        return result;
    }

    private enum Kind {
        UPDATE_DOCUMENT,
        TRIGGER_EVENT,
        COMPUTE,
        TERMINATE_PROCESSING
    }

    /** Mutable construction scope for an immutable profile. */
    public static final class Builder {
        private final Map<String, Kind> kinds =
                new LinkedHashMap<String, Kind>();

        private Builder() {
        }

        public Builder updateDocument(String blueId) {
            return register(blueId, Kind.UPDATE_DOCUMENT);
        }

        public Builder triggerEvent(String blueId) {
            return register(blueId, Kind.TRIGGER_EVENT);
        }

        public Builder compute(String blueId) {
            return register(blueId, Kind.COMPUTE);
        }

        public Builder terminateProcessing(String blueId) {
            return register(blueId, Kind.TERMINATE_PROCESSING);
        }

        public WorkflowStepTypeProfile build() {
            return new WorkflowStepTypeProfile(kinds);
        }

        private Builder register(String blueId, Kind kind) {
            String identity = Objects.requireNonNull(blueId, "blueId");
            if (identity.isEmpty()) {
                throw new IllegalArgumentException(
                        "Workflow step BlueId must not be empty");
            }
            Kind previous = kinds.put(identity, kind);
            if (previous != null && previous != kind) {
                throw new IllegalArgumentException(
                        "One workflow step BlueId cannot select two kinds: "
                                + identity);
            }
            return this;
        }
    }
}
