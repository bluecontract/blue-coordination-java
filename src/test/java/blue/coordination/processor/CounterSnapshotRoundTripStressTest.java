package blue.coordination.processor;

import blue.coordination.processor.CoordinationProcessors;
import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.TimelineChannel;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterSnapshotRoundTripStressTest {
    private static final int STRESS_ITERATIONS = 100;

    @Test
    void bexOnlyCounterUpdatesSurviveCanonicalSnapshotRoundTrips() {
        Fixture fixture = configuredFixture();
        DocumentProcessingResult initialized = fixture.blue.initializeDocument(
                fixture.blue.preprocess(bexOnlyCounterDocument(fixture.counterIncrementHandlerBlueId)
                        .blue(fixture.repository.typeAliasBlue())));
        ResolvedSnapshot currentSnapshot =
                ProcessingResultTestSupport.snapshot(fixture.blue, initialized);
        assertNotNull(currentSnapshot);

        long started = System.nanoTime();
        long totalGas = 0L;
        long maxGas = 0L;
        long minGas = Long.MAX_VALUE;
        String finalBlueId = null;

        for (int i = 1; i <= STRESS_ITERATIONS; i++) {
            Node event = timelineEntry(fixture.blue,
                    fixture.repository,
                    "counter",
                    i,
                    chatMessage("tick " + i));
            if (i > 1) {
                Node previousSubject = currentSnapshot.resolvedNodeAt(
                        "/contracts/checkpoint/entries/ownerChannel/subject");
                CoordinationEventNodes.TimelineEntryView currentEntry =
                        CoordinationEventNodes.timelineEntry(event);
                assertNotNull(previousSubject);
                assertNotNull(currentEntry);
                assertEquals(
                        TimelineExternalSubscriptionFunctions
                                .TIMELINE_ORDER_SUBJECT_VERSION,
                        TimelineProviderSupport.textProperty(
                                previousSubject, "semantics"));
                Node previousTimestamp =
                        TimelineProviderSupport.property(
                                previousSubject, "timestamp");
                assertNotNull(previousTimestamp);
                assertTrue(currentEntry.timestamp().compareTo(
                        (BigInteger) previousTimestamp.getValue()) > 0);
                TimelineChannel channel = fixture.blue.nodeToObject(
                        currentSnapshot.resolvedNodeAt("/contracts/ownerChannel"),
                        TimelineChannel.class);
                assertTrue(TimelineProviderSupport.matchesTimelineAndActor(channel, currentEntry));
            }

            DocumentProcessingResult result = fixture.blue.processDocument(currentSnapshot, event);

            ResolvedSnapshot resultSnapshot =
                    ProcessingResultTestSupport.snapshot(fixture.blue, result);
            String resultBlueId = ProcessingResultTestSupport.blueId(result);
            assertNotNull(resultSnapshot,
                    "iteration " + i + " should return a snapshot");
            assertNotNull(resultBlueId,
                    "iteration " + i + " should return a BlueId");
            assertTrue(result.totalGas() > 0, "iteration " + i + " should charge gas");
            assertEquals(1, result.events().size(), "iteration " + i + " should emit one event");
            assertEquals(BigInteger.valueOf(i),
                    ProcessingResultTestSupport.resolvedDocument(
                            fixture.blue, result).get("/counter"));
            assertCounterMessage(result.events().get(0), i);

            totalGas += result.totalGas();
            maxGas = Math.max(maxGas, result.totalGas());
            minGas = Math.min(minGas, result.totalGas());
            finalBlueId = resultBlueId;

            String canonicalJson = fixture.blue.nodeToJson(result.document());
            Fixture coldFixture = configuredFixture();
            Node parsedCanonical = coldFixture.blue.parseSourceJson(canonicalJson);
            ResolvedSnapshot loadedSnapshot = coldFixture.blue.loadSnapshot(parsedCanonical);

            assertEquals(resultBlueId, loadedSnapshot.blueId(),
                    "iteration " + i + " should preserve BlueId");
            assertSnapshotRoundTrip(resultSnapshot, loadedSnapshot);
            currentSnapshot = loadedSnapshot;
            fixture = coldFixture;
        }

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(BigInteger.valueOf(STRESS_ITERATIONS), currentSnapshot.resolvedNodeAt("/counter").getValue());
        assertNotNull(finalBlueId);
        assertTrue(totalGas > 0);
        assertTrue(maxGas > 0);
        assertTrue(minGas > 0);
        assertEquals(minGas, maxGas, "equivalent BEX-only increments should charge stable gas");

        System.out.println("BEX-only counter snapshot round-trip stress: iterations=" + STRESS_ITERATIONS
                + ", totalGas=" + totalGas
                + ", minGas=" + minGas
                + ", maxGas=" + maxGas
                + ", finalBlueId=" + finalBlueId
                + ", elapsedMillis=" + elapsedMillis);
    }

    private static void assertSnapshotRoundTrip(ResolvedSnapshot expected, ResolvedSnapshot actual) {
        assertEquals(expected.blueId(), actual.blueId());
        assertEquals(expected.frozenCanonicalRoot().blueId(),
                actual.frozenCanonicalRoot().blueId());
    }

    private static Node bexOnlyCounterDocument(String counterIncrementHandlerBlueId) {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("ownerChannel", timelineChannel("counter"));
        contracts.put("incrementImpl", new Node()
                .type(new Node().blueId(counterIncrementHandlerBlueId))
                .properties("channel", new Node().value("ownerChannel")));

        return new Node()
                .name("Counter")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node timelineChannel(String timelineId) {
        return new Node()
                .type(TimelineChannel.qualifiedName())
                .properties("timeline", timeline(timelineId))
                .properties("actor", principalActor());
    }

    private static Node timelineEntry(Blue blue,
                                      BlueRepository repository,
                                      String timelineId,
                                      int timestamp,
                                      Node message) {
        Node event = new Node()
                .type("Coordination/Timeline Entry")
                .properties("timeline", timeline(timelineId))
                .properties("actor", principalActor())
                .properties("timestamp", new Node().value(BigInteger.valueOf(timestamp)))
                .properties("message", message)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(event).blue(null);
    }

    private static Node timeline(String timelineId) {
        return new Node()
                .type("Coordination/Timeline")
                .properties("providerId", new Node().value("test-provider"))
                .properties("timelineId", new Node().value(timelineId));
    }

    private static Node principalActor() {
        return new Node().type("Coordination/Principal Actor");
    }

    private static Node chatMessage(String message) {
        return new Node()
                .type(ChatMessage.qualifiedName())
                .properties("message", new Node().value(message));
    }

    private static void assertCounterMessage(Node event, int counter) {
        assertEquals("Counter is now " + counter, event.get("/message"));
    }

    private static Fixture configuredFixture() {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        NodeProvider repositoryProvider = blue.getNodeProvider();
        Node counterIncrementHandlerType = new Node().name("Counter Increment Handler");
        BasicNodeProvider testTypes = new BasicNodeProvider();
        testTypes.addSingleNodes(counterIncrementHandlerType);
        String counterIncrementHandlerBlueId = testTypes.getBlueIdByName(
                "Counter Increment Handler");
        blue.nodeProvider(new SequentialNodeProvider(
                testTypes, repositoryProvider));
        CoordinationProcessors.registerWith(blue);
        blue.registerExternalContractType(counterIncrementHandlerBlueId,
                counterIncrementHandlerType,
                new CounterIncrementHandlerProcessor());
        return new Fixture(repository, blue, counterIncrementHandlerBlueId);
    }

    public static final class CounterIncrementHandler extends HandlerContract {
    }

    public static final class CounterIncrementHandlerProcessor
            implements HandlerProcessor<CounterIncrementHandler> {

        @Override
        public Class<CounterIncrementHandler> contractType() {
            return CounterIncrementHandler.class;
        }

        @Override
        public void execute(CounterIncrementHandler contract, ProcessorExecutionContext context) {
            Node current = context.documentAt(context.resolvePointer("/counter"));
            int value = ((Number) current.getValue()).intValue();
            int next = value + 1;

            context.applyPatch(JsonPatch.replace(
                    context.resolvePointer("/counter"),
                    new Node().value(next)));

            context.emitEvent(new Node()
                    .type(new Node().blueId(ChatMessage.blueId()))
                    .properties("message", new Node().value("Counter is now " + next)));
        }
    }

    private static final class Fixture {
        private final BlueRepository repository;
        private final Blue blue;
        private final String counterIncrementHandlerBlueId;

        private Fixture(BlueRepository repository, Blue blue, String counterIncrementHandlerBlueId) {
            this.repository = repository;
            this.blue = blue;
            this.counterIncrementHandlerBlueId = counterIncrementHandlerBlueId;
        }
    }
}
