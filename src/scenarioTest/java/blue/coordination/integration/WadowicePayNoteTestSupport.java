package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.TestReporter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Complete Wadowice acceptance campaign supported by the exact Round 10
 * fixture, including the original explicit late-cancellation refusal branch.
 */
final class WadowicePayNoteTestSupport {
    private static final String PAY_NOTE = "large-paynote";
    private static final String HOST = "large-order-host";
    private static final String HOTEL_PRODUCT =
            "large-paynote-hotel-condition-product";
    static final String RESTAURANT_PRODUCT =
            "large-paynote-restaurant-condition-product";
    private static final long T0 = 1_720_100_000_000_000L;
    static final List<String> LATE_CANCELLATION_BUSINESS_PATHS =
            List.of(
                    "/status",
                    "/authorization",
                    "/amount",
                    "/capture",
                    "/refund",
                    "/productConditions/hotel/confirmed",
                    "/productConditions/hotel/done",
                    "/productConditions/hotel/status",
                    "/productConditions/restaurant/confirmed",
                    "/productConditions/restaurant/done",
                    "/productConditions/restaurant/cancelled",
                    "/productConditions/restaurant/discountApplied",
                    "/productConditions/restaurant/status",
                    "/productConditions/restaurant/product/confirmed",
                    "/productConditions/restaurant/product/confirmedAt",
                    "/productConditions/restaurant/product/done",
                    "/productConditions/restaurant/product/cancelled",
                    "/productConditions/restaurant/product/discountApplied");

    static void assertCompleted(Campaign campaign) {
        assertTrue(campaign.bool("/productConditions/hotel/done"));
        assertTrue(campaign.bool("/productConditions/restaurant/done"));
        assertFalse(campaign.bool("/productConditions/restaurant/cancelled"));
        assertFalse(campaign.bool(
                "/productConditions/restaurant/discountApplied"));
        assertEquals(130_000L, campaign.number("/amount/captured"));
        assertEquals("Completed", campaign.string("/status"));
        assertEquals(2L, campaign.eventCount(
                "PayNote/Condition Product Done"),
                "hotel and restaurant must each contribute one done event");
        campaign.assertParentCurrent();
    }

    static void assertRefund(
            Campaign campaign,
            String requestId,
            long refundAmount,
            long capturedAmount) {
        assertTrue(campaign.bool("/refund/requested"));
        assertTrue(campaign.bool("/refund/completed"));
        assertEquals(requestId, campaign.string("/refund/requestId"));
        assertEquals(refundAmount, campaign.number("/refund/amountMinor"));
        assertEquals(capturedAmount, campaign.number("/amount/captured"));
        assertEquals("Partial Refund Completed", campaign.string("/status"));
    }

