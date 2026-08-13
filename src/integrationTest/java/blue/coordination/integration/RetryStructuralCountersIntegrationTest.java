package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production retry paths account for any actual duplicate frozen PROCESS. */
final class RetryStructuralCountersIntegrationTest {
    @Test
    void graphRetryReconcilesACommittedParentWithoutAnotherProcess()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            var attachment = engine.append(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(resource(
                                    "examples/clean/embedded-counter.yaml"))));

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STAGING_CHILD_SESSION);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            assertEquals(1L, engine.session(
                    "embedded-state-parent").epoch());
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status());

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            assertTrue(retry.counters().containsKey(
                    "temporal.parentProcessRerunsOnGraphRetry"),
                    "the graph-retry monitor must produce its raw source");
            assertEquals(0L, retry.counter(
                    "process.externalInvocationsStarted"));
            assertEquals(0L, retry.counter(
                    "PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY"));
            assertEquals(0L, retry.counter(
                    "CHILD_PROCESS_RERUNS_ON_PARENT_RETRY"));
            EngineTestSupport.assertNoGenericSplitting(retry);
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
        }
    }

    @Test
    void parentRetryReconcilesACommittedChildWithoutAnotherProcess()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childSource = resource(
                    "examples/clean/embedded-counter.yaml");
            engine.start("embedded-counter-A", childSource);
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childSource)));
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            var childEntry = engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 4"));

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(childEntry));
            assertEquals(4L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(0L, integer(
                    engine, "embedded-state-parent", "/child/counter"));

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();
            engine.dispatch(childEntry);
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            assertTrue(retry.counters().containsKey(
                    "temporal.childProcessRerunsOnParentRetry"),
                    "the parent-retry monitor must produce its raw source");
            assertEquals(0L, retry.counter(
                    "process.externalInvocationsStarted"));
            assertEquals(1L, retry.counter(
                    "EMBEDDED_EPOCH_PROCESS_CALLS"));
            assertEquals(0L, retry.counter(
                    "PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY"));
            assertEquals(0L, retry.counter(
                    "CHILD_PROCESS_RERUNS_ON_PARENT_RETRY"));
            EngineTestSupport.assertNoGenericSplitting(retry);
            assertEquals(4L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
        }
    }
}
