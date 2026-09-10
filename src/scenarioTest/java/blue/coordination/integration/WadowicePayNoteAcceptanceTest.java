package blue.coordination.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import blue.coordination.integration.WadowicePayNoteTestSupport.Campaign;
import static blue.coordination.integration.WadowicePayNoteTestSupport.assertCompleted;
import static blue.coordination.integration.WadowicePayNoteTestSupport.assertBusinessParity;

/** Complete acceptance scenario with an independently created runtime and fixture. */
@Tag("scenario")
final class WadowicePayNoteAcceptanceTest {
    @Test
    void completionHasFullBusinessParityStandaloneAndEmbedded(
            TestReporter reporter) throws Exception {
        try (Campaign standalone = Campaign.standalone();
                Campaign embedded = Campaign.embedded()) {
            // given
            standalone.prepareCapturedPayment();
            embedded.prepareCapturedPayment();

            // when
            standalone.completeRestaurant();
            embedded.completeRestaurant();

            // then
            assertCompleted(standalone);
            assertCompleted(embedded);
            assertBusinessParity(standalone, embedded);
            standalone.verifyAccounting();
            embedded.verifyAccounting();
            standalone.publishTimings(reporter, "standalone-completion");
            embedded.publishTimings(reporter, "embedded-completion");
        }
    }
}
