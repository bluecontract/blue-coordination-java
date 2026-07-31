package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario:
 * A package order for Customer and Travel Agency sells a 20-21 June weekend package: Deluxe Room in
 * Hotel Badura plus a 250zl Dinner for Two at Restaurant Cud Malina for 499 PLN.
 *
 * Main flow:
 * 1. Travel Agency delivers the exact Package PayNote as the operation request.
 * 2. Card Processor authorizes the embedded PayNote.
 * 3. Travel Agency provides the Restaurant Order and Hotel Order as separate operation requests inside
 *    the embedded PayNote. The orders are not root templates.
 * 4. Restaurant and Hotel confirm their own embedded orders.
 * 5. PayNote listens to the embedded order channels and requests capture only after both confirmations.
 * 6. Card Processor confirms capture.
 * 7. The package order listens to the embedded PayNote and changes order status to {@code Ready to use}.
 *
 * Actors and operations:
 * - Customer and Travel Agency are package-order participants.
 * - Travel Agency calls {@code deliverPaynote}, {@code provideRestaurantOrder}, and
 *   {@code provideHotelOrder}.
 * - Card Processor calls {@code confirmAuthorization} and {@code confirmCapture}.
 * - Restaurant and Hotel each call {@code confirm} inside their embedded order scopes.
 */
class OfferPaynoteEmbeddedOrdersWorkflowTest {
    private static final String LANGUAGE_PROCESS_EMBEDDED_ROUTING_DEFECT =
            "Language Process Embedded routing defect: ";
    private static final String DOCUMENT_RESOURCE =
            "coordination/compute/offer-paynote-embedded-orders-bex.yaml";

    @Test
    void shouldInitializeExpectedOfferWithoutRootTemplates() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        Node authored = support.yamlResource(DOCUMENT_RESOURCE);

