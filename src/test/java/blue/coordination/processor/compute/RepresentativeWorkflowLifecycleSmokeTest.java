package blue.coordination.processor.compute;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.RepositoryTypeAliasPreprocessor;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.workflow.SequentialWorkflowRunner;
import blue.language.BlueCacheStats;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.coordination.StatusPending;
import blue.repo.mandate.Mandate;
import java.math.BigInteger;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deterministic lifecycle/memory smoke over representative real workflow shapes.
 *
 * <p>This deliberately measures owned cache state instead of heap deltas, weak references, or
 * forced GC. Replaying identical work must settle below a warmed retention ceiling, every
 * workflow-scoped transient reference cache must return to its baseline, and explicit shutdown
 * must release both the Language runtime and the externally owned Coordination runner.</p>
 */
class RepresentativeWorkflowLifecycleSmokeTest {
    private static final String PAYNOTE_RESOURCE =
            "/processor-delay/paynote-resale-reduced-bex.yaml";
    private static final String TRANSIENT_REFERENCE_CACHE = "transientTrustedReferences";
    private static final long TWO_GIB = 2L * 1024L * 1024L * 1024L;

    @Test
    void repeatedPayNoteMandateAndEmbeddedRunsPlateauAndReleaseOwnedState() {
        assertEquals("1.8", System.getProperty("java.specification.version"),
                "memoryIntegrationTest must keep the Java 8 compatibility runtime");
        assertTrue(Runtime.getRuntime().maxMemory() <= TWO_GIB,
                "memoryIntegrationTest must retain its -Xmx2g ceiling");

        OwnedFixture fixture = new OwnedFixture();
        try {
            fixture.prepare();
            fixture.assertTransientStateAtBaseline("after fixture preparation");

            fixture.runRepresentativeSuite();
            RetainedState firstWarmSample = fixture.retainedState();
            fixture.runRepresentativeSuite();
            RetainedState warmedCeiling = RetainedState.maximum(
                    firstWarmSample, fixture.retainedState());

            for (int repetition = 0; repetition < 3; repetition++) {
                fixture.runRepresentativeSuite();
                fixture.retainedState().assertAtOrBelow(warmedCeiling,
                        "repetition " + repetition);
            }

            assertTrue(fixture.metrics.workflowStepsExecuted() > 0L);
            assertTrue(fixture.metrics.computeStepsExecuted() > 0L);
            assertTrue(fixture.metrics.workflowPlanWeightBytes() > 0L);
            assertTrue(fixture.metrics.computePlanWeightBytes() > 0L);
            assertTrue(fixture.runner.workflowPlanCacheSize() > 0);

            long runnerWeightBeforeRuntimeClose =
                    fixture.runner.workflowPlanCacheWeightBytes();
            long computeWeightBeforeRuntimeClose = fixture.metrics.computePlanWeightBytes();
            fixture.closeRuntime();

            BlueCacheStats closedRuntime = fixture.support.blue.cacheStats();
            assertTrue(closedRuntime.isClosed());
            assertEquals(0, closedRuntime.entries());
            assertEquals(0L, closedRuntime.currentWeightBytes());
            assertEquals(runnerWeightBeforeRuntimeClose,
                    fixture.runner.workflowPlanCacheWeightBytes(),
                    "Blue must not close an injected runner it does not own");
            assertEquals(computeWeightBeforeRuntimeClose,
                    fixture.metrics.computePlanWeightBytes(),
                    "runner Compute plans remain externally owned until runner.close()");
            assertEquals(1L, metric(fixture.metrics.languageCounters(), "runtimeCloseCalls"));
            assertTrue(metric(fixture.metrics.languageCounters(),
                    "runtimeCloseReleasedWeightBytes") > 0L);
            assertClosedLanguageCacheGauges(fixture.metrics.languageGauges());

            fixture.closeRunner();
            assertEquals(0, fixture.runner.workflowPlanCacheSize());
            assertEquals(0L, fixture.runner.workflowPlanCacheWeightBytes());
            assertEquals(0L, fixture.metrics.workflowPlanWeightBytes());
            assertEquals(0L, fixture.metrics.computePlanWeightBytes());
        } finally {
            fixture.close();
        }
    }

