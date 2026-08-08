package blue.coordination.engine;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationAtomicCommitPlan;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DeliveryPlanningMode;
import blue.coordination.engine.api.DocumentAdmissionCommit;
import blue.coordination.engine.api.DocumentAdmissionResult;
import blue.coordination.engine.api.DocumentAdmissionStatus;
import blue.coordination.engine.api.DocumentEpochSnapshot;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentRemovalResult;
import blue.coordination.engine.api.DocumentRemovalStatus;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.LoadedProcessingBundle;
import blue.coordination.engine.api.ManagedDocumentSnapshot;
import blue.coordination.engine.api.ManagedDocumentStatus;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.ProcessRequest;
import blue.coordination.engine.api.ProcessingBundlePlanBinding;
import blue.coordination.engine.fastpath.PreparedRootContextCache;
import blue.coordination.engine.fastpath.PreparedRootExecutionContext;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.engine.memory.InMemoryCoordinationProcessingBundleLoader;
import blue.coordination.engine.memory.InMemoryCoordinationSessionStore;
import blue.coordination.engine.spi.CoordinationProcessingBundleLoader;
import blue.coordination.engine.spi.CoordinationSessionStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end characterization of the public storage-neutral engine facade. */
final class CoordinationProcessingEngineTest {

    private static final String CHANNEL_KEY = "timeline";
    private static final ExternalOrderKey ACTIVATION_ORDER = order(
            10L, "activation");
    private static final ExternalOrderKey EVENT_ORDER = order(
            20L, "timeline-entry");

    @Test
    void shouldCreateEpochZeroAndAttachTheSameCurrentRootIdempotently() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            Node exactRoot = harness.initializedRoot();

            // when
            DocumentAdmissionResult created = harness.engine.addDocument(
                    DocumentRegistration.openOrCreate(
                            sessionId, exactRoot, ACTIVATION_ORDER));
            int fragmentsAfterCreate =
                    harness.fragmentStore.physicalFragmentCount();
            DocumentAdmissionResult attached = harness.engine.addDocument(
                    DocumentRegistration.openOrCreate(
                            sessionId, exactRoot, ACTIVATION_ORDER));

