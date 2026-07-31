package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.repo.BlueRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MustUnderstandContractsTest {
    @Test
    void shouldStopInitializationForUnknownContractType() {
        // Given
        Fixture fixture = configuredFixture(false);
        String unknownType = "3nxchG67TRi4XrYFM2MTjj4LmuHNQzVv9NZLjATrPN19";
        Node document = document(fixture.repository, contract("unknown", new Node()
                .type(new Node().blueId(unknownType))));

        // When
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> initialize(fixture, document));

        // Then
        assertTrue(ex.getMessage().contains(unknownType), ex.getMessage());
    }

    @Test
    void shouldStopInitializationWhenBaseChannelIsExecutableContract() {
        // Given
        Fixture fixture = configuredFixture(false);
        Node document = document(fixture.repository, contract("owner", new Node().type("Channel")));

        // When
        DocumentProcessingResult result = initialize(fixture, document);

        // Then
        assertCapabilityFailure(result, "Unsupported contract type");
    }

    @Test
    void shouldSupportTimelineChannelUsedDirectly() {
        // Given
        Fixture fixture = configuredFixture(false);
        Node document = document(fixture.repository,
                contract("owner", TestTimelineProvider.channel("owner")));

        // When
        DocumentProcessingResult result = initialize(fixture, document);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(fixture.blue.isInitialized(result.document()));
    }

    @Test
    void shouldInitializeHandlerBoundToTimelineChannel() {
        // Given
        Fixture fixture = configuredFixture(false);
        Map<String, Node> contracts = contract("owner", TestTimelineProvider.channel("owner"));
        contracts.put("handler", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("owner"))
                .properties("steps", new Node().items()));
        Node document = document(fixture.repository, contracts);

        // When
        DocumentProcessingResult result = initialize(fixture, document);

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(fixture.blue.isInitialized(result.document()));
    }

    @Test
    void shouldFailClearlyForHandlerBoundToTypelessContract() {
        // Given
        Fixture fixture = configuredFixture(false);
        Map<String, Node> contracts = contract("owner", new Node()
                .properties("timelineId", new Node().value("owner")));
        contracts.put("handler", new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value("owner"))
                .properties("steps", new Node().items()));
        Node document = document(fixture.repository, contracts);

        // When
        DocumentProcessingResult result = initialize(fixture, document);

        // Then
        assertCapabilityFailure(result, "must declare a type");
    }

    @Test
    void shouldUseRegisteredSimpleTimelineProvider() {
        // Given
        Fixture fixture = configuredFixture(true);
        Node document = document(fixture.repository, contract("owner", TestTimelineProvider.channel("owner")));
        Node initialized = initialize(fixture, document).document();

        // When
        DocumentProcessingResult result = fixture.blue.processDocument(initialized,
                TestTimelineProvider.timelineEntry(fixture.blue,
                        fixture.repository,
                        "owner",
                        1,
                        TestTimelineProvider.chatMessage("hello")));

        // Then
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(checkpointEvent(result.document(), "owner"));
    }

    private static DocumentProcessingResult initialize(Fixture fixture, Node document) {
        return fixture.blue.initializeDocument(fixture.blue.preprocess(document));
    }

    private static void assertCapabilityFailure(DocumentProcessingResult result, String reason) {
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertTrue(blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result).contains(reason), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(0L, result.totalGas());
        assertTrue(result.events().isEmpty());
        assertFalse(hasInitializedMarker(result.document()));
    }

    private static boolean hasInitializedMarker(Node document) {
        Node contracts = property(document, "contracts");
        return property(contracts, "initialized") != null;
    }

    private static Node checkpointEvent(Node document, String key) {
        Node contracts = property(document, "contracts");
        Node checkpoint = property(contracts, "checkpoint");
        Node entries = property(checkpoint, "entries");
        Node entry = property(entries, key);
        return property(entry, "subject");
    }

    private static Map<String, Node> contract(String key, Node contract) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(key, contract);
        return contracts;
    }

    private static Node document(BlueRepository repository, Map<String, Node> contracts) {
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Must Understand Test")
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node property(Node node, String key) {
        if (node == null) {
            return null;
        }
        if ("contracts".equals(key)) {
            return node.getContracts();
        }
        if (node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    private static Fixture configuredFixture(boolean simpleTimelineProvider) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        if (simpleTimelineProvider) {
            TestTimelineProvider.registerWith(blue);
        }
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
