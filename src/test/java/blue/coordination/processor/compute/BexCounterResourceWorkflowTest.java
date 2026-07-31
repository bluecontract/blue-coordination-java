package blue.coordination.processor.compute;

import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Scenario:
 * A small YAML counter document proves the resource-based BEX workflow used by examples and smoke tests.
 *
 * Main flow:
 * 1. Load {@code coordination/counter-bex.yaml} from test resources.
 * 2. Initialize the document.
 * 3. Send one {@code increment} operation request with value 1 through a simple timeline channel.
 * 4. Assert that the document counter is incremented and a chat message is emitted.
 *
 * Actors and operations:
 * - The owner timeline calls {@code increment}.
 * - BEX compute mutates {@code /counter} from the returned changeset and emits the chat message.
 */
class BexCounterResourceWorkflowTest {
    private static final String COUNTER_RESOURCE = "/coordination/counter-bex.yaml";
    private static final String TIMELINE_ID = "counter-timeline";

    @Test
    void shouldProcessTimelineIncrementOperationWithBexCounterWorkflow() {
        // Given
        Fixture fixture = configuredFixture();
        Node document = CoordinationTestResources.yamlResource(fixture.blue, fixture.repository, COUNTER_RESOURCE);
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(document);
        Node event = CoordinationTestResources.operationRequestEvent(fixture.blue,
                fixture.repository,
                TIMELINE_ID,
                1700000001,
                "increment",
                "ownerChannel",
                new Node().value(1));

        // When
        DocumentProcessingResult result = fixture.blue.processDocument(initialized.document(), event);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(result.document());
        assertEquals(BigInteger.ONE, result.document().get("/counter"));
        assertEquals(1, result.events().size());
        assertEquals("Counter was incremented by 1 and is now 1",
                result.events().get(0).getAsText("/message"));
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        return new Fixture(repository, blue);
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;

        private Fixture(BlueRepository repository, Blue blue) {
            this.repository = repository;
            this.blue = blue;
        }
    }
}
