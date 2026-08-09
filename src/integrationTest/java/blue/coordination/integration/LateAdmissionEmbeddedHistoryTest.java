package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A child first discovered at attachment is initialized and replays journal history once. */
final class LateAdmissionEmbeddedHistoryTest {
    private static final long T0 = 1_710_000_000_000_000L;

    @Test
    void missingChildSessionIsCreatedThenCaughtUpFromCompleteHistory()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");

            // The source history exists before Coordination has admitted A.
            engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"),
                    T0 + 100);
            engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"),
                    T0 + 200);
            engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 3"),
                    T0 + 300);

            engine.start(
                    "embedded-parent-B",
                    resource("examples/clean/embedded-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/B", "bob");
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            var attach = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)),
                    T0 + 1_000);
            engine.dispatch(attach);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(SessionStatus.READY,
                    engine.session("embedded-parent-B").status());
            assertEquals(6L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(6L, integer(
                    engine, "embedded-parent-B", "/childCounter"));
            assertEquals(4, engine.history("embedded-counter-A").size());
            assertEquals(6, engine.history("embedded-parent-B").size());
            assertEquals(1L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(3L, work.counter(
                    "catchUp.childEntriesProcessed"));
            assertEquals(4L, work.counter(
                    "catchUp.parentRevisionApplications"));
            assertEquals(8L, work.counter(
                    "process.frozenContractsInvocations"));
            assertNoGenericSplitting(work);
        }
    }

    @Test
    void laterAppendWithEarlierEventTimeStaysBeyondCapturedFrontier()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"),
                    T0 + 100L);

            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            var attachment = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)),
                    T0 + 1_000L);
            var laterAppend = engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"),
                    T0 + 200L);

            EngineMetrics.MetricsSnapshot beforeAttach =
                    engine.metricsSnapshot();
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta attachWork = delta(
                    beforeAttach, engine.metricsSnapshot());
            assertEquals(1L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(1L, attachWork.counter(
                    "childHistoricalProcessCalls"),
                    "global append sequence, not authored time, closes catch-up");

            engine.dispatch(laterAppend);
            assertEquals(3L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(3L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
        }
    }
}
