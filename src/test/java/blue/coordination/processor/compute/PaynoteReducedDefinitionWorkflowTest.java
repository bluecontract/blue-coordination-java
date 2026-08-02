package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.ExternalBlockerProbeAssertions;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scenario:
 * A reduced Paynote resale package document exercises shared Compute Definitions, BEX field fast paths,
 * batch patching, bundle caching, and cold/warm processing metrics.
 *
 * Main flow:
 * 1. A hotel participant places a resale order through the hotel participant timeline.
 * 2. The participant workflow forwards a subscription update event.
 * 3. The package workflow catches that update, calls the hotel entry function from the shared
 *    {@code packageFulfillmentComputeDefinition}, and applies its returned changeset.
 * 4. A restaurant participant repeats the same pattern through a different operation and different
 *    definition entry function.
 * 5. Tests print cold, warm, same-path, and event-only timing so setup, compilation, bundle loading,
 *    handler matching, BEX execution, and patch application costs are visible.
 *
 * Actors and operations:
 * - {@code hotel-participant} calls {@code hotelResaleOrderPlaced}.
 * - {@code restaurant-participant} calls {@code restaurantResaleOrderPlaced}.
 * - Both operations share one Compute Definition but enter different functions.
 * - Compute returns changesets and events directly from BEX accumulators.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaynoteReducedDefinitionWorkflowTest {
    private static final boolean PRINT_TIMINGS =
            Boolean.getBoolean("blue.tests.printTimings");
    private static final String DOCUMENT_RESOURCE = "/processor-delay/paynote-resale-reduced-bex.yaml";
    private static Fixture fixture;
    private static BexProcessingMetrics metrics;
    private static ResolvedSnapshot initializedSnapshot;
    private static Node hotelEvent;
    private static Node restaurantEvent;
    private static double setupBlueMs;
    private static double loadYamlMs;
    private static double initializeMs;
    private static double buildHotelEventMs;
    private static double buildRestaurantEventMs;

    @BeforeAll
    static void prepareFixture() {
        long start = System.nanoTime();
        metrics = new BexProcessingMetrics();
        fixture = configuredFixture(metrics);
        setupBlueMs = elapsedMs(start);

        start = System.nanoTime();
        Node document = loadYaml(fixture, DOCUMENT_RESOURCE);
        loadYamlMs = elapsedMs(start);

        start = System.nanoTime();
        initializedSnapshot =
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue,
                        fixture.blue.initializeDocument(document));
        initializeMs = elapsedMs(start);

        start = System.nanoTime();
        hotelEvent = participantOperation(fixture,
                "hotel-participant",
                1700000100,
                "hotelResaleOrderPlaced",
                subscriptionUpdate("hotel-resale-agreement",
                        "hotel-agreement-session",
                        "hotel-request-a",
                        "hotel-order-session-a"));
        buildHotelEventMs = elapsedMs(start);

        start = System.nanoTime();
        restaurantEvent = participantOperation(fixture,
                "restaurant-participant",
                1700000200,
                "restaurantResaleOrderPlaced",
                subscriptionUpdate("restaurant-resale-agreement",
                        "restaurant-agreement-session",
                        "restaurant-request-a",
                        "restaurant-order-session-a"));
        buildRestaurantEventMs = elapsedMs(start);
    }

    @Test
    @Order(1)
    void shouldMeasureColdAndWarmEventProcessing() {
        // Given
        BexProcessingMetrics.Snapshot beforeCold = metrics.snapshot();

        // When
        long start = System.nanoTime();
        DocumentProcessingResult coldHotel = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        double coldHotelMs = elapsedMs(start);

        start = System.nanoTime();
        DocumentProcessingResult coldRestaurant = fixture.blue.processDocument(
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue, coldHotel),
                restaurantEvent);
        double coldRestaurantMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterCold = metrics.snapshot();

        start = System.nanoTime();
        DocumentProcessingResult warmHotel = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        double warmHotelMs = elapsedMs(start);

        start = System.nanoTime();
        DocumentProcessingResult warmRestaurant = fixture.blue.processDocument(
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue, warmHotel),
                restaurantEvent);
        double warmRestaurantMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterWarm = metrics.snapshot();

        // Then
        classifyReducedHandlerSelection(
                "cold hotel/restaurant",
                beforeCold,
                afterCold,
                2L,
                coldHotel,
                coldRestaurant);
        classifyReducedHandlerSelection(
                "warm hotel/restaurant",
                afterCold,
                afterWarm,
                2L,
                warmHotel,
                warmRestaurant);
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(coldHotel), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(coldHotel));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(coldRestaurant), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(coldRestaurant));
        assertEquals(Boolean.TRUE, coldRestaurant.document().get("/orders/package-order-a/hotelOrder/resalePlaced"));
        assertEquals(Boolean.TRUE, coldRestaurant.document().get("/orders/package-order-a/restaurantOrder/resalePlaced"));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmHotel), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmHotel));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmRestaurant), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmRestaurant));
        assertEquals(Boolean.TRUE, warmRestaurant.document().get("/orders/package-order-a/hotelOrder/resalePlaced"));
        assertEquals(Boolean.TRUE, warmRestaurant.document().get("/orders/package-order-a/restaurantOrder/resalePlaced"));

        printTimingOutput(
                "Paynote reduced BEX cold/warm timing - coldHotelMs: %.3fms, coldRestaurantMs: %.3fms, " +
                        "warmHotelMs: %.3fms, warmRestaurantMs: %.3fms%n",
                coldHotelMs,
                coldRestaurantMs,
                warmHotelMs,
                warmRestaurantMs);
        printMetricsDelta("cold event-only metrics delta", beforeCold, afterCold);
        printMetricsDelta("warm event-only metrics delta", afterCold, afterWarm);
        assertEquals(2L, afterCold.updateBatchPatchApplications - beforeCold.updateBatchPatchApplications);
        assertEquals(0L, afterCold.updateIndividualPatchApplications - beforeCold.updateIndividualPatchApplications);
        assertEquals(2L, afterWarm.updateBatchPatchApplications - afterCold.updateBatchPatchApplications);
        assertEquals(0L, afterWarm.updateIndividualPatchApplications - afterCold.updateIndividualPatchApplications);
    }

    @Test
    @Order(2)
    void shouldProcessHotelParticipantOperationWithSharedDefinition() {
        // Given
        long totalStart = System.nanoTime();
        BexProcessingMetrics.Snapshot before = metrics.snapshot();
        printSetupTimings();

        // When
        long start = System.nanoTime();
        DocumentProcessingResult hotelResult = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        printTiming("process hotel participant operation", start);

        // Then
        BexProcessingMetrics.Snapshot after =
                metrics.snapshot();
        classifyReducedHandlerSelection(
                "hotel shared-definition Handler",
                before,
                after,
                1L,
                hotelResult);
        assertParticipantOperationResult(
                hotelResult,
                "hotel-request-a",
                "hotelOrder",
                "hotel-order-session-a",
                "snapshot:component:hotel:hotel-order-session-a",
                "agreement-linked:hotel:hotel-order-session-a");
        printTiming("total reduced paynote flow", totalStart);
        printMetricsDelta("reduced paynote flow metrics", before, metrics.snapshot());
    }

    @Test
    @Order(3)
    void shouldProcessRestaurantParticipantOperationWithSharedDefinition() {
        // Given
        BexProcessingMetrics.Snapshot before =
                metrics.snapshot();
        DocumentProcessingResult hotelResult =
                fixture.blue.processDocument(
                        initializedSnapshot,
                        hotelEvent);

        // When
        DocumentProcessingResult restaurantResult =
                fixture.blue.processDocument(
                        blue.coordination.processor
                                .ProcessingResultTestSupport
                                .snapshot(
                                        fixture.blue,
                                        hotelResult),
                        restaurantEvent);
        BexProcessingMetrics.Snapshot after =
                metrics.snapshot();

        // Then
        classifyReducedHandlerSelection(
                "restaurant shared-definition Handler",
                before,
                after,
                2L,
                hotelResult,
                restaurantResult);
        assertParticipantOperationResult(
                restaurantResult,
                "restaurant-request-a",
                "restaurantOrder",
                "restaurant-order-session-a",
                "snapshot:component:restaurant:restaurant-order-session-a",
                "agreement-linked:restaurant:restaurant-order-session-a");
        assertEquals(
                Boolean.TRUE,
                restaurantResult.document().get(
                        "/orders/package-order-a/hotelOrder/resalePlaced"));
    }

    @Test
    @Order(4)
    void shouldMeasureColdAndWarmTimingForSameEventPath() {
        // Given
        BexProcessingMetrics.Snapshot beforeHotelCold = metrics.snapshot();

        // When
        long start = System.nanoTime();
        DocumentProcessingResult coldHotel = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        double coldHotelMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterHotelCold = metrics.snapshot();

        start = System.nanoTime();
        DocumentProcessingResult warmHotel = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        double warmHotelMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterHotelWarm = metrics.snapshot();

        BexProcessingMetrics.Snapshot beforeRestaurantCold = metrics.snapshot();
        start = System.nanoTime();
        DocumentProcessingResult coldRestaurant = fixture.blue.processDocument(initializedSnapshot, restaurantEvent);
        double coldRestaurantMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterRestaurantCold = metrics.snapshot();

        start = System.nanoTime();
        DocumentProcessingResult warmRestaurant = fixture.blue.processDocument(initializedSnapshot, restaurantEvent);
        double warmRestaurantMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot afterRestaurantWarm = metrics.snapshot();

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(coldHotel), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(coldHotel));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmHotel), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmHotel));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(coldRestaurant), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(coldRestaurant));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmRestaurant), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmRestaurant));

        printTimingOutput(
                "Paynote reduced BEX same-path cold/warm timing - coldHotelMs: %.3fms, warmHotelMs: %.3fms, " +
                        "coldRestaurantMs: %.3fms, warmRestaurantMs: %.3fms%n",
                coldHotelMs,
                warmHotelMs,
                coldRestaurantMs,
                warmRestaurantMs);
        printMetricsDelta("same-path hotel cold delta", beforeHotelCold, afterHotelCold);
        printMetricsDelta("same-path hotel warm delta", afterHotelCold, afterHotelWarm);
        printMetricsDelta("same-path restaurant cold delta", beforeRestaurantCold, afterRestaurantCold);
        printMetricsDelta("same-path restaurant warm delta", afterRestaurantCold, afterRestaurantWarm);
    }

    @Test
    @Order(5)
    void shouldMeasureEventProcessingAfterWarmup() {
        // Given
        BexProcessingMetrics.Snapshot beforeWarm =
                metrics.snapshot();
        DocumentProcessingResult warmHotel = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmHotel), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmHotel));
        DocumentProcessingResult warmRestaurant = fixture.blue.processDocument(
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue, warmHotel),
                restaurantEvent);
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(warmRestaurant), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(warmRestaurant));
        BexProcessingMetrics.Snapshot afterWarm =
                metrics.snapshot();
        classifyReducedHandlerSelection(
                "event-only warmup",
                beforeWarm,
                afterWarm,
                2L,
                warmHotel,
                warmRestaurant);

        // When
        BexProcessingMetrics.Snapshot before = metrics.snapshot();
        long start = System.nanoTime();
        DocumentProcessingResult hotelResult = fixture.blue.processDocument(initializedSnapshot, hotelEvent);
        double processHotelMs = elapsedMs(start);

        start = System.nanoTime();
        DocumentProcessingResult restaurantResult = fixture.blue.processDocument(
                blue.coordination.processor.ProcessingResultTestSupport.snapshot(
                        fixture.blue, hotelResult),
                restaurantEvent);
        double processRestaurantMs = elapsedMs(start);
        BexProcessingMetrics.Snapshot after = metrics.snapshot();

        // Then
        classifyReducedHandlerSelection(
                "event-only measured",
                before,
                after,
                2L,
                hotelResult,
                restaurantResult);
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(hotelResult), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(hotelResult));
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(restaurantResult), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(restaurantResult));
        assertEquals(Boolean.TRUE, restaurantResult.document().get("/orders/package-order-a/hotelOrder/resalePlaced"));
        assertEquals(Boolean.TRUE, restaurantResult.document().get("/orders/package-order-a/restaurantOrder/resalePlaced"));

        printTimingOutput(
                "Paynote reduced BEX event-only timing - processHotelMs: %.3fms, processRestaurantMs: %.3fms%n",
                processHotelMs,
                processRestaurantMs);
        printMetricsDelta("event-only metrics delta", before, after);
        assertEquals(2L, after.updateBatchPatchApplications - before.updateBatchPatchApplications);
        assertEquals(0L, after.updateIndividualPatchApplications - before.updateIndividualPatchApplications);
    }

    private static void assertParticipantOperationResult(
            DocumentProcessingResult result,
            String requestId,
            String component,
            String orderSessionId,
            String snapshotRequestId,
            String subscriptionId) {
        assertFalse(
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .isCapabilityFailure(result),
                blue.coordination.processor
                        .ProcessingResultTestSupport
                        .diagnosticMessage(result));
        assertNotNull(result.document());
        assertEquals(
                "placed",
                result.document().getAsText(
                        "/resaleOrderRequests/"
                                + requestId
                                + "/status"),
                result.events().toString());
        assertEquals(
                orderSessionId,
                result.document().getAsText(
                        "/resaleOrderRequests/"
                                + requestId
                                + "/orderSessionId"));
        assertEquals(
                Boolean.TRUE,
                result.document().get(
                        "/orders/package-order-a/"
                                + component
                                + "/resalePlaced"));
        assertEquals(
                orderSessionId,
                result.document().getAsText(
                        "/orders/package-order-a/"
                                + component
                                + "/sessionId"));
        assertEquals(
                snapshotRequestId,
                result.document().getAsText(
                        "/orders/package-order-a/"
                                + component
                                + "/snapshotRequestId"));
        assertEquals(
                subscriptionId,
                result.document().getAsText(
                        "/orders/package-order-a/"
                                + component
                                + "/subscriptionId"));
        assertEquals(
                "package-order-a",
                result.document().getAsText(
                        "/componentOrderRefsBySessionId/"
                                + orderSessionId
                                + "/packageOrderSessionId"));
        assertEquals(
                component,
                result.document().getAsText(
                        "/componentOrderRefsBySessionId/"
                                + orderSessionId
                                + "/component"));
        assertContainsType(
                result.events(),
                "MyOS/Document Initial Snapshot Requested");
        assertContainsType(
                result.events(),
                "MyOS/Subscribe to Session Requested");
    }

    private static void classifyReducedHandlerSelection(
            String context,
            BexProcessingMetrics.Snapshot before,
            BexProcessingMetrics.Snapshot after,
            long repairedBatchCount,
            DocumentProcessingResult... results) {
        boolean exactStatuses = true;
        boolean noEvents = true;
        boolean unchangedBusinessState = true;
        StringBuilder resultTuples =
                new StringBuilder();
        for (DocumentProcessingResult result :
                results) {
            exactStatuses &= result != null
                    && result.status()
                    == ProcessorStatus.SUCCESS
                    && result.diagnostic() == null;
            noEvents &= result != null
                    && result.events().isEmpty();
            if (result != null) {
                Object hotelStatus =
                        result.document().get(
                                "/resaleOrderRequests/"
                                        + "hotel-request-a/status");
                Object restaurantStatus =
                        result.document().get(
                                "/resaleOrderRequests/"
                                        + "restaurant-request-a/status");
                unchangedBusinessState &=
                        (hotelStatus == null
                                || "requested".equals(
                                hotelStatus))
                                && (restaurantStatus == null
                                || "requested".equals(
                                restaurantStatus));
                if (resultTuples.length() > 0) {
                    resultTuples.append("; ");
                }
                resultTuples.append(
                        ExternalBlockerProbeAssertions
                                .resultTuple(result));
            }
        }
        long handlerDelta =
                after.handlersExecuted
                        - before.handlersExecuted;
        long computeDelta =
                after.computeStepsExecuted
                        - before.computeStepsExecuted;
        long batchDelta =
                after.updateBatchPatchApplications
                        - before.updateBatchPatchApplications;
        boolean exactNoSelection =
                exactStatuses
                        && noEvents
                        && unchangedBusinessState
                        && handlerDelta == 0L
                        && computeDelta == 0L
                        && batchDelta == 0L;
        ExternalBlockerProbeAssertions.classify(
                "paynote-reduced-handler-selection",
                "Language PayNote reduced-handler selection defect:",
                exactNoSelection,
                exactStatuses
                        && batchDelta
                        == repairedBatchCount,
                context + ": results=["
                        + resultTuples + "]"
                        + ", handlerDelta="
                        + handlerDelta
                        + ", computeDelta="
                        + computeDelta
                        + ", batchDelta="
                        + batchDelta
                        + ", unchangedBusinessState="
                        + unchangedBusinessState);
    }

    private static Node participantOperation(Fixture fixture,
                                             String timelineId,
                                             int timestamp,
                                             String operation,
                                             Node request) {
        return CoordinationTestResources.operationRequestEvent(fixture.blue,
                fixture.repository,
                timelineId,
                timestamp,
                operation,
                participantChannel(timelineId),
                request);
    }

    private static String participantChannel(String timelineId) {
        if ("hotel-participant".equals(timelineId)) {
            return "hotelParticipantChannel";
        }
        if ("restaurant-participant".equals(timelineId)) {
            return "restaurantParticipantChannel";
        }
        throw new IllegalArgumentException("Unknown participant timeline: " + timelineId);
    }

    private static Node subscriptionUpdate(String subscriptionId,
                                           String targetSessionId,
                                           String requestId,
                                           String orderSessionId) {
        return new Node()
                .type("MyOS/Subscription Update")
                .properties("subscriptionId", new Node().value(subscriptionId))
                .properties("targetSessionId", new Node().value(targetSessionId))
                .properties("update", new Node()
                        .properties("kind", new Node().value("Resale Order Placed"))
                        .properties("inResponseTo", new Node()
                                .properties("requestId", new Node().value(requestId)))
                        .properties("orderSessionId", new Node().value(orderSessionId)));
    }

    private static Node loadYaml(Fixture fixture, String resourcePath) {
        return CoordinationTestResources.yamlResource(fixture.blue, fixture.repository, resourcePath);
    }

    private static void assertContainsType(List<Node> events, String expectedType) {
        for (Node event : events) {
            if (expectedType.equals(typeName(event))) {
                return;
            }
        }
        throw new AssertionError("Expected emitted event type " + expectedType + " in " + events);
    }

    private static String typeName(Node event) {
        if (event == null) {
            return null;
        }
        if (event.getType() != null && event.getType().getValue() instanceof String) {
            return (String) event.getType().getValue();
        }
        Node type = event.getProperties() != null ? event.getProperties().get("type") : null;
        Object value = type != null ? type.getValue() : null;
        return value instanceof String ? (String) value : null;
    }

    private static void printTiming(String label, long startNanos) {
        printTimingOutput("Paynote reduced BEX timing - %s: %.3fms%n",
                label,
                elapsedMs(startNanos));
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0d;
    }

    private static void printSetupTimings() {
        printTimingOutput("Paynote reduced BEX setup timing - setupBlueMs: %.3fms%n", setupBlueMs);
        printTimingOutput("Paynote reduced BEX setup timing - loadYamlMs: %.3fms%n", loadYamlMs);
        printTimingOutput("Paynote reduced BEX setup timing - initializeMs: %.3fms%n", initializeMs);
        printTimingOutput("Paynote reduced BEX setup timing - buildHotelEventMs: %.3fms%n", buildHotelEventMs);
        printTimingOutput("Paynote reduced BEX setup timing - buildRestaurantEventMs: %.3fms%n", buildRestaurantEventMs);
    }

    private static void printMetrics(String label, BexProcessingMetrics.Snapshot snapshot) {
        printMetrics(label,
                snapshot.workflowStepsExecuted,
                snapshot.computeStepsExecuted,
                snapshot.updateDocumentStepsExecuted,
                snapshot.triggerEventStepsExecuted,
                snapshot.directBexChangesetHits,
                snapshot.patchesApplied,
                snapshot.updateBatchPatchApplications,
                snapshot.updateIndividualPatchApplications,
                snapshot.eventsEmitted,
                snapshot.computeProgramNormalizations,
                snapshot.computeDefinitionNormalizations);
        printTimingMetrics(label, snapshot);
    }

    private static void printMetrics(String label,
                                     long workflowStepsExecuted,
                                     long computeStepsExecuted,
                                     long updateDocumentStepsExecuted,
                                     long triggerEventStepsExecuted,
                                     long directBexChangesetHits,
                                     long patchesApplied,
                                     long updateBatchPatchApplications,
                                     long updateIndividualPatchApplications,
                                     long eventsEmitted,
                                     long computeProgramNormalizations,
                                     long computeDefinitionNormalizations) {
        printTimingOutput(
                "Paynote reduced BEX %s - workflowSteps=%d, computeSteps=%d, updateSteps=%d, triggerSteps=%d, " +
                        "directChangesetHits=%d, patchesApplied=%d, " +
                        "batchPatchApplications=%d, individualPatchApplications=%d, eventsEmitted=%d, " +
                        "programNormalizations=%d, definitionNormalizations=%d%n",
                label,
                workflowStepsExecuted,
                computeStepsExecuted,
                updateDocumentStepsExecuted,
                triggerEventStepsExecuted,
                directBexChangesetHits,
                patchesApplied,
                updateBatchPatchApplications,
                updateIndividualPatchApplications,
                eventsEmitted,
                computeProgramNormalizations,
                computeDefinitionNormalizations);
    }

    private static void printMetricsDelta(String label,
                                          BexProcessingMetrics.Snapshot before,
                                          BexProcessingMetrics.Snapshot after) {
        printMetrics(label,
                after.workflowStepsExecuted - before.workflowStepsExecuted,
                after.computeStepsExecuted - before.computeStepsExecuted,
                after.updateDocumentStepsExecuted - before.updateDocumentStepsExecuted,
                after.triggerEventStepsExecuted - before.triggerEventStepsExecuted,
                after.directBexChangesetHits - before.directBexChangesetHits,
                after.patchesApplied - before.patchesApplied,
                after.updateBatchPatchApplications - before.updateBatchPatchApplications,
                after.updateIndividualPatchApplications - before.updateIndividualPatchApplications,
                after.eventsEmitted - before.eventsEmitted,
                after.computeProgramNormalizations - before.computeProgramNormalizations,
                after.computeDefinitionNormalizations - before.computeDefinitionNormalizations);
        printTimingMetricsDelta(label, before, after);
    }

    private static void printTimingMetrics(String label, BexProcessingMetrics.Snapshot snapshot) {
        printTimingOutput(
                "Paynote reduced BEX %s timing metrics - workflowRunnerMs=%.3f, computeStepMs=%.3f, " +
                        "definitionResolveMs=%.3f, contextBuildMs=%.3f, programSourceBuildMs=%.3f, " +
                        "compileExecuteMs=%.3f, bexCompileMs=%.3f, bexExecuteMs=%.3f, " +
                        "updateStepMs=%.3f, directChangesetMs=%.3f, patchConversionMs=%.3f, " +
                        "patchApplyMs=%.3f, triggerStepMs=%.3f, emitEventMs=%.3f, " +
                        "bexNodeWriterMs=%.3f, compileCacheHits=%d, " +
                        "compileCacheMisses=%d, compiledExecutions=%d, definitionResolveHits=%d, " +
                        "definitionResolveMisses=%d, directPatchEntryConversions=%d%n",
                label,
                nanosToMs(snapshot.workflowRunnerNanos),
                nanosToMs(snapshot.computeStepNanos),
                nanosToMs(snapshot.computeDefinitionResolveNanos),
                nanosToMs(snapshot.computeContextBuildNanos),
                nanosToMs(snapshot.computeProgramSourceBuildNanos),
                nanosToMs(snapshot.computeCompileExecuteNanos),
                nanosToMs(snapshot.bexCompileNanos),
                nanosToMs(snapshot.bexExecuteNanos),
                nanosToMs(snapshot.updateStepNanos),
                nanosToMs(snapshot.updateDirectChangesetNanos),
                nanosToMs(snapshot.updatePatchConversionNanos),
                nanosToMs(snapshot.updatePatchApplyNanos),
                nanosToMs(snapshot.triggerStepNanos),
                nanosToMs(snapshot.triggerEmitEventNanos),
                nanosToMs(snapshot.bexNodeWriterNanos),
                snapshot.bexCompileCacheHits,
                snapshot.bexCompileCacheMisses,
                snapshot.bexCompiledExecutions,
                snapshot.computeDefinitionResolveHits,
                snapshot.computeDefinitionResolveMisses,
                snapshot.directBexPatchEntryConversions);
        printOuterProcessingMetrics(label,
                snapshot.blueProcessDocumentNanos,
                snapshot.processDocumentNanos,
                snapshot.eventPreprocessNanos,
                snapshot.resultSnapshotAttachNanos,
                snapshot.blueIdCalculationNanos,
                snapshot.bundleLoadNanos,
                snapshot.bundleLoadCacheKeyBuildNanos,
                snapshot.bundleLoadActualBuildNanos,
                snapshot.bundleLoadReuseNanos,
                snapshot.bundleLoadCacheHits,
                snapshot.bundleLoadCacheMisses,
                snapshot.bundlesBuilt,
                snapshot.bundlesReused,
                snapshot.channelDiscoveryNanos,
                snapshot.channelMatchNanos,
                snapshot.channelEvaluations,
                snapshot.handlerDiscoveryNanos,
                snapshot.handlerMatchNanos,
                snapshot.handlerMatchAttempts,
                snapshot.handlerExecutionNanos,
                snapshot.handlersExecuted,
                snapshot.triggeredEventRoutingNanos,
                snapshot.triggeredEventsRouted,
                snapshot.checkpointUpdateNanos,
                snapshot.snapshotCommitNanos,
                snapshot.postProcessingNanos);
        printPatchBatchMetrics(label,
                snapshot.patchBoundaryNanos,
                snapshot.patchGasNanos,
                snapshot.documentUpdateRoutingNanos,
                snapshot.documentUpdateEventsBuilt,
                snapshot.documentUpdateEventsSkippedNoChannel,
                snapshot.batchPatchPlanningNanos,
                snapshot.batchPatchConformanceNanos,
                snapshot.batchPatchBuildUpdatesNanos,
                snapshot.batchPatchCommitNanos,
                snapshot.documentUpdateBeforeMaterializations,
                snapshot.documentUpdateAfterMaterializations);
    }

    private static void printTimingMetricsDelta(String label,
                                                BexProcessingMetrics.Snapshot before,
                                                BexProcessingMetrics.Snapshot after) {
        printTimingOutput(
                "Paynote reduced BEX %s timing metrics - workflowRunnerMs=%.3f, computeStepMs=%.3f, " +
                        "definitionResolveMs=%.3f, contextBuildMs=%.3f, programSourceBuildMs=%.3f, " +
                        "compileExecuteMs=%.3f, bexCompileMs=%.3f, bexExecuteMs=%.3f, " +
                        "updateStepMs=%.3f, directChangesetMs=%.3f, patchConversionMs=%.3f, " +
                        "patchApplyMs=%.3f, triggerStepMs=%.3f, emitEventMs=%.3f, " +
                        "bexNodeWriterMs=%.3f, compileCacheHits=%d, " +
                        "compileCacheMisses=%d, compiledExecutions=%d, definitionResolveHits=%d, " +
                        "definitionResolveMisses=%d, directPatchEntryConversions=%d%n",
                label,
                nanosToMs(after.workflowRunnerNanos - before.workflowRunnerNanos),
                nanosToMs(after.computeStepNanos - before.computeStepNanos),
                nanosToMs(after.computeDefinitionResolveNanos - before.computeDefinitionResolveNanos),
                nanosToMs(after.computeContextBuildNanos - before.computeContextBuildNanos),
                nanosToMs(after.computeProgramSourceBuildNanos - before.computeProgramSourceBuildNanos),
                nanosToMs(after.computeCompileExecuteNanos - before.computeCompileExecuteNanos),
                nanosToMs(after.bexCompileNanos - before.bexCompileNanos),
                nanosToMs(after.bexExecuteNanos - before.bexExecuteNanos),
                nanosToMs(after.updateStepNanos - before.updateStepNanos),
                nanosToMs(after.updateDirectChangesetNanos - before.updateDirectChangesetNanos),
                nanosToMs(after.updatePatchConversionNanos - before.updatePatchConversionNanos),
                nanosToMs(after.updatePatchApplyNanos - before.updatePatchApplyNanos),
                nanosToMs(after.triggerStepNanos - before.triggerStepNanos),
                nanosToMs(after.triggerEmitEventNanos - before.triggerEmitEventNanos),
                nanosToMs(after.bexNodeWriterNanos - before.bexNodeWriterNanos),
                after.bexCompileCacheHits - before.bexCompileCacheHits,
                after.bexCompileCacheMisses - before.bexCompileCacheMisses,
                after.bexCompiledExecutions - before.bexCompiledExecutions,
                after.computeDefinitionResolveHits - before.computeDefinitionResolveHits,
                after.computeDefinitionResolveMisses - before.computeDefinitionResolveMisses,
                after.directBexPatchEntryConversions - before.directBexPatchEntryConversions);
        printOuterProcessingMetrics(label,
                after.blueProcessDocumentNanos - before.blueProcessDocumentNanos,
                after.processDocumentNanos - before.processDocumentNanos,
                after.eventPreprocessNanos - before.eventPreprocessNanos,
                after.resultSnapshotAttachNanos - before.resultSnapshotAttachNanos,
                after.blueIdCalculationNanos - before.blueIdCalculationNanos,
                after.bundleLoadNanos - before.bundleLoadNanos,
                after.bundleLoadCacheKeyBuildNanos - before.bundleLoadCacheKeyBuildNanos,
                after.bundleLoadActualBuildNanos - before.bundleLoadActualBuildNanos,
                after.bundleLoadReuseNanos - before.bundleLoadReuseNanos,
                after.bundleLoadCacheHits - before.bundleLoadCacheHits,
                after.bundleLoadCacheMisses - before.bundleLoadCacheMisses,
                after.bundlesBuilt - before.bundlesBuilt,
                after.bundlesReused - before.bundlesReused,
                after.channelDiscoveryNanos - before.channelDiscoveryNanos,
                after.channelMatchNanos - before.channelMatchNanos,
                after.channelEvaluations - before.channelEvaluations,
                after.handlerDiscoveryNanos - before.handlerDiscoveryNanos,
                after.handlerMatchNanos - before.handlerMatchNanos,
                after.handlerMatchAttempts - before.handlerMatchAttempts,
                after.handlerExecutionNanos - before.handlerExecutionNanos,
                after.handlersExecuted - before.handlersExecuted,
                after.triggeredEventRoutingNanos - before.triggeredEventRoutingNanos,
                after.triggeredEventsRouted - before.triggeredEventsRouted,
                after.checkpointUpdateNanos - before.checkpointUpdateNanos,
                after.snapshotCommitNanos - before.snapshotCommitNanos,
                after.postProcessingNanos - before.postProcessingNanos);
        printPatchBatchMetrics(label,
                after.patchBoundaryNanos - before.patchBoundaryNanos,
                after.patchGasNanos - before.patchGasNanos,
                after.documentUpdateRoutingNanos - before.documentUpdateRoutingNanos,
                after.documentUpdateEventsBuilt - before.documentUpdateEventsBuilt,
                after.documentUpdateEventsSkippedNoChannel - before.documentUpdateEventsSkippedNoChannel,
                after.batchPatchPlanningNanos - before.batchPatchPlanningNanos,
                after.batchPatchConformanceNanos - before.batchPatchConformanceNanos,
                after.batchPatchBuildUpdatesNanos - before.batchPatchBuildUpdatesNanos,
                after.batchPatchCommitNanos - before.batchPatchCommitNanos,
                after.documentUpdateBeforeMaterializations - before.documentUpdateBeforeMaterializations,
                after.documentUpdateAfterMaterializations - before.documentUpdateAfterMaterializations);
    }

    private static void printOuterProcessingMetrics(String label,
                                                    long blueProcessDocumentNanos,
                                                    long processDocumentNanos,
                                                    long eventPreprocessNanos,
                                                    long resultSnapshotAttachNanos,
                                                    long blueIdCalculationNanos,
                                                    long bundleLoadNanos,
                                                    long bundleLoadCacheKeyBuildNanos,
                                                    long bundleLoadActualBuildNanos,
                                                    long bundleLoadReuseNanos,
                                                    long bundleLoadCacheHits,
                                                    long bundleLoadCacheMisses,
                                                    long bundlesBuilt,
                                                    long bundlesReused,
                                                    long channelDiscoveryNanos,
                                                    long channelMatchNanos,
                                                    long channelEvaluations,
                                                    long handlerDiscoveryNanos,
                                                    long handlerMatchNanos,
                                                    long handlerMatchAttempts,
                                                    long handlerExecutionNanos,
                                                    long handlersExecuted,
                                                    long triggeredEventRoutingNanos,
                                                    long triggeredEventsRouted,
                                                    long checkpointUpdateNanos,
                                                    long snapshotCommitNanos,
                                                    long postProcessingNanos) {
        long attributed = eventPreprocessNanos
                + bundleLoadNanos
                + channelDiscoveryNanos
                + channelMatchNanos
                + handlerDiscoveryNanos
                + handlerMatchNanos
                + handlerExecutionNanos
                + checkpointUpdateNanos
                + postProcessingNanos;
        long processorUnattributed = Math.max(0L, processDocumentNanos - attributed);
        long blueUnattributed = Math.max(0L,
                blueProcessDocumentNanos - processDocumentNanos - resultSnapshotAttachNanos);
        printTimingOutput(
                "Paynote reduced BEX %s outer processing metrics - blueProcessDocumentMs=%.3f, " +
                        "processorProcessDocumentMs=%.3f, resultSnapshotAttachMs=%.3f, " +
                        "blueIdCalculationMs=%.3f, eventPreprocessMs=%.3f, bundleLoadMs=%.3f, " +
                        "bundleKeyBuildMs=%.3f, bundleActualBuildMs=%.3f, bundleReuseMs=%.3f, " +
                        "bundleCacheHits=%d, bundleCacheMisses=%d, bundlesBuilt=%d, bundlesReused=%d, " +
                        "channelDiscoveryMs=%.3f, " +
                        "channelMatchMs=%.3f, channelEvaluations=%d, handlerDiscoveryMs=%.3f, " +
                        "handlerMatchMs=%.3f, handlerMatchAttempts=%d, handlerExecutionMs=%.3f, " +
                        "handlersExecuted=%d, triggeredEventRoutingMs=%.3f, triggeredEventsRouted=%d, " +
                        "checkpointUpdateMs=%.3f, snapshotCommitMs=%.3f, postProcessingMs=%.3f, " +
                        "processorUnattributedMs=%.3f, blueUnattributedMs=%.3f%n",
                label,
                nanosToMs(blueProcessDocumentNanos),
                nanosToMs(processDocumentNanos),
                nanosToMs(resultSnapshotAttachNanos),
                nanosToMs(blueIdCalculationNanos),
                nanosToMs(eventPreprocessNanos),
                nanosToMs(bundleLoadNanos),
                nanosToMs(bundleLoadCacheKeyBuildNanos),
                nanosToMs(bundleLoadActualBuildNanos),
                nanosToMs(bundleLoadReuseNanos),
                bundleLoadCacheHits,
                bundleLoadCacheMisses,
                bundlesBuilt,
                bundlesReused,
                nanosToMs(channelDiscoveryNanos),
                nanosToMs(channelMatchNanos),
                channelEvaluations,
                nanosToMs(handlerDiscoveryNanos),
                nanosToMs(handlerMatchNanos),
                handlerMatchAttempts,
                nanosToMs(handlerExecutionNanos),
                handlersExecuted,
                nanosToMs(triggeredEventRoutingNanos),
                triggeredEventsRouted,
                nanosToMs(checkpointUpdateNanos),
                nanosToMs(snapshotCommitNanos),
                nanosToMs(postProcessingNanos),
                nanosToMs(processorUnattributed),
                nanosToMs(blueUnattributed));
    }

    private static void printPatchBatchMetrics(String label,
                                               long patchBoundaryNanos,
                                               long patchGasNanos,
                                               long documentUpdateRoutingNanos,
                                               long documentUpdateEventsBuilt,
                                               long documentUpdateEventsSkippedNoChannel,
                                               long batchPatchPlanningNanos,
                                               long batchPatchConformanceNanos,
                                               long batchPatchBuildUpdatesNanos,
                                               long batchPatchCommitNanos,
                                               long documentUpdateBeforeMaterializations,
                                               long documentUpdateAfterMaterializations) {
        printTimingOutput(
                "Paynote reduced BEX %s batch patch metrics - patchBoundaryMs=%.3f, patchGasMs=%.3f, " +
                        "documentUpdateRoutingMs=%.3f, documentUpdateEventsBuilt=%d, " +
                        "documentUpdateEventsSkippedNoChannel=%d, batchPlanningMs=%.3f, " +
                        "batchConformanceMs=%.3f, batchBuildUpdatesMs=%.3f, batchCommitMs=%.3f, " +
                        "documentUpdateBeforeMaterializations=%d, documentUpdateAfterMaterializations=%d%n",
                label,
                nanosToMs(patchBoundaryNanos),
                nanosToMs(patchGasNanos),
                nanosToMs(documentUpdateRoutingNanos),
                documentUpdateEventsBuilt,
                documentUpdateEventsSkippedNoChannel,
                nanosToMs(batchPatchPlanningNanos),
                nanosToMs(batchPatchConformanceNanos),
                nanosToMs(batchPatchBuildUpdatesNanos),
                nanosToMs(batchPatchCommitNanos),
                documentUpdateBeforeMaterializations,
                documentUpdateAfterMaterializations);
    }

    private static double nanosToMs(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static void printTimingOutput(
            String format,
            Object... arguments) {
        if (PRINT_TIMINGS) {
            System.out.printf(
                    Locale.ROOT,
                    format,
                    arguments);
        }
    }

    private static Fixture configuredFixture(BexProcessingMetrics metrics) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                .processingMetrics(metrics)
                .build());
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
