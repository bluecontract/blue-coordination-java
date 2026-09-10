package blue.coordination.consumer;

import blue.coordination.api.DocumentId;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ClosureHandle;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DocumentRevision;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ManagedClosure;
import blue.coordination.sdk.ManagedDocumentDraft;
import blue.coordination.sdk.TimelineHandle;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Built-JAR coverage for the developer guide's two principal walkthroughs. */
final class SdkDeveloperGuideTest {
    private static final DocumentId ORDER = DocumentId.of("guide-order-42");
    private static final DocumentId PAYMENT = DocumentId.of(
            "guide-payment-42");
    private static final DocumentId SHIPMENT = DocumentId.of(
            "guide-shipment-42");
    private static final DocumentId ROUTE = DocumentId.of(
            "guide-route-42");
    private static final DocumentId RECEIPT = DocumentId.of(
            "guide-receipt-42-primary");

    private static final String SALES_TIMELINE = "guide/orders/42/sales";
    private static final String BILLING_TIMELINE =
            "guide/orders/42/billing";
    private static final String LOGISTICS_TIMELINE =
            "guide/orders/42/logistics";
    private static final String RECEIPT_TIMELINE =
            "guide/receipts/42/worker";

    @Test
    void completeGuideEvolvesACyclicClosureAcrossSeveralTimelines() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle sales = blue.timelines().register(
                    SALES_TIMELINE, "alice");
            TimelineHandle billing = blue.timelines().register(
                    BILLING_TIMELINE, "bob");
            TimelineHandle logistics = blue.timelines().register(
                    LOGISTICS_TIMELINE, "carol");
            TimelineHandle receiptWorker = blue.timelines().register(
                    RECEIPT_TIMELINE, "dave");

            ClosureHandle closure = admitInitialClosure(blue);
            DocumentHandle order = closure.document("order");
            DocumentHandle payment = closure.document("payment");
            DocumentHandle shipment = closure.document("shipment");
            DocumentHandle route = closure.document("route");

            // when
            EntryResult confirmed = blue.operations().on(order)
                    .from(sales)
                    .call("confirm")
                    .through("salesChannel")
                    .requestYaml("{}")
                    .execute();
            requireApplied(confirmed);

            EntryResult completed = blue.operations().on(order)
                    .from(sales)
                    .call("complete")
                    .through("salesChannel")
                    .requestYaml("{}")
                    .execute();
            requireApplied(completed);

            EntryResult captured = blue.operations().on(payment)
                    .from(billing)
                    .call("capture")
                    .through("billingChannel")
                    .requestYaml("amount: 12500")
                    .execute();
            requireApplied(captured);

            EntryResult dispatched = blue.operations().on(shipment)
                    .from(logistics)
                    .call("dispatch")
                    .through("logisticsChannel")
                    .requestYaml("carrier: DHL")
                    .execute();
            requireApplied(dispatched);

            ManagedDocumentDraft receiptDraft = blue.documents().draft(
                    RECEIPT,
                    blue.values().yaml(guideYaml("receipt.yaml")));
            EntryResult created = blue.operations().on(payment)
                    .from(billing)
                    .call("createReceipt")
                    .through("billingChannel")
                    .request(request -> request.managed(
                            "receipt", receiptDraft))
                    .expectOccurrence("/receipts/primary", receiptDraft)
                    .activation(ActivationPolicy.fromNow())
                    .execute();
            requireApplied(created);

            DocumentHandle receipt = blue.documents().require(RECEIPT);
            String receiptBeforePromotion = receipt.snapshot().blueId();
            assertEquals(receiptBeforePromotion,
                    payment.snapshot().valueAt(
                            "/receipts/primary").blueId());
            long receiptEpochBeforePromotion = receipt.snapshot().epoch();
            int receiptHistoryBeforePromotion = receipt.history().size();
            int entriesBeforePromotion = blue.advanced()
                    .auditTimelineEntries().size();
            DocumentHandle promotedReceipt = blue.documents()
                    .promotePublicRoot(RECEIPT);
            assertEquals(receiptBeforePromotion,
                    promotedReceipt.snapshot().blueId());
            assertEquals(receiptEpochBeforePromotion,
                    promotedReceipt.snapshot().epoch());
            assertEquals(receiptHistoryBeforePromotion,
                    promotedReceipt.history().size());
            assertEquals(entriesBeforePromotion,
                    blue.advanced().auditTimelineEntries().size());
            int paymentHistoryBeforeSent = payment.history().size();
            String paymentBeforeSent = payment.snapshot().blueId();
            EntryResult sent = blue.operations().on(promotedReceipt)
                    .from(receiptWorker)
                    .call("markSent")
                    .through("workerChannel")
                    .requestYaml("{}")
                    .execute();
            requireApplied(sent);

