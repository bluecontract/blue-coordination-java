package blue.coordination.stagedconsumer;

import blue.coordination.sdk.AdvancedCoordination;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochApplicationAttempt;
import blue.coordination.sdk.ManagedEpochApplicationReceipt;
import blue.coordination.sdk.ManagedEpochReceipt;
import blue.coordination.sdk.ManagedEpochSelector;
import blue.coordination.sdk.SourceOrder;
import blue.coordination.sdk.TimelineHandle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK execution smoke for the immutable staged coordinate. */
final class StagedCoordinationConsumerTest {

    @Test
    void executesOnTheRequestedJavaRuntime() {
        int expectedFeature = Integer.parseInt(System.getProperty(
                "blue.coordination.stagedConsumer.expectedJavaFeature"));

        assertEquals(expectedFeature, Runtime.version().feature());
    }

    @Test
    void resolvesAndExecutesTheStagedCoordinationArtifact() {
        // given
        String timelineId = "staged-consumer/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml("staged-counter",
                            counterYaml(timelineId))
                            .publicRoot()
                            .fromNow());

            // when
            EntryResult result = coordination.operations().on(counter)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 4")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals(4L, counter.snapshot().longAt("/counter"));
            assertEquals(1L, counter.snapshot().epoch());
            assertTrue(result.stats().gas() > 0L);
        }
    }

    @Test
    void retainedEpochEvidenceExecutesThroughTheStagedCoordinate() {
        // given
        String timelineId = "staged-consumer/retained";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, "alice");
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    "staged-retained-consumer",
                                    retainedConsumerYaml(timelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    "staged-retained-source",
                                    retainedSourceYaml(timelineId))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue retainedEpochZero = source.history().get(0).after();
            EntryResult incremented = coordination.operations().on(source)
                    .from(timeline)
                    .call("increment")
                    .through("sourceChannel")
                    .request(request -> { })
                    .execute();
            assertEquals(EntryDisposition.APPLIED,
                    incremented.disposition());
            List<String> sourceHistory = source.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();
            EntryHandle attachment = coordination.operations().on(consumer)
                    .from(timeline)
                    .call("attach")
                    .through("consumerChannel")
                    .request(request -> request.exact(
                            "child", retainedEpochZero))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            source.id(),
                            0L,
                            retainedEpochZero.blueId(),
                            "/child"))
                    .submit();

            // when
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            DrainResult caughtUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(EntryDisposition.APPLIED,
                    admitted.entry(attachment).disposition());
            assertTrue(admitted.managedEpochApplications().isEmpty());
            assertEquals(2L, consumer.snapshot().longAt(
                    "/observedChanges"));
            assertEquals(source.snapshot().blueId(),
                    consumer.snapshot().valueAt("/child").blueId());
            assertEquals(1L, source.snapshot().longAt("/counter"));
            assertEquals(sourceHistory, source.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList(), "catch-up must not reprocess its source");

            AdvancedCoordination advanced = coordination.advanced();
            ManagedEpochReceipt sourceReceipt = advanced
                    .auditManagedEpoch(source.id(), 1L)
                    .orElseThrow();
            assertEquals(sourceReceipt.receiptIdentity(), advanced
                    .auditManagedEpochReceipt(
                            sourceReceipt.receiptIdentity())
                    .orElseThrow().receiptIdentity());
            assertEquals(2, sourceReceipt.emittedEvents().size());
            assertEquals(List.of(0L, 1L), sourceReceipt.emittedEvents()
                    .stream()
                    .map(event -> event.ordinal())
                    .toList());
            assertEquals(sourceReceipt.emittedEvents().get(0).eventBlueId(),
                    sourceReceipt.emittedEvents().get(1).eventBlueId());
            assertNotEquals(sourceReceipt.emittedEvents().get(0)
                            .eventOccurrenceIdentity(),
                    sourceReceipt.emittedEvents().get(1)
                            .eventOccurrenceIdentity());
            assertEquals(sourceReceipt.emittedEvents().get(0).eventBlueId(),
                    sourceReceipt.emittedEvents().get(0)
                            .exactEvent().blueId());
            String exactEventJson = sourceReceipt.emittedEvents().get(0)
                    .exactEvent().json();
            assertTrue(exactEventJson.contains("\"Staged/Changed\""),
                    exactEventJson);
            SourceOrder sourceOrder = sourceReceipt.sourceOrder()
                    .orElseThrow();
            assertFalse(sourceOrder.components().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> sourceOrder.components().add("mutate"));

            assertEquals(1, caughtUp.managedEpochApplications().size());
            assertEquals(1,
                    caughtUp.managedEpochApplicationAttempts().size());
            ManagedEpochApplicationReceipt application = caughtUp
                    .managedEpochApplications().get(0);
            ManagedEpochApplicationAttempt attempt = caughtUp
                    .managedEpochApplicationAttempts().get(0);
            assertEquals(sourceReceipt.receiptIdentity(),
                    application.sourceReceiptIdentity());
            assertTrue(attempt.published());
            assertTrue(attempt.attempt().isComplete());
            assertEquals(application.workIdentity(),
                    attempt.work().workIdentity());
            assertEquals(ManagedEpochApplicationAttempt.Status.SUCCESS,
                    attempt.attempt().processResult().status());
            assertEquals(application.applicationReceiptIdentity(),
                    attempt.receipt().orElseThrow()
                            .applicationReceiptIdentity());

            var plan = advanced.auditManagedCatchUpPlans(
                    consumer.id()).get(0);
            assertEquals(0L, plan.admittedSourceEpoch());
            assertEquals(2L, plan.nextSourceEpoch());
            assertTrue(plan.status().terminal());
            assertEquals(plan.planIdentity(), advanced
                    .auditManagedCatchUpPlan(plan.planIdentity())
                    .orElseThrow().planIdentity());
            assertTrue(advanced.auditManagedDocumentReadiness(
                    consumer.id()).orElseThrow().ready());
            assertEquals(application.workIdentity(), advanced
                    .auditManagedEpochApplicationWork(
                            application.workIdentity())
                    .orElseThrow().workIdentity());
            assertEquals(application.applicationReceiptIdentity(), advanced
                    .auditManagedEpochApplicationReceipt(
                            application.applicationReceiptIdentity())
                    .orElseThrow().applicationReceiptIdentity());
        }
    }

    private static String counterYaml(String timelineId) {
        return """
                counter: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
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
                """.formatted(timelineId);
    }

    private static String retainedSourceYaml(String timelineId) {
        return """
                documentId: staged-retained-source
                counter: 0
                contracts:
                  sourceChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  increment:
                    type: Coordination/Sequential Workflow Operation
                    channel: sourceChannel
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
                              kind: Staged/Changed
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Staged/Changed
                          - $return: true
                """.formatted(timelineId);
    }

    private static String retainedConsumerYaml(String timelineId) {
        return """
                documentId: staged-retained-consumer
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromChild:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: Staged/Changed
                  onChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    onProcessingInitiated:
                      event:
                        type: Coordination/Event
                        kind: Staged/Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val:
                                $add:
                                  - $document: /observedChanges
                                  - 1
                          - $return: true
                  consumerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                  attach:
                    type: Coordination/Sequential Workflow Operation
                    channel: consumerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(timelineId);
    }
}
