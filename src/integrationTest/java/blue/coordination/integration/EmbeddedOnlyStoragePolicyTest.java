package blue.coordination.integration;

import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the physical model: whole Roots plus only Process Embedded cuts. */
final class EmbeddedOnlyStoragePolicyTest {
    private static final long T0 = 1_730_000_000_000_000L;

    @Test
    void ordinaryLargeDocumentAndRequestsStayWholeWhileEmbeddedChildIsOneCut()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String payNote = resource("examples/clean/package-paynote.yaml");
            EngineMetrics.MetricsSnapshot beforePayNote =
                    engine.metricsSnapshot();
            engine.start("standalone-paynote", payNote);
            EngineTestSupport.MetricDelta payNoteWork = delta(
                    beforePayNote, engine.metricsSnapshot());

            EmbeddedOnlyLayout payNoteLayout =
                    engine.session("standalone-paynote").layout();
            assertEquals(1, payNoteLayout.physicalObjectCount());
            assertEquals(0, payNoteLayout.embeddedDocumentCount());
            assertEquals(0, payNoteLayout.splitterCreatedEdgeCount());
            assertTrue(payNoteLayout.boundaries().isEmpty());
            assertEquals(payNoteLayout.rootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(
                            payNoteLayout.stored("/").copyNode()));
            assertNoGenericSplitting(payNoteWork);

            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            String parentInitial = resource(
                    "examples/clean/embedded-parent.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/B", "bob");
            engine.start("embedded-counter-A", childInitial);
            var childEntry = engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 4"),
                    T0 + 100L);
            engine.dispatch(childEntry);
            engine.start("embedded-parent-B", parentInitial);

            EngineMetrics.MetricsSnapshot beforeAttach =
                    engine.metricsSnapshot();
            var attach = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)),
                    T0 + 1_000L);
            engine.dispatch(attach);
            EngineTestSupport.MetricDelta attachWork = delta(
                    beforeAttach, engine.metricsSnapshot());

            EmbeddedOnlyLayout parentLayout =
                    engine.session("embedded-parent-B").layout();
            assertEquals(2, parentLayout.physicalObjectCount());
            assertEquals(1, parentLayout.embeddedDocumentCount());
            assertEquals(1, parentLayout.splitterCreatedEdgeCount());
            assertEquals(1, parentLayout.boundaries().size());
            assertEquals("/child",
                    parentLayout.boundaries().get(0).childScopePath());

            Node storedRoot = parentLayout.stored("/").copyNode();
            Node storedChildReference = NodePathEditor.getOrNull(
                    storedRoot, "/child");
            assertTrue(storedChildReference != null
                            && storedChildReference.isReferenceOnly(),
                    "Only the effective Process Embedded child is cut");
            Node storedChild = parentLayout.stored("/child").copyNode();
            assertFalse(storedChild.isReferenceOnly(),
                    "The child is retained once as a whole exact document");
            Node reconstructed = parentLayout.reconstructRoot();
            assertEquals(parentLayout.rootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(reconstructed));
            assertEquals(
                    parentLayout.stored("/child").blueId(),
                    storedChildReference.getBlueId());

            assertTrue(attachWork.counter("journal.entriesStoredWhole") >= 1L);
            assertTrue(attachWork.counter(
                    "wholeObjectStore.purpose.timeline-request") >= 1L);
            assertTrue(attachWork.counter(
                    "wholeObjectStore.purpose.timeline-entry") >= 1L);
            assertNoGenericSplitting(attachWork);
        }
    }
}
