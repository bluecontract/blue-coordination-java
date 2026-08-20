package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A normal inline value becomes managed only after a contract delta adds /game. */
final class DynamicProcessEmbeddedPathActivationTest {
    private static final String HOST_ID = "dynamic-path-host";
    private static final String GAME_ID = "round12-dynamic-path-game";

    @Test
    void addingProcessEmbeddedPathInitializesExistingInlineDocument()
            throws Exception {
        // given
        String gameYaml = Round12NbaFixtures.game(
                GAME_ID,
                "examples/round12/dynamic/path/game",
                "round12-path-feed");
        String hostYaml = Round12NbaFixtures.dynamicPathHost(gameYaml);

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/dynamic/path/host",
                    "path-owner");
            Timeline game = engine.timeline(
                    "examples/round12/dynamic/path/game",
                    "round12-path-feed");
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.start(HOST_ID, hostYaml);
            assertEquals(1, engine.documentCount());
            assertEquals(Map.of(), engine.embeddedDocuments(HOST_ID));
            assertEquals(1, engine.history(HOST_ID).size());
            assertEquals(1L, integer(engine, HOST_ID,
                    "/hostInitializationCount"));

            // when
            TimelineEntry activation = engine.append(
                    owner,
                    Operation.yaml(
                            "activateGameProcessing", "ownerChannel", "{}"));
            engine.dispatch(activation);

            assertEquals(2, engine.documentCount());
            assertEquals(Map.of("/game", GAME_ID),
                    engine.embeddedDocuments(HOST_ID));
            assertEquals(1, engine.history(GAME_ID).size());
            DocumentRevision initialization = engine.history(GAME_ID).get(0);
            assertEquals(DocumentRevision.Kind.INITIALIZATION,
                    initialization.kind());
            assertTrue(initialization.sourceEntry().isEmpty());
            assertEquals(activation.blueId(),
                    initialization.causalEntryBlueId().orElseThrow());
            assertEquals(activation.blueId(),
                    initialization.catchUpCause().orElseThrow()
                            .attachmentEntryBlueId());
            assertEquals(1L, integer(engine, GAME_ID,
                    "/initializationCount"));
            assertTrue(Boolean.TRUE.equals(engine.value(
                    HOST_ID, "/gameInitialized").getValue()));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/gameInitializationEventCount"));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/revisionApplications"));
            assertFalse(engine.history(GAME_ID).stream()
                    .flatMap(revision -> revision.sourceEntry().stream())
                    .anyMatch(entry -> entry.blueId().equals(
                            activation.blueId())),
                    "the entry that activates /game must not be delivered to it");

            engine.appendAndDispatch(game, Round12NbaFixtures.startGame());
            assertEquals("Live", EngineTestSupport.text(
                    engine, HOST_ID, "/game/status"));
            assertEquals(2L, integer(engine, HOST_ID,
                    "/revisionApplications"));

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(1L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(1L, work.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(1L, work.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertNoGenericSplitting(work);
        }
    }
}
