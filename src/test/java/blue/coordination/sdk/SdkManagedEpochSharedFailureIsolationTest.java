package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK acceptance for shared receipts and isolated managed gas failures. */
final class SdkManagedEpochSharedFailureIsolationTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final String SOURCE_PROCESS_CALLS =
            "managedEpoch.catchUp.sourceProcessCalls";

    private static final DocumentId A = DocumentId.of(
            "sdk-shared-failure-a");
    private static final DocumentId A_PEER = DocumentId.of(
            "sdk-shared-failure-a-peer");
    private static final DocumentId B = DocumentId.of(
            "sdk-shared-failure-b");
    private static final DocumentId C = DocumentId.of(
            "sdk-shared-failure-c");
    private static final DocumentId D = DocumentId.of(
            "sdk-shared-failure-d");
    private static final String B_TIMELINE = "sdk/shared-failure/b";
    private static final String CONSUMER_TIMELINE =
            "sdk/shared-failure/consumers";

    @Test
    void sharedSuffixSurvivesOneDeterministicConsumerGasFailure() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(CONSUMER_TIMELINE, ACTOR);
            ClosureHandle aClosure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("a", A, failingConsumerYaml())
                            .document("peer", A_PEER, loopPeerYaml())
                            .bindOccurrence("a", "/peer", "peer")
                            .bindOccurrence("peer", "/peer", "a")
                            .publicRoot("a")
                            .fromNow()
                            .build());
            DocumentHandle a = aClosure.document("a");
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle c = coordination.documents().admit(
                    ManagedDocument.yaml(C, successfulConsumerYaml(
                                    C))
                            .publicRoot()
                            .fromNow());
            DocumentHandle d = coordination.documents().admit(
                    ManagedDocument.yaml(D, successfulConsumerYaml(
                                    D))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue bZero = b.history().get(0).after();
            assertApplied(sourceOperation(
                    coordination, b, bTimeline, "change").execute());
            assertApplied(sourceOperation(
                    coordination, b, bTimeline, "fail").execute());
            List<String> bHistory = historyEvidence(b);
            String bOneReceipt = receiptIdentity(coordination, 1L);
            String bTwoReceipt = receiptIdentity(coordination, 2L);

            ExactBlueValue attachment = coordination.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: 2100000000000601
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
                    CONSUMER_TIMELINE, ACTOR, bZero.blueId()));
            EntryHandle attachAll = coordination.events()
                    .from(consumerTimeline)
                    .exact(attachment)
                    .submit();
            DrainResult attachments = coordination.processing().drain(
                    new DrainBudget(100L, 1L));

            assertApplied(attachments, attachAll);
            assertTrue(attachments.managedEpochApplications().isEmpty());
            assertTrue(attachments.managedEpochApplicationAttempts()
                    .isEmpty());
            for (DocumentId consumer : List.of(A, C, D)) {
                ManagedOccurrenceCatchUpPlan plan = onlyPlan(
                        coordination, consumer);
                assertEquals(B, plan.sourceDocumentId());
                assertEquals(0L, plan.admittedSourceEpoch());
                assertEquals(1L, plan.nextSourceEpoch());
                assertEquals(2L, plan.requiredThroughSourceEpoch());
                assertEquals(1L, plan.activationGeneration());
            }

            DrainResult aFirstEpoch = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(bOneReceipt), applicationSources(
                    aFirstEpoch));
            assertEquals(List.of(A), applicationConsumers(aFirstEpoch));
            assertEquals(1, aFirstEpoch
                    .managedEpochApplicationAttempts().size());
            assertTrue(aFirstEpoch.managedEpochApplicationAttempts()
                    .get(0).published());
            ManagedEpochApplicationReceipt aFirstApplication =
                    aFirstEpoch.managedEpochApplications().get(0);
            ManagedOccurrenceCatchUpPlan aPlan = onlyPlan(
                    coordination, A);
            assertEquals(2L, aPlan.nextSourceEpoch());
            assertEquals(SessionStatus.CATCHING_UP,
                    coordination.advanced()
                            .auditManagedDocumentReadiness(A)
                            .orElseThrow().status());
            FailureState beforeFailure = failureState(
                    coordination, aPlan, aFirstApplication);
            assertEquals(1L, beforeFailure.publicEventCount());
            CoordinationTestControl.MetricsSnapshot metricsBeforeFailure =
                    control.metricsSnapshot();

            // when
            DrainResult isolated = coordination.processing().drain();
            DrainResult retried = coordination.processing().drain();

            // then
            assertTrue(isolated.blocked());
            assertEquals(List.of(
                            C, D, A, C, D),
                    isolated.managedEpochApplicationAttempts().stream()
                            .map(attempt -> attempt.work()
                                    .consumerDocumentId())
                            .toList());
            assertEquals(List.of(C, D, C, D),
                    applicationConsumers(isolated));
            assertEquals(List.of(
                            bOneReceipt,
                            bOneReceipt,
                            bTwoReceipt,
                            bTwoReceipt),
                    applicationSources(isolated));
            List<ManagedEpochApplicationAttempt> failed = isolated
                    .managedEpochApplicationAttempts().stream()
                    .filter(attempt -> !attempt.published())
                    .toList();
            assertEquals(1, failed.size());
            ManagedEpochApplicationAttempt firstFailure = failed.get(0);
            assertGasFailure(firstFailure, bTwoReceipt);
            assertEquals(firstFailure.work().workIdentity(),
                    coordination.advanced()
                            .auditManagedEpochApplicationWork(
                                    firstFailure.work().workIdentity())
                            .orElseThrow().workIdentity());
            assertEquals(beforeFailure,
                    failureState(coordination, aPlan, aFirstApplication));
            assertEquals(1L, committedObserved(c));
            assertEquals(1L, committedFailedObserved(c));
            assertEquals(1L, committedObserved(d));
            assertEquals(1L, committedFailedObserved(d));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(C)
                    .orElseThrow().ready());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(D)
                    .orElseThrow().ready());
            List<String> cHistoryAfterSuccess = historyEvidence(c);
            List<String> dHistoryAfterSuccess = historyEvidence(d);
            assertEquals(bHistory, historyEvidence(b));

            ManagedOccurrenceCatchUpPlan completedC = onlyPlan(
                    coordination, C);
            ManagedOccurrenceCatchUpPlan completedD = onlyPlan(
                    coordination, D);
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedC.status());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedD.status());
            assertEquals(3L, completedC.nextSourceEpoch());
            assertEquals(3L, completedD.nextSourceEpoch());

            assertTrue(retried.blocked());
            assertTrue(retried.managedEpochApplications().isEmpty());
            assertEquals(1,
                    retried.managedEpochApplicationAttempts().size());
            ManagedEpochApplicationAttempt retryFailure = retried
                    .managedEpochApplicationAttempts().get(0);
            assertGasFailure(retryFailure, bTwoReceipt);
            assertRetryAfterSharedTopologyGrowth(
                    firstFailure, retryFailure);
            assertEquals(retryFailure.work().expectedGraphGeneration(),
                    coordination.advanced()
                            .auditManagedEpochApplicationWork(
                                    retryFailure.work().workIdentity())
                            .orElseThrow().expectedGraphGeneration());
            assertEquals(beforeFailure,
                    failureState(coordination, aPlan, aFirstApplication));
            assertEquals(cHistoryAfterSuccess, historyEvidence(c));
            assertEquals(dHistoryAfterSuccess, historyEvidence(d));
            assertEquals(bHistory, historyEvidence(b));
            assertEquals(0L, publicCounterDelta(
                    metricsBeforeFailure,
                    control.metricsSnapshot(),
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(0L, rawCounterDelta(
                    metricsBeforeFailure,
                    control.metricsSnapshot(),
                    SOURCE_PROCESS_CALLS));
        }
    }

    private static FailureState failureState(
            BlueCoordination coordination,
            ManagedOccurrenceCatchUpPlan originalPlan,
            ManagedEpochApplicationReceipt committedApplication) {
        blue.coordination.api.DocumentSnapshot audit = coordination
                .advanced().auditDocument(A);
        ManagedDocumentReadiness readiness = coordination.advanced()
                .auditManagedDocumentReadiness(A).orElseThrow();
        ManagedOccurrenceCatchUpPlan plan = coordination.advanced()
                .auditManagedCatchUpPlan(originalPlan.planIdentity())
                .orElseThrow();
        ManagedOccurrenceAudit occurrence = coordination.advanced()
                .auditManagedOccurrence(A, "/source")
                .orElseThrow();
        blue.coordination.api.ManagedEpochApplicationReceipt application =
                coordination.advanced()
                .auditManagedEpochApplicationReceipt(
                        committedApplication.applicationReceiptIdentity())
                .orElseThrow();
        long publicEventCount = coordination.advanced().rawEngine()
                .history(A).stream()
                .flatMap(revision -> revision.emittedEvents().stream())
                .count();
        return new FailureState(
                audit.epoch(),
                audit.blueId(),
                audit.status(),
                readiness.readinessIdentity(),
                readiness.committedEpoch(),
                readiness.committedBlueId(),
                readiness.readyEpoch().orElseThrow(),
                readiness.readyBlueId().orElseThrow(),
                plan.snapshotIdentity(),
                plan.nextSourceEpoch(),
                plan.requiredThroughSourceEpoch(),
                occurrence,
                historyEvidence(coordination.documents().require(A)),
                publicEventCount,
                application.applicationReceiptIdentity());
    }

    private static void assertGasFailure(
            ManagedEpochApplicationAttempt failure,
            String sourceReceiptIdentity) {
        assertEquals(A, failure.work().consumerDocumentId());
        assertEquals(B, failure.work().sourceDocumentId());
        assertEquals(2L, failure.work().sourceEpoch());
        assertEquals(sourceReceiptIdentity,
                failure.work().sourceReceiptIdentity());
        assertFalse(failure.published());
        assertFalse(failure.replayed());
        assertTrue(failure.receipt().isEmpty());
        assertTrue(failure.attempt().isComplete(), () ->
                "suspended demands=" + failure.attempt().resourceDemands()
                        + ", requiredExactBlueIds="
                        + failure.attempt().requiredExactBlueIds());
        ManagedEpochApplicationAttempt.ProcessResult result = failure
                .attempt().processResult();
        assertEquals(ManagedEpochApplicationAttempt.Status.GAS_LIMIT_EXCEEDED,
                result.status(), result.diagnostic().toString());
        assertFalse(result.commits());
        assertTrue(result.rollbackToInput());
        assertEquals(result.inputClosureIdentity(),
                result.outputClosureIdentity());
        assertNotNull(result.rejectedWorkOccurrence());
        assertNotNull(result.rejectedCharge());
        assertTrue(result.publicEvents().isEmpty());
    }

    private static void assertRetryAfterSharedTopologyGrowth(
            ManagedEpochApplicationAttempt first,
            ManagedEpochApplicationAttempt retry) {
        assertEquals(first.work().workIdentity(),
                retry.work().workIdentity());
        assertEquals(first.work().expectedConsumerCommittedEpoch(),
                retry.work().expectedConsumerCommittedEpoch());
        assertEquals(first.work().expectedConsumerCommittedBlueId(),
                retry.work().expectedConsumerCommittedBlueId());
        assertEquals(first.work().expectedGraphGeneration(),
                retry.work().expectedGraphGeneration());
        ManagedEpochApplicationAttempt.ProcessResult left = first
                .attempt().processResult();
        ManagedEpochApplicationAttempt.ProcessResult right = retry
                .attempt().processResult();
        assertTrue(right.graphGeneration() > left.graphGeneration(),
                "completed sibling occurrences join the ordinary affected "
                        + "closure before the retry");
        assertNotEquals(left.invocationIdentity(),
                right.invocationIdentity());
        assertNotEquals(left.inputClosureIdentity(),
                right.inputClosureIdentity());
        assertEquals(left.inputClosureIdentity(),
                left.outputClosureIdentity());
        assertEquals(right.inputClosureIdentity(),
                right.outputClosureIdentity());
    }

    private static ManagedOccurrenceCatchUpPlan onlyPlan(
            BlueCoordination coordination,
            DocumentId consumer) {
        List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                .auditManagedCatchUpPlans(consumer);
        assertEquals(1, plans.size());
        return plans.get(0);
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

    private static void assertApplied(
            DrainResult drain,
            EntryHandle entry) {
        assertEquals(EntryDisposition.APPLIED,
                drain.entry(entry).disposition(),
                drain.entry(entry).diagnostic().toString());
    }

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static List<DocumentId> applicationConsumers(
            DrainResult result) {
        return result.managedEpochApplications().stream()
                .map(ManagedEpochApplicationReceipt::consumerDocumentId)
                .toList();
    }

    private static List<String> applicationSources(DrainResult result) {
        return result.managedEpochApplications().stream()
                .map(ManagedEpochApplicationReceipt::sourceReceiptIdentity)
                .toList();
    }

    private static String receiptIdentity(
            BlueCoordination coordination,
            long epoch) {
        return coordination.advanced().auditManagedEpoch(B, epoch)
                .orElseThrow().receiptIdentity();
    }

    private static long committedObserved(DocumentHandle consumer) {
        return ((Number) consumer.history().get(
                        consumer.history().size() - 1)
                .after().scalarAt("/observedChanges"))
                .longValue();
    }

    private static long committedFailedObserved(DocumentHandle consumer) {
        return ((Number) consumer.history().get(
                        consumer.history().size() - 1)
                .after().scalarAt("/observedFailures"))
                .longValue();
    }

    private static List<String> historyEvidence(DocumentHandle document) {
        return document.history().stream()
                .map(revision -> revision.epoch()
                        + ":" + revision.kind()
                        + ":" + revision.after().blueId()
                        + ":" + revision.publicEvents().stream()
                                .map(PublicEvent::blueId)
                                .toList()
                        + ":" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("-"))
                .toList();
    }

    private static long publicCounterDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            CoordinationMetrics.Counter counter) {
        return rawCounterDelta(before, after, counter.name());
    }

    private static long rawCounterDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String counter) {
        return Math.subtractExact(
                after.counters().getOrDefault(counter, 0L),
                before.counters().getOrDefault(counter, 0L));
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
                              kind: Shared/B Changed
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
                              kind: Shared/B Fail
                          - $return: true
                """.formatted(
                B.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                B_TIMELINE,
                ACTOR);
    }

    private static String failingConsumerYaml() {
        return """
                documentId: %s
                peer: {}
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                      - /source
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Shared/B Changed}
                  observeSourceChange:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event: {type: Coordination/Event, kind: Shared/B Changed}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Shared/A Observed
                          - $return: true
                  fromSourceFailure:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Shared/B Fail}
                  startInfiniteLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceFailure
                    event: {type: Coordination/Event, kind: Shared/B Fail}
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
                """.formatted(A.value(), CONSUMER_TIMELINE, ACTOR);
    }

    private static String loopPeerYaml() {
        return """
                documentId: %s
                peer: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
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
                """.formatted(A_PEER.value());
    }

    private static String successfulConsumerYaml(
            DocumentId documentId) {
        return """
                documentId: %s
                observedChanges: 0
                observedFailures: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /source
                  fromSourceChanged:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Shared/B Changed}
                  observeSourceChange:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceChanged
                    event: {type: Coordination/Event, kind: Shared/B Changed}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Shared/Consumer Observed
                          - $return: true
                  fromSourceFailure:
                    type: Embedded Node Channel
                    sourcePath: /source
                    event: {type: Coordination/Event, kind: Shared/B Fail}
                  observeSourceFailure:
                    type: Coordination/Sequential Workflow
                    channel: fromSourceFailure
                    event: {type: Coordination/Event, kind: Shared/B Fail}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedFailures
                              val: {$add: [{$document: /observedFailures}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Shared/Consumer Observed
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

    private record FailureState(
            long committedEpoch,
            String committedBlueId,
            SessionStatus status,
            String readinessIdentity,
            long readinessCommittedEpoch,
            String readinessCommittedBlueId,
            long readyEpoch,
            String readyBlueId,
            String planSnapshotIdentity,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            ManagedOccurrenceAudit occurrence,
            List<String> history,
            long publicEventCount,
            String committedApplicationIdentity) {
        private FailureState {
            history = List.copyOf(history);
        }
    }
}
