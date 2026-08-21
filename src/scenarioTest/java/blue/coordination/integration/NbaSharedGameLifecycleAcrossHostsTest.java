package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One managed Game initializes and progresses once while three containing hosts
 * consume the same ordered initialization and business epochs.
 */
@Tag("scenario")
final class NbaSharedGameLifecycleAcrossHostsTest {
    private static final String GAME_ID = "round12-shared-game";
    private static final String HOST_ONE = "round12-host-one";
    private static final String HOST_TWO = "round12-host-two";
    private static final String HOST_THREE = "round12-host-three";

    @Test
    void laterAndPostFinalHostsReuseGameAndReceiveRetainedLifecycleEvents()
            throws Exception {
        // given
        LifecycleFixture fixture = LifecycleFixture.create();

        // when
        EngineTestSupport.MetricDelta total = runLifecycle(fixture);

        // then
        assertEquals(1L, total.counter(
                "embedding.childSessionsCreated"));
        assertEquals(2L, total.counter(
                "embedding.childSessionsReused"));
        assertEquals(3L, total.counter(
                "temporal.initializationEpochApplications"));
        assertEquals(3L, total.counter(
                "temporal.initializationEventOccurrencesForwarded"));
        assertEquals(15L, total.counter(
                "temporal.parentEpochApplications"));
        assertNoGenericSplitting(total);
    }

    private static EngineTestSupport.MetricDelta runLifecycle(
            LifecycleFixture fixture) throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            Round12NbaEvidence evidence = Round12NbaEvidence.begin(engine);
            Timelines timelines = registerTimelines(engine);

            String initializationEventBlueId = admitFirstHostAndStartGame(
                    engine, evidence, timelines, fixture);
            admitSecondHostDuringLiveGame(
                    engine,
                    evidence,
                    timelines.hostTwo(),
                    fixture,
                    initializationEventBlueId);
            String endingEventBlueId = finishGameForFirstTwoHosts(
                    engine, evidence, timelines.game());
            admitThirdHostAfterFinal(
                    engine,
                    evidence,
                    timelines.hostThree(),
                    fixture,
                    before,
                    initializationEventBlueId,
                    endingEventBlueId);

