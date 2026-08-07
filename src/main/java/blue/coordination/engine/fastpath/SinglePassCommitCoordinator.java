package blue.coordination.engine.fastpath;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.ManagedDocumentSnapshot;

import java.util.Objects;

/**
 * Commit fast path: one publisher call and post-success cache installation.
 * It deliberately performs no Node clone, BlueId calculation, serialization,
 * inventory reconstruction, or second session read.
 */
public final class SinglePassCommitCoordinator {
    private final AtomicCommitPublisher publisher;
    private final PreparedRootContextCache contexts;

    public SinglePassCommitCoordinator(
            AtomicCommitPublisher publisher,
            PreparedRootContextCache contexts) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    public CommitOutcome commit(PreparedAtomicCommit commit) {
        PreparedAtomicCommit checked = Objects.requireNonNull(
                commit, "commit");
        CommitOutcome outcome = publisher.compareAndPublish(checked);
        if (outcome.status() == CommitStatus.COMMITTED
                || outcome.status() == CommitStatus.ALREADY_COMMITTED) {
            ManagedDocumentSnapshot authoritative = outcome.session()
                    .orElseThrow(() -> new IllegalStateException(
                            "Committed publication lacks session evidence"));
            PreparedRootExecutionContext context =
                    checked.resultingContext();
            if (!outcome.transitionIdentity().equals(
                    checked.plan().transitionIdentity())
                    || !context.matches(
                            authoritative.sessionId().value(),
                            authoritative.currentEpoch(),
                            authoritative.currentRootBlueId(),
                            authoritative.fragmentInventoryIdentity())) {
                throw new IllegalStateException(
                        "Committed publication differs from prepared result");
            }
            contexts.install(checked.resultingContext());
        }
        return outcome;
    }
}
