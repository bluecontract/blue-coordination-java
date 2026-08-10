package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dynamic stable-key {@code collectionPaths} membership and storage policy. */
final class ProcessEmbeddedCollectionPathsIntegrationTest {

    @Test
    void discoversAddsAndRemovesCanonicalMapMembersWithoutOrdinarySplitting()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/embedded/collection-parent", "bob");
            engine.start("embedded-collection-parent", resource(
                    "examples/clean/embedded-collection-parent.yaml"));

            assertEquals(Map.of(), engine.embeddedDocuments(
                    "embedded-collection-parent"));
            assertEquals(1, engine.session("embedded-collection-parent")
                    .layout().physicalObjectCount());

            EngineMetrics.MetricsSnapshot beforeFirst =
                    engine.metricsSnapshot();
            engine.appendAndDispatch(owner, Operation.exact(
                    "attachGameA",
                    "ownerChannel",
                    engine.embeddedDocumentRequest(resource(
                            "examples/clean/embedded-counter.yaml"))));
            EngineTestSupport.MetricDelta first = delta(
                    beforeFirst, engine.metricsSnapshot());

            assertEquals(Map.of(
                            "/games/game-a", "embedded-counter-A"),
                    engine.embeddedDocuments("embedded-collection-parent"));
            assertEquals(2, engine.session("embedded-collection-parent")
                    .layout().physicalObjectCount());
            assertTrue(first.counter("layout.plansReused") > 0L);
            assertTrue(first.counter("layout.collectionCatalogRefreshes") > 0L);
            assertTrue(first.counter("process.embeddedEpochProcessCalls") > 0L);
            assertNoGenericSplitting(first);
            assertOrdinaryPayloadRemainsInline(engine);

            EngineMetrics.MetricsSnapshot beforeSecond =
                    engine.metricsSnapshot();
            engine.appendAndDispatch(owner, Operation.exact(
                    "attachEscapedGame",
                    "ownerChannel",
                    engine.embeddedDocumentRequest(resource(
                            "examples/clean/embedded-counter-B.yaml"))));
            EngineTestSupport.MetricDelta second = delta(
                    beforeSecond, engine.metricsSnapshot());

            assertEquals(Map.of(
                            "/games/game-a", "embedded-counter-A",
                            "/games/game~1a~0b", "embedded-counter-B"),
                    engine.embeddedDocuments("embedded-collection-parent"));
            assertEquals(
                    List.of(
                            "/games/game-a", "/games/game~1a~0b"),
                    engine.session("embedded-collection-parent")
                            .layout().boundaries().stream()
                            .map(EmbeddedOnlyLayout.Boundary::childScopePath)
                            .toList());
            assertEquals(3, engine.session("embedded-collection-parent")
                    .layout().physicalObjectCount());
            assertTrue(second.counter("layout.plansReused") > 0L);
            assertTrue(second.counter("layout.collectionCatalogRefreshes")
                    > 0L);
            assertTrue(second.counter("process.embeddedEpochProcessCalls")
                    > 0L);
            assertNoGenericSplitting(second);

            EngineMetrics.MetricsSnapshot beforeRemoval =
                    engine.metricsSnapshot();
            engine.appendAndDispatch(owner, Operation.yaml(
                    "removeGameA", "ownerChannel", "{}"));
            EngineTestSupport.MetricDelta removal = delta(
                    beforeRemoval, engine.metricsSnapshot());

            assertEquals(Map.of(
                            "/games/game~1a~0b", "embedded-counter-B"),
                    engine.embeddedDocuments("embedded-collection-parent"));
            assertEquals(2, engine.session("embedded-collection-parent")
                    .layout().physicalObjectCount());
            assertTrue(removal.counter("layout.plansReused") > 0L);
            assertTrue(removal.counter("layout.collectionCatalogRefreshes")
                    > 0L);
            assertNoGenericSplitting(removal);
            assertOrdinaryPayloadRemainsInline(engine);
        }
    }

    private static void assertOrdinaryPayloadRemainsInline(TestEngine engine) {
        Node storedRoot = engine.session("embedded-collection-parent")
                .layout().stored("/").copyNode();
        Node ordinary = NodePathEditor.getOrNull(
                storedRoot, "/ordinaryPayload");
        assertTrue(ordinary != null);
        assertFalse(ordinary.isReferenceOnly());
    }
}
