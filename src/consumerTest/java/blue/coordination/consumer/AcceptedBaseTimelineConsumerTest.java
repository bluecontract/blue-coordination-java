package blue.coordination.consumer;

import blue.coordination.api.Operation;
import blue.coordination.api.TimelineEntry;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.TimelineEntrySnapshot;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Accepted-base descriptors stay callable without inventing absent request content. */
final class AcceptedBaseTimelineConsumerTest {
    @Test
    void oldEntryConstructorAndAccessorPreservePresentExactRequest() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            var engine = blue.advanced().rawEngine();
            var timeline = engine.registerTimeline("compatibility/present", "alice");
            TimelineEntry source = engine.append(timeline, Operation.yaml("touch", "owner", "{}"));
            // when
            TimelineEntry reconstructed = new TimelineEntry(source.exactEvent(), source.exactRequest(),
                    source.journalOrderKey(), source.sourceOrderKey(), source.timeline(), source.operation(),
                    source.channel(), source.timestampMicros(), source.globalSequence(), source.timelineSequence());
            // then
            assertEquals(source, reconstructed);
            assertEquals(source.request().orElseThrow().blueId(), reconstructed.exactRequest().blueId());
        }
    }

    @Test
    void absentRequestDoesNotBecomeAnEmptyObjectThroughLegacyAccessor() {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            var engine = blue.advanced().rawEngine();
            var timeline = engine.registerTimeline("compatibility/absent", "alice");
            // when
            TimelineEntry absent = engine.append(timeline, Operation.withoutRequest("touch", "owner"));
            TimelineEntry empty = engine.append(timeline, Operation.yaml("touch", "owner", "{}"));
            // then
            assertTrue(absent.request().isEmpty());
            assertThrows(NoSuchElementException.class, absent::exactRequest);
            assertTrue(empty.request().isPresent());
            assertNotEquals(absent.blueId(), empty.blueId());
        }
    }

    @Test
    void oldSnapshotConstructorDerivesBothPresentAndAbsentRequestFromExactEnvelope() throws Exception {
        // given
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            var engine = blue.advanced().rawEngine();
            var timeline = engine.registerTimeline("compatibility/snapshot", "alice");
            engine.append(timeline, Operation.withoutRequest("touch", "owner"));
            engine.append(timeline, Operation.yaml("touch", "owner", "{}"));
            // when
            var sources = blue.advanced().auditTimelineEntries();
            var restored = sources.stream().map(source -> new TimelineEntrySnapshot(source.exact(),
                    source.timeline(), source.previousEntryBlueId(), source.operation(), source.channel(),
                    source.timestampMicros(), source.globalSequence(), source.timelineSequence())).toList();
            // then
            for (int i = 0; i < sources.size(); i++) {
                var source = sources.get(i);
                var copy = restored.get(i);
                assertEquals(source.exact(), copy.exact());
                assertEquals(source.timeline(), copy.timeline());
                assertEquals(source.previousEntryBlueId(), copy.previousEntryBlueId());
                assertEquals(source.operation(), copy.operation());
                assertEquals(source.channel(), copy.channel());
                assertEquals(source.timestampMicros(), copy.timestampMicros());
                assertEquals(source.globalSequence(), copy.globalSequence());
                assertEquals(source.timelineSequence(), copy.timelineSequence());
                assertEquals(source.request().map(v -> v.blueId()), copy.request().map(v -> v.blueId()));
            }
            // The legacy constructor owns no provider; it must preserve the exact envelope reference.
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            assertEquals(mapper.readTree("{\"blueId\":\"" + sources.get(1).request().orElseThrow().blueId() + "\"}"),
                    mapper.readTree(restored.get(1).request().orElseThrow().json()));
            assertTrue(restored.get(0).request().isEmpty());
            assertTrue(restored.get(1).request().isPresent());
        }
    }
}
