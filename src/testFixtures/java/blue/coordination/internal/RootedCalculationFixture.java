package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.Objects;

/** Real calculation controls confined to the separately published test fixtures. */
public final class RootedCalculationFixture {
    private final DefaultCoordinationEngine engine;

    /** Attaches to the production engine without replacing any processor or store. */
    public RootedCalculationFixture(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine, "engine");
    }

    /** Captures the selected exact view using the production SDK adapter. */
    public ClosureInvocationInput capture(DocumentId root, String entryBlueId, ContractsExecutionPolicy policy) {
        var entry = engine.auditTimelineEntry(entryBlueId).orElseThrow();
        var batch = engine.contractsClosureAdapter().captureRoot(root, entry, policy);
        if (batch.invocations().size() != 1) throw new IllegalStateException("Expected one rooted input");
        return batch.invocations().get(0).input();
    }

    /** Calculates real source work and warms caches without invoking host publication. */
    public ClosureProcessResult precompute(DocumentId root, String entryBlueId) {
        var input = capture(root, entryBlueId, null);
        return new BlueClosureContracts(engine.runtime().documentProcessor()).processClosure(input).processResult();
    }

    /** Validates a proposed terminal record through the actual host verifier without publication. */
    public void verifyTerminalCandidate(DocumentId root, String entryBlueId, ClosureProcessResult result,
            java.util.List<DocumentId> members, String terminalKey) {
        var entry = engine.auditTimelineEntry(entryBlueId).orElseThrow();
        var batch = engine.contractsClosureAdapter().captureRoot(root, entry, null);
        if (batch.invocations().size() != 1) throw new IllegalStateException("Expected one rooted input");
        var invocation = batch.invocations().get(0);
        var evidence = RootedTerminalEvidence.capture(invocation, result);
        new ContractsClosurePublicationReceipt(terminalKey, members,
                blue.language.processor.closure.ClosureAttemptResult.complete(result), 0L,
                ManagedSurfacePublicationEvidence.empty(), null, evidence);
    }

    /** Injects one failure at an actual Contracts publication boundary; no legacy processor hook is used. */
    public void failPublicationAt(String selectedPoint) {
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        if ("AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH".equals(selectedPoint)) {
            engine.contractsClosureAdapter().onPublicationFailurePoint(point -> {
                if (once.compareAndSet(false, true)) throw new IllegalStateException("Injected rooted publication failure: " + point);
            });
        } else {
            var selected = MultiDocumentPublicationTransaction.FailurePoint.valueOf(selectedPoint);
            engine.contractsClosureAdapter().onStoreFailurePoint(point -> {
                if (point == selected && once.compareAndSet(false, true))
                    throw new IllegalStateException("Injected rooted publication failure: " + point);
            });
        }
    }

    /** Removes only the publication fault callbacks installed by this fixture. */
    public void clearPublicationFailure() {
        engine.contractsClosureAdapter().onPublicationFailurePoint(ignored -> { });
        engine.contractsClosureAdapter().onStoreFailurePoint(ignored -> { });
    }

    /** Reads actual independently owned registered work; never constructs a successful source receipt. */
    public blue.coordination.api.ManagedEpochApplicationWork registeredOwnedHistory(DocumentId root) {
        return Objects.requireNonNull(new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter())
                .select(root, engine.auditTimelineEntries()).historical(), "No actual registered owned history");
    }

    /**
     * Captures registered owned historical work through the unchanged production capturer.
     * This fixture supports only the release-profile rooted SDK setup and copies its
     * actual configured budget/environment; it never changes policy or publishes.
     * @param root actual public consumer selected by the rooted driver
     * @return exact immutable input for the registered due work
     */
    public ClosureInvocationInput captureRegisteredOwnedHistory(DocumentId root) {
        var selected = new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter())
                .select(root, engine.auditTimelineEntries());
        var work = Objects.requireNonNull(selected.historical(), "No actual registered owned history");
        var admission = engine.contractsClosureAdmissionAdapter();
        var environment = admission.environment();
        var policy = admission.executionPolicy();
        var profile = ContractsClosureProfile.release10(environment.blueLanguageSpecificationIdentity(),
                environment.contractsSpecificationIdentity(),
                ContractsExecutionPolicy.exactSharedGas(policy.sharedLimit(), policy.label()), java.util.List.of(root));
        if (!profile.rootedCheckpoint() || !profile.executionPolicy().identity().equals(policy.identity()))
            throw new IllegalArgumentException("Expected the actual rooted release-profile SDK fixture");
        return new ManagedEpochInvocationCapturer(engine.contractsClosureAdapter(), engine.runtime(), engine.objects(),
                engine.documents(), profile, environment).capture(work, selected.excludedConsumers()).invocation().input();
    }

    /**
     * Exercises the actual canonical due-work admission boundary for a proposed work item.
     * Negative callers must prove that the item differs from the registered identity.
     * @param proposed exact recomputable but deliberately noncanonical candidate
     */
    public void rejectNoncanonicalOwnedHistoryCandidate(blue.coordination.api.ManagedEpochApplicationWork proposed) {
        var canonical = registeredOwnedHistory(proposed.consumerDocumentId());
        if (canonical.workIdentity().equals(proposed.workIdentity()))
            throw new IllegalArgumentException("Negative fixture must not execute the actual canonical work");
        engine.contractsClosureAdapter().executeManagedEpochApplication(proposed);
        throw new AssertionError("Noncanonical historical work crossed the production admission boundary");
    }

    /** Simulates unavailable evidence through the real same-cursor plan/barrier failure path only. */
    public void deferRegisteredOwnedHistory(blue.coordination.api.ManagedEpochApplicationWork work) {
        var selected = registeredOwnedHistory(work.consumerDocumentId());
        if (!selected.workIdentity().equals(work.workIdentity()))
            throw new IllegalArgumentException("Availability control does not name the actual due work");
        engine.documents().recordManagedEpochEvidenceFailure(ManagedEpochEvidenceException.waiting(selected,
                ManagedEpochEvidenceException.SOURCE_EPOCH_MISSING, "Rooted join fixture: exact history temporarily unavailable"));
        var after = new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter())
                .select(work.consumerDocumentId(), engine.auditTimelineEntries());
        if (!after.blocked() || after.live() != null || after.historical() != null || after.localHistorical() != null)
            throw new IllegalStateException("Availability control did not retain a blocked source with no selected work");
    }

    /** Reads the actual barrier that created the registered work, independently of its source receipt order. */
    public blue.language.processor.ExternalOrderKey registeredHistoryBarrierOrder(
            blue.coordination.api.ManagedEpochApplicationWork work) {
        var plan = engine.documents().catchUpPlan(work.planIdentity()).orElseThrow();
        if (!plan.barrierIdentity().equals(work.barrierIdentity())) throw new IllegalStateException("Work/plan barrier mismatch");
        return engine.documents().catchUpBarrier(plan.barrierIdentity()).orElseThrow().causeOrder();
    }

    /** Captures actual input/result evidence and returns a read-only current-owner fence check. */
    public Runnable capturedPublicationFence(DocumentId root) {
        var adapter = engine.contractsClosureAdapter();
        var invocation = adapter.nextRootLiveInput(root, engine.auditTimelineEntries()).orElseThrow().invocations().get(0);
        var result = new BlueClosureContracts(engine.runtime().documentProcessor()).processClosure(invocation.input()).processResult();
        if (!result.commits()) throw new IllegalStateException("Publication fence fixture requires an actual successful calculation");
        var owners = new java.util.LinkedHashSet<>(RootedResultScope.members(result));
        return () -> adapter.requireRootedOwnersStillCurrent(invocation, result, engine.documents().closureSnapshot(owners), owners);
    }

    /** Reads the actual selected local work without creating an independent catch-up plan. */
    public blue.coordination.api.ManagedEpochApplicationWork localHistoryWork(DocumentId root) {
        return Objects.requireNonNull(engine.contractsClosureAdapter().nextRootLocalHistory(root,
                engine.auditTimelineEntries()).step()).work();
    }

    /** Captures the actual next local historical input, including the configured execution policy. */
    public ClosureInvocationInput captureLocalHistory(DocumentId root) {
        return Objects.requireNonNull(engine.contractsClosureAdapter().nextRootLocalHistory(root,
                engine.auditTimelineEntries()).step(), "No pending local history").invocation().input();
    }

    /** Describes actual selected work and its local-versus-independent fences without executing it. */
    public String rootSelectionDescription(DocumentId root) {
        var next = new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter())
                .select(root, engine.auditTimelineEntries());
        String description = "root=" + root + " blocked=" + next.blocked()
                + " live=" + (next.live() == null ? "none" : next.live().entry().blueId())
                + " managed=" + (next.historical() == null ? "none" : next.historical().workIdentity());
        if (next.localHistorical() == null) return description + " local=none";
        var step = next.localHistorical();
        var invocation = step.invocation();
        return description + " local=" + step.work().workIdentity() + " boundary=" + step.anchor().sourceOrderKey()
                + " source=" + step.work().sourceDocumentId()
                + " epoch=" + step.work().sourceEpoch() + " invocation=" + invocation.input().invocationIdentity()
                + " entryOwners=" + invocation.rootedEvidence().context().entryOwners()
                + " selected=" + invocation.documents().values().stream().map(row ->
                        row.documentId() + ":" + row.head() + ":graph=" + row.graphGeneration()).toList()
                + " independentFences=" + invocation.rootedEvidence().publicationFences();
    }

    /** Calculates one real pending local initialization successor without publishing any source or root. */
    public ClosureProcessResult precomputeLocalInitialization(DocumentId root, String causalEntry) {
        var selected = engine.contractsClosureAdapter().nextRootLocalHistory(root, engine.auditTimelineEntries());
        var step = Objects.requireNonNull(selected.step(), "Actual local prerequisite was not selected");
        if (!step.anchor().blueId().equals(causalEntry)) throw new IllegalArgumentException("Different local barrier");
        return new BlueClosureContracts(engine.runtime().documentProcessor())
                .processClosure(step.invocation().input()).processResult();
    }

    /** Enumerates actual retained terminal evidence for publication-proof mutation tests. */
    public java.util.List<RetainedTerminal> retainedTerminals(DocumentId root) {
        return engine.documents().closureSnapshot(java.util.Set.of(root)).closurePublicationReceipts().values().stream()
                .filter(receipt -> receipt.rootedTerminalEvidence() != null)
                .map(receipt -> new RetainedTerminal(receipt.publicationIdentity(),
                        receipt.rootedTerminalEvidence().input(), receipt.attempt().processResult())).toList();
    }

    /** Applies the production reference proof to a proposed result and direct-target inventory without writing state. */
    public boolean verifiesRetainedReferenceRebind(String key, ClosureProcessResult result,
            DocumentId document, java.util.List<DocumentId> directTargets) {
        return engine.documents().closurePublicationReceipt(key).orElseThrow().rootedTerminalEvidence()
                .verifiesReadOnlyReferenceRebind(result, document, directTargets);
    }

    /**
     * Runs the original retained terminal's checkpoint-only authority check without publication.
     * @param key exact retained publication key
     * @param result proposed complete result
     * @param document proposed owned representation source
     * @param proof proposed processor proof identity
     */
    public void verifyRetainedCheckpointPosition(String key, ClosureProcessResult result,
            DocumentId document, String proof) {
        engine.documents().closurePublicationReceipt(key).orElseThrow().rootedTerminalEvidence()
                .requireCheckpointReferencePosition(result, document, proof);
    }

    /**
     * Matches a proposed position against the actual immutable retained publication chain.
     * @param position proposed authenticated representation position
     */
    public void verifySuppliedRepresentationPosition(
            blue.language.processor.closure.ManagedRepresentationTransition position) {
        new ManagedRepresentationHistory(engine.documents()).verifySupplied(position);
    }

    /** The exact processor input/result retained at a real terminal publication. */
    public record RetainedTerminal(String identity, ClosureInvocationInput input, ClosureProcessResult result) { }

    /** Describes actual pending local evidence without changing stores. */
    public String localHistoryDescription(DocumentId root) {
        var view = engine.documents().require(root).rootedView();
        return "boundary=" + view.logicalBoundary() + " pending=" + view.snapshot().occurrences().stream()
                .filter(row -> !row.active() && row.pendingHistoricalEpoch() != null).map(row -> {
                    var source = view.snapshot().managedDocument(row.targetDocumentId());
                    long next = row.pendingHistoricalEpoch() + 1;
                    var receipt = engine.documents().managedEpochEvidence(
                            ContractsClosureAdapter.coordinationId(row.targetDocumentId()), next).receipt();
                    return row.sourceDocumentId() + ":" + row.sourcePath() + " cursor=" + row.pendingHistoricalEpoch()
                            + " target=" + row.expectedTargetBlueId() + " source=" + source.epoch() + ":" + source.blueId()
                            + " receipt=" + (receipt == null ? "missing" : receipt.epoch() + ":" + receipt.sourceOrder());
                }).toList();
    }

    /** Returns the exact retained selected-view evidence without opening current child heads. */
    public blue.language.processor.closure.AffectedClosureSnapshot selectedView(DocumentId root) {
        return engine.documents().require(root).rootedView().snapshot();
    }

    /** Reads the actual immutable history descriptor retained with the managed session. */
    public java.util.Map<String, Object> historyBasis(DocumentId root) {
        return engine.documents().require(root).requireRootedHistory().descriptor();
    }

    /** Validates a proposed retained coordinate on a detached session; never installs it in the store. */
    public void verifyRetainedPublicationHead(DocumentId root, long epoch, String blueId) {
        var session = engine.documents().require(root);
        var view = session.rootedView();
        var heads = new java.util.LinkedHashMap<DocumentId, InMemoryDocumentStore.DocumentHead>();
        for (var id : view.result().rootedProjection().ownedDocumentIds()) {
            DocumentId owner = ContractsClosureAdapter.coordinationId(id);
            heads.put(owner, new InMemoryDocumentStore.DocumentHead(view.retainedEpoch(owner),
                    view.snapshot().managedDocument(id).blueId()));
        }
        heads.put(root, new InMemoryDocumentStore.DocumentHead(epoch, blueId));
        session.copyForAtomicPublication().retainRootedView(view.withPublishedHeads(heads));
    }

    /** Tests exact retained-view reuse without installing the proposed capture. */
    public boolean matchesRetainedCapture(DocumentId root, long graphGeneration,
            java.util.List<blue.language.processor.closure.ManagedDocumentSnapshot> documents,
            java.util.List<blue.language.processor.closure.ManagedOccurrenceBinding> rows,
            java.util.List<blue.language.processor.closure.ComponentSnapshot> components,
            java.util.List<blue.language.processor.closure.DocumentId> publicRoots) {
        return engine.documents().require(root).rootedView()
                .matchesCapture(graphGeneration, documents, rows, components, publicRoots);
    }

    /** Exercises the publication boundary verifier with real retained state and a proposed logical order. */
    public void verifyRetainedLogicalBoundary(DocumentId root, String terminalKey,
            blue.language.processor.ExternalOrderKey proposed) {
        var retained = engine.documents().closurePublicationReceipt(terminalKey).orElseThrow();
        var original = engine.documents().require(root).rootedView();
        var routes = new java.util.LinkedHashMap<DocumentId, java.util.List<blue.language.processor.SubscriptionDelta.Entry>>();
        original.snapshot().managedDocuments().forEach(document -> {
            DocumentId id = ContractsClosureAdapter.coordinationId(document.documentId());
            routes.put(id, original.routes(id));
        });
        var candidate = new RootedDocumentView(original.result(), original.subscriptions(), routes, proposed);
        candidate.requireProcessingBoundary(retained.rootedTerminalEvidence(), engine.documents().catchUpPlansSnapshot());
    }

    /** Warms the selected exact inputs in the requested physical enumeration only. */
    public void warmSelectedView(DocumentId root, boolean reversed) {
        var documents = new java.util.ArrayList<>(engine.documents().require(root).rootedView().snapshot().managedDocuments());
        if (reversed) java.util.Collections.reverse(documents);
        for (var document : documents) engine.runtime().loadExactProcessingSnapshot(document.blueId());
    }

    /** Evicts disposable exact snapshot caches only; no retained body or progress is removed. */
    public void evictSnapshotCaches() {
        engine.runtime().clearSnapshotCaches();
    }

    /** Uses the actual read-only selector and returns only its chosen exact entry. */
    public java.util.Optional<String> nextLiveInput(DocumentId root) {
        return engine.contractsClosureAdapter().nextRootLiveInput(root, engine.auditTimelineEntries())
                .map(batch -> batch.entry().blueId());
    }

    /**
     * Executes the complete immutable input in a fresh materialized runtime.
     * There is no host publication, rooted projection, cached result, history lookup or
     * optimized dependency selection on this reference path. The original tariff,
     * direct deliveries, public-root flags, exact bodies, environment and budget remain.
     */
    public static ClosureProcessResult materializedReference(ClosureInvocationInput input) {
        return materializedReference(input, java.util.List.of());
    }

    /**
     * Executes with explicit exact cause resources, never reading the live host's provider/store.
     * @param input the captured immutable calculation input
     * @param exactCauseValues exact request/source values authenticated by the caller's cause evidence
     * @return the complete materialized reference result
     */
    public static ClosureProcessResult materializedReference(ClosureInvocationInput input,
            java.util.List<ExactValue> exactCauseValues) {
        WholeObjectStore objects = new WholeObjectStore(new EngineMetrics());
        for (ExactValue value : exactCauseValues) {
            objects.putVerifiedProviderEvidence(value, value.copyNode(), value.cyclicSetProof().orElse(null),
                    "rooted reference exact cause");
        }
        retainReferenceSnapshot(objects, input.snapshot());
        if (input.cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision
                && revision.successorRepresentationCause().isPresent()) {
            // Exact values already bound by the supplied first-successor proof, not a host-store lookup.
            retainReferenceSnapshot(objects, revision.successorRepresentationCause().orElseThrow()
                    .transition().originalInput().snapshot());
        }
        var base = ClosureInvocationInput.processClosure(input.invocationIdentity(), input.snapshot(), input.cause(),
                input.directDeliveries(), input.directDeliverySnapshotIdentity(), input.executionPolicy(), input.environment());
        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            var attempt = new BlueClosureContracts(runtime.documentProcessor()).processClosure(base);
            if (!attempt.isComplete()) {
                throw new IllegalStateException("Materialized reference suspended: " + attempt.kind()
                        + "; demands=" + attempt.resourceDemands().stream().map(demand ->
                            demand.getClass().getSimpleName() + ":" + demand.demandIdentity()
                            + ":" + demand.sourceDocumentId().value()).toList()
                        + "; exactBlueIds=" + attempt.requiredExactBlueIds());
            }
            return attempt.processResult();
        }
    }

    /**
     * Executes the original rooted input unchanged in a fresh runtime with explicit immutable resources.
     * Unlike {@link #materializedReference(ClosureInvocationInput, java.util.List)}, this retains the
     * original rooted ownership/witness binding. It is fresh-execution parity, not an unrooted oracle.
     * The caller must establish that the terminal used this ordinary input, not a distinct retry envelope.
     * @param input complete captured rooted input, including its processor-owned private binding
     * @param exactCauseValues authenticated immutable request/source values; no live provider is consulted
     * @return the complete result of executing the unchanged input
     */
    public static ClosureProcessResult freshRootedReference(ClosureInvocationInput input,
            java.util.List<ExactValue> exactCauseValues) {
        WholeObjectStore objects = new WholeObjectStore(new EngineMetrics());
        for (ExactValue value : exactCauseValues) {
            objects.putVerifiedProviderEvidence(value, value.copyNode(), value.cyclicSetProof().orElse(null),
                    "fresh rooted reference exact cause");
        }
        retainReferenceSnapshot(objects, input.snapshot());
        if (input.cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision
                && revision.successorRepresentationCause().isPresent()) {
            retainReferenceSnapshot(objects, revision.successorRepresentationCause().orElseThrow()
                    .transition().originalInput().snapshot());
        }
        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            var attempt = new BlueClosureContracts(runtime.documentProcessor()).processClosure(input);
            if (!attempt.isComplete()) {
                throw new IllegalStateException("Fresh rooted reference suspended: " + attempt.kind()
                        + "; demands=" + attempt.resourceDemands().stream().map(demand ->
                            demand.getClass().getSimpleName() + ":" + demand.demandIdentity()
                            + ":" + demand.sourceDocumentId().value()).toList()
                        + "; exactBlueIds=" + attempt.requiredExactBlueIds());
            }
            return attempt.processResult();
        }
    }

    private static void retainReferenceSnapshot(WholeObjectStore objects,
            blue.language.processor.closure.AffectedClosureSnapshot snapshot) {
        for (var document : snapshot.managedDocuments()) {
            var component = snapshot.components().stream()
                    .filter(value -> value.orderedMemberDocumentIds().contains(document.documentId()))
                    .findFirst().orElseThrow();
            var proof = component.completeCyclicProof();
            var value = proof == null
                    ? ExactValue.verified(document.blueId(), document.document())
                    : ExactValue.fromVerifiedProviderEvidence(document.blueId(), document.document(), proof);
            objects.putVerifiedProviderEvidence(value, document.document(), proof, "rooted reference input");
        }
    }
}
