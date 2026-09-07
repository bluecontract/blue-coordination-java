package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ScopeAddress;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractsClosureAdapterTest {
    private static final String SHA_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");
    private static final DocumentId C = DocumentId.of("c");
    private static final DocumentId COUNTER = DocumentId.of("counter");
    private static final String COUNTER_DOCUMENT = """
            documentId: counter
            name: Counter
            counter: 0
            contracts:
              aliceChannel:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: counter/alice
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
              increment:
                type: Coordination/Sequential Workflow Operation
                channel: aliceChannel
                request:
                  amount: {type: Integer}
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: replace
                          path: /counter
                          val:
                            $add:
                              - $document: /counter
                              - $binding: event/message/request/amount
                      - $return: true
              reject:
                type: Coordination/Sequential Workflow Operation
                channel: aliceChannel
                steps:
                  - type: Coordination/Compute
                    do:
                      - $appendChange:
                          op: remove
                          path: /does-not-exist
                      - $return: true
            """;

    @Test
    void capabilityFailureCannotAdvanceDurableFeederProgress() {
        // given
        ProcessorStatus retryable = ProcessorStatus.CAPABILITY_FAILURE;
        ProcessorStatus terminal = ProcessorStatus.NO_MATCH;

        // when
        boolean capabilityFailureTerminal =
                ContractsClosureAdapter.isDurablyTerminalStatus(retryable);
        boolean noMatchTerminal =
                ContractsClosureAdapter.isDurablyTerminalStatus(terminal);

        // then
        assertFalse(capabilityFailureTerminal);
        assertTrue(noMatchTerminal);
    }

    @Test
    void partitionsOneFrozenRouteSelectionByForwardAffectedClosure() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(occurrence("a-to-b", A, B, true)));
        ProcessEmbeddedComponentIndex components = ProcessEmbeddedComponentIndex
                .fromDocumentsAndOccurrenceInventory(
                        List.of(A, B, C), inventory);
        OperationRouteIndex routes = new OperationRouteIndex(
                new EngineMetrics());
        RoutingSurface surface = surface("timeline-a", "alice");
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        routes.replace(A, surface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 0)));
        routes.replace(C, surface, List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 1)));

        OperationRouteIndex.FrozenDirectDeliverySelection selection =
                routes.selectDirectDeliveries(entry(
                        "timeline-a", "alice", "ownerChannel"));

        // when
        List<ContractsClosureAdapter.CohortSelection> selected =
                ContractsClosureAdapter.partitionSelection(
                        components,
                        inventory,
                        selection);

        // then
        assertEquals(List.of(List.of(A, B), List.of(C)), selected.stream()
                .map(ContractsClosureAdapter.CohortSelection::members)
                .toList());
        assertEquals(List.of(0L), selected.get(0).deliveries().stream()
                .map(OperationRouteIndex.FrozenDirectDelivery
                        ::rawOccurrenceOrder)
                .toList());
        assertEquals(List.of(1L), selected.get(1).deliveries().stream()
                .map(OperationRouteIndex.FrozenDirectDelivery
                        ::rawOccurrenceOrder)
                .toList());
    }

    @Test
    void incomingOccurrenceSelectsItsSourceForExactPublicationWithoutTypedDemand() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(occurrence("a-to-b", A, B, true)));
        ProcessEmbeddedComponentIndex components = ProcessEmbeddedComponentIndex
                .fromDocumentsAndOccurrenceInventory(
                        List.of(A, B), inventory);
        OperationRouteIndex routes = new OperationRouteIndex(
                new EngineMetrics());
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        routes.replace(B, surface("timeline-a", "alice"), List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 0)));
        OperationRouteIndex.FrozenDirectDeliverySelection selection =
                routes.selectDirectDeliveries(entry(
                        "timeline-a", "alice", "ownerChannel"));
        EngineMetrics metrics = new EngineMetrics();

        // when
        List<ContractsClosureAdapter.CohortSelection> selected =
                ContractsClosureAdapter.partitionSelection(
                        components, inventory, selection, metrics);

        // then
        assertEquals(1, selected.size());
        assertEquals(List.of(A, B), selected.get(0).members());
        assertEquals(1, selected.get(0).occurrences().size());
        assertEquals(1L, metrics.counter(
                ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED));
    }

    @Test
    void livePublicationIncludesBothOccurrencesAndAllParentsWithoutListeners() {
        // given
        DocumentId otherParent = DocumentId.of("other-parent");
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(List.of(
                occurrence("first", A, "/first", B, true),
                occurrence("second", A, "/second", B, true),
                occurrence("other", otherParent, "/child", B, true),
                occurrence("inactive", C, "/old", B, false)));
        // when
        var selected = ContractsClosureAdapter.initialConnectedSelection(
                inventory, ClosureSubscriptionInventory.empty(), B);
        // then
        assertEquals(List.of(A, B, otherParent), selected.members());
        assertEquals(3, selected.occurrences().size());
        assertEquals(3L, selected.rowsExamined());
    }

    @Test
    void collectionDirectDemandDoesNotSuppressAncestorStatePublication() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        occurrence(
                                "a-to-b", A, "/orders/o1", B, true),
                        occurrence(
                                "b-to-c", B, "/payment", C, true)));
        ClosureSubscriptionInventory subscriptions = subscriptions(
                A,
                demand(
                        "orders",
                        "/orders",
                        ClosureSubscriptionInventory.EmbeddedDemandMode
                                .COLLECTION_DIRECT));

        // when
        ContractsClosureAdapter.ConnectedSelection direct =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, B);
        ContractsClosureAdapter.ConnectedSelection nested =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, C);

        // then
        assertEquals(List.of(A, B, C), direct.members());
        assertEquals(2, direct.occurrences().size());
        assertEquals(List.of(A, B, C), nested.members());
        assertEquals(2, nested.occurrences().size());
    }

    @Test
    void collectionDescendantDemandSelectsParentAcrossIntermediateMember() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        occurrence(
                                "a-to-b", A, "/orders/o1", B, true),
                        occurrence(
                                "b-to-c", B, "/payment", C, true)));
        ClosureSubscriptionInventory subscriptions = subscriptions(
                A,
                demand(
                        "orders",
                        "/orders",
                        ClosureSubscriptionInventory.EmbeddedDemandMode
                                .COLLECTION_DESCENDANTS));

        // when
        ContractsClosureAdapter.ConnectedSelection selected =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, C);

        // then
        assertEquals(List.of(A, B, C), selected.members());
        assertEquals(2, selected.occurrences().size());
    }

    @Test
    void exactAndAllDescendantDemandsTraverseContainingPath() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        occurrence(
                                "a-to-b", A, "/orders/o1", B, true),
                        occurrence(
                                "b-to-c", B, "/payment", C, true)));
        ClosureSubscriptionInventory exact = subscriptions(
                A,
                demand(
                        "exact",
                        "/orders/o1/payment",
                        ClosureSubscriptionInventory.EmbeddedDemandMode.EXACT));
        ClosureSubscriptionInventory all = subscriptions(
                A,
                demand(
                        "all",
                        "/",
                        ClosureSubscriptionInventory.EmbeddedDemandMode
                                .ALL_DESCENDANTS));

        // when
        ContractsClosureAdapter.ConnectedSelection exactSelection =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, exact, C);
        ContractsClosureAdapter.ConnectedSelection allSelection =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, all, C);

        // then
        assertEquals(List.of(A, B, C), exactSelection.members());
        assertEquals(List.of(A, B, C), allSelection.members());
    }

    @Test
    void collectionListenerPathDoesNotSuppressSiblingStatePublication() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(
                        occurrence(
                                "escaped", A,
                                "/groups/a~1b/member", B, true),
                        occurrence(
                                "unrelated", A,
                                "/groups/a~1b-old/member", C, true)));
        ClosureSubscriptionInventory subscriptions = subscriptions(
                A,
                demand(
                        "escaped-groups",
                        "/groups/a~1b",
                        ClosureSubscriptionInventory.EmbeddedDemandMode
                                .COLLECTION_DESCENDANTS));

        // when
        ContractsClosureAdapter.ConnectedSelection escaped =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, B);
        ContractsClosureAdapter.ConnectedSelection unrelated =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, C);

        // then
        assertEquals(List.of(A, B, C), escaped.members());
        assertEquals(List.of(A, B, C), unrelated.members());
    }

    @Test
    void reverseFrontierExtendsWhenAnObservingParentIntroducesAnotherBranch() {
        // given
        DocumentId observer = DocumentId.of("observer");
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(List.of(
                occurrence("ab", A, "/child", B, true),
                occurrence("ac", A, "/sibling", C, true),
                occurrence("oc", observer, "/peer", C, true)));
        ClosureSubscriptionInventory subscriptions = subscriptions(A,
                demand("all", "/", ClosureSubscriptionInventory.EmbeddedDemandMode.ALL_DESCENDANTS))
                .replaceEmbeddedDemands(observer, List.of(demand("peer", "/peer",
                        ClosureSubscriptionInventory.EmbeddedDemandMode.EXACT)));

        // when
        var selected = ContractsClosureAdapter.initialConnectedSelection(
                inventory, subscriptions, B);

        // then
        assertEquals(List.of(A, B, C, observer), selected.members());
        assertEquals(3, selected.occurrences().size());
        assertEquals(3L, selected.rowsExamined());
    }

    @Test
    void inactiveReservationIsRetainedForwardWithoutSelectingItsParentInReverse() {
        // given
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                List.of(occurrence(
                        "prospective", A, "/orders/o1", B, false)));
        ClosureSubscriptionInventory subscriptions = subscriptions(
                A,
                demand(
                        "orders",
                        "/orders",
                        ClosureSubscriptionInventory.EmbeddedDemandMode
                                .COLLECTION_DIRECT));

        // when
        ContractsClosureAdapter.ConnectedSelection fromChild =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, B);
        ContractsClosureAdapter.ConnectedSelection fromParent =
                ContractsClosureAdapter.initialConnectedSelection(
                        inventory, subscriptions, A);

        // then
        assertEquals(List.of(B), fromChild.members());
        assertTrue(fromChild.occurrences().isEmpty());
        assertEquals(List.of(A, B), fromParent.members());
        assertEquals(1, fromParent.occurrences().size());
        assertFalse(fromParent.occurrences().get(0).active());
    }

    @Test
    void oneThousandUnrelatedRowsDoNotChangeForwardSelectionWork() {
        // given
        ArrayList<ManagedOccurrenceBinding> rows = new ArrayList<>();
        rows.add(occurrence("a-to-b", A, B, true));
        ArrayList<DocumentId> documents = new ArrayList<>(List.of(A, B));
        for (int index = 0; index < 1_000; index++) {
            DocumentId source = DocumentId.of(
                    "zz-unrelated-source-%04d".formatted(index));
            DocumentId target = DocumentId.of(
                    "zz-unrelated-target-%04d".formatted(index));
            documents.add(source);
            documents.add(target);
            rows.add(occurrence(
                    "unrelated-%04d".formatted(index),
                    source,
                    target,
                    true));
        }
        ManagedOccurrenceInventory inventory = ManagedOccurrenceInventory.of(
                rows);
        ProcessEmbeddedComponentIndex components = ProcessEmbeddedComponentIndex
                .fromDocumentsAndOccurrenceInventory(documents, inventory);
        OperationRouteIndex routes = new OperationRouteIndex(
                new EngineMetrics());
        ExternalOrderKey frontier = ExternalOrderKey.of(List.of(0L));
        routes.replace(A, surface("timeline-a", "alice"), List.of(active(
                "ownerChannel", "timeline-a", "alice", frontier, 0)));
        OperationRouteIndex.FrozenDirectDeliverySelection selection =
                routes.selectDirectDeliveries(entry(
                        "timeline-a", "alice", "ownerChannel"));
        EngineMetrics metrics = new EngineMetrics();

        // when
        List<ContractsClosureAdapter.CohortSelection> selected =
                ContractsClosureAdapter.partitionSelection(
                        components, inventory, selection, metrics);

        // then
        assertEquals(1, selected.size());
        assertEquals(List.of(A, B), selected.get(0).members());
        assertEquals(1, selected.get(0).occurrences().size());
        assertEquals(1L, metrics.counter(
                ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED));
    }

    @Test
    void executesOneAcyclicRootAndPublishesItsExactResultAtomically() {
        // given

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
            ExternalOrderKey admissionFrontier = ExternalOrderKey.of(
                    List.of(0L, "admission"));
            DocumentSession admitted = transitionProcessor.admit(
                    COUNTER,
                    COUNTER_DOCUMENT,
                    admissionFrontier,
                    blue.coordination.api.CoordinationEngine
                            .AdmissionPolicy.FROM_NOW);
            InMemoryDocumentStore store = new InMemoryDocumentStore();
            store.insert(admitted);
            ManagedEpochReceiptTestFixtures.seedInitialization(
                    store, admitted, 10_000L);
            seedAcyclicComponent(store, runtime, admitted, true);

            OperationRouteIndex routes = new OperationRouteIndex(
                    metrics,
                    documentId -> store.find(documentId).orElse(null));
            routes.replace(
                    admitted.documentId(),
                    admitted.layout().routingSurface(),
                    admitted.activeSubscriptions());
            WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                    runtime, objects, metrics);
            TimelineEntry entry = entries.create(
                    new Timeline("counter/alice", "alice"),
                    null,
                    Operation.yaml(
                            "increment", "aliceChannel", "amount: 3"),
                    1_800_000_000_000_001L,
                    1L,
                    1L);

            // when
            OperationRouteIndex.FrozenDirectDeliverySelection selection =
                    routes.selectDirectDeliveries(entry);

            // then
            assertEquals(List.of(COUNTER), selection.documentIds());
            assertEquals(
                    blue.coordination.processor.TimelineProviderSupport
                            .operationRequestLogicalDeliveryKey(
                                    "increment", "aliceChannel"),
                    selection.deliveries().get(0).logicalDeliveryKey());

            InMemoryDocumentStore.PublicationSnapshot before =
                    store.publicationSnapshot();
            ContractsClosureProfile profile =
                    ContractsClosureProfile.release10(
                            SHA_A, SHA_B, List.of(COUNTER));
            List<ContractsClosureAdapter.CohortOutcome> outcomes;
            String publicationIdentity;
            String originalInvocationIdentity;
            ContractsClosurePublicationReceipt durable;
            InMemoryDocumentStore.PublicationSnapshot stranded;
            ClosureCommitCompanion originalCompanion;
            int originalRevisionCount;
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
                assertEquals(1, batch.invocations().size());
                assertEquals(List.of(COUNTER),
                        batch.invocations().get(0).members());
                originalInvocationIdentity = batch.invocations().get(0)
                        .input().invocationIdentity();
                publicationIdentity = adapter.publicationIdentityFor(
                        batch, batch.invocations().get(0));
                adapter.onPublicationFailurePoint(point -> {
                    if (point == ContractsClosureAdapter
                            .PublicationFailurePoint
                            .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH) {
                        throw new IllegalStateException("route-publish");
                    }
                });
                assertThrows(IllegalStateException.class, () ->
                        adapter.executeAndPublish(
                                batch, batch.invocations().get(0)));

                stranded =
                        store.publicationSnapshot();
                durable = stranded
                        .closurePublicationReceipts().get(publicationIdentity);
                assertTrue(durable.commits());
                originalCompanion = durable.attempt().processResult()
                        .platformCommitCompanion();
                assertNotNull(originalCompanion);
                assertEquals(durable.attempt().processResult()
                                .outputClosureIdentity(),
                        originalCompanion.outputClosureIdentity());
                originalRevisionCount = store.require(COUNTER)
                        .revisions().size();
                assertEquals(1L, stranded.requireHead(COUNTER).epoch());
            }

            OperationRouteIndex restartedRoutes = new OperationRouteIndex(
                    metrics,
                    documentId -> store.find(documentId).orElse(null));
            store.sessions().forEach(session -> restartedRoutes.replace(
                    session.documentId(),
                    session.layout().routingSurface(),
                    session.activeSubscriptions()));
            try (ContractsClosureAdapter restarted =
                    new ContractsClosureAdapter(
                            runtime,
                            objects,
                            layouts,
                            store,
                            restartedRoutes,
                            profile)) {
                ContractsClosureAdapter.FrozenBatch recoveredBatch =
                        restarted.capture(entry);
                assertEquals(1, recoveredBatch.invocations().size());
                assertNotEquals(originalInvocationIdentity,
                        recoveredBatch.invocations().get(0).input()
                                .invocationIdentity());
                assertEquals(publicationIdentity,
                        restarted.publicationIdentityFor(
                                recoveredBatch,
                                recoveredBatch.invocations().get(0)));
                ContractsClosureAdapter.CohortOutcome replay = restarted
                        .executeAndPublish(
                                recoveredBatch,
                                recoveredBatch.invocations().get(0));
                assertTrue(replay.replayed());
                assertSame(durable.attempt(), replay.attempt());
                ClosureCommitCompanion replayCompanion = replay.attempt()
                        .processResult().platformCommitCompanion();
                assertSame(originalCompanion, replayCompanion);
                assertEquals(originalCompanion.companionIdentity(),
                        replayCompanion.companionIdentity());
                assertEquals(originalRevisionCount,
                        store.require(COUNTER).revisions().size());
                assertEquals(1, store.publicationSnapshot()
                        .closurePublicationReceipts().size());
                assertEquals(stranded.outbox(),
                        store.publicationSnapshot().outbox());
                assertEquals(stranded.checkpointEvidence(),
                        store.publicationSnapshot().checkpointEvidence());

                TimelineEntry invalidEntry = entries.create(
                        new Timeline("counter/alice", "alice"),
                        null,
                        Operation.yaml(
                                "reject", "aliceChannel", "{}"),
                        1_800_000_000_000_002L,
                        2L,
                        2L);
                ContractsClosureAdapter.FrozenBatch invalidBatch =
                        restarted.capture(invalidEntry);
                InMemoryDocumentStore.PublicationSnapshot beforeRollback =
                        store.publicationSnapshot();
                ContractsClosureAdapter.CohortOutcome rejected = restarted
                        .executeAndPublish(
                                invalidBatch,
                                invalidBatch.invocations().get(0));
                assertTrue(rejected.attempt().isComplete());
                assertFalse(rejected.attempt().processResult().commits());
                assertFalse(rejected.published());
                assertFalse(rejected.replayed());
                assertTrue(store.publicationSnapshot()
                        .closurePublicationReceipts().containsKey(
                                rejected.publicationIdentity()));

                ContractsClosureAdapter.CohortOutcome rejectedReplay =
                        restarted.executeAndPublish(
                                invalidBatch,
                                invalidBatch.invocations().get(0));
                assertTrue(rejectedReplay.replayed());
                assertFalse(rejectedReplay.published());
                assertSame(rejected.attempt(), rejectedReplay.attempt());
                assertEquals(beforeRollback.documentHeads(),
                        store.publicationSnapshot().documentHeads());
                assertEquals(beforeRollback.outbox(),
                        store.publicationSnapshot().outbox());
                assertEquals(beforeRollback.checkpointEvidence(),
                        store.publicationSnapshot().checkpointEvidence());
                outcomes = List.of(replay);
            }

            assertEquals(1, outcomes.size());
            assertTrue(outcomes.get(0).attempt().isComplete());
            assertTrue(outcomes.get(0).attempt().processResult().commits());
            assertTrue(outcomes.get(0).published());
            assertTrue(outcomes.get(0).replayed());
            InMemoryDocumentStore.PublicationSnapshot after =
                    store.publicationSnapshot();
            assertEquals(1L, after.requireHead(COUNTER).epoch());
            assertNotEquals(
                    before.requireHead(COUNTER).blueId(),
                    after.requireHead(COUNTER).blueId());
            assertEquals(
                    BigInteger.valueOf(3L),
                    store.require(COUNTER).currentRevision().after()
                            .copyNode().getProperties().get("counter")
                            .getValue());
            assertEquals(
                    before.occurrenceInventoryGeneration(),
                    after.occurrenceInventoryGeneration());
            assertEquals(
                    before.componentIndexGeneration(),
                    after.componentIndexGeneration());
            assertEquals(1, after.componentStates().size());
            assertFalse(after.publicationReceipts().isEmpty());
        }
    }

    private static void seedAcyclicComponent(
            InMemoryDocumentStore store,
            BlueRuntime runtime,
            DocumentSession session,
            boolean publicRoot) {
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
                publicRoot,
                session.epoch(),
                0L);
        ComponentSnapshot component =
                ClosureEvidenceFactory.acyclicComponent(managed);
        store.beginAtomicPublication(
                        "seed-component|" + session.documentId().value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        session.documentId(),
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .stageComponentStates(List.of(component))
                .commit();
    }

    private static RoutingSurface surface(String timeline, String actor) {
        return new RoutingSurface(List.of(new RoutingSurface.Definition(
                "/", "increment", "ownerChannel", timeline, actor)), false);
    }

    private static SubscriptionDelta.Entry active(
            String channel,
            String timeline,
            String actor,
            ExternalOrderKey startAfter,
            int order) {
        return new SubscriptionDelta.Entry(
                "/",
                channel,
                "timeline-channel-type",
                List.of("source-" + channel),
                order,
                List.of(blue.coordination.processor.TimelineProviderSupport
                        .exactScalarEventKeys(timeline, actor).get(0)),
                "checkpoint-domain",
                0L,
                startAfter,
                null);
    }

    private static TimelineEntry entry(
            String timeline,
            String actor,
            String channel) {
        ExactValue event = ExactValue.verified(new Node().value(
                timeline + "|" + actor + "|event"));
        ExactValue request = ExactValue.verified(new Node().value("request"));
        ExternalOrderKey order = ExternalOrderKey.of(List.of(
                1L, timeline, event.blueId()));
        return new TimelineEntry(
                event,
                java.util.Optional.of(request),
                order,
                order,
                new Timeline(timeline, actor),
                "increment",
                channel,
                1L,
                1L,
                1L);
    }

    private static ManagedOccurrenceBinding occurrence(
            String identity,
            DocumentId source,
            DocumentId target,
            boolean active) {
        return occurrence(
                identity, source, "/" + identity, target, active);
    }

    private static ManagedOccurrenceBinding occurrence(
            String identity,
            DocumentId source,
            String sourcePath,
            DocumentId target,
            boolean active) {
        ExactValue targetValue = ExactValue.verified(
                new Node().value("state-" + identity));
        return ManagedOccurrenceBinding.derived(
                SHA_A,
                new blue.language.processor.closure.DocumentId(
                        source.value()),
                ScopeAddress.embedded(sourcePath, 1L),
                new blue.language.processor.closure.DocumentId(
                        target.value()),
                targetValue.blueId(),
                active,
                null);
    }

    private static ClosureSubscriptionInventory subscriptions(
            DocumentId documentId,
            ClosureSubscriptionInventory.EmbeddedDemand... demands) {
        return ClosureSubscriptionInventory.empty().replaceEmbeddedDemands(
                documentId, List.of(demands));
    }

    private static ClosureSubscriptionInventory.EmbeddedDemand demand(
            String channelKey,
            String selectorPath,
            ClosureSubscriptionInventory.EmbeddedDemandMode mode) {
        return new ClosureSubscriptionInventory.EmbeddedDemand(
                channelKey,
                selectorPath,
                mode,
                "embedded-contribution-" + channelKey);
    }
}
