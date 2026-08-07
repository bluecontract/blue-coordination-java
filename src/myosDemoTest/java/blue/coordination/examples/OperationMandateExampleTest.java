package blue.coordination.examples;

import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.scenarios.OperationMandateScenario;
import blue.coordination.examples.scenarios.OperationMandateScenario.RequestedOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feeder-owned Operation Mandate eligibility over real processed documents. */
final class OperationMandateExampleTest {

    @Test
    void shouldAllowTheBoundedAgentOperationAfterAuthorityConfirmation() {
        // given
        try (OperationMandateScenario scenario =
                     OperationMandateScenario.create()) {
            MyOsDemoResult confirmation = scenario.confirmAuthority();
            RequestedOperation request = scenario.requestIncrement(1);

            // when
            MyOsDemoResult increment = scenario.deliverEligible(request);

            // then
            MyOsDemoAssertions.assertSuccessful(confirmation);
            MyOsDemoAssertions.assertSuccessful(increment);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), increment);
            MyOsDemoAssertions.assertSelectedScopes(confirmation, "/");
            MyOsDemoAssertions.assertSelectedScopes(increment, "/");
            assertTrue(request.decision().isEligible(),
                    request.decision().reason());
            MyOsDemoAssertions.assertValue(
                    scenario.demo(),
                    OperationMandateScenario.TARGET,
                    "/counter",
                    1);
            assertEquals(1L, scenario.demo().currentEpoch(
                    OperationMandateScenario.TARGET));
            assertEquals(1L, scenario.demo().currentEpoch(
                    OperationMandateScenario.MANDATE));
        }
    }

    @Test
    void shouldWithholdAnOutOfPolicyAmountBeforeProcess() {
        // given
        try (OperationMandateScenario scenario =
                     OperationMandateScenario.create()) {
            MyOsDemoResult confirmation = scenario.confirmAuthority();

            // when
            RequestedOperation request = scenario.requestIncrement(2);

            // then
            MyOsDemoAssertions.assertSuccessful(confirmation);
            MyOsDemoAssertions.assertSelectedScopes(confirmation, "/");
            assertTrue(request.decision().isIneligible());
            assertEquals(
                    "mandate-request-pattern-mismatch",
                    request.decision().reason());
            MyOsDemoAssertions.assertValue(
                    scenario.demo(),
                    OperationMandateScenario.TARGET,
                    "/counter",
                    0);
            assertEquals(0L, scenario.demo().currentEpoch(
                    OperationMandateScenario.TARGET));
            assertEquals(1L, scenario.demo().currentEpoch(
                    OperationMandateScenario.MANDATE));
        }
    }

    @Test
    void shouldRevokeFutureAgentOperationsAfterMandateTermination() {
        // given
        try (OperationMandateScenario scenario =
                     OperationMandateScenario.create()) {
            MyOsDemoResult confirmation = scenario.confirmAuthority();
            RequestedOperation allowed = scenario.requestIncrement(1);
            MyOsDemoResult increment = scenario.deliverEligible(allowed);
            MyOsDemoResult termination = scenario.terminate();

            // when
            RequestedOperation afterTermination =
                    scenario.requestIncrement(1);

            // then
            MyOsDemoAssertions.assertSuccessful(confirmation);
            MyOsDemoAssertions.assertSuccessful(increment);
            MyOsDemoAssertions.assertSuccessful(termination);
            MyOsDemoAssertions.assertExactRootEventKindsInOrder(
                    scenario.demo(), increment);
            MyOsDemoAssertions.assertSelectedScopes(confirmation, "/");
            MyOsDemoAssertions.assertSelectedScopes(increment, "/");
            MyOsDemoAssertions.assertSelectedScopes(termination, "/");
            assertTrue(afterTermination.decision().isIneligible());
            assertEquals(
                    "mandate-not-active",
                    afterTermination.decision().reason());
            MyOsDemoAssertions.assertValue(
                    scenario.demo(),
                    OperationMandateScenario.TARGET,
                    "/counter",
                    1);
            assertEquals(1L, scenario.demo().currentEpoch(
                    OperationMandateScenario.TARGET));
            assertEquals(2L, scenario.demo().currentEpoch(
                    OperationMandateScenario.MANDATE));
        }
    }
}
