package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.model.NodeWireForm;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.LinkedHashSet;
import java.util.Objects;

/** Test-only inspection of a real local terminal calculation; never publishes or repairs a fence. */
public final class RootedJoinPrefixProbe {
    private final DefaultCoordinationEngine engine;

    /** Uses the actual SDK engine and its unchanged processor/store. */
    public RootedJoinPrefixProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Calculates the actual next local input and checks every prospective owner's original publication fence. */
    public Observation inspect(DocumentId root) {
        var adapter = engine.contractsClosureAdapter();
        var step = Objects.requireNonNull(adapter.nextRootLocalHistory(root, engine.auditTimelineEntries()).step(),
                "No actual local terminal work");
        var invocation = step.invocation();
        var result = new BlueClosureContracts(engine.runtime().documentProcessor())
                .processClosure(invocation.input()).processResult();
        if (!result.commits()) throw new IllegalStateException("Terminal probe did not commit: " + result.diagnostic());
        var owners = new LinkedHashSet<>(RootedResultScope.members(result));
        var current = engine.documents().closureSnapshot(owners);
        String detail = "work=" + step.work().workIdentity() + " input=" + invocation.input().invocationIdentity()
                + " entryOwners=" + invocation.rootedEvidence().context().entryOwners() + " owners=" + owners
                + " gas=" + result.totalGas() + " ownerChecks=" + owners.stream().map(id -> {
                    var selected = invocation.documents().get(id);
                    var fence = invocation.rootedEvidence().publicationFence(id);
                    var session = engine.documents().require(id);
                    var rows = invocation.input().snapshot().occurrences().stream()
                            .filter(row -> row.sourceDocumentId().value().equals(id.value())).toList();
                    return id + " selected=" + selected.head() + " capturedGraph=" + selected.graphGeneration()
                            + " fence=" + fence + " current=" + current.requireHead(id)
                            + " currentGraph=" + current.graphGenerations().require(id)
                            + " exactBody=" + NodeWireForm.get(selected.current().copyNode())
                                    .equals(NodeWireForm.get(session.currentRepresentation().copyNode()))
                            + " exactRows=" + ManagedOccurrenceInventory.sameRows(rows, current.occurrenceInventory().rowsFrom(id));
                }).toList()
                + " inputComponents=" + invocation.input().snapshot().components().stream()
                        .map(row -> row.orderedMemberDocumentIds() + ":" + row.componentStateIdentity()).toList()
                + " currentComponents=" + current.componentStates().stream()
                        .map(row -> row.orderedMemberDocumentIds() + ":" + row.componentStateIdentity()).toList();
        String rejection = null;
        try {
            adapter.requireRootedOwnersStillCurrent(invocation, result, current, owners);
        } catch (RuntimeException failure) {
            rejection = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        return new Observation(invocation.input(), result, rejection == null, detail + " guard=" + rejection);
    }

    /** Complete genuine calculation plus the unchanged guard's outcome, not new publication authority. */
    public record Observation(ClosureInvocationInput input, ClosureProcessResult result,
            boolean ownersCurrent, String detail) { }
}
