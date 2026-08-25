package blue.coordination.internal;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentId;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

final class ContractsRootFeederWindowTest {
    private static final String SPECIFICATION_ID =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONTRACTS_ID =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String REQUIRED_BLUE_ID =
            "4N8X8mM4K6cYz9V1j8Qv5A6C4a2Qj6C7v1G5d8E3r2P1";
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");

    @Test
    void needsResourcesBlocksOnlyItsRootLaneAndDoesNotRedriveTerminalLane() {
        // given

        Fixture fixture = fixture();

        try (fixture) {

            // when
            ContractsClosureAdapter.FrozenBatch eventOne =
                    fixture.adapter().capture(fixture.eventOne());

            // then
            assertEquals(2, eventOne.invocations().size());

            ContractsRootFeederWindow window =
                    new ContractsRootFeederWindow();
            List<ContractsRootFeederWindow.AttemptTicket> selected =
                    window.select(eventOne);
            assertEquals(List.of(List.of(A), List.of(B)), selected.stream()
                    .map(ContractsRootFeederWindow.AttemptTicket::members)
                    .toList());

            ContractsRootFeederWindow.AttemptTicket blocked = selected.get(0);
            window.record(blocked, new ContractsClosureAdapter.CohortOutcome(
                    blocked.members(),
                    ClosureAttemptResult.needsResources(
                            List.of(REQUIRED_BLUE_ID)),
                    false));

            ContractsRootFeederWindow.AttemptTicket completed =
                    selected.get(1);
            window.recordTerminal(
                    completed, completed.members(), true, true);
            assertFalse(window.isTerminal(eventOne));
            assertEquals(
                    List.of(REQUIRED_BLUE_ID),
                    window.requiredResourcesByLane().get(blocked.lane())
                            .stream()
                            .map(demand -> demand.suppliedValueBlueId())
                            .toList());

            ContractsClosureAdapter.FrozenBatch eventTwo =
                    fixture.adapter().capture(fixture.eventTwo());
            List<ContractsRootFeederWindow.AttemptTicket> eventTwoSelected =
                    window.select(eventTwo);
            assertEquals(List.of(List.of(B)), eventTwoSelected.stream()
                    .map(ContractsRootFeederWindow.AttemptTicket::members)
                    .toList());

            List<ContractsRootFeederWindow.AttemptTicket> eventOneRetry =
                    window.select(fixture.adapter().capture(
                            fixture.eventOne()));
            assertEquals(1, eventOneRetry.size());
            assertEquals(List.of(A), eventOneRetry.get(0).members());
            assertEquals(blocked.invocationIdentity(),
                    eventOneRetry.get(0).invocationIdentity());
        }
    }

    @Test
    void oneFrozenSelectionUsesPublicRootsAsIndependentLaneIdentities() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            ContractsClosureAdapter.FrozenBatch batch =
                    fixture.adapter().capture(fixture.eventOne());

            // when
            List<ContractsRootFeederWindow.AttemptTicket> selected =
                    new ContractsRootFeederWindow().select(batch);

