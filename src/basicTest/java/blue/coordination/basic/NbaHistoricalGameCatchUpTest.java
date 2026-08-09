package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.CatchUpPlan;
import blue.coordination.basic.engine.DocumentRevision;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.RevisionKind;
import blue.coordination.basic.engine.SessionStatus;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static blue.coordination.basic.BasicEngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historical NBA game processed once as an autonomous document, then linked
 * into a statistics Root which catches up the exact committed revision stream.
 */
final class NbaHistoricalGameCatchUpTest {
    private static final long GAME_T0 = 1_450_000_000_000_000L;
    private static final long ATTACH_T = 1_800_000_000_000_000L;

    @Test
    void completedHistoricalGameIsProcessedOnceAndStatisticsRootCatchesUp()
            throws Exception {
        try (BasicTestMetrics report = BasicTestMetrics.start(
                "nba-historical-game-catch-up",
                "NBA autonomous game and statistics catch-up");
             BasicTestMetrics.MeasuredResource<BasicCoordinationEngine> managed =
                     report.manage(
                             "12 close environment",
                             report.measure(
                                     "01 start environment",
                                     BasicCoordinationEngine::create))) {
            BasicCoordinationEngine engine = managed.value();
            String gameInitial = report.measure(
                    "02 load NBA game initial state",
                    () -> resource("examples/clean/nba-game.yaml"));
            String statisticsInitial = report.measure(
                    "03 load statistics initial state",
                    () -> resource("examples/clean/nba-statistics.yaml"));
            Timeline gameFeed = report.measure(
                    "04 add historical game Timeline",
                    () -> engine.timeline(
                            "examples/nba/game-2016-lal-min", "nba-feed"));
            Timeline commissioner = report.measure(
                    "05 add statistics Timeline",
                    () -> engine.timeline(
                            "examples/nba/statistics", "commissioner"));

            report.measure("06 start autonomous game", () ->
                    engine.start("nba-game-2016-lal-min", gameInitial));
            report.measure("07 replay game source history once", () -> {
                dispatch(engine, gameFeed, GAME_T0 + 100L,
                        "startGame", "{}");
                dispatch(engine, gameFeed, GAME_T0 + 200L,
                        "homeScores", "points: 2");
                dispatch(engine, gameFeed, GAME_T0 + 300L,
                        "awayScores", "points: 3");
                dispatch(engine, gameFeed, GAME_T0 + 400L,
                        "endGame", "{}");
            });

            List<DocumentRevision> gameHistoryBeforeAttachment =
                    engine.history("nba-game-2016-lal-min");
            assertEquals(5, gameHistoryBeforeAttachment.size());
            assertEquals("Final", text(
                    engine, "nba-game-2016-lal-min", "/status"));
            assertEquals(2L, integer(
                    engine, "nba-game-2016-lal-min", "/homeScore"));
            assertEquals(3L, integer(
                    engine, "nba-game-2016-lal-min", "/awayScore"));
            assertEquals(2L, integer(
                    engine, "nba-game-2016-lal-min", "/playCount"));
            assertTrue(gameHistoryBeforeAttachment.stream()
                    .skip(1L)
                    .allMatch(revision -> !revision.emittedEvents().isEmpty()),
                    "Every game operation in this fixture emits an exact event");

            report.measure("08 start statistics Root", () ->
                    engine.start("nba-statistics", statisticsInitial));
            EngineMetrics.MetricsSnapshot beforeAttach =
                    engine.metricsSnapshot();
            final blue.coordination.basic.engine.ExactTimelineEntry[] attachment =
                    new blue.coordination.basic.engine.ExactTimelineEntry[1];
            report.measure("09 attach original game and catch up", () -> {
                attachment[0] = engine.appendAt(
                        commissioner,
                        BasicOperation.exact(
                                "attachGame",
                                "commissionerChannel",
                                engine.embeddedDocumentRequest(gameInitial)),
                        ATTACH_T);
                engine.dispatch(attachment[0]);
            });
            BasicEngineTestSupport.MetricDelta attachWork = delta(
                    beforeAttach, engine.metricsSnapshot());

            report.measure("10 verify catch-up and temporal evidence", () -> {
                assertEquals(SessionStatus.READY,
                        engine.session("nba-statistics").status());
                assertEquals("Final", text(
                        engine, "nba-statistics", "/observedStatus"));
                assertEquals(2L, integer(
                        engine, "nba-statistics", "/observedHomeScore"));
                assertEquals(3L, integer(
                        engine, "nba-statistics", "/observedAwayScore"));
                assertEquals(2L, integer(
                        engine, "nba-statistics", "/observedPlayCount"));
                assertEquals(5L, integer(
                        engine, "nba-statistics", "/revisionApplications"));
                assertEquals(gameHistoryBeforeAttachment.size(),
                        engine.history("nba-game-2016-lal-min").size(),
                        "Linking the game must not rerun game PROCESS");
                assertEquals(2,
                        engine.session("nba-statistics")
                                .layout().physicalObjectCount(),
                        "Root shell plus one Process Embedded game object");
                assertEquals(
                        Set.of(
                                "examples/nba/statistics",
                                "examples/nba/game-2016-lal-min"),
                        engine.effectiveTimelineIds("nba-statistics"));
                assertEquals(
                        "nba-game-2016-lal-min",
                        engine.embeddedDocuments("nba-statistics")
                                .get("/game"));

                List<DocumentRevision> statisticsHistory = engine.history(
                        "nba-statistics");
                assertEquals(7, statisticsHistory.size(),
                        "initialization + attachment + five game revisions");
                DocumentRevision attachRevision = statisticsHistory.get(1);
                assertEquals(RevisionKind.TIMELINE_ENTRY,
                        attachRevision.kind());
                assertEquals(attachment[0].blueId(),
                        attachRevision.sourceEntry().orElseThrow().blueId());
                long previousApplicationOrder = attachRevision.rootApplicationOrder();
                for (DocumentRevision revision : statisticsHistory.subList(
                        2, statisticsHistory.size())) {
                    assertEquals(
                            RevisionKind.EMBEDDED_REVISION_APPLICATION,
                            revision.kind());
                    assertTrue(revision.catchUpCause().isPresent());
                    assertEquals(attachment[0].blueId(),
                            revision.catchUpCause().orElseThrow()
                                    .attachmentEntryBlueId());
                    assertTrue(revision.sourceOrderKey().orElseThrow()
                                    .compareTo(attachment[0].sourceOrderKey()) <= 0,
                            "Historical source order remains before/equal cutoff");
                    assertTrue(revision.rootApplicationOrder()
                                    > previousApplicationOrder,
                            "Root application order moves forward during catch-up");
                    previousApplicationOrder = revision.rootApplicationOrder();
                }

                CatchUpPlan plan = engine.catchUpPlans().stream()
                        .filter(candidate -> candidate.link()
                                .parentDocumentId().value()
                                .equals("nba-statistics"))
                        .findFirst()
                        .orElseThrow();
                assertEquals(CatchUpPlan.Status.COMPLETE, plan.status());
                assertEquals(4L, plan.link().appliedChildEpoch());

                assertEquals(1L, attachWork.counter(
                        "embedding.childSessionsReused"));
                assertEquals(0L, attachWork.counter(
                        "catchUp.childEntriesProcessed"));
                assertEquals(5L, attachWork.counter(
                        "catchUp.parentRevisionApplications"));
                assertEquals(6L, attachWork.counter(
                        "process.frozenContractsInvocations"),
                        "one attachment call plus five parent applications");
                assertNoGenericSplitting(attachWork);
            });

            EngineMetrics.MetricsSnapshot beforeLive = engine.metricsSnapshot();
            report.measure("11 process post-attachment live scoring play", () ->
                    dispatch(engine, gameFeed, ATTACH_T + 1_000L,
                            "homeScores", "points: 1"));
            BasicEngineTestSupport.MetricDelta liveWork = delta(
                    beforeLive, engine.metricsSnapshot());

            assertEquals(3L, integer(
                    engine, "nba-game-2016-lal-min", "/homeScore"));
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedHomeScore"));
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedAwayScore"));
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedPlayCount"));
            assertEquals(6L, integer(
                    engine, "nba-statistics", "/revisionApplications"));
            assertEquals(2L, liveWork.counter(
                    "process.frozenContractsInvocations"),
                    "one game PROCESS plus one statistics revision application");
            assertEquals(1L, liveWork.counter(
                    "catchUp.parentRevisionApplications"));
            assertNoGenericSplitting(liveWork);

            DocumentRevision latestStatistics = engine.history("nba-statistics")
                    .get(engine.history("nba-statistics").size() - 1);
            assertFalse(latestStatistics.catchUpCause().isEmpty(),
                    "Linked live revisions retain their original attachment cause");

            report.detail(
                            "nba-attachment-engine-work",
                            "09 attach original game and catch up")
                    .counter("child game sessions reused", attachWork.counter(
                            "embedding.childSessionsReused"))
                    .counter("child game entries reprocessed", attachWork.counter(
                            "catchUp.childEntriesProcessed"))
                    .counter("statistics revision applications", attachWork.counter(
                            "catchUp.parentRevisionApplications"))
                    .counter("frozen PROCESS calls", attachWork.counter(
                            "process.frozenContractsInvocations"))
                    .phase("frozen Contracts PROCESS", attachWork.nanos(
                            "process.frozenContractsOnce"))
                    .phase("embedded-only layout", attachWork.nanos(
                            "layout.retainEmbeddedOnly"));
        }
    }

    private static void dispatch(
            BasicCoordinationEngine engine,
            Timeline timeline,
            long timestamp,
            String operation,
            String request) {
        var entry = engine.appendAt(
                timeline,
                BasicOperation.of(operation, "gameFeed", request),
                timestamp);
        engine.dispatch(entry);
    }
}
