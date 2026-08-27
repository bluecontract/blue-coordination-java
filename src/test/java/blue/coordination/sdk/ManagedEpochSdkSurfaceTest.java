package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedEpochSdkSurfaceTest {

    @Test
    void selectorPlumbingIsTypedImmutableAndOccurrenceSpecific() {
        // given
        ExactValue state = ExactValue.verified(new Node().value("state"));
        ManagedEpochSelector selector = new ManagedEpochSelector(
                DocumentId.of("source"),
                3L,
                state.blueId(),
                "/child");

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // when
            OperationCall call = coordination.operations()
                    .on(DocumentId.of("consumer"))
                    .selectManagedEpoch(selector);

            // then
            assertEquals(List.of(selector), call.managedEpochSelectors());
            assertThrows(UnsupportedOperationException.class, () ->
                    call.managedEpochSelectors().add(selector));
            assertThrows(IllegalArgumentException.class, () ->
                    call.selectManagedEpoch(selector));
        }
    }

    @Test
    void boundedSdkDrainDelegatesToTheExistingEngineBudget() {
        // given
        DrainBudget budget = new DrainBudget(1L, 1L);

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // when
            DrainResult result = coordination.processing().drain(
                    budget);

            // then
            assertTrue(result.entries().isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () ->
                new DrainBudget(0L, 1L));
    }
}
