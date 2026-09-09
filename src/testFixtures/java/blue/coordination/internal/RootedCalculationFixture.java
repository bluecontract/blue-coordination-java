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

    /** Returns the exact retained selected-view evidence without opening current child heads. */
    public blue.language.processor.closure.AffectedClosureSnapshot selectedView(DocumentId root) {
        return engine.documents().require(root).rootedView().snapshot();
    }

    /** Reads the actual immutable history descriptor retained with the managed session. */
    public java.util.Map<String, Object> historyBasis(DocumentId root) {
        return engine.documents().require(root).requireRootedHistory().descriptor();
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
        candidate.requireProcessingBoundary(retained.rootedTerminalEvidence().input(), engine.documents().catchUpPlansSnapshot());
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
        WholeObjectStore objects = new WholeObjectStore(new EngineMetrics());
        for (var document : input.snapshot().managedDocuments()) {
            var component = input.snapshot().components().stream()
                    .filter(value -> value.orderedMemberDocumentIds().contains(document.documentId()))
                    .findFirst().orElseThrow();
            var proof = component.completeCyclicProof();
            var value = proof == null
                    ? ExactValue.verified(document.blueId(), document.document())
                    : ExactValue.fromVerifiedProviderEvidence(document.blueId(), document.document(), proof);
            objects.putVerifiedProviderEvidence(value, document.document(), proof, "rooted reference input");
        }
        var base = ClosureInvocationInput.processClosure(input.invocationIdentity(), input.snapshot(), input.cause(),
                input.directDeliveries(), input.directDeliverySnapshotIdentity(), input.executionPolicy(), input.environment());
        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            return new BlueClosureContracts(runtime.documentProcessor()).processClosure(base).processResult();
        }
    }
}
