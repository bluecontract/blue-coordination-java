package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.language.processor.closure.ClosureInvocationInput;
import java.util.List;
import java.util.Objects;

/** Read-only observations of real canonical and alternate point-root selections. */
public final class RootedJoinSccEntrypointProbe {
    private final DefaultCoordinationEngine engine;

    /** Retains the actual SDK engine; never substitutes a capture or publisher. */
    public RootedJoinSccEntrypointProbe(CoordinationEngine engine) {
        this.engine = (DefaultCoordinationEngine) Objects.requireNonNull(engine);
    }

    /** Reads the production driver's current selection without executing it. */
    public Selection select(DocumentId root) {
        var selected = new RootedCheckpointDriver(engine.documents(), engine.contractsClosureAdapter())
                .select(root, engine.auditTimelineEntries());
        var local = selected.localHistorical();
        return new Selection(selected.blocked(), selected.historical(), local == null ? null : local.root(),
                local == null ? null : local.invocation().input(), local == null ? List.of()
                        : local.invocation().rootedEvidence().context().entryOwners().stream()
                                .map(ContractsClosureAdapter::coordinationId).toList());
    }

    /** Actual selected input and its original entry owners, not eventual acquired owners. */
    public record Selection(boolean blocked, ManagedEpochApplicationWork work, DocumentId localRoot,
            ClosureInvocationInput input, List<DocumentId> entryOwners) {
        /** True only for the existing joint local-input/registered-work route. */
        public boolean joint() { return work != null && input != null; }
    }
}
