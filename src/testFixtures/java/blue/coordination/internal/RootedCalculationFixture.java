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

    /**
     * Executes the complete immutable acyclic input in a fresh materialized runtime.
     * There is no host publication, rooted projection, cached result, history lookup or
     * optimized dependency selection on this reference path. The original tariff,
     * direct deliveries, public-root flags, exact bodies, environment and budget remain.
     */
    public static ClosureProcessResult materializedReference(ClosureInvocationInput input) {
        WholeObjectStore objects = new WholeObjectStore(new EngineMetrics());
        for (var document : input.snapshot().managedDocuments()) {
            objects.put(ExactValue.verified(document.blueId(), document.document()), "rooted reference input");
        }
        var base = ClosureInvocationInput.processClosure(input.invocationIdentity(), input.snapshot(), input.cause(),
                input.directDeliveries(), input.directDeliverySnapshotIdentity(), input.executionPolicy(), input.environment());
        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            return new BlueClosureContracts(runtime.documentProcessor()).processClosure(base).processResult();
        }
    }
}