            return delta(before, engine.metricsSnapshot());
        }
    }

    private static String admitFirstHostAndStartGame(
            TestEngine engine,
            Round12NbaEvidence evidence,
            Timelines timelines,
            LifecycleFixture fixture) {
        evidence.admit("Host 1 admission", HOST_ONE,
                () -> engine.start(HOST_ONE, fixture.hostOneYaml()));
        assertEquals(1L, integer(engine, HOST_ONE,
                "/hostInitializationCount"));
        assertEquals(1, engine.history(HOST_ONE).size());
        assertEquals(DocumentRevision.Kind.INITIALIZATION,
                engine.history(HOST_ONE).get(0).kind());

        TimelineEntry firstAttachment = evidence.dispatch(
                "Host 1 attachment",
                HOST_ONE,
                timelines.hostOne(),
                Round12NbaFixtures.attach(fixture.gameYaml(), engine));
        assertGameInitialization(engine, firstAttachment);

        String initializationEventBlueId = DirectBlueIdCalculator
                .calculateBlueId(engine.history(GAME_ID).get(0)
                        .emittedEvents().get(0));
        assertHost(engine, HOST_ONE, 1L, false, 0L,
                0L, 0L, 0L, "Scheduled", 1L);
        assertEquals(initializationEventBlueId, text(
                engine, HOST_ONE, "/gameInitializationEventBlueId"));

        dispatchGameOnce(evidence, "Game start", engine, timelines.game(),
                Round12NbaFixtures.startGame());
        dispatchGameOnce(evidence, "Game first scoring operation",
                engine, timelines.game(), Round12NbaFixtures.homeScores(2));
        assertHost(engine, HOST_ONE, 1L, false, 0L,
                2L, 0L, 1L, "Live", 3L);
        return initializationEventBlueId;
    }

    private static void admitSecondHostDuringLiveGame(
            TestEngine engine,
            Round12NbaEvidence evidence,
            Timeline hostTwo,
            LifecycleFixture fixture,
            String initializationEventBlueId) {
        long gameEpochBeforeAttachment = engine.session(GAME_ID).epoch();
        int gameHistoryBeforeAttachment = engine.history(GAME_ID).size();
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

        evidence.admit("Host 2 admission", HOST_TWO,
                () -> engine.start(HOST_TWO, fixture.hostTwoYaml()));
        evidence.dispatch(
                "Host 2 attachment/catch-up",
                HOST_TWO,
                hostTwo,
                Round12NbaFixtures.attach(fixture.gameYaml(), engine));

        assertEquals(gameEpochBeforeAttachment,
                engine.session(GAME_ID).epoch(),
                "attaching another host must not advance the Game");
        assertEquals(gameHistoryBeforeAttachment,
                engine.history(GAME_ID).size(),
                "attaching another host must not initialize or replay the Game");
        assertHost(engine, HOST_TWO, 1L, false, 0L,
                2L, 0L, 1L, "Live", 3L);
        assertEquals(initializationEventBlueId, text(
                engine, HOST_TWO, "/gameInitializationEventBlueId"));

        EngineTestSupport.MetricDelta attachmentWork = delta(
                before, engine.metricsSnapshot());
        assertEquals(0L, attachmentWork.counter(
                "embedding.childSessionsCreated"));
        assertEquals(1L, attachmentWork.counter(
                "embedding.childSessionsReused"));
        assertEquals(3L, attachmentWork.counter(
                "temporal.parentEpochApplications"));
    }

    private static String finishGameForFirstTwoHosts(
            TestEngine engine,
            Round12NbaEvidence evidence,
            Timeline game) {
        dispatchGameOnce(evidence, "Game second scoring operation",
                engine, game, Round12NbaFixtures.awayScores(3));
        dispatchGameOnce(evidence, "Game end", engine, game,
                Round12NbaFixtures.endGame());
        String endingEventBlueId = DirectBlueIdCalculator.calculateBlueId(
                engine.history(GAME_ID).get(4).emittedEvents().get(0));

        assertHost(engine, HOST_ONE, 1L, true, 1L,
                2L, 3L, 2L, "Final", 5L);
        assertHost(engine, HOST_TWO, 1L, true, 1L,
                2L, 3L, 2L, "Final", 5L);
        assertEquals(endingEventBlueId, text(
                engine, HOST_ONE, "/gameEndedEventBlueId"));
        assertEquals(endingEventBlueId, text(
                engine, HOST_TWO, "/gameEndedEventBlueId"));
        assertConvergedGameBlueId(engine, HOST_ONE, HOST_TWO);
        assertEquals(1L, integer(engine, GAME_ID,
                "/initializationCount"));
        assertEquals(5, engine.history(GAME_ID).size());
        assertEquals(1L, engine.history(GAME_ID).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.INITIALIZATION)
                .count());
        return endingEventBlueId;
    }

    private static void admitThirdHostAfterFinal(
            TestEngine engine,
            Round12NbaEvidence evidence,
            Timeline hostThree,
            LifecycleFixture fixture,
            EngineMetrics.MetricsSnapshot lifecycleStart,
            String initializationEventBlueId,
            String endingEventBlueId) throws Exception {
        long finalGameEpoch = engine.session(GAME_ID).epoch();
        int finalGameHistorySize = engine.history(GAME_ID).size();
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

        EngineTestSupport.MetricDelta twoHostTotal = delta(
                lifecycleStart, before);
        assertEquals(1L, twoHostTotal.counter(
                "embedding.childSessionsCreated"));
        assertEquals(1L, twoHostTotal.counter(
                "embedding.childSessionsReused"));
        assertEquals(2L, twoHostTotal.counter(
                "temporal.initializationEpochApplications"));
        assertEquals(2L, twoHostTotal.counter(
                "temporal.initializationEventOccurrencesForwarded"));
        assertEquals(10L, twoHostTotal.counter(
                "temporal.parentEpochApplications"));
        assertNoGenericSplitting(twoHostTotal);
        evidence.write(GAME_ID, HOST_ONE, HOST_TWO, twoHostTotal);

        engine.start(HOST_THREE, fixture.hostThreeYaml());
        engine.appendAndDispatch(
                hostThree,
                Round12NbaFixtures.attach(fixture.gameYaml(), engine));

        assertEquals(finalGameEpoch, engine.session(GAME_ID).epoch(),
                "post-Final attachment must not advance the Game");
        assertEquals(finalGameHistorySize,
                engine.history(GAME_ID).size(),
                "post-Final attachment must reuse retained Game history");
        assertHost(engine, HOST_THREE, 1L, true, 1L,
                2L, 3L, 2L, "Final", 5L);
        assertEquals(initializationEventBlueId, text(
                engine, HOST_THREE, "/gameInitializationEventBlueId"));
        assertEquals(endingEventBlueId, text(
                engine, HOST_THREE, "/gameEndedEventBlueId"));
        assertConvergedGameBlueId(engine, HOST_ONE, HOST_TWO, HOST_THREE);
        assertEquals(1L, integer(engine, GAME_ID,
                "/initializationCount"));

        EngineTestSupport.MetricDelta attachmentWork = delta(
                before, engine.metricsSnapshot());
        assertEquals(0L, attachmentWork.counter(
                "embedding.childSessionsCreated"));
        assertEquals(1L, attachmentWork.counter(
                "embedding.childSessionsReused"));
        assertEquals(0L, attachmentWork.counter(
                "childHistoricalProcessCalls"),
                "retained Game source entries must not be replayed");
        assertEquals(5L, attachmentWork.counter(
                "temporal.parentEpochApplications"));
        assertEquals(1L, attachmentWork.counter(
                "temporal.initializationEpochApplications"));
        assertEquals(1L, attachmentWork.counter(
                "temporal.initializationEventOccurrencesForwarded"));
    }

    private static Timelines registerTimelines(TestEngine engine) {
        return new Timelines(
                engine.timeline(
                        "examples/round12/nba/shared-game",
                        "round12-nba-feed"),
                engine.timeline(
                        "examples/round12/nba/host-one",
                        "round12-host-one-owner"),
                engine.timeline(
                        "examples/round12/nba/host-two",
                        "round12-host-two-owner"),
                engine.timeline(
                        "examples/round12/nba/host-three",
                        "round12-host-three-owner"));
    }

    private static void dispatchGameOnce(
            Round12NbaEvidence evidence,
            String evidenceName,
            TestEngine engine,
            Timeline game,
            Operation operation) {
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
        evidence.dispatch(evidenceName, GAME_ID, game, operation);
        assertEquals(1L, delta(before, engine.metricsSnapshot()).counter(
                "EXTERNAL_PROCESS_CALLS"),
                "each Game source entry must cross Game PROCESS once");
    }

    private static void assertConvergedGameBlueId(
            TestEngine engine,
            String... hostIds) {
        String gameBlueId = engine.session(GAME_ID).current().blueId();
        for (String hostId : hostIds) {
            assertEquals(gameBlueId, DirectBlueIdCalculator.calculateBlueId(
                            engine.value(hostId, "/game")),
                    hostId);
        }
    }

    private static void assertGameInitialization(
            TestEngine engine,
            TimelineEntry attachment) {
        List<DocumentRevision> history = engine.history(GAME_ID);
        assertEquals(1, history.size());
        DocumentRevision initialization = history.get(0);
        assertEquals(DocumentRevision.Kind.INITIALIZATION,
                initialization.kind());
        assertTrue(initialization.sourceEntry().isEmpty(),
                "initialization is processor-managed, not a Timeline Entry");
        assertEquals(attachment.blueId(),
                initialization.causalEntryBlueId().orElseThrow());
        assertTrue(initialization.sourceOrderKey().isPresent(),
                "initialization must retain exact causal order evidence");
        assertEquals(1, initialization.emittedEvents().size());
        Node event = initialization.emittedEvents().get(0);
        assertEquals("NBA/Game Initialized",
                event.getProperties().get("kind").getValue());
        assertFalse(initialization.catchUpCause().isEmpty());
        assertEquals(1L, integer(engine, GAME_ID,
                "/initializationCount"));
    }

    private static void assertHost(
            TestEngine engine,
            String hostId,
            long initializationEvents,
            boolean ended,
            long endedEvents,
            long homeScore,
            long awayScore,
            long playCount,
            String status,
            long revisionApplications) {
        assertEquals(initializationEvents, integer(
                engine, hostId, "/gameInitializationEventCount"));
        assertEquals(initializationEvents > 0L,
                Boolean.TRUE.equals(engine.value(
                        hostId, "/gameInitialized").getValue()));
        assertEquals(ended, Boolean.TRUE.equals(engine.value(
                hostId, "/gameEnded").getValue()));
        assertEquals(endedEvents, integer(
                engine, hostId, "/gameEndedEventCount"));
        assertEquals(homeScore, integer(
                engine, hostId, "/observedHomeScore"));
        assertEquals(awayScore, integer(
                engine, hostId, "/observedAwayScore"));
        assertEquals(playCount, integer(
                engine, hostId, "/observedPlayCount"));
        assertEquals(status, text(engine, hostId, "/observedStatus"));
        assertEquals(revisionApplications, integer(
                engine, hostId, "/revisionApplications"));
    }

    private record Timelines(
            Timeline game,
            Timeline hostOne,
            Timeline hostTwo,
            Timeline hostThree) {
    }

    private record LifecycleFixture(
            String gameYaml,
            String hostOneYaml,
            String hostTwoYaml,
            String hostThreeYaml) {
        private static LifecycleFixture create() throws Exception {
            return new LifecycleFixture(
                    Round12NbaFixtures.game(
                            GAME_ID,
                            "examples/round12/nba/shared-game",
                            "round12-nba-feed"),
                    Round12NbaFixtures.host(
                            HOST_ONE,
                            "examples/round12/nba/host-one",
                            "round12-host-one-owner"),
                    Round12NbaFixtures.host(
                            HOST_TWO,
                            "examples/round12/nba/host-two",
                            "round12-host-two-owner"),
                    Round12NbaFixtures.host(
                            HOST_THREE,
                            "examples/round12/nba/host-three",
                            "round12-host-three-owner"));
        }
    }
}
