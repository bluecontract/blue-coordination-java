package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK acceptance for cycles created by retained epoch application. */
final class SdkManagedEpochCycleAcceptanceTest {
    private static final String ACTOR = "alice";

    @Test
    void retainedApplicationFormsFiniteCycleThenDetachRetiresIt() {
        // given
        try (CycleScenario scenario = prepared("finite", "attachFinite")) {
            assertFalse(scenario.a().exact().cyclicMember());
            assertFalse(scenario.b().exact().cyclicMember());
            List<String> sourceHistory = history(scenario.b());
            ManagedOccurrenceCatchUpPlan pending = onlyPlan(scenario);
            assertEquals(1L, pending.nextSourceEpoch());
            assertEquals(1L, pending.requiredThroughSourceEpoch());

            // when
            DrainResult applied = scenario.coordination().processing().drain(
                    new DrainBudget(1L, 1L));
            boolean cycleFormed = scenario.a().exact().cyclicMember()
                    && scenario.b().exact().cyclicMember();
            String cycleMaster = cyclicMaster(
                    scenario.a().snapshot().blueId());
            String sourceCycleMaster = cyclicMaster(
                    scenario.b().snapshot().blueId());
            long finiteReactions = scenario.a().snapshot().longAt(
                    "/finiteReactions");
            ManagedOccurrenceCatchUpPlan completedBeforeDetach =
                    onlyPlan(scenario);
            boolean sourceStayedExact = sourceHistory.equals(
                    history(scenario.b()));
            ExactNodeEvidence committedCycleEvidence = scenario.coordination()
                    .advanced()
                    .auditExactNodeEvidence(scenario.a().id())
                    .orElseThrow();
            String committedCycleJson = scenario.a().exact().json();

            EntryResult detachedParent = operation(
                    scenario, scenario.b(), scenario.bTimeline(),
                    "detachParent").execute();
            DrainResult detachCatchUp = scenario.coordination()
                    .processing().drain();
            ManagedOccurrenceCatchUpPlan completedAfterDetach =
                    onlyPlan(scenario);
            boolean cycleDissolved = !scenario.a().exact().cyclicMember()
                    && !scenario.b().exact().cyclicMember();

            EntryResult detachedChild = operation(
                    scenario, scenario.a(), scenario.aTimeline(),
                    "detachChild").execute();
            ManagedOccurrenceCatchUpPlan retired = onlyPlan(scenario);

            // then
            assertEquals(1, applied.managedEpochApplications().size());
            ManagedEpochApplicationReceipt application = applied
                    .managedEpochApplications().get(0);
            assertEquals(scenario.a().id(),
                    application.consumerDocumentId());
            assertEquals(scenario.sourceEpochOneReceipt(),
                    application.sourceReceiptIdentity());
            assertTrue(cycleFormed,
                    "the retained application must create the cycle");
            assertEquals(cycleMaster, sourceCycleMaster,
                    "both members shared the captured cyclic master");
            assertEquals(1L, finiteReactions);
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedBeforeDetach.status());
            assertEquals(committedCycleJson,
                    committedCycleEvidence.exactContent());
            assertEquals(2, committedCycleEvidence.declaredPlaceholderSet()
                    .orElseThrow().size(),
                    "committed cyclic evidence must carry the complete set");
            assertTrue(sourceStayedExact,
                    "forming the cycle must not reprocess source B");
            assertApplied(detachedParent);
            assertTrue(detachCatchUp.quiescent());
            assertTrue(cycleDissolved);
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedAfterDetach.status());
            assertEquals(completedAfterDetach.requiredThroughSourceEpoch()
                            + 1L,
                    completedAfterDetach.nextSourceEpoch());
            assertApplied(detachedChild);
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    retired.status());
            assertFalse(scenario.coordination().advanced()
                    .auditManagedOccurrence(
                            scenario.a().id(), "/child")
                    .orElseThrow().active());
        }
    }

    @Test
    void infiniteCycleApplicationRollsBackAndRetriesExactly() {
        // given
        try (CycleScenario scenario = prepared("infinite", "attachLoop")) {
            FailureState before = failureState(scenario);

            // when
            DrainResult failed = scenario.coordination().processing().drain();
            DrainResult retried = scenario.coordination().processing().drain();
            FailureState afterRetry = failureState(scenario);

            // then
            ManagedEpochApplicationAttempt first = onlyFailedAttempt(failed);
            ManagedEpochApplicationAttempt retry = onlyFailedAttempt(retried);
            assertGasFailure(first, scenario);
            assertGasFailure(retry, scenario);
            assertSameFailure(first, retry);
            assertEquals(before, afterRetry);
        }
    }

    @Test
    void completedRetainedLineageCanLaterMergeIntoCycleAndProcessAgain() {
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build()) {
            // given
            DocumentId aId = DocumentId.of(
                    "sdk-managed-epoch-post-catch-up-cycle-a");
            DocumentId bId = DocumentId.of(
                    "sdk-managed-epoch-post-catch-up-cycle-b");
            String aTimelineId =
                    "sdk/managed-epoch-post-catch-up-cycle/a";
            String bTimelineId =
                    "sdk/managed-epoch-post-catch-up-cycle/b";
            TimelineHandle aTimeline = coordination.timelines().register(
                    aTimelineId, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    bTimelineId, ACTOR);
            String authoredBYaml = sourceYaml(bId, bTimelineId);
            ExactBlueValue authoredB = coordination.values().yaml(
                    authoredBYaml);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    aId,
                                    dynamicConsumerYaml(aId, aTimelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(bId, authoredBYaml)
                            .publicRoot()
                            .fromNow());

            assertApplied(operation(
                    coordination, b, bTimeline, "touch").execute());
            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachChild")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", authoredB))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            bId, -1L, authoredB.blueId(), "/child"))
                    .submit();
            assertApplied(coordination.processing().drain(
                    new DrainBudget(1L, 1L)), attachment);
            for (int attempt = 0; attempt < 32
                    && !coordination.advanced()
                            .auditManagedDocumentReadiness(aId)
                            .orElseThrow().ready(); attempt++) {
                DrainResult progress = coordination.processing().drain(
                        new DrainBudget(1L, 1L));
                assertFalse(progress.blocked(),
                        progress.diagnostic().toString());
            }
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(aId)
                    .orElseThrow().ready(),
                    "bounded catch-up must reach a ready head");
            ManagedOccurrenceCatchUpPlan completed = coordination.advanced()
                    .auditManagedCatchUpPlans(aId).get(0);
            assertEquals(ManagedCatchUpStatus.COMPLETE, completed.status());
            String retainedSourceReceipt = coordination.advanced()
                    .auditManagedEpoch(bId, 1L)
                    .orElseThrow().receiptIdentity();

            // when
            assertApplied(coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("connectParent")
                    .through("ownerChannel")
                    .request(request -> request.exact("parent", a.exact()))
                    .execute());
            assertTrue(a.exact().cyclicMember());
            assertTrue(b.exact().cyclicMember());

            EntryResult finite = operation(
                    coordination, a, aTimeline, "startFinite").execute();
            ManagedOccurrenceCatchUpPlan remainedComplete = coordination
                    .advanced().auditManagedCatchUpPlans(aId).get(0);

            // then
            assertApplied(finite);
            assertEquals(1L, a.snapshot().longAt("/finiteReactions"));
            assertEquals(completed.snapshotIdentity(),
                    remainedComplete.snapshotIdentity(),
                    "later cyclic processing must not replay completed "
                            + "retained catch-up");
            assertEquals(retainedSourceReceipt, coordination.advanced()
                    .auditManagedEpoch(bId, 1L)
                    .orElseThrow().receiptIdentity(),
                    "the retained source receipt remains immutable");
        }
    }

    private static CycleScenario prepared(
            String label,
            String sourceOperation) {
        BlueCoordination coordination = LegacyContracts10TestProfile.builder().build();
        try {
            DocumentId aId = DocumentId.of(
                    "sdk-managed-epoch-cycle-a-" + label);
            DocumentId bId = DocumentId.of(
                    "sdk-managed-epoch-cycle-b-" + label);
            String aTimelineId = "sdk/managed-epoch-cycle/a/" + label;
            String bTimelineId = "sdk/managed-epoch-cycle/b/" + label;
            TimelineHandle aTimeline = coordination.timelines().register(
                    aTimelineId, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    bTimelineId, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    aId,
                                    consumerYaml(aId, aTimelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    bId,
                                    sourceYaml(bId, bTimelineId))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = b.history().get(0).after();
            EntryResult sourceAdvanced = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call(sourceOperation)
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "parent", a.snapshot().exact()))
                    .execute();
            assertApplied(sourceAdvanced);
            String epochOneReceipt = coordination.advanced()
                    .auditManagedEpoch(bId, 1L)
                    .orElseThrow().receiptIdentity();
            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachChild")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", epochZero))
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertApplied(admitted, attachment);
            assertTrue(admitted.managedEpochApplications().isEmpty());
            assertEquals(SessionStatus.CATCHING_UP,
                    coordination.advanced()
                            .auditManagedDocumentReadiness(aId)
                            .orElseThrow().status());
            return new CycleScenario(
                    coordination,
                    a,
                    b,
                    aTimeline,
                    bTimeline,
                    epochOneReceipt);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static OperationCall operation(
            CycleScenario scenario,
            DocumentHandle target,
            TimelineHandle timeline,
            String operation) {
        return scenario.coordination().operations()
                .on(target)
                .from(timeline)
                .call(operation)
                .through("ownerChannel")
                .request(request -> { });
    }

    private static OperationCall operation(
            BlueCoordination coordination,
            DocumentHandle target,
            TimelineHandle timeline,
            String operation) {
        return coordination.operations()
                .on(target)
                .from(timeline)
                .call(operation)
                .through("ownerChannel")
                .request(request -> { });
    }

    private static ManagedOccurrenceCatchUpPlan onlyPlan(
            CycleScenario scenario) {
        List<ManagedOccurrenceCatchUpPlan> plans = scenario.coordination()
                .advanced().auditManagedCatchUpPlans(scenario.a().id());
        assertEquals(1, plans.size());
        return plans.get(0);
    }

    private static ManagedEpochApplicationAttempt onlyFailedAttempt(
            DrainResult result) {
        assertTrue(result.blocked());
        assertTrue(result.managedEpochApplications().isEmpty());
        assertEquals(1, result.managedEpochApplicationAttempts().size());
        ManagedEpochApplicationAttempt attempt = result
                .managedEpochApplicationAttempts().get(0);
        assertFalse(attempt.published());
        return attempt;
    }

    private static void assertGasFailure(
            ManagedEpochApplicationAttempt attempt,
            CycleScenario scenario) {
        assertEquals(scenario.a().id(),
                attempt.work().consumerDocumentId());
        assertEquals(scenario.b().id(), attempt.work().sourceDocumentId());
        assertEquals(1L, attempt.work().sourceEpoch());
        assertEquals(scenario.sourceEpochOneReceipt(),
                attempt.work().sourceReceiptIdentity());
        assertTrue(attempt.receipt().isEmpty());
        assertTrue(attempt.attempt().isComplete());
        ManagedEpochApplicationAttempt.ProcessResult result = attempt
                .attempt().processResult();
        assertEquals(ManagedEpochApplicationAttempt.Status.GAS_LIMIT_EXCEEDED,
                result.status(), result.diagnostic().toString());
        assertFalse(result.commits());
        assertTrue(result.rollbackToInput());
        assertEquals(result.inputClosureIdentity(),
                result.outputClosureIdentity());
        assertTrue(result.publicEvents().isEmpty());
    }

    private static void assertSameFailure(
            ManagedEpochApplicationAttempt first,
            ManagedEpochApplicationAttempt retry) {
        assertEquals(first.work().workIdentity(),
                retry.work().workIdentity());
        ManagedEpochApplicationAttempt.ProcessResult left = first
                .attempt().processResult();
        ManagedEpochApplicationAttempt.ProcessResult right = retry
                .attempt().processResult();
        assertEquals(left.invocationIdentity(), right.invocationIdentity());
        assertEquals(left.totalGas(), right.totalGas());
        assertEquals(left.gasTraceIdentity(), right.gasTraceIdentity());
        assertEquals(left.rejectedWorkOccurrence().workIdentity(),
                right.rejectedWorkOccurrence().workIdentity());
        assertEquals(left.rejectedCharge().rejectedChargeIdentity(),
                right.rejectedCharge().rejectedChargeIdentity());
    }

    private static FailureState failureState(CycleScenario scenario) {
        blue.coordination.api.DocumentSnapshot a = scenario.coordination()
                .advanced().auditDocument(scenario.a().id());
        ManagedOccurrenceCatchUpPlan plan = onlyPlan(scenario);
        ManagedOccurrenceAudit occurrence = scenario.coordination()
                .advanced().auditManagedOccurrence(
                        scenario.a().id(), "/child")
                .orElseThrow();
        return new FailureState(
                a.epoch(),
                a.blueId(),
                a.status(),
                plan.snapshotIdentity(),
                plan.nextSourceEpoch(),
                plan.requiredThroughSourceEpoch(),
                occurrence,
                history(scenario.a()),
                history(scenario.b()));
    }

    private static List<String> history(DocumentHandle document) {
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

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static void assertApplied(
            DrainResult result,
            EntryHandle entry) {
        assertApplied(result.entry(entry));
    }

    private static String cyclicMaster(String blueId) {
        int separator = blueId.lastIndexOf('#');
        assertTrue(separator > 0, blueId);
        return blueId.substring(0, separator);
    }

    private static String consumerYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                finiteReactions: 0
                counter: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
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
                  detachChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /child}
                          - $return: true
                  startFinite:
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
                              kind: Cycle/Finite
                          - $return: true
                  fromFinite:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                  settleFinite:
                    type: Coordination/Sequential Workflow
                    channel: fromFinite
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /finiteReactions
                              val: {$add: [{$document: /finiteReactions}, 1]}
                          - $return: true
                  fromLoop:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromLoop
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String dynamicConsumerYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                finiteReactions: 0
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
                  attachChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/child}
                          - $appendChange:
                              op: add
                              path: /contracts/embedded
                              val:
                                type: Process Embedded
                                paths:
                                  - /child
                          - $return: true
                  startFinite:
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
                              kind: Cycle/Finite
                          - $return: true
                  fromFinite:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                  settleFinite:
                    type: Coordination/Sequential Workflow
                    channel: fromFinite
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /finiteReactions
                              val: {$add: [{$document: /finiteReactions}, 1]}
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String sourceYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                counter: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /parent
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachFinite:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      parent: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /parent
                              val: {$binding: event/message/request/parent}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Cycle/Finite
                          - $return: true
                  attachLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      parent: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /parent
                              val: {$binding: event/message/request/parent}
                          - $appendEvent: {type: Coordination/Event, kind: LOOP}
                          - $return: true
                  touch:
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
                  connectParent:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      parent: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /parent
                              val: {$binding: event/message/request/parent}
                          - $return: true
                  detachParent:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /parent}
                          - $return: true
                  fromFinite:
                    type: Embedded Node Channel
                    sourcePath: /parent
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                  relayFinite:
                    type: Coordination/Sequential Workflow
                    channel: fromFinite
                    event: {type: Coordination/Event, kind: Cycle/Finite}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Cycle/Finite
                          - $return: true
                  fromLoop:
                    type: Embedded Node Channel
                    sourcePath: /parent
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromLoop
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private record CycleScenario(
            BlueCoordination coordination,
            DocumentHandle a,
            DocumentHandle b,
            TimelineHandle aTimeline,
            TimelineHandle bTimeline,
            String sourceEpochOneReceipt) implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }

    private record FailureState(
            long epoch,
            String blueId,
            SessionStatus status,
            String planSnapshotIdentity,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            ManagedOccurrenceAudit occurrence,
            List<String> consumerHistory,
            List<String> sourceHistory) {
        private FailureState {
            consumerHistory = List.copyOf(consumerHistory);
            sourceHistory = List.copyOf(sourceHistory);
        }
    }
}
