package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Playground acceptance for five embedding occurrences backed by three logical
 * managed documents. The two repeated Game identities initialize once, while
 * the Host observes one initialization-event occurrence for every path.
 */
@Tag("scenario")
final class PlaygroundFiveOccurrenceInitializationTest {
    @Test
    void fiveOccurrencesReuseThreeSessionsAndForwardFiveInitializationEvents()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.HOST_TIMELINE,
                    PlaygroundFiveOccurrenceFixtures.HOST_ACTOR);
            PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
            engine.start(
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    resource(PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE));

            int wholeObjectsBeforeRequest = engine.wholeObjectCount();
            var attachFive = PlaygroundFiveOccurrenceFixtures.attachFive(engine);
            assertEquals(4,
                    engine.wholeObjectCount() - wholeObjectsBeforeRequest,
                    "three unique child bodies plus one whole request");

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry attachment = engine.append(owner, attachFive);
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(4, engine.documentCount(),
                    "one Host plus three unique managed Game sessions");
            assertEquals(PlaygroundFiveOccurrenceFixtures.expectedBindings(),
                    engine.embeddedDocuments(
                            PlaygroundFiveOccurrenceFixtures.HOST_ID));

            assertInitializedExactlyOnce(engine, "playground-game-alpha");
            assertInitializedExactlyOnce(engine, "playground-game-beta");
            assertInitializedExactlyOnce(engine, "playground-game-gamma");

            assertEquals(5L, integer(
                    engine, PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/initializationEventCount"));
            assertEquals(2L, integer(
                    engine, PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/alphaInitializationEventCount"));
            assertEquals(2L, integer(
                    engine, PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/betaInitializationEventCount"));
            assertEquals(1L, integer(
                    engine, PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/gammaInitializationEventCount"));
            assertEquals(5L, integer(
                    engine, PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/revisionApplications"));

            List<String> applicationPaths = engine.history(
                    PlaygroundFiveOccurrenceFixtures.HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                    .map(revision -> revision.catchUpCause()
                            .orElseThrow().occurrencePath())
                    .toList();
            assertEquals(
                    PlaygroundFiveOccurrenceFixtures.expectedApplicationPaths(),
                    applicationPaths,
                    "occurrence delivery is canonical and independent of request insertion order");

            assertEquals(3L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(2L, work.counter(
                    "embedding.childSessionsReused"));
            assertEquals(5L, work.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(5L, work.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(5L, work.counter(
                    "temporal.parentEpochApplications"));
            assertNoGenericSplitting(work);
        }
    }

    private static void assertInitializedExactlyOnce(
            TestEngine engine,
            String documentId) {
        List<DocumentRevision> history = engine.history(documentId);
        assertEquals(1, history.size(), documentId);
        assertEquals(DocumentRevision.Kind.INITIALIZATION,
                history.get(0).kind(), documentId);
        assertEquals(1L, integer(engine, documentId,
                "/initializationCount"), documentId);
    }

}
