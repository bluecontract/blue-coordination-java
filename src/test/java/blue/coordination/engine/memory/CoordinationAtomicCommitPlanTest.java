package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.processor.CoordinationEngineProcessorTestFixtures;
import blue.coordination.processor.CoordinationSubscriptionSnapshot;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class CoordinationAtomicCommitPlanTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(ResultingSessionForgery.class)
    void shouldRejectEveryForgedResultingSessionBinding(
            ResultingSessionForgery forgery) {
        // given
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-forged-result", "before");
        CoordinationEngineStorageTestFixtures.CommitFixture fixture =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-forged-result");
        ManagedDocumentSnapshot forged = forge(
                fixture.plan.resultingSession(),
                forgery);

        // when
        ThrowingPlanConstruction construction = () -> copyWithResult(
                fixture.plan,
                forged);

        // then
        assertThrows(IllegalArgumentException.class, construction::run);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EpochReceiptForgery.class)
    void shouldRejectEveryForgedEpochReceiptBinding(
            EpochReceiptForgery forgery) {
        // given
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-forged-epoch", "before");
        CoordinationAtomicCommitPlan exact =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-forged-epoch").plan;
        DocumentEpochSnapshot forged = forge(
                exact.resultingEpochSnapshot(),
                forgery);

        // when
        ThrowingPlanConstruction construction = () -> copyWithEpoch(
                exact,
                forged);

        // then
        assertThrows(IllegalArgumentException.class, construction::run);
    }

    private static CoordinationAtomicCommitPlan copyWithResult(
            CoordinationAtomicCommitPlan source,
            ManagedDocumentSnapshot result) {
        return new CoordinationAtomicCommitPlan(
                source.sessionId(),
                source.expectedEpoch(),
                source.expectedRootBlueId(),
                source.expectedInitialDocumentBlueId(),
                source.expectedEnvironmentIdentity(),
                source.expectedCommittedFrontier(),
                source.expectedFragmentInventoryIdentity(),
                source.expectedSubscriptionSnapshotIdentity(),
                source.resultingEpoch(),
                source.resultingRootBlueId(),
                source.eventBlueId(),
                source.eventOrderKey(),
                source.processResult(),
                source.commitCompanion(),
                source.fragmentTransition(),
                source.subscriptionUpdate(),
                source.rootOutboxEventBlueIds(),
                source.transitionIdentity(),
                result,
                source.resultingEpochSnapshot());
    }

    private static CoordinationAtomicCommitPlan copyWithEpoch(
            CoordinationAtomicCommitPlan source,
            DocumentEpochSnapshot epoch) {
        return new CoordinationAtomicCommitPlan(
                source.sessionId(),
                source.expectedEpoch(),
                source.expectedRootBlueId(),
                source.expectedInitialDocumentBlueId(),
                source.expectedEnvironmentIdentity(),
                source.expectedCommittedFrontier(),
                source.expectedFragmentInventoryIdentity(),
                source.expectedSubscriptionSnapshotIdentity(),
                source.resultingEpoch(),
                source.resultingRootBlueId(),
                source.eventBlueId(),
                source.eventOrderKey(),
                source.processResult(),
                source.commitCompanion(),
                source.fragmentTransition(),
                source.subscriptionUpdate(),
                source.rootOutboxEventBlueIds(),
                source.transitionIdentity(),
                source.resultingSession(),
                epoch);
    }

    private static ManagedDocumentSnapshot forge(
            ManagedDocumentSnapshot source,
            ResultingSessionForgery forgery) {
        CoordinationSubscriptionSnapshot subscriptions =
                source.subscriptions();
        if (forgery == ResultingSessionForgery.SUBSCRIPTIONS) {
            subscriptions = CoordinationEngineProcessorTestFixtures
                    .emptySnapshot(
                            source.currentRootBlueId(),
                            source.currentEpoch(),
                            source.committedFrontier());
        }
        return new ManagedDocumentSnapshot(
                source.sessionId(),
                forgery == ResultingSessionForgery.INITIAL_DOCUMENT
                        ? "forged-initial-document"
                        : source.initialDocumentBlueId(),
                source.currentRootBlueId(),
                source.currentEpoch(),
                forgery == ResultingSessionForgery.ENVIRONMENT
                        ? "forged-environment"
                        : source.environmentIdentity(),
                forgery == ResultingSessionForgery.EVENT_FRONTIER
                        ? CoordinationEngineStorageTestFixtures.order(99L)
                        : source.committedFrontier(),
                source.fragmentInventoryIdentity(),
                subscriptions,
                forgery == ResultingSessionForgery.STATUS
                        ? ManagedDocumentStatus.REMOVED
                        : source.status());
    }

    private static DocumentEpochSnapshot forge(
            DocumentEpochSnapshot source,
            EpochReceiptForgery forgery) {
        return new DocumentEpochSnapshot(
                forgery == EpochReceiptForgery.SESSION
                        ? DocumentSessionId.of("forged-session")
                        : source.sessionId(),
                forgery == EpochReceiptForgery.EPOCH
                        ? source.epoch() + 1L
                        : source.epoch(),
                forgery == EpochReceiptForgery.ROOT
                        ? "forged-root"
                        : source.rootBlueId(),
                forgery == EpochReceiptForgery.PRIOR_ROOT
                        ? "forged-prior-root"
                        : source.priorRootBlueId(),
                forgery == EpochReceiptForgery.EVENT
                        ? "forged-event"
                        : source.causedByEventBlueId(),
                forgery == EpochReceiptForgery.ORDER
                        ? ExternalOrderKey.of(
                                Collections.<Object>singletonList(99L))
                        : source.eventOrderKey(),
                forgery == EpochReceiptForgery.INVENTORY
                        ? "forged-inventory"
                        : source.fragmentInventoryIdentity(),
                forgery == EpochReceiptForgery.SUBSCRIPTIONS
                        ? "forged-subscriptions"
                        : source.subscriptionSnapshotIdentity(),
                forgery == EpochReceiptForgery.OUTBOX
                        ? Collections.singletonList("forged-outbox-event")
                        : source.rootEventBlueIds(),
                forgery == EpochReceiptForgery.GAS
                        ? source.totalGas() + 1L
                        : source.totalGas(),
                forgery == EpochReceiptForgery.TRANSITION
                        ? "forged-transition"
                        : source.transitionIdentity());
    }

    private enum ResultingSessionForgery {
        INITIAL_DOCUMENT,
        ENVIRONMENT,
        STATUS,
        EVENT_FRONTIER,
        SUBSCRIPTIONS
    }

    private enum EpochReceiptForgery {
        SESSION,
        EPOCH,
        ROOT,
        PRIOR_ROOT,
        EVENT,
        ORDER,
        INVENTORY,
        SUBSCRIPTIONS,
        OUTBOX,
        GAS,
        TRANSITION
    }

    @FunctionalInterface
    private interface ThrowingPlanConstruction {
        void run();
    }
}
