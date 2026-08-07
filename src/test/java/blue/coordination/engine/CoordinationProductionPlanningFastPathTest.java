package blue.coordination.engine;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.DocumentRegistration;
import blue.coordination.engine.api.DocumentSessionId;
import blue.coordination.engine.api.PrefetchPolicy;
import blue.coordination.engine.api.StoredCoordinationEvent;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.engine.memory.InMemoryCoordinationProcessingBundleLoader;
import blue.coordination.engine.memory.InMemoryCoordinationSessionStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.processor.CoordinationSubscriptionOccurrence;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.RepositoryIndependentCoordinationTestRuntime;
import blue.coordination.processor.RepositoryIndependentCoordinationTypes;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that the admitted planning artifacts serve the production engine. */
final class CoordinationProductionPlanningFastPathTest {
    private static final String CHANNEL_KEY = "timeline";
    private static final ExternalOrderKey ACTIVATION_ORDER = order(
            10L, "activation");
    private static final ExternalOrderKey EVENT_ORDER = order(
            20L, "timeline-entry");

    @Test
    void shouldCompileAtAdmissionAndMemoizeAnExactProductionPlan() {
        // given
        try (Harness harness = new Harness()) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "planning-fast-path-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            assertEquals(1L,
                    harness.engine.planningProjectionCacheMetricsForTest()
                            .loads());
            StoredCoordinationEvent event = harness.engine.prepareEvent(
                    timelineEvent(), EVENT_ORDER);
            List<String> candidates = new ArrayList<String>();
            for (CoordinationSubscriptionOccurrence occurrence
                    : harness.engine.session(sessionId)
                            .subscriptions().occurrences()) {
                if (CHANNEL_KEY.equals(occurrence.channelKey())) {
                    candidates.add(occurrence.occurrenceKey());
                }
            }
            assertTrue(!candidates.isEmpty(),
                    "fixture must expose an indexed Timeline occurrence");

            // when
            CoordinationProcessingPlan first = harness.engine.planIndexed(
                    sessionId,
                    0L,
                    event,
                    candidates,
                    PrefetchPolicy.BALANCED);
            CoordinationProcessingPlan retry = harness.engine.planIndexed(
                    sessionId,
                    0L,
                    event,
                    candidates,
                    PrefetchPolicy.BALANCED);

            // then
            assertSame(first.preparedDelivery(), retry.preparedDelivery());
            assertEquals(first.planIdentity(), retry.planIdentity());
            assertEquals(1L,
                    harness.engine.preparedDeliveryCacheMetricsForTest()
                            .loads());
            assertEquals(1L,
                    harness.engine.preparedDeliveryCacheMetricsForTest()
                            .hits());
            assertEquals(1L,
                    harness.engine.planningProjectionCacheMetricsForTest()
                            .loads());
            assertTrue(
                    harness.engine.planningProjectionCacheMetricsForTest()
                            .hits() >= 2L);

            CoordinationTransition transition = harness.engine.execute(
                    first);
            CommitOutcome outcome = harness.engine.commit(transition);
            assertEquals(CommitStatus.COMMITTED, outcome.status());
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, outcome));
            assertEquals(0L,
                    harness.engine.preparedDeliveryCacheMetricsForTest()
                            .entries());
            assertEquals(0L,
                    harness.engine.planningProjectionCacheMetricsForTest()
                            .entries());
        }
    }

    private static Node authoredRoot() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put(
                CHANNEL_KEY,
                RepositoryIndependentCoordinationTypes.timelineChannel(
                        "timeline-a", "actor-a"));
        contracts.put(
                "workflow",
                RepositoryIndependentCoordinationTypes.sequentialWorkflow(
                        CHANNEL_KEY,
                        RepositoryIndependentCoordinationTypes
                                .updateDocumentStep(
                                        "/counter",
                                        new Node().value(7))));
        return new Node()
                .name("Production planning fast-path Root")
                .properties("counter", new Node().value(0))
                .properties("contracts", new Node().properties(contracts));
    }

    private static Node timelineEvent() {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                "timeline-a",
                "actor-a",
                BigInteger.valueOf(20L),
                RepositoryIndependentCoordinationTypes.chatMessage(
                        "invoke"));
    }

    private static ExternalOrderKey order(long sequence, String label) {
        return ExternalOrderKey.of(Arrays.<Object>asList(sequence, label));
    }

    private static final class Harness implements AutoCloseable {
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final CoordinationProcessingEngine engine;

        private Harness() {
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            InMemoryCoordinationFragmentStore fragmentStore =
                    new InMemoryCoordinationFragmentStore(
                            CoordinationDocumentSplitter
                                    .FRAGMENTATION_PROFILE_ID);
            runtime.addNodeProvider(fragmentStore);
            engine = CoordinationProcessingEngine.builder()
                    .contracts(runtime.contracts())
                    .documentProcessor(runtime.platformProcessor())
                    .fragmentStore(fragmentStore)
                    .sessionStore(new InMemoryCoordinationSessionStore())
                    .bundleLoader(
                            new InMemoryCoordinationProcessingBundleLoader(
                                    fragmentStore,
                                    runtime.platformProcessor()
                                            .administration()
                                            .runtimeAccess()
                                            .languageRuntime()
                                            .getNodeProvider()))
                    .providerEvidenceDomain(
                            "test:production-planning-fast-path")
                    .build();
        }

        private Node initializedRoot() {
            DocumentProcessingResult initialized =
                    runtime.initializeDocument(authoredRoot());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    initialized.status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            initialized));
            return initialized.document();
        }

        @Override
        public void close() {
            engine.close();
            runtime.close();
        }
    }
}
