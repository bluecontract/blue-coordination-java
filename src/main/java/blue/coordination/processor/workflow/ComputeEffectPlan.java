package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fully validated effects produced by one Compute execution.
 *
 * <p>The effect content is immutable. The one-shot claim prevents accidental
 * duplicate buffering when a caller retries delivery of the same plan.</p>
 */
final class ComputeEffectPlan {
    private final List<PlannedPatch> patches;
    private final List<FrozenNode> events;
    private final boolean terminationRequested;
    private final String terminationReason;
    private final boolean changesetHandled;
    private final AtomicBoolean bufferingClaimed = new AtomicBoolean();

    ComputeEffectPlan(List<JsonPatch> patches,
                      List<Node> events,
                      boolean terminationRequested,
                      String terminationReason,
                      boolean changesetHandled) {
        List<PlannedPatch> frozenPatches = new ArrayList<PlannedPatch>(patches.size());
        for (JsonPatch patch : patches) {
            frozenPatches.add(PlannedPatch.from(patch));
        }
        this.patches = Collections.unmodifiableList(frozenPatches);
        List<FrozenNode> frozenEvents = new ArrayList<FrozenNode>(events.size());
        for (Node event : events) {
            // Repository-backed BEX values may already be resolved and therefore carry
            // expanded type nodes. Preserve that valid resolved shape while taking an
            // immutable snapshot of the event planned for later buffering.
            frozenEvents.add(FrozenNode.fromResolvedNode(event));
        }
        this.events = Collections.unmodifiableList(frozenEvents);
        this.terminationRequested = terminationRequested;
        this.terminationReason = terminationReason;
        this.changesetHandled = changesetHandled;
    }

    List<JsonPatch> patches() {
        List<JsonPatch> materialized = new ArrayList<JsonPatch>(patches.size());
        for (PlannedPatch patch : patches) {
            materialized.add(patch.materialize());
        }
        return Collections.unmodifiableList(materialized);
    }

    List<FrozenNode> events() {
        return events;
    }

    boolean terminationRequested() {
        return terminationRequested;
    }

    String terminationReason() {
        return terminationReason;
    }

    boolean changesetHandled() {
        return changesetHandled;
    }

    void claimForBuffering() {
        if (!bufferingClaimed.compareAndSet(false, true)) {
            throw new IllegalStateException("Compute effect plan has already been buffered");
        }
    }

    private static final class PlannedPatch {
        private final JsonPatch.Op op;
        private final String path;
        private final FrozenNode value;

        private PlannedPatch(JsonPatch.Op op, String path, FrozenNode value) {
            this.op = op;
            this.path = path;
            this.value = value;
        }

        private static PlannedPatch from(JsonPatch patch) {
            if (patch == null) {
                throw new IllegalArgumentException("Compute effect plan patch must not be null");
            }
            FrozenNode value = patch.getOp() == JsonPatch.Op.REMOVE
                    ? null
                    : FrozenNode.fromResolvedNode(patch.getVal());
            return new PlannedPatch(patch.getOp(), patch.getPath(), value);
        }

        private JsonPatch materialize() {
            if (op == JsonPatch.Op.ADD) {
                return JsonPatch.add(path, value.toNode());
            }
            if (op == JsonPatch.Op.REPLACE) {
                return JsonPatch.replace(path, value.toNode());
            }
            return JsonPatch.remove(path);
        }
    }
}
