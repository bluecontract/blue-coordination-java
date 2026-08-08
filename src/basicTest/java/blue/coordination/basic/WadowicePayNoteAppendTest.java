package blue.coordination.basic;

import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .DispatchResult;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .DocumentTransition;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .EmbeddedDocumentLayout;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .ProcessTiming;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.StartResult;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment
        .StartedDocument;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.Timeline;
import blue.coordination.basic.EmbeddedOnlyDocumentEnvironment.TimelineEntry;
import blue.coordination.examples.documents.OrderDocuments;
import blue.coordination.examples.support.MyOsDemoYaml;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** First Wadowice operation using Process-Embedded-only document storage. */
final class WadowicePayNoteAppendTest {
    private static final String PAYNOTE = "package-paynote";
    private static final String ORDER = "package-order";
    private static final String PAYNOTE_START_STEP =
            "03 start PayNote embedded-only";
    private static final String ORDER_START_STEP =
            "05 start Order embedded-only";
    private static final String PROCESS_STEP =
            "10 PROCESS exact entry across two Roots";
    private static final long FIRST_TIMESTAMP_MICROS =
            1_785_000_000_000_001L;
    private static final Set<String> AFFECTED_ROOTS =
            Set.of(PAYNOTE, ORDER);
    private static final Set<String> INITIAL_ORDER_SCOPES = Set.of(
            "/",
            "/product",
            "/product/products/hotel",
            "/product/products/restaurant");
    private static final Set<String> RESULTING_ORDER_SCOPES = Set.of(
            "/",
            "/product",
            "/product/products/hotel",
            "/product/products/restaurant",
            "/payNotes/packagePayment",
            "/payNotes/packagePayment/productConditions/hotel/product",
            "/payNotes/packagePayment/productConditions/restaurant/product");

    @Test
    void aliceAppendsPayNoteAndOnlyEmbeddedDocumentsAreSplit()
            throws Exception {
        try (BasicTestMetrics metrics = BasicTestMetrics.start(
                "wadowice-paynote-append",
                "Wadowice PayNote append with embedded-only storage");
             BasicTestMetrics.MeasuredResource<
                     EmbeddedOnlyDocumentEnvironment> environment =
                     metrics.manage(
                             "12 environment close",
                             metrics.measure(
                                     "01 environment start",
                                     EmbeddedOnlyDocumentEnvironment
                                             ::create))) {
            EmbeddedOnlyDocumentEnvironment env = environment.value();

            String payNoteSource = metrics.measure(
                    "02 load canonical PayNote resource",
                    () -> canonicalResource(
                            "examples/wadowice/package-paynote.yaml",
                            OrderDocuments.PACKAGE_PAYNOTE));
            StartResult payNote = metrics.measure(
                    PAYNOTE_START_STEP,
                    () -> env.start(PAYNOTE, payNoteSource));
            attachStartMetrics(
                    metrics,
                    "wadowice-paynote-start",
                    PAYNOTE_START_STEP,
                    payNoteSource,
                    payNote);

            String orderSource = metrics.measure(
                    "04 load canonical Order resource",
                    () -> canonicalResource(
                            "examples/wadowice/package-order.yaml",
                            OrderDocuments.PACKAGE_ORDER));
            StartResult order = metrics.measure(
                    ORDER_START_STEP,
                    () -> env.start(ORDER, orderSource));
            attachStartMetrics(
                    metrics,
                    "wadowice-order-start",
                    ORDER_START_STEP,
                    orderSource,
                    order);

            Timeline alice = metrics.measure(
                    "06 add Alice timeline",
                    () -> env.timeline(
                            "examples/order/alice", "alice"));
            String request = metrics.measure(
                    "07 build canonical PayNote request",
                    () -> attachPayNoteRequest(payNote.document()));

            // The event is retained once as one exact content-addressed
            // object. No fragment splitter participates in append.
            TimelineEntry entry = metrics.measure(
                    "08 Alice append whole Timeline Entry",
                    () -> env.append(
                            alice,
                            "attachPayNoteAsCustomer",
                            "customerChannel",
                            request));
            Set<String> candidates = metrics.measure(
                    "09 index subscribed Root candidates",
                    () -> env.candidateDocumentKeys(entry));

            // Each selected Root is reconstructed for Contracts exactly once,
            // processed, recatalogued, and staged. Publication happens only
            // after both transitions validate.
            DispatchResult dispatch = metrics.measure(
                    PROCESS_STEP,
                    () -> env.process(entry));
            attachProcessMetrics(metrics, env, dispatch);

            metrics.measure(
                    "11 verify business state and embedded-only layout",
                    () -> assertAttached(
                            env,
                            payNote,
                            order,
                            alice,
                            entry,
                            candidates,
                            dispatch));
        }
    }

    private static String canonicalResource(
            String name,
            String canonicalSource) throws IOException {
        String source = BasicTestResources.read(name);
        assertEquals(canonicalSource, source,
                name + " must remain canonical Wadowice source");
        return source;
    }

