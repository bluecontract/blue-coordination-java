package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
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
    private final List<FrozenJsonPatch> patches;
    private final List<FrozenNode> events;
    private final boolean terminationRequested;
    private final String terminationReason;
    private final boolean changesetHandled;
    private final AtomicBoolean bufferingClaimed = new AtomicBoolean();

    ComputeEffectPlan(List<FrozenJsonPatch> patches,
                      List<Node> events,
                      boolean terminationRequested,
                      String terminationReason,
                      boolean changesetHandled) {
        List<FrozenJsonPatch> frozenPatches = new ArrayList<FrozenJsonPatch>(patches.size());
        for (FrozenJsonPatch patch : patches) {
            if (patch == null) {
                throw new IllegalArgumentException("Compute effect plan patch must not be null");
            }
            // FrozenJsonPatch is immutable, so retaining the patch itself is safe. The
            // list still needs a defensive copy because callers may reuse its storage.
            frozenPatches.add(patch);
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

    List<FrozenJsonPatch> patches() {
        return patches;
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

}
