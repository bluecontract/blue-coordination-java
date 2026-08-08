package blue.coordination.basic;

import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.DispatchResult;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .DocumentTransition;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .EmbeddedDocumentLayout;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.ProcessTiming;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.StartResult;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.Timeline;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.TimelineEntry;
import blue.coordination.examples.support.MyOsDemoYaml;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One operation that retains a complete PayNote as an ordinary inline field. */
final class BasicPayNoteFieldTest {
    private static final String DOCUMENT_KEY = "paynote-field";
    private static final String START_STEP =
            "04 start PayNote field document";
    private static final String PROCESS_STEP =
            "07 Alice PROCESS PayNote (cold)";

    @Test
    void aliceAppendsOnePayNoteAsAnInlineField() throws Exception {
        try (BasicTestMetrics metrics = BasicTestMetrics.start(
                "paynote-field", "Basic inline PayNote field");
             BasicTestMetrics.MeasuredResource<
                     EmbeddedOnlyDocumentEnvironment> environment =
                     metrics.manage(
                             "09 environment close",
                             metrics.measure(
                                     "01 environment start",
                                     EmbeddedOnlyDocumentEnvironment
                                             ::create))) {
            EmbeddedOnlyDocumentEnvironment env = environment.value();

            Timeline alice = metrics.measure(
                    "02 add Alice timeline",
                    () -> env.timeline(
                            "examples/basic-paynote-field/alice",
                            "alice"));

            String documentSource = metrics.measure(
                    "03 load PayNote field resource",
                    () -> BasicTestResources.read(
                            "examples/basic-paynote-field.yaml"));
            StartResult started = metrics.measure(
                    START_STEP,
                    () -> env.start(DOCUMENT_KEY, documentSource));
            attachStartMetrics(metrics, documentSource, started);

            String payNoteSource = metrics.measure(
                    "05 load PayNote resource",
                    () -> BasicTestResources.read(
                            "examples/wadowice/package-paynote.yaml"));

            TimelineEntry entry = metrics.measure(
                    "06 Alice append PayNote",
                    () -> env.append(
                            alice,
                            "appendPayNote",
                            "aliceChannel",
                            payNoteRequest(payNoteSource)));

            DispatchResult dispatch = metrics.measure(
                    PROCESS_STEP,
                    () -> env.process(entry));
            attachProcessMetrics(metrics, env, dispatch, payNoteSource);

            metrics.measure(
                    "08 verify PayNote is one inline field",
                    () -> assertInlinePayNote(
                            env, alice, entry, dispatch));
        }
    }

    private static String payNoteRequest(String payNoteSource) {
        return """
                payNote:
                %s
                """.formatted(MyOsDemoYaml.indent(
                        payNoteSource.stripTrailing(), 2));
    }

    private static void attachStartMetrics(
            BasicTestMetrics metrics,
            String documentSource,
            StartResult started) {
        BasicTestMetrics.DetailSection detail = metrics.detail(
                "paynote-field-start", START_STEP);
        started.timing().detailedPhases().forEach(detail::phase);
        EmbeddedDocumentLayout layout = started.document().layout();
        detail.counter("source UTF-8 bytes",
                        documentSource.getBytes(StandardCharsets.UTF_8).length)
                .counter("Contracts initialization gas",
                        started.document().initializationGas())
                .counter("effective document scopes",
                        layout.scopePaths().size())
                .counter("content-addressed objects retained",
                        layout.physicalObjectCount())
                .counter("Process Embedded documents retained",
                        layout.declaredEmbeddedDocumentCount())
                .counter("non-Process-Embedded fragments retained", 0L);
    }

