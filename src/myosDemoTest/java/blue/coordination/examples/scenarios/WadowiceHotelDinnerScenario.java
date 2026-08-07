package blue.coordination.examples.scenarios;

import blue.coordination.examples.documents.OrderDocuments;
import blue.coordination.examples.support.MyOsDemoActor;
import blue.coordination.examples.support.MyOsDemoCheckpoint;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoEntry;
import blue.coordination.examples.support.MyOsDemoOperation;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsDemoRuntime;
import blue.coordination.examples.support.MyOsDemoTimeline;
import blue.coordination.examples.support.MyOsDemoYaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Executable Wadowice Hotel-and-Dinner order with conditional PayNote capture.
 *
 * <p>The same immutable guarantor entry is applied independently to the
 * standalone PayNote and the copy embedded in the living Order. Provider
 * entries later target both the service Product and its PayNote condition in
 * one Root transition.</p>
 */
public final class WadowiceHotelDinnerScenario implements AutoCloseable {

    public static final String PAYNOTE = "package-paynote";
    public static final String ORDER = "package-order";

    private final MyOsDemoRuntime demo;
    private final MyOsDemoTimeline customer;
    private final MyOsDemoTimeline merchant;
    private final MyOsDemoTimeline hotel;
    private final MyOsDemoTimeline restaurant;
    private final MyOsDemoTimeline guarantor;

    private WadowiceHotelDinnerScenario(String caseId) {
        this(MyOsDemoRuntime.create(
                "wadowice-hotel-dinner", caseId), true);
    }

    private WadowiceHotelDinnerScenario(
            String caseId,
            MyOsDemoCheckpoint checkpoint) {
        this(MyOsDemoRuntime.fork(
                "wadowice-hotel-dinner", caseId, checkpoint), false);
    }

    private WadowiceHotelDinnerScenario(
            MyOsDemoRuntime runtime,
            boolean addDocuments) {
        demo = runtime;
        if (addDocuments) {
            demo.addDocument(PAYNOTE, OrderDocuments.PACKAGE_PAYNOTE);
            demo.addDocument(ORDER, OrderDocuments.PACKAGE_ORDER);
        }
        customer = demo.timeline(
                "examples/order/alice",
                MyOsDemoActor.principal("alice"));
        merchant = demo.timeline(
                "examples/order/bob",
                MyOsDemoActor.principal("bob"));
        hotel = demo.timeline(
                "examples/order/celine",
                MyOsDemoActor.principal("celine"));
        restaurant = demo.timeline(
                "examples/order/david",
                MyOsDemoActor.principal("david"));
        guarantor = demo.timeline(
                "examples/order/myos-admin",
                MyOsDemoActor.admin());
        if (addDocuments) {
            // Warm the recurring entry shape but leave this exact PayNote
            // event unseen for the release-defining first-seen measurement.
            customer.primeTemplate(attachPayNoteOperation());
        }
    }

    public static WadowiceHotelDinnerScenario create() {
        return new WadowiceHotelDinnerScenario("wadowice-order");
    }

    public static WadowiceHotelDinnerScenario create(String caseId) {
        return new WadowiceHotelDinnerScenario(caseId);
    }

    public static WadowiceHotelDinnerScenario fork(
            MyOsDemoCheckpoint checkpoint,
            String caseId) {
        return new WadowiceHotelDinnerScenario(
                caseId,
                java.util.Objects.requireNonNull(
                        checkpoint, "checkpoint"));
    }

    public MyOsDemoRuntime demo() {
        return demo;
    }

    public MyOsDemoResult attachPayNote() {
        MyOsDemoOperation operation = attachPayNoteOperation();
        return processChecked(
                operation,
                demo.append(customer, operation)).require(ORDER);
    }

    /** Appends the full PayNote entry while keeping PROCESS outside the span. */
    public MyOsDemoEntry appendPayNoteEntry() {
        return demo.append(customer, attachPayNoteOperation());
    }

