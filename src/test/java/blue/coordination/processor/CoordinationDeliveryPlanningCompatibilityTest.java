package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.NodeProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationDeliveryPlanningCompatibilityTest {

    private static final long ROOT_REVISION = 4L;
    private static final ExternalOrderKey ACTIVATION_ORDER =
            ExternalOrderKey.of(Arrays.asList(10L, "activation"));
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.asList(20L, "timeline-entry"));

    @Test
    void shouldPrepareCompleteCurrentRootCompatibilityEvidence() {
        // given
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            Node root = root();
            Node event = RepositoryIndependentCoordinationTypes.timelineEntry(
                    "timeline-a",
                    "actor-a",
                    BigInteger.valueOf(20L),
                    RepositoryIndependentCoordinationTypes
                            .chatMessage("deliver"));
            String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
            String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
            CoordinationSubscriptionSnapshot snapshot =
                    CoordinationDeliveryPlanning.subscriptionProjector(
                                    runtime.processor(),
                                    runtime.contracts())
                            .projectCurrent(
                                    root,
                                    ROOT_REVISION,
                                    ACTIVATION_ORDER);
            NodeProvider exactProvider = exactProvider(
                    rootBlueId,
                    root,
                    eventBlueId,
                    event);

            // when
            CoordinationPreparedDelivery prepared =
                    CoordinationDeliveryPlanning
                            .prepareCurrentRootCompatibility(
                                    runtime.processor(),
                                    runtime.contracts(),
                                    root,
                                    event,
                                    snapshot,
                                    exactProvider,
                                    ROOT_REVISION,
                                    EVENT_ORDER);

            // then
            assertEquals(rootBlueId, prepared.rootReference().getBlueId());
            assertEquals(eventBlueId, prepared.eventReference().getBlueId());
            assertEquals(rootBlueId, prepared.evidence().rootBlueId());
            assertEquals(eventBlueId, prepared.evidence().eventBlueId());
            assertEquals(
                    snapshot.digest(),
                    prepared.subscriptionSnapshotIdentity());
            assertEquals(
                    snapshot.occurrences().size(),
                    prepared.deliveryPlan()
                            .activeSubscriptionIntervals().size());
            assertEquals(
                    prepared.deliveryPlan()
                            .activeSubscriptionIntervals(),
                    prepared.evidence()
                            .activeSubscriptionIntervals());
            assertEquals(1, prepared.preselectedOccurrenceOrder().size());
            assertEquals(
                    prepared.preselectedOccurrenceOrder().size(),
                    prepared.sourceDeliveries().size());
            assertEquals(
                    deliverySignatures(prepared.deliveryPlan().deliveries()),
                    deliverySignatures(prepared.evidence().deliveries()));
            assertFalse(
                    prepared.selectedScopeChainIdentities().isEmpty());
            for (List<String> chain
                    : prepared.selectedScopeChainIdentities().values()) {
                assertTrue(
                        prepared.requiredSeedFragmentIdentities()
                                .containsAll(chain));
            }
            assertTrue(
                    prepared.requiredSeedFragmentIdentities()
                            .contains(rootBlueId));
            assertTrue(
                    prepared.requiredSeedFragmentIdentities()
                            .contains(eventBlueId));
            assertEquals(
                    prepared.prefetchIdentities(),
                    prepared.demandBoundary().prefetchBlueIds());
            assertEquals(
                    prepared.selectedScopeChainIdentities().keySet(),
                    new java.util.LinkedHashSet<String>(
                            prepared.demandBoundary()
                                    .selectedScopePaths()));
            assertEquals(
                    rootBlueId,
                    prepared.demandBoundary().rootBlueId());
            assertEquals(
                    eventBlueId,
                    prepared.demandBoundary().eventBlueId());
        }
    }

    private static Node root() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(
                "timeline",
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-a", "actor-a"));
        return new Node()
                .name("Current-Root compatibility test")
                .properties(
                        "contracts",
                        new Node().properties(contracts));
    }

    private static NodeProvider exactProvider(
            String rootBlueId,
            Node root,
            String eventBlueId,
            Node event) {
        Map<String, Node> exact = new LinkedHashMap<String, Node>();
        exact.put(rootBlueId, root.clone());
        exact.put(eventBlueId, event.clone());
        return blueId -> {
            Node node = exact.get(blueId);
            return node == null
                    ? Collections.<Node>emptyList()
                    : Collections.singletonList(node.clone());
        };
    }

    private static List<String> deliverySignatures(
            List<ExternalDeliverySnapshot> deliveries) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<String>();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            result.add(
                    delivery.scopePath()
                            + "|" + delivery.channelKey()
                            + "|" + delivery.effectiveTypeBlueId()
                            + "|" + delivery.checkpointDomainBlueId()
                            + "|" + delivery.checkpointSubjectBlueId());
        }
        return result;
    }
}