            // then
            List<EntryResult> results = List.of(
                    confirmed,
                    completed,
                    captured,
                    dispatched,
                    created,
                    sent);
            results.forEach(result -> {
                assertEquals(EntryDisposition.APPLIED,
                        result.disposition());
                assertFalse(result.diagnostic().present());
                assertTrue(result.stats().gas() > 0L);
            });
            assertEquals("complete", order.snapshot().textAt("/status"));
            assertEquals("captured",
                    payment.snapshot().textAt("/status"));
            assertEquals(12500L, payment.snapshot().longAt("/amount"));
            assertEquals("dispatched",
                    shipment.snapshot().textAt("/status"));
            assertEquals("DHL", shipment.snapshot().textAt("/carrier"));
            assertTrue(receipt.snapshot().booleanAt("/sent"));
            assertTrue(order.snapshot().ready());
            assertTrue(payment.snapshot().ready());
            assertTrue(shipment.snapshot().ready());
            assertTrue(route.snapshot().ready());
            assertTrue(receipt.snapshot().ready());
            assertEquals(receipt.snapshot().blueId(),
                    payment.snapshot().valueAt(
                            "/receipts/primary").blueId());
            assertFalse(receiptBeforePromotion.equals(receipt.snapshot().blueId()));
            assertFalse(paymentBeforeSent.equals(payment.snapshot().blueId()));
            assertEquals(paymentHistoryBeforeSent + 1, payment.history().size());
            assertFalse(order.exact().cyclicMember());
            assertFalse(payment.exact().cyclicMember());
            assertTrue(shipment.exact().cyclicMember());
            assertTrue(route.exact().cyclicMember());
            assertEquals(DocumentRevision.Kind.INITIALIZATION,
                    receipt.history().get(0).kind());
            assertTrue(receipt.history().stream().anyMatch(revision ->
                    revision.kind() == DocumentRevision.Kind.TIMELINE_ENTRY));
        }
    }

    @Test
    void completeProviderEntryProcessesTheInitiallyKnownCyclicClosure() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            TimelineHandle sales = blue.timelines().register(
                    SALES_TIMELINE, "alice");
            blue.timelines().register(BILLING_TIMELINE, "bob");
            blue.timelines().register(LOGISTICS_TIMELINE, "carol");
            blue.timelines().register(RECEIPT_TIMELINE, "dave");
            ClosureHandle closure = admitInitialClosure(blue);
            DocumentHandle order = closure.document("order");
            DocumentHandle shipment = closure.document("shipment");
            DocumentHandle route = closure.document("route");
            var exactEntry = blue.values().yaml(
                    guideYaml("provider-confirm-entry.yaml"));

            // when
            EntryResult result = blue.events()
                    .from(sales)
                    .exact(exactEntry)
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertFalse(result.diagnostic().present());
            assertEquals(exactEntry.blueId(), result.entry().blueId());
            assertEquals("confirmed",
                    order.snapshot().textAt("/status"));
            assertEquals(
                    exactEntry.blueId(),
                    order.history().stream()
                            .filter(revision -> revision.kind()
                                    == DocumentRevision.Kind.TIMELINE_ENTRY)
                            .findFirst()
                            .orElseThrow()
                            .sourceEntry()
                            .orElseThrow()
                            .blueId());
            assertTrue(shipment.exact().cyclicMember());
            assertTrue(route.exact().cyclicMember());
        }
    }

    private static ClosureHandle admitInitialClosure(BlueCoordination blue) {
        return blue.documents().admit(
                ManagedClosure.builder()
                        .document("order", ORDER, guideYaml("order.yaml"))
                        .document(
                                "payment",
                                PAYMENT,
                                guideYaml("payment.yaml"))
                        .document(
                                "shipment",
                                SHIPMENT,
                                guideYaml("shipment.yaml"))
                        .document("route", ROUTE, guideYaml("route.yaml"))
                        .bindOccurrence(
                                "order", "/payment", "payment")
                        .bindOccurrence(
                                "order", "/shipment", "shipment")
                        .bindOccurrence(
                                "shipment", "/route", "route")
                        .bindOccurrence(
                                "route", "/shipment", "shipment")
                        .publicRoot("order")
                        .fromNow()
                        .build());
    }

    private static void requireApplied(EntryResult result) {
        if (!result.applied()) {
            throw new IllegalStateException(
                    result.disposition() + ": " + result.diagnostic());
        }
    }

    private static String guideYaml(String name) {
        String resource = "developer-guide/" + name;
        try (var input = SdkDeveloperGuideTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing developer-guide resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Cannot read developer-guide resource " + resource,
                    failure);
        }
    }
}