    /** Explicit secondary-path prime; it never runs implicitly for the gate. */
    public void primePayNoteAppend() {
        customer.prime(attachPayNoteOperation());
    }

    /** Verifies every state boundary included by the PayNote latency span. */
    public void requirePayNoteAttachmentObservable(
            MyOsDemoDispatch dispatch) {
        Set<String> expectedRoots = Set.of(ORDER, PAYNOTE);
        if (!dispatch.documentKeys().equals(expectedRoots)) {
            throw new IllegalStateException(
                    "PayNote fan-out mismatch: " + dispatch.documentKeys());
        }
        if (demo.journalEntryCount() != 1
                || demo.storedEventInventoryCount() != 1
                || demo.canonicalStoredEventCount() != 1
                || demo.authoredEntries().size() != 1) {
            throw new IllegalStateException(
                    "PayNote append is not fully observable in the journal "
                            + "and event stores");
        }
        if (!demo.documentsForTimeline(customer).containsAll(expectedRoots)) {
            throw new IllegalStateException(
                    "PayNote route index is not observable for both Roots");
        }
        for (String documentKey : expectedRoots) {
            MyOsDemoResult result = dispatch.require(documentKey);
            if (!result.delivery().commitOutcome().committed()
                    || !result.delivery().transition().afterRootBlueId()
                    .equals(demo.currentRootBlueId(documentKey))
                    || result.delivery().transition().afterEpoch()
                    != demo.currentEpoch(documentKey)
                    || demo.committedJournalHighWater(
                    documentKey, customer) != 1L) {
                throw new IllegalStateException(
                        "PayNote Root is not fully observable: "
                                + documentKey);
            }
        }
    }

    private MyOsDemoOperation attachPayNoteOperation() {
        String request = """
                document:
                %s
                documentRef:
                  blueId: %s
                """.formatted(
                MyOsDemoYaml.indent(
                        demo.document(PAYNOTE).authoredYaml().stripTrailing(),
                        2),
                demo.document(PAYNOTE).initialBlueId());
        return MyOsDemoOperation.operation("attachPayNoteAsCustomer")
                        .through("customerChannel")
                        .request(request)
                        .build();
    }

    /** Applies one exact authorization entry to both independent Root sessions. */
    public List<MyOsDemoResult> authorize(
            String authorizationId,
            int amountMinor) {
        return authorizeDispatch(
                authorizationId, amountMinor).deliveries();
    }

    public MyOsDemoDispatch authorizeDispatch(
            String authorizationId,
            int amountMinor) {
        MyOsDemoEntry entry = demo.append(
                guarantor,
                MyOsDemoOperation.operation("authorizeAmount")
                        .through("guarantorChannel")
                        .request("""
                                authorizationId: %s
                                amountMinor: %d
                                currency: PLN
                                """.formatted(
                                authorizationId,
                                amountMinor))
                        .build());
        MyOsDemoDispatch dispatch = demo.process(entry);
        if (!dispatch.documentKeys().equals(Set.of(PAYNOTE, ORDER))) {
            throw new IllegalStateException(
                    "Authorization fan-out mismatch: "
                            + dispatch.documentKeys());
        }
        for (MyOsDemoResult result : dispatch.deliveries()) {
            var process = result.delivery().transition()
                    .platformResult().processResult();
            if (!process.commits()) {
                String documentKey = result.delivery().transition()
                        .plan().session().sessionId().value()
                        .substring("myos-demo/".length());
                throw new IllegalStateException(
                        "Authorization delivery to " + documentKey
                                + " failed: "
                                + (process.diagnostic() == null
                                ? result.delivery().transition().status()
                                : process.diagnostic().category()
                                + " - " + process.diagnostic().message()
                                + " " + process.diagnostic().details())
                                + "; checkpoint view="
                                + demo.processingViewAt(
                                        documentKey,
                                        PAYNOTE.equals(documentKey)
                                                ? "/contracts/checkpoint"
                                                : "/payNotes/packagePayment/contracts/checkpoint")
                                + "; root checkpoint view="
                                + demo.processingViewAt(
                                        documentKey,
                                        "/contracts/checkpoint"));
            }
        }
        return dispatch;
    }

