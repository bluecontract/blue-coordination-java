package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Budgets backed only by live engine, store and host work sites. */
final class WadowiceMeasuredWorkBudgetTest {

    /** Provider-backed authorization definition in the frozen Wadowice set. */
    private static final String AUTHORIZATION_PROVIDER_BLUE_ID =
            "7qzdy4hb1EpafAHj7SELuiBSY1nfj5P8HhupnXTMjdYb";

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldPrepareOneAuthorizationEntryForExactlyTwoRoots() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.payNoteBranch("measured-authorization")) {
            MyOsMeasuredWork before = scenario.demo().measuredWork();
            int inventoriesBefore = scenario.demo()
                    .storedEventInventoryCount();

            // when
            MyOsDemoDispatch dispatch = scenario.authorizeDispatch(
                    "measured-auth-50000", 50000);
            MyOsMeasuredWork delta = scenario.demo().measuredWork()
                    .minus(before);

            // then
            dispatch.deliveries().forEach(
                    MyOsDemoAssertions::assertSuccessful);
            assertEquals(Set.of(
                    WadowiceHotelDinnerScenario.PAYNOTE,
                    WadowiceHotelDinnerScenario.ORDER),
                    dispatch.documentKeys());
            assertEquals(1, scenario.demo().storedEventInventoryCount()
                    - inventoriesBefore);
            var storedEvent = scenario.demo().environment().eventStore()
                    .require(dispatch.entry().blueId());
            var committedReceipts = dispatch.documentKeys().stream()
                    .map(documentKey -> scenario.demo().environment()
                            .committedDeliveryProbe()
                            .committedDelivery(
                                    storedEvent,
                                    scenario.demo().document(documentKey)
                                            .sessionId())
                            .orElseThrow(() -> new AssertionError(
                                    "Missing committed delivery receipt for "
                                            + documentKey)))
                    .toList();
            assertEquals(2, committedReceipts.size());
            assertEquals(Set.of(
                            scenario.demo().document(
                                    WadowiceHotelDinnerScenario.PAYNOTE)
                                    .sessionId(),
                            scenario.demo().document(
                                    WadowiceHotelDinnerScenario.ORDER)
                                    .sessionId()),
                    committedReceipts.stream()
                            .map(receipt -> receipt.sessionId())
                            .collect(java.util.stream.Collectors.toSet()));
            assertTrue(committedReceipts.stream().allMatch(receipt ->
                    receipt.eventBlueId().equals(dispatch.entry().blueId())));
            var providerResolved = dispatch.deliveries().stream()
                    .map(result -> result.delivery().transition())
                    .filter(transition -> transition.locality()
                            .requestedBlueIds()
                            .contains(AUTHORIZATION_PROVIDER_BLUE_ID))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Frozen PROCESS bypassed the request-local "
                                    + "provider for the authorization "
                                    + "definition"));
            assertTrue(providerResolved.locality().backendLoadedBlueIds()
                            .contains(AUTHORIZATION_PROVIDER_BLUE_ID),
                    "the demanded authorization definition must come from "
                            + "the exact prepared provider bundle");
            assertEquals(0, providerResolved.locality()
                    .fallbackReadCount());
            assertEquals(0, providerResolved.locality()
                    .forbiddenReadCount());
            WadowiceWorkBudgetAssertions.assertTwoRootFanout(delta);
        }
    }

    @Test
    void shouldConfirmBothOccurrencesInOneSparseRootProcess() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.conditionsBranch(
                             "measured-restaurant-locality")) {
            MyOsMeasuredWork before = scenario.demo().measuredWork();
            long epochBefore = scenario.demo().currentEpoch(
                    WadowiceHotelDinnerScenario.ORDER);

            // when
            MyOsDemoDispatch dispatch =
                    scenario.confirmRestaurantDispatch();
            MyOsMeasuredWork delta = scenario.demo().measuredWork()
                    .minus(before);
            var result = dispatch.onlyResult();
            var storedEvent = scenario.demo().environment().eventStore()
                    .require(dispatch.entry().blueId());
            var receipt = scenario.demo().environment()
                    .committedDeliveryProbe()
                    .committedDelivery(
                            storedEvent,
                            scenario.demo().document(
                                    WadowiceHotelDinnerScenario.ORDER)
                                    .sessionId())
                    .orElseThrow(() -> new AssertionError(
                            "Missing committed restaurant delivery"));

            // then
            MyOsDemoAssertions.assertSuccessful(result);
            assertEquals(Set.of(WadowiceHotelDinnerScenario.ORDER),
                    dispatch.documentKeys());
            assertEquals(scenario.demo().document(
                            WadowiceHotelDinnerScenario.ORDER).sessionId(),
                    receipt.sessionId());
            assertEquals(List.of(
                            "/payNotes/packagePayment/productConditions/"
                                    + "restaurant/product",
                            "/product/products/restaurant"),
                    result.delivery().transition().plan()
                            .preparedDelivery()
                            .selectedScopeChainIdentities()
                            .keySet().stream().toList());
            assertEquals(epochBefore,
                    result.delivery().transition().beforeEpoch());
            assertEquals(Math.addExact(epochBefore, 1L),
                    result.delivery().transition().afterEpoch());
            assertEquals(Math.addExact(epochBefore, 1L),
                    scenario.demo().currentEpoch(
                            WadowiceHotelDinnerScenario.ORDER));
            WadowiceWorkBudgetAssertions.assertOneRootProcess(delta);

            Set<String> completeInventory = scenario.demo()
                    .currentFragmentBlueIds(
                            WadowiceHotelDinnerScenario.ORDER);
            long loadedCurrent = result.delivery().transition().locality()
                    .backendLoadedBlueIds().stream()
                    .filter(completeInventory::contains)
                    .count();
            assertTrue(loadedCurrent < completeInventory.size(),
                    () -> "loaded complete inventory: " + loadedCurrent
                            + "/" + completeInventory.size());
            assertEquals(0, result.delivery().transition().locality()
                    .fallbackReadCount());
            assertEquals(0, result.delivery().transition().locality()
                    .forbiddenReadCount());
        }
    }
}
