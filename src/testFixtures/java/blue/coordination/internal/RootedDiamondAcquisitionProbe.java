package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Test-only alternative at the existing typed read-expansion boundary. No old
 * captured document is replaced: the completed peer is absent from the original
 * LIVE input, and its historical role stays inside the source's complete proof.
 * This is not a production scheduler or a caller-selected writable owner set.
 */
public final class RootedDiamondAcquisitionProbe {
    private final DefaultCoordinationEngine engine;

    public RootedDiamondAcquisitionProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Reads the actual next local step; a matching cause alone never manufactures a step. */
    public boolean atRegisteredTerminal(DocumentId receiver, DocumentId consumer) {
        var local = engine.contractsClosureAdapter().nextRootLocalHistory(receiver, engine.auditTimelineEntries()).step();
        if (local == null) return false;
        var registered = new RootedJoinPublicationSafetyProbe(engine).registeredInput(consumer);
        return local.work().consumerDocumentId().equals(consumer)
                && local.invocation().input().cause().causeIdentity().equals(registered.cause().causeIdentity());
    }

    /**
     * Resolves the real demand once to discover its exact source proof, then asks
     * the existing Language factory to add the separately published peer view to
     * the ORIGINAL input. The ordinary expanded D2 input is never rewritten.
     */
    public Calculation publishExpandedOriginal(DocumentId driver, DocumentId peer,
            DocumentId consumer, DocumentId source, String entryBlueId, String originalIdentity) {
        var adapter = engine.contractsClosureAdapter();
        var documents = engine.documents();
        var batch = adapter.captureRoot(driver, engine.auditTimelineEntry(entryBlueId).orElseThrow());
        if (batch.invocations().size() != 1) throw new IllegalArgumentException("Expected one original LIVE lane");
        var original = batch.invocations().get(0);
        if (!original.input().invocationIdentity().equals(originalIdentity)
                || original.input().snapshot().contains(ContractsClosureAdapter.closureId(peer))
                || original.input().snapshot().contains(ContractsClosureAdapter.closureId(source)))
            throw new IllegalArgumentException("Acquisition is allowed only for absent read-expansion members");

        var peerStep = Objects.requireNonNull(adapter.nextRootLocalHistory(peer, engine.auditTimelineEntries()).step());
        peerStep.requireCurrentInput(documents);
        var safety = new RootedJoinPublicationSafetyProbe(engine);
        var registeredWork = safety.registered(consumer);
        var registeredInput = safety.registeredInput(consumer);
        var peerWork = peerStep.work();
        var barrier = documents.catchUpBarrier(registeredWork.barrierIdentity()).orElseThrow();
        var peerSession = documents.require(peer);
        var peerView = peerSession.rootedView();
        var cause = RootedTerminalEvidence.originalLocalCause(peerView, documents);
        if (cause == null || !cause.causeIdentity().equals(original.input().cause().causeIdentity())
                || !cause.causeIdentity().equals(barrier.causedByIdentity())
                || !cause.sourceOrder().equals(batch.entry().sourceOrderKey())
                || !cause.sourceOrder().equals(peerView.logicalBoundary())
                || !cause.sourceOrder().equals(peerStep.anchor().sourceOrderKey())
                || !peerStep.invocation().input().cause().causeIdentity().equals(registeredInput.cause().causeIdentity())
                || !peerWork.consumerDocumentId().equals(registeredWork.consumerDocumentId())
                || !peerWork.sourceDocumentId().equals(registeredWork.sourceDocumentId())
                || !peerWork.sourceReceiptIdentity().equals(registeredWork.sourceReceiptIdentity())
                || peerWork.sourceEpoch() != registeredWork.sourceEpoch()
                || !peerWork.targetOccurrenceIdentity().equals(registeredWork.targetOccurrenceIdentity())
                || peerWork.activationGeneration() != registeredWork.activationGeneration())
            throw new IllegalArgumentException("Peer lacks this exact original-cause completed local prefix");
        peerView.requirePublishedHead(peer, peerSession.epoch(), peerSession.currentRepresentation().blueId());
        peerSession.rootedPublicationPrefix(peerView);
        if (!peerView.result().commits() || !peerView.result().rootedProjection().ownedDocumentIds()
                .equals(List.of(ContractsClosureAdapter.closureId(peer))))
            throw new IllegalArgumentException("Expected the real independently published peer prefix");

        var sourceView = documents.require(source).rootedViewBefore(cause.sourceOrder());
        var frozenSource = registeredInput.snapshot().managedDocument(ContractsClosureAdapter.closureId(source));
        var sourceProof = sourceView.retainedSnapshot();
        exact(frozenSource, sourceProof.managedDocument(frozenSource.documentId()));
        sourceView.requirePublishedHead(source, frozenSource.epoch(), frozenSource.blueId());
        documents.require(source).rootedPublicationPrefix(sourceView);
        var peerSource = peerStep.invocation().input().snapshot().managedDocument(frozenSource.documentId());
        exact(frozenSource, peerSource);
        var peerProof = peerView.retainedSnapshot();
        var selectedPeer = peerProof.managedDocument(ContractsClosureAdapter.closureId(peer));
        var historicalPeer = sourceProof.managedDocument(selectedPeer.documentId());
        if (historicalPeer == null || historicalPeer.blueId().equals(selectedPeer.blueId()))
            throw new IllegalArgumentException("Probe requires distinct genuine historical and acquisition peer views");
        System.out.println("DIAMOND_ACQUISITION peer=" + peer + " originalCause=" + cause.causeIdentity()
                + " boundary=" + cause.sourceOrder() + " peerPublication=" + peerView.result().invocationIdentity()
                + " selected=" + selectedPeer.epoch() + ":" + selectedPeer.blueId()
                + " historical=" + historicalPeer.epoch() + ":" + historicalPeer.blueId()
                + " frozenSource=" + frozenSource.epoch() + ":" + frozenSource.blueId());

        var unchanged = safety.publicationState();
        var resolved = adapter.resolveManagedApplicationOccurrences(original);
        if (!resolved.attempt().isComplete() || resolved.expansionCount() == 0L
                || resolved.invocation().retryInput() != null || resolved.invocation().managedDraftPlan() != null)
            throw new IllegalArgumentException("Expected the real completed A-source read expansion, without a retry or birth");
        var baseline = resolved.invocation();
        exact(historicalPeer, baseline.input().snapshot().managedDocument(selectedPeer.documentId()));
        exact(frozenSource, baseline.input().snapshot().managedDocument(frozenSource.documentId()));
        if (!unchanged.equals(safety.publicationState())) throw new IllegalStateException("Diagnostic PROCESS published state");

        var primaries = new ArrayList<ManagedDocumentSnapshot>();
        for (var selected : baseline.input().snapshot().managedDocuments()) primaries.add(
                selected.documentId().equals(selectedPeer.documentId())
                        ? new ManagedDocumentSnapshot(selectedPeer.documentId(), selectedPeer.blueId(), selectedPeer.document(),
                                selectedPeer.initialized(), selectedPeer.terminated(), false, selectedPeer.epoch(), selectedPeer.componentGeneration())
                        : selected);
        var rows = new ArrayList<>(baseline.input().snapshot().occurrences().stream()
                .filter(row -> !row.sourceDocumentId().equals(selectedPeer.documentId())).toList());
        peerProof.occurrences().stream().filter(row -> row.sourceDocumentId().equals(selectedPeer.documentId())).forEach(rows::add);
        var captures = new LinkedHashMap<>(baseline.documents());
        captures.put(peer, adapter.captureRootedState(peer).documents().get(peer));
        blue.language.processor.closure.RootedAcquisitionFinalizationDiagnostic.describe(original.input(),
                primaries, rows, List.of(sourceProof, peerProof))
                .forEach(line -> System.out.println("DIAMOND_EXPANSION_FINALIZATION " + line));
        if (!unchanged.equals(safety.publicationState())) throw new IllegalStateException("Finalization diagnostic published state");
        var expanded = ClosureEvidenceFactory.rootedReadExpansion(original.input(),
                ContractsClosureAdapter.maximumCapturedGraphGeneration(captures.values()), primaries, rows,
                List.of(sourceProof, peerProof));
        for (var prior : original.input().snapshot().managedDocuments()) exact(prior, expanded.snapshot().managedDocument(prior.documentId()));
        var selected = new ContractsClosureAdapter.CohortInvocation(baseline.members(), original.directDeliveries(), expanded, null,
                captures, null, baseline.automaticExpansion(), original.publicationIdentityMembers(),
                original.publicationIdentityPublicRoots(), original.rootedAnchor(),
                original.rootedEvidence().capturePublicationFences(expanded, documents));
        var execution = adapter.resolveManagedApplicationOccurrences(selected);
        if (!execution.invocation().input().invocationIdentity().equals(expanded.invocationIdentity())
                || execution.invocation().retryInput() != null || !execution.attempt().isComplete())
            throw new IllegalStateException("Acquisition expansion did not remain the same complete closed input");
        var result = execution.attempt().processResult();
        var reference = fresh(expanded);
        if (!result.commits() || !result.rootedProjection().context().entryOwners().equals(original.rootedEvidence().context().entryOwners())
                || !result.rootedProjection().deliveryBasisIdentity().equals(original.rootedEvidence().deliveryBasisIdentity())
                || !result.rootedProjection().ownedDocumentIds().equals(List.of(ContractsClosureAdapter.closureId(driver))))
            throw new IllegalStateException("Read evidence changed original entry context or prematurely acquired the peer");
        var retainedPeer = result.resultingDocuments().stream().filter(value -> value.documentId().equals(selectedPeer.documentId())).findFirst().orElseThrow();
        if (retainedPeer.epoch() != selectedPeer.epoch() || !retainedPeer.afterBlueId().equals(selectedPeer.blueId()))
            throw new IllegalStateException("The added peer must remain immutable until the proved live join");
        var owners = new LinkedHashSet<>(RootedResultScope.members(result));
        adapter.requireRootedOwnersStillCurrent(selected, result, documents.closureSnapshot(owners), owners);
        if (!unchanged.equals(safety.publicationState())) throw new IllegalStateException("Prepublication inspection mutated state");
        var selectedBatch = new ContractsClosureAdapter.FrozenBatch(batch.entry(), batch.routeGeneration(), List.of(selected));
        var outcome = adapter.executeAndPublish(selectedBatch, selected);
        return new Calculation(expanded, outcome.attempt().processResult(), reference, outcome.published(), outcome.replayed());
    }