    public MyOsDemoResult createServiceOrders() {
        return invoke(
                merchant,
                MyOsDemoOperation.operation("createServiceOrders")
                        .through("merchantChannel")
                        .build());
    }

    public MyOsDemoResult linkServiceOrders() {
        return invoke(
                merchant,
                MyOsDemoOperation.operation("attachServiceOrders")
                        .through("merchantChannel")
                        .build());
    }

    public MyOsDemoResult attachHotelCondition() {
        return invoke(
                merchant,
                MyOsDemoOperation.operation("attachHotelCondition")
                        .through("merchantChannel")
                        .request("""
                                productKey: hotel
                                sourceProductPath: /product/products/hotel
                                expectedProductName: Hotel Mlyn Jacka Stay
                                expectedProductIdentity: "wadowice-order-2026-v1:hotel:v1"
                                sourceOrderId: wadowice-order-2026-v1
                                """)
                        .build());
    }

    public MyOsDemoResult attachRestaurantCondition() {
        return invoke(
                merchant,
                MyOsDemoOperation.operation("attachRestaurantCondition")
                        .through("merchantChannel")
                        .request("""
                                productKey: restaurant
                                sourceProductPath: /product/products/restaurant
                                expectedProductName: Old Town Restaurant Dinner
                                expectedProductIdentity: "wadowice-order-2026-v1:restaurant:v1"
                                sourceOrderId: wadowice-order-2026-v1
                                """)
                        .build());
    }

    public MyOsDemoResult confirmRestaurant() {
        return confirmRestaurantDispatch().onlyResult();
    }

    public MyOsDemoDispatch confirmRestaurantDispatch() {
        return invokeDispatch(
                restaurant,
                MyOsDemoOperation.operation("confirmProduct")
                        .through("providerChannel")
                        .request("""
                                confirmationReference: REST-WAD-1930
                                """)
                        .build());
    }

    public MyOsDemoResult confirmHotel() {
        return invoke(
                hotel,
                MyOsDemoOperation.operation("confirmProduct")
                        .through("providerChannel")
                        .request("""
                                confirmationReference: HOTEL-WAD-2207
                                """)
                        .build());
    }

    public MyOsDemoResult capturePayment() {
        return invoke(
                guarantor,
                MyOsDemoOperation.operation("capturePayment")
                        .through("guarantorChannel")
                        .request("""
                                requestId: package-capture-001
                                amountMinor: 130000
                                currency: PLN
                                """)
                        .build());
    }

    public MyOsDemoResult completeHotelStay() {
        return invoke(
                hotel,
                MyOsDemoOperation.operation("completeProduct")
                        .through("providerChannel")
                        .request("""
                                confirmationCode: WAD-7429
                                note: Stay completed with customer present.
                                """)
                        .build());
    }

    /** Runs the shared path through attachment of both live Product conditions. */
    public List<MyOsDemoResult> prepareAttachedConditions() {
        List<MyOsDemoResult> results = new ArrayList<>();
        MyOsDemoResult attachment = attachPayNote();
        results.add(attachment);
        if (!Boolean.TRUE.equals(demo.value(ORDER, "/payNoteAttached"))) {
            List<String> eventKinds = attachment.delivery().transition()
                    .platformResult().processResult().events().stream()
                    .map(event -> String.valueOf(demo.value(event, "/kind")))
                    .toList();
            throw new IllegalStateException(
                    "PayNote attachment did not activate the embedded scope; "
                            + "Root event kinds=" + eventKinds
                            + ", gas=" + attachment.delivery().transition()
                            .platformResult().processResult().totalGas()
                            + ", selected scopes=" + attachment.delivery()
                            .transition().plan().preparedDelivery()
                            .selectedScopeChainIdentities().keySet()
                            + ", scope transitions=" + attachment.delivery()
                            .transition().fragmentTransition()
                            .scopeTransitions().stream()
                            .map(transition -> transition.scopePath() + "="
                                    + transition.kind())
                            .toList());
        }
        results.addAll(authorize("wadowice-auth-50000", 50000));
        results.addAll(authorize("wadowice-auth-80000", 80000));
        results.add(createServiceOrders());
        results.add(linkServiceOrders());
        results.add(attachHotelCondition());
        results.add(attachRestaurantCondition());
        return Collections.unmodifiableList(results);
    }

