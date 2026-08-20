package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact Contracts subscription-state and copy-on-write publication proofs. */
final class ClosureSubscriptionInventoryTest {
    private static final String LANGUAGE_SPECIFICATION_IDENTITY =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONTRACTS_SPECIFICATION_IDENTITY =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final DocumentId A = DocumentId.of("subscription-a");
    private static final DocumentId B = DocumentId.of("subscription-b");
    private static final String OWNER_TIMELINE =
            "closure-subscription-inventory/owner";
    private static final String OWNER_ACTOR = "owner";
    private static final String ADDED_TIMELINE =
            "closure-subscription-inventory/added";
    private static final String ADDED_ACTOR = "added";

    private static Fixture fixture;

    @BeforeAll
    static void executeGenuineContractsBatch() {
        fixture = executeFixture();
    }

    @Test
    void appliesVerifiedAddReplaceAndRemoveWithoutErasingDisconnectedRows() {
        // given

        ClosureProcessResult resultA = fixture.result(A);

        // when
        ClosureProcessResult resultB = fixture.result(B);

        // then
        assertEquals(EnumSet.allOf(SubscriptionDelta.Operation.class),
                operations(resultA));
        assertEquals(EnumSet.allOf(SubscriptionDelta.Operation.class),
                operations(resultB));
        assertEquals(3, resultA.subscriptionDeltas().size());
        assertEquals(3, resultB.subscriptionDeltas().size());

        assertFinalRows(resultA, fixture.after().closureSubscriptions());
        assertFinalRows(resultB, fixture.after().closureSubscriptions());
        assertEquals(4,
                fixture.after().closureSubscriptions().states().size());
        assertEquals(Set.of(A.value(), B.value()),
                fixture.after().closureSubscriptions().states().stream()
                        .map(state -> state.channelOccurrence()
                                .managedDocumentId().value())
                        .collect(java.util.stream.Collectors.toSet()));

        assertTrue(fixture.after().publicationReceipts().contains(
                fixture.publicationIdentities().get(A)));
        assertTrue(fixture.after().publicationReceipts().contains(
                fixture.publicationIdentities().get(B)));
        assertEquals(0L, fixture.before().requireHead(A).epoch());
        assertEquals(0L, fixture.before().requireHead(B).epoch());
        assertEquals(1L, fixture.after().requireHead(A).epoch());
        assertEquals(1L, fixture.after().requireHead(B).epoch());
    }

    @Test
    void rejectsMismatchedBeforeStateAndStaleDurableHead() {
        // given

        ClosureProcessResult result = fixture.result(A);
        SubscriptionState before = delta(
                result, SubscriptionDelta.Operation.REPLACE)
                .beforeSubscription();

        // when
        SubscriptionState conflicting = SubscriptionState.identified(
                before.channelOccurrence(),
                before.documentBlueId(),
                before.graphGeneration(),
                before.componentGeneration() + 1L);

        // then
        IllegalStateException stateFailure = assertThrows(
                IllegalStateException.class,
                () -> ClosureSubscriptionInventory.of(List.of(conflicting))
                        .apply(result));
        assertTrue(stateFailure.getMessage().contains(
                "before-state CAS mismatch"));

        InMemoryDocumentStore.PublicationSnapshot beforeAttempt =
                fixture.store().publicationSnapshot();
        ResultingDocument document = resultingDocument(result, A);
        MultiDocumentPublicationTransaction.AtomicPublicationCasException
                headFailure = assertThrows(
                        MultiDocumentPublicationTransaction
                                .AtomicPublicationCasException.class,
                        () -> fixture.store().beginAtomicPublication(
                                        "stale-subscription-head",
                                        beforeAttempt
                                                .occurrenceInventoryGeneration(),
                                        beforeAttempt
                                                .componentIndexGeneration())
                                .expectHead(A, 0L, document.beforeBlueId())
                                .stageClosureSubscriptionDeltas(result)
                                .commit());
        assertTrue(headFailure.getMessage().contains("Stale document head"));
        assertEquals(beforeAttempt,
                fixture.store().publicationSnapshot(),
                "a rejected COW attempt must not publish any surface");
    }

