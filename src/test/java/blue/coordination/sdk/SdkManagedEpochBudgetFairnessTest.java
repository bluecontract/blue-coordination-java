package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bounded-drain fairness proofs for permanently failing managed lanes. */
final class SdkManagedEpochBudgetFairnessTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId POISON_A =
            DocumentId.of("scheduler-a-poison");
    private static final DocumentId POISON_A_PEER =
            DocumentId.of("scheduler-a-poison-peer");
    private static final DocumentId POISON_B =
            DocumentId.of("scheduler-b-poison");
    private static final DocumentId POISON_B_PEER =
            DocumentId.of("scheduler-b-poison-peer");
    private static final DocumentId HEALTHY =
            DocumentId.of("scheduler-z-healthy");
    private static final DocumentId SOURCE =
            DocumentId.of("scheduler-source");
    private static final DocumentId DIRECT =
            DocumentId.of("scheduler-direct");
    private static final String SOURCE_TIMELINE = "scheduler/source";
    private static final String CONSUMER_TIMELINE = "scheduler/consumers";
    private static final String DIRECT_TIMELINE = "scheduler/direct";

    @Test
    void poisonLanesRoundRobinAndHealthyProgressSurviveRestart() {
        // given
        RunEvidence uninterrupted;
        RunEvidence restarted;

        // when
        try (Scenario scenario = scenario()) {
            uninterrupted = runBounded(scenario, false);
        }
        try (Scenario scenario = scenario()) {
            restarted = runBounded(scenario, true);
        }

        // then
        assertEquals(uninterrupted.selectionOrder(),
                restarted.selectionOrder());
        assertEquals(List.of(
                        "managed:scheduler-a-poison:1:published",
                        "direct",
                        "managed:scheduler-b-poison:1:published",
                        "managed:scheduler-z-healthy:1:published",
                        "managed:scheduler-a-poison:2:failed",
                        "managed:scheduler-b-poison:2:failed",
                        "managed:scheduler-z-healthy:2:published"),
                uninterrupted.selectionOrder());
        assertTrue(uninterrupted.directApplied());
        assertTrue(restarted.directApplied());
        assertTrue(restarted.restartObserved());
    }

    private static RunEvidence runBounded(
            Scenario scenario,
            boolean restartAfterFirstFailure) {
        List<String> order = new ArrayList<>();
        List<String> poisonABeforeFailure = null;
        List<String> poisonBBeforeFailure = null;
        int failedAttempts = 0;
        boolean directApplied = false;
        boolean restartObserved = false;

        for (int call = 0; call < 16; call++) {
            DrainResult result = scenario.coordination().processing().drain(
                    new DrainBudget(1L, 1L));
            int selected = Math.addExact(
                    result.entries().size(),
                    result.managedEpochApplicationAttempts().size());
            assertTrue(selected <= 1,
                    () -> "one selected-entry budget opened " + selected
                            + " items: " + result);
            assertTrue(result.managedEpochApplications().size() <= 1);

            if (!result.entries().isEmpty()) {
                assertTrue(result.managedEpochApplicationAttempts().isEmpty());
                assertEquals(1, result.entries().size());
                assertEquals(scenario.directEntry(),
                        result.entries().get(0).entry());
                assertEquals(EntryDisposition.APPLIED,
                        result.entries().get(0).disposition());
                directApplied = true;
                order.add("direct");
            }

            if (!result.managedEpochApplicationAttempts().isEmpty()) {
                assertTrue(result.entries().isEmpty());
                assertEquals(1,
                        result.managedEpochApplicationAttempts().size());
                ManagedEpochApplicationAttempt attempt = result
                        .managedEpochApplicationAttempts().get(0);
                order.add("managed:"
                        + attempt.work().consumerDocumentId().value()
                        + ":" + attempt.work().sourceEpoch()
                        + ":" + (attempt.published()
                                ? "published" : "failed"));
                if (!attempt.published()) {
                    failedAttempts = Math.addExact(failedAttempts, 1);
                    assertTrue(attempt.attempt().isComplete());
                    assertEquals(
                            ManagedEpochApplicationAttempt.Status
                                    .GAS_LIMIT_EXCEEDED,
                            attempt.attempt().processResult().status());
                }
            }

            if (poisonABeforeFailure == null
                    && nextSourceEpoch(scenario, POISON_A) == 2L
                    && nextSourceEpoch(scenario, POISON_B) == 2L
                    && nextSourceEpoch(scenario, HEALTHY) == 2L) {
                poisonABeforeFailure = historyEvidence(
                        scenario.poisonA());
                poisonBBeforeFailure = historyEvidence(
                        scenario.poisonB());
            }

            if (restartAfterFirstFailure
                    && !restartObserved
                    && failedAttempts == 1) {
                scenario.control().restartFromStores();
                restartObserved = true;
            }

            if (failedAttempts >= 2
                    && directApplied
                    && onlyPlan(scenario, HEALTHY).status()
                            == ManagedCatchUpStatus.COMPLETE) {
                break;
            }
        }

        assertTrue(directApplied);
        assertEquals(2, failedAttempts);
        assertEquals(ManagedCatchUpStatus.COMPLETE,
                onlyPlan(scenario, HEALTHY).status());
        assertEquals(3L, nextSourceEpoch(scenario, HEALTHY));
        assertEquals(1L,
                scenario.healthy().snapshot().longAt("/observedChanges"));
        assertEquals(1L,
                scenario.healthy().snapshot().longAt("/observedFailures"));
        assertEquals(2L, nextSourceEpoch(scenario, POISON_A));
        assertEquals(2L, nextSourceEpoch(scenario, POISON_B));
        assertFalse(onlyPlan(scenario, POISON_A).status()
                == ManagedCatchUpStatus.COMPLETE);
        assertFalse(onlyPlan(scenario, POISON_B).status()
                == ManagedCatchUpStatus.COMPLETE);
        assertEquals(poisonABeforeFailure,
                historyEvidence(scenario.poisonA()));
        assertEquals(poisonBBeforeFailure,
                historyEvidence(scenario.poisonB()));
        if (restartAfterFirstFailure) {
            assertTrue(restartObserved);
        }
        return new RunEvidence(
                order, directApplied, restartObserved);
    }

    private static Scenario scenario() {
        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle sourceTimeline = coordination.timelines().register(
                    SOURCE_TIMELINE, ACTOR);
            TimelineHandle consumerTimeline = coordination.timelines().register(
                    CONSUMER_TIMELINE, ACTOR);
            TimelineHandle directTimeline = coordination.timelines().register(
                    DIRECT_TIMELINE, ACTOR);

            ClosureHandle poisonAClosure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("consumer", POISON_A,
                                    failingConsumerYaml(POISON_A))
                            .document("peer", POISON_A_PEER,
                                    loopPeerYaml(POISON_A_PEER))
                            .bindOccurrence("consumer", "/peer", "peer")
                            .bindOccurrence("peer", "/peer", "consumer")
                            .publicRoot("consumer")
                            .fromNow()
                            .build());
            ClosureHandle poisonBClosure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("consumer", POISON_B,
                                    failingConsumerYaml(POISON_B))
                            .document("peer", POISON_B_PEER,
                                    loopPeerYaml(POISON_B_PEER))
                            .bindOccurrence("consumer", "/peer", "peer")
                            .bindOccurrence("peer", "/peer", "consumer")
                            .publicRoot("consumer")
                            .fromNow()
                            .build());
            DocumentHandle poisonA = poisonAClosure.document("consumer");
            DocumentHandle poisonB = poisonBClosure.document("consumer");
            DocumentHandle healthy = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    HEALTHY,
                                    successfulConsumerYaml(HEALTHY))
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(SOURCE, sourceYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle direct = coordination.documents().admit(
                    ManagedDocument.yaml(DIRECT, directYaml())
                            .publicRoot()
                            .fromNow());

            ExactBlueValue sourceEpochZero = source.history().get(0).after();
            assertApplied(sourceOperation(
                    coordination, source, sourceTimeline, "change").execute());
            assertApplied(sourceOperation(
                    coordination, source, sourceTimeline, "fail").execute());

            ExactBlueValue attachment = coordination.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: 2100000000000701
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                    message:
                      type: Coordination/Operation Request
                      operation: attach
                      channel: ownerChannel
                      request:
                        source:
                          blueId: %s
                    """.formatted(
                    CONSUMER_TIMELINE,
                    ACTOR,
                    sourceEpochZero.blueId()));
            EntryHandle attachmentEntry = coordination.events()
                    .from(consumerTimeline)
                    .exact(attachment)
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(100L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachmentEntry).disposition());
            assertTrue(attached.managedEpochApplicationAttempts().isEmpty());

            EntryHandle directEntry = coordination.operations()
                    .on(direct)
                    .from(directTimeline)
                    .call("increment")
                    .through("ownerChannel")
                    .request(request -> { })
                    .submit();
            return new Scenario(
                    coordination,
                    control,
                    poisonA,
                    poisonB,
                    healthy,
                    directEntry);
        } catch (RuntimeException failure) {
            coordination.close();
            throw failure;
        }
    }

    private static long nextSourceEpoch(
            Scenario scenario,
            DocumentId consumer) {
        return onlyPlan(scenario, consumer).nextSourceEpoch();
    }

    private static ManagedOccurrenceCatchUpPlan onlyPlan(
            Scenario scenario,
            DocumentId consumer) {
        List<ManagedOccurrenceCatchUpPlan> plans = scenario.coordination()
                .advanced().auditManagedCatchUpPlans(consumer);
        assertEquals(1, plans.size());
        return plans.get(0);
    }

    private static List<String> historyEvidence(DocumentHandle document) {
        return document.history().stream()
                .map(revision -> revision.epoch()
                        + ":" + revision.kind()
                        + ":" + revision.after().blueId()
                        + ":" + revision.publicEvents().stream()
                                .map(PublicEvent::blueId)
                                .toList())
                .toList();
    }

    private static OperationCall sourceOperation(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline,
            String operation) {
        return coordination.operations()
                .on(source)
                .from(timeline)
                .call(operation)
                .through("ownerChannel")
                .request(request -> { });
    }

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                initializationCount: 0
                counter: 0
                contracts:
                  lifecycleChannel:
                    type: {blueId: %s}
                    order: 0
                    event:
                      type: {blueId: %s}
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
                  change:
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
                              kind: Scheduler/Changed
                          - $return: true
                  fail:
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
                              kind: Scheduler/Fail
                          - $return: true
                """.formatted(
                SOURCE.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                SOURCE_TIMELINE,
                ACTOR);
    }

    private static String failingConsumerYaml(DocumentId documentId) {
        return """
                documentId: %s
                peer: {}
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/peer, /source]
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Scheduler/Changed}
                  observeSourceChange:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event: {type: Coordination/Event, kind: Scheduler/Changed}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $return: true
                  fromSourceFailure:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Scheduler/Fail}
                  startInfiniteLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceFailure
                    event: {type: Coordination/Event, kind: Scheduler/Fail}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                  fromPeerLoop:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: LOOP}
                  relayPeerLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromPeerLoop
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
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
                      source: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /source
                              val: {$binding: event/message/request/source}
                          - $return: true
                """.formatted(
                documentId.value(), CONSUMER_TIMELINE, ACTOR);
    }

    private static String loopPeerYaml(DocumentId documentId) {
        return """
                documentId: %s
                peer: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/peer]
                  fromPeerLoop:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: LOOP}
                  relayPeerLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromPeerLoop
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(documentId.value());
    }

    private static String successfulConsumerYaml(DocumentId documentId) {
        return """
                documentId: %s
                observedChanges: 0
                observedFailures: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/source]
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Scheduler/Changed}
                  observeSourceChange:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event: {type: Coordination/Event, kind: Scheduler/Changed}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $return: true
                  fromSourceFailure:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Scheduler/Fail}
                  observeSourceFailure:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceFailure
                    event: {type: Coordination/Event, kind: Scheduler/Fail}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedFailures
                              val: {$add: [{$document: /observedFailures}, 1]}
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
                      source: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /source
                              val: {$binding: event/message/request/source}
                          - $return: true
                """.formatted(
                documentId.value(), CONSUMER_TIMELINE, ACTOR);
    }

    private static String directYaml() {
        return """
                documentId: %s
                counter: 0
                contracts:
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
                          - $return: true
                """.formatted(DIRECT.value(), DIRECT_TIMELINE, ACTOR);
    }

    private record Scenario(
            BlueCoordination coordination,
            CoordinationTestControl control,
            DocumentHandle poisonA,
            DocumentHandle poisonB,
            DocumentHandle healthy,
            EntryHandle directEntry) implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }

    private record RunEvidence(
            List<String> selectionOrder,
            boolean directApplied,
            boolean restartObserved) {
        private RunEvidence {
            selectionOrder = List.copyOf(selectionOrder);
        }
    }
}
