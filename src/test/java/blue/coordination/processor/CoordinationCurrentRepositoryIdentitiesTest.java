package blue.coordination.processor;

import blue.repo.coordination.Actor;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class CoordinationCurrentRepositoryIdentitiesTest {

    @Test
    void shouldExposeOnlyTheCurrentGeneratedIdentitySet() {
        // given
        CoordinationCurrentRepositoryIdentities ids =
                CoordinationCurrentRepositoryIdentities.current();

        // when
        CoordinationCurrentRepositoryIdentities secondRead =
                CoordinationCurrentRepositoryIdentities.current();
        CoordinationSemanticTypeIdentities semantic =
                CoordinationSemanticTypeIdentities.publishedDefaults();

        // then
        assertSame(ids, secondRead);
        assertEquals(TimelineEntry.blueId(), ids.timelineEntryBlueId());
        assertEquals(OperationRequest.blueId(), ids.operationRequestBlueId());
        assertEquals(Timeline.blueId(), ids.timelineBlueId());
        assertEquals(Actor.blueId(), ids.actorBlueId());
        assertEquals(TimelineChannel.blueId(), ids.timelineChannelBlueId());
        assertEquals(AllTimelinesChannel.blueId(),
                ids.allTimelinesChannelBlueId());
        assertEquals(CompositeTimelineChannel.blueId(),
                ids.compositeTimelineChannelBlueId());
        assertEquals(ids.timelineEntryBlueId(),
                semantic.timelineEntryBlueId());
        assertEquals(ids.operationRequestBlueId(),
                semantic.operationRequestBlueId());
        assertEquals(ids.timelineBlueId(), semantic.timelineBlueId());
        assertEquals(ids.actorBlueId(), semantic.actorBlueId());
        assertFalse(semantic.custom());
        assertEquals(
                new LinkedHashSet<String>(Arrays.asList(
                        "TimelineEntry",
                        "OperationRequest",
                        "Timeline",
                        "Actor",
                        "TimelineChannel",
                        "AllTimelinesChannel",
                        "CompositeTimelineChannel")),
                ids.asMap().keySet());
        assertFalse(ids.profileIdentity().isEmpty());
    }
}