        // When
        ResolvedSnapshot initialized =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue, support.initialize(authored));

        // Then
        assertNoRootTemplates(authored);
        assertEquals("Awaiting PayNote", initialized.resolvedNodeAt("/order/status").getValue());
        assertEquals("20-21 June weekend", initialized.resolvedNodeAt("/package/title").getValue());
        assertEquals("Deluxe Room", initialized.resolvedNodeAt("/package/roomType").getValue());
        assertEquals("Restaurant Cud Malina", initialized.resolvedNodeAt("/package/restaurantName").getValue());
        assertEquals(BigInteger.valueOf(499), initialized.resolvedNodeAt("/package/price/amount").getValue());
    }

    @Test
    void shouldDeliverEmbeddedPaynoteAndRequestAuthorization() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot initialized = initializedSnapshot(support);

        // When
        DocumentProcessingResult delivered = support.blue.processDocument(
                initialized,
                operationEvent(support, "travel-agency", 12,
                        "deliverPaynote", packagePaynote(support)));

        // Then
        assertSuccessful(delivered);
        assertEquals("Waiting for PayNote capture", delivered.document().get("/order/status"));
        assertEquals(Boolean.TRUE, delivered.document().get("/order/paynoteDelivered"));
        assertEquals("Package PayNote", delivered.document().get("/paynote/name"));
        assertEquals("/paynote", delivered.document().get("/contracts/embeddedPaynotes/paths/0"));
        assertContainsEventKind(delivered.events(), "PayNote Authorization Requested");
    }

    @Test
    void shouldAuthorizeDeliveredPackagePaynote() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot delivered = deliveredPaynoteSnapshot(support);

        // When
        DocumentProcessingResult authorized = support.blue.processDocument(
                delivered,
                operationEvent(support, "card-processor", 14,
                        "confirmAuthorization", new Node()));

        // Then
        assertSuccessful(authorized);
        assertEquals("Authorized", authorized.document().get("/paynote/status"));
    }

    @Test
    void shouldEmbedRestaurantAndHotelOrdersAfterAuthorization() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot authorized = authorizedPaynoteSnapshot(support);

        // When
        DocumentProcessingResult restaurantProvided = support.blue.processDocument(
                authorized,
                operationEvent(support, "travel-agency", 16,
                        "provideRestaurantOrder", restaurantOrder(support)));
        ResolvedSnapshot withRestaurant =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue, restaurantProvided);
        DocumentProcessingResult hotelProvided = support.blue.processDocument(
                withRestaurant,
                operationEvent(support, "travel-agency", 17,
                        "provideHotelOrder", hotelOrder(support)));

        // Then
        assertSuccessful(restaurantProvided);
        assertSuccessful(hotelProvided);
        assertEquals("Restaurant Order", hotelProvided.document().get("/paynote/restaurantOrder/name"));
        assertEquals(Boolean.TRUE, hotelProvided.document().get("/paynote/restaurantOrderProvided"));
        assertEquals("/restaurantOrder", hotelProvided.document().get("/paynote/contracts/componentOrders/paths/0"));
        assertEquals("Hotel Order", hotelProvided.document().get("/paynote/hotelOrder/name"));
        assertEquals(Boolean.TRUE, hotelProvided.document().get("/paynote/hotelOrderProvided"));
        assertEquals("/hotelOrder", hotelProvided.document().get("/paynote/contracts/componentOrders/paths/1"));
    }

    @Test
    void shouldRequestCaptureOnlyAfterBothComponentOrdersConfirm() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot ordersProvided =
                componentOrdersProvidedSnapshot(support);

        // When
        DocumentProcessingResult restaurantConfirmed = support.blue.processDocument(
                ordersProvided,
                operationEvent(support, "restaurant", 18,
                        "confirm", new Node()));
        ResolvedSnapshot withRestaurantConfirmation =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue, restaurantConfirmed);
        DocumentProcessingResult hotelConfirmed = support.blue.processDocument(
                withRestaurantConfirmation,
                operationEvent(support, "hotel", 19,
                        "confirm", new Node()));

        // Then
        assertSuccessful(restaurantConfirmed);
        assertSuccessful(hotelConfirmed);
        assertEquals("Confirmed", restaurantConfirmed.document().get("/paynote/restaurantOrder/status"));
        assertEquals(Boolean.TRUE, restaurantConfirmed.document().get("/paynote/restaurantConfirmed"));
        assertEquals(Boolean.FALSE, restaurantConfirmed.document().get("/paynote/captureRequested"));
        assertEquals("Confirmed", hotelConfirmed.document().get("/paynote/hotelOrder/status"));
        assertEquals(Boolean.TRUE, hotelConfirmed.document().get("/paynote/hotelConfirmed"));
        assertEquals(Boolean.TRUE, hotelConfirmed.document().get("/paynote/captureRequested"));
    }

    @Test
    void shouldMakePackageReadyAfterCapturingConfirmedComponentOrders() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot confirmedOrders =
                confirmedOrdersSnapshot(support);

        // When
        DocumentProcessingResult captured = support.blue.processDocument(
                confirmedOrders,
                operationEvent(support, "card-processor", 20,
                        "confirmCapture", new Node()));

        // Then
        assertSuccessful(captured);
        assertEquals("Captured", captured.document().get("/paynote/status"));
        assertEquals(Boolean.TRUE, captured.document().get("/paynote/captured"));
        assertEquals("Ready to use", captured.document().get("/order/status"));
        assertContainsEventKind(captured.events(), "Package Order Ready to Use");
    }

    @Test
    void shouldPreserveSnapshotOptimizationsAcrossPackageLifecycle() {
        // Given
        BexProcessingMetrics metrics = new BexProcessingMetrics();

        // When
        MeasuredLifecycle lifecycle = runMeasuredLifecycle(metrics);

        // Then
        assertSuccessful(lifecycle.captured);
        assertEquals(0L, metrics.updateIndividualPatchApplications());
        assertEquals(metrics.updateBatchPatchApplications(), metrics.directBexChangesetHits());
        assertEquals(0L, metrics.bexDocumentViewMaterializedHits());
        assertEquals(0L, metrics.bexSyntheticProgramMaterializations());
        assertEquals(0L, metrics.workflowDocumentViewsFromDocument());
        assertEquals(0L, metrics.workflowDocumentViewMisses());
        assertTrue(metrics.bexDocumentViewFrozenDirectHits() > 0L);
        assertEquals(
                lifecycle.snapshotBuildsAfterInitialize,
                metrics.processingSnapshotFromDocumentBuilds());
    }

    @Test
    void shouldRejectPaynoteWithWrongAmount() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot current =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue,
                        support.initialize(support.yamlResource(DOCUMENT_RESOURCE)));

        // When
        // Illegal: wrong PayNote amount. The package order only accepts the exact 499 PLN PayNote for
        // this Hotel Badura + Cud Malina weekend package. This is rejected by deliverPaynote.request
        // matching, so the workflow does not run and the document is unchanged.
        Node wrongPaynote = packagePaynote(support);
        wrongPaynote.getProperties().put("amount", new Node().value(498));
        DocumentProcessingResult wrongPaynoteResult = support.blue.processDocument(current,
                operationEvent(support, "travel-agency", 11, "deliverPaynote", wrongPaynote));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(wrongPaynoteResult), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(wrongPaynoteResult));
        assertFalse(wrongPaynoteResult.document().getProperties().containsKey("paynote"));
        assertEquals("Awaiting PayNote", wrongPaynoteResult.document().get("/order/status"));
    }

    @Test
    void shouldRejectComponentOrderBeforePaynoteAuthorization() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot current = deliveredPaynoteSnapshot(support);

        // When
        // Illegal: Travel Agency cannot provide component orders until Card Processor authorizes the
        // embedded PayNote.
        DocumentProcessingResult beforeAuthorization = support.blue.processDocument(current,
                operationEvent(support, "travel-agency", 13, "provideHotelOrder", hotelOrder(support)));

        // Then
        assertRuntimeFatal(beforeAuthorization, "after PayNote authorization");
    }

    @Test
    void shouldRejectHotelDocumentForRestaurantOrder() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot current = authorizedPaynoteSnapshot(support);

        // When
        // Illegal: provideRestaurantOrder rejects a hotel document at operation-request matching time.
        // Restaurant and hotel fulfillment documents are intentionally specific and not interchangeable.
        DocumentProcessingResult wrongRestaurantDocument = support.blue.processDocument(current,
                operationEvent(support, "travel-agency", 15, "provideRestaurantOrder", hotelOrder(support)));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(wrongRestaurantDocument), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(wrongRestaurantDocument));
        assertFalse(wrongRestaurantDocument.document().getAsNode("/paynote").getProperties()
                .containsKey("restaurantOrder"));
        assertEquals(Boolean.FALSE, wrongRestaurantDocument.document().get("/paynote/restaurantOrderProvided"));
    }

    @Test
    void shouldRejectCaptureBeforeBothComponentOrdersConfirm() {
        // Given
        ComputeWorkflowTestSupport support = support(null);
        ResolvedSnapshot current = componentOrdersProvidedSnapshot(support);

        // When
        // Illegal: Card Processor cannot capture before both Restaurant and Hotel have confirmed.
        DocumentProcessingResult earlyCapture = support.blue.processDocument(current,
                operationEvent(support, "card-processor", 18, "confirmCapture", new Node()));

        // Then
        assertRuntimeFatal(earlyCapture, "before both orders confirm");
    }

    private static ResolvedSnapshot initializedSnapshot(
            ComputeWorkflowTestSupport support) {
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue,
                support.initialize(
                        support.yamlResource(DOCUMENT_RESOURCE)));
    }

    private static ResolvedSnapshot deliveredPaynoteSnapshot(
            ComputeWorkflowTestSupport support) {
        ResolvedSnapshot initialized =
                initializedSnapshot(support);
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue,
                support.blue.processDocument(initialized,
                        operationEvent(support, "travel-agency", 12,
                                "deliverPaynote", packagePaynote(support))));
    }

    private static ResolvedSnapshot authorizedPaynoteSnapshot(
            ComputeWorkflowTestSupport support) {
        ResolvedSnapshot delivered = deliveredPaynoteSnapshot(support);
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue,
                support.blue.processDocument(delivered,
                        operationEvent(support, "card-processor", 14,
                                "confirmAuthorization", new Node())));
    }

    private static ResolvedSnapshot componentOrdersProvidedSnapshot(
            ComputeWorkflowTestSupport support) {
        ResolvedSnapshot authorized = authorizedPaynoteSnapshot(support);
        ResolvedSnapshot withRestaurant =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue,
                        support.blue.processDocument(authorized,
                                operationEvent(support, "travel-agency", 16,
                                        "provideRestaurantOrder",
                                        restaurantOrder(support))));
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue,
                support.blue.processDocument(withRestaurant,
                        operationEvent(support, "travel-agency", 17,
                                        "provideHotelOrder", hotelOrder(support))));
    }

    private static ResolvedSnapshot confirmedOrdersSnapshot(
            ComputeWorkflowTestSupport support) {
        ResolvedSnapshot ordersProvided =
                componentOrdersProvidedSnapshot(support);
        ResolvedSnapshot restaurantConfirmed =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        support.blue,
                        support.blue.processDocument(
                                ordersProvided,
                                operationEvent(
                                        support,
                                        "restaurant",
                                        18,
                                        "confirm",
                                        new Node())));
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue,
                support.blue.processDocument(
                        restaurantConfirmed,
                        operationEvent(
                                support,
                                "hotel",
                                19,
                                "confirm",
                                new Node())));
    }

    private static MeasuredLifecycle runMeasuredLifecycle(
            BexProcessingMetrics metrics) {
        ComputeWorkflowTestSupport support =
                support(metrics);
        ResolvedSnapshot current =
                initializedSnapshot(support);
        long snapshotBuildsAfterInitialize =
                metrics.processingSnapshotFromDocumentBuilds();

        DocumentProcessingResult delivered =
                processMeasured(
                        metrics, "deliverPaynote", support, current,
                        operationEvent(
                                support, "travel-agency", 1,
                                "deliverPaynote",
                                packagePaynote(support)));
        current = snapshot(support, delivered);
        DocumentProcessingResult authorized =
                processMeasured(
                        metrics, "confirmAuthorization", support, current,
                        operationEvent(
                                support, "card-processor", 2,
                                "confirmAuthorization", new Node()));
        current = snapshot(support, authorized);
        DocumentProcessingResult restaurantProvided =
                processMeasured(
                        metrics, "provideRestaurantOrder", support, current,
                        operationEvent(
                                support, "travel-agency", 3,
                                "provideRestaurantOrder",
                                restaurantOrder(support)));
        current = snapshot(support, restaurantProvided);
        DocumentProcessingResult hotelProvided =
                processMeasured(
                        metrics, "provideHotelOrder", support, current,
                        operationEvent(
                                support, "travel-agency", 4,
                                "provideHotelOrder",
                                hotelOrder(support)));
        current = snapshot(support, hotelProvided);
        DocumentProcessingResult restaurantConfirmed =
                processMeasured(
                        metrics, "restaurantConfirm", support, current,
                        operationEvent(
                                support, "restaurant", 5,
                                "confirm", new Node()));
        current = snapshot(support, restaurantConfirmed);
        DocumentProcessingResult hotelConfirmed =
                processMeasured(
                        metrics, "hotelConfirm", support, current,
                        operationEvent(
                                support, "hotel", 6,
                                "confirm", new Node()));
        current = snapshot(support, hotelConfirmed);
        DocumentProcessingResult captured =
                processMeasured(
                        metrics, "confirmCapture", support, current,
                        operationEvent(
                                support, "card-processor", 7,
                                "confirmCapture", new Node()));
        return new MeasuredLifecycle(
                captured,
                snapshotBuildsAfterInitialize);
    }

    private static ResolvedSnapshot snapshot(
            ComputeWorkflowTestSupport support,
            DocumentProcessingResult result) {
        return blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                support.blue, result);
    }

    private static void assertSuccessful(
            DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                LANGUAGE_PROCESS_EMBEDDED_ROUTING_DEFECT
                        + blue.coordination.processor
                                .ProcessingResultTestSupport
                                .diagnosticMessage(result));
    }

    private static ComputeWorkflowTestSupport support(BexProcessingMetrics metrics) {
        CoordinationProcessorOptions.Builder builder = CoordinationProcessorOptions.builder();
        if (metrics != null) {
            builder.processingMetrics(metrics);
        }
        return ComputeWorkflowTestSupport.create(builder.build());
    }

    private static DocumentProcessingResult processMeasured(BexProcessingMetrics metrics,
                                                            String label,
                                                            ComputeWorkflowTestSupport support,
                                                            ResolvedSnapshot document,
                                                            Node event) {
        return support.blue.processDocument(
                document,
                event);
    }

    private static final class MeasuredLifecycle {
        private final DocumentProcessingResult captured;
        private final long snapshotBuildsAfterInitialize;

        private MeasuredLifecycle(
                DocumentProcessingResult captured,
                long snapshotBuildsAfterInitialize) {
            this.captured = captured;
            this.snapshotBuildsAfterInitialize =
                    snapshotBuildsAfterInitialize;
        }
    }

    private static void assertNoRootTemplates(Node document) {
        assertFalse(document.getProperties().containsKey("paynoteTemplate"));
        assertFalse(document.getProperties().containsKey("hotelOrderTemplate"));
        assertFalse(document.getProperties().containsKey("restaurantOrderTemplate"));
    }

    private static Node operationEvent(ComputeWorkflowTestSupport support,
                                       String timelineId,
                                       int timestamp,
                                       String operation,
                                       Node request) {
        return support.operationRequest(
                timelineId,
                timestamp,
                operation,
                operationChannel(timelineId, operation),
                request);
    }

    private static String operationChannel(String timelineId, String operation) {
        if ("deliverPaynote".equals(operation)) {
            return "packageParticipants";
        }
        if ("confirmAuthorization".equals(operation) || "confirmCapture".equals(operation)) {
            return "cardProcessorChannel";
        }
        if ("provideRestaurantOrder".equals(operation) || "provideHotelOrder".equals(operation)) {
            return "travelAgencyChannel";
        }
        if ("confirm".equals(operation) && "restaurant".equals(timelineId)) {
            return "restaurantChannel";
        }
        if ("confirm".equals(operation) && "hotel".equals(timelineId)) {
            return "hotelChannel";
        }
        throw new IllegalArgumentException("Unknown operation route: " + operation + " from " + timelineId);
    }

    private static Node packagePaynote(ComputeWorkflowTestSupport support) {
        return support.yaml(String.join("\n",
                "name: Package PayNote",
                "packageId: weekend-badura-cud-malina",
                "status: Pending authorization",
                "amount: 499",
                "currency: PLN",
                "startDate: 2026-06-20",
                "endDate: 2026-06-21",
                "customer: Customer",
                "travelAgency: Travel Agency",
                "cardProcessor: Card Processor",
                "restaurantOrderProvided: false",
                "hotelOrderProvided: false",
                "restaurantConfirmed: false",
                "hotelConfirmed: false",
                "captureRequested: false",
                "captured: false",
                "contracts:",
                "  travelAgencyChannel:",
                "    type: Coordination/Timeline Channel",
                "    timeline:",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId: travel-agency",
                "    actor:",
                "      type: MyOS/Principal Actor",
                "      accountId: travel-agency",
                "  cardProcessorChannel:",
                "    type: Coordination/Timeline Channel",
                "    timeline:",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId: card-processor",
                "    actor:",
                "      type: MyOS/Principal Actor",
                "      accountId: card-processor",
                "  confirmAuthorization:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: cardProcessorChannel",
                "    steps:",
                "      - name: BuildAuthorizationPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $ne:",
                "                  - $document: /status",
                "                  - Pending authorization",
                "              then:",
                "                - $fail: PayNote authorization can only be confirmed while pending",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: Authorized",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: PayNote Authorized",
                "              amount:",
                "                $document: /amount",
                "              currency:",
                "                $document: /currency",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "  provideRestaurantOrder:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: travelAgencyChannel",
                "    request:",
                "      name: Restaurant Order",
                "      packageId: weekend-badura-cud-malina",
                "      restaurantName: Restaurant Cud Malina",
                "      description: 250zl Dinner for Two",
                "      dinnerDate: 2026-06-20",
                "      amount: 250",
                "      currency: PLN",
                "      status: Pending",
                "      contracts:",
                "        restaurantChannel:",
                "          timeline:",
                "            timelineId: restaurant",
                "          actor:",
                "            accountId: restaurant",
                "        confirm:",
                "          channel: restaurantChannel",
                "    steps:",
                "      - name: BuildRestaurantOrderPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $ne:",
                "                  - $document: /status",
                "                  - Authorized",
                "              then:",
                "                - $fail: Restaurant Order can only be provided after PayNote authorization",
                "          - $if:",
                "              cond:",
                "                $document: /restaurantOrderProvided",
                "              then:",
                "                - $fail: Restaurant Order is already provided",
                "          - $appendChange:",
                "              op: add",
                "              path: /restaurantOrder",
                "              val:",
                "                $binding:",
                "                  name: event",
                "                  path: /message/request",
                "          - $appendChange:",
                "              op: replace",
                "              path: /restaurantOrderProvided",
                "              val: true",
                "          - $appendChange:",
                "              op: add",
                "              path: /contracts/componentOrders/paths/-",
                "              val: /restaurantOrder",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "  provideHotelOrder:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: travelAgencyChannel",
                "    request:",
                "      name: Hotel Order",
                "      packageId: weekend-badura-cud-malina",
                "      hotelName: Hotel Badura",
                "      roomType: Deluxe Room",
                "      checkIn: 2026-06-20",
                "      checkOut: 2026-06-21",
                "      amount: 249",
                "      currency: PLN",
                "      status: Pending",
                "      contracts:",
                "        hotelChannel:",
                "          timeline:",
                "            timelineId: hotel",
                "          actor:",
                "            accountId: hotel",
                "        confirm:",
                "          channel: hotelChannel",
                "    steps:",
                "      - name: BuildHotelOrderPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $ne:",
                "                  - $document: /status",
                "                  - Authorized",
                "              then:",
                "                - $fail: Hotel Order can only be provided after PayNote authorization",
                "          - $if:",
                "              cond:",
                "                $document: /hotelOrderProvided",
                "              then:",
                "                - $fail: Hotel Order is already provided",
                "          - $appendChange:",
                "              op: add",
                "              path: /hotelOrder",
                "              val:",
                "                $binding:",
                "                  name: event",
                "                  path: /message/request",
                "          - $appendChange:",
                "              op: replace",
                "              path: /hotelOrderProvided",
                "              val: true",
                "          - $appendChange:",
                "              op: add",
                "              path: /contracts/componentOrders/paths/-",
                "              val: /hotelOrder",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "  componentOrders:",
                "    type: Process Embedded",
                "    paths: []",
                "  restaurantOrderEvents:",
                "    type: Embedded Node Channel",
                "    sourcePath: /restaurantOrder",
                "  hotelOrderEvents:",
                "    type: Embedded Node Channel",
                "    sourcePath: /hotelOrder",
                "  restaurantOrderConfirmed:",
                "    type: Coordination/Sequential Workflow",
                "    channel: restaurantOrderEvents",
                "    event:",
                "      type: Coordination/Event",
                "      kind: Component Order Confirmed",
                "      component: restaurant",
                "    steps:",
                "      - name: BuildRestaurantConfirmedPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /restaurantConfirmed",
                "              val: true",
                "          - $if:",
                "              cond:",
                "                $and:",
                "                  - $document: /hotelConfirmed",
                "                  - $not:",
                "                      $document: /captureRequested",
                "              then:",
                "                - $appendChange:",
                "                    op: replace",
                "                    path: /captureRequested",
                "                    val: true",
                "                - $appendEvent:",
                "                    type: Coordination/Event",
                "                    kind: PayNote Capture Requested",
                "                    amount:",
                "                      $document: /amount",
                "                    currency:",
                "                      $document: /currency",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "  hotelOrderConfirmed:",
                "    type: Coordination/Sequential Workflow",
                "    channel: hotelOrderEvents",
                "    event:",
                "      type: Coordination/Event",
                "      kind: Component Order Confirmed",
                "      component: hotel",
                "    steps:",
                "      - name: BuildHotelConfirmedPatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $appendChange:",
                "              op: replace",
                "              path: /hotelConfirmed",
                "              val: true",
                "          - $if:",
                "              cond:",
                "                $and:",
                "                  - $document: /restaurantConfirmed",
                "                  - $not:",
                "                      $document: /captureRequested",
                "              then:",
                "                - $appendChange:",
                "                    op: replace",
                "                    path: /captureRequested",
                "                    val: true",
                "                - $appendEvent:",
                "                    type: Coordination/Event",
                "                    kind: PayNote Capture Requested",
                "                    amount:",
                "                      $document: /amount",
                "                    currency:",
                "                      $document: /currency",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true",
                "  confirmCapture:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: cardProcessorChannel",
                "    steps:",
                "      - name: BuildCapturePatch",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $not:",
                "                  $document: /captureRequested",
                "              then:",
                "                - $fail: PayNote capture cannot be confirmed before both orders confirm",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: Captured",
                "          - $appendChange:",
                "              op: replace",
                "              path: /captured",
                "              val: true",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: PayNote Captured",
                "              amount:",
                "                $document: /amount",
                "              currency:",
                "                $document: /currency",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true"));
    }

    private static Node restaurantOrder(ComputeWorkflowTestSupport support) {
        return support.yaml(String.join("\n",
                "name: Restaurant Order",
                "packageId: weekend-badura-cud-malina",
                "restaurantName: Restaurant Cud Malina",
                "description: 250zl Dinner for Two",
                "dinnerDate: 2026-06-20",
                "amount: 250",
                "currency: PLN",
                "status: Pending",
                "contracts:",
                "  restaurantChannel:",
                "    type: Coordination/Timeline Channel",
                "    timeline:",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId: restaurant",
                "    actor:",
                "      type: MyOS/Principal Actor",
                "      accountId: restaurant",
                "  confirm:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: restaurantChannel",
                "    steps:",
                "      - name: BuildConfirmation",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $ne:",
                "                  - $document: /status",
                "                  - Pending",
                "              then:",
                "                - $fail: Restaurant Order can only be confirmed while pending",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: Confirmed",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Component Order Confirmed",
                "              component: restaurant",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true"));
    }

    private static Node hotelOrder(ComputeWorkflowTestSupport support) {
        return support.yaml(String.join("\n",
                "name: Hotel Order",
                "packageId: weekend-badura-cud-malina",
                "hotelName: Hotel Badura",
                "roomType: Deluxe Room",
                "checkIn: 2026-06-20",
                "checkOut: 2026-06-21",
                "amount: 249",
                "currency: PLN",
                "status: Pending",
                "contracts:",
                "  hotelChannel:",
                "    type: Coordination/Timeline Channel",
                "    timeline:",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId: hotel",
                "    actor:",
                "      type: MyOS/Principal Actor",
                "      accountId: hotel",
                "  confirm:",
                "    type: Coordination/Sequential Workflow Operation",
                "    channel: hotelChannel",
                "    steps:",
                "      - name: BuildConfirmation",
                "        type: Coordination/Compute",
                "        do:",
                "          - $if:",
                "              cond:",
                "                $ne:",
                "                  - $document: /status",
                "                  - Pending",
                "              then:",
                "                - $fail: Hotel Order can only be confirmed while pending",
                "          - $appendChange:",
                "              op: replace",
                "              path: /status",
                "              val: Confirmed",
                "          - $appendEvent:",
                "              type: Coordination/Event",
                "              kind: Component Order Confirmed",
                "              component: hotel",
                "          - $return:",
                "              changeset:",
                "                $changeset: true",
                "              events:",
                "                $events: true"));
    }

    private static void assertContainsEventKind(List<Node> events, String expectedKind) {
        for (Node event : events) {
            if (expectedKind.equals(eventKind(event))) {
                return;
            }
        }
        throw new AssertionError("Expected event kind " + expectedKind + " in " + events);
    }

    private static String eventKind(Node event) {
        if (event == null) {
            return null;
        }
        Node kind = event.getProperties() != null ? event.getProperties().get("kind") : null;
        Object value = kind != null ? kind.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    private static void assertRuntimeFatal(DocumentProcessingResult result, String expectedMessage) {
        assertEquals(
                ProcessorStatus.RUNTIME_FATAL,
                result.status(),
                LANGUAGE_PROCESS_EMBEDDED_ROUTING_DEFECT
                        + blue.coordination.processor
                                .ProcessingResultTestSupport
                                .diagnosticMessage(result));
        if (blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result) != null && blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(expectedMessage)) {
            return;
        }
        assertTrue(containsStringValue(result.document(), expectedMessage),
                "Expected fatal reason containing: " + expectedMessage);
    }

    private static boolean containsStringValue(Node node, String expectedMessage) {
        if (node == null) {
            return false;
        }
        Object value = node.getValue();
        if (value instanceof String && ((String) value).contains(expectedMessage)) {
            return true;
        }
        if (containsStringValue(node.getType(), expectedMessage)) {
            return true;
        }
        if (containsStringValue(node.getContracts(), expectedMessage)) {
            return true;
        }
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                if (containsStringValue(item, expectedMessage)) {
                    return true;
                }
            }
        }
        if (node.getProperties() != null) {
            for (Node property : node.getProperties().values()) {
                if (containsStringValue(property, expectedMessage)) {
                    return true;
                }
            }
        }
        return false;
    }

}
