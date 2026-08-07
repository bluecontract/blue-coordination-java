package blue.coordination.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.Timeline;
import blue.repo.coordination.TimelineChannel;
import blue.repo.coordination.TimelineEntry;
import blue.repo.myos.PrincipalActor;

import java.math.BigInteger;

public final class TestTimelineProvider {
    private TestTimelineProvider() {
    }

    public static CoordinationTestRuntime registerWith(
            CoordinationTestRuntime runtime) {
        // The current Coordination registry already owns Timeline Channel.
        return runtime;
    }

    public static Node channel(String timelineId) {
        return channel(timelineId, timelineId);
    }

    public static Node channel(String timelineId, String actorId) {
        Node channel = new Node().type(
                new Node().blueId(
                        TimelineChannel.blueId()));
        if (timelineId != null) {
            channel.properties("timeline", new Node()
                    .type(new Node().blueId(
                            Timeline.blueId()))
                    .properties("providerId", new Node().value("test-provider"))
                    .properties("timelineId", new Node().value(timelineId)));
        }
        if (actorId != null) {
            channel.properties("actor", new Node()
                    .type(new Node().blueId(
                            PrincipalActor.blueId()))
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
                message);
    }

    public static Node timelineEntry(
            CoordinationTestRuntime runtime,
            BlueRepository repository,
            String timelineId,
            int timestamp,
            Node message) {
        return timelineEntry(
                runtime,
                repository,
                timelineId,
                timelineId,
                BigInteger.valueOf(timestamp),
                message);
    }

    public static Node timelineEntry(Blue blue,
                                     BlueRepository repository,
                                     String timelineId,
                                     String actorId,
                                     BigInteger timestamp,
                                     Node message) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId(timelineId))
                .actor(new PrincipalActor().accountId(actorId))
                .timestamp(timestamp);

        Node event = blue.objectToNode(entry)
                .properties("timestamp", new Node().value(timestamp))
                .properties("message", message)
                .blue(repository.importsDirective());
        /*
         * PROCESS receives strict canonical content. The paired resolved
         * lane remains internal to Language; returning it here would expose
         * materialized type definitions as mixed BlueId/object nodes.
         */
        return blue.resolveToSnapshot(event).canonicalRoot();
    }

    public static Node timelineEntry(
            CoordinationTestRuntime runtime,
            BlueRepository repository,
            String timelineId,
            String actorId,
            BigInteger timestamp,
            Node message) {
        TimelineEntry entry = new TimelineEntry()
                .timeline(new Timeline().timelineId(timelineId))
                .actor(new PrincipalActor().accountId(actorId))
                .timestamp(timestamp);

        Node event = runtime.objectToNode(entry)
                .properties("timestamp", new Node().value(timestamp))
                .properties("message", message)
                .blue(repository.importsDirective());
        return runtime.resolveToSnapshot(event).canonicalRoot();
    }

    public static Node timelineEntryWithProviderSequence(Blue blue,
                                                         BlueRepository repository,
                                                         String timelineId,
                                                         String actorId,
                                                         BigInteger providerSequence,
                                                         BigInteger timestamp,
                                                         Node message) {
        return timelineEntry(blue, repository, timelineId, actorId, timestamp, message)
                .properties("sequence", new Node().value(providerSequence));
    }

    public static Node timelineEntryWithProviderSequence(
            CoordinationTestRuntime runtime,
            BlueRepository repository,
            String timelineId,
            String actorId,
            BigInteger providerSequence,
            BigInteger timestamp,
            Node message) {
        return timelineEntry(
                runtime,
                repository,
                timelineId,
                actorId,
                timestamp,
                message)
                .properties(
                        "sequence",
                        new Node().value(providerSequence));
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
        public ExternalChannelSubscriptionFunctions<TimelineChannel>
        externalSubscriptionFunctions() {
            return TimelineExternalSubscriptionFunctions.INSTANCE;
        }

        @Override
        public ChannelEvaluation evaluate(TimelineChannel contract, ChannelEvaluationContext context) {
            return TimelineProviderSupport.evaluateTimelineEntry(contract, context);
        }

        @Override
        public String eventId(TimelineChannel contract, ChannelEvaluationContext context) {
            return TimelineProviderSupport.eventId(context.event());
        }

    }
}