    private static void attachProcessMetrics(
            BasicTestMetrics metrics,
            EmbeddedOnlyDocumentEnvironment env,
            DispatchResult dispatch,
            String payNoteSource) {
        BasicTestMetrics.DetailSection detail = metrics.detail(
                "paynote-field-process", PROCESS_STEP);
        detail.phase("indexed candidate Root routing",
                dispatch.timing().candidateRoutingNanos());
        DocumentTransition transition = dispatch.require(DOCUMENT_KEY);
        ProcessTiming timing = transition.timing();
        timing.detailedPhases().forEach(detail::phase);
        detail.phase("atomic publication of Root revision",
                        dispatch.timing().atomicPublicationNanos())
                .phase("dispatch orchestration overhead",
                        dispatch.timing().orchestrationOverheadNanos())
                .counter("PayNote source UTF-8 bytes",
                        payNoteSource.getBytes(StandardCharsets.UTF_8).length)
                .counter("indexed Root candidates",
                        dispatch.documentKeys().size())
                .counter("Contracts PROCESS gas",
                        transition.processingGas())
                .counter("whole Timeline Entry objects retained",
                        env.timelineEntryCount())
                .counter("document objects before PROCESS",
                        transition.before().layout().physicalObjectCount())
                .counter("document objects after PROCESS",
                        transition.after().layout().physicalObjectCount())
                .counter("Process Embedded documents after PROCESS",
                        transition.after().layout()
                                .declaredEmbeddedDocumentCount())
                .counter("non-Process-Embedded fragments retained", 0L)
                .counter("public events emitted",
                        transition.events().size());
    }

    private static void assertInlinePayNote(
            EmbeddedOnlyDocumentEnvironment env,
            Timeline alice,
            TimelineEntry entry,
            DispatchResult dispatch) {
        assertEquals(Set.of(DOCUMENT_KEY), dispatch.documentKeys());
        assertEquals(1, env.documentCount());
        assertEquals(1, env.timelineCount());
        assertEquals(1, env.timelineEntryCount());
        assertEquals("examples/basic-paynote-field/alice",
                alice.timelineId());
        assertEquals("alice", alice.actorId());
        assertEquals("appendPayNote", entry.operation());
        assertEquals("aliceChannel", entry.sourceChannel());

        DocumentTransition transition = dispatch.require(DOCUMENT_KEY);
        assertEquals(0L, transition.before().currentEpoch());
        assertEquals(1L, transition.after().currentEpoch());
        assertEquals(Set.of(entry.blueId()),
                transition.after().deliveredEventBlueIds());
        assertTrue(transition.processingGas() > 0L);
        assertEquals(0, transition.events().size());

        Node requestPayNote = required(
                entry.exactEvent(), "/message/request/payNote");
        Node currentRoot = env.document(DOCUMENT_KEY).currentRoot();
        Node inlinePayNote = required(currentRoot, "/payNote");
        assertFalse(inlinePayNote.isReferenceOnly(),
                "An ordinary PayNote field must remain inline");
        assertEquals(NodeWireForm.get(requestPayNote),
                NodeWireForm.get(inlinePayNote));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(requestPayNote),
                DirectBlueIdCalculator.calculateBlueId(inlinePayNote));
        assertEquals("ACME Hotel & Dinner PayNote", inlinePayNote.getName());
        assertEquals("Awaiting Product Conditions",
                required(inlinePayNote, "/status").getValue());

        EmbeddedDocumentLayout layout = transition.after().layout();
        assertEquals(Set.of("/"), layout.scopePaths());
        assertEquals(1, layout.physicalObjectCount());
        assertEquals(0, layout.declaredEmbeddedDocumentCount());
        assertEquals(0, layout.splitterCreatedEdgeCount());
        assertEquals(0, layout.authoredReferenceEdgeCount());
        Node storedInlinePayNote = required(
                layout.storedRootObject(), "/payNote");
        assertFalse(storedInlinePayNote.isReferenceOnly());
        assertEquals(NodeWireForm.get(inlinePayNote),
                NodeWireForm.get(storedInlinePayNote));
        assertEquals(layout.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(currentRoot));
    }

    private static Node required(Node root, String path) {
        Node selected = NodePathEditor.getOrNull(root, path);
        assertNotNull(selected, "Missing node at " + path);
        return selected;
    }
}
