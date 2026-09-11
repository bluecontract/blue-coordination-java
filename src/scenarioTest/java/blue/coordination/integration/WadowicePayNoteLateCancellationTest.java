package blue.coordination.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import java.util.List;
import blue.coordination.integration.WadowicePayNoteTestSupport.Campaign;
import static blue.coordination.integration.WadowicePayNoteTestSupport.LATE_CANCELLATION_BUSINESS_PATHS;
import static blue.coordination.integration.WadowicePayNoteTestSupport.RESTAURANT_PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Complete acceptance scenario with an independently created runtime and fixture. */
@Tag("scenario")
final class WadowicePayNoteLateCancellationTest {
    @Test
    void embeddedLateCancellationIsRefusedWithoutBusinessStateChange(
            TestReporter reporter) throws Exception {
        try (Campaign campaign = Campaign.embedded()) {
            // given
            campaign.prepareCapturedPayment();
            List<String> unchangedBusinessState = campaign.businessIdentity(
                    LATE_CANCELLATION_BUSINESS_PATHS);
            int childHistoryBefore = campaign.engine.history(
                    RESTAURANT_PRODUCT).size();

            // when
            campaign.refuseLateCancellation();

            // then
            assertEquals(unchangedBusinessState, campaign.businessIdentity(
                    LATE_CANCELLATION_BUSINESS_PATHS));
            assertEquals(childHistoryBefore + 1,
                    campaign.engine.history(RESTAURANT_PRODUCT).size(),
                    "the refusal is processed and retained as an exact epoch");
            assertFalse(campaign.bool(
                    "/productConditions/restaurant/cancelled"));
            assertFalse(campaign.bool("/refund/requested"));
            assertEquals(1L, campaign.eventCount(
                    "Commerce/Change Declined"));
            assertEquals(0L, campaign.eventCount(
                    "PayNote/Refund Requested"));
            campaign.assertParentCurrent();
            campaign.verifyAccounting();
            campaign.publishTimings(reporter, "embedded-late-refusal");
        }
    }
}
