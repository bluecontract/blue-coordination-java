package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ClosureResult;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.language.model.NodeWireForm;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Observes normal SDK publications and independently recalculates their exact retained inputs. */
public final class RootedDiamondAcquisitionProbe {
    private final BlueCoordination blue;
    private final DefaultCoordinationEngine engine;

    public RootedDiamondAcquisitionProbe(BlueCoordination blue) {
        this.blue = Objects.requireNonNull(blue);
        this.engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
    }

    /** Reads the actual next local step; a matching cause alone never manufactures a step. */
    public boolean atRegisteredTerminal(DocumentId receiver, DocumentId consumer) {
        var local = engine.contractsClosureAdapter().nextRootLocalHistory(receiver, engine.auditTimelineEntries()).step();
        if (local == null) return false;
        var registered = new RootedJoinPublicationSafetyProbe(engine).registeredInput(consumer);
        return local.work().consumerDocumentId().equals(consumer)
                && local.invocation().input().cause().causeIdentity().equals(registered.cause().causeIdentity());
    }

    /** Records real independent prefix proofs before B executes; this does not select its input. */
    public Evidence capture(DocumentId driver, DocumentId peer,
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

        return new Evidence(original.input(), original.rootedEvidence().context().identity(),
                original.rootedEvidence().deliveryBasisIdentity(), frozenSource, sourceProof,
                historicalPeer, selectedPeer, peerProof, registeredWork,
                registeredInput.cause().causeIdentity(), cause.sourceOrder());
    }

    /** Before-call durable membership distinguishes new publication from exact reconciliation. */
    public Set<String> publications() {
        var roots = engine.documents().sessions().stream().map(DocumentSession::documentId)
                .collect(java.util.stream.Collectors.toSet());
        return Set.copyOf(engine.documents().closureSnapshot(roots).closurePublicationReceipts().keySet());
    }

    /** Enumerates all three attempt lanes; failures/suspensions cannot disappear in a success filter. */
    public List<Calculation> observe(DrainResult drain, Set<String> before, ExternalOrderKey cutoff) {
        if (!drain.managedEpochEvidenceFailures().isEmpty())
            throw new AssertionError("Unexpected evidence failure: " + drain.managedEpochEvidenceFailures());
        // Root readiness describes the next boundary: a completed prefix may expose
        // a waiting terminal. A deterministic one-step pause is not an attempt failure.
        String expectedDiagnostic = drain.paused() ? "PROCESSING_PAUSED" : drain.blocked() ? "PROCESSING_BLOCKED" : "NONE";
        if (!drain.diagnostic().code().equals(expectedDiagnostic))
            throw new AssertionError("Unexpected aggregate drain diagnostic: " + drain.diagnostic());
        var results = new ArrayList<Calculation>();
        for (var entry : drain.entries()) {
            if (entry.closures().isEmpty()) {
                if (entry.disposition() != EntryDisposition.NO_MATCH || !entry.publicEvents().isEmpty() || entry.diagnostic().present()
                        || entry.stats().gas() != 0L || entry.stats().committedTransitions() != 0L
                        || !entry.stats().documentStepOrder().isEmpty())
                    throw new AssertionError("An empty entry must be a zero-effect transport NO_MATCH: " + entry);
            } else {
                // NO_MATCH can describe the original targeted selector even when
                // other selected work committed. Every actual closure is still verified.
                if (entry.disposition() != EntryDisposition.APPLIED && entry.disposition() != EntryDisposition.NO_MATCH)
                    throw new AssertionError("Unexpected entry outcome: " + entry);
                for (var result : entry.closures()) results.add(closure(result, before, null, false));
            }
        }
        for (var local : drain.rootedRetainedApplications())
            results.add(closure(local.result(), before, local.work(), false));
        for (var managed : drain.managedEpochApplicationAttempts()) {
            if (!managed.attempt().isComplete() || !managed.published() || managed.replayed()
                    || managed.publicationFailure().isPresent())
                throw new AssertionError("Unexpected incomplete, failed or replayed managed attempt: " + managed);
            var application = engine.documents().catchUpApplicationByWork(managed.work().workIdentity()).orElseThrow();
            var receipt = engine.documents().closureReceiptForApplication(application).orElseThrow();
            if (!application.applicationReceiptIdentity().equals(managed.receipt().orElseThrow().applicationReceiptIdentity()))
                throw new AssertionError("SDK attempt differs from its exact durable application");
            var calculation = retained(receipt.publicationIdentity(), before, managed.work(), true);
            var actual = managed.attempt().processResult();
            if (!actual.invocationIdentity().equals(calculation.actual().invocationIdentity())
                    || !actual.outputClosureIdentity().equals(calculation.actual().outputClosureIdentity())
                    || !actual.gasTraceIdentity().equals(calculation.actual().gasTraceIdentity())
                    || actual.totalGas() != calculation.actual().totalGas())
                throw new AssertionError("SDK managed result differs from its retained processor evidence");
            results.add(calculation);
        }
        if (results.stream().map(Calculation::publicationIdentity).distinct().count() != results.size())
            throw new AssertionError("A publication appeared in more than one attempt lane");
        for (var result : results) if (result.logicalBoundary().compareTo(cutoff) > 0)
            throw new AssertionError("Selected publication exceeded the original exact logical boundary: " + result.publicationIdentity());
        return List.copyOf(results);
    }

