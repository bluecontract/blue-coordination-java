package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.TimelineHandle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Response-loss acceptance for one retained managed-epoch application. */
final class ManagedEpochApplicationResponseLossTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";

    @Test
    void sameProcessRetryReconcilesCommittedApplicationWithoutProcess() {
        try (Scenario scenario = prepared("retry")) {
            // given
            StrandedCommit committed = strandAfterStoreCommit(scenario);
            ContractsClosureAdapter adapter = scenario.engine()
                    .contractsClosureAdapter();

            // when
            Optional<ContractsClosureAdapter.ManagedApplicationOutcome>
                    replay = adapter.processNextManagedEpochApplication();

            // then
            assertTrue(replay.isPresent());
            assertTrue(replay.orElseThrow().published());
            assertTrue(replay.orElseThrow().replayed());
            assertEquals(
                    committed.receipt().applicationReceiptIdentity(),
                    replay.orElseThrow().receipt().orElseThrow()
                            .applicationReceiptIdentity());
            assertDurableStateUnchanged(scenario, committed);
            assertTrue(scenario.engine().documents()
                    .nextCatchUpWork().isEmpty());
        }
    }

    @Test
    void restartRebuildsRoutesAndDropsDisposableRepairWithoutProcess() {
        try (Scenario scenario = prepared("restart")) {
            // given
            StrandedCommit committed = strandAfterStoreCommit(scenario);

            // when
            scenario.engine().restartFromStores();

            // then
            assertTrue(scenario.engine().contractsClosureAdapter()
                    .processNextManagedEpochApplication().isEmpty());
            assertDurableStateUnchanged(scenario, committed);
            assertTrue(scenario.engine().documents()
                    .nextCatchUpWork().isEmpty());
        }
    }

    private static Scenario prepared(String label) {
        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            DocumentId consumerId = DocumentId.of(
                    "managed-epoch-response-loss-consumer-" + label);
            DocumentId sourceId = DocumentId.of(
                    "managed-epoch-response-loss-source-" + label);
            String consumerTimelineId =
                    "managed-epoch/response-loss/consumer/" + label;
            String sourceTimelineId =
                    "managed-epoch/response-loss/source/" + label;
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(consumerTimelineId, ACTOR);
            TimelineHandle sourceTimeline = coordination.timelines()
                    .register(sourceTimelineId, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    consumerId,
                                    consumerYaml(
                                            consumerId,
                                            consumerTimelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    sourceId,
                                    sourceYaml(sourceId, sourceTimelineId))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = source.history().get(0).after();
            coordination.operations()
                    .on(source)
                    .from(sourceTimeline)
                    .call("increment")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            List<String> sourceHistory = source.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();
            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .requestYaml("child:\n  blueId: " + epochZero.blueId())
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(
                    EntryDisposition.APPLIED,
                    admitted.entry(attachment).disposition(),
                    admitted.entry(attachment).diagnostic().toString());
            assertTrue(admitted.managedEpochApplications().isEmpty());

            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) coordination.advanced()
                            .rawEngine();
            ManagedEpochApplicationWork work = engine.documents()
                    .nextCatchUpWork().orElseThrow();
            assertEquals(sourceId, work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
            assertEquals(consumerId, work.consumerDocumentId());
            return new Scenario(
                    coordination,
                    engine,
                    consumer,
                    source,
                    consumerId,
                    work,
                    sourceHistory);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static StrandedCommit strandAfterStoreCommit(
            Scenario scenario) {
        ContractsClosureAdapter adapter = scenario.engine()
                .contractsClosureAdapter();
        long processCallsBefore = counter(
                scenario.engine(),
                ManagedEpochApplicationExecutor.PROCESS_CALLS);
        long externalProcessCallsBefore = counter(
                scenario.engine(), "EXTERNAL_PROCESS_CALLS");
        adapter.onPublicationFailurePoint(point -> {
            if (point == ContractsClosureAdapter.PublicationFailurePoint
                    .AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH) {
                throw new IllegalStateException(
                        "injected managed route-publication response loss");
            }
        });
        try {
            assertThrows(
                    IllegalStateException.class,
                    adapter::processNextManagedEpochApplication);
        } finally {
            adapter.onPublicationFailurePoint(ignored -> { });
        }

        assertEquals(processCallsBefore + 1L, counter(
                scenario.engine(),
                ManagedEpochApplicationExecutor.PROCESS_CALLS));
        assertEquals(externalProcessCallsBefore, counter(
                scenario.engine(), "EXTERNAL_PROCESS_CALLS"));
        assertEquals(0L, counter(
                scenario.engine(),
                ManagedEpochApplicationExecutor.SOURCE_PROCESS_CALLS));
        ManagedEpochApplicationReceipt receipt = scenario.engine()
                .documents()
                .catchUpApplicationByWork(
                        scenario.work().workIdentity())
                .orElseThrow();
        ManagedOccurrenceCatchUpPlan plan = scenario.engine().documents()
                .catchUpPlan(scenario.work().planIdentity())
                .orElseThrow();
        assertEquals(2L, plan.nextSourceEpoch());
        assertTrue(plan.status().terminal());
        assertEquals(2L, receipt.resultingSourceCursor());
        assertEquals(1L, scenario.consumer().snapshot().longAt(
                "/observedChanges"));
        assertEquals(scenario.sourceHistory(), sourceHistory(scenario));
        assertEquals(1L, consumerEventCount(scenario));
        assertTrue(scenario.engine().documents()
                .closurePublicationReceipt(
                        scenario.work().workIdentity())
                .orElseThrow().commits());
        return new StrandedCommit(
                receipt,
                plan.nextSourceEpoch(),
                processCallsBefore + 1L,
                externalProcessCallsBefore,
                scenario.engine().history(
                        scenario.consumerId()).size(),
                scenario.consumer().snapshot().blueId(),
                consumerEventCount(scenario));
    }

    private static void assertDurableStateUnchanged(
            Scenario scenario,
            StrandedCommit committed) {
        ManagedEpochApplicationReceipt retained = scenario.engine()
                .documents()
                .catchUpApplicationByWork(
                        scenario.work().workIdentity())
                .orElseThrow();
        ManagedOccurrenceCatchUpPlan plan = scenario.engine().documents()
                .catchUpPlan(scenario.work().planIdentity())
                .orElseThrow();
        assertEquals(
                committed.receipt().applicationReceiptIdentity(),
                retained.applicationReceiptIdentity());
        assertEquals(committed.nextSourceEpoch(), plan.nextSourceEpoch());
        assertTrue(plan.status().terminal());
        assertEquals(committed.processCalls(), counter(
                scenario.engine(),
                ManagedEpochApplicationExecutor.PROCESS_CALLS));
        assertEquals(committed.externalProcessCalls(), counter(
                scenario.engine(), "EXTERNAL_PROCESS_CALLS"));
        assertEquals(0L, counter(
                scenario.engine(),
                ManagedEpochApplicationExecutor.SOURCE_PROCESS_CALLS));
        assertEquals(
                committed.consumerHistorySize(),
                scenario.engine().history(
                        scenario.consumerId()).size());
        assertEquals(
                committed.consumerBlueId(),
                scenario.consumer().snapshot().blueId());
        assertEquals(
                committed.consumerEventCount(),
                consumerEventCount(scenario));
        assertEquals(1L, committed.consumerEventCount());
        assertEquals(scenario.sourceHistory(), sourceHistory(scenario));
        assertFalse(scenario.engine().documents().hasActiveCatchUp());
    }

    private static long counter(
            DefaultCoordinationEngine engine,
            String name) {
        return engine.metricsSnapshot().counters().getOrDefault(name, 0L);
    }

    private static long consumerEventCount(Scenario scenario) {
        return scenario.engine().history(scenario.consumerId()).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind
                                .EMBEDDED_REVISION_APPLICATION)
                .mapToLong(revision -> revision.emittedEvents().size())
                .sum();
    }

    private static List<String> sourceHistory(Scenario scenario) {
        return scenario.source().history().stream()
                .map(revision -> revision.after().blueId())
                .toList();
    }

    private static String consumerYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                children: {}
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/b
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                  onSourceChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/A Observed
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attach:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/b
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String sourceYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                initializationCount: 0
                counter: 0
                contracts:
                  lifecycleChannel:
                    type:
                      blueId: %s
                    order: 0
                  onProcessingInitiated:
                    type: Coordination/Sequential Workflow
                    channel: lifecycleChannel
                    event:
                      type:
                        blueId: %s
                    order: 0
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /initializationCount
                              val: {$add: [{$document: /initializationCount}, 1]}
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: {$add: [{$document: /counter}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/B Changed
                          - $return: true
                """.formatted(
                        documentId.value(),
                        LIFECYCLE_CHANNEL_BLUE_ID,
                        LIFECYCLE_EVENT_BLUE_ID,
                        timelineId,
                        ACTOR);
    }

    private record Scenario(
            BlueCoordination coordination,
            DefaultCoordinationEngine engine,
            DocumentHandle consumer,
            DocumentHandle source,
            DocumentId consumerId,
            ManagedEpochApplicationWork work,
            List<String> sourceHistory) implements AutoCloseable {
        private Scenario {
            sourceHistory = List.copyOf(sourceHistory);
        }

        @Override
        public void close() {
            coordination.close();
        }
    }

    private record StrandedCommit(
            ManagedEpochApplicationReceipt receipt,
            long nextSourceEpoch,
            long processCalls,
            long externalProcessCalls,
            int consumerHistorySize,
            String consumerBlueId,
            long consumerEventCount) {
    }
}
