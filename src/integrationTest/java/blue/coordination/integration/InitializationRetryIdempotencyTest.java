package blue.coordination.integration;

import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Initialization events and epochs remain exactly-once across graph-publication retry. */
final class InitializationRetryIdempotencyTest {
    private static final String HOST_ID = "dynamic-path-host";
    private static final String GAME_ID = "round12-retry-game";

    @Test
    void retryPublishesOneChildInitializationWithoutRepeatingParentOperation()
            throws Exception {
        // given
        String gameYaml = Round12NbaFixtures.game(
                GAME_ID,
                "examples/round12/retry/game",
                "round12-retry-feed");
        String hostYaml = Round12NbaFixtures.dynamicPathHost(gameYaml)
                .replace(
                        "timelineId: examples/round12/dynamic/path/host",
                        "timelineId: examples/round12/retry/host")
                .replace("accountId: path-owner",
                        "accountId: round12-retry-owner");

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/retry/host",
                    "round12-retry-owner");
            engine.timeline(
                    "examples/round12/retry/game",
                    "round12-retry-feed");
            engine.start(HOST_ID, hostYaml);
            TimelineEntry activation = engine.append(
                    owner,
                    Operation.yaml(
                            "activateGameProcessing", "ownerChannel", "{}"));

            engine.failOnceAt(TestEngine.FailurePoint.AFTER_STAGING_CHILD_SESSION);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(activation));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(HOST_ID).status());
            assertThrows(CoordinationException.class,
                    () -> engine.readyDocument(HOST_ID));
            assertEquals(1L, engine.history(HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY)
                    .count(),
                    "the already committed parent operation must not repeat");

            engine.clearFailureInjection();

            // when
            engine.dispatch(activation);

            // then
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST_ID).status());
            assertEquals(1L, engine.history(HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY)
                    .count());
            assertEquals(1L, engine.history(HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                            .EMBEDDED_REVISION_APPLICATION)
                    .count());
            assertEquals(1, engine.history(GAME_ID).size());
            assertEquals(DocumentRevision.Kind.INITIALIZATION,
                    engine.history(GAME_ID).get(0).kind());
            assertEquals(1L, integer(engine, GAME_ID,
                    "/initializationCount"));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/gameInitializationEventCount"));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/revisionApplications"));
        }
    }

    @Test
    void failedParentInitializationApplicationDoesNotRollbackChildOrPeer()
            throws Exception {
        // given
        String gameId = "round12-isolation-game";
        String firstHostId = "round12-isolation-host-one";
        String secondHostId = "round12-isolation-host-two";
        String gameYaml = Round12NbaFixtures.game(
                gameId,
                "examples/round12/isolation/game",
                "round12-isolation-feed");
        String firstHostYaml = Round12NbaFixtures.host(
                firstHostId,
                "examples/round12/isolation/host-one",
                "round12-isolation-owner-one");
        String secondHostYaml = Round12NbaFixtures.host(
                secondHostId,
                "examples/round12/isolation/host-two",
                "round12-isolation-owner-two");

        try (TestEngine engine = TestEngine.create()) {
            engine.timeline(
                    "examples/round12/isolation/game",
                    "round12-isolation-feed");
            Timeline firstHost = engine.timeline(
                    "examples/round12/isolation/host-one",
                    "round12-isolation-owner-one");
            Timeline secondHost = engine.timeline(
                    "examples/round12/isolation/host-two",
                    "round12-isolation-owner-two");

            engine.start(firstHostId, firstHostYaml);
            engine.appendAndDispatch(
                    firstHost, Round12NbaFixtures.attach(gameYaml, engine));
            int childHistorySize = engine.history(gameId).size();
            int successfulParentHistorySize =
                    engine.history(firstHostId).size();
            assertEquals(1L, initializationRevisions(engine, gameId));
            assertEquals(1L, integer(engine, gameId,
                    "/initializationCount"));
            assertEquals(1L, integer(engine, firstHostId,
                    "/gameInitializationEventCount"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(firstHostId).status());

            engine.start(secondHostId, secondHostYaml);
            TimelineEntry secondAttachment = engine.append(
                    secondHost, Round12NbaFixtures.attach(gameYaml, engine));
            engine.failOnceAt(
                    TestEngine.FailurePoint.AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(secondAttachment));
            assertEquals(1L, timelineRevisions(engine, secondHostId));
            assertEquals(0L, embeddedApplications(engine, secondHostId));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(secondHostId).status());

            EngineMetrics.MetricsSnapshot beforeApplicationFailure =
                    engine.metricsSnapshot();
            engine.failOnceAt(
                    TestEngine.FailurePoint.AFTER_FROZEN_BEFORE_STAGE);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(secondAttachment));
            EngineTestSupport.MetricDelta failedApplication = delta(
                    beforeApplicationFailure, engine.metricsSnapshot());

            assertEquals(1L, failedApplication.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(0L, failedApplication.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(0L, failedApplication.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(0L, failedApplication.counter(
                    "temporal.parentEpochApplications"));
            assertEquals(childHistorySize, engine.history(gameId).size());
            assertEquals(1L, initializationRevisions(engine, gameId));
            assertEquals(1L, integer(engine, gameId,
                    "/initializationCount"));
            assertEquals(successfulParentHistorySize,
                    engine.history(firstHostId).size());
            assertEquals(1L, integer(engine, firstHostId,
                    "/gameInitializationEventCount"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(firstHostId).status());
            assertEquals(0L, embeddedApplications(engine, secondHostId));
            assertEquals(0L, integer(engine, secondHostId,
                    "/gameInitializationEventCount"));
            assertEquals(0L, integer(engine, secondHostId,
                    "/revisionApplications"));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(secondHostId).status());
            assertThrows(CoordinationException.class,
                    () -> engine.readyDocument(secondHostId));

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();

            // when
            engine.dispatch(secondAttachment);
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            // then
            assertEquals(0L, retry.counter("temporal.externalProcessCalls"));
            assertEquals(1L, retry.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(1L, retry.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(1L, retry.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(0L, retry.counter("childHistoricalProcessCalls"));
            assertEquals(childHistorySize, engine.history(gameId).size());
            assertEquals(successfulParentHistorySize,
                    engine.history(firstHostId).size());
            assertEquals(1L, embeddedApplications(engine, secondHostId));
            assertEquals(1L, integer(engine, secondHostId,
                    "/gameInitializationEventCount"));
            assertEquals(1L, integer(engine, secondHostId,
                    "/revisionApplications"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(secondHostId).status());
        }
    }

    @Test
    void restartBeforeParentApplicationReusesCommittedInitializationEpoch()
            throws Exception {
        // given
        String gameId = "round12-recovery-game";
        String firstHostId = "round12-recovery-host-one";
        String recoveringHostId = "round12-recovery-host-two";
        String gameYaml = Round12NbaFixtures.game(
                gameId,
                "examples/round12/recovery/game",
                "round12-recovery-feed");
        String firstHostYaml = Round12NbaFixtures.host(
                firstHostId,
                "examples/round12/recovery/host-one",
                "round12-recovery-owner-one");
        String recoveringHostYaml = Round12NbaFixtures.host(
                recoveringHostId,
                "examples/round12/recovery/host-two",
                "round12-recovery-owner-two");

        try (TestEngine engine = TestEngine.create()) {
            engine.timeline(
                    "examples/round12/recovery/game",
                    "round12-recovery-feed");
            Timeline firstHost = engine.timeline(
                    "examples/round12/recovery/host-one",
                    "round12-recovery-owner-one");
            Timeline recoveringHost = engine.timeline(
                    "examples/round12/recovery/host-two",
                    "round12-recovery-owner-two");

            engine.start(firstHostId, firstHostYaml);
            TimelineEntry firstAttachment = engine.append(
                    firstHost, Round12NbaFixtures.attach(gameYaml, engine));
            engine.dispatch(firstAttachment);
            int childHistorySize = engine.history(gameId).size();
            String initializationCause = engine.history(gameId).get(0)
                    .causalEntryBlueId().orElseThrow();
            assertEquals(1L, initializationRevisions(engine, gameId));
            assertEquals(firstAttachment.blueId(), initializationCause);
            assertEquals(1L, integer(engine, gameId,
                    "/initializationCount"));

            engine.start(recoveringHostId, recoveringHostYaml);
            TimelineEntry recoveringAttachment = engine.append(
                    recoveringHost,
                    Round12NbaFixtures.attach(gameYaml, engine));
            engine.failOnceAt(
                    TestEngine.FailurePoint.AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(recoveringAttachment));

            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(recoveringHostId).status());
            assertEquals(1L, timelineRevisions(engine, recoveringHostId));
            assertEquals(0L, embeddedApplications(
                    engine, recoveringHostId));
            assertEquals(0L, integer(engine, recoveringHostId,
                    "/gameInitializationEventCount"));
            assertEquals(childHistorySize, engine.history(gameId).size());
            assertEquals(1L, initializationRevisions(engine, gameId));
            assertEquals(initializationCause, engine.history(gameId).get(0)
                    .causalEntryBlueId().orElseThrow());

            // when
            engine.restartFromStores();
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(recoveringHostId).status());
            assertThrows(CoordinationException.class,
                    () -> engine.readyDocument(recoveringHostId));

            EngineMetrics.MetricsSnapshot beforeResume =
                    engine.metricsSnapshot();
            engine.dispatch(recoveringAttachment);
            EngineTestSupport.MetricDelta resume = delta(
                    beforeResume, engine.metricsSnapshot());

            // then
            assertEquals(0L, resume.counter(
                    "temporal.externalProcessCalls"),
                    "the committed attachment must not run again");
            assertEquals(1L, resume.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(1L, resume.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(1L, resume.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(0L, resume.counter("childHistoricalProcessCalls"));
            assertEquals(childHistorySize, engine.history(gameId).size());
            assertEquals(1L, initializationRevisions(engine, gameId));
            assertEquals(initializationCause, engine.history(gameId).get(0)
                    .causalEntryBlueId().orElseThrow());
            assertEquals(1L, integer(engine, gameId,
                    "/initializationCount"));
            assertEquals(1L, timelineRevisions(engine, recoveringHostId));
            assertEquals(1L, embeddedApplications(
                    engine, recoveringHostId));
            assertEquals(1L, integer(engine, recoveringHostId,
                    "/gameInitializationEventCount"));
            assertEquals(1L, integer(engine, recoveringHostId,
                    "/revisionApplications"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(recoveringHostId).status());
        }
    }

    @Test
    void postCommitInitializationRecoveryDoesNotCountTheReceiptTwice()
            throws Exception {
        // given
        String gameYaml = Round12NbaFixtures.game(
                GAME_ID,
                "examples/round12/retry/game",
                "round12-retry-feed");
        String hostYaml = Round12NbaFixtures.dynamicPathHost(gameYaml)
                .replace(
                        "timelineId: examples/round12/dynamic/path/host",
                        "timelineId: examples/round12/retry/host")
                .replace("accountId: path-owner",
                        "accountId: round12-retry-owner");

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/retry/host",
                    "round12-retry-owner");
            engine.timeline(
                    "examples/round12/retry/game",
                    "round12-retry-feed");
            engine.start(HOST_ID, hostYaml);
            TimelineEntry activation = engine.append(
                    owner,
                    Operation.yaml(
                            "activateGameProcessing", "ownerChannel", "{}"));

            EngineMetrics.MetricsSnapshot beforeFailure =
                    engine.metricsSnapshot();
            engine.failOnceAt(
                    TestEngine.FailurePoint.AFTER_APPLYING_CHILD_REVISION);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(activation));
            EngineTestSupport.MetricDelta committed = delta(
                    beforeFailure, engine.metricsSnapshot());

            assertEquals(1L, committed.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(1L, committed.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(1L, embeddedApplications(engine, HOST_ID));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/gameInitializationEventCount"));
            assertEquals(1L, initializationRevisions(engine, GAME_ID));
            assertEquals(1L, integer(engine, GAME_ID,
                    "/initializationCount"));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(HOST_ID).status());
            assertThrows(CoordinationException.class,
                    () -> engine.readyDocument(HOST_ID));

            // when
            engine.restartFromStores();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();
            engine.dispatch(activation);
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            // then
            assertEquals(0L, retry.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(0L, retry.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(0L, retry.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(1L, embeddedApplications(engine, HOST_ID));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/gameInitializationEventCount"));
            assertEquals(1L, initializationRevisions(engine, GAME_ID));
            assertEquals(1L, integer(engine, GAME_ID,
                    "/initializationCount"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST_ID).status());
        }
    }

    private static long initializationRevisions(
            TestEngine engine,
            String documentId) {
        return revisionCount(
                engine, documentId, DocumentRevision.Kind.INITIALIZATION);
    }

    private static long timelineRevisions(
            TestEngine engine,
            String documentId) {
        return revisionCount(
                engine, documentId, DocumentRevision.Kind.TIMELINE_ENTRY);
    }

    private static long embeddedApplications(
            TestEngine engine,
            String documentId) {
        return revisionCount(
                engine,
                documentId,
                DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION);
    }

    private static long revisionCount(
            TestEngine engine,
            String documentId,
            DocumentRevision.Kind kind) {
        return engine.history(documentId).stream()
                .filter(revision -> revision.kind() == kind)
                .count();
    }
}
