package blue.coordination.processor;

import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.VerifiedExecutionEvidence;

import java.util.Arrays;
import java.util.Collections;

/** Test-only factories for package-scoped immutable processor values. */
public final class CoordinationEngineProcessorTestFixtures {

    private CoordinationEngineProcessorTestFixtures() {
    }

    public static CoordinationSubscriptionSnapshot emptySnapshot(
            String rootBlueId,
            long rootRevision,
            ExternalOrderKey activationFrontier) {
        return new CoordinationSubscriptionSnapshot(
                "language-runtime-test",
                "coordination-runtime-test",
                rootBlueId,
                rootRevision,
                activationFrontier,
                Collections.<CoordinationSubscriptionOccurrence>emptyList(),
                Collections.<String, java.util.List<String>>emptyMap(),
                Collections.<String>emptySet());
    }

    public static CoordinationPreparedDelivery emptyPreparedDelivery(
            String rootBlueId,
            String eventBlueId,
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            String subscriptionSnapshotIdentity) {
        VerifiedExecutionEvidence evidence = VerifiedExecutionEvidence
                .builder(rootBlueId, eventBlueId)
                .revisions(rootRevision, rootRevision)
                .runtimeRegistryIdentity("language-runtime-test")
                .eventOrderKey(eventOrderKey)
                .activeSubscriptionIntervals(
                        Collections.emptyList())
                .availableExactNode(rootBlueId)
                .availableExactNode(eventBlueId)
                .requiredExactNode(rootBlueId)
                .requiredExactNode(eventBlueId)
                .build();
        ExternalDeliveryPlan deliveryPlan = ExternalDeliveryPlan.builder()
                .revisions(rootRevision, rootRevision)
                .eventOrderKey(eventOrderKey)
                .activeSubscriptionIntervals(Collections.emptyList())
                .availableExactNode(rootBlueId)
                .availableExactNode(eventBlueId)
                .requiredExactNode(rootBlueId)
                .requiredExactNode(eventBlueId)
                .exactRuntimeState()
                .build();
        CoordinationSemanticDemandBoundary boundary =
                new CoordinationSemanticDemandBoundary(
                        rootBlueId,
                        eventBlueId,
                        Collections.singletonList("/"),
                        Arrays.asList(rootBlueId, eventBlueId),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList());
        return new CoordinationPreparedDelivery(
                rootBlueId,
                eventBlueId,
                evidence,
                deliveryPlan,
                "delivery-plan-test",
                subscriptionSnapshotIdentity,
                Collections.<String>emptyList(),
                Collections.<CoordinationDeliveryDiagnostic>emptyList(),
                Collections.<String, java.util.Collection<String>>emptyMap(),
                Arrays.asList(rootBlueId, eventBlueId),
                Collections.<String>emptyList(),
                boundary);
    }
}
