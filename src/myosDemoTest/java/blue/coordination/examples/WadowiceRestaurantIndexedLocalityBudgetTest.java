package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsDemoResult;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Restaurant confirmation stays sparse while selecting both required scopes. */
final class WadowiceRestaurantIndexedLocalityBudgetTest {

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldRouteRestaurantConfirmationWithoutScanningTheOrderRoot() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.conditionsBranch(
                             "restaurant-index-work-budget")) {
            MyOsMeasuredWork before = scenario.demo().measuredWork();

            // when
            MyOsDemoDispatch dispatch =
                    scenario.confirmRestaurantDispatch();
            MyOsDemoResult result = dispatch.onlyResult();
            MyOsMeasuredWork work = scenario.demo().measuredWork()
                    .minus(before);

            // then
            MyOsDemoAssertions.assertSuccessful(result);
            assertEquals(Set.of(WadowiceHotelDinnerScenario.ORDER),
                    dispatch.documentKeys());
            assertEquals(
                    true,
                    scenario.demo().value(
                            WadowiceHotelDinnerScenario.ORDER,
                            "/payNotes/packagePayment/productConditions/"
                                    + "restaurant/confirmed"));
            assertEquals(
                    List.of(
                            "/payNotes/packagePayment/productConditions/"
                                    + "restaurant/product",
                            "/product/products/restaurant"),
                    result.delivery().transition().plan()
                            .preparedDelivery()
                            .selectedScopeChainIdentities()
                            .keySet().stream().toList());
            WadowiceWorkBudgetAssertions.assertOneRootProcess(work);
            Set<String> currentInventory = scenario.demo()
                    .currentFragmentBlueIds(
                            WadowiceHotelDinnerScenario.ORDER);
            long loadedCurrentFragments = result.delivery().transition()
                    .locality().backendLoadedBlueIds().stream()
                    .filter(currentInventory::contains)
                    .count();
            assertTrue(
                    loadedCurrentFragments < currentInventory.size(),
                    () -> "selected PROCESS bundle loaded "
                            + loadedCurrentFragments
                            + " fragments from a complete inventory of "
                            + currentInventory.size()
                            + "; required seeds="
                            + result.delivery().transition().plan()
                            .requiredSeedBlueIds().size()
                            + "; preferred prefetch="
                            + result.delivery().transition().plan()
                            .preferredPrefetchBlueIds().size());
            assertEquals(0, result.delivery().transition().locality()
                    .fallbackReadCount());
            assertEquals(0, result.delivery().transition().locality()
                    .forbiddenReadCount());
        }
    }
}
