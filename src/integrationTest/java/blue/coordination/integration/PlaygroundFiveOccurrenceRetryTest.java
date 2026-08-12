package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Retry proof for a partially published five-occurrence initialization. */
final class PlaygroundFiveOccurrenceRetryTest {
    @Test
    void retryCompletesFiveOccurrenceFanoutWithoutReinitializingChildren()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.HOST_TIMELINE,
                    PlaygroundFiveOccurrenceFixtures.HOST_ACTOR);
            PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
            engine.start(
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    resource(PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE));
            TimelineEntry attachment = engine.append(
                    owner,
                    PlaygroundFiveOccurrenceFixtures.attachFive(engine));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.failOnceAt(
                    TestEngine.FailurePoint.AFTER_APPLYING_CHILD_REVISION);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session(PlaygroundFiveOccurrenceFixtures.HOST_ID)
                            .status());
            assertEquals(1L, integer(
                    engine,
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/initializationEventCount"));
            assertEquals(1L, integer(
                    engine,
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/revisionApplications"));
            assertEquals(1L, engine.history(
                            PlaygroundFiveOccurrenceFixtures.HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                            .EMBEDDED_REVISION_APPLICATION)
                    .count(), "the failure follows one committed parent prefix");
            for (String child : childIds()) {
                assertEquals(1L, initializationRevisions(engine, child),
                        child + " must already be initialized before retry");
                assertEquals(1L, integer(
                        engine, child, "/initializationCount"), child);
            }
            EngineTestSupport.MetricDelta failedAttempt = delta(
                    before, engine.metricsSnapshot());
            assertEquals(1L,
                    failedAttempt.counter("EXTERNAL_PROCESS_CALLS"));
            assertEquals(3L,
                    failedAttempt.counter("DOCUMENT_INITIALIZATIONS"));
            assertEquals(1L,
                    failedAttempt.counter("EMBEDDED_EPOCH_PROCESS_CALLS"));
            engine.clearFailureInjection();

            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta completed = delta(
                    before, engine.metricsSnapshot());

            assertEquals(SessionStatus.READY,
                    engine.readyDocument(
                            PlaygroundFiveOccurrenceFixtures.HOST_ID).status());
            assertEquals(5L, integer(
                    engine,
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/initializationEventCount"));
            assertEquals(5L, integer(
                    engine,
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    "/revisionApplications"));
            assertEquals(5L, engine.history(
                            PlaygroundFiveOccurrenceFixtures.HOST_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                            .EMBEDDED_REVISION_APPLICATION)
                    .count());
            for (String child : childIds()) {
                assertEquals(1L, initializationRevisions(engine, child),
                        child);
                assertEquals(1L, integer(
                        engine, child, "/initializationCount"), child);
            }
            assertEquals(1L, completed.counter("EXTERNAL_PROCESS_CALLS"),
                    "retry cannot rerun the external Host PROCESS");
            assertEquals(3L, completed.counter("DOCUMENT_INITIALIZATIONS"),
                    "retry cannot reinitialize a managed child");
            assertEquals(5L,
                    completed.counter("EMBEDDED_EPOCH_PROCESS_CALLS"),
                    "the committed prefix plus four remaining occurrences "
                            + "must each cross PROCESS exactly once");
            assertNoGenericSplitting(completed);
        }
    }

    private static List<String> childIds() {
        return List.of(
                PlaygroundFiveOccurrenceFixtures.ALPHA_ID,
                PlaygroundFiveOccurrenceFixtures.BETA_ID,
                PlaygroundFiveOccurrenceFixtures.GAMMA_ID);
    }

    private static long initializationRevisions(
            TestEngine engine,
            String child) {
        return engine.history(child).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.INITIALIZATION)
                .count();
    }
}