    private static void assertClosedLanguageCacheGauges(Map<String, Long> gauges) {
        boolean observedRetentionGauge = false;
        for (Map.Entry<String, Long> gauge : gauges.entrySet()) {
            String name = gauge.getKey();
            if (name.startsWith("cache.")
                    && (name.endsWith(".entries")
                    || name.endsWith(".currentWeightBytes")
                    || name.endsWith(".pinnedEntries")
                    || name.endsWith(".derivedEntries"))) {
                observedRetentionGauge = true;
                assertEquals(0L, gauge.getValue().longValue(),
                        "runtime close retained " + name);
            }
        }
        assertTrue(observedRetentionGauge,
                "the Language runtime must publish close-time cache gauges");
    }

    private static long metric(Map<String, Long> metrics, String name) {
        Long value = metrics.get(name);
        return value == null ? 0L : value.longValue();
    }

    private static void assertSuccess(DocumentProcessingResult result) {
        assertEquals(ProcessorStatus.SUCCESS, result.status(), result.failureReason());
    }

    private static Node subscriptionUpdate(String subscriptionId,
                                           String targetSessionId,
                                           String requestId,
                                           String orderSessionId) {
        return new Node()
                .type("Sample/Subscription Update")
                .properties("subscriptionId", new Node().value(subscriptionId))
                .properties("targetSessionId", new Node().value(targetSessionId))
                .properties("update", new Node()
                        .properties("kind", new Node().value("Resale Order Placed"))
                        .properties("inResponseTo", new Node()
                                .properties("requestId", new Node().value(requestId)))
                        .properties("orderSessionId", new Node().value(orderSessionId)));
    }

    private static Node mandateDocument() {
        return new Node()
                .name("Lifecycle memory smoke mandate")
                .type(Mandate.qualifiedName())
                .properties("activateOnAuthorityConfirmation", new Node().value(false))
                .properties("contracts", new Node()
                        .properties("mandateGuarantorChannel",
                                TestTimelineProvider.channel("guarantor"))
                        .properties("authorityHolderChannel",
                                TestTimelineProvider.channel("holder"))
                        .properties("authorizedActorChannel",
                                TestTimelineProvider.channel("authorized")));
    }

    private static Node embeddedDocument() {
        return new Node()
                .name("Lifecycle memory smoke embedded parent")
                .properties("contracts", new Node()
                        .properties("embedded", new Node()
                                .type("Process Embedded")
                                .properties("paths", new Node().items(
                                        new Node().value("/child")))))
                .properties("child", new Node()
                        .name("Lifecycle memory smoke embedded child")
                        .properties("status", new Node().value("idle"))
                        .properties("contracts", new Node()
                                .properties("childChannel",
                                        TestTimelineProvider.channel("child"))
                                .properties("runChild", new Node()
                                        .type("Coordination/Sequential Workflow Operation")
                                        .properties("channel", new Node().value("childChannel"))
                                        .properties("request", new Node().type("Text"))
                                        .properties("steps", new Node().items(
                                                embeddedComputeStep())))));
    }

    private static Node embeddedComputeStep() {
        return new Node()
                .name("Update embedded child")
                .type("Coordination/Compute")
                .properties("do", new Node().items(
                        new Node().properties("$appendChange", new Node()
                                .properties("op", new Node().value("replace"))
                                .properties("path", new Node().value("/status"))
                                .properties("val", new Node().value("processed"))),
                        new Node().properties("$return", new Node()
                                .properties("changeset", new Node()
                                        .properties("$changeset", new Node().value(true))))));
    }

    private static final class OwnedFixture implements AutoCloseable {
        private final BexProcessingMetrics metrics = new BexProcessingMetrics();
        private final BexEngine engine = BexEngine.builder().build();
        private final SequentialWorkflowRunner runner = SequentialWorkflowRunner.withBexEngine(
                engine, 100_000L, metrics);
        private final ComputeWorkflowTestSupport support = ComputeWorkflowTestSupport.create(
                CoordinationProcessorOptions.builder()
                        .bexEngine(engine)
                        .sequentialWorkflowRunner(runner)
                        .defaultComputeGasLimit(100_000L)
                        .processingMetrics(metrics)
                        .build());

