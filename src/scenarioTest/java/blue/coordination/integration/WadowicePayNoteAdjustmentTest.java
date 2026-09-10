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
final class WadowicePayNoteAdjustmentTest {
    @Test
    void embeddedTenPercentAdjustmentCompletesOnePartialRefund(
            TestReporter reporter) throws Exception {
        try (Campaign campaign = Campaign.embedded()) {
            // given
            campaign.prepareCapturedPayment();

            // when
            campaign.discountRestaurant();

            // then
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/product/done"));
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/product/discountApplied"));
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/done"));
            assertTrue(campaign.bool(
                    "/productConditions/restaurant/discountApplied"));
            assertRefund(campaign, "restaurant-discount-001", 3_800L,
                    126_200L);
            assertEquals(1L, campaign.eventCount(
                    "PayNote/Condition Product Discount Applied"));
            assertEquals(1L, campaign.eventCount(
                    "PayNote/Refund Requested"));
            assertEquals(1L, campaign.eventCount(
                    "PayNote/Refund Completed"));
            campaign.assertParentCurrent();
            campaign.verifyAccounting();
            campaign.publishTimings(reporter, "embedded-adjustment");
        }
    }
}
