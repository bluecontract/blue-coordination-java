package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConcurrentEmbeddedChildCreationTest {
    @Test
    void twoParentsAttachingSameUnseenChildCreateOneSession() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"));

            String template = resource(
                    "examples/clean/embedded-state-parent.yaml");
            Timeline firstTimeline = engine.timeline(
                    "examples/embedded/state-parent-1", "bob-1");
            Timeline secondTimeline = engine.timeline(
                    "examples/embedded/state-parent-2", "bob-2");
            engine.start(
                    "embedded-state-parent-1",
                    parent(template, "1"));
            engine.start(
                    "embedded-state-parent-2",
                    parent(template, "2"));
            var first = engine.append(
                    firstTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));
            var second = engine.append(
                    secondTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)));

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> firstResult = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    engine.dispatch(first);
                });
                Future<?> secondResult = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    engine.dispatch(second);
                });
                ready.await();
                start.countDown();
                firstResult.get();
                secondResult.get();
            } finally {
                executor.shutdownNow();
            }
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(1L, work.counter("embedding.childSessionsCreated"));
            assertEquals(1L, work.counter("embedding.childSessionsReused"));
            assertEquals(1L, work.counter("preparedRuntimeCompilations"),
                    "only the single-flight child is prepared in this delta");
            assertEquals(1L, work.counter("sessionsCreated"),
                    "the two parents already exist; no duplicate child is created");
            assertEquals(1L, integer(
                    engine, "embedded-state-parent-1", "/child/counter"));
            assertEquals(1L, integer(
                    engine, "embedded-state-parent-2", "/child/counter"));
        }
    }

    private static String parent(String template, String suffix) {
        return template
                .replace("embedded-state-parent", "embedded-state-parent-" + suffix)
                .replace("examples/embedded/state-parent",
                        "examples/embedded/state-parent-" + suffix)
                .replace("accountId: bob", "accountId: bob-" + suffix);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
