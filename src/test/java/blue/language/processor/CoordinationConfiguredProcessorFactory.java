package blue.coordination.processor;

import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.VerifiedExecutionEvidence;

import java.util.Objects;

/**
 * Test fixture that derives successor immutable processor generations from
 * the current public builder snapshot API.
 */
public final class CoordinationConfiguredProcessorFactory {
    private CoordinationConfiguredProcessorFactory() {
    }

    public static DocumentProcessor withGasLimit(
            CoordinationTestRuntime runtime,
            long gasLimit) {
        return DocumentProcessor.Builder.from(
                        Objects.requireNonNull(runtime, "runtime")
                                .processor())
                .gasLimit(gasLimit)
                .build();
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
            CoordinationTestRuntime runtime,
            Long gasLimit,
            VerifiedExecutionEvidence evidence) {
        VerifiedExecutionEvidence exactEvidence =
                Objects.requireNonNull(
                        evidence,
                        "evidence");
        ExternalDeliveryPlan plan =
                plan(exactEvidence);
        DocumentProcessor.Builder builder =
                DocumentProcessor.Builder.from(
                                Objects.requireNonNull(
                                        runtime, "runtime")
                                        .processor())
                        .deliveryPlanDeriver(
                                (root, event) -> plan);
        if (gasLimit != null) {
            builder.gasLimit(
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

}
