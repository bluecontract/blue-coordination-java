package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.SessionStatus;
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
    void stagedChildFailureRollsBackOnlyHostDeltaAndTerminalRetryReconciles()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

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
                            engine.embeddedDocumentRequest(childInitial)));
            int routeRowsBefore = engine.routeRowCount();

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STAGING_CHILD_SESSION);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            assertEquals(1L, engine.session(
                    "embedded-state-parent").epoch(),
                    "the external parent commit is durable");
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status(),
                    "a committed parent with an unpublished graph is not READY");
            assertCommittedAndReadyHeads(
                    engine, "embedded-state-parent", 1L, 0L);
            assertEquals(1, engine.documentCount(),
                    "the staged child is not a committed managed document");
            assertEquals(routeRowsBefore, engine.routeRowCount(),
                    "staged child route rows roll back with its session");
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());
            assertTrue(engine.catchUpPlans().isEmpty(),
                    "graph, barrier, and cursor publication roll back together");
            assertEquals(1L, engine.history("embedded-state-parent").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY)
                    .count());
            int objectsAfterFirstFailure = engine.wholeObjectCount();

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STAGING_CHILD_SESSION);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            assertEquals(1, engine.documentCount());
            assertEquals(routeRowsBefore, engine.routeRowCount());
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());
            assertTrue(engine.catchUpPlans().isEmpty());
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status());
            assertCommittedAndReadyHeads(
                    engine, "embedded-state-parent", 1L, 0L);
            assertEquals(objectsAfterFirstFailure, engine.wholeObjectCount(),
                    "repeated terminal reconciliation has exact object rollback");
            assertEquals(1L, engine.history("embedded-state-parent").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY)
                    .count(),
                    "terminal retry cannot rerun the committed parent PROCESS");

            engine.clearFailureInjection();

            // when
            engine.dispatch(attachment);

            // then
            assertEquals(2, engine.documentCount());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
            assertEquals(engine.session(
                    "embedded-state-parent").epoch(), engine.readyDocument(
                    "embedded-state-parent").epoch());
            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "embedded-state-parent").get("/child"));
            assertEquals(1L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            CatchUpPlan evidence = engine.catchUpPlans().get(0);
            assertEquals(CatchUpPlan.Status.COMPLETE, evidence.status());
            assertEquals(1L, evidence.link().appliedChildEpoch());
            assertEquals(1L, evidence.link().activationGeneration(),
                    "rolled-back activation state must not skip a generation");
        }
    }

    @Test
    void childCommitSurvivesARepeatedFailureBeforeParentCommit()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            var liveChildEntry = engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 4"));
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(liveChildEntry));
            assertEquals(1L, engine.session("embedded-counter-A").epoch());
            assertEquals(4L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(0L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status(),
                    "a parent becomes non-ready as soon as its child commits");
            assertCommittedAndReadyHeads(
                    engine, "embedded-state-parent", 2L, 2L);

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_FROZEN_BEFORE_STAGE);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(liveChildEntry));
            assertEquals(1L, engine.session("embedded-counter-A").epoch(),
                    "parent failure cannot roll back the child commit");
            assertEquals(2, engine.history("embedded-counter-A").size());
            assertEquals(0L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(0L, engine.catchUpPlans().get(0)
                    .link().appliedChildEpoch());
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status());
            assertCommittedAndReadyHeads(
                    engine, "embedded-state-parent", 2L, 2L);

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();

            // when
            engine.dispatch(liveChildEntry);
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            // then
            assertEquals(1L, retry.counter("frozenProcessCalls"),
                    "retry runs only the missing parent application");
            assertEquals(2, engine.history("embedded-counter-A").size());
            assertEquals(4L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(1L, engine.catchUpPlans().get(0)
                    .link().appliedChildEpoch());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
            assertEquals(engine.session(
                            "embedded-state-parent").epoch(),
                    engine.readyDocument(
                            "embedded-state-parent").epoch());
        }
    }

    @Test
    void restartRebuildsQueueAndAppliesOnlyTheMissingParentTransition()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            var childEntry = engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 5"));
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(childEntry));
            assertEquals(5L, integer(
                    engine, "embedded-counter-A", "/counter"));

            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_FROZEN_BEFORE_STAGE);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(childEntry));
            assertEquals(0L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(2, engine.history("embedded-counter-A").size());
            assertEquals(0L, engine.catchUpPlans().get(0)
                    .link().appliedChildEpoch());

            engine.forceLegacyReadyMarker("embedded-state-parent");
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status(),
                    "simulate the stale marker retained by a pre-Round-11 "
                            + "crash boundary");
            assertNotReady(engine, "embedded-state-parent");
            EngineMetrics.MetricsSnapshot beforeRestart =
                    engine.metricsSnapshot();

            // when
            engine.restartFromStores();
            EngineTestSupport.MetricDelta restart = delta(
                    beforeRestart, engine.metricsSnapshot());
            assertEquals(SessionStatus.CATCHING_UP, engine.session(
                    "embedded-state-parent").status(),
                    "restart must normalize stale READY evidence");
            assertEquals(1L, restart.counter(
                    "temporal.recoveredReadinessRepairs"));
            assertCommittedAndReadyHeads(
                    engine, "embedded-state-parent", 2L, 2L);
            EngineMetrics.MetricsSnapshot beforeResume =
                    engine.metricsSnapshot();
            engine.dispatch(childEntry);
            EngineTestSupport.MetricDelta resume = delta(
                    beforeResume, engine.metricsSnapshot());

            // then
            assertEquals(5L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(2, engine.history("embedded-counter-A").size(),
                    "recovery must not reinitialize or reprocess the child");
            assertEquals(0L, resume.counter("temporal.externalProcessCalls"),
                    "the committed child PROCESS must not replay");
            assertEquals(1L, resume.counter(
                    "process.embeddedEpochProcessCalls"),
                    "resume executes exactly the missing parent application");
            assertEquals(1L, resume.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(1L, resume.counter(
                    "temporal.parentEpochApplications"));
            assertEquals(1L, engine.catchUpPlans().get(0)
                    .link().appliedChildEpoch());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
            assertEquals(engine.session(
                            "embedded-state-parent").epoch(),
                    engine.readyDocument(
                            "embedded-state-parent").epoch());
            assertEquals(1L, engine.history("embedded-counter-A").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
            assertEquals(1L, engine.history(
                            "embedded-state-parent").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
        }
    }

    @Test
    void committedEmbeddedEpochSurvivesRestartAndReconcilesItsCursorOnce()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
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
            assertEquals(2L, engine.session(
                    "embedded-state-parent").epoch());
            assertEquals("embedded-counter-A", engine.embeddedDocuments(
                    "embedded-state-parent").get("/child"));
            assertEquals(0L, engine.session("embedded-counter-A").epoch());

            // when
            engine.restartFromStores();
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

            // then
            assertEquals(0L, duplicate.counter("frozenProcessCalls"));
        }
    }

    @Test
    void committedStateWithLostResponseIsReconciledFromDeliveryReceipt()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start(
                    "counter",
                    resource("examples/clean/counter.yaml"));
            var entry = engine.append(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"));
            EngineMetrics.MetricsSnapshot beforeFailure =
                    engine.metricsSnapshot();
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);
            assertThrows(
                    TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(entry));
            assertEquals(3L, integer(engine, "counter", "/counter"));
            assertEquals(1L, delta(beforeFailure, engine.metricsSnapshot())
                    .counter("EXTERNAL_PROCESS_CALLS"));

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();

            // when
            assertEquals(0, engine.dispatch(entry).outcomes().size(),
                    "receipt reconciliation commits no new transition in "
                            + "the retry call");
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            // then
            assertEquals(0L, retry.counter("frozenProcessCalls"));
            assertEquals(0L, retry.counter("EXTERNAL_PROCESS_CALLS"));
            assertEquals(3L, integer(engine, "counter", "/counter"));
        }
    }
    @Test
    void privateEmbeddedInputNeverChangesExternalJournalFrontier()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
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
                    "processor-owned embedded input is never journaled");
            assertEquals(0L, engine.metricsSnapshot().counters()
                    .getOrDefault("journal.rollbacks", 0L));
            assertEquals(2L, engine.session("embedded-root-B").epoch());
            assertEquals("embedded-middle-A", engine.embeddedDocuments(
                    "embedded-root-B").get("/child"));

            engine.clearFailureInjection();

            // when
            engine.dispatch(attachment);
            assertEquals(journalBeforeDispatch, engine.journalSize(),
                    "retry reconciles the document-local receipt only");
            assertEquals(2L, engine.session("embedded-root-B").epoch());
            assertEquals(1L, integer(
                    engine,
                    "embedded-root-B",
                    "/childRevisionApplications"));

            int journalAfterCommit = engine.journalSize();
            engine.dispatch(attachment);
            assertEquals(journalAfterCommit, engine.journalSize(),
                    "receipt replay cannot append an internal event");
            var next = engine.append(
                    rootTimeline,
                    Operation.yaml("ignored", "ownerChannel", "{}"));

            // then
            assertEquals(attachment.timestampMicros() + 1L,
                    next.timestampMicros());
            assertEquals(attachment.globalSequence() + 1L,
                    next.globalSequence());
        }
    }

    private static void assertNotReady(
            TestEngine engine,
            String documentId) {
        CoordinationException failure = assertThrows(
                CoordinationException.class,
                () -> engine.readyDocument(documentId));
        assertEquals(CoordinationErrorCode.DOCUMENT_NOT_READY,
                failure.code());
    }

    private static void assertCommittedAndReadyHeads(
            TestEngine engine,
            String documentId,
            long expectedCommittedEpoch,
            long expectedReadyEpoch) {
        TestEngine.DocumentView committed = engine.session(documentId);
        TestEngine.DocumentView ready = engine.readyDocument(documentId);
        assertEquals(SessionStatus.CATCHING_UP, committed.status());
        assertEquals(expectedCommittedEpoch, committed.epoch());
        assertEquals(SessionStatus.CATCHING_UP, ready.status());
        assertEquals(expectedReadyEpoch, ready.epoch());
        assertEquals(
                engine.history(documentId)
                        .get(Math.toIntExact(expectedCommittedEpoch))
                        .after()
                        .blueId(),
                committed.current().blueId(),
                "audit state must expose the exact committed revision");
        assertEquals(
                engine.history(documentId)
                        .get(Math.toIntExact(expectedReadyEpoch))
                        .after()
                        .blueId(),
                ready.current().blueId(),
                "normal reads must remain fenced to the exact ready state");
    }

}
