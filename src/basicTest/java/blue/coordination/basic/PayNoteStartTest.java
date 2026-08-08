package blue.coordination.basic;

import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .EmbeddedDocumentLayout;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.StartResult;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.StartTiming;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .StartedDocument;
import blue.coordination.examples.documents.OrderDocuments;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts the standalone Wadowice PayNote without ordinary-node splitting. */
final class PayNoteStartTest {
    private static final String PAYNOTE = "package-paynote";
    private static final String START_STEP =
            "03 initialize and retain PayNote whole";

    @Test
    void startsPayNoteAsOneWholeContentAddressedObject() throws Exception {
        try (BasicTestMetrics metrics = BasicTestMetrics.start(
                "paynote-start",
                "PayNote start with Process-Embedded-only storage");
             BasicTestMetrics.MeasuredResource<
                     EmbeddedOnlyDocumentEnvironment> environment =
                     metrics.manage(
                             "05 environment close",
                             metrics.measure(
                                     "01 environment start",
                                     EmbeddedOnlyDocumentEnvironment
                                             ::create))) {
            EmbeddedOnlyDocumentEnvironment env = environment.value();
            String source = metrics.measure(
                    "02 load canonical PayNote resource",
                    PayNoteStartTest::canonicalPayNoteResource);

            StartResult started = metrics.measure(
                    START_STEP,
                    () -> env.start(PAYNOTE, source));
            attachDetailedMetrics(metrics, source, started);

            metrics.measure(
                    "04 verify zero ordinary-node splits",
                    () -> assertStartedWhole(env, source, started));
        }
    }

    private static String canonicalPayNoteResource() throws IOException {
        String source = BasicTestResources.read(
                "examples/wadowice/package-paynote.yaml");
        assertEquals(
                OrderDocuments.PACKAGE_PAYNOTE,
                source,
                "PayNote resource must remain canonical Wadowice source");
        return source;
    }

    private static void attachDetailedMetrics(
            BasicTestMetrics metrics,
            String source,
            StartResult started) {
        StartTiming timing = started.timing();
        EmbeddedDocumentLayout layout = started.document().layout();
        BasicTestMetrics.DetailSection detail = metrics.detail(
                "paynote-embedded-only-start", START_STEP);
        timing.detailedPhases().forEach(detail::phase);
        detail.counter("source UTF-8 bytes",
                        source.getBytes(StandardCharsets.UTF_8).length)
                .counter("Contracts initialization gas",
                        started.document().initializationGas())
                .counter("effective document scopes",
                        layout.scopePaths().size())
                .counter("declared embedded documents",
                        layout.declaredEmbeddedDocumentCount())
                .counter("content-addressed objects retained",
                        layout.physicalObjectCount())
                .counter("splitter-created embedded edges",
                        layout.splitterCreatedEdgeCount())
                .counter("authored embedded-reference edges",
                        layout.authoredReferenceEdgeCount())
                .counter("initialization events emitted",
                        started.document().initializationEventCount());
    }

    private static void assertStartedWhole(
            EmbeddedOnlyDocumentEnvironment env,
            String source,
            StartResult started) {
        StartedDocument document = started.document();
        StartTiming timing = started.timing();
        EmbeddedDocumentLayout layout = document.layout();

        assertTrue(timing.totalNanos() > 0L);
        assertEquals(timing.totalNanos(),
                timing.detailedPhases().values().stream()
                        .reduce(0L, Math::addExact));
        assertSame(document, env.document(PAYNOTE));
        assertEquals(1, env.documentCount());
        assertEquals(PAYNOTE, document.key());
        assertEquals(source, document.authoredYaml());
        assertFalse(document.initialBlueId().isBlank());
        assertFalse(document.currentRootBlueId().isBlank());
        assertNotEquals(
                document.initialBlueId(), document.currentRootBlueId());
        assertEquals(0L, document.currentEpoch());
        assertEquals(0, document.initializationEventCount());

        assertEquals(
                EmbeddedOnlyDocumentEnvironment.LAYOUT_PROFILE_ID,
                layout.layoutProfileIdentity());
        assertEquals(Set.of("/"), layout.scopePaths());
        assertEquals(0, layout.declaredEmbeddedDocumentCount());
        assertEquals(1, layout.physicalObjectCount());
        assertEquals(0, layout.splitterCreatedEdgeCount());
        assertEquals(0, layout.authoredReferenceEdgeCount());
        assertEquals(
                Set.of(document.currentRootBlueId()),
                layout.physicalObjectBlueIds());

        Node storedRoot = layout.storedRootObject();
        Node reconstructedRoot = layout.reconstructRoot();
        Node currentRoot = document.currentRoot();
        assertFalse(storedRoot.isReferenceOnly());
        assertEquals(
                NodeWireForm.get(storedRoot),
                NodeWireForm.get(reconstructedRoot),
                "A cut-free Root must be stored byte-for-byte whole");
        assertEquals(
                NodeWireForm.get(reconstructedRoot),
                NodeWireForm.get(currentRoot));

        assertEquals("ACME Hotel & Dinner PayNote",
                currentRoot.getName());
        assertValue(currentRoot, "/status",
                "Awaiting Product Conditions");
        assertValue(currentRoot, "/currency", "PLN");
        assertNumber(currentRoot, "/amount/expectedTotal", 130000L);
        assertNumber(currentRoot, "/amount/captured", 0L);
        assertNull(NodePathEditor.getOrNull(
                currentRoot, "/contracts/embedded"));
        assertNotNull(NodePathEditor.getOrNull(
                currentRoot, "/contracts/initialized"));
    }

    private static void assertValue(
            Node root,
            String path,
            Object expected) {
        Node selected = NodePathEditor.getOrNull(root, path);
        assertNotNull(selected, "Missing value at " + path);
        assertEquals(expected, selected.getValue(), path);
    }

    private static void assertNumber(
            Node root,
            String path,
            long expected) {
        Node selected = NodePathEditor.getOrNull(root, path);
        assertNotNull(selected, "Missing number at " + path);
        assertTrue(selected.getValue() instanceof Number,
                "Expected number at " + path);
        assertEquals(expected,
                ((Number) selected.getValue()).longValue(), path);
    }
}
