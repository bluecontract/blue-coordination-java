package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.repo.BlueRepository;
import blue.repo.coordination.Actor;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.PrincipalActor;
import java.math.BigInteger;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishedTimelineChannelResolutionTest {
    private static final String CHANNEL_YAML = String.join("\n",
            "type: Coordination/Timeline Channel",
            "timeline:",
            "  type: Coordination/Timeline",
            "  providerId: test-provider",
            "  timelineId: timeline-1",
            "actor:",
            "  type: MyOS/Principal Actor",
            "  accountId: account-1");

    @Test
    void shouldEnsureThatPublishedMaterializedTimelineChannelResolves() {
        // Given
        Fixture fixture = fixture(false);

        // When
        Node resolved = fixture.blue.resolve(fixture.blue.preprocess(
                authoredChannel(fixture.blue).blue(fixture.repository.typeAliasBlue())));

        // Then
        assertResolvedBinding(fixture, resolved);
    }

    @Test
    void shouldEnsureThatPublishedMaterializedTimelineChannelInitializesAsContract() {
        // Given
        Fixture fixture = fixture(false);

        // When
        DocumentProcessingResult result = fixture.blue.initializeDocument(
                fixture.blue.preprocess(document(fixture)));

        // Then
        assertSuccessfulSnapshot(fixture, result);
        assertResolvedBinding(fixture, result.document().getAsNode("/contracts/timeline"));
    }

    @Test
    void shouldEnsureThatPublishedTimelineEntryRecursiveTypeResolvesFinitely() {
        // Given
        Fixture fixture = fixture(false);
        Node first = timelineEntry(
                fixture.blue,
                BigInteger.ONE,
                "first");
        String firstBlueId =
                TimelineProviderSupport.eventId(first);

        // When
        Node resolved = fixture.blue.resolve(
                timelineEntry(
                        fixture.blue,
                        BigInteger.valueOf(2),
                        "finite")
                        .properties(
                                "prevEntry",
                                new Node().blueId(
                                        firstBlueId)));

        // Then
        assertFinitePrevEntryBoundary(resolved);
    }

    @Test
    void shouldEnsureThatPublishedCheckpointedTimelineEntrySurvivesClonedDocumentRebuild() {
        // Given
        Fixture fixture = fixture(true);
        Node initialized = fixture.blue.initializeDocument(
                fixture.blue.preprocess(document(fixture))).document();
        Node firstEntry = timelineEntry(
                fixture.blue,
                BigInteger.ONE,
                "first");

        // When
        DocumentProcessingResult first =
                fixture.blue.processDocument(
                        initialized,
                        firstEntry);

        // Then
        assertSuccessfulSnapshot(fixture, first);
        assertCheckpoint(first.document(), BigInteger.ONE);

        String firstBlueId =
                TimelineProviderSupport.eventId(firstEntry);
        Node secondEntry = timelineEntry(
                fixture.blue,
                BigInteger.valueOf(2),
                "second")
                .properties(
                        "prevEntry",
                        new Node().blueId(
                                firstBlueId));
        DocumentProcessingResult second =
                fixture.blue.processDocument(
                        first.document().clone(),
                        secondEntry);

        assertSuccessfulSnapshot(fixture, second);
        assertCheckpoint(second.document(), BigInteger.valueOf(2));
        assertFinitePrevEntryBoundary(
                fixture.blue.resolve(secondEntry));
    }

    private static Fixture fixture(boolean timelineProcessorOnly) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = repository.configure(new Blue());
        if (timelineProcessorOnly) {
            blue.registerContractProcessor(TimelineChannel.blueId(),
                    new TimelineChannelProcessor());
        } else {
            CoordinationProcessors.registerWith(blue);
        }
        CoordinationDeliveryPlanning.currentRootCompatibility(
                blue);
        return new Fixture(repository, blue);
    }

    private static Node document(Fixture fixture) {
        return new Node()
                .blue(fixture.repository.typeAliasBlue())
                .properties("contracts", new Node().properties(Collections.singletonMap(
                        "timeline", authoredChannel(fixture.blue))));
    }

    private static Node authoredChannel(Blue blue) {
        return blue.parseSourceYaml(CHANNEL_YAML);
    }

    private static Node timelineEntry(Blue blue, BigInteger timestamp, String message) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId("timeline-1"))
                .actor(new PrincipalActor().accountId("account-1"))
                .timestamp(timestamp)
                .message(blue.objectToNode(new ChatMessage().message(message)));
        return blue.preprocess(blue.objectToNode(entry));
    }

    private static void assertSuccessfulSnapshot(Fixture fixture,
                                                 DocumentProcessingResult result) {
        assertFalse(blue.coordination.processor.ProcessingResultTestSupport.isCapabilityFailure(result), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(ProcessorStatus.SUCCESS, result.status(), blue.coordination.processor.ProcessingResultTestSupport.diagnosticMessage(result));
        assertNotNull(ProcessingResultTestSupport.snapshot(fixture.blue, result));
        assertEquals(ProcessingResultTestSupport.blueId(result),
                ProcessingResultTestSupport.snapshot(fixture.blue, result)
                        .frozenCanonicalRoot().blueId());
    }

    private static void assertCheckpoint(
            Node document,
            BigInteger timestamp) {
        Node subject = document.getAsNode(
                "/contracts/checkpoint/entries/timeline/subject");
        assertNotNull(subject);
        assertEquals(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                subject.getAsText("/semantics"));
        assertEquals(timestamp, subject.get("/timestamp"));
        assertNotNull(subject.getAsText("/timelineBlueId"));
        assertNotNull(subject.getAsText("/entryBlueId"));
    }

    private static void assertFinitePrevEntryBoundary(
            Node resolvedTimelineEntry) {
        Node prevEntry = resolvedTimelineEntry.getAsNode("/prevEntry");
        assertNotNull(prevEntry);
        /*
         * TimelineEntry.prevEntry is deliberately untyped in the published
         * repository model. The resolved lane therefore retains only its
         * field metadata; it must not recursively expand the referenced
         * history. Exact reference identity remains an authored/canonical
         * concern and is covered before this explicit resolve boundary.
         */
        assertNull(prevEntry.getType());
        assertNull(prevEntry.getProperties());
        assertNull(prevEntry.getItems());
        assertNull(prevEntry.getContracts());
        assertNull(prevEntry.getValue());
    }

    private static void assertResolvedBinding(Fixture fixture, Node channel) {
        assertNotNull(channel);
        assertEquals("timeline-1", channel.getAsText("/timeline/timelineId"));
        assertEquals("account-1", channel.getAsText("/actor/accountId"));
        Node actorType = fixture.repository.nodeByName(Actor.qualifiedName())
                .orElseThrow(() -> new AssertionError("Published repository is missing Coordination/Actor"));
        assertTrue(fixture.blue.nodeMatchesType(channel.getAsNode("/actor"), actorType));
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
