package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.model.NodeWireForm;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only diagnostics of real pending join work and selected-versus-current owner states. */
public final class RootedJoinPrerequisiteProbe {
    private final DefaultCoordinationEngine engine;

    /** Uses the actual SDK engine without changing capture, execution, or publication. */
    public RootedJoinPrerequisiteProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Captures no replacement inputs and performs no PROCESS calls or publications. */
    public List<String> describe(List<DocumentId> roots) {
        var lines = new ArrayList<String>();
        var documents = engine.documents();
        var adapter = engine.contractsClosureAdapter();
        var entries = engine.auditTimelineEntries();
        for (var fence : RootedJoinEligibility.capture(documents)) {
            var terminal = fence.terminal();
            lines.add("fence boundary=" + fence.boundary() + " cause=" + fence.causeIdentity()
                    + " affected=" + fence.owners() + " receivers=" + fence.receivers()
                    + " occurrence=" + fence.occurrence().occurrenceIdentity()
                    + " cursor=" + fence.occurrence().pendingHistoricalEpoch()
                    + " terminal=" + (terminal == null ? "none" : terminal.work().workIdentity()
                            + " source=" + terminal.work().sourceEpoch() + " interior=" + terminal.interiorOwners()));
        }
        for (var root : roots) {
            var session = documents.require(root);
            var view = session.rootedView();
            var selected = new RootedCheckpointDriver(documents, adapter).select(root, entries);
            var local = adapter.nextRootLocalHistory(root, entries);
            var live = adapter.nextRootLiveInput(root, entries);
            lines.add("root=" + root + " current=" + session.epoch() + ":" + session.currentRepresentation().blueId()
                    + " boundary=" + view.logicalBoundary() + " blocked=" + selected.blocked()
                    + " selectedWork=" + (selected.historical() == null ? "none" : selected.historical().workIdentity())
                    + " live=" + live.map(batch -> batch.entry().sourceOrderKey().toString()).orElse("none")
                    + " localPending=" + local.pending());
            for (var plan : documents.catchUpPlans(root)) lines.add("plan=" + plan.planIdentity()
                    + " status=" + plan.status() + " next=" + plan.nextSourceEpoch() + " through=" + plan.requiredThroughSourceEpoch()
                    + " boundary=" + documents.catchUpBarrier(plan.barrierIdentity()).orElseThrow().causeOrder());
            var step = local.step();
            if (step == null) continue;
            var invocation = step.invocation();
            lines.add("localRoot=" + root + " work=" + step.work().workIdentity() + " sourceEpoch=" + step.work().sourceEpoch()
                    + " receipt=" + step.work().sourceReceiptIdentity() + " anchor=" + step.anchor().sourceOrderKey()
                    + " input=" + invocation.input().invocationIdentity() + " cause=" + invocation.input().cause().causeIdentity()
                    + " kind=" + invocation.input().cause().getClass().getSimpleName()
                    + " entryOwners=" + invocation.rootedEvidence().context().entryOwners()
                    + " expectedTarget=" + step.target().expectedTargetBlueId()
                    + " cursor=" + step.target().pendingHistoricalEpoch());
            var current = documents.closureSnapshot(invocation.documents().keySet());
            for (var captured : invocation.documents().values()) {
                var owner = captured.documentId();
                var actual = documents.require(owner);
                var rows = invocation.input().snapshot().occurrences().stream()
                        .filter(row -> row.sourceDocumentId().value().equals(owner.value())).toList();
                lines.add("localRoot=" + root + " comparedOwner=" + owner + " selected=" + captured.head()
                        + " actual=" + actual.epoch() + ":" + actual.currentRepresentation().blueId()
                        + " selectedGraph=" + captured.graphGeneration() + " actualGraph=" + documents.graphGeneration(owner)
                        + " exactBody=" + NodeWireForm.get(captured.current().copyNode())
                                .equals(NodeWireForm.get(actual.currentRepresentation().copyNode()))
                        + " exactRows=" + ManagedOccurrenceInventory.sameRows(rows, current.occurrenceInventory().rowsFrom(owner)));
            }
        }
        return List.copyOf(lines);
    }
}
