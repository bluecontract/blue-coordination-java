package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.ContractProcessor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.model.Contract;
import blue.language.utils.BlueIdCalculator;
import blue.repo.coordination.TimelineChannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic identity of the Coordination registrations installed in one
 * concrete Language processor.
 */
final class CoordinationRuntimeRegistrations {
    private static final String IDENTITY_KIND =
            "blue.coordination/runtime-registry/1.0";

    private CoordinationRuntimeRegistrations() {
    }

    static String identity(
            DocumentProcessor processor) {
        return identity(runtimeTypes(processor));
    }

    static List<String> timelineSubtypeBlueIds(
            DocumentProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        List<String> result =
                new ArrayList<String>();
        for (Map.Entry<
                String,
                ContractProcessor<? extends Contract>>
                registration
                : processor.getContractRegistry()
                        .processors().entrySet()) {
            ContractProcessor<? extends Contract>
                    registeredProcessor =
                    registration.getValue();
            Class<? extends Contract> contractType =
                    registeredProcessor
                            .contractType();
            if (!(registeredProcessor
                    instanceof TimelineChannelSubtypeProcessor)
                    || contractType == null
                    || TimelineChannel.class.equals(
                            contractType)
                    || !TimelineChannel.class
                    .isAssignableFrom(
                            contractType)) {
                continue;
            }
            result.add(registration.getKey());
        }
        Collections.sort(result);
        return Collections.unmodifiableList(
                result);
    }

    private static List<String> runtimeTypes(
            DocumentProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        List<String> types =
                new ArrayList<String>();
        for (Map.Entry<
                String,
                ContractProcessor<? extends Contract>>
                registration
                : processor.getContractRegistry()
                        .processors().entrySet()) {
            ContractProcessor<? extends Contract>
                    registeredProcessor =
                    registration.getValue();
            if (!isCoordinationRegistration(
                    registeredProcessor)) {
                continue;
            }
            Class<? extends Contract> contractType =
                    registeredProcessor.contractType();
            types.add(
                    registration.getKey()
                            + "\u0000"
                            + registeredProcessor
                            .getClass().getName()
                            + "\u0000"
                            + (contractType == null
                            ? ""
                            : contractType.getName()));
        }
        Collections.sort(types);
        types.add(
                "projection\u0000"
                        + TimelineSubscriptionProjection.VERSION);
        return Collections.unmodifiableList(types);
    }

    private static boolean isCoordinationRegistration(
            ContractProcessor<? extends Contract> processor) {
        return processor
                instanceof TimelineChannelProcessor
                || processor
                instanceof TimelineChannelSubtypeProcessor
                || processor
                instanceof AllTimelinesChannelProcessor
                || processor
                instanceof CompositeTimelineChannelProcessor
                || processor
                instanceof OperationProcessor
                || processor
                instanceof ChatWorkflowOperationProcessor
                || processor
                instanceof SequentialWorkflowProcessor
                || processor
                instanceof SequentialWorkflowOperationProcessor;
    }

    private static String identity(
            List<String> values) {
        List<Node> items =
                new ArrayList<Node>(
                        values.size());
        for (String value : values) {
            items.add(
                    new Node().value(value));
        }
        return BlueIdCalculator.calculateBlueId(
                new Node()
                        .properties(
                                "kind",
                                new Node().value(
                                        IDENTITY_KIND))
                        .properties(
                                "values",
                                new Node().items(
                                        items)));
    }
}
