package blue.language.processor;

import blue.language.Blue;

import java.util.Objects;

/**
 * Test-harness bridge that retains a configured Blue runtime's exact
 * collaborators while selecting one fixture-local process gas limit.
 */
public final class CoordinationConfiguredProcessorFactory {
    private CoordinationConfiguredProcessorFactory() {
    }

    public static DocumentProcessor withGasLimit(
            Blue blue,
            long gasLimit) {
        DocumentProcessor processor =
                configuredBuilder(blue)
                        .withGasLimit(gasLimit)
                        .build();
        processor.externalDeliveryPlanDeriver(
                new CoordinationCurrentRootDeliveryPlanDeriver(
                        processor));
        return processor;
    }

    /**
     * Creates a fixture-local processor whose real Language verifier reads the
     * exact environmental plan represented by authored feeder evidence.
     *
     * @param blue configured runtime
     * @param gasLimit fixture-local limit, or {@code null} for the manifest
     *        maximum
     * @param evidence exact immutable evidence the fixture derived
     * @return caller-owned processor retaining the runtime collaborators
     */
    public static DocumentProcessor withExecutionEvidencePlan(
            Blue blue,
            Long gasLimit,
            VerifiedExecutionEvidence evidence) {
        VerifiedExecutionEvidence exactEvidence =
                Objects.requireNonNull(
                        evidence,
                        "evidence");
        ExternalDeliveryPlan plan =
                plan(exactEvidence);
        DocumentProcessor.Builder builder =
                configuredBuilder(blue)
                        .withExternalDeliveryPlanDeriver(
                                (root, event) -> plan);
        if (gasLimit != null) {
            builder.withGasLimit(
                    gasLimit.longValue());
        }
        return builder.build();
    }

    private static ExternalDeliveryPlan plan(
            VerifiedExecutionEvidence evidence) {
        ExternalDeliveryPlan.Builder builder =
                ExternalDeliveryPlan.builder()
                        .revisions(
                                evidence.managedRootRevision(),
                                evidence.indexedRootRevision())
                        .eventOrderKey(
                                evidence.eventOrderKey())
                        .exactRuntimeState();
        for (ExternalDeliverySnapshot delivery
                : evidence.deliveries()) {
            builder.delivery(delivery);
        }
        if (evidence.hasActiveSubscriptionIntervals()) {
            builder.activeSubscriptionIntervals(
                    evidence
                            .activeSubscriptionIntervals());
        }
        for (String available
                : evidence.availableExactNodeBlueIds()) {
            builder.availableExactNode(
                    available);
        }
        for (String required
                : evidence.requiredExactNodeBlueIds()) {
            builder.requiredExactNode(
                    required);
        }
        return builder.build();
    }

    private static DocumentProcessor.Builder configuredBuilder(
            Blue blue) {
        Blue runtime = Objects.requireNonNull(
                blue, "blue");
        DocumentProcessor configured =
                runtime.getDocumentProcessor();
        return DocumentProcessor.builder()
                .withRegistry(
                        configured.getContractRegistry())
                .withContractTypeResolver(
                        configured.getContractTypeResolver())
                .withConformanceEngine(
                        configured.conformanceEngine())
                .withConformancePlannerOverride(
                        configured
                                .conformancePlannerOverride())
                .withSnapshotManager(
                        configured.snapshotManager())
                .withMatchingService(
                        new ContractMatchingService(runtime))
                .withProcessingMetricsSink(
                        configured.metricsSink())
                .withGasSchedule(
                        configured.gasSchedule())
                .withRuntimeRegistryIdentity(
                        configured
                                .runtimeRegistryIdentity())
                .withSubscriptionSurfaceValidator(
                        configured
                                .subscriptionSurfaceValidator());
    }
}