    private static String attachPayNoteRequest(StartedDocument payNote) {
        return """
                document:
                %s
                documentRef:
                  blueId: %s
                """.formatted(
                MyOsDemoYaml.indent(
                        payNote.authoredYaml().stripTrailing(), 2),
                payNote.initialBlueId());
    }

    private static void attachStartMetrics(
            BasicTestMetrics metrics,
            String detailId,
            String parentStep,
            String source,
            StartResult started) {
        var detail = metrics.detail(detailId, parentStep);
        started.timing().detailedPhases().forEach(detail::phase);
        EmbeddedDocumentLayout layout = started.document().layout();
        detail.counter("source UTF-8 bytes",
                        source.getBytes(StandardCharsets.UTF_8).length)
                .counter("Contracts initialization gas",
                        started.document().initializationGas())
                .counter("effective document scopes",
                        layout.scopePaths().size())
                .counter("content-addressed objects retained",
                        layout.physicalObjectCount())
                .counter("Process Embedded documents retained",
                        layout.declaredEmbeddedDocumentCount())
                .counter("non-Process-Embedded fragments retained", 0L)
                .counter("splitter-created embedded edges",
                        layout.splitterCreatedEdgeCount());
    }

    private static void attachProcessMetrics(
            BasicTestMetrics metrics,
            EmbeddedOnlyDocumentEnvironment env,
            DispatchResult dispatch) {
        BasicTestMetrics.DetailSection detail = metrics.detail(
                "wadowice-embedded-only-process", PROCESS_STEP);
        detail.phase("indexed candidate Root routing",
                dispatch.timing().candidateRoutingNanos());
        for (Map.Entry<String, DocumentTransition> item
                : dispatch.transitions().entrySet()) {
            String documentKey = item.getKey();
            DocumentTransition transition = item.getValue();
            ProcessTiming timing = transition.timing();
            timing.detailedPhases().forEach((phase, nanos) ->
                    detail.phase(documentKey + " - " + phase, nanos));
            detail.counter(documentKey + " - Contracts PROCESS gas",
                            transition.processingGas())
                    .counter(documentKey + " - objects before",
                            transition.before().layout()
                                    .physicalObjectCount())
                    .counter(documentKey + " - objects after",
                            transition.after().layout()
                                    .physicalObjectCount())
                    .counter(documentKey + " - public events emitted",
                            transition.events().size());
        }
        detail.phase("atomic publication of all Root revisions",
                        dispatch.timing().atomicPublicationNanos())
                .phase("dispatch orchestration overhead",
                        dispatch.timing().orchestrationOverheadNanos());

        int objectsBefore = dispatch.transitions().values().stream()
                .mapToInt(transition -> transition.before().layout()
                        .physicalObjectCount())
                .sum();
        int objectsAfter = dispatch.transitions().values().stream()
                .mapToInt(transition -> transition.after().layout()
                        .physicalObjectCount())
                .sum();
        int embeddedBefore = dispatch.transitions().values().stream()
                .mapToInt(transition -> transition.before().layout()
                        .declaredEmbeddedDocumentCount())
                .sum();
        int embeddedAfter = dispatch.transitions().values().stream()
                .mapToInt(transition -> transition.after().layout()
                        .declaredEmbeddedDocumentCount())
                .sum();
        detail.counter("indexed Root candidates", dispatch.documentKeys().size())
                .counter("atomic Root revisions published",
                        dispatch.transitions().size())
                .counter("whole Timeline Entry objects retained",
                        env.timelineEntryCount())
                .counter("document objects before PROCESS", objectsBefore)
                .counter("document objects after PROCESS", objectsAfter)
                .counter("Process Embedded documents before PROCESS",
                        embeddedBefore)
                .counter("Process Embedded documents after PROCESS",
                        embeddedAfter)
                .counter("new Process Embedded documents",
                        embeddedAfter - embeddedBefore)
                .counter("non-Process-Embedded fragments retained", 0L);
    }

