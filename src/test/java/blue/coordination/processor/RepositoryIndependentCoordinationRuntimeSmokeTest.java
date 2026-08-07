package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ExternalSubscriptionOccurrenceKey;
import blue.language.processor.IndexedDeliveryPreparation;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.PlatformProcessingResult;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused executable proof for the repository-independent runtime fixture. */
final class RepositoryIndependentCoordinationRuntimeSmokeTest {

    private static final String CHANNEL_KEY = "timeline";
    private static final long ROOT_REVISION = 1L;
    private static final ExternalOrderKey ACTIVATION_ORDER =
            ExternalOrderKey.of(Arrays.asList(10L, "activation"));
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(Arrays.asList(20L, "timeline-entry"));

    @Test
    void shouldRunUpdateAndTriggerWorkflowWithPlatformCompanionParity() {
        // given
        try (RepositoryIndependentCoordinationTestRuntime runtime =
                     RepositoryIndependentCoordinationTestRuntime.open()) {
            Node authoredRoot = root();
            DocumentProcessingResult initialized =
                    runtime.initializeDocument(authoredRoot);
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            initialized));
            Node root = initialized.document();
            Node event = RepositoryIndependentCoordinationTypes
                    .timelineEntry(
                            "timeline-a",
                            "actor-a",
                            BigInteger.valueOf(20L),
                            RepositoryIndependentCoordinationTypes
                                    .chatMessage("invoke"));

            SubscriptionDelta initial = runtime
                    .subscriptionSurfaceProjection()
                    .projectInitial(
                            root,
                            ROOT_REVISION,
                            ACTIVATION_ORDER);
            List<SubscriptionDelta.Entry> activeIntervals =
                    initial.added();
            List<ExternalSubscriptionOccurrenceKey> candidates =
                    Collections.singletonList(
                            ExternalSubscriptionOccurrenceKey.of(
                                    "/", CHANNEL_KEY));
            IndexedDeliveryPreparation indexed = runtime
                    .indexedDeliveryEvaluator()
                    .prepare(
                            root,
                            event,
                            ROOT_REVISION,
                            EVENT_ORDER,
                            activeIntervals,
                            candidates);
            ExternalDeliveryPlanDeriver exactDeriver =
                    (candidateRoot, candidateEvent) ->
                            indexed.deliveryPlan();
            runtime.configureDeliveryPlanDeriver(exactDeriver);
            VerifiedExecutionEvidence evidence =
                    runtime.executionEvidence(
                            root,
                            event,
                            indexed.deliveryPlan());

            // when
            ProcessingDebugResult traced = runtime.processor()
                    .processDocumentWithTrace(root, event, evidence);
            PlatformProcessingResult platform = runtime.platformProcessor()
                    .processDocumentForPlatformCommit(
                            root, event, evidence);

            // then
            assertEquals(1, activeIntervals.size());
            assertEquals(CHANNEL_KEY,
                    activeIntervals.get(0).channelKey());
            assertEquals(1, indexed.deliveryPlan().deliveries().size());
            assertSuccessfulWorkflowResult(traced.processResult());
            assertSuccessfulWorkflowResult(platform.processResult());
            assertSemanticParity(
                    traced.processResult(),
                    platform.processResult());
            assertPlatformParity(
                    traced.platformCommitCompanion(),
                    platform.commitCompanion());
            assertFalse(traced.trace().gas().isEmpty());
            assertTrue(evidence.missingRequiredExactNodeBlueIds().isEmpty());
        }
    }

    private static Node root() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(CHANNEL_KEY,
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-a", "actor-a"));
        contracts.put("workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        CHANNEL_KEY,
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "/counter",
                                        new Node().value(7)),
                        RepositoryIndependentCoordinationTypes
                                .triggerEventStep(
                                        RepositoryIndependentCoordinationTypes
                                                .chatMessage("completed"))));
        return new Node()
                .name("Repository-independent workflow smoke Root")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static void assertSuccessfulWorkflowResult(
            DocumentProcessingResult result) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                ProcessingResultTestSupport.diagnosticMessage(result));
        assertEquals(BigInteger.valueOf(7L),
                result.document().get("/counter"));
        assertEquals(1, result.events().size());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        RepositoryIndependentCoordinationTypes
                                .chatMessage("completed")),
                DirectBlueIdCalculator.calculateBlueId(
                        result.events().get(0)));
    }

    private static void assertSemanticParity(
            DocumentProcessingResult traced,
            DocumentProcessingResult platform) {
        assertEquals(traced.status(), platform.status());
        assertEquals(traced.totalGas(), platform.totalGas());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(traced.document()),
                DirectBlueIdCalculator.calculateBlueId(platform.document()));
        assertEquals(eventBlueIds(traced.events()),
                eventBlueIds(platform.events()));
    }

    private static void assertPlatformParity(
            PlatformCommitCompanion traced,
            PlatformCommitCompanion platform) {
        assertNotNull(traced);
        assertNotNull(platform);
        assertEquals(traced.expectedRootBlueId(),
                platform.expectedRootBlueId());
        assertEquals(traced.eventBlueId(), platform.eventBlueId());
        assertEquals(traced.expectedRootRevision(),
                platform.expectedRootRevision());
        assertEquals(traced.resultingRootRevision(),
                platform.resultingRootRevision());
        assertEquals(traced.eventOrderKey(), platform.eventOrderKey());
        assertEquals(traced.commitsRootAndOutbox(),
                platform.commitsRootAndOutbox());
        assertEquals(traced.subscriptionDelta().added().size(),
                platform.subscriptionDelta().added().size());
        assertEquals(traced.subscriptionDelta().removed().size(),
                platform.subscriptionDelta().removed().size());
    }

    private static List<String> eventBlueIds(List<Node> events) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<String>();
        for (Node event : events) {
            result.add(DirectBlueIdCalculator.calculateBlueId(event));
        }
        return result;
    }
}