        private ResolvedSnapshot paynoteSnapshot;
        private Node paynoteEvent;
        private ResolvedSnapshot mandateSnapshot;
        private Node mandateEvent;
        private ResolvedSnapshot embeddedSnapshot;
        private Node embeddedEvent;
        private int transientBaselineEntries;
        private long transientBaselineWeightBytes;
        private boolean runtimeClosed;
        private boolean runnerClosed;

        private void prepare() {
            DocumentProcessingResult paynoteInitialized = support.blue.initializeDocument(
                    support.yamlResource(PAYNOTE_RESOURCE));
            assertSuccess(paynoteInitialized);
            paynoteSnapshot = paynoteInitialized.snapshot();
            paynoteEvent = CoordinationTestResources.operationRequestEvent(
                    support.blue,
                    support.repository,
                    "hotel-participant",
                    1_700_000_100,
                    "hotelResaleOrderPlaced",
                    "hotelParticipantChannel",
                    subscriptionUpdate("hotel-resale-agreement",
                            "hotel-agreement-session",
                            "hotel-request-a",
                            "hotel-order-session-a"));

            Node mandate = mandateDocument();
            mandate.blue(support.repository.typeAliasBlue());
            Node aliasesResolved = new RepositoryTypeAliasPreprocessor(
                    support.repository).preprocess(mandate);
            ResolvedSnapshot resolvedMandate = support.blue.resolveToSnapshot(
                    support.blue.preprocess(aliasesResolved));
            DocumentProcessingResult mandateInitialized =
                    support.blue.initializeDocument(resolvedMandate);
            assertSuccess(mandateInitialized);
            assertEquals(StatusPending.blueId(),
                    mandateInitialized.canonicalDocument().getAsText("/status/type/blueId"));
            mandateSnapshot = mandateInitialized.snapshot();
            mandateEvent = TestTimelineProvider.timelineEntry(
                    support.blue,
                    support.repository,
                    "guarantor",
                    "guarantor",
                    BigInteger.valueOf(7_000_001L),
                    CoordinationTestResources.operationRequest(
                            "confirmMandateAuthority",
                            "mandateGuarantorChannel",
                            new Node()));

            DocumentProcessingResult embeddedInitialized = support.initialize(
                    embeddedDocument());
            assertSuccess(embeddedInitialized);
            embeddedSnapshot = embeddedInitialized.snapshot();
            embeddedEvent = CoordinationTestResources.operationRequestEvent(
                    support.blue,
                    support.repository,
                    "child",
                    1,
                    "runChild",
                    "childChannel",
                    new Node().value("request"));

            BlueCacheStats.Region transientCache = transientCache();
            transientBaselineEntries = transientCache.entries();
            transientBaselineWeightBytes = transientCache.currentWeightBytes();
        }

        private void runRepresentativeSuite() {
            DocumentProcessingResult paynote = support.blue.processDocument(
                    paynoteSnapshot, paynoteEvent.clone());
            assertSuccess(paynote);
            assertEquals(Boolean.TRUE,
                    paynote.document().get("/orders/package-order-a/hotelOrder/resalePlaced"));

            DocumentProcessingResult mandate = support.blue.processDocument(
                    mandateSnapshot, mandateEvent.clone());
            assertSuccess(mandate);
            assertEquals(BigInteger.valueOf(7_000_001L),
                    mandate.document().get("/authorityConfirmedAt"));

            DocumentProcessingResult embedded = support.blue.processDocument(
                    embeddedSnapshot, embeddedEvent.clone());
            assertSuccess(embedded);
            assertEquals("processed", embedded.document().get("/child/status"));

            assertTransientStateAtBaseline("after representative suite");
        }

