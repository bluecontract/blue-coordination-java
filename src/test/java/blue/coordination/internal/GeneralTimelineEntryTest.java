package blue.coordination.internal;

import blue.coordination.sdk.BlueCoordination;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** General input admission uses the same exact journal as operation input. */
final class GeneralTimelineEntryTest {
    @Test
    void retainsOriginalGeneralEnvelopeWithoutExecutingAReceiver() {
        // given
        try (var sdk = BlueCoordination.inMemory()) {
            var engine = sdk.advanced().rawEngine();
            engine.registerTimeline("general-entry", "alice");
            Node event = GeneralTimelineEntryMother.event(new Node()
                    .properties("kind", new Node().value("CreditRecorded"))
                    .properties("amount", new Node().value(7L)));
            String identity = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(event);
            // when
            var accepted = engine.appendTimelineEntry(event);
            // then
            assertEquals(identity, accepted.entry().blueId());
            assertTrue(blue.coordination.api.ExactValue.verified(event)
                    .sameExactValue(accepted.entry().exactEvent()));
            assertTrue(accepted.entry().operationDetails().isEmpty());
            assertTrue(accepted.entry().request().isEmpty());
            assertThrows(java.util.NoSuchElementException.class, accepted.entry()::operation);
            assertThrows(java.util.NoSuchElementException.class, accepted.entry()::channel);
        }
    }
}
