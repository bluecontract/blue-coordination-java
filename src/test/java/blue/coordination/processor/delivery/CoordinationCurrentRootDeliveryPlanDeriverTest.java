package blue.coordination.processor.delivery;

import blue.coordination.processor.CoordinationProcessors;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.SubscriptionDelta;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-Contracts coverage for whole-current-Root delivery derivation. */
final class CoordinationCurrentRootDeliveryPlanDeriverTest {

    @Test
    void shouldDeriveCurrentRootPlanThroughPublicContractsApi() {
        // given
        Node root = new Node().name("Root without External Channels");
        Node event = new Node().properties(
                "kind", new Node().value("tick"));
        ExternalOrderKey order = ExternalOrderKey.of(
                Arrays.<Object>asList(1L, "tick-1"));

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts =
                     CoordinationProcessors.contracts(language)) {
            SubscriptionDelta initial = contracts
                    .subscriptionSurfaceProjection()
                    .projectInitial(root, 0L, order);
            ExternalDeliveryPlanDeriver deriver =
                    CoordinationCurrentRootDeliveryPlanDeriver.forContracts(
                            contracts,
                            0L,
                            order,
                            initial.added());
            ExternalDeliveryPlan plan = deriver.derive(root, event);

            // then
            assertNotNull(deriver);
            assertTrue(plan.deliveries().isEmpty());
            assertEquals(initial.added(),
                    plan.activeSubscriptionIntervals());
            assertEquals(order, plan.eventOrderKey());
        }
    }

    @Test
    void shouldRejectMissingContractsAtFactoryBoundary() {
        // given
        BlueContracts missingContracts = null;
        ExternalOrderKey order = ExternalOrderKey.of(
                Collections.<Object>singletonList(1L));

        // when
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> CoordinationCurrentRootDeliveryPlanDeriver
                        .forContracts(
                                missingContracts,
                                0L,
                                order,
                                Collections
                                        .<SubscriptionDelta.Entry>emptyList()));

        // then
        assertNotNull(failure);
    }
}
