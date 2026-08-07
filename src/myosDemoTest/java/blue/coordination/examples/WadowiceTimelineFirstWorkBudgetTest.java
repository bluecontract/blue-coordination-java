package blue.coordination.examples;

import blue.coordination.examples.scenarios.WadowiceHotelDinnerScenario;
import blue.coordination.examples.scenarios.WadowicePreparedFixture;
import blue.coordination.examples.support.MyOsDemoAssertions;
import blue.coordination.examples.support.MyOsDemoDispatch;
import blue.coordination.examples.support.MyOsMeasuredWork;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exact work budgets avoid fragile machine-time assertions. */
final class WadowiceTimelineFirstWorkBudgetTest {

    private static final WadowicePreparedFixture FIXTURE =
            WadowicePreparedFixture.shared();

    @Test
    void shouldPrepareOneAuthorizationEntryOnceForTwoRootSessions() {
        // given
        try (WadowiceHotelDinnerScenario scenario =
                     FIXTURE.payNoteBranch(
                             "authorization-work-budget")) {
            MyOsMeasuredWork before = scenario.demo().measuredWork();

            // when
            MyOsDemoDispatch dispatch = scenario.authorizeDispatch(
                    "wadowice-auth-50000", 50000);
            MyOsMeasuredWork work = scenario.demo().measuredWork()
                    .minus(before);

            // then
            dispatch.deliveries().forEach(
                    MyOsDemoAssertions::assertSuccessful);
            assertEquals(Set.of(
                    WadowiceHotelDinnerScenario.PAYNOTE,
                    WadowiceHotelDinnerScenario.ORDER),
                    dispatch.documentKeys());
            WadowiceWorkBudgetAssertions.assertTwoRootFanout(work);
        }
    }
}
