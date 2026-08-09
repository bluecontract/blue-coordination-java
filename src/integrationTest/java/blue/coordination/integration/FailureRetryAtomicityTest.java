package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** State, cursors, indexes, and receipts publish as one retryable unit. */
final class FailureRetryAtomicityTest {
    @Test
    void stagedCatchUpRollsBackAndRetryCommitsEachFactOnce() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));
            engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"));

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            var attachment = engine.append(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            assertEquals(0L, engine.session(
                    "embedded-state-parent").epoch());
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());

            engine.clearFailureInjection();
            engine.dispatch(attachment);
            assertEquals(3L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(3, engine.history("embedded-counter-A").size());
            assertEquals(5, engine.history(
                    "embedded-state-parent").size());

            EngineMetrics.MetricsSnapshot beforeDuplicate =
                    engine.metricsSnapshot();
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta duplicate = delta(
                    beforeDuplicate, engine.metricsSnapshot());
            assertEquals(0L, duplicate.counter("frozenProcessCalls"));
        }
    }

    @Test
    void committedStateWithLostResponseIsReconciledFromDeliveryReceipt()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start(
                    "counter",
                    resource("examples/clean/counter.yaml"));
            var entry = engine.append(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(entry));
            assertEquals(3L, integer(engine, "counter", "/counter"));

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();
            assertEquals(1, engine.dispatch(entry).outcomes().size());
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());
            assertEquals(0L, retry.counter("frozenProcessCalls"));
            assertEquals(3L, integer(engine, "counter", "/counter"));
        }
    }
    @Test
    void failedProcessorManagedParentRevisionRestoresJournalFrontier()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-middle.yaml");
            engine.start("embedded-middle-A", childInitial);
            engine.start(
                    "embedded-root-B",
                    resource("examples/clean/embedded-root.yaml"));
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/root", "root-owner");
            var attachment = engine.append(
                    rootTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            int journalBeforeDispatch = engine.journalSize();

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));

            assertEquals(journalBeforeDispatch, engine.journalSize(),
                    "The failed processor-managed revision must not leak into "
                            + "the external journal frontier");
            assertEquals(1L, engine.metricsSnapshot().counters()
                    .getOrDefault("journal.rollbacks", 0L));
            assertEquals(0L, engine.session("embedded-root-B").epoch());
            assertTrue(engine.embeddedDocuments(
                    "embedded-root-B").isEmpty());

            engine.clearFailureInjection();
            engine.dispatch(attachment);
            assertEquals(journalBeforeDispatch + 1, engine.journalSize(),
                    "Exactly one processor-managed child revision is committed");
            assertEquals(2L, engine.session("embedded-root-B").epoch());
            assertEquals(1L, integer(
                    engine,
                    "embedded-root-B",
                    "/childRevisionApplications"));

            int journalAfterCommit = engine.journalSize();
            engine.dispatch(attachment);
            assertEquals(journalAfterCommit, engine.journalSize(),
                    "Delivery receipt replay must not append another internal "
                            + "revision event");
            var next = engine.append(
                    rootTimeline,
                    Operation.yaml("ignored", "ownerChannel", "{}"));
            assertEquals(attachment.timestampMicros() + 2L,
                    next.timestampMicros());
            assertEquals(attachment.globalSequence() + 2L,
                    next.globalSequence());
        }
    }

}
