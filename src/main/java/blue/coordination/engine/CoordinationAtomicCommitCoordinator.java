package blue.coordination.engine;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.coordination.processor.CoordinationFragmentAdmissionVerifier;
import blue.language.model.Node;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Package-private orchestration of immutable fragment admission followed by
 * the single authoritative session-store CAS.
 */
final class CoordinationAtomicCommitCoordinator {

    private final CoordinationFragmentStore fragmentStore;
    private final CoordinationSessionStore sessionStore;
    private final String environmentIdentity;

    CoordinationAtomicCommitCoordinator(
            CoordinationFragmentStore fragmentStore,
            CoordinationSessionStore sessionStore,
            String environmentIdentity) {
        this.fragmentStore = Objects.requireNonNull(
                fragmentStore, "fragmentStore");
        this.sessionStore = Objects.requireNonNull(
                sessionStore, "sessionStore");
        this.environmentIdentity = Objects.requireNonNull(
                environmentIdentity, "environmentIdentity");
    }

    CommitOutcome commit(CoordinationTransition transition) {
        CoordinationTransition checked = Objects.requireNonNull(
                transition, "transition");
        requireCommitBindings(checked);
        CoordinationAtomicCommitPlan commitPlan = checked.commitPlan();
        Optional<ManagedDocumentSnapshot> current = sessionStore.findSession(
                commitPlan.sessionId());
        if (!canWinCommit(current, commitPlan)) {
            return sessionStore.commit(commitPlan);
        }
        CoordinationFragmentTransition fragments =
                checked.fragmentTransition();
        Map<String, Node> newFragments = fragments.newFragments();
        if (!newFragments.isEmpty()) {
            CoordinationFragmentAdmissionVerifier.admitDelta(
                    fragments.resultingInventory()
                            .fragmentationProfileIdentity(),
                    newFragments,
                    fragmentStore);
        }
        fragmentStore.putInventory(fragments.resultingInventory());
        boolean inventoryChanged = !fragments.resultingInventory()
                .inventoryIdentity().equals(
                        commitPlan.expectedFragmentInventoryIdentity());
        Map<String, Node> processingViews = fragments.processingViews();
        if (inventoryChanged || !processingViews.isEmpty()) {
            fragmentStore.putProcessingViews(
                    fragments.resultingInventory().inventoryIdentity(),
                    processingViews);
        }
        return sessionStore.commit(commitPlan);
    }

    private static boolean canWinCommit(
            Optional<ManagedDocumentSnapshot> current,
            CoordinationAtomicCommitPlan commit) {
        if (!current.isPresent()) {
            return false;
        }
        ManagedDocumentSnapshot session = current.get();
        return session.status() == ManagedDocumentStatus.ACTIVE
                && session.currentEpoch() == commit.expectedEpoch()
                && session.currentRootBlueId().equals(
                        commit.expectedRootBlueId())
                && session.initialDocumentBlueId().equals(
                        commit.expectedInitialDocumentBlueId())
                && session.environmentIdentity().equals(
                        commit.expectedEnvironmentIdentity())
                && session.committedFrontier().equals(
                        commit.expectedCommittedFrontier())
                && session.fragmentInventoryIdentity().equals(
                        commit.expectedFragmentInventoryIdentity())
                && session.subscriptions().digest().equals(
                        commit.expectedSubscriptionSnapshotIdentity());
    }

    /**
     * Verifies that a transition belongs to this engine generation without
     * re-checking the mutable current session revision.
     *
     * <p>The session store remains the authoritative CAS and idempotency
     * boundary. Requiring the plan to remain current here would turn exact
     * retries and stale-plan races into local exceptions instead of the
     * required {@code ALREADY_COMMITTED} and {@code CONFLICT} outcomes.</p>
     */
    private void requireCommitBindings(CoordinationTransition transition) {
        CoordinationProcessingPlan plan = transition.plan();
        CoordinationAtomicCommitPlan commit = transition.commitPlan();
        if (!environmentIdentity.equals(
                plan.session().environmentIdentity())) {
            throw new IllegalStateException(
                    "Managed session belongs to another runtime environment");
        }
        if (!plan.session().sessionId().equals(commit.sessionId())
                || plan.session().currentEpoch() != commit.expectedEpoch()
                || !plan.session().currentRootBlueId().equals(
                        commit.expectedRootBlueId())
                || !plan.session().initialDocumentBlueId().equals(
                        commit.expectedInitialDocumentBlueId())
                || !plan.session().environmentIdentity().equals(
                        commit.expectedEnvironmentIdentity())
                || !plan.session().committedFrontier().equals(
                        commit.expectedCommittedFrontier())
                || !plan.session().fragmentInventoryIdentity().equals(
                        commit.expectedFragmentInventoryIdentity())
                || !plan.session().subscriptions().digest().equals(
                        commit.expectedSubscriptionSnapshotIdentity())
                || !plan.rootReference().getBlueId().equals(
                        commit.expectedRootBlueId())
                || !plan.eventReference().getBlueId().equals(
                        commit.eventBlueId())
                || transition.fragmentTransition()
                        != commit.fragmentTransition()
                || transition.subscriptionUpdate()
                        != commit.subscriptionUpdate()) {
            throw new IllegalArgumentException(
                    "Transition commit does not bind to its exact planned "
                            + "session, Root, event, fragments, and "
                            + "subscriptions");
        }
    }
}