        private RetainedState retainedState() {
            BlueCacheStats runtime = support.blue.cacheStats();
            BlueCacheStats.Region transientCache = transientCache();
            return new RetainedState(runtime.entries(),
                    runtime.currentWeightBytes(),
                    transientCache.entries(),
                    transientCache.currentWeightBytes(),
                    runner.workflowPlanCacheSize(),
                    runner.workflowPlanCacheWeightBytes(),
                    metrics.computePlanWeightBytes());
        }

        private void assertTransientStateAtBaseline(String phase) {
            BlueCacheStats.Region transientCache = transientCache();
            assertEquals(transientBaselineEntries, transientCache.entries(),
                    phase + " retained transient reference entries");
            assertEquals(transientBaselineWeightBytes, transientCache.currentWeightBytes(),
                    phase + " retained transient reference weight");
        }

        private BlueCacheStats.Region transientCache() {
            BlueCacheStats.Region region = support.blue.cacheStats().region(
                    TRANSIENT_REFERENCE_CACHE);
            assertNotNull(region, "Language runtime did not expose the transient cache region");
            return region;
        }

        private void closeRuntime() {
            if (!runtimeClosed) {
                runtimeClosed = true;
                support.blue.close();
            }
        }

        private void closeRunner() {
            if (!runnerClosed) {
                runnerClosed = true;
                runner.close();
            }
        }

        @Override
        public void close() {
            try {
                closeRuntime();
            } finally {
                closeRunner();
            }
        }
    }

    private static final class RetainedState {
        private final int languageEntries;
        private final long languageWeightBytes;
        private final int transientEntries;
        private final long transientWeightBytes;
        private final int workflowPlanEntries;
        private final long workflowPlanWeightBytes;
        private final long computePlanWeightBytes;

        private RetainedState(int languageEntries,
                              long languageWeightBytes,
                              int transientEntries,
                              long transientWeightBytes,
                              int workflowPlanEntries,
                              long workflowPlanWeightBytes,
                              long computePlanWeightBytes) {
            this.languageEntries = languageEntries;
            this.languageWeightBytes = languageWeightBytes;
            this.transientEntries = transientEntries;
            this.transientWeightBytes = transientWeightBytes;
            this.workflowPlanEntries = workflowPlanEntries;
            this.workflowPlanWeightBytes = workflowPlanWeightBytes;
            this.computePlanWeightBytes = computePlanWeightBytes;
        }

        private static RetainedState maximum(RetainedState left, RetainedState right) {
            return new RetainedState(
                    Math.max(left.languageEntries, right.languageEntries),
                    Math.max(left.languageWeightBytes, right.languageWeightBytes),
                    Math.max(left.transientEntries, right.transientEntries),
                    Math.max(left.transientWeightBytes, right.transientWeightBytes),
                    Math.max(left.workflowPlanEntries, right.workflowPlanEntries),
                    Math.max(left.workflowPlanWeightBytes, right.workflowPlanWeightBytes),
                    Math.max(left.computePlanWeightBytes, right.computePlanWeightBytes));
        }

        private void assertAtOrBelow(RetainedState ceiling, String phase) {
            assertAtOrBelow(languageEntries, ceiling.languageEntries,
                    phase + " Language cache entries");
            assertAtOrBelow(languageWeightBytes, ceiling.languageWeightBytes,
                    phase + " Language cache weight");
            assertAtOrBelow(transientEntries, ceiling.transientEntries,
                    phase + " transient entries");
            assertAtOrBelow(transientWeightBytes, ceiling.transientWeightBytes,
                    phase + " transient weight");
            assertAtOrBelow(workflowPlanEntries, ceiling.workflowPlanEntries,
                    phase + " workflow-plan entries");
            assertAtOrBelow(workflowPlanWeightBytes, ceiling.workflowPlanWeightBytes,
                    phase + " workflow-plan weight");
            assertAtOrBelow(computePlanWeightBytes, ceiling.computePlanWeightBytes,
                    phase + " Compute-plan weight");
        }

        private static void assertAtOrBelow(long actual, long ceiling, String label) {
            assertTrue(actual <= ceiling,
                    label + " exceeded the warmed ceiling: actual=" + actual
                            + ", ceiling=" + ceiling);
        }
    }
}