    private Calculation closure(ClosureResult result, Set<String> before,
            blue.coordination.sdk.ManagedEpochApplicationWork work, boolean registered) {
        if (!result.applied()) throw new AssertionError("Unexpected noncommitting closure: " + result);
        var calculation = retained(result.closureId(), before, work, registered);
        if (result.stats().gas() != calculation.actual().totalGas())
            throw new AssertionError("SDK closure gas differs from its retained exact result");
        return calculation;
    }

    private Calculation retained(String publication, Set<String> before,
            blue.coordination.sdk.ManagedEpochApplicationWork work, boolean registered) {
        var input = blue.advanced().closureInvocation(publication).orElseThrow();
        var actual = blue.advanced().closureExecution(publication).orElseThrow();
        if (!input.invocationIdentity().equals(actual.invocationIdentity()))
            throw new AssertionError("Retained base input cannot stand in for a different retry execution");
        var terminal = Objects.requireNonNull(engine.documents().closurePublicationReceipt(publication).orElseThrow()
                .rootedTerminalEvidence(), "Expected retained rooted publication evidence");
        var logicalBoundary = terminal.logicalBoundary(engine.documents().catchUpPlansSnapshot());
        var state = new RootedJoinPublicationSafetyProbe(engine).publicationState();
        ClosureProcessResult reference;
        try (var runtime = BlueRuntime.create(engine.objects())) {
            var attempt = new BlueClosureContracts(runtime.documentProcessor()).processClosure(input);
            if (!attempt.isComplete()) throw new AssertionError("Exact retained input did not independently complete");
            reference = attempt.processResult();
        }
        if (!state.equals(new RootedJoinPublicationSafetyProbe(engine).publicationState()))
            throw new AssertionError("Independent reference calculation mutated publication state");
        return new Calculation(input, actual, reference, actual.commits(), before.contains(publication),
                publication, work, registered, logicalBoundary);
    }

    /** D14 is already available, but the actual B original must still select A11 and historical D2. */
    public void requireHistoricalOriginal(Evidence evidence, Calculation calculation) {
        var input = calculation.input();
        if (!input.cause().causeIdentity().equals(evidence.original().cause().causeIdentity())
                || !input.executionPolicy().identity().equals(evidence.original().executionPolicy().identity())
                || !input.directDeliverySnapshotIdentity().equals(evidence.original().directDeliverySnapshotIdentity()))
            throw new AssertionError("Historical original changed its cause, deliveries or meter");
        for (var original : evidence.original().snapshot().managedDocuments())
            exact(original, input.snapshot().managedDocument(original.documentId()));
        requireFrozenSource(evidence, input);
        exact(evidence.historicalPeer(), input.snapshot().managedDocument(evidence.historicalPeer().documentId()));
        var projection = calculation.actual().rootedProjection();
        if (!projection.context().identity().equals(evidence.originalContext())
                || !projection.deliveryBasisIdentity().equals(evidence.originalDeliveryBasis()))
            throw new AssertionError("Read expansion changed the original rooted context or delivery basis");
        if (!projection.ownedDocumentIds()
                .equals(evidence.original().snapshot().publicRootDocumentIds()))
            throw new AssertionError("Original LIVE prematurely acquired independent owners");
        var peerAfter = calculation.actual().resultingDocuments().stream().filter(value ->
                value.documentId().equals(evidence.historicalPeer().documentId())).findFirst().orElseThrow();
        if (peerAfter.epoch() != evidence.historicalPeer().epoch()
                || !peerAfter.afterBlueId().equals(evidence.historicalPeer().blueId()))
            throw new AssertionError("The historical D2 witness changed during the original LIVE calculation");
    }

