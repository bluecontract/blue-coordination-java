package blue.coordination.engine.memory;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentAdmissionStatus;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRemovalResult;
import blue.coordination.engine.api.DocumentRemovalStatus;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.RegistrationMode;
import blue.coordination.engine.spi.CoordinationSessionStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class CoordinationSessionStoreContract {

    abstract CoordinationSessionStore createStore();

    abstract List<String> rootOutbox(
            CoordinationSessionStore store,
            DocumentSessionId sessionId);

    abstract List<String> terminalProgress(
            CoordinationSessionStore store,
            DocumentSessionId sessionId);

    @Test
    void shouldCreateAnEpochZeroSessionAtomically() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-create", "initial");

        // when
        DocumentAdmissionResult result = store.admit(admission.commit);

        // then
        assertEquals(DocumentAdmissionStatus.CREATED, result.status());
        assertTrue(result.succeeded());
        assertEquals(admission.session, result.session().get());
        assertEquals(admission.session,
                store.findSession(admission.session.sessionId()).get());
        assertEquals(admission.epochZero,
                store.findEpoch(admission.session.sessionId(), 0L).get());
    }

    @Test
    void shouldAttachToTheCurrentExactStateWithoutCreatingAnotherEpoch() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture created =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-attach", "initial");
        store.admit(created.commit);
        CoordinationEngineStorageTestFixtures.AdmissionFixture attach =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-attach",
                        created.graph,
                        RegistrationMode.ATTACH_EXISTING,
                        null);

        // when
        DocumentAdmissionResult result = store.admit(attach.commit);

        // then
        assertEquals(DocumentAdmissionStatus.ATTACHED_CURRENT,
                result.status());
        assertEquals(created.session, result.session().get());
        assertFalse(store.findEpoch(created.session.sessionId(), 1L)
                .isPresent());
    }

    @Test
    void shouldRejectCreateOnlyWhenTheSessionAlreadyExists() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture created =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-create-only", "initial");
        store.admit(created.commit);
        CoordinationEngineStorageTestFixtures.AdmissionFixture duplicate =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-create-only",
                        created.graph,
                        RegistrationMode.CREATE_ONLY,
                        null);

        // when
        DocumentAdmissionResult result = store.admit(duplicate.commit);

        // then
        assertEquals(DocumentAdmissionStatus.CONFLICT, result.status());
        assertFalse(result.succeeded());
        assertTrue(result.diagnostic().get().contains("already exists"));
    }

    @Test
    void shouldRejectAttachExistingWhenTheSessionIsAbsent() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("absent");
        CoordinationEngineStorageTestFixtures.AdmissionFixture attach =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-absent",
                        graph,
                        RegistrationMode.ATTACH_EXISTING,
                        null);

        // when
        DocumentAdmissionResult result = store.admit(attach.commit);

        // then
        assertEquals(DocumentAdmissionStatus.CONFLICT, result.status());
        assertFalse(result.session().isPresent());
        assertFalse(store.findSession(attach.session.sessionId()).isPresent());
    }

    @Test
    void shouldCommitTheNewRootAndEpochWithRevisionBoundCas() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-commit", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-commit");

        // when
        CommitOutcome result = store.commit(transition.plan);

        // then
        assertEquals(CommitStatus.COMMITTED, result.status());
        assertTrue(result.committed());
        assertEquals(1L, result.session().get().currentEpoch());
        assertEquals(transition.after.inventory.rootBlueId(),
                result.session().get().currentRootBlueId());
        assertEquals(transition.plan.resultingEpochSnapshot(),
                store.findEpoch(admission.session.sessionId(), 1L).get());
        assertEquals(
                transition.plan.resultingSession()
                        .subscriptions().toMap(),
                result.session().get().subscriptions().toMap());
        assertEquals(
                transition.plan.resultingSession()
                        .subscriptions().digest(),
                store.findEpoch(
                        admission.session.sessionId(),
                        1L).get().subscriptionSnapshotIdentity());
        assertEquals(
                transition.plan.rootOutboxEventBlueIds(),
                rootOutbox(store, admission.session.sessionId()));
        assertEquals(
                Collections.singletonList(transition.plan.eventBlueId()),
                terminalProgress(store, admission.session.sessionId()));
    }

    @Test
    void shouldReturnAlreadyCommittedWithoutApplyingATransitionTwice() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-idempotent", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-idempotent");
        CommitOutcome first = store.commit(transition.plan);

        // when
        CommitOutcome repeated = store.commit(transition.plan);

        // then
        assertEquals(CommitStatus.COMMITTED, first.status());
        assertEquals(CommitStatus.ALREADY_COMMITTED, repeated.status());
        assertEquals(first.session(), repeated.session());
        assertEquals(1L,
                store.findSession(admission.session.sessionId())
                        .get().currentEpoch());
        assertEquals(
                transition.plan.rootOutboxEventBlueIds(),
                rootOutbox(store, admission.session.sessionId()));
        assertEquals(
                Collections.singletonList(transition.plan.eventBlueId()),
                terminalProgress(store, admission.session.sessionId()));
    }

    @Test
    void shouldRetrySafelyAfterAFaultBeforeTheAtomicCommitBoundary() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-fault-retry", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-fault-retry");
        StoreSnapshot beforeFault = snapshot(
                store,
                admission.session.sessionId(),
                1L);
        CoordinationSessionStore failOnce =
                new FailBeforeCommitSessionStore(store);

        // when
        assertThrows(
                InjectedCommitFailure.class,
                () -> failOnce.commit(transition.plan));
        StoreSnapshot afterFault = snapshot(
                store,
                admission.session.sessionId(),
                1L);
        CommitOutcome retry = failOnce.commit(transition.plan);
        CommitOutcome repeated = failOnce.commit(transition.plan);

        // then
        assertEquals(beforeFault, afterFault);
        assertEquals(CommitStatus.COMMITTED, retry.status());
        assertEquals(CommitStatus.ALREADY_COMMITTED, repeated.status());
        assertEquals(
                transition.plan.rootOutboxEventBlueIds(),
                rootOutbox(store, admission.session.sessionId()));
        assertEquals(
                Collections.singletonList(transition.plan.eventBlueId()),
                terminalProgress(store, admission.session.sessionId()));
    }

    @Test
    void shouldRejectASecondTransitionPlannedFromAStaleEpoch() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-stale", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture winning =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "winner",
                        "transition-winner");
        CoordinationEngineStorageTestFixtures.CommitFixture stale =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "stale",
                        "transition-stale");
        store.commit(winning.plan);
        StoreSnapshot beforeConflict = snapshot(
                store,
                admission.session.sessionId(),
                1L);

        // when
        CommitOutcome result = store.commit(stale.plan);

        // then
        assertEquals(CommitStatus.CONFLICT, result.status());
        assertFalse(result.committed());
        assertEquals(winning.after.inventory.rootBlueId(),
                result.session().get().currentRootBlueId());
        assertEquals(
                beforeConflict,
                snapshot(
                        store,
                        admission.session.sessionId(),
                        1L));
    }

    @Test
    void shouldAdvanceProgressWithoutCreatingARootEpoch() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-progress", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture progress =
                CoordinationEngineStorageTestFixtures.progressOnlyCommit(
                        admission,
                        "rejected-event",
                        "transition-progress");

        // when
        CommitOutcome result = store.commit(progress.plan);

        // then
        assertEquals(CommitStatus.COMMITTED, result.status());
        assertEquals(0L, result.session().get().currentEpoch());
        assertEquals(admission.session.currentRootBlueId(),
                result.session().get().currentRootBlueId());
        assertFalse(store.findEpoch(admission.session.sessionId(), 1L)
                .isPresent());
        assertEquals(
                admission.session.subscriptions().toMap(),
                result.session().get().subscriptions().toMap());
        assertTrue(rootOutbox(
                store,
                admission.session.sessionId()).isEmpty());
        assertEquals(
                Collections.singletonList(progress.plan.eventBlueId()),
                terminalProgress(
                        store,
                        admission.session.sessionId()));
    }

    @Test
    void shouldAllowOnlyOneCompetingProgressOnlyPlanToAdvanceTheFrontier() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-competing-progress", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture winner =
                CoordinationEngineStorageTestFixtures.progressOnlyCommit(
                        admission,
                        "newer-event",
                        "transition-newer-progress",
                        2L);
        CoordinationEngineStorageTestFixtures.CommitFixture stale =
                CoordinationEngineStorageTestFixtures.progressOnlyCommit(
                        admission,
                        "older-event",
                        "transition-stale-progress",
                        1L);

        // when
        CommitOutcome winningOutcome = store.commit(winner.plan);
        CommitOutcome staleOutcome = store.commit(stale.plan);

        // then
        assertEquals(CommitStatus.COMMITTED, winningOutcome.status());
        assertEquals(CommitStatus.CONFLICT, staleOutcome.status());
        assertEquals(
                winner.plan.eventOrderKey(),
                store.findSession(admission.session.sessionId())
                        .get().committedFrontier());
        assertEquals(
                Collections.singletonList(winner.plan.eventBlueId()),
                terminalProgress(store, admission.session.sessionId()));
        assertTrue(rootOutbox(
                store,
                admission.session.sessionId()).isEmpty());
    }

    @Test
    void shouldRejectEveryMismatchedExpectedSessionIdentity() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-expected-identity", "before");
        store.admit(admission.commit);
        CoordinationAtomicCommitPlan exact =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-expected-identity").plan;
        CoordinationAtomicCommitPlan wrongInventory =
                copyWithExpectedState(
                        exact,
                        "forged-inventory",
                        exact.expectedSubscriptionSnapshotIdentity());
        CoordinationAtomicCommitPlan wrongSubscriptions =
                copyWithExpectedState(
                        exact,
                        exact.expectedFragmentInventoryIdentity(),
                        "forged-subscriptions");

        // when
        CommitOutcome inventoryOutcome = store.commit(wrongInventory);
        CommitOutcome subscriptionOutcome = store.commit(wrongSubscriptions);

        // then
        assertEquals(CommitStatus.CONFLICT, inventoryOutcome.status());
        assertEquals(CommitStatus.CONFLICT, subscriptionOutcome.status());
        assertEquals(
                admission.session,
                store.findSession(admission.session.sessionId()).get());
        assertTrue(rootOutbox(
                store,
                admission.session.sessionId()).isEmpty());
        assertTrue(terminalProgress(
                store,
                admission.session.sessionId()).isEmpty());
    }

    private static CoordinationAtomicCommitPlan copyWithExpectedState(
            CoordinationAtomicCommitPlan source,
            String expectedInventoryIdentity,
            String expectedSubscriptionIdentity) {
        return new CoordinationAtomicCommitPlan(
                source.sessionId(),
                source.expectedEpoch(),
                source.expectedRootBlueId(),
                source.expectedInitialDocumentBlueId(),
                source.expectedEnvironmentIdentity(),
                source.expectedCommittedFrontier(),
                expectedInventoryIdentity,
                expectedSubscriptionIdentity,
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
                source.resultingEpochSnapshot());
    }

    @Test
    void shouldRecognizeAHistoricalExactStateAfterTheSessionAdvances() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture initial =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-history", "before");
        store.admit(initial.commit);
        store.commit(CoordinationEngineStorageTestFixtures.successfulCommit(
                initial,
                "after",
                "transition-history").plan);
        CoordinationEngineStorageTestFixtures.AdmissionFixture historical =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-history",
                        initial.graph,
                        RegistrationMode.ATTACH_EXISTING,
                        0L);

        // when
        DocumentAdmissionResult result = store.admit(historical.commit);

        // then
        assertEquals(DocumentAdmissionStatus.ATTACHED_TO_CURRENT,
                result.status());
        assertEquals(1L, result.session().get().currentEpoch());
        assertTrue(result.diagnostic().get().contains("historical epoch 0"));
        assertEquals(
                initial.epochZero,
                store.findEpoch(initial.session.sessionId(), 0L).get());
        assertTrue(store.findEpoch(
                initial.session.sessionId(), 1L).isPresent());
    }

    @Test
    void shouldRequireVerifiedLineageForAnUnknownUnclaimedState() {
        // given
        CoordinationSessionStore store = advancedStore("session-lineage");
        CoordinationEngineStorageTestFixtures.AdmissionFixture unknown =
                unknownAdmission(
                        "session-lineage",
                        RegistrationMode.OPEN_OR_CREATE,
                        null);

        // when
        DocumentAdmissionResult result = store.admit(unknown.commit);

        // then
        assertEquals(DocumentAdmissionStatus.VERIFIED_LINEAGE_REQUIRED,
                result.status());
        assertFalse(result.succeeded());
    }

    @Test
    void shouldRequireAForkForAnUnknownClaimedNewerState() {
        // given
        CoordinationSessionStore store = advancedStore("session-fork");
        CoordinationEngineStorageTestFixtures.AdmissionFixture unknown =
                unknownAdmission(
                        "session-fork",
                        RegistrationMode.OPEN_OR_CREATE,
                        2L);

        // when
        DocumentAdmissionResult result = store.admit(unknown.commit);

        // then
        assertEquals(DocumentAdmissionStatus.FORK_REQUIRED, result.status());
        assertFalse(result.succeeded());
    }

    @Test
    void shouldRejectAnUnknownClaimedHistoricalState() {
        // given
        CoordinationSessionStore store = advancedStore("session-unknown");
        CoordinationEngineStorageTestFixtures.AdmissionFixture unknown =
                unknownAdmission(
                        "session-unknown",
                        RegistrationMode.OPEN_OR_CREATE,
                        0L);

        // when
        DocumentAdmissionResult result = store.admit(unknown.commit);

        // then
        assertEquals(DocumentAdmissionStatus.CONFLICT, result.status());
        assertFalse(result.succeeded());
    }

    @Test
    void shouldRejectRemovalAtAStaleEpochWithoutChangingSession() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-remove-stale", "before");
        store.admit(admission.commit);
        StoreSnapshot beforeRemoval = snapshot(
                store,
                admission.session.sessionId(),
                0L);

        // when
        DocumentRemovalResult result = store.remove(
                admission.session.sessionId(), 1L);

        // then
        assertEquals(DocumentRemovalStatus.CONFLICT, result.status());
        assertEquals(
                beforeRemoval,
                snapshot(
                        store,
                        admission.session.sessionId(),
                        0L));
    }

    @Test
    void shouldRemoveAtTheExpectedEpochAndRetainHistory() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-remove", "before");
        store.admit(admission.commit);

        // when
        DocumentRemovalResult result = store.remove(
                admission.session.sessionId(), 0L);

        // then
        assertEquals(DocumentRemovalStatus.REMOVED, result.status());
        assertEquals(ManagedDocumentStatus.REMOVED,
                result.session().get().status());
        assertEquals(ManagedDocumentStatus.REMOVED,
                store.findSession(admission.session.sessionId())
                        .get().status());
        assertEquals(
                admission.epochZero,
                store.findEpoch(
                        admission.session.sessionId(),
                        0L).get());
    }

    @Test
    void shouldReturnAlreadyRemovedForAnExactRemovalRetry() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-remove-retry", "before");
        store.admit(admission.commit);
        store.remove(admission.session.sessionId(), 0L);
        StoreSnapshot afterRemoval = snapshot(
                store,
                admission.session.sessionId(),
                0L);

        // when
        DocumentRemovalResult result = store.remove(
                admission.session.sessionId(), 0L);

        // then
        assertEquals(DocumentRemovalStatus.ALREADY_REMOVED,
                result.status());
        assertEquals(
                afterRemoval,
                snapshot(
                        store,
                        admission.session.sessionId(),
                        0L));
    }

    @Test
    void shouldReportNotFoundWhenRemovingAnUnknownSession() {
        // given
        CoordinationSessionStore store = createStore();
        DocumentSessionId unknown = DocumentSessionId.of("unknown-session");

        // when
        DocumentRemovalResult result = store.remove(unknown, 0L);

        // then
        assertEquals(DocumentRemovalStatus.NOT_FOUND, result.status());
        assertFalse(result.session().isPresent());
    }

    @Test
    void shouldRejectCommitAfterTheSessionWasRemoved() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture admission =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-removed-commit", "before");
        store.admit(admission.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture transition =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        admission,
                        "after",
                        "transition-after-removal");
        store.remove(admission.session.sessionId(), 0L);

        // when
        CommitOutcome result = store.commit(transition.plan);

        // then
        assertEquals(CommitStatus.CONFLICT, result.status());
        assertEquals(ManagedDocumentStatus.REMOVED,
                result.session().get().status());
    }

    @Test
    void shouldIsolateIdenticalTransitionIdentitiesAcrossSessions() {
        // given
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture first =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-a", "shared-before");
        CoordinationEngineStorageTestFixtures.AdmissionFixture second =
                CoordinationEngineStorageTestFixtures.admission(
                        "session-b", "shared-before");
        store.admit(first.commit);
        store.admit(second.commit);
        CoordinationEngineStorageTestFixtures.CommitFixture firstPlan =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        first,
                        "shared-after",
                        "shared-transition");
        CoordinationEngineStorageTestFixtures.CommitFixture secondPlan =
                CoordinationEngineStorageTestFixtures.successfulCommit(
                        second,
                        "shared-after",
                        "shared-transition");

        // when
        CommitOutcome firstResult = store.commit(firstPlan.plan);
        CommitOutcome secondResult = store.commit(secondPlan.plan);

        // then
        assertEquals(CommitStatus.COMMITTED, firstResult.status());
        assertEquals(CommitStatus.COMMITTED, secondResult.status());
        assertEquals(first.session.sessionId(),
                firstResult.session().get().sessionId());
        assertEquals(second.session.sessionId(),
                secondResult.session().get().sessionId());
    }

    @Test
    void shouldRejectNegativeEpochLookupsAndRemovalRevisions() {
        // given
        CoordinationSessionStore store = createStore();
        DocumentSessionId sessionId = DocumentSessionId.of("negative");

        // when
        IllegalArgumentException lookupFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.findEpoch(sessionId, -1L));
        IllegalArgumentException removalFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.remove(sessionId, -1L));

        // then
        assertTrue(lookupFailure.getMessage().contains("non-negative"));
        assertTrue(removalFailure.getMessage().contains("non-negative"));
    }

    private CoordinationSessionStore advancedStore(String sessionValue) {
        CoordinationSessionStore store = createStore();
        CoordinationEngineStorageTestFixtures.AdmissionFixture initial =
                CoordinationEngineStorageTestFixtures.admission(
                        sessionValue, "before");
        store.admit(initial.commit);
        store.commit(CoordinationEngineStorageTestFixtures.successfulCommit(
                initial,
                "after",
                "advance:" + sessionValue).plan);
        return store;
    }

    private static CoordinationEngineStorageTestFixtures.AdmissionFixture
    unknownAdmission(
            String sessionValue,
            RegistrationMode mode,
            Long claimedEpoch) {
        return CoordinationEngineStorageTestFixtures.admission(
                sessionValue,
                CoordinationEngineStorageTestFixtures.graph("unknown"),
                mode,
                claimedEpoch);
    }

    private StoreSnapshot snapshot(
            CoordinationSessionStore store,
            DocumentSessionId sessionId,
            long maximumEpoch) {
        Map<String, Object> session = new LinkedHashMap<String, Object>();
        Optional<ManagedDocumentSnapshot> current =
                store.findSession(sessionId);
        if (current.isPresent()) {
            ManagedDocumentSnapshot value = current.get();
            session.put("sessionId", value.sessionId().value());
            session.put("initialDocumentBlueId",
                    value.initialDocumentBlueId());
            session.put("currentRootBlueId", value.currentRootBlueId());
            session.put("currentEpoch", value.currentEpoch());
            session.put("environmentIdentity",
                    value.environmentIdentity());
            session.put("committedFrontier",
                    value.committedFrontier().components());
            session.put("fragmentInventoryIdentity",
                    value.fragmentInventoryIdentity());
            session.put("subscriptions", value.subscriptions().toMap());
            session.put("status", value.status().name());
        }
        List<Map<String, Object>> history =
                new ArrayList<Map<String, Object>>();
        for (long epoch = 0L; epoch <= maximumEpoch; epoch++) {
            Optional<DocumentEpochSnapshot> found =
                    store.findEpoch(sessionId, epoch);
            if (found.isPresent()) {
                history.add(epochSnapshot(found.get()));
            }
        }
        return new StoreSnapshot(
                session,
                history,
                rootOutbox(store, sessionId),
                terminalProgress(store, sessionId));
    }

    private static Map<String, Object> epochSnapshot(
            DocumentEpochSnapshot value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", value.sessionId().value());
        result.put("epoch", value.epoch());
        result.put("rootBlueId", value.rootBlueId());
        result.put("priorRootBlueId", value.priorRootBlueId());
        result.put("causedByEventBlueId", value.causedByEventBlueId());
        result.put(
                "eventOrderKey",
                value.eventOrderKey() == null
                        ? null
                        : value.eventOrderKey().components());
        result.put("fragmentInventoryIdentity",
                value.fragmentInventoryIdentity());
        result.put("subscriptionSnapshotIdentity",
                value.subscriptionSnapshotIdentity());
        result.put("rootEventBlueIds",
                new ArrayList<String>(value.rootEventBlueIds()));
        result.put("totalGas", value.totalGas());
        result.put("transitionIdentity", value.transitionIdentity());
        return Collections.unmodifiableMap(result);
    }

    private static final class StoreSnapshot {
        private final Map<String, Object> session;
        private final List<Map<String, Object>> history;
        private final List<String> rootOutbox;
        private final List<String> terminalProgress;

        private StoreSnapshot(
                Map<String, Object> session,
                List<Map<String, Object>> history,
                List<String> rootOutbox,
                List<String> terminalProgress) {
            this.session = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(session));
            this.history = Collections.unmodifiableList(
                    new ArrayList<Map<String, Object>>(history));
            this.rootOutbox = Collections.unmodifiableList(
                    new ArrayList<String>(rootOutbox));
            this.terminalProgress = Collections.unmodifiableList(
                    new ArrayList<String>(terminalProgress));
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof StoreSnapshot)) {
                return false;
            }
            StoreSnapshot value = (StoreSnapshot) other;
            return session.equals(value.session)
                    && history.equals(value.history)
                    && rootOutbox.equals(value.rootOutbox)
                    && terminalProgress.equals(value.terminalProgress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    session,
                    history,
                    rootOutbox,
                    terminalProgress);
        }
    }

    private static final class FailBeforeCommitSessionStore
            implements CoordinationSessionStore {
        private final CoordinationSessionStore delegate;
        private boolean fail = true;

        private FailBeforeCommitSessionStore(
                CoordinationSessionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<ManagedDocumentSnapshot> findSession(
                DocumentSessionId id) {
            return delegate.findSession(id);
        }

        @Override
        public Optional<DocumentEpochSnapshot> findEpoch(
                DocumentSessionId id,
                long epoch) {
            return delegate.findEpoch(id, epoch);
        }

        @Override
        public DocumentAdmissionResult admit(
                DocumentAdmissionCommit commit) {
            return delegate.admit(commit);
        }

        @Override
        public CommitOutcome commit(CoordinationAtomicCommitPlan plan) {
            if (fail) {
                fail = false;
                throw new InjectedCommitFailure();
            }
            return delegate.commit(plan);
        }

        @Override
        public DocumentRemovalResult remove(
                DocumentSessionId id,
                long expectedEpoch) {
            return delegate.remove(id, expectedEpoch);
        }
    }

    private static final class InjectedCommitFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