    static void assertBusinessParity(
            Campaign standalone,
            Campaign embedded) {
        List<String> businessPaths = List.of(
                "/name",
                "/status",
                "/attachedBy",
                "/validationMethod",
                "/payer",
                "/payee",
                "/guarantor",
                "/currency",
                "/authorizationAuthorizedAmountMinorState",
                "/authorizationCountState",
                "/hotelConditionAttachedState",
                "/restaurantConditionAttachedState",
                "/hotelConfirmedState",
                "/restaurantConfirmedState",
                "/captureReadinessConfirmedState",
                "/captureRequestedState",
                "/captureRequestedAtState",
                "/captureCompletedState",
                "/capturedAmountMinorState",
                "/refundRequestedState",
                "/refundCompletedState",
                "/refundRequestIdState",
                "/refundAmountMinorState",
                "/refundReasonState",
                "/amount",
                "/authorization",
                "/attachedConditions",
                "/captureReadiness",
                "/capture",
                "/refund",
                "/productConditions/hotel/sourceProductPath",
                "/productConditions/hotel/expectedProductKey",
                "/productConditions/hotel/expectedProductName",
                "/productConditions/hotel/expectedProductIdentity",
                "/productConditions/hotel/sourceOrderId",
                "/productConditions/hotel/status",
                "/productConditions/hotel/deliveryStatus",
                "/productConditions/hotel/confirmed",
                "/productConditions/hotel/done",
                "/productConditions/hotel/captureConditionSatisfied",
                "/productConditions/hotel/lastProcessedSourceTimestamp",
                "/productConditions/hotel/product/documentId",
                "/productConditions/hotel/product/name",
                "/productConditions/hotel/product/productKey",
                "/productConditions/hotel/product/sourceOrderId",
                "/productConditions/hotel/product/confirmed",
                "/productConditions/hotel/product/confirmedAt",
                "/productConditions/hotel/product/done",
                "/productConditions/restaurant/sourceProductPath",
                "/productConditions/restaurant/expectedProductKey",
                "/productConditions/restaurant/expectedProductName",
                "/productConditions/restaurant/expectedProductIdentity",
                "/productConditions/restaurant/sourceOrderId",
                "/productConditions/restaurant/status",
                "/productConditions/restaurant/deliveryStatus",
                "/productConditions/restaurant/confirmed",
                "/productConditions/restaurant/done",
                "/productConditions/restaurant/cancelled",
                "/productConditions/restaurant/discountApplied",
                "/productConditions/restaurant/captureConditionSatisfied",
                "/productConditions/restaurant/lastProcessedSourceTimestamp",
                "/productConditions/restaurant/product/documentId",
                "/productConditions/restaurant/product/name",
                "/productConditions/restaurant/product/productKey",
                "/productConditions/restaurant/product/sourceOrderId",
                "/productConditions/restaurant/product/confirmed",
                "/productConditions/restaurant/product/confirmedAt",
                "/productConditions/restaurant/product/done",
                "/productConditions/restaurant/product/cancelled",
                "/productConditions/restaurant/product/discountApplied");
        businessPaths.forEach(path -> assertEquals(
                standalone.engine.session(PAY_NOTE).current()
                        .canonicalBlueIdAt(path),
                embedded.engine.session(PAY_NOTE).current()
                        .canonicalBlueIdAt(path),
                path));
        List<String> businessEvents = List.of(
                "PayNote/Amount Authorized",
                "PayNote/Condition Product Confirmed",
                "PayNote/Capture Funds Requested",
                "PayNote/Payment Completed",
                "PayNote/Condition Product Done");
        businessEvents.forEach(kind -> assertEquals(
                standalone.eventCount(kind),
                embedded.eventCount(kind),
                kind));
        assertNotEquals(
                standalone.initializationCause(),
                embedded.initializationCause(),
                "top-level admission and host attachment retain distinct "
                        + "temporal provenance");
        embedded.assertParentCurrent();
    }

    static final class Campaign implements AutoCloseable {
        final TestEngine engine;
        private final Timelines timelines;
        private final boolean embedded;
        private final EngineMetrics.MetricsSnapshot baseline;
        private final List<StepTiming> timings = new ArrayList<>();

        private Campaign(
                TestEngine engine,
                Timelines timelines,
                boolean embedded,
                EngineMetrics.MetricsSnapshot baseline) {
            this.engine = engine;
            this.timelines = timelines;
            this.embedded = embedded;
            this.baseline = baseline;
        }

        static Campaign standalone() throws Exception {
            TestEngine engine = TestEngine.create();
            try {
                Timelines timelines = register(engine);
                engine.start(PAY_NOTE, resource(
                        "examples/clean/large-paynote.yaml"));
                return new Campaign(engine, timelines, false,
                        engine.metricsSnapshot());
            } catch (Exception | Error failure) {
                engine.close();
                throw failure;
            }
        }

        static Campaign embedded() throws Exception {
            TestEngine engine = TestEngine.create();
            try {
                Timelines timelines = register(engine);
                engine.start(HOST, resource(
                        "examples/clean/large-order-host.yaml"));
                Campaign campaign = new Campaign(
                        engine, timelines, true, engine.metricsSnapshot());
                campaign.invoke(
                        "attach PayNote",
                        timelines.hostOwner(),
                        Operation.exact(
                                "attachPayNote",
                                "ownerChannel",
                                engine.embeddedDocumentRequest(resource(
                                        "examples/clean/large-paynote.yaml"))),
                        T0 + 1_000L);
                assertEquals(PAY_NOTE, engine.embeddedDocuments(HOST)
                        .get("/payNote"));
                campaign.assertParentCurrent();
                return campaign;
            } catch (Exception | Error failure) {
                engine.close();
                throw failure;
            }
        }

