package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.ManagedEpochApplicationWork;
import java.util.Optional;

/** Read-only test probe of the two production source-view selectors; creates no evidence. */
public final class RootedTerminalSuccessorProbe {
    private RootedTerminalSuccessorProbe() { }

    public static Runnable capturedFence(CoordinationEngine actualEngine, blue.coordination.api.DocumentId root) {
        var engine = (DefaultCoordinationEngine) actualEngine;
        var captured = engine.contractsClosureAdapter().captureRootedState(root);
        return () -> captured.requireCurrentView(engine.documents());
    }

    /** Actual published objects used as deliberately mismatched staging inputs; never installed. */
    public static java.util.List<Runnable> invalidStagedBindings(CoordinationEngine actualEngine,
            ManagedEpochApplicationWork work) {
        var engine = (DefaultCoordinationEngine) actualEngine;
        var documents = engine.documents();
        var view = documents.require(work.consumerDocumentId()).rootedView();
        var receipt = documents.closureSnapshot(java.util.List.of(work.consumerDocumentId()))
                .closurePublicationReceipts().values().stream()
                .filter(value -> value.attempt().processResult() == view.result()).findFirst().orElseThrow();
        var heads = new java.util.LinkedHashMap<blue.coordination.api.DocumentId, ManagedCatchUpPlanner.Head>();
        for (var owner : RootedResultScope.members(view.result())) {
            var row = documents.require(owner);
            heads.put(owner, new ManagedCatchUpPlanner.Head(row.epoch(), row.currentRepresentation().blueId()));
        }
        var boundary = documents.catchUpBarrier(work.barrierIdentity()).orElseThrow().causeOrder();
        var other = documents.require(work.sourceDocumentId()).rootedViewBefore(boundary);
        if (view.result() == other.result()) throw new IllegalArgumentException("Negative requires a different actual source publication");
        var missing = new java.util.LinkedHashMap<>(heads);
        missing.remove(work.consumerDocumentId());
        var extra = new java.util.LinkedHashMap<>(heads);
        extra.put(blue.coordination.api.DocumentId.of("not-a-derived-owner"), heads.get(work.consumerDocumentId()));
        var wrong = new java.util.LinkedHashMap<>(heads);
        wrong.put(work.consumerDocumentId(), heads.get(work.sourceDocumentId()));
        var wrongEpoch = new java.util.LinkedHashMap<>(heads);
        var ownerHead = heads.get(work.consumerDocumentId());
        wrongEpoch.put(work.consumerDocumentId(), new ManagedCatchUpPlanner.Head(ownerHead.epoch() + 1L, ownerHead.blueId()));
        return java.util.List.of(
                () -> new ManagedRepresentationHistory(documents).afterPublication(receipt, heads, java.util.Map.of(), other),
                () -> new ManagedRepresentationHistory(documents).afterPublication(receipt, missing, java.util.Map.of(), view),
                () -> new ManagedRepresentationHistory(documents).afterPublication(receipt, extra, java.util.Map.of(), view),
                () -> new ManagedRepresentationHistory(documents).afterPublication(receipt, wrong, java.util.Map.of(), view),
                () -> new ManagedRepresentationHistory(documents).afterPublication(receipt, wrongEpoch, java.util.Map.of(), view));
    }

    public static Observation inspect(CoordinationEngine actualEngine, ManagedEpochApplicationWork work) {
        var engine = (DefaultCoordinationEngine) actualEngine;
        var canonical = engine.auditManagedEpochApplicationWork(work.workIdentity()).orElseThrow();
        if (!canonical.workIdentity().equals(work.workIdentity()))
            throw new IllegalArgumentException("Probe requires actual canonical work");
        var documents = engine.documents();
        var boundary = documents.catchUpBarrier(work.barrierIdentity()).orElseThrow().causeOrder();
        var captured = engine.contractsClosureAdapter().captureRootedState(work.consumerDocumentId());
        var selected = captured.snapshot().managedDocument(ContractsClosureAdapter.closureId(work.sourceDocumentId()));
        var consumerHistory = new ManagedRepresentationHistory(documents).forConsumer(work.consumerDocumentId());
        var capturedHistory = new ManagedRepresentationHistory(documents).forCapturedRoot(captured);
        java.util.function.Function<String, blue.language.provider.CyclicSetProof> proofs =
                id -> engine.objects().cyclicSetProofFor(id).proof().orElse(null);
        var planned = consumerHistory.terminalSuccessor(work.sourceDocumentId(), work.sourceEpoch(),
                work.targetOccurrenceIdentity(), boundary, proofs);
        var invocation = capturedHistory.terminalSuccessor(work.sourceDocumentId(), work.sourceEpoch(),
                work.targetOccurrenceIdentity(), boundary, selected, proofs);
        return new Observation(work.workIdentity(), boundary.toString(), selected.epoch(), selected.blueId(),
                work.successorRepresentationCause().map(value -> value.causeIdentity()),
                planned.map(value -> value.causeIdentity()), invocation.map(value -> value.causeIdentity()));
    }

    public record Observation(String workIdentity, String boundary, long selectedEpoch, String selectedBlueId,
            Optional<String> workSuccessor, Optional<String> consumerSuccessor, Optional<String> capturedSuccessor) { }
}
