package blue.coordination.integration;

import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.indent;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Top-level admission publishes documents and routing as one unit. */
final class StartAdmissionAtomicityTest {
    @Test
    void rejectedTopLevelSelfCyclePublishesNothingAndRetryMatchesFresh()
            throws Exception {
        String parent = resource(
                "examples/clean/root-isolation-parent.yaml");
        String child = resource(
                "examples/clean/root-isolation-child.yaml");
        String cyclicChild = child.replace(
                "documentId: root-isolation-child",
                "documentId: root-isolation-parent");
        String invalidTopLevel = parent + "\nchild:\n"
                + indent(cyclicChild, 2);

        try (TestEngine engine = TestEngine.create();
             TestEngine fresh = TestEngine.create()) {
            int objectsBefore = engine.wholeObjectCount();
            int journalBefore = engine.journalSize();
            long clockBefore = engine.logicalClockMicros();
            EngineMetrics.MetricsSnapshot metricsBefore =
                    engine.metricsSnapshot();

            assertThrows(
                    IllegalStateException.class,
                    () -> engine.start(
                            "root-isolation-parent", invalidTopLevel));

            assertEquals(0, engine.documentCount());
            assertEquals(0, engine.routeRowCount());
            assertEquals(objectsBefore, engine.wholeObjectCount());
            assertEquals(journalBefore, engine.journalSize());
            assertEquals(clockBefore, engine.logicalClockMicros());
            assertEquals(
                    metricsBefore.counters().getOrDefault(
                            "sessionsCreated", 0L),
                    engine.metricsSnapshot().counters().getOrDefault(
                            "sessionsCreated", 0L));

            var retry = engine.start("root-isolation-parent", parent);
            var expected = fresh.start("root-isolation-parent", parent);
            assertEquals(expected.authoredInitialBlueId(),
                    retry.authoredInitialBlueId());
            assertEquals(expected.layout().rootBlueId(),
                    retry.layout().rootBlueId());
            assertEquals(expected.layout().routingSurface().definitions(),
                    retry.layout().routingSurface().definitions());
            assertEquals(fresh.documentCount(), engine.documentCount());
            assertEquals(fresh.routeRowCount(), engine.routeRowCount());
            assertEquals(fresh.wholeObjectCount(), engine.wholeObjectCount());
        }
    }
}
