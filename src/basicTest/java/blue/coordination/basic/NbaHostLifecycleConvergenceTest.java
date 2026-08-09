package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactTimelineEntry;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static blue.coordination.basic.BasicEngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that four admission orders converge to one event-driven host state. */
@Tag("scenario")
final class NbaHostLifecycleConvergenceTest {
    private static final long T0 = 1_450_000_000_000_000L;
    private static final String GAME_ID = "nba-game-2016-lal-min";
    private static final String REPLAY_GAME_ID = "nba-game-replay";

    @Test
    void allHostAndHistoricalGameAdmissionOrdersConverge() throws Exception {
        String gameInitial = resource("examples/clean/nba-game.yaml")
                .replace("accountId: nba-feed", "accountId: nba-commissioner");
        String hostInitial = resource("examples/clean/nba-game-host.yaml");

        try (BasicTestMetrics report = BasicTestMetrics.start(
                "nba-host-lifecycle-convergence",
                "Four NBA host and historical game admission orders")) {
            List<VariationResult> results = new ArrayList<>(4);
            results.add(report.measure(
                    "01 host first, game history arrives after attachment",
                    () -> hostFirst(gameInitial, hostInitial)));
            results.add(report.measure(
                    "02 completed game first, existing session attached",
                    () -> completedGameFirst(gameInitial, hostInitial)));
            results.add(report.measure(
                    "03 partial history before late game admission",
                    () -> partialHistoryFirst(gameInitial, hostInitial)));
            results.add(report.measure(
                    "04 second game instance replays shared history",
                    () -> replayGameFirst(gameInitial, hostInitial)));

            report.measure("05 verify all four host states are identical", () -> {
                HostState expected = results.get(0).host();
                results.forEach(result -> assertEquals(
                        expected, result.host(), result.name()));
                assertEquals(new HostState(
                        2L, true, 1L, 2L, 3L, 2L, "Final", 5L),
                        expected);
                results.forEach(result -> {
                    assertEquals(5, result.gameRevisionCount(), result.name());
                    assertTrue(result.frozenProcessCalls() > 0L, result.name());
                });
            });

            for (int i = 0; i < results.size(); i++) {
                VariationResult result = results.get(i);
                report.detail("variation-" + (i + 1),
                                String.format("%02d %s", i + 1, result.name()))
                        .counter("frozen PROCESS calls", result.frozenProcessCalls())
                        .counter("child history entries processed",
                                result.childEntriesProcessed())
                        .counter("parent revision applications",
                                result.parentRevisionApplications());
            }
        }
    }

