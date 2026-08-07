package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Test;

/**
 * Deterministic performance contract for one entry shared by two deep scopes.
 *
 * <p>The test intentionally asserts graph demand and selected scope paths,
 * not wall-clock time. It therefore protects the optimization on every host
 * without turning machine noise into product semantics.</p>
 */
final class WadowiceHotelDinnerLocalityTest {

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldLoadOnlyTheTwoRestaurantBranchesForOneRestaurantEntry() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.conditionsBranch("wadowice-locality")) {
            MyOsMeasuredWork before = scenario.demo().measuredWork();

            // when
            MyOsDemoResult confirmation = scenario.confirmRestaurant();
            MyOsMeasuredWork delta = scenario.demo().measuredWork()
                    .minus(before);

            // then
            MyOsDemoAssertions.assertSuccessful(confirmation);
            MyOsDemoAssertions.assertSelectedScopes(
                    confirmation,
                    "/payNotes/packagePayment/productConditions/restaurant/product",
                    "/product/products/restaurant");
            MyOsDemoAssertions.assertStrictFragmentLocality(
                    scenario.demo(),
                    WadowiceHotelDinnerScenario.ORDER,
                    confirmation);
            WadowiceWorkBudgetAssertions.assertOneRootProcess(delta);
        }
    }
}
