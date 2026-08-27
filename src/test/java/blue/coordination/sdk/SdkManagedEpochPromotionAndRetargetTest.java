package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK acceptance for promotion and occurrence-generation replacement. */
final class SdkManagedEpochPromotionAndRetargetTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId PROMOTION_ANCHOR = DocumentId.of(
            "sdk-promotion-anchor");
    private static final DocumentId PROMOTED_SOURCE = DocumentId.of(
            "sdk-promotion-source");
    private static final DocumentId PROMOTION_CONSUMER = DocumentId.of(
            "sdk-promotion-consumer");
    private static final String PROMOTED_TIMELINE = "sdk/promotion/source";
    private static final String PROMOTION_CONSUMER_TIMELINE =
            "sdk/promotion/consumer";

    private static final DocumentId REPLACEMENT_CONSUMER = DocumentId.of(
            "sdk-replacement-consumer");
    private static final DocumentId REPLACEMENT_B = DocumentId.of(
            "sdk-replacement-b");
    private static final DocumentId REPLACEMENT_C = DocumentId.of(
            "sdk-replacement-c");
    private static final String REPLACEMENT_CONSUMER_TIMELINE =
            "sdk/replacement/consumer";
    private static final String REPLACEMENT_B_TIMELINE =
            "sdk/replacement/b";
    private static final String REPLACEMENT_C_TIMELINE =
            "sdk/replacement/c";

    @Test
    void promotionPreservesNonPublicHistoryAndAuthoredInitialCatchUp() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle sourceTimeline = coordination.timelines().register(
                    PROMOTED_TIMELINE, ACTOR);
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(PROMOTION_CONSUMER_TIMELINE, ACTOR);
            ExactBlueValue authoredSource = coordination.values().yaml(
                    promotionSourceYaml());
            ClosureHandle admitted = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document(
                                    "anchor",
                                    PROMOTION_ANCHOR,
                                    promotionAnchorYaml())
                            .document(
                                    "source",
                                    PROMOTED_SOURCE,
                                    promotionSourceYaml())
                            .bindOccurrence(
                                    "anchor", "/source", "source")
                            .publicRoot("anchor")
                            .fromNow()
                            .build());
            assertEquals(Set.of("anchor"), admitted.publicRootAliases());
            DocumentHandle source = admitted.document("source");
            assertApplied(increment(
                    coordination, source, sourceTimeline).execute());
            String stateBeforePromotion = source.snapshot().blueId();
            long epochBeforePromotion = source.snapshot().epoch();
            List<String> historyBeforePromotion = historyEvidence(source);
            List<String> receiptsBeforePromotion = receiptIdentities(
                    coordination, PROMOTED_SOURCE);
            CoordinationMetrics metricsBeforePromotion = coordination
                    .advanced().rawEngine().metrics();

            // when
            DocumentHandle promoted = coordination.documents()
                    .promotePublicRoot(PROMOTED_SOURCE);

            assertEquals(PROMOTED_SOURCE, promoted.id());
            assertEquals(stateBeforePromotion, source.snapshot().blueId());
            assertEquals(epochBeforePromotion, source.snapshot().epoch());
            assertEquals(historyBeforePromotion, historyEvidence(source));
            assertEquals(receiptsBeforePromotion, receiptIdentities(
                    coordination, PROMOTED_SOURCE));
            CoordinationMetrics metricsAfterPromotion = coordination
                    .advanced().rawEngine().metrics();
            assertEquals(metricsBeforePromotion.journalEntryCount(),
                    metricsAfterPromotion.journalEntryCount());
            assertEquals(metricsBeforePromotion.wholeObjectCount(),
                    metricsAfterPromotion.wholeObjectCount());
            assertEquals(counter(
                            metricsBeforePromotion,
                            CoordinationMetrics.Counter
                                    .DOCUMENT_INITIALIZATIONS),
                    counter(
                            metricsAfterPromotion,
                            CoordinationMetrics.Counter
                                    .DOCUMENT_INITIALIZATIONS));
            assertEquals(counter(
                            metricsBeforePromotion,
                            CoordinationMetrics.Counter
                                    .EXTERNAL_PROCESS_CALLS),
                    counter(
                            metricsAfterPromotion,
                            CoordinationMetrics.Counter
                                    .EXTERNAL_PROCESS_CALLS));

            List<String> sourceHistory = historyEvidence(source);
            List<String> sourceReceipts = receiptIdentities(
                    coordination, PROMOTED_SOURCE);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    PROMOTION_CONSUMER,
                                    promotionConsumerYaml())
                            .publicRoot()
                            .fromNow());

            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", authoredSource))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition());
            assertTrue(attached.managedEpochApplications().isEmpty());
            CoordinationMetrics beforeCatchUp = coordination.advanced()
                    .rawEngine().metrics();
            DrainResult caughtUp = coordination.processing().drain();
            assertEquals(2, caughtUp.managedEpochApplications().size());
            ManagedOccurrenceCatchUpPlan plan = coordination.advanced()
                    .auditManagedCatchUpPlans(PROMOTION_CONSUMER)
                    .get(0);
            assertEquals(PROMOTED_SOURCE, plan.sourceDocumentId());
            assertEquals(-1L, plan.admittedSourceEpoch());
            assertEquals(2L, plan.nextSourceEpoch());
            assertEquals(1L, plan.requiredThroughSourceEpoch());
            assertEquals(ManagedCatchUpStatus.COMPLETE, plan.status());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(PROMOTION_CONSUMER)
                    .orElseThrow().ready());
            assertEquals(1L, consumer.snapshot().longAt(
                    "/observedInitializations"));
            assertEquals(1L, consumer.snapshot().longAt(
                    "/observedChanges"));
            assertEquals(sourceHistory, historyEvidence(source));
            assertEquals(sourceReceipts, receiptIdentities(
                    coordination, PROMOTED_SOURCE));
            CoordinationMetrics afterCatchUp = coordination.advanced()
                    .rawEngine().metrics();

            // then
            assertEquals(0L, counterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(0L, counterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS));
            assertEquals(2L, counterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.EMBEDDED_EPOCH_PROCESS_CALLS));
        }
    }

    @Test
    void detachReaddAndRetargetOwnDistinctPlansAndCursors() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(REPLACEMENT_CONSUMER_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    REPLACEMENT_B_TIMELINE, ACTOR);
            TimelineHandle cTimeline = coordination.timelines().register(
                    REPLACEMENT_C_TIMELINE, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    REPLACEMENT_CONSUMER,
                                    replacementConsumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    REPLACEMENT_B,
                                    replacementSourceYaml(
                                            REPLACEMENT_B,
                                            REPLACEMENT_B_TIMELINE))
                            .publicRoot()
                            .fromNow());
            DocumentHandle c = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    REPLACEMENT_C,
                                    replacementSourceYaml(
                                            REPLACEMENT_C,
                                            REPLACEMENT_C_TIMELINE))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue bZero = b.history().get(0).after();
            ExactBlueValue cZero = c.history().get(0).after();
            assertApplied(sourceOperation(
                    coordination,
                    b,
                    bTimeline,
                    "signalDetach").execute());
            ExactBlueValue bOne = b.history().get(1).after();
            assertApplied(sourceOperation(
                    coordination, b, bTimeline, "change").execute());
            assertApplied(sourceOperation(
                    coordination, c, cTimeline, "change").execute());
            List<String> bHistory = historyEvidence(b);
            List<String> cHistory = historyEvidence(c);

            // when
            EntryHandle firstAttachment = setChild(
                    coordination,
                    consumer,
                    consumerTimeline,
                    bZero).submit();
            DrainResult firstAdmitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    firstAdmitted.entry(firstAttachment).disposition());
            ManagedOccurrenceCatchUpPlan firstPlan = onlyActivePlan(
                    coordination);
            assertEquals(REPLACEMENT_B, firstPlan.sourceDocumentId());
            assertEquals(1L, firstPlan.activationGeneration());
            assertEquals(0L, firstPlan.admittedSourceEpoch());
            assertEquals(1L, firstPlan.nextSourceEpoch());
            assertEquals(2L, firstPlan.requiredThroughSourceEpoch());
            assertCatchingUp(coordination);

            DrainResult detached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(receiptIdentity(
                            coordination, REPLACEMENT_B, 1L)),
                    applicationSourceReceipts(detached),
                    () -> "attempts="
                            + detached.managedEpochApplicationAttempts()
                            + ", plans=" + coordination.advanced()
                                    .auditManagedCatchUpPlans(
                                            REPLACEMENT_CONSUMER));
            ManagedOccurrenceCatchUpPlan cancelled = planByIdentity(
                    coordination, firstPlan.planIdentity());
            assertEquals(ManagedCatchUpStatus
                            .CANCELLED_OCCURRENCE_RETIRED,
                    cancelled.status());
            assertEquals(1L, cancelled.activationGeneration());
            assertEquals(2L, cancelled.nextSourceEpoch());
            assertEquals(2L, cancelled.requiredThroughSourceEpoch());
            String cancelledSnapshotIdentity = cancelled.snapshotIdentity();
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(REPLACEMENT_CONSUMER)
                    .orElseThrow().ready());
            ManagedOccurrenceAudit retired = coordination.advanced()
                    .auditManagedOccurrence(
                            REPLACEMENT_CONSUMER, "/child")
                    .orElseThrow();
            assertEquals(REPLACEMENT_B, retired.targetDocumentId());
            assertEquals(2L, retired.activationGeneration(),
                    "event-owned retirement allocates a fresh inactive "
                            + "successor while the cancelled plan retains "
                            + "generation 1");
            assertFalse(retired.active());
            assertEquals(1L, consumer.snapshot().longAt("/detachCount"));
            assertEquals(0L, consumer.snapshot().longAt(
                    "/observedChanges"));

            EntryHandle readd = setChild(
                    coordination,
                    consumer,
                    consumerTimeline,
                    bOne).submit();
            DrainResult readdAdmitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    readdAdmitted.entry(readd).disposition(),
                    readdAdmitted.entry(readd).diagnostic().toString());
            ManagedOccurrenceCatchUpPlan secondPlan = onlyActivePlan(
                    coordination);
            assertEquals(REPLACEMENT_B, secondPlan.sourceDocumentId());
            assertEquals(2L, secondPlan.activationGeneration());
            assertEquals(1L, secondPlan.admittedSourceEpoch());
            assertEquals(2L, secondPlan.nextSourceEpoch());
            assertEquals(2L, secondPlan.requiredThroughSourceEpoch());
            assertNotEquals(firstPlan.planIdentity(),
                    secondPlan.planIdentity());
            assertNotEquals(firstPlan.barrierIdentity(),
                    secondPlan.barrierIdentity());
            assertNotEquals(firstPlan.targetOccurrenceIdentity(),
                    secondPlan.targetOccurrenceIdentity());
            assertEquals(cancelledSnapshotIdentity,
                    planByIdentity(coordination, firstPlan.planIdentity())
                            .snapshotIdentity());
            assertEquals(ManagedCatchUpStatus
                            .CANCELLED_OCCURRENCE_RETIRED,
                    planByIdentity(coordination, firstPlan.planIdentity())
                            .status());
            assertCatchingUp(coordination);

            DrainResult secondCatchUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(receiptIdentity(
                            coordination, REPLACEMENT_B, 2L)),
                    applicationSourceReceipts(secondCatchUp));
            ManagedOccurrenceCatchUpPlan completedB = planByIdentity(
                    coordination, secondPlan.planIdentity());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedB.status());
            assertEquals(3L, completedB.nextSourceEpoch());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(REPLACEMENT_CONSUMER)
                    .orElseThrow().ready());
            ManagedOccurrenceAudit activeB = coordination.advanced()
                    .auditManagedOccurrence(
                            REPLACEMENT_CONSUMER, "/child")
                    .orElseThrow();
            assertEquals(REPLACEMENT_B, activeB.targetDocumentId());
            assertEquals(2L, activeB.activationGeneration());
            assertTrue(activeB.active());
            assertEquals(b.snapshot().blueId(), consumer.snapshot()
                    .valueAt("/child").blueId());
            assertEquals(1L, consumer.snapshot().longAt(
                    "/observedChanges"));
            String completedBSnapshotIdentity = completedB
                    .snapshotIdentity();

            EntryResult retiredB = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("clearChild")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            assertApplied(retiredB);
            ManagedOccurrenceAudit retiredBeforeRetarget = coordination
                    .advanced()
                    .auditManagedOccurrence(
                            REPLACEMENT_CONSUMER, "/child")
                    .orElseThrow();
            assertEquals(REPLACEMENT_B,
                    retiredBeforeRetarget.targetDocumentId());
            assertEquals(3L,
                    retiredBeforeRetarget.activationGeneration());
            assertFalse(retiredBeforeRetarget.active());

            EntryHandle retarget = setChild(
                    coordination,
                    consumer,
                    consumerTimeline,
                    cZero)
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            REPLACEMENT_C,
                            0L,
                            cZero.blueId(),
                            "/child"))
                    .submit();
            DrainResult retargetAdmitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    retargetAdmitted.entry(retarget).disposition(),
                    retargetAdmitted.entry(retarget).diagnostic().toString());
            assertEquals(completedBSnapshotIdentity,
                    planByIdentity(coordination, secondPlan.planIdentity())
                            .snapshotIdentity(),
                    "retargeting must not migrate or rewrite B's cursor");
            ManagedOccurrenceCatchUpPlan cPlan = onlyActivePlan(
                    coordination);
            assertEquals(REPLACEMENT_C, cPlan.sourceDocumentId());
            assertEquals(3L, cPlan.activationGeneration());
            assertEquals(0L, cPlan.admittedSourceEpoch());
            assertEquals(1L, cPlan.nextSourceEpoch());
            assertEquals(1L, cPlan.requiredThroughSourceEpoch());
            assertNotEquals(secondPlan.planIdentity(), cPlan.planIdentity());
            assertNotEquals(secondPlan.barrierIdentity(),
                    cPlan.barrierIdentity());
            assertNotEquals(secondPlan.targetOccurrenceIdentity(),
                    cPlan.targetOccurrenceIdentity());
            ManagedOccurrenceAudit pendingC = coordination.advanced()
                    .auditManagedOccurrence(
                            REPLACEMENT_CONSUMER, "/child")
                    .orElseThrow();
            assertEquals(REPLACEMENT_C, pendingC.targetDocumentId());
            assertEquals(3L, pendingC.activationGeneration());
            assertFalse(pendingC.active());
            assertCatchingUp(coordination);

            DrainResult cCatchUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(receiptIdentity(
                            coordination, REPLACEMENT_C, 1L)),
                    applicationSourceReceipts(cCatchUp));
            ManagedOccurrenceCatchUpPlan completedC = planByIdentity(
                    coordination, cPlan.planIdentity());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    completedC.status());
            assertEquals(2L, completedC.nextSourceEpoch());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(REPLACEMENT_CONSUMER)
                    .orElseThrow().ready());
            ManagedOccurrenceAudit activeC = coordination.advanced()
                    .auditManagedOccurrence(
                            REPLACEMENT_CONSUMER, "/child")
                    .orElseThrow();

            // then
            assertEquals(REPLACEMENT_C, activeC.targetDocumentId());
            assertEquals(3L, activeC.activationGeneration());
            assertTrue(activeC.active());
            assertEquals(c.snapshot().blueId(), consumer.snapshot()
                    .valueAt("/child").blueId());
            assertEquals(2L, consumer.snapshot().longAt(
                    "/observedChanges"));
            assertEquals(1L, consumer.snapshot().longAt("/detachCount"));
            assertEquals(bHistory, historyEvidence(b));
            assertEquals(cHistory, historyEvidence(c));
        }
    }

    private static OperationCall increment(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline) {
        return sourceOperation(coordination, source, timeline, "increment");
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

    private static OperationCall setChild(
            BlueCoordination coordination,
            DocumentHandle consumer,
            TimelineHandle timeline,
            ExactBlueValue child) {
        return coordination.operations()
                .on(consumer)
                .from(timeline)
                .call("setChild")
                .through("ownerChannel")
                .request(request -> request.exact("child", child));
    }

    private static ManagedOccurrenceCatchUpPlan onlyActivePlan(
            BlueCoordination coordination) {
        List<ManagedOccurrenceCatchUpPlan> active = coordination.advanced()
                .auditManagedCatchUpPlans(REPLACEMENT_CONSUMER).stream()
                .filter(plan -> !plan.status().terminal())
                .toList();
        assertEquals(1, active.size());
        return active.get(0);
    }

    private static ManagedOccurrenceCatchUpPlan planByIdentity(
            BlueCoordination coordination,
            String identity) {
        return coordination.advanced().auditManagedCatchUpPlan(identity)
                .orElseThrow();
    }

    private static void assertCatchingUp(BlueCoordination coordination) {
        assertEquals(SessionStatus.CATCHING_UP,
                coordination.advanced()
                        .auditManagedDocumentReadiness(
                                REPLACEMENT_CONSUMER)
                        .orElseThrow().status());
    }

    private static List<String> applicationSourceReceipts(
            DrainResult result) {
        return result.managedEpochApplications().stream()
                .map(ManagedEpochApplicationReceipt::sourceReceiptIdentity)
                .toList();
    }

    private static String receiptIdentity(
            BlueCoordination coordination,
            DocumentId source,
            long epoch) {
        return coordination.advanced().auditManagedEpoch(source, epoch)
                .orElseThrow().receiptIdentity();
    }

    private static List<String> receiptIdentities(
            BlueCoordination coordination,
            DocumentId source) {
        return coordination.advanced().auditManagedEpochs(source).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static List<String> historyEvidence(DocumentHandle document) {
        return document.history().stream()
                .map(revision -> revision.epoch()
                        + ":" + revision.kind()
                        + ":" + revision.before()
                                .map(ExactBlueValue::blueId)
                                .orElse("-")
                        + ":" + revision.after().blueId()
                        + ":" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("-"))
                .toList();
    }

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static long counter(
            CoordinationMetrics metrics,
            CoordinationMetrics.Counter counter) {
        return metrics.counter(counter);
    }

    private static long counterDelta(
            CoordinationMetrics before,
            CoordinationMetrics after,
            CoordinationMetrics.Counter counter) {
        return Math.subtractExact(counter(after, counter),
                counter(before, counter));
    }

    private static String promotionAnchorYaml() {
        return """
                documentId: %s
                source: {}
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /source
                """.formatted(PROMOTION_ANCHOR.value());
    }

    private static String promotionSourceYaml() {
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
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Promotion/Source Initialized
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
                              kind: Promotion/Source Changed
                          - $return: true
                """.formatted(
                PROMOTED_SOURCE.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                PROMOTED_TIMELINE,
                ACTOR);
    }

    private static String promotionConsumerYaml() {
        return """
                documentId: %s
                children: {}
                observedInitializations: 0
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  fromInitialized:
                    type: Embedded Node Channel
                    sourcePath: /children/source
                    event:
                      type: Coordination/Event
                      kind: Promotion/Source Initialized
                  onInitialized:
                    type: Coordination/Sequential Workflow
                    channel: fromInitialized
                    event:
                      type: Coordination/Event
                      kind: Promotion/Source Initialized
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedInitializations
                              val: {$add: [{$document: /observedInitializations}, 1]}
                          - $return: true
                  fromChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/source
                    event:
                      type: Coordination/Event
                      kind: Promotion/Source Changed
                  onChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromChanged
                    event:
                      type: Coordination/Event
                      kind: Promotion/Source Changed
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
                              path: /children/source
                              val: {$binding: event/message/request/child}
                          - $return: true
                """.formatted(
                PROMOTION_CONSUMER.value(),
                PROMOTION_CONSUMER_TIMELINE,
                ACTOR);
    }

    private static String replacementConsumerYaml() {
        return """
                documentId: %s
                observedChanges: 0
                detachCount: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromDetach:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: Replacement/Detach
                  onDetach:
                    type: Coordination/Sequential Workflow
                    channel: fromDetach
                    event:
                      type: Coordination/Event
                      kind: Replacement/Detach
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /child}
                          - $appendChange:
                              op: replace
                              path: /detachCount
                              val: {$add: [{$document: /detachCount}, 1]}
                          - $return: true
                  fromChanged:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: Replacement/Changed
                  onChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromChanged
                    event:
                      type: Coordination/Event
                      kind: Replacement/Changed
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
                  setChild:
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
                  clearChild:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /child}
                          - $return: true
                """.formatted(
                REPLACEMENT_CONSUMER.value(),
                REPLACEMENT_CONSUMER_TIMELINE,
                ACTOR);
    }

    private static String replacementSourceYaml(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                initializationCount: 0
                changes: 0
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
                  signalDetach:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /changes
                              val: {$add: [{$document: /changes}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Replacement/Detach
                          - $return: true
                  change:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /changes
                              val: {$add: [{$document: /changes}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Replacement/Changed
                          - $return: true
                """.formatted(
                documentId.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                timelineId,
                ACTOR);
    }
}
