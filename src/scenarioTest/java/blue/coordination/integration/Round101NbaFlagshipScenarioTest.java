package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round 10.1 flagship: three interleaved Games under two collection Roots. */
@Tag("scenario")
final class Round101NbaFlagshipScenarioTest {
    private static final long T0 = 1_720_000_000_000_000L;
    private static final RootSpec PRIMARY = new RootSpec(
            DocumentId.of("nba-round10-slate"),
            "examples/nba/round10/slate",
            "slate-owner",
            "coordination/internal/nba-round10-slate");
    private static final RootSpec MIRROR = new RootSpec(
            DocumentId.of("nba-round10-slate-mirror"),
            "examples/nba/round10/slate-mirror",
            "slate-mirror-owner",
            "coordination/internal/nba-round10-slate-mirror");
    private static final GameSpec GAME_A = new GameSpec(
            "a",
            DocumentId.of("nba-round10-game-a"),
            "examples/nba/round10/game-a",
            2L,
            3L);
    private static final GameSpec GAME_B = new GameSpec(
            "b",
            DocumentId.of("nba-round10-game-b"),
            "examples/nba/round10/game-b",
            4L,
            1L);
    private static final GameSpec GAME_C = new GameSpec(
            "c",
            DocumentId.of("nba-round10-game-c"),
            "examples/nba/round10/game-c",
            5L,
            6L);
    private static final List<GameSpec> GAMES =
            List.of(GAME_A, GAME_B, GAME_C);

    @Test
    void threeGameCollectionConvergesAcrossAdmissionOrders()
            throws Exception {
        // given
        List<Outcome> outcomes = new ArrayList<>();

        // when
        for (AdmissionOrder order : AdmissionOrder.values()) {
            outcomes.add(run(order));
        }

        // then
        Outcome expected = outcomes.get(0);
        SlateState expectedState = new SlateState(
                15L,
                3L,
                1L,
                15L,
                true,
                11L,
                10L,
                "Final",
                "Final",
                "Final");
        assertEquals(expectedState, expected.primaryState());
        assertEquals(expectedState, expected.mirrorState());
        for (Outcome outcome : outcomes) {
            assertEquals(expected.primaryState(), outcome.primaryState(),
                    outcome.order().name());
            assertEquals(expected.mirrorState(), outcome.mirrorState(),
                    outcome.order().name());
            assertEquals(expected.gameStates(), outcome.gameStates(),
                    outcome.order().name());
            assertEquals(expected.primaryFinalBlueId(),
                    outcome.primaryFinalBlueId(), outcome.order().name());
            assertEquals(expected.mirrorFinalBlueId(),
                    outcome.mirrorFinalBlueId(), outcome.order().name());
        }
    }

