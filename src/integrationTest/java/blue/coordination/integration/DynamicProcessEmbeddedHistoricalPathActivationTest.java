package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Historical child entries settle inside a newly activated fixed-path frame. */
final class DynamicProcessEmbeddedHistoricalPathActivationTest {
    private static final long T0 = 1_748_000_000_000_000L;
    private static final String HOST_ID = "dynamic-path-host";
    private static final String GAME_ID = "round12-dynamic-history-game";

    @Test
    void activationInitializesThenCatchesHistoryBeforeTheNextParentEntry()
            throws Exception {
        // given
        String gameYaml = Round12NbaFixtures.game(
                GAME_ID,
                "examples/round12/dynamic/history/game",
                "round12-history-feed");
        String hostYaml = withCatchUpObservation(
                Round12NbaFixtures.dynamicPathHost(gameYaml));

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/dynamic/path/host",
                    "path-owner");
            Timeline game = engine.timeline(
                    "examples/round12/dynamic/history/game",
                    "round12-history-feed");

            engine.start(HOST_ID, hostYaml);
            TimelineEntry started = engine.appendAt(
                    game, Round12NbaFixtures.startGame(), T0 + 100L);
            TimelineEntry scored = engine.appendAt(
                    game, Round12NbaFixtures.homeScores(2), T0 + 200L);
            TimelineEntry activation = engine.appendAt(
                    owner,
                    Operation.yaml(
                            "activateGameProcessing", "ownerChannel", "{}"),
                    T0 + 1_000L);
            TimelineEntry nextParent = engine.appendAt(
                    owner,
                    Operation.yaml(
                            "observeCaughtUpGame", "ownerChannel", "{}"),
                    T0 + 2_000L);

            assertEquals(0, engine.routeTargetCount(started));
            assertEquals(0, engine.routeTargetCount(scored));
            assertTrue(started.sourceOrderKey().compareTo(
                    activation.sourceOrderKey()) < 0);
            assertTrue(scored.sourceOrderKey().compareTo(
                    activation.sourceOrderKey()) < 0);
            assertTrue(activation.sourceOrderKey().compareTo(
                    nextParent.sourceOrderKey()) < 0);

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.dispatch(nextParent);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(SessionStatus.READY,
                    engine.session(HOST_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.session(GAME_ID).status());
            assertEquals(Map.of("/game", GAME_ID),
                    engine.embeddedDocuments(HOST_ID));
            assertEquals("Live", text(
                    engine, HOST_ID, "/observedStatusBeforeNext"));
            assertEquals(2L, integer(
                    engine, HOST_ID, "/observedHomeScoreBeforeNext"));
            assertEquals(1L, integer(
                    engine, HOST_ID, "/nextParentEntryCount"));
            assertEquals(2L, integer(engine, GAME_ID, "/homeScore"));
            assertEquals(1L, integer(
                    engine, GAME_ID, "/initializationCount"));

            List<DocumentRevision> childHistory = engine.history(GAME_ID);
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.TIMELINE_ENTRY),
                    childHistory.stream().map(DocumentRevision::kind).toList());
            assertEquals(List.of(
                            activation.blueId(),
                            started.blueId(),
                            scored.blueId()),
                    childHistory.stream()
                            .map(revision -> revision.causalEntryBlueId()
                                    .orElseThrow())
                            .toList());
            assertEquals(List.of(started.blueId(), scored.blueId()),
                    childHistory.stream()
                            .flatMap(revision -> revision.sourceEntry().stream())
                            .map(TimelineEntry::blueId)
                            .toList());
            assertFalse(childHistory.stream()
                    .flatMap(revision -> revision.sourceEntry().stream())
                    .anyMatch(entry -> entry.blueId().equals(
                            activation.blueId())),
                    "the activating parent entry cannot enter child history");

            List<DocumentRevision> parentAfterInitialization =
                    engine.history(HOST_ID).subList(
                            1, engine.history(HOST_ID).size());
            assertEquals(List.of(
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY),
                    parentAfterInitialization.stream()
                            .map(DocumentRevision::kind)
                            .toList());
            assertEquals(List.of(
                            activation.blueId(),
                            activation.blueId(),
                            started.blueId(),
                            scored.blueId(),
                            nextParent.blueId()),
                    parentAfterInitialization.stream()
                            .map(revision -> revision.causalEntryBlueId()
                                    .orElseThrow())
                            .toList(),
                    "initialization and both historical epochs must precede "
                            + "the next parent PROCESS call");
            DocumentRevision observed = parentAfterInitialization.get(4);
            assertEquals("Live", observed.before().orElseThrow()
                    .canonicalAt("/game/status").getValue());
            assertEquals(2L, integerValue(
                    observed, "/game/homeScore"));

            assertEquals(1L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(2L, work.counter(
                    "catchUp.childEntriesProcessed"));
            assertEquals(1L, work.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(3L, work.counter(
                    "temporal.parentEpochApplications"));
            assertNoGenericSplitting(work);
        }
    }

    private static String withCatchUpObservation(String hostYaml) {
        return hostYaml
                .replace(
                        "revisionApplications: 0",
                        "revisionApplications: 0\n"
                                + "nextParentEntryCount: 0\n"
                                + "observedStatusBeforeNext: None\n"
                                + "observedHomeScoreBeforeNext: 0")
                .replace(
                        "  coordinationApplyEmbeddedRevision:\n",
                        "  observeCaughtUpGame:\n"
                                + "    type: Coordination/Sequential Workflow Operation\n"
                                + "    channel: ownerChannel\n"
                                + "    request: {}\n"
                                + "    steps:\n"
                                + "      - type: Coordination/Compute\n"
                                + "        do:\n"
                                + "          - $appendChange:\n"
                                + "              op: replace\n"
                                + "              path: /observedStatusBeforeNext\n"
                                + "              val: {$document: /game/status}\n"
                                + "          - $appendChange:\n"
                                + "              op: replace\n"
                                + "              path: /observedHomeScoreBeforeNext\n"
                                + "              val: {$document: /game/homeScore}\n"
                                + "          - $appendChange:\n"
                                + "              op: replace\n"
                                + "              path: /nextParentEntryCount\n"
                                + "              val: {$add: [$document: /nextParentEntryCount, 1]}\n"
                                + "          - $return: true\n"
                                + "  coordinationApplyEmbeddedRevision:\n");
    }

    private static long integerValue(
            DocumentRevision revision,
            String path) {
        Object value = revision.before().orElseThrow()
                .canonicalAt(path).getValue();
        if (value instanceof java.math.BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected Integer at " + path
                + " but got " + value);
    }
}
