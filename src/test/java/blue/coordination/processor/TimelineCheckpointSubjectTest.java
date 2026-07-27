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
    void directTimelineOrdersOnlyByExactIntegerTimestamp() {
        TimelineChannelProcessor processor =
                new TimelineChannelProcessor();

        assertTrue(processor.isNewerEvent(
                new TimelineChannel(),
                context(directSubject(11), directSubject(10))));
        assertFalse(processor.isNewerEvent(
                new TimelineChannel(),
                context(directSubject(10), directSubject(10))));
        assertFalse(processor.isNewerEvent(
                new TimelineChannel(),
                context(directSubject(9), directSubject(10))));
    }

    @Test
    void compositeTreatsEachFrozenMemberLineageAsAnIndependentSource() {
        CompositeTimelineChannelProcessor processor =
                new CompositeTimelineChannelProcessor();

        assertTrue(processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(10, "b", "domain"),
                        compositeSubject(10, "a", "domain"))));
        assertTrue(processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(10, "a", "domain"),
                        compositeSubject(10, "b", "domain"))));
        assertTrue(processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(9, "a", "other-domain"),
                        compositeSubject(10, "b", "domain"))));
        assertFalse(processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(10, "a", "domain"),
                        compositeSubject(10, "a", "domain"))));
        assertFalse(processor.isNewerEvent(
                new CompositeTimelineChannel(),
                context(compositeSubject(9, "a", "domain"),
                        compositeSubject(10, "a", "domain"))));
    }

    @Test
    void allTimelinesRejectsMalformedStoredOrderSubject() {
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();
        Node malformed = new Node()
                .properties("semantics", new Node().value(
                        AllTimelinesExternalSubscriptionFunctions
                                .ORDER_SUBJECT_VERSION));

        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(10, "a", "domain"),
                                malformed)));
    }

    @Test
    void aggregateSubjectsRejectEmptyMemberLineage() {
        AllTimelinesChannelProcessor processor =
                new AllTimelinesChannelProcessor();

        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(10, "", "domain"), null)));
        assertThrows(IllegalArgumentException.class,
                () -> processor.isNewerEvent(
                        new AllTimelinesChannel(),
                        context(allSubject(10, "member", ""), null)));
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

    private static Node directSubject(long timestamp) {
        return subject(
                TimelineExternalSubscriptionFunctions
                        .TIMELINE_ORDER_SUBJECT_VERSION,
                timestamp,
                null,
                null);
    }

    private static Node compositeSubject(long timestamp,
                                         String memberKey,
                                         String memberDomain) {
        return subject(
                CompositeTimelineExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                timestamp,
                memberKey,
                memberDomain);
    }

    private static Node allSubject(long timestamp,
                                   String memberKey,
                                   String memberDomain) {
        return subject(
                AllTimelinesExternalSubscriptionFunctions
                        .ORDER_SUBJECT_VERSION,
                timestamp,
                memberKey,
                memberDomain);
    }

    private static Node subject(String semantics,
                                long timestamp,
                                String memberKey,
                                String memberDomain) {
        Node subject = new Node()
                .properties("semantics",
                        new Node().value(semantics))
                .properties("timestamp",
                        new Node().value(
                                BigInteger.valueOf(timestamp)));
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
