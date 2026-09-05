package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
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
import blue.coordination.sdk.ManagedEpochEvidenceFailure;
import blue.coordination.sdk.TimelineHandle;
import blue.language.processor.closure.ManagedDocumentTransitionReceipt;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRootEventOccurrence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-closed immutable managed-history acceptance. */
final class ManagedEpochReceiptVerificationTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final String ALTERNATE_COMPANION_IDENTITY =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Method IDENTIFY_TRANSITION = identifyTransition();

    @Test
    void sixMissingOrTamperedHistoryCasesFailClosedAndAllowSiblingProgress() {
        // given
        List<Fault> faults = List.of(
                Fault.MISSING_EPOCH,
                Fault.WRONG_BEFORE,
                Fault.WRONG_AFTER,
                Fault.WRONG_SOURCE,
                Fault.WRONG_EVENT,
                Fault.WRONG_RECEIPT_IDENTITY);

        // when
        ArrayList<Fault> verified = new ArrayList<>();
        for (Fault fault : faults) {
            assertFailure(fault);
            verified.add(fault);
        }

        // then
        assertEquals(faults, verified);
    }

    @Test
    void missingContractsTransitionBlocksBeforeProcess() {
        // given
        Fault fault = Fault.MISSING_TRANSITION;

        // when
        Fault verified = verifyFailure(fault);

        // then
        assertEquals(fault, verified);
    }

    private static void assertFailure(Fault fault) {
        assertEquals(fault, verifyFailure(fault));
    }

    private static Fault verifyFailure(Fault fault) {
        try (Scenario scenario = prepared(fault.name().toLowerCase())) {
            InMemoryDocumentStore documents = scenario.engine().documents();
            ManagedEpochApplicationWork failedWork = scenario.failedWork();
            ManagedOccurrenceCatchUpPlan planBefore = documents
                    .catchUpPlan(failedWork.planIdentity()).orElseThrow();
            ManagedCatchUpBarrier barrierBefore = documents
                    .catchUpBarrier(failedWork.barrierIdentity()).orElseThrow();
            DocumentSession consumerBefore = documents.require(
                    failedWork.consumerDocumentId());
            SessionSnapshot headsBefore = SessionSnapshot.capture(
                    consumerBefore);
            ManagedOccurrenceBinding occurrenceBefore = documents
                    .occurrenceInventory()
                    .find(
                            failedWork.consumerDocumentId(),
                            failedWork.targetPath())
                    .orElseThrow();
            int historySizeBefore = scenario.engine().history(
                    failedWork.consumerDocumentId()).size();
            long processCallsBefore = counter(
                    scenario.engine(),
                    ManagedEpochApplicationExecutor.PROCESS_CALLS);

            installFault(fault, scenario);

            ManagedEpochEvidenceException direct = assertThrows(
                    ManagedEpochEvidenceException.class,
                    () -> scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(failedWork));
            assertSame(failedWork, direct.work());
            assertEquals(fault.status(), direct.planStatus());
            assertEquals(fault.code(), direct.code());
            assertEquals(processCallsBefore, counter(
                    scenario.engine(),
                    ManagedEpochApplicationExecutor.PROCESS_CALLS));

            scenario.engine().restartFromStores();
            ManagedEpochEvidenceException restarted = assertThrows(
                    ManagedEpochEvidenceException.class,
                    () -> scenario.engine().contractsClosureAdapter()
                            .executeManagedEpochApplication(failedWork));
            assertEquals(direct.work().workIdentity(),
                    restarted.work().workIdentity());
            assertEquals(direct.planStatus(), restarted.planStatus());
            assertEquals(direct.code(), restarted.code());
            assertEquals(direct.message(), restarted.message());
            assertSame(consumerBefore, documents.require(
                    failedWork.consumerDocumentId()));
            assertEquals(headsBefore, SessionSnapshot.capture(
                    documents.require(failedWork.consumerDocumentId())));
            assertEquals(occurrenceBefore, documents.occurrenceInventory()
                    .find(
                            failedWork.consumerDocumentId(),
                            failedWork.targetPath())
                    .orElseThrow());
            assertEquals(processCallsBefore, counter(
                    scenario.engine(),
                    ManagedEpochApplicationExecutor.PROCESS_CALLS));

            DrainResult drained = scenario.coordination().processing().drain(
                    new DrainBudget(10L, 10L));

            assertEquals(1, drained.managedEpochEvidenceFailures().size());
            ManagedEpochEvidenceFailure exposed = drained
                    .managedEpochEvidenceFailures().get(0);
            assertSdkWorkEquals(failedWork, exposed.work());
            assertEquals(
                    ManagedEpochEvidenceFailure.Status.valueOf(
                            fault.status().name()),
                    exposed.status());
            assertEquals(direct.code(), exposed.code());
            assertEquals(direct.message(), exposed.diagnostic());

            ManagedOccurrenceCatchUpPlan failedPlan = documents
                    .catchUpPlan(failedWork.planIdentity()).orElseThrow();
            ManagedCatchUpBarrier failedBarrier = documents
                    .catchUpBarrier(failedWork.barrierIdentity()).orElseThrow();
            assertEquals(planBefore.nextSourceEpoch(),
                    failedPlan.nextSourceEpoch());
            assertEquals(planBefore.requiredThroughSourceEpoch(),
                    failedPlan.requiredThroughSourceEpoch());
            assertEquals(fault.status(), failedPlan.status());
            assertEquals(fault.code(), failedPlan.waitingCode().orElseThrow());
            assertEquals(fault.barrierStatus(), failedBarrier.status());
            assertEquals(fault.code(),
                    failedBarrier.waitingCode().orElseThrow());
            assertEquals(barrierBefore.barrierIdentity(),
                    failedBarrier.barrierIdentity());
            assertFalse(documents.catchUpPlansSnapshot()
                    .pendingWorkForPlan(failedWork.planIdentity()).found());
            assertTrue(documents.catchUpApplicationByWork(
                    failedWork.workIdentity()).isEmpty());

            DocumentSession consumerAfter = documents.require(
                    failedWork.consumerDocumentId());
            assertSame(consumerBefore, consumerAfter);
            assertEquals(headsBefore, SessionSnapshot.capture(consumerAfter));
            assertEquals(historySizeBefore, scenario.engine().history(
                    failedWork.consumerDocumentId()).size());
            ManagedOccurrenceBinding occurrenceAfter = documents
                    .occurrenceInventory()
                    .find(
                            failedWork.consumerDocumentId(),
                            failedWork.targetPath())
                    .orElseThrow();
            assertEquals(
                    occurrenceBefore.pendingHistoricalEpoch(),
                    occurrenceAfter.pendingHistoricalEpoch());
            assertEquals(
                    occurrenceBefore.activationGeneration(),
                    occurrenceAfter.activationGeneration());
            assertEquals(
                    occurrenceBefore.occurrenceIdentity(),
                    occurrenceAfter.occurrenceIdentity());

            assertEquals(1, drained.managedEpochApplications().size());
            assertEquals(1,
                    drained.managedEpochApplicationAttempts().size());
            assertTrue(drained.managedEpochApplicationAttempts().get(0)
                    .published());
            assertEquals(
                    scenario.siblingWork().consumerDocumentId(),
                    drained.managedEpochApplicationAttempts().get(0)
                            .work().consumerDocumentId());
            assertEquals(
                    scenario.siblingWork().consumerDocumentId(),
                    drained.managedEpochApplications().get(0)
                            .consumerDocumentId());
            assertEquals(1L,
                    scenario.siblingConsumer().snapshot()
                            .longAt("/observedChanges"));
            assertTrue(scenario.coordination().advanced()
                    .auditManagedDocumentReadiness(
                            scenario.siblingWork().consumerDocumentId())
                    .orElseThrow().ready());
            assertEquals(processCallsBefore + 1L, counter(
                    scenario.engine(),
                    ManagedEpochApplicationExecutor.PROCESS_CALLS));
            assertEquals(0L, counter(
                    scenario.engine(),
                    ManagedEpochApplicationExecutor.SOURCE_PROCESS_CALLS));
            assertFalse(drained.quiescent(),
                    "the typed failed barrier remains active");
            return fault;
        }
    }

    private static Scenario prepared(String label) {
        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            DocumentId failedConsumerId = DocumentId.of(
                    "managed-history-failed-consumer-" + label);
            DocumentId failedSourceId = DocumentId.of(
                    "managed-history-failed-source-" + label);
            DocumentId siblingConsumerId = DocumentId.of(
                    "managed-history-sibling-consumer-" + label);
            DocumentId siblingSourceId = DocumentId.of(
                    "managed-history-sibling-source-" + label);
            String failedConsumerTimeline = "managed-history/" + label
                    + "/failed-consumer";
            String failedSourceTimeline = "managed-history/" + label
                    + "/failed-source";
            String siblingConsumerTimeline = "managed-history/" + label
                    + "/sibling-consumer";
            String siblingSourceTimeline = "managed-history/" + label
                    + "/sibling-source";

            TimelineHandle failedConsumerFeed = coordination.timelines()
                    .register(failedConsumerTimeline, ACTOR);
            TimelineHandle failedSourceFeed = coordination.timelines()
                    .register(failedSourceTimeline, ACTOR);
            TimelineHandle siblingConsumerFeed = coordination.timelines()
                    .register(siblingConsumerTimeline, ACTOR);
            TimelineHandle siblingSourceFeed = coordination.timelines()
                    .register(siblingSourceTimeline, ACTOR);
            DocumentHandle failedConsumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    failedConsumerId,
                                    consumerYaml(
                                            failedConsumerId,
                                            failedConsumerTimeline))
                            .publicRoot()
                            .fromNow());
            DocumentHandle failedSource = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    failedSourceId,
                                    sourceYaml(
                                            failedSourceId,
                                            failedSourceTimeline))
                            .publicRoot()
                            .fromNow());
            DocumentHandle siblingConsumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    siblingConsumerId,
                                    consumerYaml(
                                            siblingConsumerId,
                                            siblingConsumerTimeline))
                            .publicRoot()
                            .fromNow());
            DocumentHandle siblingSource = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    siblingSourceId,
                                    sourceYaml(
                                            siblingSourceId,
                                            siblingSourceTimeline))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue failedEpochZero = failedSource.history()
                    .get(0).after();
            ExactBlueValue siblingEpochZero = siblingSource.history()
                    .get(0).after();
            increment(coordination, failedSource, failedSourceFeed);
            increment(coordination, failedSource, failedSourceFeed);
            increment(coordination, siblingSource, siblingSourceFeed);

            EntryHandle failedAttachment = attach(
                    coordination,
                    failedConsumer,
                    failedConsumerFeed,
                    failedEpochZero);
            EntryHandle siblingAttachment = attach(
                    coordination,
                    siblingConsumer,
                    siblingConsumerFeed,
                    siblingEpochZero);
            DrainResult attachments = coordination.processing().drain(
                    new DrainBudget(2L, 2L));
            assertEquals(EntryDisposition.APPLIED,
                    attachments.entry(failedAttachment).disposition());
            assertEquals(EntryDisposition.APPLIED,
                    attachments.entry(siblingAttachment).disposition());
            assertTrue(attachments.managedEpochApplications().isEmpty());

            DefaultCoordinationEngine engine = (DefaultCoordinationEngine)
                    coordination.advanced().rawEngine();
            CatchUpPlanStore plans = engine.documents()
                    .catchUpPlansSnapshot();
            ManagedOccurrenceCatchUpPlan failedPlan = plans
                    .plansForConsumer(failedConsumerId).plans().get(0);
            ManagedOccurrenceCatchUpPlan siblingPlan = plans
                    .plansForConsumer(siblingConsumerId).plans().get(0);
            ManagedEpochApplicationWork failedWork = plans
                    .pendingWorkForPlan(failedPlan.planIdentity())
                    .work();
            ManagedEpochApplicationWork siblingWork = plans
                    .pendingWorkForPlan(siblingPlan.planIdentity())
                    .work();
            assertEquals(failedWork.workIdentity(), engine.documents()
                    .nextCatchUpWork().orElseThrow().workIdentity(),
                    "the corrupted lane must be selected before its sibling");
            return new Scenario(
                    coordination,
                    engine,
                    failedConsumer,
                    failedSource,
                    siblingConsumer,
                    siblingSource,
                    failedWork,
                    siblingWork);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static void installFault(Fault fault, Scenario scenario) {
        InMemoryDocumentStore documents = scenario.engine().documents();
        ManagedEpochApplicationWork work = scenario.failedWork();
        InMemoryDocumentStore.ManagedEpochEvidence evidence = documents
                .managedEpochEvidence(
                        work.sourceDocumentId(), work.sourceEpoch());
        ManagedEpochReceipt receipt = evidence.receipt();
        ManagedDocumentTransitionReceipt transition =
                evidence.transitionReceipt();
        ManagedEpochReceipt replacementReceipt = receipt;
        ManagedDocumentTransitionReceipt replacementTransition = transition;
        switch (fault) {
            case MISSING_EPOCH -> {
                replacementReceipt = null;
                replacementTransition = null;
            }
            case MISSING_TRANSITION -> replacementTransition = null;
            case WRONG_BEFORE -> replacementTransition = transition(
                    transition,
                    transition.sourceInvocationIdentity(),
                    transition.transitionOrdinal(),
                    transition.documentId(),
                    transition.afterBlueId(),
                    transition.afterBlueId(),
                    transition.emittedRootEvents());
            case WRONG_AFTER -> replacementTransition = transition(
                    transition,
                    transition.sourceInvocationIdentity(),
                    transition.transitionOrdinal(),
                    transition.documentId(),
                    transition.beforeBlueId(),
                    transition.beforeBlueId(),
                    transition.emittedRootEvents());
            case WRONG_SOURCE -> {
                blue.language.processor.closure.DocumentId wrongDocument =
                        new blue.language.processor.closure.DocumentId(
                                work.sourceDocumentId().value() + "-wrong");
                ArrayList<ManagedRootEventOccurrence> wrongEvents =
                        new ArrayList<>();
                for (ManagedRootEventOccurrence event
                        : transition.emittedRootEvents()) {
                    wrongEvents.add(new ManagedRootEventOccurrence(
                            event.ordinal(),
                            event.occurrenceOrdinal(),
                            wrongDocument,
                            event.occurrenceIdentity(),
                            IntegrationEventEvidence.verify(
                                    event.exactEvent(), event.eventBlueId()),
                            event.publicAtSource()));
                }
                replacementTransition = transition(
                        transition,
                        transition.sourceInvocationIdentity(),
                        transition.transitionOrdinal(),
                        wrongDocument,
                        transition.beforeBlueId(),
                        transition.afterBlueId(),
                        wrongEvents);
            }
            case WRONG_EVENT -> {
                ManagedDocumentTransitionReceipt later = documents
                        .managedEpochEvidence(work.sourceDocumentId(), 2L)
                        .transitionReceipt();
                replacementTransition = transition(
                        transition,
                        later.sourceInvocationIdentity(),
                        transition.transitionOrdinal(),
                        transition.documentId(),
                        transition.beforeBlueId(),
                        transition.afterBlueId(),
                        later.emittedRootEvents());
            }
            case WRONG_RECEIPT_IDENTITY -> {
                String companion = receipt.commitCompanionIdentity().equals(
                        ALTERNATE_COMPANION_IDENTITY)
                        ? "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        : ALTERNATE_COMPANION_IDENTITY;
                replacementReceipt = ManagedEpochReceipt.identified(
                        receipt.documentId(),
                        receipt.epoch(),
                        receipt.kind(),
                        receipt.beforeBlueId().orElse(null),
                        receipt.afterDocument(),
                        receipt.originalCauseIdentity(),
                        receipt.sourceEntry().orElse(null),
                        receipt.sourceOrder().orElse(null),
                        receipt.contractsTransitionReceiptIdentity(),
                        companion,
                        receipt.emittedEvents(),
                        receipt.processingGas());
            }
        }
        documents.replaceManagedEpochEvidenceForTesting(
                work.sourceDocumentId(),
                work.sourceEpoch(),
                replacementReceipt,
                replacementTransition);
    }

    private static ManagedDocumentTransitionReceipt transition(
            ManagedDocumentTransitionReceipt template,
            String invocationIdentity,
            long transitionOrdinal,
            blue.language.processor.closure.DocumentId documentId,
            String beforeBlueId,
            String afterBlueId,
            List<ManagedRootEventOccurrence> events) {
        try {
            return (ManagedDocumentTransitionReceipt)
                    IDENTIFY_TRANSITION.invoke(
                            null,
                            invocationIdentity,
                            transitionOrdinal,
                            documentId,
                            template.originalCauseIdentity(),
                            beforeBlueId,
                            afterBlueId,
                            events,
                            template.admittedGas());
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    private static Method identifyTransition() {
        try {
            Method method = ManagedDocumentTransitionReceipt.class
                    .getDeclaredMethod(
                            "identified",
                            String.class,
                            long.class,
                            blue.language.processor.closure.DocumentId.class,
                            String.class,
                            String.class,
                            String.class,
                            List.class,
                            long.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void increment(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline) {
        coordination.operations()
                .on(source)
                .from(timeline)
                .call("increment")
                .through("ownerChannel")
                .request(request -> { })
                .execute();
    }

    private static EntryHandle attach(
            BlueCoordination coordination,
            DocumentHandle consumer,
            TimelineHandle timeline,
            ExactBlueValue child) {
        return coordination.operations()
                .on(consumer)
                .from(timeline)
                .call("attach")
                .through("ownerChannel")
                .requestYaml("child:\n  blueId: " + child.blueId())
                .submit();
    }

    private static long counter(
            DefaultCoordinationEngine engine, String name) {
        return engine.metricsSnapshot().counters().getOrDefault(name, 0L);
    }

    private static void assertSdkWorkEquals(
            ManagedEpochApplicationWork expected,
            blue.coordination.sdk.ManagedEpochApplicationWork actual) {
        assertEquals(expected.workIdentity(), actual.workIdentity());
        assertEquals(expected.planIdentity(), actual.planIdentity());
        assertEquals(expected.barrierIdentity(), actual.barrierIdentity());
        assertEquals(expected.sourceReceiptIdentity(),
                actual.sourceReceiptIdentity());
        assertEquals(expected.sourceDocumentId(), actual.sourceDocumentId());
        assertEquals(expected.sourceEpoch(), actual.sourceEpoch());
        assertEquals(expected.consumerDocumentId(),
                actual.consumerDocumentId());
        assertEquals(expected.targetOccurrenceIdentity(),
                actual.targetOccurrenceIdentity());
        assertEquals(expected.targetPath(), actual.targetPath());
        assertEquals(expected.activationGeneration(),
                actual.activationGeneration());
        assertEquals(expected.expectedConsumerCommittedEpoch(),
                actual.expectedConsumerCommittedEpoch());
        assertEquals(expected.expectedConsumerCommittedBlueId(),
                actual.expectedConsumerCommittedBlueId());
        assertEquals(expected.expectedGraphGeneration(),
                actual.expectedGraphGeneration());
    }

    private static String consumerYaml(
            DocumentId documentId, String timelineId) {
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
            DocumentId documentId, String timelineId) {
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

    private enum Fault {
        MISSING_EPOCH(
                ManagedCatchUpStatus.WAITING_FOR_HISTORY,
                ManagedCatchUpBarrierStatus.WAITING_FOR_HISTORY,
                ManagedEpochEvidenceException.SOURCE_EPOCH_MISSING),
        MISSING_TRANSITION(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.TRANSITION_RECEIPT_MISSING),
        WRONG_BEFORE(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.BEFORE_BLUE_ID_MISMATCH),
        WRONG_AFTER(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.AFTER_BLUE_ID_MISMATCH),
        WRONG_SOURCE(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.SOURCE_DOCUMENT_MISMATCH),
        WRONG_EVENT(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.EVENT_OCCURRENCE_MISMATCH),
        WRONG_RECEIPT_IDENTITY(
                ManagedCatchUpStatus.BLOCKED,
                ManagedCatchUpBarrierStatus.BLOCKED,
                ManagedEpochEvidenceException.RECEIPT_IDENTITY_MISMATCH);

        private final ManagedCatchUpStatus status;
        private final ManagedCatchUpBarrierStatus barrierStatus;
        private final String code;

        Fault(
                ManagedCatchUpStatus status,
                ManagedCatchUpBarrierStatus barrierStatus,
                String code) {
            this.status = status;
            this.barrierStatus = barrierStatus;
            this.code = code;
        }

        ManagedCatchUpStatus status() {
            return status;
        }

        ManagedCatchUpBarrierStatus barrierStatus() {
            return barrierStatus;
        }

        String code() {
            return code;
        }
    }

    private record SessionSnapshot(
            long committedEpoch,
            String committedBlueId,
            long readyEpoch,
            String readyBlueId) {
        static SessionSnapshot capture(DocumentSession session) {
            return new SessionSnapshot(
                    session.epoch(),
                    session.currentRevision().after().blueId(),
                    session.readyEpoch(),
                    session.readyRevision().after().blueId());
        }
    }

    private record Scenario(
            BlueCoordination coordination,
            DefaultCoordinationEngine engine,
            DocumentHandle failedConsumer,
            DocumentHandle failedSource,
            DocumentHandle siblingConsumer,
            DocumentHandle siblingSource,
            ManagedEpochApplicationWork failedWork,
            ManagedEpochApplicationWork siblingWork)
            implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }
}
