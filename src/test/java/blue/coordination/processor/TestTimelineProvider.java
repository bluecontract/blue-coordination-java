package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.MyOSPrincipalActor;

import java.math.BigInteger;

public final class TestTimelineProvider {
    private TestTimelineProvider() {
    }

    public static Blue registerWith(Blue blue) {
        blue.registerContractProcessor(TimelineChannel.blueId(), new SimpleTimelineChannelProcessor());
        return blue;
    }

    public static Node channel(String timelineId) {
        return channel(timelineId, timelineId);
    }

    public static Node channel(String timelineId, String actorId) {
        Node channel = new Node().type(TimelineChannel.qualifiedName());
        if (timelineId != null) {
            channel.properties("timeline", new Node()
                    .type(Timeline.qualifiedName())
                    .properties("timelineId", new Node().value(timelineId)));
        }
        if (actorId != null) {
            channel.properties("actor", new Node()
                    .type(MyOSPrincipalActor.qualifiedName())
                    .properties("accountId", new Node().value(actorId)));
        }
        return channel;
    }

    public static Node timelineEntry(Blue blue,
                                     BlueRepository repository,
                                     String timelineId,
                                     int timestamp,
                                     Node message) {
        return timelineEntry(blue,
                repository,
                timelineId,
                timelineId,
                BigInteger.valueOf(timestamp),
                BigInteger.valueOf(timestamp),
                message);
    }

    public static Node timelineEntry(Blue blue,
                                     BlueRepository repository,
                                     String timelineId,
                                     String actorId,
                                     BigInteger sequence,
                                     BigInteger timestamp,
                                     Node message) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId(timelineId))
                .actor(new MyOSPrincipalActor().accountId(actorId))
                .sequence(sequence)
                .timestamp(timestamp);

        Node event = blue.objectToNode(entry)
                .properties("sequence", new Node().value(sequence))
                .properties("timestamp", new Node().value(timestamp))
                .properties("message", message)
                .blue(repository.typeAliasBlue());
        return blue.preprocess(event).blue(null);
    }

    public static Node chatMessage(String message) {
        ChatMessage chatMessage = new ChatMessage().message(message);
        return new Node()
                .type(ChatMessage.qualifiedName())
                .properties("message", new Node().value(chatMessage.getMessage()));
    }

    public static final class SimpleTimelineChannelProcessor implements ChannelProcessor<TimelineChannel> {
        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ChannelEvaluation evaluate(TimelineChannel contract, ChannelEvaluationContext context) {
            return TimelineProviderSupport.evaluateTimelineEntry(contract, context);
        }

        @Override
        public String eventId(TimelineChannel contract, ChannelEvaluationContext context) {
            return TimelineProviderSupport.eventId(context.event());
        }

        @Override
        public boolean isNewerEvent(TimelineChannel contract, ChannelCheckpointContext context) {
            return TimelineProviderSupport.isNewerOrSameTimelineEvent(context);
        }
    }
}
