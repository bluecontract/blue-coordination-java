package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
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

        List<VariationResult> results = new ArrayList<>(4);
        results.add(hostFirst(gameInitial, hostInitial));
        results.add(completedGameFirst(gameInitial, hostInitial));
        results.add(partialHistoryFirst(gameInitial, hostInitial));
        results.add(replayGameFirst(gameInitial, hostInitial));

        HostState expected = results.get(0).host();
        results.forEach(result -> assertEquals(
                expected, result.host(), result.name()));
        assertEquals(new HostState(
                2L, true, 1L, 2L, 3L, 2L, "Final", 5L), expected);
        results.forEach(result -> {
            assertEquals(5, result.gameRevisionCount(), result.name());
            assertTrue(result.frozenProcessCalls() > 0L, result.name());
            assertEquals(5L, result.parentRevisionApplications(),
                    result.name());
        });
    }

    private static VariationResult hostFirst(
            String gameInitial,
            String hostInitial) throws Exception {
        try (TestEngine engine = TestEngine.create()) {
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
        try (TestEngine engine = TestEngine.create()) {
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
        try (TestEngine engine = TestEngine.create()) {
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
        try (TestEngine engine = TestEngine.create()) {
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

    private static Timelines timelines(TestEngine engine) {
        return new Timelines(
                engine.timeline("examples/nba/host", "host-owner"),
                engine.timeline(
                        "examples/nba/game-2016-lal-min",
                        "nba-commissioner"));
    }

    private static void touchHost(
            TestEngine engine,
            Timeline host) {
        engine.appendAndDispatch(
                host, Operation.yaml("touchHost", "hostChannel", "{}"));
    }

    private static void attach(
            TestEngine engine,
            Timeline host,
            String gameInitial) {
        engine.appendAndDispatch(host, Operation.exact(
                "attachGame",
                "hostChannel",
                engine.embeddedDocumentRequest(gameInitial)));
    }

    private static void appendGameRange(
            TestEngine engine,
            Timeline game,
            int from,
            int to) {
        for (int index = from; index < to; index++) {
            engine.appendAt(game, gameOperation(index), gameTimestamp(index));
        }
    }

    private static void dispatchGameRange(
            TestEngine engine,
            Timeline game,
            int from,
            int to) {
        for (int index = from; index < to; index++) {
            dispatch(engine, game, gameTimestamp(index), gameOperation(index));
        }
    }

    private static Operation gameOperation(int index) {
        return switch (index) {
            case 0 -> Operation.yaml("startGame", "gameFeed", "{}");
            case 1 -> Operation.yaml("homeScores", "gameFeed", "points: 2");
            case 2 -> Operation.yaml("awayScores", "gameFeed", "points: 3");
            case 3 -> Operation.yaml("endGame", "gameFeed", "{}");
            default -> throw new IllegalArgumentException("Unknown game entry " + index);
        };
    }

    private static long gameTimestamp(int index) {
        return T0 + (index + 1L) * 100L;
    }

    private static void dispatch(
            TestEngine engine,
            Timeline timeline,
            long timestamp,
            Operation operation) {
        TimelineEntry entry = engine.appendAt(timeline, operation, timestamp);
        engine.dispatch(entry);
    }

    private static VariationResult result(
            String name,
            TestEngine engine,
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

    private static HostState hostState(TestEngine engine) {
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
            TestEngine engine,
            String gameId) {
        assertEquals(new GameState(2L, 3L, 2L, "Final"),
                gameState(engine, gameId));
    }

    private static GameState gameState(
            TestEngine engine,
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
