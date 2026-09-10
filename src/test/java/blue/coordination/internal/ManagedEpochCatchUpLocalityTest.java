package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochApplicationReceipt;
import blue.coordination.sdk.TimelineHandle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ManagedEpochCatchUpLocalityTest {
    private static final int AMBIENT_COUNT = 1_000;
    private static final int ADMISSION_BATCH_SIZE = 25;
    private static final String ACTOR = "alice";
    private static final DocumentId A = DocumentId.of(
            "managed-epoch-locality-consumer");
    private static final DocumentId B = DocumentId.of(
            "managed-epoch-locality-source");
    private static final String A_TIMELINE = "managed-epoch/locality/a";
    private static final String B_TIMELINE = "managed-epoch/locality/b";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";

    @Test
    void oneThousandUnrelatedSessionsOpenOnlyExactCatchUpRows() {
        try (BlueCoordination coordination = LegacyContracts10TestProfile.sdkBuilder().build()) {
            // given
            DefaultCoordinationEngine engine = (DefaultCoordinationEngine)
                    coordination.advanced().rawEngine();
            List<DocumentId> ambient = admitAmbient(engine, AMBIENT_COUNT);
            DocumentId retainedAmbient = ambient.get(AMBIENT_COUNT / 2);

            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = source.history().get(0).after();
            coordination.operations()
                    .on(source)
                    .from(bTimeline)
                    .call("increment")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();

            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(aTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .requestYaml("child:\n  blueId: " + epochZero.blueId())
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(
                    EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition());
            assertTrue(attached.managedEpochApplications().isEmpty());

            ManagedOccurrenceCatchUpPlan plan = coordination.advanced()
                    .auditManagedCatchUpPlans(A).get(0);
            DocumentSession ambientSessionBefore = engine.documents()
                    .require(retainedAmbient);
            InMemoryDocumentStore.StoreStructureSnapshot structureBefore =
                    engine.documents().storeStructureSnapshotForTesting();
            EngineMetrics.MetricsSnapshot metricsBefore = engine
                    .engineMetrics().snapshot();

            // when
            DrainResult caughtUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(1, caughtUp.managedEpochApplications().size());
            ManagedEpochApplicationReceipt application = caughtUp
                    .managedEpochApplications().get(0);
            assertEquals(1L, consumer.snapshot().longAt("/observedChanges"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(
                    plan.planIdentity(),
                    coordination.advanced().auditManagedCatchUpPlan(
                            plan.planIdentity()).orElseThrow().planIdentity());
            assertEquals(
                    plan.barrierIdentity(),
                    coordination.advanced().auditManagedCatchUpBarrier(
                            plan.barrierIdentity()).orElseThrow()
                            .barrierIdentity());
            assertEquals(
                    application.applicationReceiptIdentity(),
                    coordination.advanced()
                            .auditManagedEpochApplicationReceipt(
                                    application.applicationReceiptIdentity())
                            .orElseThrow().applicationReceiptIdentity());
            assertEquals(
                    1L,
                    coordination.advanced().auditManagedEpoch(B, 1L)
                            .orElseThrow().epoch());

            EngineMetrics.MetricsSnapshot metricsAfter = engine
                    .engineMetrics().snapshot();
            InMemoryDocumentStore.StoreStructureSnapshot structureAfter =
                    engine.documents().storeStructureSnapshotForTesting();

            assertEquals(1_002, engine.documents().size());
            assertSame(
                    ambientSessionBefore,
                    engine.documents().require(retainedAmbient),
                    "unrelated durable session identity must be retained");
            assertTrue(structureBefore.sameComponentIndexEntryIdentity(
                    structureAfter, retainedAmbient));
            assertTrue(structureBefore.sameComponentStateEntryIdentity(
                    structureAfter, retainedAmbient));

            assertEquals(0L, delta(
                    metricsBefore,
                    metricsAfter,
                    CoordinationMetrics.Counter.FULL_ENVIRONMENT_SCANS
                            .name()));
            assertEquals(0L, delta(
                    metricsBefore,
                    metricsAfter,
                    CoordinationMetrics.Counter.UNRELATED_DOCUMENT_READS
                            .name()));
            assertEquals(2L, delta(
                    metricsBefore,
                    metricsAfter,
                    InMemoryDocumentStore.MANAGED_RECEIPT_ROWS_OPENED));
            assertEquals(3L, delta(
                    metricsBefore,
                    metricsAfter,
                    InMemoryDocumentStore.MANAGED_PLAN_ROWS_OPENED));
            assertEquals(2L, delta(
                    metricsBefore,
                    metricsAfter,
                    InMemoryDocumentStore.MANAGED_BARRIER_ROWS_OPENED));
            assertEquals(1L, delta(
                    metricsBefore,
                    metricsAfter,
                    InMemoryDocumentStore
                            .MANAGED_APPLICATION_RECEIPT_ROWS_OPENED));
            assertEquals(0L, delta(
                    metricsBefore,
                    metricsAfter,
                    ManagedEpochApplicationExecutor.UNRELATED_DOCUMENTS_SCANNED));
            assertTrue(delta(
                    metricsBefore,
                    metricsAfter,
                    ContractsClosureAdapter.OCCURRENCE_ROWS_EXAMINED) <= 8L,
                    "occurrence work must stay bounded by the selected closure");
        }
    }

    private static List<DocumentId> admitAmbient(
            DefaultCoordinationEngine engine,
            int count) {
        ArrayList<DocumentId> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ids.add(DocumentId.of("managed-epoch-locality-ambient-"
                    + String.format("%04d", index)));
        }
        for (int start = 0; start < ids.size();
                start += ADMISSION_BATCH_SIZE) {
            int end = Math.min(start + ADMISSION_BATCH_SIZE, ids.size());
            List<DocumentId> batchIds = ids.subList(start, end);
            engine.authorizeContractsPublicRoots(batchIds);
            Contracts10ScenarioBuilder batch =
                    new Contracts10ScenarioBuilder(engine);
            for (DocumentId documentId : batchIds) {
                batch.document(documentId, ambientYaml(documentId))
                        .publicRoot(documentId)
                        .expectedComponent(documentId);
            }
            batch.admissionLabel("managed-epoch-locality-ambient-" + start);
            ContractsClosureAdmissionReceipt receipt = batch.admitTo(engine)
                    .admissionReceipt();
            assertEquals(
                    ContractsClosureAdmissionReceipt.PublicationOutcome
                            .PUBLISHED,
                    receipt.publicationOutcome(),
                    "ambient admission batch " + start);
        }
        return List.copyOf(ids);
    }

    private static long delta(
            EngineMetrics.MetricsSnapshot before,
            EngineMetrics.MetricsSnapshot after,
            String counter) {
        assertTrue(before.counters().containsKey(counter),
                () -> "counter was not producer-registered: " + counter);
        assertTrue(after.counters().containsKey(counter),
                () -> "counter disappeared: " + counter);
        return Math.subtractExact(
                after.counters().get(counter),
                before.counters().get(counter));
    }

    private static String ambientYaml(DocumentId documentId) {
        return """
                documentId: %s
                phase: unrelated
                contracts: {}
                """.formatted(documentId.value());
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                children: {}
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  fromBChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/b
                    event:
                      type: Coordination/Event
                      kind: Locality/B Changed
                  onBChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromBChanged
                    event:
                      type: Coordination/Event
                      kind: Locality/B Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
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
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String sourceYaml() {
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
                              kind: Locality/B Changed
                          - $return: true
                """.formatted(
                        B.value(),
                        LIFECYCLE_CHANNEL_BLUE_ID,
                        LIFECYCLE_EVENT_BLUE_ID,
                        B_TIMELINE,
                        ACTOR);
    }
}
