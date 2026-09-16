package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Test-only access to an actual normal terminal selection and its ordinary atomic publisher. */
public final class RootedTerminalPeerProbe {
    private final DefaultCoordinationEngine engine;

    public RootedTerminalPeerProbe(CoordinationEngine engine) { this.engine = (DefaultCoordinationEngine) engine; }

    public Optional<Selection> next() {
        var adapter = engine.contractsClosureAdapter();
        var driver = new RootedCheckpointDriver(engine.documents(), adapter);
        for (var session : engine.documents().sessions()) {
            var selected = driver.select(session.documentId(), engine.auditTimelineEntries());
            if (selected.historical() == null || selected.localHistorical() == null) continue;
            var local = selected.localHistorical();
            var original = adapter.nextRootLocalHistory(local.root(), engine.auditTimelineEntries()).step();
            if (original.invocation().input().snapshot().closureIdentity().equals(local.invocation().input().snapshot().closureIdentity())) continue;
            var fence = RootedJoinEligibility.captureForRoot(engine.documents(), local.root()).stream()
                    .filter(value -> value.terminal() != null && value.terminal().work().workIdentity()
                            .equals(selected.historical().workIdentity())).findFirst().orElseThrow();
            return Optional.of(new Selection(selected, original.invocation().input(), fence));
        }
        return Optional.empty();
    }

    public ClosureProcessResult publish(Selection selected) {
        var next = selected.selected;
        return engine.contractsClosureAdapter().executeRootedJoinApplication(next.historical(), next.excludedConsumers(),
                next.localHistorical()).attempt().processResult();
    }

    /** A genuinely new test invocation on the same verified terminal capture, with one finite cap. */
    public Selection withLimit(Selection selected, long gas) {
        var original = selected.selected.localHistorical();
        var policy = blue.language.processor.closure.ClosureEvidenceFactory.executionPolicy(gas, java.util.Map.of(),
                "terminal-peer-boundary");
        var local = RootedLocalHistory.capture(original.root(), original.capturedState(), original.target(), original.anchor(),
                engine.documents(), engine.objects(), policy, original.invocation().input().environment());
        if (local == null || !local.work().workIdentity().equals(original.work().workIdentity()))
            throw new IllegalStateException("Policy capture changed the exact retained terminal");
        return new Selection(new RootedCheckpointDriver.Selection(null, selected.selected.historical(),
                selected.selected.excludedConsumers(), false, local), selected.original, selected.fence);
    }

    public ClosureProcessResult retained(Selection selected) {
        var application = engine.documents().catchUpApplicationByWork(selected.selected.historical().workIdentity()).orElseThrow();
        return engine.documents().closureReceiptForApplication(application).orElseThrow().attempt().processResult();
    }

    public void requireAlteredFence(Selection selected, boolean futureBoundary) {
        var fence = selected.fence;
        var components = new ArrayList<>(fence.boundary().components());
        components.add(0L); // A strict later tuple, never a fabricated source/receipt or epoch.
        var altered = new RootedJoinEligibility.Fence(futureBoundary ? ExternalOrderKey.of(components) : fence.boundary(),
                fence.owners(), fence.receivers(), futureBoundary ? fence.causeIdentity() : "different-original-cause",
                fence.occurrence(), fence.terminal());
        var roots = selected.fence.terminal().interiorOwners();
        var originals = roots.stream().map(root -> engine.contractsClosureAdapter()
                .nextRootLocalHistory(root, engine.auditTimelineEntries()).step()).filter(java.util.Objects::nonNull).toList();
        engine.contractsClosureAdapter().captureTerminalPeers(altered, originals);
    }

    public Object publicationState() {
        return List.of(engine.documents().publicationSnapshot(), engine.documents().catchUpPlansSnapshot());
    }

    public long processCalls() { return engine.runtime().metrics().counter(ManagedEpochApplicationExecutor.PROCESS_CALLS); }

    public static final class Selection {
        private final RootedCheckpointDriver.Selection selected;
        private final ClosureInvocationInput original;
        private final RootedJoinEligibility.Fence fence;

        private Selection(RootedCheckpointDriver.Selection selected, ClosureInvocationInput original,
                RootedJoinEligibility.Fence fence) {
            this.selected = selected; this.original = original; this.fence = fence;
        }

        public ClosureInvocationInput input() { return selected.localHistorical().invocation().input(); }
        public ClosureInvocationInput originalInput() { return original; }
        public DocumentId frozenSource() { return selected.historical().sourceDocumentId(); }
    }
}