    @Test
    void validatesFinalDocumentGraphAndComponentGenerations() {
        // given

        ClosureProcessResult result = fixture.result(A);

        // when
        ResultingDocument document = resultingDocument(result, A);

        // then
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.beforeBlueId(),
                        result.graphGeneration(),
                        document.componentGeneration()));
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.afterBlueId(),
                        result.graphGeneration() + 1L,
                        document.componentGeneration()));
        assertFinalStateRejected(
                result,
                state -> SubscriptionState.identified(
                        state.channelOccurrence(),
                        document.afterBlueId(),
                        result.graphGeneration(),
                        document.componentGeneration() + 1L));
    }

    @Test
    void publishesRoutesWithExactCheckpointAndStartAfterIntervals() {
        // given
        OperationRouteIndex routes = fixture.routes();

        // when
        var atBoundary = routes.selectDirectDeliveries(
                fixture.addedAtBoundary()).documentIds();
        var afterBoundary = routes.selectDirectDeliveries(
                fixture.addedAfterBoundary()).documentIds();
        var retiring = routes.selectDirectDeliveries(
                fixture.retiringAfterBoundary()).documentIds();

        // then
        assertEquals(List.of(), atBoundary,
                "a Channel added by an event cannot receive that event");
        assertEquals(List.of(A, B), afterBoundary);
        assertEquals(List.of(), retiring);
        assertTrue(routes.generation()
                > fixture.routeGenerationBefore());

        for (DocumentId documentId : List.of(A, B)) {
            Map<String, blue.language.processor.SubscriptionDelta.Entry>
                    before = legacySubscriptions(
                            fixture.subscriptionsBefore().get(documentId));
            Map<String, blue.language.processor.SubscriptionDelta.Entry>
                    after = legacySubscriptions(fixture.store()
                            .require(documentId).activeSubscriptions());
            assertEquals(Set.of("addedChannel", "ownerChannel"),
                    after.keySet());

            blue.language.processor.SubscriptionDelta.Entry ownerBefore =
                    before.get("ownerChannel");
            blue.language.processor.SubscriptionDelta.Entry ownerAfter =
                    after.get("ownerChannel");
            assertEquals(ownerBefore.activationRootRevision(),
                    ownerAfter.activationRootRevision());
            assertEquals(ownerBefore.startAfterExternalOrderKey(),
                    ownerAfter.startAfterExternalOrderKey());

            blue.language.processor.SubscriptionDelta.Entry added =
                    after.get("addedChannel");
            assertEquals(fixture.trigger().sourceOrderKey(),
                    added.startAfterExternalOrderKey());
            assertEquals(Long.valueOf(resultingDocument(
                            fixture.result(documentId), documentId).epoch()),
                    added.activationRootRevision());
            assertTrue(fixture.objects().contains(
                    ownerAfter.checkpointDomainBlueId()));
            assertTrue(fixture.objects().contains(
                    added.checkpointDomainBlueId()));
        }
    }

    private static void assertFinalRows(
            ClosureProcessResult result,
            ClosureSubscriptionInventory inventory) {
        DocumentId documentId = DocumentId.of(
                result.resultingDocuments().get(0).documentId().value());
        Map<String, SubscriptionState> actual = new LinkedHashMap<>();
        inventory.statesFor(documentId).forEach(state -> actual.put(
                state.channelOccurrence().rawChannelKey(), state));
        assertEquals(Set.of("addedChannel", "ownerChannel"),
                actual.keySet());
        assertFalse(actual.containsKey("retiringChannel"));
        for (SubscriptionDelta delta : result.subscriptionDeltas()) {
            if (delta.afterSubscription() == null) {
                assertFalse(actual.containsKey(delta.beforeSubscription()
                        .channelOccurrence().rawChannelKey()));
            } else {
                String channelKey = delta.afterSubscription()
                        .channelOccurrence().rawChannelKey();
                assertEquals(delta.afterSubscription().subscriptionIdentity(),
                        actual.get(channelKey).subscriptionIdentity());
            }
        }
    }

    private static void assertFinalStateRejected(
            ClosureProcessResult result,
            Function<SubscriptionState, SubscriptionState> staleState) {
        SubscriptionState exemplar = delta(
                result, SubscriptionDelta.Operation.ADD).afterSubscription();
        ChannelOccurrence occurrence = ChannelOccurrence.root(
                exemplar.channelOccurrence().managedDocumentId(),
                "unmentionedChannel",
                exemplar.channelOccurrence()
                        .effectiveRuntimeContributionBlueId(),
                exemplar.channelOccurrence().subscriptionHeaderBlueId());
        SubscriptionState validShape = SubscriptionState.identified(
                occurrence,
                exemplar.documentBlueId(),
                exemplar.graphGeneration(),
                exemplar.componentGeneration());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ClosureSubscriptionInventory.of(
                                List.of(staleState.apply(validShape)))
                        .apply(result));
        assertTrue(failure.getMessage().contains(
                "stale after publication"));
    }

    private static EnumSet<SubscriptionDelta.Operation> operations(
            ClosureProcessResult result) {
        EnumSet<SubscriptionDelta.Operation> operations =
                EnumSet.noneOf(SubscriptionDelta.Operation.class);
        result.subscriptionDeltas().forEach(
                delta -> operations.add(delta.operation()));
        return operations;
    }

    private static Map<String, blue.language.processor.SubscriptionDelta.Entry>
            legacySubscriptions(
                    List<blue.language.processor.SubscriptionDelta.Entry>
                            subscriptions) {
        Map<String, blue.language.processor.SubscriptionDelta.Entry> result =
                new LinkedHashMap<>();
        subscriptions.forEach(entry -> result.put(entry.channelKey(), entry));
        return result;
    }

    private static SubscriptionDelta delta(
            ClosureProcessResult result,
            SubscriptionDelta.Operation operation) {
        return result.subscriptionDeltas().stream()
                .filter(candidate -> candidate.operation() == operation)
                .findFirst()
                .orElseThrow();
    }

    private static ResultingDocument resultingDocument(
            ClosureProcessResult result,
            DocumentId documentId) {
        return result.resultingDocuments().stream()
                .filter(document -> document.documentId().value()
                        .equals(documentId.value()))
                .findFirst()
                .orElseThrow();
    }

    private static Fixture executeFixture() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {
            EmbeddedOnlyLayoutBuilder layouts =
                    new EmbeddedOnlyLayoutBuilder(runtime, objects, metrics);
            DocumentTransitionProcessor transitionProcessor =
                    new DocumentTransitionProcessor(
                            runtime,
                            objects,
                            layouts,
                            metrics,
                            ignored -> { });
            InMemoryDocumentStore store = new InMemoryDocumentStore();
            DocumentSession sessionA = admit(
                    transitionProcessor, A, 1_900_000_000_000_001L);
            DocumentSession sessionB = admit(
                    transitionProcessor, B, 1_900_000_000_000_002L);
            store.insert(sessionA);
            store.insert(sessionB);
            seedAcyclicComponent(store, runtime, sessionA);
            seedAcyclicComponent(store, runtime, sessionB);

            OperationRouteIndex routes = new OperationRouteIndex(
                    metrics,
                    documentId -> store.find(documentId).orElse(null));
            routes.replace(
                    A, sessionA.layout().routingSurface(),
                    sessionA.activeSubscriptions());
            routes.replace(
                    B, sessionB.layout().routingSurface(),
                    sessionB.activeSubscriptions());
            Map<DocumentId,
                    List<blue.language.processor.SubscriptionDelta.Entry>>
                    subscriptionsBefore = Map.of(
                            A, List.copyOf(sessionA.activeSubscriptions()),
                            B, List.copyOf(sessionB.activeSubscriptions()));
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);
            TimelineEntry entry = entries.create(
                            new Timeline(OWNER_TIMELINE, OWNER_ACTOR),
                            null,
                            Operation.yaml(
                                    "reconfigure",
                                    "ownerChannel",
                                    addedChannelRequest()),
                            1_900_000_000_000_003L,
                            1L,
                            1L);
            long routeGenerationBefore = routes.generation();

            InMemoryDocumentStore.PublicationSnapshot before =
                    store.publicationSnapshot();
            ContractsClosureProfile profile =
                    ContractsClosureProfile.release10(
                            LANGUAGE_SPECIFICATION_IDENTITY,
                            CONTRACTS_SPECIFICATION_IDENTITY,
                            List.of(A, B));
            List<ContractsClosureAdapter.CohortOutcome> outcomes;
            try (ContractsClosureAdapter adapter =
                    new ContractsClosureAdapter(
                            runtime,
                            objects,
                            layouts,
                            store,
                            routes,
                            profile)) {
                ContractsClosureAdapter.FrozenBatch batch =
                        adapter.capture(entry);
                assertEquals(2, batch.invocations().size());
                outcomes = adapter.processAndPublish(batch);
            }

            Map<DocumentId, ClosureProcessResult> results =
                    new LinkedHashMap<>();
            Map<DocumentId, String> publicationIdentities =
                    new LinkedHashMap<>();
            for (ContractsClosureAdapter.CohortOutcome outcome : outcomes) {
                assertTrue(outcome.published(), () -> outcome.attempt()
                        .isComplete()
                        ? outcome.attempt().processResult().status() + ": "
                        + outcome.attempt().processResult().diagnostic()
                        .message() + " " + outcome.attempt().processResult()
                        .diagnostic().details()
                        : "needs " + outcome.attempt()
                        .requiredExactBlueIds());
                assertTrue(outcome.attempt().isComplete());
                ClosureProcessResult result = outcome.attempt().processResult();
                assertEquals(ProcessorStatus.SUCCESS, result.status());
                assertTrue(result.commits());
                assertEquals(1, outcome.members().size());
                results.put(outcome.members().get(0), result);
                publicationIdentities.put(
                        outcome.members().get(0),
                        outcome.publicationIdentity());
            }
            assertEquals(Set.of(A, B), results.keySet());
            TimelineEntry addedAfterBoundary = entries.create(
                    new Timeline(ADDED_TIMELINE, ADDED_ACTOR),
                    null,
                    Operation.yaml(
                            "addedOperation", "addedChannel", "{}"),
                    1_900_000_000_000_004L,
                    2L,
                    1L);
            TimelineEntry retiringAfterBoundary = entries.create(
                    new Timeline(
                            "closure-subscription-inventory/retiring/"
                                    + A.value(),
                            "retiring"),
                    null,
                    Operation.yaml(
                            "retiringOperation", "retiringChannel", "{}"),
                    1_900_000_000_000_004L,
                    3L,
                    1L);
            return new Fixture(
                    store,
                    before,
                    store.publicationSnapshot(),
                    Map.copyOf(results),
                    Map.copyOf(publicationIdentities),
                    routes,
                    routeGenerationBefore,
                    subscriptionsBefore,
                    objects,
                    entry,
                    withSourceOrder(
                            addedAfterBoundary, entry.sourceOrderKey()),
                    addedAfterBoundary,
                    retiringAfterBoundary);
        }
    }

    private static DocumentSession admit(
            DocumentTransitionProcessor processor,
            DocumentId documentId,
            long sourceOrder) {
        return processor.admit(
                documentId,
                document(documentId),
                ExternalOrderKey.of(List.of(sourceOrder, "admission")),
                blue.coordination.api.CoordinationEngine.AdmissionPolicy
                        .FROM_NOW);
    }

    private static void seedAcyclicComponent(
            InMemoryDocumentStore store,
            BlueRuntime runtime,
            DocumentSession session) {
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        Node document = session.currentRevision().after().copyNode();
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                new blue.language.processor.closure.DocumentId(
                        session.documentId().value()),
                session.currentRevision().after().blueId(),
                document,
                runtime.documentProcessor().isInitialized(document),
                false,
                true,
                session.epoch(),
                0L);
        ComponentSnapshot component =
                ClosureEvidenceFactory.acyclicComponent(managed);
        store.beginAtomicPublication(
                        "seed-subscription-component|"
                                + session.documentId().value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        session.documentId(),
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .stageComponentStates(List.of(component))
                .commit();
    }

    private static String document(DocumentId documentId) {
        return """
                documentId: %s
                state: initial
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  retiringChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: closure-subscription-inventory/retiring/%s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: retiring
                  retiringOperation:
                    type: Coordination/Sequential Workflow Operation
                    channel: retiringChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $return: true
                  reconfigure:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      addedChannel: {}
                      addedHandler: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /contracts/addedChannel
                              val: {$binding: event/message/request/addedChannel}
                          - $appendChange:
                              op: add
                              path: /contracts/addedOperation
                              val: {$binding: event/message/request/addedHandler}
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringOperation
                          - $appendChange:
                              op: remove
                              path: /contracts/retiringChannel
                          - $appendChange:
                              op: replace
                              path: /state
                              val: updated
                          - $return: true
                """.formatted(
                documentId.value(),
                OWNER_TIMELINE,
                OWNER_ACTOR,
                documentId.value());
    }

    private static String addedChannelRequest() {
        return """
                addedChannel:
                  type: Coordination/Timeline Channel
                  timeline:
                    type: MyOS/MyOS Timeline
                    timelineId: %s
                  actor:
                    type: MyOS/Principal Actor
                    accountId: %s
                addedHandler:
                  type: Coordination/Sequential Workflow Operation
                  channel: addedChannel
                  request: {}
                  steps:
                    - type: Coordination/Compute
                      do:
                        - $return: true
                """.formatted(ADDED_TIMELINE, ADDED_ACTOR);
    }

    private static TimelineEntry withSourceOrder(
            TimelineEntry entry,
            ExternalOrderKey sourceOrder) {
        return new TimelineEntry(
                entry.exactEvent(),
                entry.exactRequest(),
                sourceOrder,
                sourceOrder,
                entry.timeline(),
                entry.operation(),
                entry.channel(),
                entry.timestampMicros(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    private record Fixture(
            InMemoryDocumentStore store,
            InMemoryDocumentStore.PublicationSnapshot before,
            InMemoryDocumentStore.PublicationSnapshot after,
            Map<DocumentId, ClosureProcessResult> results,
            Map<DocumentId, String> publicationIdentities,
            OperationRouteIndex routes,
            long routeGenerationBefore,
            Map<DocumentId,
                    List<blue.language.processor.SubscriptionDelta.Entry>>
                    subscriptionsBefore,
            WholeObjectStore objects,
            TimelineEntry trigger,
            TimelineEntry addedAtBoundary,
            TimelineEntry addedAfterBoundary,
            TimelineEntry retiringAfterBoundary) {
        ClosureProcessResult result(DocumentId documentId) {
            return results.get(documentId);
        }
    }
}
