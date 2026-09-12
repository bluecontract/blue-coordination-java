package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Diagnostic execution of an authentic original LIVE input; never publishes its result. */
public final class RootedDiamondDriverProbe {
    private final DefaultCoordinationEngine engine;

    /** Attaches to the real adapter, immutable resources and selected stores. */
    public RootedDiamondDriverProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Runs only the existing automatic demand loop, then the unchanged owned-state guard. */
    public Observation inspect(DocumentId root, String entryBlueId) {
        var adapter = engine.contractsClosureAdapter();
        var batch = adapter.captureRoot(root, engine.auditTimelineEntry(entryBlueId).orElseThrow());
        if (batch.invocations().size() != 1) throw new IllegalStateException("Expected one actual rooted input");
        var original = batch.invocations().get(0);
        var before = state();
        var lines = new ArrayList<String>();
        var reachable = new LinkedHashSet<>(original.rootedEvidence().context().entryOwners());
        boolean changed;
        do {
            changed = false;
            for (var row : original.input().snapshot().occurrences()) {
                if (row.active() && reachable.contains(row.sourceDocumentId())) changed |= reachable.add(row.targetDocumentId());
            }
        } while (changed);
        lines.add("original=" + original.input().invocationIdentity() + " cause=" + original.input().cause().causeIdentity()
                + " entryOwners=" + original.rootedEvidence().context().entryOwners() + " activeReach=" + reachable);
        for (var document : original.input().snapshot().managedDocuments()) lines.add("selected=" + document.documentId()
                + " epoch=" + document.epoch() + " blueId=" + document.blueId()
                + " observed=" + document.document().get("/observed"));
        ClosureAttemptResult attempt = null;
        ClosureProcessResult reference = null;
        String failure = null;
        try {
            var run = adapter.resolveManagedApplicationOccurrences(original);
            var invocation = run.invocation();
            attempt = run.attempt();
            lines.add("resolved=" + invocation.input().invocationIdentity() + " retry="
                    + (invocation.retryInput() != null) + " expansions=" + run.expansionCount()
                    + " attempt=" + attempt.kind() + " stop=" + run.automaticResolutionStopReason()
                    + " demands=" + attempt.resourceDemands().stream().map(demand -> demand.demandIdentity()).toList());
            if (attempt.isComplete()) {
                var result = attempt.processResult();
                lines.add("result=" + result.invocationIdentity() + " commits=" + result.commits() + " gas=" + result.totalGas());
                for (var document : result.resultingDocuments()) lines.add("resultDocument=" + document.documentId()
                        + " epoch=" + document.epoch() + " blueId=" + document.afterBlueId()
                        + " observed=" + document.document().get("/observed"));
                for (var row : result.occurrenceBindings()) if (row.pendingHistoricalEpoch() != null) lines.add("pending="
                        + row.sourceDocumentId() + row.sourcePath() + " source=" + row.targetDocumentId()
                        + " cursor=" + row.pendingHistoricalEpoch() + " expected=" + row.expectedTargetBlueId());
                adapter.lastExecutionEvidence().filter(evidence -> evidence.invocationIdentity().equals(result.invocationIdentity()))
                        .ifPresent(evidence -> lines.add("work=" + evidence.workTrace().stream()
                                .map(work -> work.kind() + ":" + work.targetDocumentId() + ":" + work.eventBlueId()).toList()));
                // Fresh execution of the SAME closed input/retry over immutable exact objects,
                // not an unrooted oracle, reconstructed resolution, or alternative scheduler.
                try (var runtime = BlueRuntime.create(engine.objects())) {
                    var contracts = new BlueClosureContracts(runtime.documentProcessor());
                    var fresh = invocation.retryInput() == null ? contracts.processClosure(invocation.input())
                            : contracts.processClosureRetry(invocation.retryInput());
                    if (!fresh.isComplete()) throw new IllegalStateException("Fresh exact execution suspended: " + fresh.kind());
                    reference = fresh.processResult();
                }
                if (result.commits()) {
                    var owners = new LinkedHashSet<>(RootedResultScope.members(result));
                    lines.add("owners=" + owners);
                    try {
                        adapter.requireRootedOwnersStillCurrent(invocation, result, engine.documents().closureSnapshot(owners), owners);
                        lines.add("publicationGuard=PASS");
                    } catch (RuntimeException conflict) {
                        lines.add("publicationGuard=" + conflict.getClass().getSimpleName() + ":" + conflict.getMessage());
                    }
                }
            }
        } catch (RuntimeException problem) {
            failure = problem.getClass().getSimpleName() + ":" + problem.getMessage();
            lines.add("executionFailure=" + failure);
        }
        return new Observation(original.input(), reachable.stream().map(id -> id.value())
                .collect(java.util.stream.Collectors.toUnmodifiableSet()), attempt, reference, failure,
                before.equals(state()), List.copyOf(lines));
    }

    private Object state() {
        return List.of(engine.documents().publicationSnapshot(), engine.documents().catchUpPlansSnapshot());
    }

    /** Actual execution and guard diagnostics; none of these values grants publication authority. */
    public record Observation(ClosureInvocationInput originalInput, Set<String> activeReach,
            ClosureAttemptResult attempt, ClosureProcessResult reference, String failure,
            boolean publicationUnchanged, List<String> lines) { }
}