    private static Outcome run(AdmissionOrder order) throws Exception {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            Map<RootSpec, Timeline> rootTimelines = registerRootTimelines(
                    engine);
            Map<GameSpec, Timeline> gameTimelines = registerGameTimelines(
                    engine);
            Map<GameSpec, String> gameYaml = gameYaml();
            Map<GameSpec, ExactValue> gameValues = exactGames(
                    engine, gameYaml);

            if (order == AdmissionOrder.ROOTS_FIRST) {
                startRoots(engine);
            } else if (order == AdmissionOrder.GAMES_MANAGED_FIRST) {
                startGames(engine, gameYaml);
            }

            List<TimelineEntry> gameEntries = appendInterleavedHistory(
                    engine, gameTimelines);

            if (order != AdmissionOrder.ROOTS_FIRST) {
                startRoots(engine);
            }

            ExactValue attachmentRequest = attachmentRequest(gameValues);
            TimelineEntry primaryAttachment = engine.appendAt(
                    rootTimelines.get(PRIMARY),
                    Operation.exact(
                            "attachGames", "slateChannel", attachmentRequest),
                    T0 + 1_000L);
            TimelineEntry mirrorAttachment = engine.appendAt(
                    rootTimelines.get(MIRROR),
                    Operation.exact(
                            "attachGames", "slateChannel", attachmentRequest),
                    T0 + 1_020L);
            TimelineEntry primarySnapshot = engine.appendAt(
                    rootTimelines.get(PRIMARY),
                    Operation.yaml("snapshotSlate", "slateChannel", "{}"),
                    T0 + 1_100L);
            TimelineEntry mirrorSnapshot = engine.appendAt(
                    rootTimelines.get(MIRROR),
                    Operation.yaml("snapshotSlate", "slateChannel", "{}"),
                    T0 + 1_120L);

            ProcessingDrainReceipt receipt = engine.drain();

            List<String> expectedDrainOrder = new ArrayList<>(
                    entryIds(gameEntries));
            expectedDrainOrder.add(primaryAttachment.blueId());
            expectedDrainOrder.add(mirrorAttachment.blueId());
            expectedDrainOrder.add(primarySnapshot.blueId());
            expectedDrainOrder.add(mirrorSnapshot.blueId());
            assertEquals(expectedDrainOrder,
                    entryIds(receipt.processedEntries()), order.name());
            assertTrue(receipt.quiescent(), order.name());

            verifyContainingRoot(
                    engine,
                    PRIMARY,
                    primaryAttachment,
                    primarySnapshot,
                    gameEntries);
            verifyContainingRoot(
                    engine,
                    MIRROR,
                    mirrorAttachment,
                    mirrorSnapshot,
                    gameEntries);
            assertSharedGames(engine);
            assertEquals(30L, engine.metrics().counter(
                    CoordinationMetrics.Counter.PARENT_EPOCH_APPLICATIONS));
            assertEquals(0L, engine.metrics().counter(
                    CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT));

            return new Outcome(
                    order,
                    slateState(engine, PRIMARY.documentId()),
                    slateState(engine, MIRROR.documentId()),
                    gameStates(engine),
                    engine.document(PRIMARY.documentId()).blueId(),
                    engine.document(MIRROR.documentId()).blueId());
        }
    }

    private static void verifyContainingRoot(
            CoordinationEngine engine,
            RootSpec root,
            TimelineEntry attachment,
            TimelineEntry snapshot,
            List<TimelineEntry> gameEntries) {
        DocumentSnapshot current = engine.document(root.documentId());
        assertEquals(17L, current.epoch());
        assertEquals(18, engine.history(root.documentId()).size());
        assertEquals(expectedChildren(), current.embeddedChildren());
        assertEquals(
                Set.of(
                        root.timelineId(),
                        GAME_A.timelineId(),
                        GAME_B.timelineId(),
                        GAME_C.timelineId()),
                engine.effectiveTimelineIds(root.documentId()));

        List<DocumentRevision> history = engine.history(root.documentId());
        assertEquals(
                List.of(attachment.blueId(), snapshot.blueId()),
                history.stream()
                        .flatMap(revision -> revision.sourceEntry().stream())
                        .map(TimelineEntry::blueId)
                        .toList());
        assertEquals(snapshot.blueId(), history.get(history.size() - 1)
                .sourceEntry().orElseThrow().blueId());

        List<String> embeddedCauses = history.stream()
                        .filter(revision -> revision.kind()
                                == DocumentRevision.Kind
                                .EMBEDDED_REVISION_APPLICATION)
                        .map(revision -> revision.causalEntryBlueId()
                                .orElseThrow())
                        .toList();
        assertEquals(15, embeddedCauses.size());
        assertEquals(
                entryIds(gameEntries),
                embeddedCauses.subList(3, embeddedCauses.size()));
        assertEquals(15L, number(current, "/applicationsSeenBySnapshot"));
        assertEquals(15L, number(current, "/revisionApplications"));
    }

    private static void assertSharedGames(CoordinationEngine engine) {
        assertEquals(
                engine.document(PRIMARY.documentId()).embeddedChildren(),
                engine.document(MIRROR.documentId()).embeddedChildren());
        for (GameSpec game : GAMES) {
            assertEquals(5, engine.history(game.documentId()).size());
            assertEquals(4L, engine.document(game.documentId()).epoch());
        }
    }

    private static Map<RootSpec, Timeline> registerRootTimelines(
            CoordinationEngine engine) {
        Map<RootSpec, Timeline> result = new LinkedHashMap<>();
        for (RootSpec root : List.of(PRIMARY, MIRROR)) {
            result.put(root, engine.registerTimeline(
                    root.timelineId(), root.actorId()));
        }
        return result;
    }

    private static Map<GameSpec, Timeline> registerGameTimelines(
            CoordinationEngine engine) {
        Map<GameSpec, Timeline> result = new LinkedHashMap<>();
        for (GameSpec game : GAMES) {
            result.put(game, engine.registerTimeline(
                    game.timelineId(), "nba-commissioner"));
        }
        return result;
    }

    private static Map<GameSpec, String> gameYaml() throws Exception {
        String template = resource("examples/clean/nba-game.yaml");
        Map<GameSpec, String> result = new LinkedHashMap<>();
        for (GameSpec game : GAMES) {
            result.put(game, template
                    .replace(
                            "documentId: nba-game-2016-lal-min",
                            "documentId: " + game.documentId().value())
                    .replace(
                            "name: Lakers at Timberwolves 2016 Historical Game",
                            "name: Round 10 Game " + game.key().toUpperCase())
                    .replace(
                            "timelineId: examples/nba/game-2016-lal-min",
                            "timelineId: " + game.timelineId())
                    .replace(
                            "accountId: nba-feed",
                            "accountId: nba-commissioner"));
        }
        return result;
    }

    private static Map<GameSpec, ExactValue> exactGames(
            CoordinationEngine engine,
            Map<GameSpec, String> gameYaml) {
        Map<GameSpec, ExactValue> result = new LinkedHashMap<>();
        gameYaml.forEach((game, yaml) -> result.put(
                game, engine.exactValue(yaml)));
        return result;
    }

    private static void startGames(
            CoordinationEngine engine,
            Map<GameSpec, String> gameYaml) {
        gameYaml.forEach((game, yaml) -> engine.startDocument(
                game.documentId(), yaml));
    }

    private static void startRoots(CoordinationEngine engine)
            throws Exception {
        engine.startDocument(PRIMARY.documentId(), slateYaml(PRIMARY));
        engine.startDocument(MIRROR.documentId(), slateYaml(MIRROR));
    }

    private static String slateYaml(RootSpec root) throws Exception {
        return resource("examples/clean/nba-round10-slate.yaml")
                .replace(
                        "documentId: nba-round10-slate",
                        "documentId: " + root.documentId().value())
                .replace(
                        "timelineId: examples/nba/round10/slate",
                        "timelineId: " + root.timelineId())
                .replace(
                        "accountId: slate-owner",
                        "accountId: " + root.actorId())
                .replace(
                        "timelineId: coordination/internal/nba-round10-slate",
                        "timelineId: " + root.internalTimelineId());
    }

    private static ExactValue attachmentRequest(
            Map<GameSpec, ExactValue> games) {
        Map<String, Node> fields = new LinkedHashMap<>();
        for (GameSpec game : GAMES) {
            fields.put(game.key(), games.get(game).referenceNode());
        }
        return ExactValue.verified(new Node().properties(fields));
    }

    private static List<TimelineEntry> appendInterleavedHistory(
            CoordinationEngine engine,
            Map<GameSpec, Timeline> timelines) {
        List<ScheduledOperation> schedule = List.of(
                operation(GAME_A, 100L, "startGame", "{}"),
                operation(GAME_B, 150L, "startGame", "{}"),
                operation(GAME_C, 200L, "startGame", "{}"),
                operation(GAME_C, 300L, "homeScores", "points: 5"),
                operation(GAME_B, 350L, "awayScores", "points: 1"),
                operation(GAME_A, 400L, "homeScores", "points: 2"),
                operation(GAME_C, 600L, "awayScores", "points: 6"),
                operation(GAME_B, 650L, "homeScores", "points: 4"),
                operation(GAME_A, 700L, "awayScores", "points: 3"),
                operation(GAME_C, 800L, "endGame", "{}"),
                operation(GAME_B, 850L, "endGame", "{}"),
                operation(GAME_A, 900L, "endGame", "{}"));
        List<TimelineEntry> result = new ArrayList<>();
        for (ScheduledOperation scheduled : schedule) {
            result.add(engine.appendAt(
                    timelines.get(scheduled.game()),
                    scheduled.operation(),
                    T0 + scheduled.offset()));
        }
        return result;
    }

    private static ScheduledOperation operation(
            GameSpec game,
            long offset,
            String operation,
            String request) {
        return new ScheduledOperation(
                game,
                offset,
                Operation.yaml(operation, "gameFeed", request));
    }

    private static Map<String, DocumentId> expectedChildren() {
        return Map.of(
                "/games/a", GAME_A.documentId(),
                "/games/b", GAME_B.documentId(),
                "/games/c", GAME_C.documentId());
    }

    private static SlateState slateState(
            CoordinationEngine engine,
            DocumentId documentId) {
        DocumentSnapshot snapshot = engine.document(documentId);
        return new SlateState(
                number(snapshot, "/revisionApplications"),
                number(snapshot, "/endedGameEventCount"),
                number(snapshot, "/snapshotCount"),
                number(snapshot, "/applicationsSeenBySnapshot"),
                Boolean.TRUE.equals(value(snapshot, "/allGamesFinal")),
                number(snapshot, "/observedHomeScoreTotal"),
                number(snapshot, "/observedAwayScoreTotal"),
                text(snapshot, "/observedGameAStatus"),
                text(snapshot, "/observedGameBStatus"),
                text(snapshot, "/observedGameCStatus"));
    }

    private static Map<String, GameState> gameStates(
            CoordinationEngine engine) {
        Map<String, GameState> result = new LinkedHashMap<>();
        for (GameSpec game : GAMES) {
            DocumentSnapshot snapshot = engine.document(game.documentId());
            GameState state = new GameState(
                    number(snapshot, "/homeScore"),
                    number(snapshot, "/awayScore"),
                    number(snapshot, "/playCount"),
                    text(snapshot, "/status"));
            assertEquals(new GameState(
                    game.homeScore(), game.awayScore(), 2L, "Final"), state);
            result.put(game.key(), state);
        }
        return Map.copyOf(result);
    }

    private static List<String> entryIds(List<TimelineEntry> entries) {
        return entries.stream().map(TimelineEntry::blueId).toList();
    }

    private static long number(DocumentSnapshot snapshot, String path) {
        Object value = value(snapshot, path);
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected Integer at "
                + snapshot.documentId() + path + ", got " + value);
    }

    private static String text(DocumentSnapshot snapshot, String path) {
        Object value = value(snapshot, path);
        if (value instanceof String text) {
            return text;
        }
        throw new AssertionError("Expected Text at "
                + snapshot.documentId() + path + ", got " + value);
    }

    private static Object value(DocumentSnapshot snapshot, String path) {
        return snapshot.valueAt(path).copyNode().getValue();
    }

    private enum AdmissionOrder {
        HISTORIES_FIRST,
        ROOTS_FIRST,
        GAMES_MANAGED_FIRST
    }

    private record RootSpec(
            DocumentId documentId,
            String timelineId,
            String actorId,
            String internalTimelineId) {
    }

    private record GameSpec(
            String key,
            DocumentId documentId,
            String timelineId,
            long homeScore,
            long awayScore) {
    }

    private record ScheduledOperation(
            GameSpec game,
            long offset,
            Operation operation) {
    }

    private record SlateState(
            long revisionApplications,
            long endedGameEventCount,
            long snapshotCount,
            long applicationsSeenBySnapshot,
            boolean allGamesFinal,
            long observedHomeScoreTotal,
            long observedAwayScoreTotal,
            String observedGameAStatus,
            String observedGameBStatus,
            String observedGameCStatus) {
    }

    private record GameState(
            long homeScore,
            long awayScore,
            long playCount,
            String status) {
    }

    private record Outcome(
            AdmissionOrder order,
            SlateState primaryState,
            SlateState mirrorState,
            Map<String, GameState> gameStates,
            String primaryFinalBlueId,
            String mirrorFinalBlueId) {
    }
}