            // then
            assertEquals(2, selected.size());
            assertTrue(selected.stream().allMatch(ticket ->
                    ticket.lane().publicLane()));
            assertEquals(List.of(List.of(A), List.of(B)), selected.stream()
                    .map(ticket -> ticket.lane().roots())
                    .toList());
            assertTrue(selected.stream().allMatch(ticket ->
                    ticket.members().equals(ticket.lane().roots())));
        }
    }

    @Test
    void restartRetainsTerminalProgressAndExactResourceBarrier() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            ContractsClosureAdapter.FrozenBatch batch =
                    fixture.adapter().capture(fixture.eventOne());
            ContractsRootFeederWindow beforeRestart =
                    new ContractsRootFeederWindow();
            List<ContractsRootFeederWindow.AttemptTicket> selected =
                    beforeRestart.select(batch);
            beforeRestart.recordNeedsResources(
                    selected.get(0),
                    selected.get(0).members(),
                    List.of(ExactNodeDemand.derived(
                            REQUIRED_BLUE_ID,
                            new blue.language.processor.closure.DocumentId(
                                    A.value()),
                            "/resource")));
            beforeRestart.recordTerminal(
                    selected.get(1),
                    selected.get(1).members(),
                    true,
                    true);

            ContractsRootFeederWindow restarted =
                    new ContractsRootFeederWindow(
                            beforeRestart.durableState().copy());

            // when
            List<ContractsRootFeederWindow.AttemptTicket> retry =
                    restarted.select(fixture.adapter().capture(
                            fixture.eventOne()));

            // then
            assertEquals(1, retry.size());
            assertEquals(List.of(A), retry.get(0).members());
            assertEquals(
                    List.of(REQUIRED_BLUE_ID),
                    restarted.requiredResourcesByLane().get(
                            retry.get(0).lane()).stream()
                            .map(demand -> demand.suppliedValueBlueId())
                            .toList());
            assertEquals(1, restarted.terminalProgress().size());
            assertEquals(List.of(B), restarted.terminalProgress().get(0)
                    .ticket().members());
        }
    }

    @Test
    void eachDisconnectedCohortExecutesAsAnIndependentRootInvocation() {
        // given

        Fixture fixture = fixture();

        try (fixture;
                BlueClosureContracts contracts = new BlueClosureContracts(
                        fixture.runtime().documentProcessor())) {
            ContractsClosureAdapter.FrozenBatch batch =
                    fixture.adapter().capture(fixture.eventOne());

            // when
            batch.invocations().forEach(invocation -> {

                // then
                ClosureAttemptResult attempt = assertDoesNotThrow(
                        () -> contracts.processClosure(invocation.input()),
                        () -> "failed independently for "
                                + invocation.members());
                assertNotNull(attempt);
                assertTrue(attempt.isComplete());
                assertTrue(attempt.processResult().commits());
            });
        }
    }

    @Test
    void disconnectedCohortsPublishIndependentlyFromOneFrozenRootEvent() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            ContractsClosureAdapter.FrozenBatch batch =
                    fixture.adapter().capture(fixture.eventOne());
            ContractsRootFeederWindow window =
                    new ContractsRootFeederWindow();

            // when
            ContractsRootFeederCoordinator.EventProgress progress =
                    new ContractsRootFeederCoordinator(
                            fixture.adapter(), window).process(batch);

            // then
            assertEquals(2, progress.cohorts().size());
            assertTrue(progress.cohorts().stream().allMatch(cohort ->
                    cohort.outcome().attempt().isComplete()
                            && cohort.outcome().published()));
            assertTrue(progress.terminal());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());
        }
    }

    @Test
    void freshCoordinatorRecoversCrashAfterPublicationBeforeWindowRecord() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            ContractsRootFeederWindow abandonedWindow =
                    new ContractsRootFeederWindow();
            boolean[] crash = {true};

            // when
            ContractsRootFeederCoordinator abandoned =
                    new ContractsRootFeederCoordinator(
                            fixture.adapter(),
                            abandonedWindow,
                            (batch, invocation) -> {
                                ContractsClosureAdapter.CohortOutcome outcome =
                                        fixture.adapter().executeAndPublish(
                                                batch, invocation);
                                if (crash[0]) {
                                    crash[0] = false;
                                    throw new IllegalStateException(
                                            "after-publication-before-record");
                                }
                                return outcome;
                            });

            // then
            assertThrows(IllegalStateException.class, () ->
                    abandoned.process(fixture.eventOne()));
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(0L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());
            ContractsClosurePublicationReceipt firstReceipt = fixture.store()
                    .publicationSnapshot().closurePublicationReceipts().values()
                    .stream()
                    .filter(receipt -> receipt.documentIds().equals(List.of(A)))
                    .findFirst()
                    .orElseThrow();

            ContractsRootFeederWindow restartedWindow =
                    new ContractsRootFeederWindow(
                            abandonedWindow.durableState().copy());
            ContractsRootFeederCoordinator restarted =
                    new ContractsRootFeederCoordinator(
                            fixture.adapter(), restartedWindow);
            ContractsRootFeederCoordinator.EventProgress recovered =
                    restarted.process(fixture.eventOne());

            assertTrue(recovered.terminal());
            assertEquals(2, recovered.cohorts().size());
            ContractsClosureAdapter.CohortOutcome replay = recovered.cohorts()
                    .stream()
                    .filter(progress -> progress.ticket().members()
                            .equals(List.of(A)))
                    .findFirst()
                    .orElseThrow()
                    .outcome();
            assertTrue(replay.replayed());
            assertSame(firstReceipt.attempt(), replay.attempt());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());
        }
    }

    @Test
    void feederContinuesUnrelatedRootLaneWhileFirstLaneNeedsResources() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            ContractsRootFeederWindow window =
                    new ContractsRootFeederWindow();
            boolean[] resourceAvailable = {false};
            ContractsRootFeederCoordinator coordinator =
                    new ContractsRootFeederCoordinator(
                            fixture.adapter(),
                            window,
                            (batch, invocation) -> {
                                if (invocation.members().equals(List.of(A))
                                        && !resourceAvailable[0]) {
                                    return new ContractsClosureAdapter
                                            .CohortOutcome(
                                                    invocation.members(),
                                                    ClosureAttemptResult
                                                            .needsResources(
                                                                    List.of(
                                                                            REQUIRED_BLUE_ID)),
                                                    false);
                                }
                                return fixture.adapter().executeAndPublish(
                                        batch, invocation);
                            });

            // when
            ContractsRootFeederCoordinator.EventProgress first =
                    coordinator.process(fixture.eventOne());

            // then
            assertFalse(first.terminal());
            assertEquals(2, first.cohorts().size());
            assertEquals(0L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());

            ContractsRootFeederCoordinator.EventProgress second =
                    coordinator.process(fixture.eventTwo());
            assertFalse(second.terminal());
            assertEquals(List.of(List.of(B)), second.cohorts().stream()
                    .map(progress -> progress.ticket().members())
                    .toList());
            assertEquals(0L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());

            resourceAvailable[0] = true;
            ContractsRootFeederCoordinator.EventProgress retry =
                    coordinator.process(fixture.eventOne());
            assertTrue(retry.terminal());
            assertEquals(List.of(List.of(A)), retry.cohorts().stream()
                    .map(progress -> progress.ticket().members())
                    .toList());
            assertEquals(1L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());

            ContractsRootFeederCoordinator.EventProgress resumedSecond =
                    coordinator.process(fixture.eventTwo());
            assertTrue(resumedSecond.terminal());
            assertEquals(List.of(List.of(A)), resumedSecond.cohorts().stream()
                    .map(progress -> progress.ticket().members())
                    .toList());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());
        }
    }

    @Test
    void journalRescansPastBlockedLaneWithoutAdvancingGlobalFrontier() {
        // given

        Fixture fixture = fixture();

        try (fixture) {
            boolean[] resourceAvailable = {false};
            ContractsRootFeederCoordinator.CohortExecutor executor =
                    (batch, invocation) -> {
                        if (invocation.members().equals(List.of(A))
                                && !resourceAvailable[0]) {
                            return new ContractsClosureAdapter.CohortOutcome(
                                    invocation.members(),
                                    ClosureAttemptResult.needsResources(
                                            List.of(REQUIRED_BLUE_ID)),
                                    false);
                        }
                        return fixture.adapter().executeAndPublish(
                                batch, invocation);
                    };
            ContractsRootFeederWindow window =
                    new ContractsRootFeederWindow();
            ContractsJournalDrainCoordinator drain =
                    new ContractsJournalDrainCoordinator(
                            fixture.journal(),
                            new ContractsRootFeederCoordinator(
                                    fixture.adapter(), window, executor),
                            new ContractsJournalDrainCoordinator
                                    .DurableState(),
                            () -> java.util.Set.of("shared/alice"));

            // when
            ContractsJournalDrainCoordinator.DrainProgress first =
                    drain.drain();

            // then
            assertNull(first.processedThrough());
            assertFalse(first.quiescent());
            assertEquals(List.of(
                            fixture.eventOne().blueId(),
                            fixture.eventTwo().blueId()),
                    first.attempts().stream()
                            .map(progress -> progress.batch()
                                    .entry().blueId())
                            .toList());
            assertEquals(List.of(List.of(A), List.of(B)),
                    first.attempts().get(0).cohorts().stream()
                            .map(progress -> progress.ticket().members())
                            .toList());
            assertEquals(List.of(List.of(B)),
                    first.attempts().get(1).cohorts().stream()
                            .map(progress -> progress.ticket().members())
                            .toList());
            assertEquals(0L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());

            resourceAvailable[0] = true;
            ContractsJournalDrainCoordinator restarted =
                    new ContractsJournalDrainCoordinator(
                            fixture.journal(),
                            new ContractsRootFeederCoordinator(
                                    fixture.adapter(),
                                    new ContractsRootFeederWindow(
                                            window.durableState()),
                                    executor),
                            drain.durableState(),
                            () -> java.util.Set.of("shared/alice"));
            ContractsJournalDrainCoordinator.DrainProgress resumed =
                    restarted.drain();

            assertEquals(fixture.eventTwo().sourceOrderKey(),
                    resumed.processedThrough());
            assertTrue(resumed.quiescent());
            assertEquals(List.of(
                            fixture.eventOne().blueId(),
                            fixture.eventTwo().blueId()),
                    resumed.attempts().stream()
                            .map(progress -> progress.batch()
                                    .entry().blueId())
                            .toList());
            assertTrue(resumed.attempts().stream()
                    .flatMap(progress -> progress.cohorts().stream())
                    .allMatch(progress ->
                            progress.ticket().members().equals(List.of(A))));
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(A).epoch());
            assertEquals(2L, fixture.store().publicationSnapshot()
                    .requireHead(B).epoch());
        }
    }

    private static Fixture fixture() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        BlueRuntime runtime = BlueRuntime.create(objects, metrics);
        EmbeddedOnlyLayoutBuilder layouts = new EmbeddedOnlyLayoutBuilder(
                runtime, objects, metrics);
        DocumentTransitionProcessor transitions =
                new DocumentTransitionProcessor(
                        runtime,
                        objects,
                        layouts,
                        metrics,
                        ignored -> { });
        InMemoryDocumentStore store = new InMemoryDocumentStore();
        admit(store, runtime, transitions, A);
        admit(store, runtime, transitions, B);

        OperationRouteIndex routes = new OperationRouteIndex(
                metrics, documentId -> store.find(documentId).orElse(null));
        store.sessions().forEach(session -> routes.replace(
                session.documentId(),
                session.layout().routingSurface(),
                session.activeSubscriptions()));

        WholeRequestEntryFactory entries = new WholeRequestEntryFactory(
                runtime, objects, metrics);
        InMemoryTimelineJournal journal = new InMemoryTimelineJournal(
                entries, metrics);
        Timeline timeline = new Timeline("shared/alice", "alice");
        TimelineEntry eventOne = journal.append(
                timeline,
                Operation.yaml("increment", "aliceChannel", "amount: 1"),
                1_800_000_000_000_001L);
        TimelineEntry eventTwo = journal.append(
                timeline,
                Operation.yaml("increment", "aliceChannel", "amount: 2"),
                1_800_000_000_000_002L);
        ContractsClosureAdapter adapter = new ContractsClosureAdapter(
                runtime,
                objects,
                layouts,
                store,
                routes,
                ContractsClosureProfile.release10(
                        SPECIFICATION_ID,
                        CONTRACTS_ID,
                        List.of(A, B)));
        return new Fixture(
                runtime, store, adapter, journal, eventOne, eventTwo);
    }

    private static void admit(
            InMemoryDocumentStore store,
            BlueRuntime runtime,
            DocumentTransitionProcessor transitions,
            DocumentId documentId) {
        DocumentSession session = transitions.admit(
                documentId,
                counterDocument(documentId),
                ExternalOrderKey.of(List.of(0L, "admission", documentId.value())),
                CoordinationEngine.AdmissionPolicy.FROM_NOW);
        store.insert(session);
        InMemoryDocumentStore.PublicationSnapshot snapshot =
                store.publicationSnapshot();
        Node document = session.currentRevision().after().copyNode();
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                new blue.language.processor.closure.DocumentId(
                        documentId.value()),
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
                        "seed-component|" + documentId.value(),
                        snapshot.occurrenceInventoryGeneration(),
                        snapshot.componentIndexGeneration())
                .expectHead(
                        documentId,
                        session.epoch(),
                        session.currentRevision().after().blueId())
                .stageComponentStates(List.of(component))
                .commit();
    }

    private static String counterDocument(DocumentId documentId) {
        return """
                documentId: %s
                name: Counter
                counter: 0
                contracts:
                  aliceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: shared/alice
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
                """.formatted(documentId.value());
    }

    private record Fixture(
            BlueRuntime runtime,
            InMemoryDocumentStore store,
            ContractsClosureAdapter adapter,
            InMemoryTimelineJournal journal,
            TimelineEntry eventOne,
            TimelineEntry eventTwo) implements AutoCloseable {
        @Override
        public void close() {
            adapter.close();
            runtime.close();
        }
    }
}
