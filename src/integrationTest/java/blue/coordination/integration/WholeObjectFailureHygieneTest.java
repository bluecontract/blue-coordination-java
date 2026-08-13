package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures immutable whole-object retention across deterministic retries. */
final class WholeObjectFailureHygieneTest {
    private static final int ATTEMPTS = 20;

    @Test
    void identicalPrePublicationFailuresReachAStableWholeObjectCount()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start("counter", resource("examples/clean/counter.yaml"));
            var entry = engine.append(
                    alice,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 1"));
            int journalBefore = engine.journalSize();
            int objectsBefore = engine.wholeObjectCount();
            EngineMetrics.MetricsSnapshot metricsBefore =
                    engine.metricsSnapshot();
            List<Integer> retainedCounts = new ArrayList<>(ATTEMPTS);

            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                engine.failOnceAt(TestEngine.FailurePoint
                        .AFTER_FROZEN_BEFORE_STAGE);
                assertThrows(
                        TestEngine.InjectedFailureException.class,
                        () -> engine.dispatch(entry));
                retainedCounts.add(engine.wholeObjectCount());
                assertEquals(0L, engine.session("counter").epoch());
                assertTrue(engine.embeddedDocuments("counter").isEmpty());
                assertEquals(journalBefore, engine.journalSize());
            }

            int stableCount = retainedCounts.get(0);
            assertTrue(retainedCounts.stream().allMatch(
                    count -> count == stableCount),
                    () -> "Whole-object count grew across identical retries: "
                            + retainedCounts);
            assertTrue(stableCount >= objectsBefore);
            EngineTestSupport.MetricDelta failures = delta(
                    metricsBefore, engine.metricsSnapshot());
            assertEquals(ATTEMPTS, failures.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(0L, failures.counter("journal.rollbacks"),
                    "failed processing never rewrites the external journal");

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeCommit =
                    engine.metricsSnapshot();
            engine.dispatch(entry);
            engine.dispatch(entry);
            EngineTestSupport.MetricDelta committed = delta(
                    beforeCommit, engine.metricsSnapshot());
            assertEquals(1L, committed.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(1L, integer(engine, "counter", "/counter"));

            var next = engine.append(
                    alice,
                    Operation.yaml("ignored", "aliceChannel", "{}"));
            assertEquals(entry.timestampMicros() + 1L,
                    next.timestampMicros());
            assertEquals(entry.globalSequence() + 1L,
                    next.globalSequence());

        }
    }
}
