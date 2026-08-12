package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Comparator;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Dynamically activating collectionPaths initializes every direct member canonically. */
@Tag("scenario")
final class DynamicProcessEmbeddedCollectionActivationTest {
    private static final String HOST_ID = "dynamic-collection-host";

    @Test
    void collectionMembersInitializeByCanonicalPathNotInsertionOrBlueIdOrder()
            throws Exception {
        Map<String, String> games =
                Round12NbaFixtures.deliberatelyUnorderedGames();
        String hostYaml = Round12NbaFixtures.dynamicCollectionHost(games);

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/dynamic/collection/host",
                    "collection-owner");
            engine.timeline(
                    "examples/round12/nba/game-zulu", "nba-feed-zulu");
            engine.timeline(
                    "examples/round12/nba/game-alpha", "nba-feed-alpha");
            engine.timeline(
                    "examples/round12/nba/game-middle", "nba-feed-middle");
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.start(HOST_ID, hostYaml);
            assertEquals(1, engine.documentCount());
            assertEquals(Map.of(), engine.embeddedDocuments(HOST_ID));

            TimelineEntry activation = engine.append(
                    owner,
                    Operation.yaml(
                            "activateGameCollection", "ownerChannel", "{}"));
            engine.dispatch(activation);

            assertEquals(4, engine.documentCount());
            List<String> canonicalChildren = List.of(
                    "round12-game-alpha",
                    "round12-game-middle",
                    "round12-game-zulu");
            List<String> blueIdOrder = canonicalChildren.stream()
                    .sorted(Comparator.comparing(child ->
                            engine.session(child).current().blueId()))
                    .toList();
            assertNotEquals(canonicalChildren, blueIdOrder,
                    "fixture BlueId order must differ from path order");
            assertEquals(Map.of(
                            "/games/alpha", "round12-game-alpha",
                            "/games/middle", "round12-game-middle",
                            "/games/zulu", "round12-game-zulu"),
                    engine.embeddedDocuments(HOST_ID));
            assertEquals(3L, integer(engine, HOST_ID,
                    "/initializationEventCount"));
            assertEquals(1L, integer(engine, HOST_ID,
                    "/alphaInitializationOrdinal"));
            assertEquals(2L, integer(engine, HOST_ID,
                    "/middleInitializationOrdinal"));
            assertEquals(3L, integer(engine, HOST_ID,
                    "/zuluInitializationOrdinal"));
            assertEquals(3L, integer(engine, HOST_ID,
                    "/revisionApplications"));

            for (String child : canonicalChildren) {
                List<DocumentRevision> history = engine.history(child);
                assertEquals(1, history.size(), child);
                assertEquals(DocumentRevision.Kind.INITIALIZATION,
                        history.get(0).kind(), child);
                assertEquals(activation.blueId(),
                        history.get(0).causalEntryBlueId().orElseThrow(), child);
                assertFalse(history.stream()
                        .flatMap(revision -> revision.sourceEntry().stream())
                        .anyMatch(entry -> entry.blueId().equals(
                                activation.blueId())), child);
                assertEquals(1L, integer(engine, child,
                        "/initializationCount"));
            }

            List<DocumentRevision.Kind> parentKinds = engine.history(HOST_ID)
                    .stream().map(DocumentRevision::kind).toList();
            assertEquals(List.of(
                    DocumentRevision.Kind.INITIALIZATION,
                    DocumentRevision.Kind.TIMELINE_ENTRY,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    parentKinds);

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            assertEquals(3L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(3L, work.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(3L, work.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(3L, work.counter(
                    "temporal.parentEpochApplications"));
            assertNoGenericSplitting(work);
        }
    }
}
