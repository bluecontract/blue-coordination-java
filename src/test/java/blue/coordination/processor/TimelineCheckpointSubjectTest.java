package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.repo.coordination.AllTimelinesChannel;
import blue.repo.coordination.CompositeTimelineChannel;
import blue.repo.coordination.TimelineChannel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimelineCheckpointSubjectTest {

    @Test
    void shouldAcceptIncreasingTimestampForDirectTimeline() {
        // Given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(11, "entry-b"),
                        directSubject(10, "entry-a")));

        // Then
        assertTrue(newer);
    }

    @Test
    void shouldRejectEqualTimestampForDirectTimeline() {
        // Given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(10, "entry-z"),
                        directSubject(10, "entry-a")));

        // Then
        assertFalse(newer);
    }

    @Test
    void shouldRejectBackdatedEntryForDirectTimeline() {
        // Given
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new TimelineChannel(),
                context(
                        directSubject(9, "entry-z"),
                        directSubject(10, "entry-a")));

        // Then
        assertFalse(newer);
    }

    @Test
    void shouldConsumeVerifiedPlatformOrderAcrossDifferentTimelines() {
        // Given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                9, "timeline-a",
                                "entry-a", "a", "domain-a"),
                        compositeSubject(
                                10, "timeline-z",
                                "entry-z", "z", "domain-z")));

        // Then
        assertTrue(newer);
    }

    @Test
    void shouldAcceptIncreasingTimestampWithinSameTimelineWhenMemberChanges() {
        // Given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                11, "timeline-a",
                                "entry-b", "b", "domain-b"),
                        compositeSubject(
                                10, "timeline-a",
                                "entry-a", "a", "domain-a")));

        // Then
        assertTrue(newer);
    }

    @Test
    void shouldRejectEqualTimestampWithinSameTimelineWhenMemberChanges() {
        // Given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                10, "timeline-a",
                                "entry-b", "b", "domain-b"),
                        compositeSubject(
                                10, "timeline-a",
                                "entry-a", "a", "domain-a")));

        // Then
        assertFalse(newer);
    }

    @Test
    void shouldRejectBackdatedEntryWithinSameTimelineWhenMemberChanges() {
        // Given
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        // When
        boolean newer = processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(
                                9, "timeline-z",
                                "entry-z", "z", "domain-z"),
                        compositeSubject(
                                10, "timeline-z",
                                "entry-a", "a", "domain-a")));

        // Then
        assertFalse(newer);
    }

    @Test
    void shouldEnsureThatAllTimelinesRejectsMalformedStoredOrderSubject() {
        // Given
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();
        // When
        Node malformed = new Node()
                .properties("semantics", new Node().value(
                        AllTimelinesExternalSubscriptionFunctions
                                .ORDER_SUBJECT_VERSION));

        // Then
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
        // Given
        // When
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();

        // Then
        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(
                                        10, "timeline-a", "entry-a",
                                        "", "domain"),
                                null)));
        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(
                                        10, "timeline-a", "entry-a",
                                        "member", ""),
                                null)));
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
