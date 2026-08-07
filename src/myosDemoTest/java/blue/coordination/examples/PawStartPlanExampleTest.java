package blue.coordination.examples;

import blue.coordination.examples.scenarios.PawStartPlanScenario;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Full PawStart fulfilment, settlement, and bounded-delegation branches. */
final class PawStartPlanExampleTest {

    @Test
    void shouldCompleteTheConfirmedTrainingVisitAndEarnSettlement() {
        // given
        try (PawStartPlanScenario scenario = PawStartPlanScenario.create()) {
            List<MyOsDemoResult> setup = scenario.prepareConfirmedVisit();

            // when
            MyOsDemoResult completion = scenario.completeVisitNormally();

            // then
            setup.forEach(MyOsDemoAssertions::assertSuccessful);
            MyOsDemoAssertions.assertSuccessful(completion);
            assertConfirmedCommonState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/terminalOutcome",
                    "Completed");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/products/puppyTraining/status",
                    "Done");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/completedVisitCount", 1);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/puppyTrainingSettlementState",
                    "Earned");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/requested", false);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), completion, "Commerce/Product Done");
        }
    }

    @Test
    void shouldCancelOnTimeAndCompleteTheExactTrainingRefund() {
        // given
        try (PawStartPlanScenario scenario = PawStartPlanScenario.create()) {
            List<MyOsDemoResult> setup = scenario.prepareConfirmedVisit();

            // when
            MyOsDemoResult cancellation = scenario.cancelVisitOnTime();
            MyOsDemoResult refund = scenario.completeCancellationRefund();

            // then
            setup.forEach(MyOsDemoAssertions::assertSuccessful);
            MyOsDemoAssertions.assertSuccessful(cancellation);
            MyOsDemoAssertions.assertSuccessful(refund);
            assertConfirmedCommonState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/terminalOutcome",
                    "CancelledOnTime");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/onTimeCancellationCount", 1);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/puppyTrainingSettlementState",
                    "Not earned");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/amountMinor", 27100);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/completed", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/amount/refundedMinor", 27100);
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
    void shouldRecordNoShowWithoutClaimingDeliveryOrRefund() {
        // given
        try (PawStartPlanScenario scenario = PawStartPlanScenario.create()) {
            List<MyOsDemoResult> setup = scenario.prepareConfirmedVisit();

            // when
            MyOsDemoResult noShow = scenario.recordNoShow();

            // then
            setup.forEach(MyOsDemoAssertions::assertSuccessful);
            MyOsDemoAssertions.assertSuccessful(noShow);
            assertConfirmedCommonState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/terminalOutcome", "NoShow");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/lateCancellationOrNoShowCount",
                    1);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/products/puppyTraining/done",
                    false);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/puppyTrainingSettlementState",
                    "Earned");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/requested", false);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), noShow, "Commerce/No Show Recorded");
        }
    }

    @Test
    void shouldCompleteWithLowSatisfactionAndApplyTheExactAdjustment() {
        // given
        try (PawStartPlanScenario scenario = PawStartPlanScenario.create()) {
            List<MyOsDemoResult> setup = scenario.prepareConfirmedVisit();

            // when
            MyOsDemoResult lowSatisfaction =
                    scenario.completeWithLowSatisfaction();
            MyOsDemoResult adjustment =
                    scenario.completeLowSatisfactionAdjustment();

            // then
            setup.forEach(MyOsDemoAssertions::assertSuccessful);
            MyOsDemoAssertions.assertSuccessful(lowSatisfaction);
            MyOsDemoAssertions.assertSuccessful(adjustment);
            assertConfirmedCommonState(scenario);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/product/products/puppsOrder/terminalOutcome",
                    "CompletedLowSatisfaction");
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/lowSatisfactionCount", 1);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/partnerAgreements/pupps/openIssues/puppyTrainingLowSatisfaction/open",
                    true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/amountMinor", 2710);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/refund/completed", true);
            MyOsDemoAssertions.assertValue(
                    scenario.demo(), PawStartPlanScenario.ORDER,
                    "/payNote/amount/refundedMinor", 2710);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), lowSatisfaction,
                    "Commerce/Product Done",
                    "Commerce/Satisfaction Submitted",
                    "PayNote/Refund Requested");
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), adjustment,
                    "PayNote/Refund Completed");
        }
    }

    @Test
    void shouldTerminateTheSchedulingMandateAfterTheAuthorizedCall() {
        // given
        try (PawStartPlanScenario scenario = PawStartPlanScenario.create()) {
            List<MyOsDemoResult> setup = scenario.prepareConfirmedVisit();

            // when
            MyOsDemoResult termination =
                    scenario.terminateSchedulingAuthority();

            // then
            setup.forEach(MyOsDemoAssertions::assertSuccessful);
            MyOsDemoAssertions.assertSuccessful(termination);
            assertNotNull(scenario.demo().value(
                    PawStartPlanScenario.MANDATE,
                    "/contracts/terminated"));
        }
    }

    private static void assertConfirmedCommonState(
            PawStartPlanScenario scenario) {
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/initialization/agreementAttached", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/initialization/payNoteAttached", true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/product/products/puppsOrder/pendingVisit/status",
                "Visit requested");
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/product/products/puppsOrder/confirmedVisit/confirmed",
                true);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/partnerAgreements/pupps/requestedVisitCount", 1);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/partnerAgreements/pupps/confirmedVisitCount", 1);
        MyOsDemoAssertions.assertValue(
                scenario.demo(), PawStartPlanScenario.ORDER,
                "/payNote/trainingVisit/confirmed", true);
    }
}
