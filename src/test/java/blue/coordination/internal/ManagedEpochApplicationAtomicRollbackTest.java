package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedEpochApplicationReceipt;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
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
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomic rollback and exact-retry proof for one managed catch-up step. */
final class ManagedEpochApplicationAtomicRollbackTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";

    @Test
    void beforeSwapFailureRollsBackEveryManagedSurfaceAndRetryCommitsOnce() {
        try (Scenario scenario = prepared()) {
            // given
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            ContractsClosureAdapter adapter = engine.contractsClosureAdapter();
            ManagedEpochApplicationWork work = scenario.work();
            DurableEvidence before = DurableEvidence.capture(scenario);
            CatchUpPlanStore plansBefore = documents.catchUpPlansSnapshot();
            long processCallsBefore = counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS);
            long externalProcessCallsBefore = counter(
                    engine, "EXTERNAL_PROCESS_CALLS");

            adapter.onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint
                        .BEFORE_SWAP) {
                    throw new IllegalStateException(
                            "injected managed catch-up failure before swap");
                }
            });

            // when
            // PROCESS completes, but no durable store image is swapped.
            IllegalStateException failure;
            try {
                failure = assertThrows(
                        IllegalStateException.class,
                        adapter::processNextManagedEpochApplication);
            } finally {
                adapter.onStoreFailurePoint(ignored -> { });
            }

            // rollback checkpoint: every durable surface remains unchanged
            assertEquals(
                    "injected managed catch-up failure before swap",
                    failure.getMessage());
            assertEquals(before, DurableEvidence.capture(scenario));
            assertSame(plansBefore, documents.catchUpPlansSnapshot(),
                    "the cursor, plan, work, and application indexes swap "
                            + "only with the consumer revision");
            assertEquals(processCallsBefore + 1L, counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS));
            assertEquals(externalProcessCallsBefore, counter(
                    engine, "EXTERNAL_PROCESS_CALLS"));
            assertEquals(0L, counter(
                    engine,
                    ManagedEpochApplicationExecutor.SOURCE_PROCESS_CALLS));
            assertEquals(work.workIdentity(), documents.nextCatchUpWork()
                    .orElseThrow().workIdentity());

            // Retry the exact retained work.
            Optional<ContractsClosureAdapter.ManagedApplicationOutcome>
                    retried = adapter.processNextManagedEpochApplication();

            // then
            // The retry commits once and becomes the sole retained application.
            ContractsClosureAdapter.ManagedApplicationOutcome outcome =
                    retried.orElseThrow();
            assertTrue(outcome.published());
            assertFalse(outcome.replayed(),
                    "a pre-swap rollback has no committed response to replay");
            ManagedEpochApplicationReceipt application = outcome.receipt()
                    .orElseThrow();
            assertEquals(work.workIdentity(), application.workIdentity());
            assertEquals(2L, application.resultingSourceCursor());
            assertEquals(processCallsBefore + 2L, counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS));
            assertEquals(externalProcessCallsBefore, counter(
                    engine, "EXTERNAL_PROCESS_CALLS"));
            assertEquals(0L, counter(
                    engine,
                    ManagedEpochApplicationExecutor.SOURCE_PROCESS_CALLS));
            assertEquals(scenario.sourceHistory(), sourceHistory(scenario));
            assertEquals(scenario.sourceReceiptIdentities(),
                    receiptIdentities(documents, scenario.sourceId()));

            ManagedOccurrenceCatchUpPlan completed = documents.catchUpPlan(
                    work.planIdentity()).orElseThrow();
            ManagedCatchUpBarrier completedBarrier = documents.catchUpBarrier(
                    work.barrierIdentity()).orElseThrow();
            assertEquals(2L, completed.nextSourceEpoch());
            assertTrue(completed.status().terminal());
            assertEquals(
                    ManagedCatchUpBarrierStatus.COMPLETE,
                    completedBarrier.status());
            assertEquals(before.consumerHistory().size() + 1,
                    engine.history(scenario.consumerId()).size());
            assertEquals(before.consumerReceipts().size() + 1,
                    documents.managedEpochReceipts(
                            scenario.consumerId()).size());
            assertEquals(before.consumerEventCount() + 1L,
                    consumerEventCount(scenario));
            assertEquals(before.outbox().size() + 1,
                    outboxEvidence(documents).size());
            assertEquals(
                    application.applicationReceiptIdentity(),
                    documents.catchUpApplicationByWork(work.workIdentity())
                            .orElseThrow().applicationReceiptIdentity());
            assertEquals(before.applicationCount() + 1,
                    documents.catchUpPlansSnapshot().applicationCount());
            assertTrue(documents.closurePublicationReceipt(
                    work.workIdentity()).orElseThrow().commits());
            assertEquals(1L, documents.publicationSnapshot()
                    .publicationReceipts().stream()
                    .filter(work.workIdentity()::equals)
                    .count());
            assertTrue(documents.nextCatchUpWork().isEmpty());

            // and: another drain cannot duplicate the committed application
            assertTrue(adapter.processNextManagedEpochApplication().isEmpty());
            assertEquals(before.applicationCount() + 1,
                    documents.catchUpPlansSnapshot().applicationCount());
            assertEquals(before.consumerEventCount() + 1L,
                    consumerEventCount(scenario));
            assertEquals(processCallsBefore + 2L, counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS));
        }
    }

    private static Scenario prepared() {
        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            DocumentId consumerId = DocumentId.of(
                    "managed-epoch-atomic-rollback-consumer");
            DocumentId sourceId = DocumentId.of(
                    "managed-epoch-atomic-rollback-source");
            String consumerTimelineId =
                    "managed-epoch/atomic-rollback/consumer";
            String sourceTimelineId =
                    "managed-epoch/atomic-rollback/source";
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
            List<String> sourceHistory = sourceHistory(source);

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
                    sourceId,
                    work,
                    sourceHistory,
                    receiptIdentities(engine.documents(), sourceId));
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
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

    private static List<String> historyEvidence(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        return engine.history(documentId).stream()
                .map(revision -> revision.epoch()
                        + "|" + revision.kind()
                        + "|" + revision.after().blueId()
                        + "|" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("none"))
                .toList();
    }

    private static List<String> receiptIdentities(
            InMemoryDocumentStore documents,
            DocumentId documentId) {
        return documents.managedEpochReceipts(documentId).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static List<String> sourceHistory(Scenario scenario) {
        return sourceHistory(scenario.source());
    }

    private static List<String> sourceHistory(DocumentHandle source) {
        return source.history().stream()
                .map(revision -> revision.after().blueId())
                .toList();
    }

    private static List<String> outboxEvidence(
            InMemoryDocumentStore documents) {
        return documents.publicationSnapshot().outbox().stream()
                .map(ManagedEpochApplicationAtomicRollbackTest::eventEvidence)
                .toList();
    }

    private static String eventEvidence(PublicEventOccurrence event) {
        return event.publicEventOrdinal()
                + "|" + event.eventOccurrenceOrdinal()
                + "|" + event.publicRootDocumentId().value()
                + "|" + event.eventOccurrenceIdentity()
                + "|" + event.eventBlueId();
    }

    private static OccurrenceEvidence occurrenceEvidence(
            Scenario scenario) {
        ManagedOccurrenceBinding binding = scenario.engine().documents()
                .occurrenceInventory()
                .find(scenario.consumerId(), "/children/b")
                .orElseThrow();
        return new OccurrenceEvidence(
                binding.occurrenceIdentity(),
                binding.activationGeneration(),
                binding.active(),
                binding.pendingHistoricalEpoch(),
                binding.expectedTargetBlueId());
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
                    event:
                      type:
                        blueId: %s
                  onProcessingInitiated:
                    type: Coordination/Sequential Workflow
                    channel: lifecycleChannel
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
            DocumentId sourceId,
            ManagedEpochApplicationWork work,
            List<String> sourceHistory,
            List<String> sourceReceiptIdentities) implements AutoCloseable {
        private Scenario {
            sourceHistory = List.copyOf(sourceHistory);
            sourceReceiptIdentities = List.copyOf(sourceReceiptIdentities);
        }

        @Override
        public void close() {
            coordination.close();
        }
    }

    private record HeadEvidence(
            long epoch,
            String blueId,
            String status) {
    }

    private record OccurrenceEvidence(
            String occurrenceIdentity,
            long activationGeneration,
            boolean active,
            Long pendingHistoricalEpoch,
            String expectedTargetBlueId) {
    }

    private record DurableEvidence(
            InMemoryDocumentStore.PublicationSnapshot publication,
            HeadEvidence committedHead,
            HeadEvidence readyHead,
            List<String> consumerHistory,
            List<String> consumerReceipts,
            List<String> sourceHistory,
            List<String> sourceReceipts,
            OccurrenceEvidence occurrence,
            String planSnapshotIdentity,
            String barrierSnapshotIdentity,
            int workCount,
            int applicationCount,
            Optional<String> applicationReceiptIdentity,
            Optional<String> closurePublicationIdentity,
            List<String> outbox,
            long consumerEventCount) {
        private DurableEvidence {
            consumerHistory = List.copyOf(consumerHistory);
            consumerReceipts = List.copyOf(consumerReceipts);
            sourceHistory = List.copyOf(sourceHistory);
            sourceReceipts = List.copyOf(sourceReceipts);
            applicationReceiptIdentity = Objects.requireNonNull(
                    applicationReceiptIdentity,
                    "applicationReceiptIdentity");
            closurePublicationIdentity = Objects.requireNonNull(
                    closurePublicationIdentity,
                    "closurePublicationIdentity");
            outbox = List.copyOf(outbox);
        }

        static DurableEvidence capture(Scenario scenario) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            blue.coordination.api.DocumentSnapshot committed =
                    engine.auditDocument(scenario.consumerId());
            blue.coordination.sdk.DocumentSnapshot ready =
                    scenario.consumer().snapshot();
            ManagedOccurrenceCatchUpPlan plan = documents.catchUpPlan(
                    scenario.work().planIdentity()).orElseThrow();
            ManagedCatchUpBarrier barrier = documents.catchUpBarrier(
                    scenario.work().barrierIdentity()).orElseThrow();
            CatchUpPlanStore catchUp = documents.catchUpPlansSnapshot();
            return new DurableEvidence(
                    documents.publicationSnapshot(),
                    new HeadEvidence(
                            committed.epoch(),
                            committed.blueId(),
                            committed.status().name()),
                    new HeadEvidence(
                            ready.epoch(),
                            ready.blueId(),
                            ready.ready() ? "READY" : "NOT_READY"),
                    historyEvidence(engine, scenario.consumerId()),
                    receiptIdentities(documents, scenario.consumerId()),
                    ManagedEpochApplicationAtomicRollbackTest
                            .sourceHistory(scenario),
                    receiptIdentities(documents, scenario.sourceId()),
                    occurrenceEvidence(scenario),
                    plan.snapshotIdentity(),
                    barrier.snapshotIdentity(),
                    catchUp.workCount(),
                    catchUp.applicationCount(),
                    documents.catchUpApplicationByWork(
                                    scenario.work().workIdentity())
                            .map(ManagedEpochApplicationReceipt
                                    ::applicationReceiptIdentity),
                    documents.closurePublicationReceipt(
                                    scenario.work().workIdentity())
                            .map(ContractsClosurePublicationReceipt
                                    ::publicationIdentity),
                    outboxEvidence(documents),
                    ManagedEpochApplicationAtomicRollbackTest
                            .consumerEventCount(scenario));
        }
    }
}