        void prepareCapturedPayment() {
            invoke("authorize 50000", timelines.guarantor(),
                    authorization("WAD-AUTH-50000", 50_000L),
                    T0 + 10_000L);
            invoke("authorize 80000", timelines.guarantor(),
                    authorization("WAD-AUTH-80000", 80_000L),
                    T0 + 20_000L);
            invoke("attach hotel condition", timelines.merchant(),
                    Operation.yaml(
                            "attachHotelCondition",
                            "merchantChannel",
                            """
                                    productKey: hotel
                                    sourceProductPath: /product/products/hotel
                                    expectedProductName: Hotel Mlyn Jacka Stay
                                    expectedProductIdentity: "wadowice-order-2026-v1:hotel:v1"
                                    sourceOrderId: wadowice-order-2026-v1
                                    """),
                    T0 + 30_000L);
            invoke("attach restaurant condition", timelines.merchant(),
                    Operation.yaml(
                            "attachRestaurantCondition",
                            "merchantChannel",
                            """
                                    productKey: restaurant
                                    sourceProductPath: /product/products/restaurant
                                    expectedProductName: Old Town Restaurant Dinner
                                    expectedProductIdentity: "wadowice-order-2026-v1:restaurant:v1"
                                    sourceOrderId: wadowice-order-2026-v1
                                    """),
                    T0 + 40_000L);
            invoke("restaurant confirmation", timelines.restaurant(),
                    Operation.yaml(
                            "confirmProduct",
                            "providerChannel",
                            "confirmationReference: REST-WAD-1930"),
                    T0 + 50_000L);
            assertEquals(1L, number("/captureReadiness/confirmed"),
                    "restaurant root flag="
                            + bool("/restaurantConfirmedState")
                            + ", condition="
                            + bool("/productConditions/restaurant/confirmed")
                            + ", product=" + bool(
                            "/productConditions/restaurant/product/confirmed"));
            assertFalse(bool("/capture/requested"));

            invoke("hotel confirmation", timelines.hotel(),
                    Operation.yaml(
                            "confirmProduct",
                            "providerChannel",
                            "confirmationReference: HOTEL-WAD-2207"),
                    T0 + 60_000L);
            assertEquals(2L, number("/captureReadiness/confirmed"));
            assertTrue(bool("/capture/requested"));
            assertEquals(1L, number("/capture/requestCount"));
            assertEquals(1L, eventCount("PayNote/Capture Funds Requested"));

            invoke("duplicate restaurant confirmation", timelines.restaurant(),
                    Operation.yaml(
                            "confirmProduct",
                            "providerChannel",
                            "confirmationReference: REST-WAD-1930"),
                    T0 + 70_000L);
            invoke("duplicate hotel confirmation", timelines.hotel(),
                    Operation.yaml(
                            "confirmProduct",
                            "providerChannel",
                            "confirmationReference: HOTEL-WAD-2207"),
                    T0 + 80_000L);
            assertEquals(1L, number("/capture/requestCount"));
            assertEquals(1L, eventCount("PayNote/Capture Funds Requested"));

            Operation capture = Operation.yaml(
                    "capturePayment",
                    "guarantorChannel",
                    """
                            requestId: package-capture-001
                            amountMinor: 130000
                            currency: PLN
                            """);
            invoke("capture completion", timelines.guarantor(), capture,
                    T0 + 90_000L);
            invoke("duplicate capture completion", timelines.guarantor(),
                    capture, T0 + 100_000L);
            invoke("hotel completion", timelines.hotel(), Operation.yaml(
                    "completeProduct",
                    "providerChannel",
                    """
                            confirmationCode: WAD-7429
                            note: Stay completed with customer present.
                            """), T0 + 110_000L);

            assertEquals(130_000L, number(
                    "/authorization/authorizedAmountMinor"));
            assertEquals(2L, number("/authorization/authorizationCount"));
            assertEquals("Authorized", string("/authorization/state"));
            assertTrue(bool("/attachedConditions/hotel"));
            assertTrue(bool("/attachedConditions/restaurant"));
            assertTrue(bool("/productConditions/hotel/product/confirmed"));
            assertTrue(bool(
                    "/productConditions/restaurant/product/confirmed"));
            assertTrue(bool("/productConditions/hotel/confirmed"));
            assertTrue(bool("/productConditions/restaurant/confirmed"));
            assertTrue(bool("/capture/completed"));
            assertEquals(130_000L, number("/amount/captured"));
            assertEquals(2L, eventCount("PayNote/Amount Authorized"));
            assertEquals(1L, eventCount("PayNote/Capture Funds Requested"));
            assertEquals(1L, eventCount("PayNote/Payment Completed"));
            assertParentCurrent();
        }

