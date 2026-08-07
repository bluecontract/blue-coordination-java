package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wadowice living Order, two provider Products, and conditional settlement. */
final class WadowiceHotelDinnerOrderExampleTest {

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldCaptureAndConfirmTheCompleteHotelAndDinnerOrder() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.branch("complete-order")) {

            // when
            MyOsDemoResult dinner = scenario.completeRestaurantDinner();

            // then
            MyOsDemoAssertions.assertSuccessful(dinner);
            assertPreparedState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/done", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/orderState", "Confirmed");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/paymentState", "Completed");
            assertPaymentOutcome(scenario, 130000, false, false, 0);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), dinner, "Commerce/Product Done");
        }
    }

    @Test
    void shouldCancelRestaurantWithinRangeAndRefundOnlyItsComponent() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.branch("cancel-refund")) {

            // when
            MyOsDemoResult cancellation =
                    scenario.cancelRestaurantWithinRange();
            MyOsDemoResult refund = scenario.completeCancellationRefund();

            // then
            MyOsDemoAssertions.assertSuccessful(cancellation);
            MyOsDemoAssertions.assertSuccessful(refund);
            assertPreparedState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/cancelled", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/requested", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/amountMinor", 38000);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/completed", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured", 92000);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/paymentState", "Partially Refunded");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/requestId",
                    "restaurant-refund-001");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/orderState", "Restaurant Cancelled - Refund Pending");
            assertPaymentOutcome(scenario, 92000, true, true, 38000);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), cancellation,
                    "Commerce/Product Cancelled",
                    "PayNote/Refund Requested");
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), refund,
                    "PayNote/Refund Completed");
        }
    }

    @Test
    void shouldCompleteDinnerWithTenPercentAdjustment() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.branch("discount-adjustment")) {

            // when
            MyOsDemoResult discounted =
                    scenario.completeRestaurantWithDiscount();
            MyOsDemoResult adjustment =
                    scenario.completeDiscountAdjustment();

            // then
            MyOsDemoAssertions.assertSuccessful(discounted);
            MyOsDemoAssertions.assertSuccessful(adjustment);
            assertPreparedState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/done", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/discountPercent", 10);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/amountMinor", 3800);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/completed", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/paymentState", "Partially Refunded");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/orderState", "Confirmed");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/discountAmountMinor", 3800);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/netAmountMinor", 34200);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/requestId",
                    "restaurant-discount-001");
            assertPaymentOutcome(scenario, 126200, true, true, 3800);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), discounted,
                    "Commerce/Product Done",
                    "Commerce/Product Discount Applied",
                    "PayNote/Refund Requested");
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), adjustment,
                    "PayNote/Refund Completed");
        }
    }

    @Test
    void shouldDeclineLateCancellationWithoutChangingRestaurantState() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.branch("late-cancellation")) {
            Object orderStateBefore = scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER, "/orderState");
            Object paymentStateBefore = scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER, "/paymentState");
            Object restaurantStatusBefore = scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/status");
            Object capturedBefore = scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured");

            // when
            MyOsDemoResult declined =
                    scenario.declineLateRestaurantCancellation();

            // then
            MyOsDemoAssertions.assertSuccessful(declined);
            assertPreparedState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/done", false);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/cancelled", false);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/refund/requested", false);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured", 130000);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/cancellationRequested",
                    false);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/discountPercent", 0);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/netAmountMinor", 38000);
            assertPaymentOutcome(scenario, 130000, false, false, 0);
            assertEquals(orderStateBefore, scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER, "/orderState"));
            assertEquals(paymentStateBefore, scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER, "/paymentState"));
            assertEquals(restaurantStatusBefore, scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER,
                    "/product/products/restaurant/status"));
            assertEquals(capturedBefore, scenario.demo().value(
                    WadowiceHotelDinnerScenario.ORDER,
                    "/payNotes/packagePayment/amount/captured"));
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), declined, "Commerce/Change Declined");
        }
    }

    private static void assertPaymentOutcome(
            WadowiceHotelDinnerScenario scenario,
            int capturedMinor,
            boolean refundRequested,
            boolean refundCompleted,
            int refundMinor) {
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/amount/captured", capturedMinor);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/refund/requested", refundRequested);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/refund/completed", refundCompleted);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/refund/amountMinor", refundMinor);
    }

    private static void assertPreparedState(
            WadowiceHotelDinnerScenario scenario) {
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.PAYNOTE,
                "/authorization/authorizedAmountMinor", 130000);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.PAYNOTE,
                "/authorization/authorizationCount", 2);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/authorization/authorizedAmountMinor",
                130000);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/authorization/authorizationCount",
                2);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/attachedConditions/hotel", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/attachedConditions/restaurant", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/product/products/restaurant/confirmed", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/product/products/hotel/confirmed", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/productConditions/restaurant/confirmed",
                true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/productConditions/hotel/confirmed",
                true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/capture/requested", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/capture/requestCount", 1);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/payNotes/packagePayment/capture/completed", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), WadowiceHotelDinnerScenario.ORDER,
                "/product/products/hotel/done", true);
    }
}