    /** Normal validated local publication, or the existing exact registered joint publisher at the terminal. */
    public Calculation publishNext(DocumentId driver, DocumentId consumer) {
        var adapter = engine.contractsClosureAdapter();
        var step = Objects.requireNonNull(adapter.nextRootLocalHistory(driver, engine.auditTimelineEntries()).step(), "No real local step");
        step.requireCurrentInput(engine.documents());
        var input = step.invocation().input();
        var reference = fresh(input);
        var safety = new RootedJoinPublicationSafetyProbe(engine);
        if (input.cause().causeIdentity().equals(safety.registeredInput(consumer).cause().causeIdentity())) {
            var publication = safety.publish(safety.capture(driver));
            return new Calculation(input, publication.result(), reference, publication.published(), publication.replayed());
        }
        var outcome = adapter.executeAndPublish(adapter.localHistoryBatch(step), step.invocation());
        return new Calculation(input, outcome.attempt().processResult(), reference, outcome.published(), outcome.replayed());
    }

    private ClosureProcessResult fresh(ClosureInvocationInput input) {
        try (var runtime = BlueRuntime.create(engine.objects())) {
            var attempt = new BlueClosureContracts(runtime.documentProcessor()).processClosure(input);
            if (!attempt.isComplete() || !attempt.processResult().commits())
                throw new IllegalStateException("Fresh exact reference is not a successful terminal: " + attempt.kind());
            return attempt.processResult();
        }
    }

    private static void exact(ManagedDocumentSnapshot expected, ManagedDocumentSnapshot actual) {
        if (actual == null || !expected.documentId().equals(actual.documentId()) || expected.epoch() != actual.epoch()
                || !expected.blueId().equals(actual.blueId()) || expected.componentGeneration() != actual.componentGeneration()
                || expected.initialized() != actual.initialized() || expected.terminated() != actual.terminated()
                || !blue.language.model.NodeWireForm.get(expected.document()).equals(blue.language.model.NodeWireForm.get(actual.document())))
            throw new IllegalArgumentException("Exact retained source or original primary changed");
    }

    public record Calculation(ClosureInvocationInput input, ClosureProcessResult actual,
            ClosureProcessResult reference, boolean published, boolean replayed) { }
}