        void completeRestaurant() {
            invoke("restaurant completion", timelines.restaurant(),
                    Operation.yaml(
                            "completeProduct",
                            "providerChannel",
                            """
                                    confirmationCode: WAD-7429
                                    note: Dinner completed as booked.
                                    """),
                    T0 + 120_000L);
        }

        void cancelRestaurant() {
            invoke("restaurant cancellation", timelines.customer(),
                    Operation.yaml(
                            "cancelWithinRange",
                            "customerChannel",
                            "reason: Plans changed inside refund window."),
                    T0 + 120_000L);
            Operation refund = refund("restaurant-refund-001", 38_000L);
            invoke("restaurant refund", timelines.guarantor(), refund,
                    T0 + 130_000L);
            invoke("duplicate restaurant refund", timelines.guarantor(),
                    refund, T0 + 140_000L);
        }

        void discountRestaurant() {
            invoke("restaurant 10% adjustment", timelines.restaurant(),
                    Operation.yaml(
                            "completeWithDiscount",
                            "providerChannel",
                            """
                                    confirmationCode: WAD-7429
                                    note: Dinner completed with a service recovery discount.
                                    """),
                    T0 + 120_000L);
            Operation refund = refund("restaurant-discount-001", 3_800L);
            invoke("restaurant adjustment refund", timelines.guarantor(),
                    refund, T0 + 130_000L);
            invoke("duplicate adjustment refund", timelines.guarantor(),
                    refund, T0 + 140_000L);
        }

        void refuseLateCancellation() {
            invoke("late restaurant cancellation refusal",
                    timelines.customer(),
                    Operation.yaml(
                            "cancelOutsideRange",
                            "customerChannel",
                            "reason: Cancellation requested outside window."),
                    T0 + 120_000L);
        }

        long number(String path) {
            return integer(engine, PAY_NOTE, path);
        }

        String string(String path) {
            return text(engine, PAY_NOTE, path);
        }

        boolean bool(String path) {
            Object value = engine.value(PAY_NOTE, path).getValue();
            if (!(value instanceof Boolean result)) {
                throw new AssertionError(
                        "Expected Boolean at " + PAY_NOTE + path
                                + " but got " + value);
            }
            return result;
        }

        long eventCount(String kind) {
            return List.of(PAY_NOTE, HOTEL_PRODUCT, RESTAURANT_PRODUCT)
                    .stream()
                    .flatMap(documentId -> engine.history(documentId).stream())
                    .map(DocumentRevision::emittedEvents)
                    .flatMap(List::stream)
                    .filter(event -> kind.equals(kind(event)))
                    .count();
        }

        String initializationCause() {
            return engine.history(PAY_NOTE).get(0)
                    .causalEntryBlueId().orElseThrow();
        }

        List<String> businessIdentity(List<String> paths) {
            return paths.stream()
                    .map(path -> engine.session(PAY_NOTE).current()
                            .canonicalBlueIdAt(path))
                    .toList();
        }

        void assertParentCurrent() {
            if (!embedded) {
                return;
            }
            String childBlueId = engine.session(PAY_NOTE)
                    .layout().rootBlueId();
            assertEquals(childBlueId, engine.session(HOST).current()
                    .canonicalBlueIdAt("/payNote"));
            assertEquals(string("/status"),
                    text(engine, HOST, "/observedPayNoteStatus"));
            assertTrue(integer(engine, HOST, "/payNoteRevisionCount") > 0L);
        }

        void verifyAccounting() {
            EngineTestSupport.MetricDelta work = delta(
                    baseline, engine.metricsSnapshot());
            assertNoGenericSplitting(work);
            assertTrue(work.counter(
                    "process.frozenContractsInvocations") > 0L);
            assertTrue(work.nanos("process.frozen") > 0L);
            assertTrue(work.nanos("process.hostBeforeFrozen") > 0L);
            assertTrue(work.nanos("process.hostAfterFrozen") > 0L);
            assertFalse(timings.isEmpty());
            timings.forEach(StepTiming::verify);
        }

