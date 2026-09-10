package blue.coordination.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import blue.coordination.integration.WadowicePayNoteTestSupport.Campaign;
import static blue.coordination.integration.WadowicePayNoteTestSupport.assertRefund;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Complete acceptance scenario with an independently created runtime and fixture. */
@Tag("scenario")
final class WadowicePayNoteCancellationTest {
    @Test
    void embeddedCancellationRequestsAndCompletesOneRestaurantRefund(
            TestReporter reporter) throws Exception {
        try (Campaign campaign = Campaign.embedded()) {
            // given
            campaign.prepareCapturedPayment();

            // when
            campaign.cancelRestaurant();

            // then
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/product/cancelled"));
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/cancelled"));
            assertFalse(campaign.bool("/productConditions/restaurant/done"));
            assertRefund(campaign, "restaurant-refund-001", 38_000L,
                    92_000L);
            assertEquals(1L, campaign.eventCount(
                    "PayNote/Refund Requested"));
            assertEquals(1L, campaign.eventCount(
                    "PayNote/Refund Completed"));
            campaign.assertParentCurrent();
            campaign.verifyAccounting();
            campaign.publishTimings(reporter, "embedded-cancellation");
        }
    }
}
