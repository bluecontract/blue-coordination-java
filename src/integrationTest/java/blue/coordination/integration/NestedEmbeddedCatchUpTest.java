package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Root -> Emb1 -> Emb2 catch-up, reuse, and live propagation. */
final class NestedEmbeddedCatchUpTest {
    private static final long T0 = 1_720_000_000_000_000L;

    @Test
    void nestedInitialStatesCatchUpRecursivelyAndLiveRevisionPropagatesOnce()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            // given
            String leafInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            String middleInitial = resource(
                    "examples/clean/embedded-middle.yaml");
            String rootInitial = resource(
                    "examples/clean/embedded-root.yaml");

            Timeline leafTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", leafInitial);
            var plusTwo = engine.appendAt(
                    leafTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"),
                    T0 + 100);
            engine.dispatch(plusTwo);

            Timeline middleTimeline = engine.timeline(
                    "examples/embedded/middle", "middle-owner");
            engine.start("embedded-middle-A", middleInitial);
            var attachLeaf = engine.appendAt(
                    middleTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(leafInitial)),
                    T0 + 1_000);
            engine.dispatch(attachLeaf);
            assertEquals(2L, integer(
                    engine, "embedded-middle-A", "/childCounter"));

            int leafHistoryBeforeRoot = engine.history(
                    "embedded-counter-A").size();
            int middleHistoryBeforeRoot = engine.history(
                    "embedded-middle-A").size();
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/root", "root-owner");
            engine.start("embedded-root-B", rootInitial);
            EngineMetrics.MetricsSnapshot beforeRootAttach =
                    engine.metricsSnapshot();
            var attachMiddle = engine.appendAt(
                    rootTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(middleInitial)),
                    T0 + 2_000);
            engine.dispatch(attachMiddle);
            EngineTestSupport.MetricDelta rootAttachWork = delta(
                    beforeRootAttach, engine.metricsSnapshot());

            assertEquals(SessionStatus.READY,
                    engine.session("embedded-root-B").status());
            assertEquals(2L, integer(
                    engine, "embedded-root-B", "/leafCounter"));
            assertEquals(2,
                    engine.session("embedded-root-B")
                            .layout().physicalObjectCount(),
                    "A Root owns only its direct child; the middle session "
                            + "owns the leaf boundary");
            assertEquals(2,
                    engine.session("embedded-middle-A")
                            .layout().physicalObjectCount());
            assertEquals(1,
                    engine.session("embedded-counter-A")
                            .layout().physicalObjectCount());
            assertEquals(leafHistoryBeforeRoot,
                    engine.history("embedded-counter-A").size());
            assertEquals(middleHistoryBeforeRoot,
                    engine.history("embedded-middle-A").size());
            assertEquals(
                    Set.of(
                            "examples/embedded/A",
                            "examples/embedded/middle",
                            "examples/embedded/root"),
                    engine.effectiveTimelineIds("embedded-root-B"));
            assertNoGenericSplitting(rootAttachWork);

            EngineMetrics.MetricsSnapshot beforeLive = engine.metricsSnapshot();

            // when
            var plusThree = engine.appendAt(
                    leafTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 3"),
                    T0 + 3_000);
            engine.dispatch(plusThree);
            EngineTestSupport.MetricDelta liveWork = delta(
                    beforeLive, engine.metricsSnapshot());

            // then
            assertEquals(5L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(5L, integer(
                    engine, "embedded-middle-A", "/childCounter"));
            assertEquals(5L, integer(
                    engine, "embedded-root-B", "/leafCounter"));
            assertEquals(3L, liveWork.counter(
                    "process.frozenContractsInvocations"),
                    "Leaf, middle, and root each advance exactly once");
            assertEquals(2L, liveWork.counter(
                    "catchUp.parentRevisionApplications"));
            assertNoGenericSplitting(liveWork);
        }
    }
}
