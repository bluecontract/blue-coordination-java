package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Timeline;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Existing child revisions cross the exact parent PROCESS boundary once each. */
final class ExistingEmbeddedStateOnlyCatchUpTest {
    @Test
    void attachmentReusesChildHistoryAndProcessesEveryParentEpochExactlyOnce()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            for (int index = 0; index < 20; index++) {
                engine.appendAndDispatch(
                        childTimeline,
                        Operation.yaml(
                                "increment", "ownerChannel", "amount: 1"));
            }
            int childRevisions = engine.history(
                    "embedded-counter-A").size();

            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            // when
            engine.appendAndDispatch(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            // then
            assertEquals(20L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
            assertEquals(childRevisions, engine.history(
                    "embedded-counter-A").size());
            assertEquals(22L, work.counter("frozenProcessCalls"),
                    "attachment plus initialization and twenty child epochs");
            assertEquals(21L, work.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
            assertEquals(21L, work.counter("childRevisionApplications"));
            assertEquals(21L, work.counter(
                    "revisionApplicationReceiptsCommitted"));

            List<Long> appliedStates = engine.history("embedded-state-parent")
                    .stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                    .map(ExistingEmbeddedStateOnlyCatchUpTest::childCounter)
                    .toList();
            assertEquals(21, appliedStates.size());
            assertEquals(0L, appliedStates.get(0));
            for (int index = 1; index < appliedStates.size(); index++) {
                assertEquals((long) index, appliedStates.get(index),
                        "every parent revision must retain the corresponding "
                                + "intermediate child state");
            }
        }
    }

    private static long childCounter(DocumentRevision revision) {
        FrozenNode value = revision.after().canonicalAt("/child/counter");
        if (value == null || value.getValue() == null) {
            throw new AssertionError("Missing /child/counter in parent revision");
        }
        Object scalar = value.getValue();
        if (scalar instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (scalar instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected numeric child counter, got " + scalar);
    }
}
