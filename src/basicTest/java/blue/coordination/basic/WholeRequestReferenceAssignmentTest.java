package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactNodeValue;
import blue.coordination.basic.engine.Timeline;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static blue.coordination.basic.BasicEngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stores one canonical PayNote as one ordinary whole-value field, never fragments. */
@Tag("performance")
final class WholeRequestReferenceAssignmentTest {
    @Test
    void payNoteIsAssignedAsOneWholeValueWithNoEmbeddedOrGenericFragments()
            throws Exception {
        String payNoteYaml = resource("examples/clean/package-paynote.yaml");
        try (BasicTestMetrics report = BasicTestMetrics.start(
                "whole-paynote-reference-assignment",
                "Whole PayNote reference assignment");
             BasicTestMetrics.MeasuredResource<BasicCoordinationEngine> managed =
                     report.manage(
                             "07 close environment",
                             report.measure(
                                     "01 start environment",
                                     BasicCoordinationEngine::create))) {
            BasicCoordinationEngine engine = managed.value();
            Timeline alice = report.measure(
                    "02 add Alice timeline",
                    () -> engine.timeline(
                            "examples/whole-request/alice", "alice"));
            report.measure(
                    "03 start sink",
                    () -> engine.start(
                            "whole-request-sink",
                            resource("examples/clean/whole-request-sink.yaml")));
            ExactNodeValue request = report.measure(
                    "04 retain exact PayNote request",
                    () -> engine.referencedValueRequest(
                            "payload", payNoteYaml));

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            var entry = report.measure(
                    "05 append whole request reference",
                    () -> engine.append(
                            alice,
                            BasicOperation.exact(
                                    "storePayload",
                                    "aliceChannel",
                                    request)));
            report.measure("06 PROCESS one Root", () -> engine.dispatch(entry));
            BasicEngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            Node stored = engine.value(
                    "whole-request-sink", "/payload");
            String storedBlueId = stored.isReferenceOnly()
                    ? stored.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(stored);
            Node exactPayNote = engine.exactRequest(payNoteYaml).copyNode();
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(exactPayNote),
                    storedBlueId);
            assertEquals(1,
                    engine.session("whole-request-sink")
                            .layout().physicalObjectCount());
            assertEquals(0,
                    engine.session("whole-request-sink")
                            .layout().embeddedDocumentCount());
            assertEquals(1L, work.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(0L, work.counter("layout.catalogCompilations"));
            assertNoGenericSplitting(work);

            if (Boolean.getBoolean("basic.strictPerformance")) {
                assertTrue(work.nanos("process.frozenContractsOnce")
                                <= 1_000_000_000L,
                        "Frozen assignment PROCESS exceeded one second");
            }
            report.detail(
                            "whole-paynote-engine-work",
                            "06 PROCESS one Root")
                    .counter("frozen PROCESS calls", work.counter(
                            "process.frozenContractsInvocations"))
                    .counter("embedded documents", engine.session(
                            "whole-request-sink").layout()
                            .embeddedDocumentCount())
                    .counter("ordinary fragments", work.counter(
                            "layout.ordinaryNodeFragments"))
                    .phase("frozen Contracts PROCESS", work.nanos(
                            "process.frozenContractsOnce"))
                    .phase("embedded-only layout", work.nanos(
                            "layout.retainEmbeddedOnly"));
        }
    }
}
