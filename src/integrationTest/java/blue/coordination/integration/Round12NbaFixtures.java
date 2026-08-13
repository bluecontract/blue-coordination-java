package blue.coordination.integration;

import blue.coordination.api.Operation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic source builders shared by the Round 12 lifecycle scenarios. */
final class Round12NbaFixtures {
    static final String GAME_RESOURCE =
            "examples/round12/nba-lifecycle-game.yaml";
    static final String HOST_RESOURCE =
            "examples/round12/nba-lifecycle-host.yaml";
    static final String PATH_HOST_RESOURCE =
            "examples/round12/dynamic-path-host.yaml";
    static final String COLLECTION_HOST_RESOURCE =
            "examples/round12/dynamic-collection-host.yaml";

    private Round12NbaFixtures() {
    }

    static String game(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return EngineTestSupport.resource(GAME_RESOURCE)
                .replace("documentId: nba-lifecycle-game",
                        "documentId: " + documentId)
                .replace("timelineId: examples/round12/nba/game",
                        "timelineId: " + timelineId)
                .replace("accountId: nba-feed",
                        "accountId: " + actorId);
    }

    static String host(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return EngineTestSupport.resource(HOST_RESOURCE)
                .replace("documentId: nba-lifecycle-host",
                        "documentId: " + documentId)
                .replace("timelineId: examples/round12/nba/host",
                        "timelineId: " + timelineId)
                .replace("accountId: host-owner",
                        "accountId: " + actorId)
                .replace("timelineId: coordination/internal/round12/nba-host",
                        "timelineId: coordination/internal/" + documentId);
    }

    static String dynamicPathHost(String exactGameYaml) throws Exception {
        return EngineTestSupport.resource(PATH_HOST_RESOURCE).replace(
                "game: __GAME_DOCUMENT__",
                "game:\n" + EngineTestSupport.indent(
                        exactGameYaml.strip(), 2));
    }

    static String dynamicCollectionHost(
            Map<String, String> exactGamesByKey) throws Exception {
        StringBuilder games = new StringBuilder("games:\n");
        exactGamesByKey.forEach((key, yaml) -> games
                .append("  ").append(key).append(":\n")
                .append(EngineTestSupport.indent(yaml.strip(), 4))
                .append('\n'));
        return EngineTestSupport.resource(COLLECTION_HOST_RESOURCE).replace(
                "games: __GAME_COLLECTION__", games.toString().stripTrailing());
    }

    static Map<String, String> deliberatelyUnorderedGames() throws Exception {
        Map<String, String> games = new LinkedHashMap<>();
        games.put("zulu", game(
                "round12-game-zulu",
                "examples/round12/nba/game-zulu",
                "nba-feed-zulu"));
        games.put("alpha", game(
                "round12-game-alpha",
                "examples/round12/nba/game-alpha",
                "nba-feed-alpha"));
        games.put("middle", game(
                "round12-game-middle",
                "examples/round12/nba/game-middle",
                "nba-feed-middle"));
        return games;
    }

    static Operation attach(String gameYaml, TestEngine engine) {
        return Operation.exact(
                "attachGame",
                "hostChannel",
                engine.embeddedDocumentRequest(gameYaml));
    }

    static Operation startGame() {
        return Operation.yaml("startGame", "gameFeed", "{}");
    }

    static Operation homeScores(int points) {
        return Operation.yaml(
                "homeScores", "gameFeed", "points: " + points);
    }

    static Operation awayScores(int points) {
        return Operation.yaml(
                "awayScores", "gameFeed", "points: " + points);
    }

    static Operation endGame() {
        return Operation.yaml("endGame", "gameFeed", "{}");
    }

    static long counter(
            EngineMetrics.MetricsSnapshot metrics,
            String name) {
        return metrics.counters().getOrDefault(name, 0L);
    }
}
