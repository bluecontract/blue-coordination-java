package blue.coordination.processor.compute;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.CoordinationCyclicMutationHarness;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationCyclicMutationBoundaryTest {

    @Test
    void shouldRejectMutationBelowOpaqueCyclicMemberWithoutProviderDemand() {
        // Given
        ComputeWorkflowTestSupport support =
                ComputeWorkflowTestSupport.create();
        String memberBlueId =
                cyclicMemberBlueId();
        List<String> providerRequests =
                installDemandRecorder(
                        support);
        Node document =
                new Node().properties(
                        "opaque",
                        new Node().blueId(
                                memberBlueId));
        String originalBlueId =
                support.blue.calculateBlueId(
                        document);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationCyclicMutationHarness
                                .replace(
                                        support.blue
                                                .getDocumentProcessor(),
                                        document,
                                        "/opaque",
                                        "/opaque/memberField",
                                        new Node().value(
                                                "must-not-apply")));

        // Then
        assertEquals(
                "Mutation below cyclic-set member reference is unsupported "
                        + "at /opaque: /opaque/memberField",
                failure.getMessage());
        assertEquals(
                originalBlueId,
                support.blue.calculateBlueId(
                        document),
                "a rejected below-member patch must leave the Root exact");
        assertTrue(
                document.getAsNode(
                                "/opaque")
                        .isReferenceOnly());
        assertFalse(
                providerRequests.contains(
                        memberBlueId),
                "patch planning must reject traversal before demanding "
                        + "opaque cyclic member content");
    }

    @Test
    void shouldAllowWholeOpaqueCyclicEdgeReplacementWithoutProviderDemand() {
        // Given
        ComputeWorkflowTestSupport support =
                ComputeWorkflowTestSupport.create();
        String memberBlueId =
                cyclicMemberBlueId();
        List<String> providerRequests =
                installDemandRecorder(
                        support);
        Node document =
                new Node().properties(
                        "opaque",
                        new Node().blueId(
                                memberBlueId));

        // When
        Node result =
                CoordinationCyclicMutationHarness
                        .replace(
                                support.blue
                                        .getDocumentProcessor(),
                                document,
                                "/opaque",
                                "/opaque",
                                new Node().value(
                                        "replacement"));

        // Then
        assertEquals(
                "replacement",
                result.get(
                        "/opaque"));
        assertFalse(
                providerRequests.contains(
                        memberBlueId),
                "whole-edge replacement does not require member content");
    }

    private static String cyclicMemberBlueId() {
        return BlueIdCalculator.calculateBlueId(
                new Node().value(
                        "opaque cyclic mutation set"))
                + "#0";
    }

    private static List<String> installDemandRecorder(
            ComputeWorkflowTestSupport support) {
        List<String> providerRequests =
                new ArrayList<String>();
        NodeProvider existing =
                support.blue.getNodeProvider();
        support.blue.nodeProvider(
                new SequentialNodeProvider(
                        blueId -> {
                            providerRequests.add(
                                    blueId);
                            return null;
                        },
                        existing));
        return providerRequests;
    }
}
