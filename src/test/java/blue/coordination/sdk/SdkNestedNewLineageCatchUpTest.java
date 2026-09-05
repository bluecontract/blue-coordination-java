package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict SDK acceptance for a catch-up-created lineage and its birth evidence. */
final class SdkNestedNewLineageCatchUpTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId CONSUMER = DocumentId.of(
            "sdk-unsupported-nested-consumer");
    private static final DocumentId SOURCE = DocumentId.of(
            "sdk-unsupported-nested-source");
    private static final DocumentId NEW_NESTED = DocumentId.of(
            "sdk-unsupported-nested-new");
    private static final String CONSUMER_TIMELINE =
            "sdk/unsupported-nested/consumer";
    private static final String SOURCE_TIMELINE =
            "sdk/unsupported-nested/source";
    @Test
    void retainedApplicationPublishesVerifiedNestedBirthAtomically() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(CONSUMER_TIMELINE, ACTOR);
            TimelineHandle sourceTimeline = coordination.timelines()
                    .register(SOURCE_TIMELINE, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(CONSUMER, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    SOURCE,
                                    sourceYaml(SOURCE, SOURCE_TIMELINE))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue sourceEpochZero = source.history().get(0).after();

            assertEquals(EntryDisposition.APPLIED,
                    coordination.operations()
                            .on(source)
                            .from(sourceTimeline)
                            .call("increment")
                            .through("ownerChannel")
                            .request(request -> { })
                            .execute()
                            .disposition());
            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attachSourceAndRetainCandidate")
                    .through("ownerChannel")
                    .request(request -> request
                            .exact("source", sourceEpochZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition());
            ProcessingSelection selectionBefore = coordination.advanced()
                    .auditNextProcessingSelection();
            assertEquals(
                    ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION,
                    selectionBefore.kind());
            ManagedEpochApplicationWork work = selectionBefore
                    .managedEpochApplicationWork().orElseThrow();
            var sourceBefore = coordination.advanced().auditDocument(SOURCE);
            int consumerHistoryBefore = consumer.history().size();
            var retainedConsumerHistoryBefore = List.copyOf(
                    coordination.advanced().rawEngine().history(CONSUMER));
            assertEquals(consumerHistoryBefore + 1, retainedConsumerHistoryBefore.size());
            assertEquals(1L, work.expectedConsumerCommittedEpoch());
            assertEquals(0L, consumer.snapshot().epoch());
            int sourceHistoryBefore = source.history().size();

            // when
            DrainResult applied = coordination.processing()
                    .drainManagedEpochApplication(work.workIdentity());

            // then
            assertEquals(1, applied.managedEpochApplications().size(),
                    "Expected one authenticated retained application: "
                            + applied.managedEpochApplicationAttempts());
            assertTrue(applied.managedEpochApplicationAttempts().get(0).published());
            var occurrence = coordination.advanced().auditManagedOccurrence(
                    CONSUMER, "/children/new").orElseThrow();
            DocumentId born = occurrence.targetDocumentId();
            var birth = coordination.advanced().auditManagedEpoch(born, 0L).orElseThrow();
            assertEquals(DocumentRevision.Kind.INITIALIZATION, birth.kind());
            assertEquals(0L, coordination.advanced().auditDocument(born).epoch());
            assertEquals("authored-inside-retained-catch-up",
                    birth.afterDocument().scalarAt("/marker"));
            assertEquals(1L, occurrence.activationGeneration());
            assertTrue(occurrence.active());
            assertEquals(sourceHistoryBefore, source.history().size());
            assertEquals(sourceBefore.blueId(), coordination.advanced()
                    .auditDocument(SOURCE).blueId());
            assertEquals(sourceBefore.epoch(), coordination.advanced()
                    .auditDocument(SOURCE).epoch());
            assertEquals(retainedConsumerHistoryBefore.size() + 1, consumer.history().size());
            assertEquals(retainedConsumerHistoryBefore.stream()
                            .map(revision -> revision.managedEpochReceipt().orElseThrow().receiptIdentity())
                            .toList(),
                    consumer.history().subList(0, retainedConsumerHistoryBefore.size()).stream()
                            .map(revision -> revision.managedEpochReceipt().orElseThrow().receiptIdentity())
                            .toList());
            var application = applied.managedEpochApplications().get(0);
            var appliedRevision = consumer.history().get(consumer.history().size() - 1);
            assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION, appliedRevision.kind());
            assertEquals(2L, appliedRevision.epoch());
            assertEquals(application.consumerRevisionEpoch(), appliedRevision.epoch());
            assertEquals(application.consumerRevisionReceiptIdentity(),
                    appliedRevision.managedEpochReceipt().orElseThrow().receiptIdentity());
            assertEquals(birth.commitCompanionIdentity(), application.commitCompanionIdentity());
            assertEquals(work.sourceReceiptIdentity(), application.sourceReceiptIdentity());
            assertEquals(2L, coordination.advanced()
                    .auditManagedCatchUpPlan(work.planIdentity()).orElseThrow()
                    .nextSourceEpoch());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced().auditManagedCatchUpBarrier(
                            work.barrierIdentity()).orElseThrow().status());
            String committedConsumer = coordination.advanced()
                    .auditDocument(CONSUMER).blueId();
            control.restartFromStores();
            assertEquals(birth.receiptIdentity(), coordination.advanced()
                    .auditManagedEpoch(born, 0L).orElseThrow().receiptIdentity());
            assertEquals(committedConsumer, coordination.advanced()
                    .auditDocument(CONSUMER).blueId());
            DrainResult retried = coordination.processing().drain();
            assertTrue(retried.managedEpochApplications().isEmpty());
            assertTrue(retried.entries().isEmpty());
            assertEquals(sourceHistoryBefore, source.history().size());
            assertEquals(retainedConsumerHistoryBefore.size() + 1, consumer.history().size());
        }
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                children: {}
                sourceChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/source
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                  onSourceChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /sourceChanges
                              val: {$add: [{$document: /sourceChanges}, 1]}
                          - $if:
                              cond: {$eq: [{$document: /sourceChanges}, 0]}
                              then:
                                - $appendChange:
                                    op: add
                                    path: /children/new
                                    val:
                                      documentId: %s
                                      marker: authored-inside-retained-catch-up
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachSourceAndRetainCandidate:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      source: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/source
                              val: {$binding: event/message/request/source}
                          - $return: true
                """.formatted(
                CONSUMER.value(),
                NEW_NESTED.value(),
                CONSUMER_TIMELINE,
                ACTOR);
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
                              kind: CatchUp/Source Changed
                          - $return: true
                """.formatted(
                documentId.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                timelineId,
                ACTOR);
    }
}
