package blue.coordination.sdk;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.ProcessingSelection;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK diagnostic for the unsupported catch-up-created lineage lane. */
final class SdkUnsupportedNestedNewLineageTest {
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
    private static final DocumentId NEW_NESTED_RESULT = DocumentId.of(
            "3ZxNU3NNJCpBYjQmNtU4u7w1H9QEUga8seogAYhN8KQS");
    private static final String CONSUMER_TIMELINE =
            "sdk/unsupported-nested/consumer";
    private static final String SOURCE_TIMELINE =
            "sdk/unsupported-nested/source";
    @Test
    void retainedApplicationCreatingNewNestedLineageIsTyped() {
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
            ManagedOccurrenceCatchUpPlan planBefore = coordination.advanced()
                    .auditManagedCatchUpPlans(CONSUMER).get(0);
            ProcessingSelection selectionBefore = coordination.advanced()
                    .auditNextProcessingSelection();
            assertEquals(
                    ProcessingSelection.Kind.MANAGED_EPOCH_APPLICATION,
                    selectionBefore.kind());
            ManagedEpochApplicationWork work = selectionBefore
                    .managedEpochApplicationWork().orElseThrow();
            var consumerBefore = coordination.advanced()
                    .auditDocument(CONSUMER);
            var sourceBefore = coordination.advanced().auditDocument(SOURCE);
            int consumerHistoryBefore = consumer.history().size();
            int sourceHistoryBefore = source.history().size();

            // when
            DrainResult blocked = coordination.processing()
                    .drainManagedEpochApplication(work.workIdentity());

            // then
            assertTrue(blocked.managedEpochApplications().isEmpty());
            assertTrue(blocked.managedEpochEvidenceFailures().isEmpty());
            assertEquals(1,
                    blocked.managedEpochApplicationAttempts().size());
            ManagedEpochApplicationAttempt applicationAttempt = blocked
                    .managedEpochApplicationAttempts().get(0);
            assertEquals(work.workIdentity(),
                    applicationAttempt.work().workIdentity());
            assertFalse(applicationAttempt.published());
            assertFalse(applicationAttempt.replayed());
            assertTrue(applicationAttempt.receipt().isEmpty());
            assertTrue(applicationAttempt.attempt().complete());
            assertTrue(applicationAttempt.attempt()
                    .processResult().commits());
            ManagedEpochApplicationAttempt.PublicationFailure failure =
                    applicationAttempt.publicationFailure().orElseThrow();

            assertEquals(
                    ManagedEpochApplicationAttempt.PublicationFailureCode
                            .UNSUPPORTED_NESTED_NEW_LINEAGE,
                    failure.code());
            assertEquals(CONSUMER.value(),
                    failure.details().get("consumerDocumentId"));
            assertEquals(SOURCE.value(),
                    failure.details().get("sourceDocumentId"));
            assertEquals("1", failure.details().get("sourceEpoch"));
            assertEquals("/children/source",
                    failure.details().get("targetPath"));
            assertEquals(NEW_NESTED_RESULT.value(),
                    failure.details().get("newDocumentId"));
            assertEquals(work.workIdentity(),
                    failure.details().get("workIdentity"));
            assertEquals(work.planIdentity(),
                    failure.details().get("planIdentity"));
            assertEquals(work.barrierIdentity(),
                    failure.details().get("barrierIdentity"));
            assertEquals(work.sourceReceiptIdentity(),
                    failure.details().get("sourceReceiptIdentity"));

            var consumerAfter = coordination.advanced()
                    .auditDocument(CONSUMER);
            var sourceAfter = coordination.advanced().auditDocument(SOURCE);
            assertEquals(consumerBefore.epoch(), consumerAfter.epoch());
            assertEquals(consumerBefore.blueId(), consumerAfter.blueId());
            assertEquals(sourceBefore.epoch(), sourceAfter.epoch());
            assertEquals(sourceBefore.blueId(), sourceAfter.blueId());
            assertEquals(consumerHistoryBefore, consumer.history().size());
            assertEquals(sourceHistoryBefore, source.history().size());
            ManagedOccurrenceCatchUpPlan planAfter = coordination.advanced()
                    .auditManagedCatchUpPlan(work.planIdentity())
                    .orElseThrow();
            assertFalse(planBefore.snapshotIdentity().equals(
                    planAfter.snapshotIdentity()));
            assertEquals(ManagedCatchUpStatus.BLOCKED,
                    planAfter.status());
            assertEquals(planBefore.nextSourceEpoch(),
                    planAfter.nextSourceEpoch());
            assertEquals(
                    CoordinationErrorCode.UNSUPPORTED_NESTED_NEW_LINEAGE
                            .name(),
                    planAfter.waitingCode().orElseThrow());
            var barrierAfter = coordination.advanced()
                    .auditManagedCatchUpBarrier(work.barrierIdentity())
                    .orElseThrow();
            assertEquals(ManagedCatchUpBarrierStatus.BLOCKED,
                    barrierAfter.status());
            assertEquals(
                    CoordinationErrorCode.UNSUPPORTED_NESTED_NEW_LINEAGE
                            .name(),
                    barrierAfter.waitingCode().orElseThrow());
            ProcessingSelection selectionAfter = coordination.advanced()
                    .auditNextProcessingSelection();
            assertEquals(ProcessingSelection.Kind.NONE,
                    selectionAfter.kind());
            assertTrue(selectionAfter.managedEpochApplicationWork().isEmpty());
            CoordinationException notAdmitted = assertThrows(
                    CoordinationException.class,
                    () -> coordination.advanced()
                            .auditDocument(NEW_NESTED_RESULT));
            assertEquals(CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                    notAdmitted.code());

            control.restartFromStores();
            assertEquals(ManagedCatchUpStatus.BLOCKED,
                    coordination.advanced()
                            .auditManagedCatchUpPlan(work.planIdentity())
                            .orElseThrow().status());
            assertEquals(ManagedCatchUpBarrierStatus.BLOCKED,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(
                                    work.barrierIdentity())
                            .orElseThrow().status());
            assertEquals(ProcessingSelection.Kind.NONE,
                    coordination.advanced()
                            .auditNextProcessingSelection().kind());
            assertEquals(consumerBefore.blueId(), coordination.advanced()
                    .auditDocument(CONSUMER).blueId());
            assertEquals(sourceBefore.blueId(), coordination.advanced()
                    .auditDocument(SOURCE).blueId());
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