            // then
            assertEquals(DocumentAdmissionStatus.CREATED, created.status());
            assertEquals(
                    DocumentAdmissionStatus.ATTACHED_CURRENT,
                    attached.status());
            assertEquals(0L, harness.engine.session(sessionId).currentEpoch());
            assertEquals(
                    harness.engine.session(sessionId).currentRootBlueId(),
                    harness.engine.epoch(sessionId, 0L).rootBlueId());
            assertEquals(
                    fragmentsAfterCreate,
                    harness.fragmentStore.physicalFragmentCount());
        }
    }

    @Test
    void shouldCommitASuccessfulProcessExactlyOnceAndReturnAlreadyCommittedOnRetry() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            Node before = harness.initializedRoot();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, before, ACTIVATION_ORDER));
            Node event = timelineEvent();
            ProcessRequest request = compatibilityRequest(
                    sessionId, 0L, event);

            // when
            CoordinationProcessingPlan plan = harness.engine.plan(request);
            CoordinationTransition transition = harness.engine.execute(plan);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    transition.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            transition.platformResult().processResult()));
            List<String> expectedOutbox = eventBlueIds(
                    transition.platformResult().processResult());
            CommitOutcome committed = harness.engine.commit(transition);
            ManagedDocumentSnapshot sessionAfterCommit =
                    harness.engine.session(sessionId);
            DocumentEpochSnapshot receiptAfterCommit =
                    harness.engine.epoch(sessionId, 1L);
            List<String> outboxAfterCommit =
                    harness.sessionStore.rootOutbox(sessionId);
            List<String> progressAfterCommit =
                    harness.sessionStore.terminalProgress(sessionId);
            CommitOutcome retried = harness.engine.commit(transition);

            // then
            assertEquals(CommitStatus.COMMITTED, committed.status());
            assertEquals(CommitStatus.ALREADY_COMMITTED, retried.status());
            assertEquals(0L, transition.beforeEpoch());
            assertEquals(1L, transition.afterEpoch());
            assertNotEquals(
                    transition.beforeRootBlueId(),
                    transition.afterRootBlueId());
            assertEquals(
                    BigInteger.valueOf(7L),
                    transition.platformResult().processResult()
                            .document().get("/counter"));
            assertEquals(1L, sessionAfterCommit.currentEpoch());
            assertEquals(
                    transition.afterRootBlueId(),
                    sessionAfterCommit.currentRootBlueId());
            assertEquals(
                    transition.afterRootBlueId(),
                    receiptAfterCommit.rootBlueId());
            assertEquals(
                    transition.beforeRootBlueId(),
                    receiptAfterCommit.priorRootBlueId());
            assertEquals(
                    transition.commitPlan().eventBlueId(),
                    receiptAfterCommit.causedByEventBlueId());
            assertEquals(expectedOutbox, receiptAfterCommit.rootEventBlueIds());
            assertEquals(expectedOutbox, outboxAfterCommit);
            assertEquals(
                    Collections.singletonList(
                            transition.commitPlan().eventBlueId()),
                    progressAfterCommit);
            assertEquals(1L, harness.engine.session(sessionId).currentEpoch());
            assertEquals(
                    transition.afterRootBlueId(),
                    harness.engine.session(sessionId).currentRootBlueId());
            assertEquals(outboxAfterCommit,
                    harness.sessionStore.rootOutbox(sessionId));
            assertEquals(progressAfterCommit,
                    harness.sessionStore.terminalProgress(sessionId));
            assertEquals(
                    receiptAfterCommit.transitionIdentity(),
                    harness.engine.epoch(sessionId, 1L)
                            .transitionIdentity());
        }
    }

    @Test
    void shouldRetainPreparedCandidateUntilExactCommitEvidenceExists() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "prepared-evidence-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId, 0L, timelineEvent())));
            CommitOutcome unsupportedClaim = new CommitOutcome(
                    CommitStatus.COMMITTED,
                    transition.commitPlan().resultingSession(),
                    transition.commitPlan().transitionIdentity());
            long loadsBeforeEvidence = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            long entriesBeforeEvidence = harness.engine
                    .planningProjectionCacheMetricsForTest().entries();

            // when
            boolean installedWithoutReceipt =
                    harness.engine.installPreparedRootContextAfterPublication(
                            transition, unsupportedClaim);
            long loadsAfterUnsupported = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            long entriesAfterUnsupported = harness.engine
                    .planningProjectionCacheMetricsForTest().entries();
            CommitOutcome committed = harness.engine.commit(transition);
            boolean installedAfterCommit =
                    harness.engine.installPreparedRootContextAfterPublication(
                            transition, committed);

            // then
            assertFalse(installedWithoutReceipt);
            assertEquals(loadsBeforeEvidence, loadsAfterUnsupported);
            assertEquals(entriesBeforeEvidence, entriesAfterUnsupported,
                    "a claimed outcome without authoritative receipt must "
                            + "neither publish nor consume the successor");
            assertEquals(CommitStatus.COMMITTED, committed.status());
            assertTrue(installedAfterCommit,
                    "invalid early evidence must not consume the candidate");
            assertEquals(loadsBeforeEvidence + 1L, harness.engine
                    .planningProjectionCacheMetricsForTest().loads(),
                    "the retained successor must publish after exact CAS "
                            + "evidence arrives");
        }
    }

    @Test
    void shouldContainPostCasSessionReadFailureAndRetainSuccessor() {
        try (Harness harness = Harness.openWithPostCasReadFailure()) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "post-cas-read-failure-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId, 0L, timelineEvent())));
            CommitOutcome committed = harness.engine.commit(transition);
            long loadsBeforeCallback = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            harness.failPostCasSessionReads();

            boolean installedDuringFailure = harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, committed);

            assertEquals(CommitStatus.COMMITTED, committed.status());
            assertFalse(installedDuringFailure,
                    "derived session probes must fail closed after the CAS");
            assertEquals(1L, harness.sessionStore.findSession(sessionId)
                    .get().currentEpoch(),
                    "the authoritative commit must remain visible");
            assertEquals(loadsBeforeCallback, harness.engine
                    .planningProjectionCacheMetricsForTest().loads(),
                    "a failed post-CAS probe must not publish the successor");

            harness.allowPostCasSessionReads();
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, committed),
                    "the failed observational callback must not consume the "
                            + "prepared generation");
            assertEquals(loadsBeforeCallback + 1L, harness.engine
                    .planningProjectionCacheMetricsForTest().loads());
        }
    }

    @Test
    void shouldRejectDelayedHistoricalAlreadyCommittedContext() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "historical-context-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            CoordinationTransition historical = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId, 0L, timelineEvent())));
            assertEquals(
                    CommitStatus.COMMITTED,
                    harness.engine.commit(historical).status());
            CoordinationTransition current = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId,
                            1L,
                            timelineEvent(
                                    "timeline-a",
                                    "actor-a",
                                    21L,
                                    "second"),
                            order(21L, "second"))));
            CommitOutcome currentOutcome = harness.engine.commit(current);
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            current, currentOutcome));

            // when
            CommitOutcome delayed = harness.engine.commit(historical);
            boolean historicalInstalled = harness.engine
                    .installPreparedRootContextAfterPublication(
                            historical, delayed);

            // then
            assertEquals(CommitStatus.ALREADY_COMMITTED, delayed.status());
            assertEquals(2L, harness.engine.session(sessionId).currentEpoch());
            assertFalse(historicalInstalled,
                    "historical ALREADY_COMMITTED evidence is not current");
        }
    }

    @Test
    void shouldNotEvictCurrentPreparedContextWhenAttachingHistoricalRoot()
            throws Exception {
        // given
        try (Harness harness = Harness.openWithCacheSize(1)) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "historical-attach-session");
            Node epochZero = harness.initializedRoot();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, epochZero, ACTIVATION_ORDER));
            ManagedDocumentSnapshot initial = harness.engine.session(
                    sessionId);
            PreparedRootExecutionContext historicalContext =
                    preparedContext(harness.engine, initial);
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId, 0L, timelineEvent())));
            CommitOutcome committed = harness.engine.commit(transition);
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, committed));
            ManagedDocumentSnapshot current = harness.engine.session(
                    sessionId);

            // when
            DocumentAdmissionResult attached = harness.engine.addDocument(
                    DocumentRegistration.openOrCreate(
                            sessionId, epochZero, ACTIVATION_ORDER));
            boolean staleCallbackInstalled = preparedContexts(
                    harness.engine).installIfCurrent(historicalContext);

            // then
            assertEquals(
                    DocumentAdmissionStatus.ATTACHED_TO_CURRENT,
                    attached.status());
            assertFalse(staleCallbackInstalled,
                    "the authoritative watermark rejects callback reordering");
            assertTrue(preparedContext(harness.engine, current) != null,
                    "historical attach must not replace the current context");
        }
    }

    @Test
    void shouldRejectAnUnboundLegacyBundleBeforeProcess() {
        // given
        BundleTransform transform = (session, plan, bundle) ->
                new LoadedProcessingBundle(
                        bundle.exactProvider(),
                        bundle.backendLoadedBlueIds(),
                        bundle.prefetchedBlueIds(),
                        bundle.batchCount(),
                        bundle.loadedBytes());

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executeWithBundleTransform(transform));

        // then
        assertEquals(
                "Processing bundle is not bound to an immutable plan",
                failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(BundleBindingMismatch.class)
    void shouldRejectEveryMismatchedBundleBindingBeforeProcess(
            BundleBindingMismatch mismatch) {
        // given
        BundleTransform transform = mismatchedBinding(mismatch);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> executeWithBundleTransform(transform));

        // then
        assertEquals(
                "Processing bundle does not bind the exact current session, "
                        + "epoch, Root, event, plan, subscriptions, and "
                        + "environment",
                failure.getMessage());
    }

    @Test
    void shouldDeduplicateEqualRootFragmentsWhileKeepingSessionsIndependent() {
        // given
        try (Harness harness = Harness.open()) {
            Node exactRoot = harness.initializedRoot();
            DocumentSessionId first = DocumentSessionId.of("session-a");
            DocumentSessionId second = DocumentSessionId.of("session-b");

            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    first, exactRoot, ACTIVATION_ORDER));
            int firstPhysicalCount =
                    harness.fragmentStore.physicalFragmentCount();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    second, exactRoot, ACTIVATION_ORDER));
            int secondPhysicalCount =
                    harness.fragmentStore.physicalFragmentCount();
            String sharedRootBlueId =
                    harness.engine.session(first).currentRootBlueId();

            // when
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            first, 0L, timelineEvent())));
            CommitOutcome outcome = harness.engine.commit(transition);

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    transition.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            transition.platformResult().processResult()));
            assertEquals(CommitStatus.COMMITTED, outcome.status());
            assertEquals(firstPhysicalCount, secondPhysicalCount);
            assertEquals(1L, harness.engine.session(first).currentEpoch());
            assertEquals(0L, harness.engine.session(second).currentEpoch());
            assertEquals(
                    transition.afterRootBlueId(),
                    harness.engine.session(first).currentRootBlueId());
            assertEquals(
                    sharedRootBlueId,
                    harness.engine.session(second).currentRootBlueId());
            assertNotEquals(
                    harness.engine.session(first).currentRootBlueId(),
                    harness.engine.session(second).currentRootBlueId());
            assertEquals(
                    eventBlueIds(transition.platformResult().processResult()),
                    harness.sessionStore.rootOutbox(first));
            assertEquals(
                    Collections.singletonList(
                            transition.commitPlan().eventBlueId()),
                    harness.sessionStore.terminalProgress(first));
            assertEquals(
                    Collections.emptyList(),
                    harness.sessionStore.rootOutbox(second));
            assertEquals(
                    Collections.emptyList(),
                    harness.sessionStore.terminalProgress(second));
        }
    }

    @Test
    void shouldCommitTerminalProgressWithoutAdvancingTheRootForNoMatch() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            Node before = harness.initializedRoot();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, before, ACTIVATION_ORDER));
            String beforeRootBlueId =
                    harness.engine.session(sessionId).currentRootBlueId();
            int processingViewsBefore =
                    harness.fragmentStore.processingViewCount();
            assertTrue(processingViewsBefore > 0,
                    "Fixture must retain a non-empty PROCESS surface");
            Node event = timelineEvent(
                    "unknown-timeline",
                    "unknown-actor",
                    30L,
                    "unmatched");
            ExternalOrderKey noMatchOrder = order(30L, "unmatched");

            // when
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId,
                            0L,
                            event,
                            noMatchOrder)));
            CommitOutcome outcome = harness.engine.commit(transition);

            // then
            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    transition.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            transition.platformResult().processResult()));
            assertEquals(CommitStatus.COMMITTED, outcome.status());
            assertTrue(transition.fragmentTransition()
                    .processingViews().isEmpty());
            assertEquals(
                    processingViewsBefore,
                    harness.fragmentStore.processingViewCount());
            assertEquals(0L, transition.beforeEpoch());
            assertEquals(0L, transition.afterEpoch());
            assertEquals(beforeRootBlueId, transition.beforeRootBlueId());
            assertEquals(beforeRootBlueId, transition.afterRootBlueId());
            assertEquals(0L,
                    harness.engine.session(sessionId).currentEpoch());
            assertEquals(beforeRootBlueId,
                    harness.engine.session(sessionId).currentRootBlueId());
            assertEquals(noMatchOrder,
                    harness.engine.session(sessionId).committedFrontier());
            assertFalse(harness.sessionStore.findEpoch(sessionId, 1L)
                    .isPresent());
            assertEquals(
                    Collections.emptyList(),
                    harness.sessionStore.rootOutbox(sessionId));
            assertEquals(
                    Collections.singletonList(
                            transition.commitPlan().eventBlueId()),
                    harness.sessionStore.terminalProgress(sessionId));
        }
    }

    @Test
    void shouldRejectCompetingProgressOnlyTransitionWithoutRegressingFrontier() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            ExternalOrderKey newerOrder = order(31L, "newer-unmatched");
            ExternalOrderKey olderOrder = order(30L, "older-unmatched");
            CoordinationTransition newer = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId,
                            0L,
                            timelineEvent(
                                    "unknown-timeline",
                                    "unknown-actor",
                                    31L,
                                    "newer-unmatched"),
                            newerOrder)));
            CoordinationTransition older = harness.engine.execute(
                    harness.engine.plan(compatibilityRequest(
                            sessionId,
                            0L,
                            timelineEvent(
                                    "unknown-timeline",
                                    "unknown-actor",
                                    30L,
                                    "older-unmatched"),
                            olderOrder)));

            // when
            CommitOutcome winningOutcome = harness.engine.commit(newer);
            CommitOutcome staleOutcome = harness.engine.commit(older);

            // then
            assertEquals(ProcessorStatus.NO_MATCH, newer.status());
            assertEquals(ProcessorStatus.NO_MATCH, older.status());
            assertEquals(CommitStatus.COMMITTED, winningOutcome.status());
            assertEquals(CommitStatus.CONFLICT, staleOutcome.status());
            assertEquals(newerOrder,
                    harness.engine.session(sessionId).committedFrontier());
            assertEquals(0L,
                    harness.engine.session(sessionId).currentEpoch());
            assertEquals(
                    Collections.singletonList(
                            newer.commitPlan().eventBlueId()),
                    harness.sessionStore.terminalProgress(sessionId));
            assertEquals(Collections.emptyList(),
                    harness.sessionStore.rootOutbox(sessionId));
        }
    }

    @Test
    void shouldRejectAStaleTransitionWithoutPartialAuthoritativeWrites() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            Node before = harness.initializedRoot();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, before, ACTIVATION_ORDER));
            ProcessRequest winningRequest = compatibilityRequest(
                    sessionId,
                    0L,
                    timelineEvent(
                            "timeline-a", "actor-a", 20L, "winner"),
                    order(20L, "winner"));
            ProcessRequest staleRequest = compatibilityRequest(
                    sessionId,
                    0L,
                    timelineEvent(
                            "timeline-a", "actor-a", 21L, "stale"),
                    order(21L, "stale"));
            CoordinationProcessingPlan winningPlan =
                    harness.engine.plan(winningRequest);
            CoordinationProcessingPlan stalePlan =
                    harness.engine.plan(staleRequest);
            CoordinationTransition winningTransition =
                    harness.engine.execute(winningPlan);
            CoordinationTransition staleTransition =
                    harness.engine.execute(stalePlan);
            long loadsBeforeCas = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            long entriesBeforeCas = harness.engine
                    .planningProjectionCacheMetricsForTest().entries();

            // when
            CommitOutcome winningOutcome =
                    harness.engine.commit(winningTransition);
            ManagedDocumentSnapshot sessionAfterWinner =
                    harness.engine.session(sessionId);
            DocumentEpochSnapshot receiptAfterWinner =
                    harness.engine.epoch(sessionId, 1L);
            List<String> outboxAfterWinner =
                    harness.sessionStore.rootOutbox(sessionId);
            List<String> progressAfterWinner =
                    harness.sessionStore.terminalProgress(sessionId);
            CommitOutcome staleOutcome =
                    harness.engine.commit(staleTransition);
            boolean staleInstalled = harness.engine
                    .installPreparedRootContextAfterPublication(
                            staleTransition, staleOutcome);
            long loadsAfterRejectedCallback = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            long entriesAfterRejectedCallback = harness.engine
                    .planningProjectionCacheMetricsForTest().entries();
            boolean winnerInstalled = harness.engine
                    .installPreparedRootContextAfterPublication(
                            winningTransition, winningOutcome);

            // then
            assertEquals(ProcessorStatus.SUCCESS,
                    winningTransition.status());
            assertEquals(ProcessorStatus.SUCCESS, staleTransition.status());
            assertEquals(CommitStatus.COMMITTED, winningOutcome.status());
            assertEquals(CommitStatus.CONFLICT, staleOutcome.status());
            assertFalse(staleOutcome.committed());
            assertFalse(staleInstalled,
                    "a losing CAS must not publish its prepared successor");
            assertEquals(loadsBeforeCas, loadsAfterRejectedCallback);
            assertEquals(entriesBeforeCas, entriesAfterRejectedCallback,
                    "the losing candidate must not enter the admitted "
                            + "projection cache");
            assertTrue(winnerInstalled);
            assertEquals(loadsBeforeCas + 1L, harness.engine
                    .planningProjectionCacheMetricsForTest().loads());
            assertNotEquals(
                    winningTransition.commitPlan().transitionIdentity(),
                    staleTransition.commitPlan().transitionIdentity());
            assertEquals(1L,
                    harness.engine.session(sessionId).currentEpoch());
            assertEquals(
                    sessionAfterWinner.currentRootBlueId(),
                    harness.engine.session(sessionId).currentRootBlueId());
            assertEquals(
                    sessionAfterWinner.committedFrontier(),
                    harness.engine.session(sessionId).committedFrontier());
            assertEquals(
                    sessionAfterWinner.fragmentInventoryIdentity(),
                    harness.engine.session(sessionId)
                            .fragmentInventoryIdentity());
            assertEquals(
                    sessionAfterWinner.subscriptions().digest(),
                    harness.engine.session(sessionId)
                            .subscriptions().digest());
            assertEquals(outboxAfterWinner,
                    harness.sessionStore.rootOutbox(sessionId));
            assertEquals(progressAfterWinner,
                    harness.sessionStore.terminalProgress(sessionId));
            assertEquals(
                    Collections.singletonList(
                            winningTransition.commitPlan().eventBlueId()),
                    progressAfterWinner);
            assertFalse(progressAfterWinner.contains(
                    staleTransition.commitPlan().eventBlueId()));
            assertEquals(
                    eventBlueIds(winningTransition.platformResult()
                            .processResult()),
                    outboxAfterWinner);
            assertEquals(
                    receiptAfterWinner.transitionIdentity(),
                    harness.engine.epoch(sessionId, 1L)
                            .transitionIdentity());
            assertFalse(harness.sessionStore.findEpoch(sessionId, 2L)
                    .isPresent());
        }
    }

    @Test
    void shouldRequireForkForAnUnknownClaimedFutureState() {
        // given
        try (Harness harness = Harness.open()) {
            DocumentSessionId sessionId = DocumentSessionId.of("session-a");
            Node exactRoot = harness.initializedRoot();
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId, exactRoot, ACTIVATION_ORDER));
            Node unknownFuture = exactRoot.clone().properties(
                    "futureMarker", new Node().value("unverified"));
            DocumentRegistration registration = new DocumentRegistration(
                    sessionId,
                    unknownFuture,
                    order(30L, "claimed-future"),
                    blue.coordination.engine.api.RegistrationMode
                            .ATTACH_EXISTING,
                    5L);

            // when
            DocumentAdmissionResult result =
                    harness.engine.addDocument(registration);

            // then
            assertEquals(DocumentAdmissionStatus.FORK_REQUIRED,
                    result.status());
            assertEquals(0L, harness.engine.session(sessionId).currentEpoch());
        }
    }

    @Test
    void shouldRemoveOnlyOneSessionAndRetainItsEpochHistory() {
        // given
        try (Harness harness = Harness.open()) {
            Node exactRoot = harness.initializedRoot();
            DocumentSessionId removed = DocumentSessionId.of("session-a");
            DocumentSessionId retained = DocumentSessionId.of("session-b");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    removed, exactRoot, ACTIVATION_ORDER));
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    retained, exactRoot, ACTIVATION_ORDER));
            int physicalCount =
                    harness.fragmentStore.physicalFragmentCount();

            // when
            DocumentRemovalStatus status = harness.engine.removeDocument(
                    removed, 0L).status();
            Map<String, Node> checkpointRoots =
                    harness.engine.checkpointCurrentRootViews(Arrays.asList(
                            harness.engine.session(removed),
                            harness.engine.session(retained)));

            // then
            assertEquals(DocumentRemovalStatus.REMOVED, status);
            assertEquals(
                    ManagedDocumentStatus.REMOVED,
                    harness.engine.session(removed).status());
            assertEquals(
                    ManagedDocumentStatus.ACTIVE,
                    harness.engine.session(retained).status());
            assertEquals(
                    harness.engine.session(removed).currentRootBlueId(),
                    harness.engine.epoch(removed, 0L).rootBlueId());
            assertEquals(
                    physicalCount,
                    harness.fragmentStore.physicalFragmentCount());
            assertEquals(1, checkpointRoots.size());
            assertEquals(
                    harness.engine.session(removed).currentRootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(
                            checkpointRoots.values().iterator().next()));
        }
    }

    private static ProcessRequest compatibilityRequest(
            DocumentSessionId sessionId,
            long expectedEpoch,
            Node event) {
        return compatibilityRequest(
                sessionId, expectedEpoch, event, EVENT_ORDER);
    }

    private static ProcessRequest compatibilityRequest(
            DocumentSessionId sessionId,
            long expectedEpoch,
            Node event,
            ExternalOrderKey eventOrderKey) {
        return new ProcessRequest(
                sessionId,
                expectedEpoch,
                event,
                eventOrderKey,
                DeliveryPlanningMode.CURRENT_ROOT_COMPATIBILITY,
                Collections.<String>emptyList(),
                PrefetchPolicy.BALANCED,
                true);
    }

    private static Node authoredRoot() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(
                CHANNEL_KEY,
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-a", "actor-a"));
        contracts.put(
                "workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        CHANNEL_KEY,
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "/counter",
                                        new Node().value(7)),
                        RepositoryIndependentCoordinationTypes
                                .triggerEventStep(
                                        RepositoryIndependentCoordinationTypes
                                                .chatMessage("completed"))));
        return new Node()
                .name("Storage-neutral engine Root")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node timelineEvent() {
        return timelineEvent(
                "timeline-a", "actor-a", 20L, "invoke");
    }

    private static Node timelineEvent(
            String timeline,
            String actor,
            long timestamp,
            String message) {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                timeline,
                actor,
                BigInteger.valueOf(timestamp),
                RepositoryIndependentCoordinationTypes.chatMessage(
                        message));
    }

    private static List<String> eventBlueIds(
            DocumentProcessingResult result) {
        List<String> blueIds = new ArrayList<String>();
        for (Node event : result.events()) {
            blueIds.add(DirectBlueIdCalculator.calculateBlueId(event));
        }
        return blueIds;
    }

    private static ExternalOrderKey order(long sequence, String label) {
        return ExternalOrderKey.of(Arrays.<Object>asList(sequence, label));
    }

    private static PreparedRootExecutionContext preparedContext(
            CoordinationProcessingEngine engine,
            ManagedDocumentSnapshot session) throws Exception {
        PreparedRootContextCache contexts = preparedContexts(engine);
        return contexts.get(
                session.sessionId().value(),
                session.currentEpoch(),
                session.currentRootBlueId(),
                session.fragmentInventoryIdentity());
    }

    private static PreparedRootContextCache preparedContexts(
            CoordinationProcessingEngine engine) throws Exception {
        Field field = CoordinationProcessingEngine.class.getDeclaredField(
                "preparedRootContexts");
        field.setAccessible(true);
        return (PreparedRootContextCache) field.get(engine);
    }

    private static void executeWithBundleTransform(
            BundleTransform transform) {
        try (Harness harness = Harness.open(transform)) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "bundle-binding-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            CoordinationProcessingPlan plan = harness.engine.plan(
                    compatibilityRequest(
                            sessionId,
                            0L,
                            timelineEvent()));
            harness.engine.execute(plan);
        }
    }

    private static BundleTransform mismatchedBinding(
            BundleBindingMismatch mismatch) {
        return (session, plan, bundle) -> {
            DocumentSessionId sessionId = session.sessionId();
            long epoch = session.currentEpoch();
            String rootBlueId = plan.rootReference().getBlueId();
            String eventBlueId = plan.eventReference().getBlueId();
            String planIdentity = plan.planIdentity();
            String subscriptionDigest = session.subscriptions().digest();
            String environmentIdentity = session.environmentIdentity();
            switch (mismatch) {
                case SESSION:
                    sessionId = DocumentSessionId.of("another-session");
                    break;
                case EPOCH:
                    epoch++;
                    break;
                case ROOT:
                    rootBlueId = eventBlueId;
                    break;
                case EVENT:
                    eventBlueId = rootBlueId;
                    break;
                case PLAN:
                    planIdentity = planIdentity + ":another";
                    break;
                case SUBSCRIPTIONS:
                    subscriptionDigest = subscriptionDigest + ":another";
                    break;
                case ENVIRONMENT:
                    environmentIdentity = environmentIdentity + ":another";
                    break;
                default:
                    throw new AssertionError(mismatch);
            }
            return new LoadedProcessingBundle(
                    bundle.exactProvider(),
                    bundle.backendLoadedBlueIds(),
                    bundle.prefetchedBlueIds(),
                    bundle.batchCount(),
                    bundle.loadedBytes(),
                    new ProcessingBundlePlanBinding(
                            sessionId,
                            epoch,
                            rootBlueId,
                            eventBlueId,
                            planIdentity,
                            subscriptionDigest,
                            environmentIdentity));
        };
    }

    private interface BundleTransform {
        LoadedProcessingBundle apply(
                ManagedDocumentSnapshot session,
                CoordinationProcessingPlan plan,
                LoadedProcessingBundle bundle);
    }

    private enum BundleBindingMismatch {
        SESSION,
        EPOCH,
        ROOT,
        EVENT,
        PLAN,
        SUBSCRIPTIONS,
        ENVIRONMENT
    }

    private static final class Harness implements AutoCloseable {
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final InMemoryCoordinationFragmentStore fragmentStore;
        private final InMemoryCoordinationSessionStore sessionStore;
        private final PostCasReadFailingSessionStore failingSessionStore;
        private final CoordinationProcessingEngine engine;

        private Harness() {
            this(null);
        }

        private Harness(BundleTransform transform) {
            this(
                    transform,
                    CoordinationProcessingEngine
                            .DEFAULT_ROOT_VIEW_CACHE_MAXIMUM_SIZE);
        }

        private Harness(BundleTransform transform, int cacheSize) {
            this(transform, cacheSize, false);
        }

        private Harness(
                BundleTransform transform,
                int cacheSize,
                boolean injectPostCasReadFailure) {
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            fragmentStore = new InMemoryCoordinationFragmentStore(
                    CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(fragmentStore);
            sessionStore = new InMemoryCoordinationSessionStore();
            failingSessionStore = injectPostCasReadFailure
                    ? new PostCasReadFailingSessionStore(sessionStore)
                    : null;
            CoordinationProcessingBundleLoader exactLoader =
                    new InMemoryCoordinationProcessingBundleLoader(
                            fragmentStore,
                            runtime.platformProcessor()
                                    .administration()
                                    .runtimeAccess()
                                    .languageRuntime()
                                    .getNodeProvider());
            CoordinationProcessingBundleLoader selectedLoader =
                    transform == null
                            ? exactLoader
                            : (session, plan, preferredBlueIds) ->
                            transform.apply(
                                    session,
                                    plan,
                                    exactLoader.load(
                                            session,
                                            plan,
                                            preferredBlueIds));
            engine = CoordinationProcessingEngine.builder()
                    .contracts(runtime.contracts())
                    .documentProcessor(runtime.platformProcessor())
                    .fragmentStore(fragmentStore)
                    .sessionStore(failingSessionStore == null
                            ? sessionStore
                            : failingSessionStore)
                    .bundleLoader(selectedLoader)
                    .rootViewCacheMaximumSize(cacheSize)
                    .providerEvidenceDomain(
                            "test:repository-independent-fragment-store")
                    .build();
        }

        private static Harness open() {
            return new Harness();
        }

        private static Harness open(BundleTransform transform) {
            return new Harness(transform);
        }

        private static Harness openWithCacheSize(int cacheSize) {
            return new Harness(null, cacheSize);
        }

        private static Harness openWithPostCasReadFailure() {
            return new Harness(
                    null,
                    CoordinationProcessingEngine
                            .DEFAULT_ROOT_VIEW_CACHE_MAXIMUM_SIZE,
                    true);
        }

        private void failPostCasSessionReads() {
            failingSessionStore.failReads();
        }

        private void allowPostCasSessionReads() {
            failingSessionStore.allowReads();
        }

        private Node initializedRoot() {
            DocumentProcessingResult initialized =
                    runtime.initializeDocument(authoredRoot());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            initialized));
            return initialized.document();
        }

        @Override
        public void close() {
            engine.close();
            runtime.close();
        }
    }

    private static final class PostCasReadFailingSessionStore
            implements CoordinationSessionStore {
        private final CoordinationSessionStore delegate;
        private boolean failReads;

        private PostCasReadFailingSessionStore(
                CoordinationSessionStore delegate) {
            this.delegate = delegate;
        }

        private void failReads() {
            failReads = true;
        }

        private void allowReads() {
            failReads = false;
        }

        @Override
        public Optional<ManagedDocumentSnapshot> findSession(
                DocumentSessionId id) {
            requireReadable();
            return delegate.findSession(id);
        }

        @Override
        public Optional<DocumentEpochSnapshot> findEpoch(
                DocumentSessionId id,
                long epoch) {
            requireReadable();
            return delegate.findEpoch(id, epoch);
        }

        @Override
        public DocumentAdmissionResult admit(DocumentAdmissionCommit commit) {
            return delegate.admit(commit);
        }

        @Override
        public CommitOutcome commit(CoordinationAtomicCommitPlan plan) {
            return delegate.commit(plan);
        }

        @Override
        public DocumentRemovalResult remove(
                DocumentSessionId id,
                long expectedEpoch) {
            return delegate.remove(id, expectedEpoch);
        }

        private void requireReadable() {
            if (failReads) {
                throw new IllegalStateException(
                        "injected post-CAS session-store read failure");
            }
        }
    }
}
