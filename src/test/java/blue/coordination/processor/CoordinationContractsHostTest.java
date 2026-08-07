package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.PlatformProcessInvocation;
import blue.language.processor.SubscriptionDelta;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused managed-host coverage for the current public Contracts services. */
final class CoordinationContractsHostTest {

    @Test
    void shouldUseOnePublicContractsGenerationForProjectionDeliveryAndCommit() {
        // given
        Node root = new Node().name("managed Root");
        Node event = new Node().properties(
                "kind", new Node().value("tick"));
        ExternalOrderKey order = ExternalOrderKey.of(
                Collections.emptyList());

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts =
                     CoordinationProcessors.contracts(language)) {
            CoordinationContractsHost host =
                    new CoordinationContractsHost(contracts);
            SubscriptionDelta initial = host.projectInitialSubscriptions(
                    root, 0L, order);
            IndexedDeliveryPreparation indexed =
                    host.prepareIndexedDelivery(
                            root,
                            event,
                            0L,
                            order,
                            initial.added(),
                            Collections
                                    .<ExternalSubscriptionOccurrenceKey>
                                    emptyList());
            ExternalDeliveryPlan compatible =
                    host.currentRootDeliveryPlanDeriver(
                                    0L, order, initial.added())
                            .derive(root, event);
            PlatformProcessInvocation invocation =
                    host.preparePlatformCommitInvocation(
                            indexed,
                            host.runtimeAccess().languageRuntime()
                                    .getNodeProvider());
            PlatformProcessingResult committed =
                    host.processForPlatformCommit(
                            root, event, invocation);

            // then
            assertTrue(host.runtimeAccess().isCurrent());
            assertTrue(host.materializeVerifiedExactReference(root)
                    .isEstablished());
            assertEquals(
                    host.effectiveFragmentationCatalog(root).rootBlueId(),
                    DirectBlueIdCalculator.calculateBlueId(root));
            assertTrue(initial.isEmpty());
            assertEquals(
                    indexed.deliveryPlan().deliveries(),
                    compatible.deliveries());
            assertEquals(
                    indexed.deliveryPlan().activeSubscriptionIntervals(),
                    compatible.activeSubscriptionIntervals());
            assertNotNull(committed.processResult());
            assertEquals(
                    indexed.deliveryPlan().managedRootRevision(),
                    committed.commitCompanion().expectedRootRevision());
            assertEquals(
                    indexed.deliveryPlan().eventOrderKey(),
                    committed.commitCompanion().eventOrderKey());
        }
    }
}
