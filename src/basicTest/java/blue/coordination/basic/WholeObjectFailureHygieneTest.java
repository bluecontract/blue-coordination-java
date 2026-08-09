package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.Timeline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures immutable whole-object retention across deterministic retries. */
final class WholeObjectFailureHygieneTest {
    private static final int ATTEMPTS = 20;

    @Test
    void identicalPrePublicationFailuresReachAStableWholeObjectCount()
            throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start("counter", resource("examples/clean/counter.yaml"));
            var entry = engine.append(
                    alice,
                    BasicOperation.of(
                            "increment", "aliceChannel", "amount: 1"));
            int journalBefore = engine.journalSize();
            int objectsBefore = engine.wholeObjectCount();
            EngineMetrics.MetricsSnapshot metricsBefore =
                    engine.metricsSnapshot();
            List<Integer> retainedCounts = new ArrayList<>(ATTEMPTS);

            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                engine.failOnceAt(BasicCoordinationEngine.FailurePoint
                        .AFTER_FROZEN_BEFORE_STAGE);
                assertThrows(
                        BasicCoordinationEngine.InjectedFailureException.class,
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
            BasicEngineTestSupport.MetricDelta failures = delta(
                    metricsBefore, engine.metricsSnapshot());
            assertEquals(ATTEMPTS, failures.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(ATTEMPTS, failures.counter("transactionRetries"));
            assertEquals(ATTEMPTS, failures.counter("journal.rollbacks"));

            engine.clearFailureInjection();
            EngineMetrics.MetricsSnapshot beforeCommit =
                    engine.metricsSnapshot();
            engine.dispatch(entry);
            engine.dispatch(entry);
            BasicEngineTestSupport.MetricDelta committed = delta(
                    beforeCommit, engine.metricsSnapshot());
            assertEquals(1L, committed.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(1L, integer(engine, "counter", "/counter"));

            var next = engine.append(
                    alice,
                    BasicOperation.of("ignored", "aliceChannel", "{}"));
            assertEquals(entry.timestampMicros() + 1L,
                    next.timestampMicros());
            assertEquals(entry.globalSequence() + 1L,
                    next.globalSequence());

            System.out.println("whole-object retry counts: before="
                    + objectsBefore + ", attempts=" + retainedCounts
                    + ", afterCommit=" + engine.wholeObjectCount());
        }
    }
}
