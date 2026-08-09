package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.CatchUpPlan;
import blue.coordination.basic.engine.DocumentRevision;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.RevisionKind;
import blue.coordination.basic.engine.SessionStatus;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Existing child history is processed once and imported into a new parent. */
final class ExistingEmbeddedDocumentCatchUpTest {
    private static final long T0 = 1_700_000_000_000_000L;

    @Test
    void originalInitialStateCatchesUpToAttachmentCutoffWithoutReprocessingChild()
            throws Exception {
        try (BasicTestMetrics report = BasicTestMetrics.start(
                "existing-embedded-catch-up",
                "Existing embedded document catch-up");
             BasicTestMetrics.MeasuredResource<BasicCoordinationEngine> managed =
                     report.manage(
                             "09 close environment",
                             report.measure(
                                     "01 start environment",
                                     BasicCoordinationEngine::create))) {
            BasicCoordinationEngine engine = managed.value();
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = report.measure(
                    "02 add child timeline",
                    () -> engine.timeline("examples/embedded/A", "alice"));
            report.measure(
                    "03 start autonomous child",
                    () -> engine.start("embedded-counter-A", childInitial));
            report.measure("04 process three child entries", () -> {
                increment(engine, childTimeline, T0 + 100, 1);
                increment(engine, childTimeline, T0 + 200, 2);
                increment(engine, childTimeline, T0 + 300, 3);
            });
            assertEquals(6L, integer(
                    engine, "embedded-counter-A", "/counter"));
            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();

            Timeline parentTimeline = report.measure(
                    "05 add parent timeline",
                    () -> engine.timeline("examples/embedded/B", "bob"));
            report.measure(
                    "06 start parent",
                    () -> engine.start(
                            "embedded-parent-B",
                            resource("examples/clean/embedded-parent.yaml")));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            report.measure("07 attach original child state and catch up", () -> {
                var attachment = engine.appendAt(
                        parentTimeline,
                        BasicOperation.exact(
                                "attachChild",
                                "ownerChannel",
                                engine.embeddedDocumentRequest(childInitial)),
                        T0 + 1_000);
                engine.dispatch(attachment);
            });
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            report.measure("08 verify temporal consistency", () -> {
                assertEquals(SessionStatus.READY,
                        engine.session("embedded-parent-B").status());
                assertEquals(6L, integer(
                        engine, "embedded-parent-B", "/child/counter"));
                assertEquals(6L, integer(
                        engine, "embedded-parent-B", "/childCounter"));
                assertEquals(4L, integer(
                        engine,
                        "embedded-parent-B",
                        "/childRevisionApplications"));
                assertEquals(childHistoryBefore,
                        engine.history("embedded-counter-A").size(),
                        "Parent catch-up must not rerun child Contracts");
                assertEquals(2,
                        engine.session("embedded-parent-B")
                                .layout().physicalObjectCount());
                assertEquals(
                        Set.of("examples/embedded/A", "examples/embedded/B"),
                        engine.effectiveTimelineIds("embedded-parent-B"));
                assertEquals(
                        "embedded-counter-A",
                        engine.embeddedDocuments("embedded-parent-B")
                                .get("/child"));

                List<DocumentRevision> parentHistory = engine.history(
                        "embedded-parent-B");
                assertEquals(6, parentHistory.size());
                assertEquals(RevisionKind.TIMELINE_ENTRY,
                        parentHistory.get(1).kind());
                for (DocumentRevision revision : parentHistory.subList(
                        2, parentHistory.size())) {
                    assertEquals(
                            RevisionKind.EMBEDDED_REVISION_APPLICATION,
                            revision.kind());
                    assertTrue(revision.catchUpCause().isPresent());
                }
                CatchUpPlan plan = engine.catchUpPlans().get(0);
                assertEquals(CatchUpPlan.Status.COMPLETE, plan.status());
                assertEquals(3L, plan.link().appliedChildEpoch());

                assertEquals(1L, work.counter(
                        "embedding.childSessionsReused"));
                assertEquals(0L, work.counter(
                        "catchUp.childEntriesProcessed"));
                assertEquals(4L, work.counter(
                        "catchUp.parentRevisionApplications"));
                assertEquals(5L, work.counter(
                        "process.frozenContractsInvocations"));
                assertNoGenericSplitting(work);
            });
            report.detail(
                            "existing-child-engine-work",
                            "07 attach original child state and catch up")
                    .counter("child sessions reused", work.counter(
                            "embedding.childSessionsReused"))
                    .counter("child entries reprocessed", work.counter(
                            "catchUp.childEntriesProcessed"))
                    .counter("parent revision applications", work.counter(
                            "catchUp.parentRevisionApplications"))
                    .counter("frozen PROCESS calls", work.counter(
                            "process.frozenContractsInvocations"))
                    .phase("frozen Contracts PROCESS", work.nanos(
                            "process.frozenContractsOnce"))
                    .phase("embedded-only layout", work.nanos(
                            "layout.retainEmbeddedOnly"));
        }
    }

    private static void increment(
            BasicCoordinationEngine engine,
            Timeline timeline,
            long timestamp,
            int amount) {
        var entry = engine.appendAt(
                timeline,
                BasicOperation.of(
                        "increment", "ownerChannel", "amount: " + amount),
                timestamp);
        engine.dispatch(entry);
    }
}
