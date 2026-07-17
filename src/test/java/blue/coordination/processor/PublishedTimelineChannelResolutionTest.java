package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
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
    void publishedMaterializedTimelineChannelResolves() {
        Fixture fixture = fixture(false);

        Node resolved = fixture.blue.resolve(fixture.blue.preprocess(
                authoredChannel(fixture.blue).blue(fixture.repository.typeAliasBlue())));

        assertResolvedBinding(fixture, resolved);
    }

    @Test
    void publishedMaterializedTimelineChannelInitializesAsContract() {
        Fixture fixture = fixture(false);

        DocumentProcessingResult result = fixture.blue.initializeDocument(
                fixture.blue.preprocess(document(fixture)));

        assertSuccessfulSnapshot(result);
        assertResolvedBinding(fixture, result.document().getAsNode("/contracts/timeline"));
    }

    @Test
    void publishedTimelineEntryRecursiveTypeResolvesFinitely() {
        Fixture fixture = fixture(false);

        Node resolved = fixture.blue.resolve(timelineEntry(fixture.blue, BigInteger.ONE, "finite"));

        assertFinitePrevEntryBoundary(resolved);
    }

    @Test
    void publishedCheckpointedTimelineEntrySurvivesClonedDocumentRebuild() {
        Fixture fixture = fixture(true);
        Node initialized = fixture.blue.initializeDocument(
                fixture.blue.preprocess(document(fixture))).document();

        DocumentProcessingResult first = fixture.blue.processDocument(initialized,
                timelineEntry(fixture.blue, BigInteger.ONE, "first"));

        assertSuccessfulSnapshot(first);
        assertCheckpoint(first.document(), BigInteger.ONE, "first");

        DocumentProcessingResult second = fixture.blue.processDocument(first.document().clone(),
                timelineEntry(fixture.blue, BigInteger.valueOf(2), "second"));

        assertSuccessfulSnapshot(second);
        assertCheckpoint(second.document(), BigInteger.valueOf(2), "second");
        assertFinitePrevEntryBoundary(fixture.blue.resolve(
                timelineEntry(fixture.blue, BigInteger.valueOf(2), "second")));
    }

    private static Fixture fixture(boolean alwaysMatchingProcessor) {
        BlueRepository repository = BlueRepository.latest();
        Blue blue = repository.configure(new Blue());
        if (alwaysMatchingProcessor) {
            blue.registerContractProcessor(TimelineChannel.blueId(),
                    new AlwaysMatchingTimelineChannelProcessor());
        } else {
            CoordinationProcessors.registerWith(blue);
        }
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

    private static void assertSuccessfulSnapshot(DocumentProcessingResult result) {
        assertFalse(result.capabilityFailure(), result.failureReason());
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
        assertNotNull(result.snapshot());
        assertEquals(result.snapshot().blueId(), result.snapshot().frozenCanonicalRoot().blueId());
    }

    private static void assertCheckpoint(Node document, BigInteger timestamp, String message) {
        Node event = document.getAsNode("/contracts/checkpoint/lastEvents/timeline");
        assertNotNull(event);
        assertNotNull(event.getType());
        assertTrue(event.getType().isReferenceOnly());
        assertEquals(TimelineEntry.blueId(), event.getType().getBlueId());
        assertEquals("timeline-1", event.getAsText("/timeline/timelineId"));
        assertEquals("account-1", event.getAsText("/actor/accountId"));
        assertFalse(event.getProperties().containsKey("sequence"));
        assertEquals(timestamp, event.get("/timestamp"));
        assertEquals(message, event.getAsText("/message/message"));
    }

    private static void assertFinitePrevEntryBoundary(Node resolvedTimelineEntry) {
        Node prevEntry = resolvedTimelineEntry.getAsNode("/prevEntry");
        assertNotNull(prevEntry);
        Node prevEntryType = prevEntry.getType();
        assertNotNull(prevEntryType);
        assertTrue(prevEntryType.isReferenceOnly());
        assertEquals(TimelineEntry.blueId(), prevEntryType.getBlueId());
        assertNull(prevEntryType.getProperties());
        assertNull(prevEntryType.getItems());
        assertNull(prevEntryType.getType());
    }

    private static void assertResolvedBinding(Fixture fixture, Node channel) {
        assertNotNull(channel);
        assertEquals("timeline-1", channel.getAsText("/timeline/timelineId"));
        assertEquals("account-1", channel.getAsText("/actor/accountId"));
        Node actorType = fixture.repository.nodeByName(Actor.qualifiedName())
                .orElseThrow(() -> new AssertionError("Published repository is missing Coordination/Actor"));
        assertTrue(fixture.blue.nodeMatchesType(channel.getAsNode("/actor"), actorType));
    }

    private static final class AlwaysMatchingTimelineChannelProcessor
            implements ChannelProcessor<TimelineChannel> {
        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public boolean matches(TimelineChannel contract, ChannelEvaluationContext context) {
            return true;
        }
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