    /** Runs the shared path through captured payment and completed Hotel stay. */
    public List<MyOsDemoResult> prepareRestaurantOutcome() {
        List<MyOsDemoResult> results = new ArrayList<>(
                prepareAttachedConditions());
        results.add(confirmRestaurant());
        results.add(confirmHotel());
        results.add(capturePayment());
        results.add(completeHotelStay());
        return Collections.unmodifiableList(results);
    }

    public MyOsDemoResult completeRestaurantDinner() {
        return invoke(
                restaurant,
                MyOsDemoOperation.operation("completeProduct")
                        .through("providerChannel")
                        .request("""
                                confirmationCode: WAD-7429
                                note: Dinner completed as booked.
                                """)
                        .build());
    }

    public MyOsDemoResult cancelRestaurantWithinRange() {
        return invoke(
                customer,
                MyOsDemoOperation.operation("cancelWithinRange")
                        .through("customerChannel")
                        .request("""
                                reason: Plans changed within the allowed cancellation window.
                                """)
                        .build());
    }

    public MyOsDemoResult completeRestaurantWithDiscount() {
        return invoke(
                restaurant,
                MyOsDemoOperation.operation("completeWithDiscount")
                        .through("providerChannel")
                        .request("""
                                confirmationCode: WAD-7429
                                note: Dinner completed with a service recovery discount.
                                """)
                        .build());
    }

    public MyOsDemoResult declineLateRestaurantCancellation() {
        return invoke(
                customer,
                MyOsDemoOperation.operation("cancelOutsideRange")
                        .through("customerChannel")
                        .request("""
                                reason: Cancellation requested outside the allowed range or customer did not arrive.
                                """)
                        .build());
    }

    public MyOsDemoResult completeCancellationRefund() {
        return invoke(
                guarantor,
                MyOsDemoOperation.operation("refundPayment")
                        .through("guarantorChannel")
                        .request("""
                                requestId: restaurant-refund-001
                                amountMinor: 38000
                                currency: PLN
                                """)
                        .build());
    }

    public MyOsDemoResult completeDiscountAdjustment() {
        return invoke(
                guarantor,
                MyOsDemoOperation.operation("refundPayment")
                        .through("guarantorChannel")
                        .request("""
                                requestId: restaurant-discount-001
                                amountMinor: 3800
                                currency: PLN
                                """)
                        .build());
    }

    private MyOsDemoResult invoke(
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation) {
        return invokeDispatch(timeline, operation).require(ORDER);
    }

    private MyOsDemoDispatch invokeDispatch(
            MyOsDemoTimeline timeline,
            MyOsDemoOperation operation) {
        return processChecked(
                operation,
                demo.append(timeline, operation));
    }

    private MyOsDemoDispatch processChecked(
            MyOsDemoOperation operation,
            MyOsDemoEntry entry) {
        MyOsDemoDispatch dispatch = demo.process(entry);
        for (var delivery : dispatch.deliveriesByDocument().entrySet()) {
            MyOsDemoResult result = delivery.getValue();
            var process = result.delivery().transition()
                    .platformResult().processResult();
            if (!process.commits()) {
                throw new IllegalStateException(
                        operation.operation() + " delivery to "
                                + delivery.getKey() + " failed: "
                                + (process.diagnostic() == null
                                ? result.delivery().transition().status()
                                : process.diagnostic().category()
                                + " - " + process.diagnostic().message()
                                + " " + process.diagnostic().details()));
            }
        }
        return dispatch;
    }

    @Override
    public void close() {
        demo.close();
    }
}