        void publishTimings(TestReporter reporter, String campaignName) {
            reporter.publishEntry(campaignName, StepTiming.report(timings));
        }

        private void invoke(
                String label,
                Timeline timeline,
                Operation operation,
                long timestampMicros) {
            long appendStarted = System.nanoTime();
            TimelineEntry entry = engine.appendAt(
                    timeline, operation, timestampMicros);
            long appendNanos = System.nanoTime() - appendStarted;
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            long wallStarted = System.nanoTime();
            ProcessingDrainReceipt receipt = engine.dispatch(entry);
            long wallNanos = System.nanoTime() - wallStarted;
            EngineTestSupport.MetricDelta processing = delta(
                    before, engine.metricsSnapshot());
            timings.add(new StepTiming(
                    label,
                    appendNanos,
                    receipt.elapsedNanos(),
                    wallNanos,
                    processing.nanos("process.frozen"),
                    processing.nanos("process.hostBeforeFrozen"),
                    processing.nanos("process.hostAfterFrozen")));
        }

        @Override
        public void close() {
            engine.close();
        }

        private static Timelines register(TestEngine engine) {
            return new Timelines(
                    engine.timeline("examples/large-order/alice", "alice"),
                    engine.timeline("examples/order/alice", "alice"),
                    engine.timeline("examples/order/bob", "bob"),
                    engine.timeline("examples/order/celine", "celine"),
                    engine.timeline("examples/order/david", "david"),
                    engine.timeline(
                            "examples/order/myos-admin", "myos-admin"));
        }
    }

    private static Operation authorization(String id, long amountMinor) {
        return Operation.yaml(
                "authorizeAmount",
                "guarantorChannel",
                "authorizationId: " + id + "\n"
                        + "amountMinor: " + amountMinor + "\n"
                        + "currency: PLN\n");
    }

    private static Operation refund(String id, long amountMinor) {
        return Operation.yaml(
                "refundPayment",
                "guarantorChannel",
                "requestId: " + id + "\n"
                        + "amountMinor: " + amountMinor + "\n"
                        + "currency: PLN\n");
    }

    private static String kind(Node event) {
        Node kind = event.getProperties().get("kind");
        return kind == null ? null : String.valueOf(kind.getValue());
    }

    private record Timelines(
            Timeline hostOwner,
            Timeline customer,
            Timeline merchant,
            Timeline hotel,
            Timeline restaurant,
            Timeline guarantor) {
    }

    private record StepTiming(
            String label,
            long appendNanos,
            long receiptNanos,
            long wallNanos,
            long frozenNanos,
            long hostBeforeNanos,
            long hostAfterNanos) {

        void verify() {
            assertTrue(appendNanos > 0L, label);
            assertTrue(receiptNanos > 0L, label);
            assertTrue(wallNanos >= receiptNanos, label);
            assertTrue(frozenNanos > 0L, label);
            assertTrue(hostBeforeNanos > 0L, label);
            assertTrue(hostAfterNanos > 0L, label);
            assertTrue(receiptNanos >= accountedNanos(), label);
        }

        long accountedNanos() {
            return frozenNanos + hostBeforeNanos + hostAfterNanos;
        }

        long schedulerNanos() {
            return Math.max(0L, receiptNanos - accountedNanos());
        }

        static String report(List<StepTiming> values) {
            StringBuilder result = new StringBuilder(512);
            result.append("step | append ms | drain ms | frozen ms | ")
                    .append("host ms | scheduler/unattributed ms\n");
            for (StepTiming value : values) {
                result.append(value.label()).append(" | ")
                        .append(ms(value.appendNanos())).append(" | ")
                        .append(ms(value.receiptNanos())).append(" | ")
                        .append(ms(value.frozenNanos())).append(" | ")
                        .append(ms(value.hostBeforeNanos()
                                + value.hostAfterNanos())).append(" | ")
                        .append(ms(value.schedulerNanos())).append('\n');
            }
            return result.toString();
        }

        private static String ms(long nanos) {
            return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
        }
    }
}