    private static void assertAttached(
            EmbeddedOnlyDocumentEnvironment env,
            StartResult startedPayNote,
            StartResult startedOrder,
            Timeline alice,
            TimelineEntry entry,
            Set<String> candidates,
            DispatchResult dispatch) {
        assertEquals(2, env.documentCount());
        assertEquals(1, env.timelineCount());
        assertEquals(1, env.timelineEntryCount());
        assertEquals(AFFECTED_ROOTS, candidates);
        assertEquals(AFFECTED_ROOTS, dispatch.documentKeys());
        assertSame(entry, dispatch.entry());

        assertEquals("examples/order/alice", alice.timelineId());
        assertEquals("alice", alice.actorId());
        assertEquals(alice.timelineId(), entry.timelineId());
        assertEquals(alice.actorId(), entry.actorId());
        assertEquals("attachPayNoteAsCustomer", entry.operation());
        assertEquals("customerChannel", entry.sourceChannel());
        assertEquals("customerChannel", entry.handlerChannel());
        assertEquals(FIRST_TIMESTAMP_MICROS, entry.timestampMicros());
        assertEquals(entry.blueId(),
                DirectBlueIdCalculator.calculateBlueId(entry.exactEvent()));
        assertValue(entry.exactEvent(),
                "/timeline/timelineId", alice.timelineId());
        assertValue(entry.exactEvent(),
                "/actor/accountId", alice.actorId());
        assertValue(entry.exactEvent(),
                "/message/operation", entry.operation());
        assertValue(entry.exactEvent(),
                "/message/channel", entry.handlerChannel());
        assertEquals(
                startedPayNote.document().initialBlueId(),
                required(entry.exactEvent(),
                        "/message/request/documentRef").getBlueId());

        DocumentTransition payNote = dispatch.require(PAYNOTE);
        DocumentTransition order = dispatch.require(ORDER);
        assertSame(startedPayNote.document(), payNote.before());
        assertSame(startedOrder.document(), order.before());
        assertEquals(List.of(), eventKinds(payNote));
        assertEquals(List.of("Commerce/PayNote Attached"),
                eventKinds(order));

        for (String key : AFFECTED_ROOTS) {
            DocumentTransition transition = dispatch.require(key);
            assertEquals(0L, transition.before().currentEpoch());
            assertEquals(1L, transition.after().currentEpoch());
            assertEquals(Set.of(),
                    transition.before().deliveredEventBlueIds());
            assertEquals(Set.of(entry.blueId()),
                    transition.after().deliveredEventBlueIds());
            assertSame(transition.after(), env.document(key));
            assertEquals(
                    transition.after().currentRootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(
                            transition.after().currentRoot()));
            assertTrue(transition.processingGas() > 0L);
            assertTrue(transition.timing().totalNanos() > 0L);
        }

        Node currentOrder = env.document(ORDER).currentRoot();
        assertValue(currentOrder, "/payNoteAttached", true);
        assertValue(
                currentOrder,
                "/paymentState",
                "Payment Initiated - Conditions Pending");
        assertNumber(currentOrder,
                "/paymentInitiatedAt", entry.timestampMicros());
        assertValue(
                currentOrder,
                "/contracts/embedded/paths/1",
                "/payNotes/packagePayment");
        assertValue(
                env.document(PAYNOTE).currentRoot(),
                "/status",
                "Awaiting Product Conditions");

        assertLayout(
                payNote.before().layout(), Set.of("/"), 1, 0);
        assertLayout(
                payNote.after().layout(), Set.of("/"), 1, 0);
        assertLayout(
                order.before().layout(), INITIAL_ORDER_SCOPES, 4, 3);
        assertLayout(
                order.after().layout(), RESULTING_ORDER_SCOPES, 7, 6);
        assertEquals(8,
                payNote.after().layout().physicalObjectCount()
                        + order.after().layout().physicalObjectCount());
        assertEquals(6,
                payNote.after().layout().splitterCreatedEdgeCount()
                        + order.after().layout()
                                .splitterCreatedEdgeCount());

        String payNoteRoot = env.document(PAYNOTE).currentRootBlueId();
        String orderRoot = env.document(ORDER).currentRootBlueId();
        assertThrows(
                IllegalStateException.class,
                () -> env.process(entry));
        assertEquals(payNoteRoot,
                env.document(PAYNOTE).currentRootBlueId());
        assertEquals(orderRoot,
                env.document(ORDER).currentRootBlueId());
    }

    private static void assertLayout(
            EmbeddedDocumentLayout layout,
            Set<String> expectedScopes,
            int expectedObjects,
            int expectedEmbeddedEdges) {
        assertEquals(
                EmbeddedOnlyDocumentEnvironment.LAYOUT_PROFILE_ID,
                layout.layoutProfileIdentity());
        assertEquals(expectedScopes, layout.scopePaths());
        assertEquals(expectedObjects, layout.physicalObjectCount());
        assertEquals(expectedObjects - 1,
                layout.declaredEmbeddedDocumentCount());
        assertEquals(expectedEmbeddedEdges,
                layout.splitterCreatedEdgeCount());
        assertEquals(0, layout.authoredReferenceEdgeCount());
        assertEquals(expectedObjects,
                layout.physicalObjectBlueIds().size());
        assertFalse(layout.storedRootObject().isReferenceOnly());
        assertEquals(layout.rootBlueId(),
                DirectBlueIdCalculator.calculateBlueId(
                        layout.reconstructRoot()));
    }

    private static List<Object> eventKinds(DocumentTransition transition) {
        return transition.events().stream()
                .map(event -> required(event, "/kind").getValue())
                .toList();
    }

    private static void assertValue(
            Node root,
            String path,
            Object expected) {
        assertEquals(expected, required(root, path).getValue(), path);
    }

    private static void assertNumber(
            Node root,
            String path,
            long expected) {
        Object actual = required(root, path).getValue();
        assertTrue(actual instanceof Number,
                "Expected number at " + path + " but got " + actual);
        assertEquals(expected, ((Number) actual).longValue(), path);
    }

    private static Node required(Node root, String path) {
        Node selected = NodePathEditor.getOrNull(root, path);
        assertNotNull(selected, "Missing node at " + path);
        return selected;
    }
}