    private static VariationResult hostFirst(
            String gameInitial,
            String hostInitial) throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timelines timelines = timelines(engine);
            engine.start("nba-game-host", hostInitial);
            touchHost(engine, timelines.host());
            attach(engine, timelines.host(), gameInitial);
            dispatchGameRange(engine, timelines.game(), 0, 4);
            touchHost(engine, timelines.host());
            return result("host first, game history arrives after attachment", engine, GAME_ID);
        }
    }

    private static VariationResult completedGameFirst(
            String gameInitial,
            String hostInitial) throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timelines timelines = timelines(engine);
            engine.start(GAME_ID, gameInitial);
            dispatchGameRange(engine, timelines.game(), 0, 4);
            assertGameFinal(engine, GAME_ID);
            engine.start("nba-game-host", hostInitial);
            touchHost(engine, timelines.host());
            attach(engine, timelines.host(), gameInitial);
            touchHost(engine, timelines.host());
            return result("completed game first, existing session attached", engine, GAME_ID);
        }
    }

    private static VariationResult partialHistoryFirst(
            String gameInitial,
            String hostInitial) throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timelines timelines = timelines(engine);
            appendGameRange(engine, timelines.game(), 0, 2);
            engine.start("nba-game-host", hostInitial);
            touchHost(engine, timelines.host());
            attach(engine, timelines.host(), gameInitial);
            dispatchGameRange(engine, timelines.game(), 2, 4);
            touchHost(engine, timelines.host());
            return result("partial history before late game admission", engine, GAME_ID);
        }
    }

    private static VariationResult replayGameFirst(
            String gameInitial,
            String hostInitial) throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timelines timelines = timelines(engine);
            engine.start(GAME_ID, gameInitial);
            dispatchGameRange(engine, timelines.game(), 0, 4);
            GameState completed = gameState(engine, GAME_ID);

            engine.start("nba-game-host", hostInitial);
            touchHost(engine, timelines.host());
            String replayInitial = gameInitial.replaceFirst(
                    "documentId: " + GAME_ID,
                    "documentId: " + REPLAY_GAME_ID);
            attach(engine, timelines.host(), replayInitial);
            assertEquals(completed, gameState(engine, REPLAY_GAME_ID),
                    "A distinct instance of the same game definition must catch up "
                            + "from the shared commissioner Timeline");
            touchHost(engine, timelines.host());
            return result("second game instance replays shared history", engine, REPLAY_GAME_ID);
        }
    }

    private static Timelines timelines(BasicCoordinationEngine engine) {
        return new Timelines(
                engine.timeline("examples/nba/host", "host-owner"),
                engine.timeline(
                        "examples/nba/game-2016-lal-min",
                        "nba-commissioner"));
    }

    private static void touchHost(
            BasicCoordinationEngine engine,
            Timeline host) {
        engine.appendAndDispatch(
                host, BasicOperation.of("touchHost", "hostChannel", "{}"));
    }

    private static void attach(
            BasicCoordinationEngine engine,
            Timeline host,
            String gameInitial) {
        engine.appendAndDispatch(host, BasicOperation.exact(
                "attachGame",
                "hostChannel",
                engine.embeddedDocumentRequest(gameInitial)));
    }

    private static void appendGameRange(
            BasicCoordinationEngine engine,
            Timeline game,
            int from,
            int to) {
        for (int index = from; index < to; index++) {
            engine.appendAt(game, gameOperation(index), gameTimestamp(index));
        }
    }

    private static void dispatchGameRange(
            BasicCoordinationEngine engine,
            Timeline game,
            int from,
            int to) {
        for (int index = from; index < to; index++) {
            dispatch(engine, game, gameTimestamp(index), gameOperation(index));
        }
    }

    private static BasicOperation gameOperation(int index) {
        return switch (index) {
            case 0 -> BasicOperation.of("startGame", "gameFeed", "{}");
            case 1 -> BasicOperation.of("homeScores", "gameFeed", "points: 2");
            case 2 -> BasicOperation.of("awayScores", "gameFeed", "points: 3");
            case 3 -> BasicOperation.of("endGame", "gameFeed", "{}");
            default -> throw new IllegalArgumentException("Unknown game entry " + index);
        };
    }

    private static long gameTimestamp(int index) {
        return T0 + (index + 1L) * 100L;
    }

    private static void dispatch(
            BasicCoordinationEngine engine,
            Timeline timeline,
            long timestamp,
            BasicOperation operation) {
        ExactTimelineEntry entry = engine.appendAt(timeline, operation, timestamp);
        engine.dispatch(entry);
    }

    private static VariationResult result(
            String name,
            BasicCoordinationEngine engine,
            String gameId) {
        assertGameFinal(engine, gameId);
        EngineMetrics.MetricsSnapshot metrics = engine.metricsSnapshot();
        return new VariationResult(
                name,
                hostState(engine),
                engine.history(gameId).size(),
                metrics.counters().getOrDefault(
                        "process.frozenContractsInvocations", 0L),
                metrics.counters().getOrDefault(
                        "catchUp.childEntriesProcessed", 0L),
                metrics.counters().getOrDefault(
                        "catchUp.parentRevisionApplications", 0L));
    }

    private static HostState hostState(BasicCoordinationEngine engine) {
        return new HostState(
                integer(engine, "nba-game-host", "/hostOperationCount"),
                Boolean.TRUE.equals(engine.value(
                        "nba-game-host", "/gameEnded").getValue()),
                integer(engine, "nba-game-host", "/gameEndedEventCount"),
                integer(engine, "nba-game-host", "/observedHomeScore"),
                integer(engine, "nba-game-host", "/observedAwayScore"),
                integer(engine, "nba-game-host", "/observedPlayCount"),
                text(engine, "nba-game-host", "/observedStatus"),
                integer(engine, "nba-game-host", "/revisionApplications"));
    }

    private static void assertGameFinal(
            BasicCoordinationEngine engine,
            String gameId) {
        assertEquals(new GameState(2L, 3L, 2L, "Final"),
                gameState(engine, gameId));
    }

    private static GameState gameState(
            BasicCoordinationEngine engine,
            String gameId) {
        return new GameState(
                integer(engine, gameId, "/homeScore"),
                integer(engine, gameId, "/awayScore"),
                integer(engine, gameId, "/playCount"),
                text(engine, gameId, "/status"));
    }

    private record Timelines(Timeline host, Timeline game) { }

    private record GameState(
            long homeScore,
            long awayScore,
            long playCount,
            String status) { }

    private record HostState(
            long hostOperationCount,
            boolean gameEnded,
            long gameEndedEventCount,
            long observedHomeScore,
            long observedAwayScore,
            long observedPlayCount,
            String observedStatus,
            long revisionApplications) { }

    private record VariationResult(
            String name,
            HostState host,
            int gameRevisionCount,
            long frozenProcessCalls,
            long childEntriesProcessed,
            long parentRevisionApplications) { }
}
