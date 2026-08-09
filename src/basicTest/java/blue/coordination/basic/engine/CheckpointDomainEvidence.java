package blue.coordination.basic.engine;

import blue.coordination.processor.CoordinationSemanticTypeIdentities;
import blue.language.model.Node;
import blue.language.processor.SubscriptionDelta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Retains exact processor-owned checkpoint descriptors needed by Compute. */
final class CheckpointDomainEvidence {
    private static final String CONTRACTS_VERSION = "1.0";
    private static final String PROJECTION_VERSION =
            "blue.coordination/1.0/timeline-entry-projection-v3";
    private static final String SUBJECT_VERSION =
            "blue.coordination/1.0/timeline-order-subject-v3";
    private static final CoordinationSemanticTypeIdentities IDENTITIES =
            CoordinationSemanticTypeIdentities.publishedDefaults();

    private CheckpointDomainEvidence() {
    }

    static void retainAll(
            List<SubscriptionDelta.Entry> subscriptions,
            WholeObjectStore objects) {
        Objects.requireNonNull(subscriptions, "subscriptions");
        Objects.requireNonNull(objects, "objects");
        for (SubscriptionDelta.Entry subscription : subscriptions) {
            if (objects.contains(subscription.checkpointDomainBlueId())) {
                continue;
            }
            Node descriptor = timelineDescriptor(subscription);
            ExactNodeValue exact = objects.put(
                    descriptor, "checkpoint-domain");
            if (!subscription.checkpointDomainBlueId().equals(
                    exact.blueId())) {
                throw new IllegalStateException(
                        "Unsupported external checkpoint domain at "
                                + subscription.scopePath() + "/"
                                + subscription.channelKey() + ": projected "
                                + subscription.checkpointDomainBlueId()
                                + " but Timeline descriptor calculated "
                                + exact.blueId());
            }
        }
    }

    private static Node timelineDescriptor(
            SubscriptionDelta.Entry subscription) {
        Node descriptor = new Node()
                .properties(
                        "contractsVersion",
                        new Node().value(CONTRACTS_VERSION))
                .properties(
                        "effectiveTypeBlueId",
                        new Node().value(
                                subscription.effectiveTypeBlueId()))
                .properties(
                        "sourceContributionNodeBlueIds",
                        textList(subscription
                                .sourceContributionNodeBlueIds()));
        List<String> dependencies = subscription.dependencies()
                .deterministicDependencyNodeBlueIds();
        if (!dependencies.isEmpty()) {
            descriptor.properties(
                    "deterministicDependencyNodeBlueIds",
                    textList(dependencies));
        }
        return descriptor.properties(
                "runtimeDiscriminator",
                new Node().value(
                        "coordination.timeline-entry:"
                                + IDENTITIES.timelineEntryBlueId()
                                + "|semantic-profile="
                                + IDENTITIES.profileIdentity()
                                + "|projection=" + PROJECTION_VERSION
                                + "|subject=" + SUBJECT_VERSION));
    }

    private static Node textList(List<String> values) {
        List<Node> items = new ArrayList<>(values.size());
        for (String value : values) {
            items.add(new Node().value(value));
        }
        return new Node().items(items);
    }
}
