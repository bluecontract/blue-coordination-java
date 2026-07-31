package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.ChannelCheckpointContext;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.CoordinationConfiguredProcessorFactory;
import blue.language.processor.CoordinationCurrentRootDeliveryPlanDeriver;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.repo.BlueRepository;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.TimelineChannel;
import blue.repo.myos.MyOSTimelineChannel;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationIndexedDeliveryPlannerTest {

    @Test
    void shouldProduceTheCompatibilityPlannerDeliveryFromAnExactIndex() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching", "other"))) {
            Node event = fixture.event("matching", 2);
            ExternalOrderKey order = eventOrder(event);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeys(snapshot, "matching");
            CoordinationHostQuotaSession hostQuotas =
                    CoordinationHostQuotaSession.observing();

            // When
            CoordinationPreparedDelivery indexed =
                    fixture.planner.prepare(
                            fixture.rootBlueId,
                            BlueIdCalculator.calculateBlueId(event),
                            snapshot,
                            candidates,
                            fixture.provider(event),
                            fixture.revision,
                            order,
                            hostQuotas);
            ExternalDeliveryPlan compatibility =
                    CoordinationDeliveryPlanning
                            .currentRootCompatibilityDeriver(
                                    fixture.blue
                                            .getDocumentProcessor())
                            .derive(
                                    fixture.root,
                                    event);

            // Then
            assertEquals(
                    deliverySignatures(compatibility),
                    deliverySignatures(
                            indexed.deliveryPlan()));
            assertEquals(
                    deliverySignatures(compatibility),
                    deliverySignatures(
                            indexed.evidence()
                                    .deliveries()));
            assertEquals(
                    activeSurfaceSignatures(
                            compatibility
                                    .activeSubscriptionIntervals()),
                    activeSurfaceSignatures(
                            indexed.evidence()
                                    .activeSubscriptionIntervals()));
            assertEquals(
                    candidates,
                    indexed.preselectedOccurrenceOrder());
            assertEquals(
                    fixture.rootBlueId,
                    indexed.evidence().rootBlueId());
            assertEquals(
                    BlueIdCalculator.calculateBlueId(event),
                    indexed.evidence().eventBlueId());
            assertTrue(
                    indexed.requiredSeedFragmentIdentities()
                            .contains(fixture.rootBlueId));
            assertEquals(
                    candidates.size(),
                    hostQuotas.quantity(
                            CoordinationHostQuotaSchedule
                                    .INDEXED_CANDIDATE_VALIDATED));
            assertEquals(
                    indexed.prefetchIdentities().size(),
                    hostQuotas.quantity(
                            CoordinationHostQuotaSchedule
                                    .PREFETCH_IDENTITY_CONSTRUCTED));
        }
    }

    @Test
    void shouldNotEvaluateUnrelatedOccurrenceHeadersDuringIndexedPlanning() {
        // Given
        AtomicInteger unrelatedHeaderEvaluations =
                new AtomicInteger();
        try (Fixture fixture = fixture(
                channels("matching", "unrelated"),
                new CountingTimelineChannelProcessor(
                        "unrelated",
                        unrelatedHeaderEvaluations))) {
            Node event = fixture.event("matching", 71);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeys(snapshot, "matching");
            unrelatedHeaderEvaluations.set(0);

            // When
            CoordinationPreparedDelivery prepared =
                    fixture.planner.prepare(
                            fixture.rootBlueId,
                            BlueIdCalculator.calculateBlueId(
                                    event),
                            snapshot,
                            candidates,
                            fixture.provider(event),
                            fixture.revision,
                            eventOrder(event));

            // Then
            assertEquals(
                    candidates,
                    prepared.preselectedOccurrenceOrder());
            assertEquals(
                    0,
                    unrelatedHeaderEvaluations.get(),
                    "an unrelated retained occurrence must stay unopened");
        }
    }

    @Test
    void shouldRejectSnapshotAfterTimelineSubtypeRegistryChanges() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event =
                    fixture.event(
                            "matching", 2);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            CoordinationProcessors.registerTimelineSubtype(
                    fixture.blue,
                    MyOSTimelineChannel.class);

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(
                                                    event),
                                    snapshot,
                                    candidateKeys(
                                            snapshot,
                                            "matching"),
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "runtime or projection identity "
                                    + "mismatch"),
                    failure.getMessage());
        }
    }

    @Test
    void shouldRejectAnOmittedCanonicalCandidate() {
        // Given
        try (Fixture fixture = fixture(
                channels("same", "same"))) {
            Node event = fixture.event("same", 3);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> complete =
                    candidateKeys(snapshot, "same");

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    complete.subList(
                                            0,
                                            complete.size() - 1),
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains("omits"));
        }
    }

    @Test
    void shouldRejectCandidatesInTheWrongCanonicalOrder() {
        // Given
        try (Fixture fixture = fixture(
                channels("same", "same"))) {
            Node event = fixture.event("same", 4);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> reversed =
                    new ArrayList<>(
                            candidateKeys(snapshot, "same"));
            Collections.reverse(reversed);

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    reversed,
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "wrong canonical order"));
        }
    }

    @Test
    void shouldRejectAnIndexedFalsePositiveUnderTheExactCandidateContract() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching", "other"))) {
            Node event = fixture.event("matching", 5);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    new ArrayList<>(
                            candidateKeys(
                                    snapshot, "matching"));
            for (CoordinationSubscriptionOccurrence occurrence
                    : snapshot.occurrences()) {
                if (!"matching".equals(
                        occurrence.channelKey())) {
                    candidates.add(
                            occurrence.occurrenceKey());
                }
            }

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    candidates,
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "illegal extras"));
        }
    }

    @Test
    void shouldRejectARevisionThatDoesNotBindTheSnapshot() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 6);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    candidateKeys(
                                            snapshot,
                                            "matching"),
                                    fixture.provider(event),
                                    fixture.revision + 1L,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "Root revision mismatch"));
        }
    }

    @Test
    void shouldRejectADuplicateIndexedCandidate() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 7);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            String candidate =
                    candidateKeys(
                            snapshot, "matching")
                            .get(0);

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    Arrays.asList(
                                            candidate,
                                            candidate),
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "Duplicate indexed candidate"));
        }
    }

    @Test
    void shouldRejectIndexedValidationBeforeTheOverLimitCandidateIsAdmitted() {
        // Given
        try (Fixture fixture = fixture(
                channels("same", "same"))) {
            Node event = fixture.event("same", 70);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeys(snapshot, "same");
            CoordinationHostQuotaSession hostQuotas =
                    CoordinationHostQuotaSession.observing(
                            CoordinationHostQuotaTestSupport
                                    .limitedIndexedCandidates(1));

            // When
            CoordinationHostQuotaExceededException failure =
                    assertThrows(
                            CoordinationHostQuotaExceededException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    candidates,
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event),
                                    hostQuotas));

            // Then
            assertEquals(
                    "maxIndexedCandidatesPerPlan",
                    failure.limitName());
            assertEquals(2L, failure.attemptedQuantity());
            assertEquals(1L, failure.admittedQuantity());
            assertEquals(
                    1L,
                    hostQuotas.quantity(
                            CoordinationHostQuotaSchedule
                                    .INDEXED_CANDIDATE_VALIDATED));
            CoordinationHostQuotaTraceEntry admitted =
                    hostQuotas.trace().get(0);
            assertEquals(
                    "prepare-indexed-delivery",
                    admitted.operation());
            assertEquals(
                    "/indexed-candidates/0",
                    admitted.logicalPath());
        }
    }

    @Test
    void shouldRejectAnEventAtTheSnapshotActivationFrontier() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 8);
            ExternalOrderKey frontier =
                    eventOrder(event);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(frontier);

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    Collections
                                            .<String>emptyList(),
                                    fixture.provider(event),
                                    fixture.revision,
                                    frontier));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "not after"));
        }
    }

    @Test
    void shouldRejectARootIdentityThatDoesNotBindTheSnapshot() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 9);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    "wrong-root-identity",
                                    BlueIdCalculator
                                            .calculateBlueId(event),
                                    snapshot,
                                    candidateKeys(
                                            snapshot,
                                            "matching"),
                                    fixture.provider(event),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "Root identity mismatch"));
        }
    }

    @Test
    void shouldRejectEventContentThatDoesNotVerifyItsRequestedIdentity() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 10);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            String eventBlueId =
                    BlueIdCalculator.calculateBlueId(event);
            Node tampered = event.clone()
                    .properties(
                            "tampered",
                            new Node().value(true));

            // When
            InvalidExecutionEvidenceException failure =
                    assertThrows(
                            InvalidExecutionEvidenceException.class,
                            () -> fixture.planner.prepare(
                                    fixture.rootBlueId,
                                    eventBlueId,
                                    snapshot,
                                    candidateKeys(
                                            snapshot,
                                            "matching"),
                                    fixture.provider(
                                            eventBlueId,
                                            tampered),
                                    fixture.revision,
                                    eventOrder(event)));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "Provider returned content with BlueId"));
        }
    }

    @Test
    void shouldRejectPersistedSnapshotContentThatRetiresAnActiveOccurrence() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            Map<String, Object> persisted =
                    mutablePersistedSnapshot(snapshot);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> occurrences =
                    (List<Map<String, Object>>)
                            persisted.get("occurrences");
            occurrences.get(0).put(
                    "endAtRootRevision",
                    fixture.revision);

            // When
            IllegalArgumentException failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> CoordinationSubscriptionSnapshot
                                    .rehydrate(persisted));

            // Then
            assertTrue(
                    failure.getMessage().contains(
                            "retired occurrence"));
        }
    }

    @Test
    void shouldReturnDefensiveAndUnmodifiablePreparationViews() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 11);
            CoordinationPreparedDelivery prepared =
                    fixture.prepared(event);
            CoordinationDocumentSplitter.SplitGraph
                    documentGraph =
                    new CoordinationDocumentSplitter(
                            fixture.blue
                                    .getDocumentProcessor())
                            .splitDocument(fixture.root);
            CoordinationDocumentSplitter.SplitGraph
                    eventGraph =
                    CoordinationDocumentSplitter
                            .forEventSplitting()
                            .splitEvent(event);

            // When
            CoordinationProcessingPreparation result =
                    CoordinationProcessingPreparation.combine(
                            prepared,
                            documentGraph,
                            eventGraph);
            Node mutableReference =
                    result.rootReference();
            mutableReference.blueId("tampered");

            // Then
            assertEquals(
                    fixture.rootBlueId,
                    result.rootReference().getBlueId());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> result
                            .selectedScopeChainIdentities()
                            .clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> result
                            .documentEdgeOccurrences()
                            .clear());
        }
    }

    @Test
    void shouldPermitOnlyRuntimeSelectedHandlerBodiesAtTheRoutedTarget() {
        // Given
        try (Fixture fixture = fixture(
                channels("matching"))) {
            Node event = fixture.event("matching", 12);
            CoordinationPreparedDelivery prepared =
                    fixture.prepared(event);
            CoordinationDeliveryDiagnostic delivery =
                    prepared.sourceDeliveries().get(0);
            CoordinationSemanticDemandBoundary boundary =
                    prepared.demandBoundary();

            // When
            boolean selected = boundary.permits(
                    new CoordinationSemanticDemandBoundary.Demand(
                            CoordinationSemanticDemandBoundary.Kind
                                    .SELECTED_HANDLER_BODY,
                            delivery.scopePath(),
                            delivery.targetChannelKey(),
                            "selected-body-blue-id",
                            true));
            boolean notYetSelected = boundary.permits(
                    new CoordinationSemanticDemandBoundary.Demand(
                            CoordinationSemanticDemandBoundary.Kind
                                    .SELECTED_HANDLER_BODY,
                            delivery.scopePath(),
                            delivery.targetChannelKey(),
                            "unselected-body-blue-id",
                            false));
            boolean unrelated = boundary.permits(
                    new CoordinationSemanticDemandBoundary.Demand(
                            CoordinationSemanticDemandBoundary.Kind
                                    .SELECTED_HANDLER_BODY,
                            "/unrelated",
                            delivery.targetChannelKey(),
                            "unrelated-body-blue-id",
                            true));

            // Then
            assertTrue(selected);
            assertFalse(notYetSelected);
            assertFalse(unrelated);
        }
    }

    @Test
    void shouldRouteAnIndexedSourceToAPeerTargetWhileCheckpointingOnlyTheSource() {
        // Given
        try (Fixture fixture = fixture(
                routingContracts(false))) {
            Node event = fixture.operationEvent(101);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeysForChannels(
                            snapshot, "alice");

            // When
            CoordinationPreparedDelivery prepared =
                    fixture.prepare(
                            event, snapshot, candidates);
            ProcessingDebugResult debug =
                    fixture.execute(event, prepared);

            // Then
            CoordinationDeliveryDiagnostic delivery =
                    prepared.sourceDeliveries().get(0);
            assertEquals("alice", delivery.sourceChannelKey());
            assertEquals("bob", delivery.targetChannelKey());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            debug.processResult()));
            assertEquals(
                    BigInteger.ONE,
                    debug.processResult().document().get(
                            "/counter"));
            assertNotNull(
                    checkpoint(
                            debug.processResult().document(),
                            "alice"));
            assertNull(
                    checkpoint(
                            debug.processResult().document(),
                            "bob"));
        }
    }

    @Test
    void shouldCoalesceIndexedPeerRoutesWithoutCheckpointingAStaleSource() {
        // Given
        try (Fixture fixture = fixture(
                routingContracts(true),
                new SelectiveFreshnessTimelineProcessor(
                        "alice"))) {
            Node event = fixture.operationEvent(102);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeysForChannels(
                            snapshot,
                            "alice",
                            "aliceMirror");

            // When
            CoordinationPreparedDelivery prepared =
                    fixture.prepare(
                            event, snapshot, candidates);
            ProcessingDebugResult debug =
                    fixture.execute(event, prepared);

            // Then
            assertEquals(2, prepared.sourceDeliveries().size());
            assertEquals(
                    prepared.sourceDeliveries().get(0)
                            .logicalDeliveryKey(),
                    prepared.sourceDeliveries().get(1)
                            .logicalDeliveryKey());
            assertEquals(
                    "bob",
                    prepared.sourceDeliveries().get(0)
                            .targetChannelKey());
            assertEquals(
                    "bob",
                    prepared.sourceDeliveries().get(1)
                            .targetChannelKey());
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    debug.processResult().status(),
                    ProcessingResultTestSupport.diagnosticMessage(
                            debug.processResult()));
            assertEquals(
                    BigInteger.ONE,
                    debug.processResult().document().get(
                            "/counter"),
                    "coalesced peer routes must execute the target once");
            assertNull(
                    checkpoint(
                            debug.processResult().document(),
                            "alice"));
            assertNotNull(
                    checkpoint(
                            debug.processResult().document(),
                            "aliceMirror"));
            assertNull(
                    checkpoint(
                            debug.processResult().document(),
                            "bob"));
        }
    }

    @Test
    void shouldProduceTheSameIndexedPeerRouteFromFragmentedProvidersWithoutOpeningBodies() {
        // Given
        try (Fixture fixture = fixture(
                routingContracts(false))) {
            Node event = fixture.operationEvent(103);
            CoordinationSubscriptionSnapshot snapshot =
                    fixture.project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            List<String> candidates =
                    candidateKeysForChannels(
                            snapshot, "alice");
            CoordinationPreparedDelivery inline =
                    fixture.prepare(
                            event, snapshot, candidates);
            CoordinationDocumentSplitter.SplitGraph
                    documentGraph =
                    new CoordinationDocumentSplitter(
                            fixture.blue
                                    .getDocumentProcessor())
                            .splitDocument(fixture.root);
            CoordinationDocumentSplitter.SplitGraph
                    eventGraph =
                    CoordinationDocumentSplitter
                            .forEventSplitting()
                            .splitEvent(event);
            Set<String> executableBodyBlueIds =
                    executableBodyBlueIds(
                            documentGraph);
            RecordingNodeProvider fragmentedProvider =
                    new RecordingNodeProvider(
                            new SequentialNodeProvider(
                                    documentGraph.provider(),
                                    eventGraph.provider()));

            // When
            CoordinationPreparedDelivery fragmented =
                    fixture.prepare(
                            event,
                            snapshot,
                            candidates,
                            fragmentedProvider);

            // Then
            assertFalse(
                    executableBodyBlueIds.isEmpty(),
                    "the fixture must contain a separately retained "
                            + "workflow body");
            assertEquals(
                    inline.deliveryPlanIdentity(),
                    fragmented.deliveryPlanIdentity());
            assertEquals(
                    deliverySignatures(
                            inline.deliveryPlan()),
                    deliverySignatures(
                            fragmented.deliveryPlan()));
            assertEquals(
                    "bob",
                    fragmented.sourceDeliveries().get(0)
                            .targetChannelKey());
            assertTrue(
                    Collections.disjoint(
                            executableBodyBlueIds,
                            fragmentedProvider.requestedBlueIds()),
                    "indexed planning must use immutable headers without "
                            + "opening an executable body");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutablePersistedSnapshot(
            CoordinationSubscriptionSnapshot snapshot) {
        Map<String, Object> persisted =
                new LinkedHashMap<>(snapshot.toMap());
        List<Map<String, Object>> occurrences =
                new ArrayList<>();
        for (Map<String, Object> occurrence
                : (List<Map<String, Object>>)
                persisted.get("occurrences")) {
            occurrences.add(
                    new LinkedHashMap<>(occurrence));
        }
        persisted.put("occurrences", occurrences);
        return persisted;
    }

    private static List<String> candidateKeys(
            CoordinationSubscriptionSnapshot snapshot,
            String timelineId) {
        List<CoordinationSubscriptionOccurrence> matching =
                new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            if (occurrence.headerFieldBlueIds()
                    .containsKey("timeline")) {
                matching.add(occurrence);
            }
        }
        /*
         * Both Timeline Channels in this fixture carry the same Timeline
         * value when timelineId is "same"; otherwise the raw key identifies
         * the one intended match.
         */
        if (!"same".equals(timelineId)) {
            matching.removeIf(
                    occurrence ->
                            !timelineId.equals(
                                    occurrence.channelKey()));
        }
        matching.sort(
                Comparator
                        .comparingInt(
                                CoordinationSubscriptionOccurrence
                                        ::order)
                        .thenComparing(
                                CoordinationSubscriptionOccurrence
                                        ::channelKey));
        List<String> result = new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : matching) {
            result.add(occurrence.occurrenceKey());
        }
        return result;
    }

    private static List<String> candidateKeysForChannels(
            CoordinationSubscriptionSnapshot snapshot,
            String... channelKeys) {
        Set<String> selected =
                new HashSet<>(
                        Arrays.asList(channelKeys));
        List<CoordinationSubscriptionOccurrence> matching =
                new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : snapshot.occurrences()) {
            if (selected.contains(
                    occurrence.channelKey())) {
                matching.add(occurrence);
            }
        }
        matching.sort(
                Comparator
                        .comparingInt(
                                CoordinationSubscriptionOccurrence
                                        ::order)
                        .thenComparing(
                                CoordinationSubscriptionOccurrence
                                        ::channelKey)
                        .thenComparing(
                                CoordinationSubscriptionOccurrence
                                        ::occurrenceKey));
        List<String> result =
                new ArrayList<>();
        for (CoordinationSubscriptionOccurrence occurrence
                : matching) {
            result.add(
                    occurrence.occurrenceKey());
        }
        return result;
    }

    private static Map<String, Node> routingContracts(
            boolean includeMirror) {
        Map<String, Node> contracts =
                new LinkedHashMap<>();
        contracts.put(
                "alice",
                TestTimelineProvider.channel(
                        "alice-timeline",
                        "alice-account"));
        if (includeMirror) {
            contracts.put(
                    "aliceMirror",
                    TestTimelineProvider.channel(
                            "alice-timeline",
                            "alice-account"));
        }
        contracts.put(
                "bob",
                TestTimelineProvider.channel(
                        "bob-timeline",
                        "bob-account"));
        contracts.put(
                "increment",
                incrementOperation("bob"));
        return contracts;
    }

    private static Node incrementOperation(
            String channelKey) {
        return new Node()
                .type("Coordination/Sequential Workflow Operation")
                .properties(
                        "channel",
                        new Node().value(channelKey))
                .properties(
                        "request",
                        new Node().type("Integer"))
                .properties(
                        "steps",
                        new Node().items(
                                new Node()
                                        .type("Coordination/Compute")
                                        .properties(
                                                "do",
                                                new Node().items(
                                                        new Node()
                                                                .properties(
                                                                        "$appendChange",
                                                                        new Node()
                                                                                .properties(
                                                                                        "op",
                                                                                        new Node().value(
                                                                                                "replace"))
                                                                                .properties(
                                                                                        "path",
                                                                                        new Node().value(
                                                                                                "/counter"))
                                                                                .properties(
                                                                                        "val",
                                                                                        new Node().properties(
                                                                                                "$add",
                                                                                                new Node().items(
                                                                                                        new Node().properties(
                                                                                                                "$document",
                                                                                                                new Node().value(
                                                                                                                        "/counter")),
                                                                                                        new Node().value(
                                                                                                                1))))),
                                                        new Node()
                                                                .properties(
                                                                        "$return",
                                                                        new Node().value(
                                                                                true))))));
    }

    private static Node operationRequest() {
        return new Node()
                .type(OperationRequest.qualifiedName())
                .properties(
                        "operation",
                        new Node().value("increment"))
                .properties(
                        "channel",
                        new Node().value("bob"))
                .properties(
                        "request",
                        new Node().value(7));
    }

    private static Set<String> executableBodyBlueIds(
            CoordinationDocumentSplitter.SplitGraph graph) {
        Set<String> result =
                new HashSet<>();
        for (CoordinationDocumentSplitter.FragmentMetadata metadata
                : graph.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter.FragmentKind
                    .EXECUTABLE_BODY) {
                result.add(metadata.blueId());
            }
        }
        return result;
    }

    private static Node checkpoint(
            Node document,
            String channelKey) {
        try {
            return document.getAsNode(
                    "/contracts/checkpoint/entries/"
                            + JsonPointer.escape(channelKey)
                            + "/subject");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, Node> channels(
            String... timelineIds) {
        Map<String, Node> result = new LinkedHashMap<>();
        for (int index = 0;
                index < timelineIds.length;
                index++) {
            String key = timelineIds.length == 1
                    ? timelineIds[index]
                    : index == 0
                    ? timelineIds[index]
                    : "channel-" + index;
            result.put(
                    key,
                    TestTimelineProvider.channel(
                            timelineIds[index]));
        }
        return result;
    }

    private static List<String> deliverySignatures(
            ExternalDeliveryPlan plan) {
        return deliverySignatures(
                plan.deliveries());
    }

    private static List<String> deliverySignatures(
            List<ExternalDeliverySnapshot> deliveries) {
        List<String> result = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery
                : deliveries) {
            result.add(
                    delivery.scopePath()
                            + "|"
                            + delivery.channelKey()
                            + "|"
                            + delivery.effectiveTypeBlueId()
                            + "|"
                            + delivery.checkpointDomainBlueId()
                            + "|"
                            + delivery.checkpointSubjectBlueId());
        }
        return result;
    }

    private static List<String> activeSurfaceSignatures(
            List<SubscriptionDelta.Entry> intervals) {
        List<String> result = new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            result.add(
                    interval.scopePath()
                            + "|"
                            + interval.channelKey()
                            + "|"
                            + interval.effectiveTypeBlueId()
                            + "|"
                            + interval.order()
                            + "|"
                            + interval
                            .sourceContributionNodeBlueIds()
                            + "|"
                            + interval.subscriptionKeys()
                            + "|"
                            + interval.checkpointDomainBlueId()
                            + "|"
                            + interval.dependencies()
                            .deterministicDependencyNodeBlueIds());
        }
        return result;
    }

    private static ExternalOrderKey eventOrder(Node event) {
        List<Object> components = new ArrayList<>();
        Node timestamp = event.getProperties().get(
                "timestamp");
        Object value = timestamp.getValue();
        components.add(
                value instanceof BigInteger
                        ? value
                        : BigInteger.valueOf(
                                ((Number) value).longValue()));
        Node timeline = event.getProperties().get(
                "timeline");
        components.add(
                BlueIdCalculator.calculateBlueId(
                        timeline));
        components.add(
                BlueIdCalculator.calculateBlueId(
                        event));
        return ExternalOrderKey.of(components);
    }

    private static Fixture fixture(
            Map<String, Node> contracts) {
        return fixture(contracts, null);
    }

    private static Fixture fixture(
            Map<String, Node> contracts,
            ChannelProcessor<TimelineChannel>
                    timelineProcessor) {
        BlueRepository repository =
                BlueRepository.latest();
        Blue blue =
                CoordinationTestResources
                        .configuredBlue(repository);
        CoordinationProcessors.registerWith(blue);
        if (timelineProcessor != null) {
            blue.registerContractProcessor(
                    timelineProcessor);
        }
        Node authored = new Node()
                .blue(repository.typeAliasBlue())
                .name("Indexed delivery planner")
                .properties(
                        "counter",
                        new Node().value(0))
                .properties(
                        "contracts",
                        new Node().properties(contracts));
        Node exact =
                blue.preprocess(authored);
        DocumentProcessingResult initialized =
                blue.initializeDocument(exact);
        assertEquals(
                ProcessorStatus.SUCCESS,
                initialized.status());
        return new Fixture(
                repository,
                blue,
                initialized.document());
    }

    private static final class
    CountingTimelineChannelProcessor
            implements ChannelProcessor<TimelineChannel> {
        private final TimelineChannelProcessor delegate =
                new TimelineChannelProcessor();
        private final ExternalChannelSubscriptionFunctions<
                TimelineChannel> subscriptionFunctions;

        private CountingTimelineChannelProcessor(
                String observedTimelineId,
                AtomicInteger headerEvaluations) {
            this.subscriptionFunctions =
                    new CountingTimelineSubscriptionFunctions(
                            observedTimelineId,
                            headerEvaluations);
        }

        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                TimelineChannel>
        externalSubscriptionFunctions() {
            return subscriptionFunctions;
        }

        @Override
        public ChannelEvaluation evaluate(
                TimelineChannel contract,
                ChannelEvaluationContext context) {
            return delegate.evaluate(
                    contract, context);
        }

        @Override
        public String eventId(
                TimelineChannel contract,
                ChannelEvaluationContext context) {
            return delegate.eventId(
                    contract, context);
        }

        @Override
        public boolean isNewerEvent(
                TimelineChannel contract,
                ChannelCheckpointContext context) {
            return delegate.isNewerEvent(
                    contract, context);
        }
    }

    private static final class
    CountingTimelineSubscriptionFunctions
            implements ExternalChannelSubscriptionFunctions<
            TimelineChannel> {
        private final String observedTimelineId;
        private final AtomicInteger headerEvaluations;

        private CountingTimelineSubscriptionFunctions(
                String observedTimelineId,
                AtomicInteger headerEvaluations) {
            this.observedTimelineId =
                    observedTimelineId;
            this.headerEvaluations =
                    headerEvaluations;
        }

        @Override
        public List<String> channelKeys(
                TimelineChannel contract) {
            recordHeaderEvaluation(contract);
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.channelKeys(contract);
        }

        @Override
        public List<String> channelKeys(
                TimelineChannel contract,
                ExternalChannelFunctionContext context) {
            recordHeaderEvaluation(contract);
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.channelKeys(
                            contract, context);
        }

        @Override
        public List<String> eventKeys(Node event) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.eventKeys(event);
        }

        @Override
        public List<String> eventKeys(
                Node event,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.eventKeys(
                            event, context);
        }

        @Override
        public boolean accepts(
                TimelineChannel contract,
                Node event) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.accepts(
                            contract, event);
        }

        @Override
        public boolean accepts(
                TimelineChannel contract,
                Node event,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.accepts(
                            contract, event, context);
        }

        @Override
        public Node payload(
                TimelineChannel contract,
                Node event,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.payload(
                            contract, event, context);
        }

        @Override
        public Node checkpointSubject(
                TimelineChannel contract,
                Node event,
                Node payload) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.checkpointSubject(
                            contract, event, payload);
        }

        @Override
        public Node checkpointSubject(
                TimelineChannel contract,
                Node event,
                Node payload,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.checkpointSubject(
                            contract,
                            event,
                            payload,
                            context);
        }

        @Override
        public String handlerChannelKey(
                TimelineChannel contract,
                Node event,
                Node payload,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.handlerChannelKey(
                            contract,
                            event,
                            payload,
                            context);
        }

        @Override
        public String logicalDeliveryKey(
                TimelineChannel contract,
                Node event,
                Node payload,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE.logicalDeliveryKey(
                            contract,
                            event,
                            payload,
                            context);
        }

        @Override
        public String checkpointDomainDiscriminator(
                TimelineChannel contract) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE
                    .checkpointDomainDiscriminator(
                            contract);
        }

        @Override
        public String checkpointDomainDiscriminator(
                TimelineChannel contract,
                ExternalChannelFunctionContext context) {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE
                    .checkpointDomainDiscriminator(
                            contract, context);
        }

        private void recordHeaderEvaluation(
                TimelineChannel contract) {
            if (contract != null
                    && contract.getTimeline() != null
                    && observedTimelineId.equals(
                    contract.getTimeline()
                            .getTimelineId())) {
                headerEvaluations.incrementAndGet();
            }
        }
    }

    private static final class
    SelectiveFreshnessTimelineProcessor
            implements ChannelProcessor<TimelineChannel> {
        private final TimelineChannelProcessor delegate =
                new TimelineChannelProcessor();
        private final String staleChannelKey;

        private SelectiveFreshnessTimelineProcessor(
                String staleChannelKey) {
            this.staleChannelKey =
                    staleChannelKey;
        }

        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                TimelineChannel>
        externalSubscriptionFunctions() {
            return TimelineExternalSubscriptionFunctions
                    .INSTANCE;
        }

        @Override
        public ChannelEvaluation evaluate(
                TimelineChannel contract,
                ChannelEvaluationContext context) {
            return delegate.evaluate(
                    contract, context);
        }

        @Override
        public String eventId(
                TimelineChannel contract,
                ChannelEvaluationContext context) {
            return delegate.eventId(
                    contract, context);
        }

        @Override
        public boolean isNewerEvent(
                TimelineChannel contract,
                ChannelCheckpointContext context) {
            return !staleChannelKey.equals(
                    context.channelKey());
        }
    }

    private static final class RecordingNodeProvider
            implements NodeProvider {
        private final NodeProvider delegate;
        private final Set<String> requestedBlueIds =
                new HashSet<>();

        private RecordingNodeProvider(
                NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            requestedBlueIds.add(blueId);
            return delegate.fetchByBlueId(blueId);
        }

        private Set<String> requestedBlueIds() {
            return Collections.unmodifiableSet(
                    new HashSet<>(
                            requestedBlueIds));
        }
    }

    private static final class Fixture
            implements AutoCloseable {
        private static final long REVISION = 11L;
        private final BlueRepository repository;
        private final Blue blue;
        private final Node root;
        private final String rootBlueId;
        private final long revision;
        private final CoordinationSubscriptionProjector
                projector;
        private final CoordinationIndexedDeliveryPlanner
                planner;

        private Fixture(
                BlueRepository repository,
                Blue blue,
                Node root) {
            this.repository = repository;
            this.blue = blue;
            this.root = root;
            this.rootBlueId =
                    BlueIdCalculator.calculateBlueId(
                            root);
            this.revision = REVISION;
            this.projector =
                    CoordinationDeliveryPlanning
                            .subscriptionProjector(
                                    blue.getDocumentProcessor());
            this.planner =
                    new CoordinationIndexedDeliveryPlanner(
                            blue.getDocumentProcessor());
        }

        private CoordinationSubscriptionSnapshot project(
                ExternalOrderKey frontier) {
            return projector.projectCurrent(
                    root, revision, frontier);
        }

        private Node event(
                String timelineId,
                int timestamp) {
            return TestTimelineProvider.timelineEntry(
                    blue,
                    repository,
                    timelineId,
                    timestamp,
                    TestTimelineProvider.chatMessage(
                            "event-" + timestamp));
        }

        private Node operationEvent(
                int timestamp) {
            return TestTimelineProvider.timelineEntry(
                    blue,
                    repository,
                    "alice-timeline",
                    "alice-account",
                    BigInteger.valueOf(timestamp),
                    operationRequest());
        }

        private NodeProvider provider(Node event) {
            return provider(
                    BlueIdCalculator.calculateBlueId(event),
                    event);
        }

        private NodeProvider provider(
                String eventBlueId,
                Node suppliedEvent) {
            Map<String, Node> exact =
                    new LinkedHashMap<>();
            exact.put(rootBlueId, root.clone());
            exact.put(
                    eventBlueId,
                    suppliedEvent.clone());
            return blueId -> {
                Node node = exact.get(blueId);
                return node == null
                        ? Collections.emptyList()
                        : Arrays.asList(node.clone());
            };
        }

        private CoordinationPreparedDelivery prepare(
                Node event,
                CoordinationSubscriptionSnapshot snapshot,
                List<String> candidates) {
            return prepare(
                    event,
                    snapshot,
                    candidates,
                    provider(event));
        }

        private CoordinationPreparedDelivery prepare(
                Node event,
                CoordinationSubscriptionSnapshot snapshot,
                List<String> candidates,
                NodeProvider exactProvider) {
            return planner.prepare(
                    rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            event),
                    snapshot,
                    candidates,
                    exactProvider,
                    revision,
                    eventOrder(event));
        }

        private ProcessingDebugResult execute(
                Node event,
                CoordinationPreparedDelivery prepared) {
            try (DocumentProcessor processor =
                         CoordinationConfiguredProcessorFactory
                                 .withExecutionEvidencePlan(
                                         blue,
                                         null,
                                         prepared.evidence())) {
                return processor.processDocumentWithTrace(
                        root,
                        event,
                        prepared.evidence());
            }
        }

        private CoordinationPreparedDelivery prepared(
                Node event) {
            CoordinationSubscriptionSnapshot snapshot =
                    project(
                            ExternalOrderKey.of(
                                    Collections.emptyList()));
            return planner.prepare(
                    rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            event),
                    snapshot,
                    candidateKeys(
                            snapshot,
                            "matching"),
                    provider(event),
                    revision,
                    eventOrder(event));
        }

        @Override
        public void close() {
            blue.close();
        }
    }
}
