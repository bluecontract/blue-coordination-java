package blue.coordination.engine;

import blue.coordination.engine.api.CommitOutcome;
import blue.coordination.engine.api.CommitStatus;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransitionWorkSnapshot;
import blue.coordination.engine.api.CoordinationProcessingPlan;
import blue.coordination.engine.api.CoordinationTransition;
import blue.coordination.engine.api.FragmentEdgeRecord;
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
import blue.language.model.wire.JsonPointer;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            blue.coordination.processor.CoordinationSubscriptionSnapshot
                    subscriptions = harness.engine.session(sessionId)
                            .subscriptions();
            CoordinationFragmentInventory rootInventory =
                    harness.fragmentStore.requireInventory(
                            harness.engine.session(sessionId)
                                    .fragmentInventoryIdentity());
            List<String> selectedSurface = CoordinationProcessingEngine
                    .planningScopePaths(
                            subscriptions,
                            candidates,
                            rootInventory);
            Set<String> selectedKeys = new LinkedHashSet<String>(candidates);
            Set<String> selectedScopeChain = new LinkedHashSet<String>();
            for (CoordinationSubscriptionOccurrence occurrence
                    : subscriptions.occurrences()) {
                if (!selectedKeys.contains(occurrence.occurrenceKey())) {
                    assertFalse(selectedSurface.contains(
                            occurrence.scopePath()),
                            "unrelated active scope widened the sparse Root");
                    continue;
                }
                assertTrue(selectedSurface.contains(occurrence.scopePath()));
                List<String> scopeSegments = JsonPointer.split(
                        occurrence.scopePath());
                for (int length = 0;
                        length <= scopeSegments.size();
                        length++) {
                    selectedScopeChain.add(JsonPointer.toPointer(
                            scopeSegments.subList(0, length)));
                }
                Set<String> requiredBlueIds = new LinkedHashSet<String>();
                requiredBlueIds.addAll(
                        occurrence.sourceContributionNodeBlueIds());
                requiredBlueIds.addAll(
                        occurrence.dependencyNodeBlueIds());
                for (FragmentEdgeRecord edge : rootInventory.edges()) {
                    if (edge.rootKind()
                            == CoordinationDocumentSplitter
                                    .FragmentRootKind.DOCUMENT
                            && requiredBlueIds.contains(edge.childBlueId())
                            && !CoordinationProcessingEngine
                                    .isContractsDescendantPath(
                                            edge.absolutePointer())) {
                        assertTrue(selectedSurface.contains(
                                edge.absolutePointer()),
                                "selected provider dependency is missing: "
                                        + edge.childBlueId());
                    }
                }
            }
            for (FragmentEdgeRecord edge : rootInventory.edges()) {
                if (edge.rootKind()
                        == CoordinationDocumentSplitter
                                .FragmentRootKind.DOCUMENT
                        && edge.ownerScopePath() != null
                        && selectedScopeChain.contains(
                                JsonPointer.canonicalize(
                                        edge.ownerScopePath()))
                        && isDirectChildOfScope(
                                edge.absolutePointer(),
                                edge.ownerScopePath())
                        && !CoordinationProcessingEngine
                                .isContractsDescendantPath(
                                        edge.absolutePointer())) {
                    assertTrue(selectedSurface.contains(
                            edge.absolutePointer()),
                            "selected scope-chain state is missing: "
                                    + edge.absolutePointer());
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> CoordinationProcessingEngine.planningScopePaths(
                            subscriptions,
                            Arrays.asList(candidates.get(0), candidates.get(0)),
                            rootInventory));
            assertThrows(IllegalArgumentException.class,
                    () -> CoordinationProcessingEngine.planningScopePaths(
                            subscriptions,
                            Arrays.asList("stale-occurrence"),
                            rootInventory));
            blue.coordination.processor.CoordinationSubscriptionSnapshot
                    .PlanningMetrics planningBefore =
                    harness.engine.session(sessionId)
                            .subscriptions().planningMetrics();

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
            blue.coordination.processor.CoordinationSubscriptionSnapshot
                    .PlanningMetrics planningAfter =
                    harness.engine.session(sessionId)
                            .subscriptions().planningMetrics();
            assertEquals(
                    Math.multiplyExact(2L, candidates.size()),
                    planningAfter.candidateScopeLookupCount()
                            - planningBefore.candidateScopeLookupCount(),
                    "candidate scope validation must not visit unrelated "
                            + "subscription occurrences");

            CoordinationTransition transition = harness.engine.execute(
                    first);
            CommitOutcome outcome = harness.engine.commit(transition);
            assertEquals(CommitStatus.COMMITTED, outcome.status());
            assertEquals(1L,
                    harness.fragmentStore
                            .verifiedTransitionPublicationCount());
            assertTrue(
                    harness.fragmentStore
                            .verifiedTransitionBorrowedNodeCount() > 0L,
                    "commit must retain authority-bound verified nodes");
            assertTrue(
                    harness.fragmentStore
                            .verifiedTransitionWireEvidenceCalculationCount()
                            > 0L,
                    "commit must calculate each canonical wire proof once");
            CoordinationFragmentTransitionWorkSnapshot transitionWork =
                    harness.engine.fragmentTransitionWorkSnapshot();
            assertEquals(
                    1L,
                    transitionWork.deltaHits(),
                    transitionWork.toString());
            assertEquals(0L, transitionWork.typedFallbackCount());
            assertEquals(0L, transitionWork.fullBlueprintAttempts());
            assertEquals(0L, transitionWork.fullResultClones());
            assertEquals(0L, transitionWork.fullRootMaterializations());
            assertEquals(0L, transitionWork.retainedIndexFullScans());
            assertTrue(transitionWork.frontierBoundaryGrafts() > 0L);
            assertTrue(transitionWork.unchangedFragmentsShared() > 0L);
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, outcome));
            assertEquals(0L,
                    harness.engine.preparedDeliveryCacheMetricsForTest()
                            .entries());
            assertEquals(2L,
                    harness.engine.planningProjectionCacheMetricsForTest()
                            .entries(),
                    "the prior and published successor projections remain "
                            + "reusable by checkpoint siblings until bounded "
                            + "eviction");
        }
    }

    @Test
    void shouldPublishIncrementalSuccessorOnlyAfterCasAndHitNextPlan() {
        try (Harness harness = new Harness()) {
            DocumentSessionId sessionId = DocumentSessionId.of(
                    "incremental-successor-publication-session");
            harness.engine.addDocument(DocumentRegistration.openOrCreate(
                    sessionId,
                    harness.initializedRoot(),
                    ACTIVATION_ORDER));
            StoredCoordinationEvent firstEvent = harness.engine.prepareEvent(
                    timelineEvent(20L, "first"),
                    EVENT_ORDER);
            List<String> firstCandidates = candidates(
                    harness, sessionId);
            CoordinationTransition transition = harness.engine.execute(
                    harness.engine.planIndexed(
                            sessionId,
                            0L,
                            firstEvent,
                            firstCandidates,
                            PrefetchPolicy.BALANCED));
            long admissionLoads = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            long admissionEntries = harness.engine
                    .planningProjectionCacheMetricsForTest().entries();

            assertEquals(1L, admissionLoads);
            assertEquals(1L, admissionEntries,
                    "execute must keep the successor private before CAS");

            CommitOutcome outcome = harness.engine.commit(transition);

            assertEquals(CommitStatus.COMMITTED, outcome.status());
            assertEquals(admissionLoads, harness.engine
                    .planningProjectionCacheMetricsForTest().loads(),
                    "the authoritative CAS alone must not publish derived "
                            + "state");
            assertEquals(admissionEntries, harness.engine
                    .planningProjectionCacheMetricsForTest().entries());
            assertTrue(harness.engine
                    .installPreparedRootContextAfterPublication(
                            transition, outcome));
            long publishedLoads = harness.engine
                    .planningProjectionCacheMetricsForTest().loads();
            assertEquals(admissionLoads + 1L, publishedLoads,
                    "the commit callback must publish the incrementally "
                            + "prepared successor generation");

            StoredCoordinationEvent secondEvent = harness.engine.prepareEvent(
                    timelineEvent(21L, "second"),
                    order(21L, "second"));
            List<String> secondCandidates = candidates(
                    harness, sessionId);
            long hitsBeforeNextPlan = harness.engine
                    .planningProjectionCacheMetricsForTest().hits();

            CoordinationProcessingPlan next = harness.engine.planIndexed(
                    sessionId,
                    1L,
                    secondEvent,
                    secondCandidates,
                    PrefetchPolicy.BALANCED);

            assertEquals(1L, next.session().currentEpoch());
            assertEquals(publishedLoads, harness.engine
                    .planningProjectionCacheMetricsForTest().loads(),
                    "the next plan must not cold-compile its admitted "
                            + "projection");
            assertTrue(harness.engine
                            .planningProjectionCacheMetricsForTest().hits()
                            > hitsBeforeNextPlan,
                    "the next plan must hit the published successor");
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
        return timelineEvent(20L, "invoke");
    }

    private static Node timelineEvent(long timestamp, String message) {
        return RepositoryIndependentCoordinationTypes.timelineEntry(
                "timeline-a",
                "actor-a",
                BigInteger.valueOf(timestamp),
                RepositoryIndependentCoordinationTypes.chatMessage(
                        message));
    }

    private static List<String> candidates(
            Harness harness,
            DocumentSessionId sessionId) {
        List<String> result = new ArrayList<String>();
        for (CoordinationSubscriptionOccurrence occurrence
                : harness.engine.session(sessionId)
                        .subscriptions().occurrences()) {
            if (CHANNEL_KEY.equals(occurrence.channelKey())) {
                result.add(occurrence.occurrenceKey());
            }
        }
        assertFalse(result.isEmpty(),
                "fixture must retain an indexed Timeline occurrence");
        return result;
    }

    private static boolean isDirectChildOfScope(
            String pointer,
            String scopePath) {
        List<String> value = JsonPointer.split(pointer);
        List<String> scope = JsonPointer.split(scopePath);
        return value.size() == scope.size() + 1
                && value.subList(0, scope.size()).equals(scope);
    }

    private static ExternalOrderKey order(long sequence, String label) {
        return ExternalOrderKey.of(Arrays.<Object>asList(sequence, label));
    }

    private static final class Harness implements AutoCloseable {
        private final RepositoryIndependentCoordinationTestRuntime runtime;
        private final CoordinationProcessingEngine engine;
        private final InMemoryCoordinationFragmentStore fragmentStore;

        private Harness() {
            runtime = RepositoryIndependentCoordinationTestRuntime.open();
            fragmentStore = new InMemoryCoordinationFragmentStore(
                    CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
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