    /** Invalid replacement after D2 became a frozen operand; this is not an absent-member admission. */
    public void frontloadFrozenOriginal(Evidence evidence, ClosureInvocationInput original) {
        var peer = evidence.peer();
        var documents = original.snapshot().managedDocuments().stream().map(value ->
                value.documentId().equals(peer.documentId())
                        ? new ManagedDocumentSnapshot(peer.documentId(), peer.blueId(), peer.document(), peer.initialized(),
                                peer.terminated(), false, peer.epoch(), peer.componentGeneration()) : value).toList();
        var rows = new ArrayList<>(original.snapshot().occurrences().stream()
                .filter(row -> !row.sourceDocumentId().equals(peer.documentId())).toList());
        evidence.peerProof().occurrences().stream().filter(row -> row.sourceDocumentId().equals(peer.documentId())).forEach(rows::add);
        ClosureEvidenceFactory.rootedReadExpansion(original, original.snapshot().graphGeneration(), documents, rows,
                List.of(evidence.peerProof()));
    }

    /** Identify the registered terminal by durable work, never by a matching cause alone. */
    public boolean isAcquiredTerminal(Evidence evidence, Calculation calculation) {
        return calculation.registered() && calculation.work().workIdentity().equals(evidence.terminalWork().workIdentity());
    }

    public void requireAcquiredTerminal(Evidence evidence, Calculation calculation) {
        if (!isAcquiredTerminal(evidence, calculation)
                || !calculation.input().cause().causeIdentity().equals(evidence.terminalCause()))
            throw new AssertionError("Expected the genuine original registered terminal");
        var work = calculation.work();
        var expected = evidence.terminalWork();
        if (!work.sourceReceiptIdentity().equals(expected.sourceReceiptIdentity())
                || !work.consumerDocumentId().equals(expected.consumerDocumentId())
                || !work.targetOccurrenceIdentity().equals(expected.targetOccurrenceIdentity())
                || work.activationGeneration() != expected.activationGeneration())
            throw new AssertionError("Terminal peer selection changed the registered position");
        requireFrozenSource(evidence, calculation.input());
        exact(evidence.peer(), calculation.input().snapshot().managedDocument(evidence.peer().documentId()));
        var historicalRows = calculation.input().snapshot().occurrences().stream().filter(row ->
                row.sourceDocumentId().equals(evidence.frozenSource().documentId())
                        && row.targetDocumentId().equals(evidence.peer().documentId())).toList();
        if (historicalRows.isEmpty() || historicalRows.stream().anyMatch(row ->
                !row.expectedTargetBlueId().equals(evidence.historicalPeer().blueId())))
            throw new AssertionError("Fresh terminal changed A11's historical D2 witness");
    }

    private static void requireFrozenSource(Evidence evidence, ClosureInvocationInput input) {
        var id = evidence.frozenSource().documentId();
        exact(evidence.frozenSource(), input.snapshot().managedDocument(id));
        var before = evidence.sourceProof().occurrences().stream().filter(row -> row.sourceDocumentId().equals(id))
                .map(row -> row.bindingIdentity()).sorted().toList();
        var after = input.snapshot().occurrences().stream().filter(row -> row.sourceDocumentId().equals(id))
                .map(row -> row.bindingIdentity()).sorted().toList();
        if (!before.equals(after)) throw new AssertionError("Frozen A11 source-owned occurrence evidence changed");
    }

    private static void exact(ManagedDocumentSnapshot expected, ManagedDocumentSnapshot actual) {
        if (actual == null || !expected.documentId().equals(actual.documentId()) || expected.epoch() != actual.epoch()
                || !expected.blueId().equals(actual.blueId()) || expected.componentGeneration() != actual.componentGeneration()
                || expected.initialized() != actual.initialized() || expected.terminated() != actual.terminated()
                || !NodeWireForm.get(expected.document()).equals(NodeWireForm.get(actual.document())))
            throw new IllegalArgumentException("Exact retained source or original primary changed");
    }

    public record Evidence(ClosureInvocationInput original, String originalContext, String originalDeliveryBasis,
            ManagedDocumentSnapshot frozenSource, AffectedClosureSnapshot sourceProof,
            ManagedDocumentSnapshot historicalPeer, ManagedDocumentSnapshot peer, AffectedClosureSnapshot peerProof,
            ManagedEpochApplicationWork terminalWork, String terminalCause, ExternalOrderKey boundary) { }

    public record Calculation(ClosureInvocationInput input, ClosureProcessResult actual,
            ClosureProcessResult reference, boolean published, boolean replayed, String publicationIdentity,
            blue.coordination.sdk.ManagedEpochApplicationWork work, boolean registered, ExternalOrderKey logicalBoundary) { }
}
