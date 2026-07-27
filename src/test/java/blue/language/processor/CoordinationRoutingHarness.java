package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Collections;

/**
 * Test-only bridge for preparing exact verified external-delivery evidence.
 */
public final class CoordinationRoutingHarness {
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    java.util.Arrays.<Object>asList(
                            7,
                            "coordination-logical-routing",
                            1));

    private CoordinationRoutingHarness() {
    }

    public static ProcessingSnapshotManager snapshotManager(
            Blue language) {
        return language.getDocumentProcessor()
                .snapshotManager();
    }

    public static DocumentProcessingResult process(
            DocumentProcessor processor,
            Node document,
            Node event,
            String... sourceKeys) {
        ResolvedSnapshot snapshot =
                processor.snapshotManager()
                        .fromDocumentTransient(document);
        ContractBundle bundle =
                processor.contractLoader()
                        .load(snapshot, "/");
        ExternalDeliveryPlan.Builder plan =
                ExternalDeliveryPlan.builder()
                        .revisions(7L, 7L)
                        .eventOrderKey(EVENT_ORDER)
                        .activeSubscriptionIntervals(
                                Collections
                                        .<SubscriptionDelta.Entry>
                                                emptyList())
                        .exactRuntimeState();
        for (String sourceKey : sourceKeys) {
            EffectiveContractSnapshot contract =
                    bundle.effectiveContractSnapshot(
                            sourceKey);
            ExternalChannelFunctionEvaluation evaluation =
                    ExternalChannelFunctionEvaluation
                            .evaluate(
                                    processor.registry(),
                                    processor
                                            .contractConverter(),
                                    ExternalChannelFunctionEvaluation
                                            .verifiedMatcherSessions(
                                                    processor
                                                            .snapshotManager()),
                                    bundle,
                                    contract,
                                    event);
            plan.delivery(delivery(
                    contract, evaluation));
        }
        ExternalDeliveryPlan built = plan.build();
        VerifiedExecutionEvidence evidence =
                built.bind(
                        document,
                        event,
                        processor.runtimeRegistryIdentity());
        return processor.processDocumentWithTrace(
                document,
                event,
                evidence).processResult();
    }

    public static java.util.List<String> routingProjection(
            DocumentProcessor processor,
            Node document,
            Node event,
            String sourceKey) {
        ResolvedSnapshot snapshot =
                processor.snapshotManager()
                        .fromDocumentTransient(document);
        ContractBundle bundle =
                processor.contractLoader()
                        .load(snapshot, "/");
        EffectiveContractSnapshot contract =
                bundle.effectiveContractSnapshot(
                        sourceKey);
        ExternalChannelFunctionEvaluation evaluation =
                ExternalChannelFunctionEvaluation
                        .evaluate(
                                processor.registry(),
                                processor.contractConverter(),
                                ExternalChannelFunctionEvaluation
                                        .verifiedMatcherSessions(
                                                processor
                                                        .snapshotManager()),
                                bundle,
                                contract,
                                event);
        return java.util.Arrays.asList(
                evaluation.handlerChannelKey(),
                evaluation.logicalDeliveryKey());
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot
                                        .effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                evaluation
                                        .checkpointSubjectBlueId());
        for (String contribution
                : snapshot
                .sourceContributionNodeBlueIds()) {
            builder.sourceContribution(
                    contribution);
        }
        for (String subscriptionKey
                : evaluation.channelKeys()) {
            builder.subscriptionKey(
                    subscriptionKey);
        }
        return builder.build();
    }
}
