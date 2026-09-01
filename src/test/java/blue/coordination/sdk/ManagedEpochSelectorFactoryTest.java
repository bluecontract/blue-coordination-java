package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SDK contract for the documented exact managed-epoch selector factory. */
final class ManagedEpochSelectorFactoryTest {

    @Test
    void exactFactoryPreservesEverySelectorCoordinate() {
        // given
        DocumentId source = DocumentId.of("selector-source");
        String blueId = "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";

        // when
        ManagedEpochSelector selector = ManagedEpochSelector.exact(
                source, 3L, blueId, "/children/0");

        // then
        assertEquals(source, selector.sourceDocumentId());
        assertEquals(3L, selector.sourceEpoch());
        assertEquals(blueId, selector.expectedSourceBlueId());
        assertEquals("/children/0", selector.targetOccurrencePath());
    }
}
