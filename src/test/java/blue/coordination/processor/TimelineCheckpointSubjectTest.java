package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineCheckpointSubjectTest {

    @Test
    void absentAndEmptyRequestsKeepDistinctSubjectsAtSameTimelinePosition() {
        // given
        Node absentEntry = timelineEntry(10, false);
        Node emptyEntry = timelineEntry(10, true);
        Node absentSubject = TimelineProviderSupport.timelineOrderSubject(
                CoordinationEventNodes.timelineEntry(absentEntry));
        Node emptySubject = TimelineProviderSupport.timelineOrderSubject(
                CoordinationEventNodes.timelineEntry(emptyEntry));

        // when
        boolean newerAtSamePosition =
                TimelineProviderSupport.isNewerOrSameTimelineEvent(
                ChannelCheckpointContext.of(
                        "/",
                        "timeline",
                        emptyEntry,
                        TimelineProviderSupport.eventId(emptyEntry),
                        emptySubject,
                        absentSubject,
                        TimelineProviderSupport.eventId(absentEntry),
                        Collections.emptyMap()));

        // then
        assertNotEquals(
                TimelineProviderSupport.eventId(absentEntry),
                TimelineProviderSupport.eventId(emptyEntry));
        assertEquals(TimelineProviderSupport.eventId(absentEntry),
                absentSubject.getAsText("/entryBlueId"));
        assertEquals(TimelineProviderSupport.eventId(emptyEntry),
                emptySubject.getAsText("/entryBlueId"));
        assertFalse(newerAtSamePosition,
                "exact identity remains distinct while direct Timeline "
                        + "newness independently requires a greater timestamp");
    }

    @Test
    void increasingEmptyRequestRemainsNewerThanAbsentRequest() {
        // given
        Node absentEntry = timelineEntry(10, false);
        Node emptyEntry = timelineEntry(11, true);
        Node absentSubject = TimelineProviderSupport.timelineOrderSubject(
                CoordinationEventNodes.timelineEntry(absentEntry));
        Node emptySubject = TimelineProviderSupport.timelineOrderSubject(
                CoordinationEventNodes.timelineEntry(emptyEntry));

        // when
        boolean newer = TimelineProviderSupport.isNewerOrSameTimelineEvent(
                ChannelCheckpointContext.of(
                        "/",
                        "timeline",
                        emptyEntry,
                        TimelineProviderSupport.eventId(emptyEntry),
                        emptySubject,
                        absentSubject,
                        TimelineProviderSupport.eventId(absentEntry),
                        Collections.emptyMap()));

        // then
        assertTrue(newer);
    }

    @Test
    void shouldAcceptIncreasingTimestampForDirectTimeline() {
        // given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(11, "entry-b"),
                        directSubject(10, "entry-a")));

        // then
        assertTrue(newer);
    }

    @Test
    void shouldRejectEqualTimestampForDirectTimeline() {
        // given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(10, "entry-z"),
                        directSubject(10, "entry-a")));

        // then
        assertFalse(newer);
    }

    @Test
    void shouldRejectBackdatedEntryForDirectTimeline() {
        // given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(9, "entry-z"),
                        directSubject(10, "entry-a")));

        // then
        assertFalse(newer);
    }

    @Test
    void shouldConsumeVerifiedPlatformOrderAcrossDifferentTimelines() {
        // given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                9, "timeline-a",
                                "entry-a", "a", "domain-a"),
                        compositeSubject(
                                10, "timeline-z",
                                "entry-z", "z", "domain-z")));

        // then
        assertTrue(newer);
    }

    @Test
    void shouldAcceptIncreasingTimestampWithinSameTimelineWhenMemberChanges() {
        // given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                11, "timeline-a",
                                "entry-b", "b", "domain-b"),
                        compositeSubject(
                                10, "timeline-a",
                                "entry-a", "a", "domain-a")));

        // then
        assertTrue(newer);
    }

    @Test
    void shouldRejectEqualTimestampWithinSameTimelineWhenMemberChanges() {
        // given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                10, "timeline-a",
                                "entry-b", "b", "domain-b"),
                        compositeSubject(
                                10, "timeline-a",
                                "entry-a", "a", "domain-a")));

        // then
        assertFalse(newer);
    }

    @Test
    void shouldRejectBackdatedEntryWithinSameTimelineWhenMemberChanges() {
        // given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // when
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                9, "timeline-z",
                                "entry-z", "z", "domain-z"),
                        compositeSubject(
                                10, "timeline-z",
                                "entry-a", "a", "domain-a")));

        // then
        assertFalse(newer);
    }

    @Test
    void shouldEnsureThatAllTimelinesRejectsMalformedStoredOrderSubject() {
        // given
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();
        // when
        Node malformed = new Node()
                .properties("semantics", new Node().value(
                        AllTimelinesExternalSubscriptionFunctions
                                .ORDER_SUBJECT_VERSION));

        // then
        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(
                                        10, "timeline-a", "entry-a",
                                        "a", "domain"),
                                malformed)));
    }

    @Test
    void shouldEnsureThatAggregateSubjectsRejectEmptyMemberLineage() {
        // given
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();

        // when
        IllegalArgumentException emptyMember = assertThrows(
                IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(
                                        10, "timeline-a", "entry-a",
                                        "", "domain"),
                                null)));
        IllegalArgumentException emptyDomain = assertThrows(
                IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(
                                        10, "timeline-a", "entry-a",
                                        "member", ""),
                                null)));

        // then
        assertFalse(emptyMember.getMessage().isBlank());
        assertFalse(emptyDomain.getMessage().isBlank());
    }

    private static ChannelCheckpointContext context(Node current,
                                                       Node previous) {
        return ChannelCheckpointContext.of(
                "/",
                "timeline",
                new Node().properties(
                        "rawTimelineExtension",
                        new Node().value("ignored")),
                "current-signature",
                current,
                previous,
                "previous-signature",
                Collections.emptyMap());
    }

    private static Node timelineEntry(long timestamp,
                                      boolean emptyRequest) {
        Node message = new Node()
                .properties("operation", new Node().value("touch"))
                .properties("channel", new Node().value("ownerChannel"));
        if (emptyRequest) {
            message.properties("request", new Node());
        }
        return new Node()
                .type(new Node().blueId(
                        blue.repo.coordination.TimelineEntry.blueId()))
                .properties("timeline", new Node().properties(
                        "timelineId", new Node().value("timeline-a")))
                .properties("timestamp", new Node().value(
                        BigInteger.valueOf(timestamp)))
                .properties("actor", new Node().properties(
                        "accountId", new Node().value("alice")))
                .properties("message", message);
    }

    private static Node directSubject(long timestamp,
                                      String entryBlueId) {
        return subject(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                timestamp,
                "timeline-a",
                entryBlueId,
                null,
                null);
    }

    private static Node compositeSubject(long timestamp,
                                         String timelineBlueId,
                                         String entryBlueId,
                                         String memberKey,
                                         String memberDomain) {
        return subject(
                CompositeTimelineExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                timestamp,
                timelineBlueId,
                entryBlueId,
                memberKey,
                memberDomain);
    }

    private static Node allSubject(long timestamp,
                                   String timelineBlueId,
                                   String entryBlueId,
                                   String memberKey,
                                   String memberDomain) {
        return subject(
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                timestamp,
                timelineBlueId,
                entryBlueId,
                memberKey,
                memberDomain);
    }

    private static Node subject(String semantics,
                                long timestamp,
                                String timelineBlueId,
                                String entryBlueId,
                                String memberKey,
                                String memberDomain) {
        Node subject = new Node()
                .properties("semantics",
                        new Node().value(semantics))
                .properties("timestamp",
                        new Node().value(
                                BigInteger.valueOf(timestamp)))
                .properties("timelineBlueId",
                        new Node().value(timelineBlueId))
                .properties("entryBlueId",
                        new Node().value(entryBlueId));
        if (memberKey != null) {
            subject.properties("memberKey",
                    new Node().value(memberKey));
        }
        if (memberDomain != null) {
            subject.properties("memberDomain",
                    new Node().value(memberDomain));
        }
        return subject;
    }
}
