package blue.coordination.integration;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentRevision;
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

/** One managed child advances once and publishes one epoch to each parent. */
final class SharedManagedChildTwoParentsTest {
    @Test
    void oneChildRevisionConvergesTwoParentsWithoutReprocessingTheChild()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline firstParentTimeline = engine.timeline(
                    "examples/embedded/parent-one", "bob-one");
            Timeline secondParentTimeline = engine.timeline(
                    "examples/embedded/parent-two", "bob-two");

            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            engine.start(
                    "embedded-parent-one",
                    parentDefinition(
                            "embedded-parent-one",
                            "examples/embedded/parent-one",
                            "bob-one"));
            engine.appendAndDispatch(
                    firstParentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            engine.start(
                    "embedded-parent-two",
                    parentDefinition(
                            "embedded-parent-two",
                            "examples/embedded/parent-two",
                            "bob-two"));
            engine.appendAndDispatch(
                    secondParentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            assertEquals(2L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(2L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            int childHistoryBefore = engine.history("embedded-counter-A").size();
            int firstParentHistoryBefore = engine.history(
                    "embedded-parent-one").size();
            int secondParentHistoryBefore = engine.history(
                    "embedded-parent-two").size();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 5"));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(7L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(7L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(7L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            assertEquals(childHistoryBefore + 1,
                    engine.history("embedded-counter-A").size());
            assertEquals(firstParentHistoryBefore + 1,
                    engine.history("embedded-parent-one").size());
            assertEquals(secondParentHistoryBefore + 1,
                    engine.history("embedded-parent-two").size());
            assertEquals(3L, work.counter(
                    "process.frozenContractsInvocations"),
                    "one child and two exact parent PROCESS invocations");
            assertEquals(1L, work.counter(
                    "temporal.externalProcessCalls"));
            assertEquals(2L, work.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(2L, work.counter(
                    "catchUp.parentRevisionApplications"));
            assertEquals(2L, work.counter("childRevisionApplications"));
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
        }
    }

    @Test
    void failedSharedParentRetryRunsOnlyItsMissingApplication()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline firstParentTimeline = engine.timeline(
                    "examples/embedded/parent-one", "bob-one");
            Timeline secondParentTimeline = engine.timeline(
                    "examples/embedded/parent-two", "bob-two");

            engine.start("embedded-counter-A", childInitial);
            engine.start(
                    "embedded-parent-one",
                    parentDefinition(
                            "embedded-parent-one",
                            "examples/embedded/parent-one",
                            "bob-one"));
            engine.appendAndDispatch(
                    firstParentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            engine.start(
                    "embedded-parent-two",
                    parentDefinition(
                            "embedded-parent-two",
                            "examples/embedded/parent-two",
                            "bob-two"));
            engine.appendAndDispatch(
                    secondParentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();
            int firstParentHistoryBefore = engine.history(
                    "embedded-parent-one").size();
            int secondParentHistoryBefore = engine.history(
                    "embedded-parent-two").size();
            EngineMetrics.MetricsSnapshot beforeFailure =
                    engine.metricsSnapshot();
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_APPLYING_CHILD_REVISION);
            var childEntry = engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 5"));
            assertThrows(RuntimeException.class, engine::drain);
            EngineTestSupport.MetricDelta failed = delta(
                    beforeFailure, engine.metricsSnapshot());

            assertEquals(2L, failed.counter("frozenProcessCalls"),
                    "one child and one committed parent PROCESS run");
            assertEquals(1L, failed.counter(
                    "temporal.externalProcessCalls"));
            assertEquals(1L, failed.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(1L, failed.counter(
                    "PARENT_EPOCH_APPLICATIONS"));
            assertEquals(childHistoryBefore + 1,
                    engine.history("embedded-counter-A").size());
            assertEquals(firstParentHistoryBefore + 1,
                    engine.history("embedded-parent-one").size(),
                    "the first parent commit survives its lost response");
            assertEquals(secondParentHistoryBefore,
                    engine.history("embedded-parent-two").size(),
                    "the other parent remains independently pending");
            assertEquals(5L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(5L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(0L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            assertEquals(0L, plan(
                    engine, "embedded-parent-one").link()
                    .appliedChildEpoch(),
                    "cursor stays behind until commit-companion recovery");
            assertEquals(0L, plan(
                    engine, "embedded-parent-two").link()
                    .appliedChildEpoch());
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("embedded-parent-one").status());
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("embedded-parent-two").status());
            assertNotReady(engine, "embedded-parent-one");
            assertNotReady(engine, "embedded-parent-two");

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeRetry =
                    engine.metricsSnapshot();

            // when
            assertTrue(engine.drain().quiescent());
            EngineTestSupport.MetricDelta retry = delta(
                    beforeRetry, engine.metricsSnapshot());

            // then
            assertEquals(1L, retry.counter("frozenProcessCalls"),
                    "retry runs only the failed second-parent application");
            assertEquals(0L, retry.counter("temporal.externalProcessCalls"));
            assertEquals(1L, retry.counter(
                    "PARENT_EPOCH_APPLICATIONS"));
            assertTrue(retry.nanos("process.hostAfterFrozen") > 0L);
            assertEquals(childHistoryBefore + 1,
                    engine.history("embedded-counter-A").size());
            assertEquals(firstParentHistoryBefore + 1,
                    engine.history("embedded-parent-one").size(),
                    "the committed parent is reconciled, not processed again");
            assertEquals(secondParentHistoryBefore + 1,
                    engine.history("embedded-parent-two").size());
            assertEquals(1L, revisionsCausedBy(
                    engine, "embedded-counter-A", childEntry.blueId()));
            assertEquals(1L, revisionsCausedBy(
                    engine, "embedded-parent-one", childEntry.blueId()));
            assertEquals(1L, revisionsCausedBy(
                    engine, "embedded-parent-two", childEntry.blueId()));
            assertEquals(5L, integer(
                    engine, "embedded-parent-one", "/child/counter"));
            assertEquals(5L, integer(
                    engine, "embedded-parent-two", "/child/counter"));
            assertEquals(1L, plan(
                    engine, "embedded-parent-one").link()
                    .appliedChildEpoch());
            assertEquals(1L, plan(
                    engine, "embedded-parent-two").link()
                    .appliedChildEpoch());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument("embedded-parent-one").status());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument("embedded-parent-two").status());
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

    private static long revisionsCausedBy(
            TestEngine engine,
            String documentId,
            String entryBlueId) {
        return engine.history(documentId).stream()
                .filter(revision -> revision.kind()
                        != DocumentRevision.Kind.INITIALIZATION)
                .filter(revision -> revision.causalEntryBlueId()
                        .filter(entryBlueId::equals)
                        .isPresent())
                .count();
    }

    private static CatchUpPlan plan(
            TestEngine engine,
            String parentDocumentId) {
        return engine.catchUpPlans().stream()
                .filter(candidate -> candidate.link().parentDocumentId()
                        .value().equals(parentDocumentId))
                .findFirst()
                .orElseThrow();
    }

    private static String parentDefinition(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("coordination/internal/embedded-state-parent",
                        "coordination/internal/" + documentId)
                .replace("accountId: bob", "accountId: " + actorId);
    }
}
