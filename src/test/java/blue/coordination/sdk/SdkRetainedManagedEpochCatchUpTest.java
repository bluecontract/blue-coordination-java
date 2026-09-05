package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end SDK acceptance for retained managed source epochs. */
final class SdkRetainedManagedEpochCatchUpTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId A = DocumentId.of(
            "sdk-retained-catch-up-a");
    private static final DocumentId B = DocumentId.of(
            "sdk-retained-catch-up-b");
    private static final DocumentId RETAINED_CONSUMER = DocumentId.of(
            "sdk-retained-catch-up-retained-consumer");
    private static final DocumentId CURRENT_CONSUMER = DocumentId.of(
            "sdk-retained-catch-up-current-consumer");
    private static final DocumentId DUPLICATE_CONSUMER = DocumentId.of(
            "sdk-retained-catch-up-duplicate-consumer");
    private static final DocumentId READ_THROUGH_HOST = DocumentId.of(
            "sdk-retained-read-through-host");
    private static final DocumentId READ_THROUGH_SOURCE = DocumentId.of(
            "sdk-retained-read-through-source");
    private static final String A_TIMELINE = "sdk/retained/a";
    private static final String B_TIMELINE = "sdk/retained/b";
    private static final String READ_THROUGH_HOST_TIMELINE =
            "sdk/retained/read-through/host";
    private static final String READ_THROUGH_SOURCE_TIMELINE =
            "sdk/retained/read-through/source";

    @Test
    void authoredInitialInlineValueAppliesInitializationAndLaterEpochs() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            ExactBlueValue authoredSource = coordination.values().yaml(
                    sourceYaml());
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            increment(coordination, b, bTimeline).execute();
            List<String> sourceHistory = b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();

            // when
            EntryResult attached = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", authoredSource))
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, attached.disposition(),
                    attached.diagnostic().toString());
            assertEquals(1L, a.snapshot().longAt(
                    "/observedInitializations"));
            assertEquals(1L, a.snapshot().longAt("/observedChanges"));
            assertEquals(sourceHistory, b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList(), "source history must not be reprocessed");
            assertEquals(List.of(0L, 1L), coordination.advanced()
                    .auditManagedEpochs(B).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            ManagedOccurrenceCatchUpPlan completed = coordination.advanced()
                    .auditManagedCatchUpPlans(A).get(0);
            assertEquals(-1L, completed.admittedSourceEpoch());
            assertEquals(2L, completed.nextSourceEpoch());
            assertTrue(completed.status().terminal());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
        }
    }

    @Test
    void initializedEpochZeroCatchesUpWithoutReprocessingTheSource() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = b.history().get(0).after();
            increment(coordination, b, bTimeline).execute();
            increment(coordination, b, bTimeline).execute();
            DocumentSnapshot readyBeforeAttachment = a.snapshot();
            List<String> sourceReceiptIdentities = coordination.advanced()
                    .auditManagedEpochs(B).stream()
                    .map(ManagedEpochReceipt::receiptIdentity)
                    .toList();
            assertEquals(List.of(0L, 1L, 2L), coordination.advanced()
                    .auditManagedEpochs(B).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            assertEquals(3, b.history().size());

            // when
            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .requestYaml("child:\n  blueId: " + epochZero.blueId())
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.paused());
            assertTrue(attached.managedEpochApplications().isEmpty());

            ManagedDocumentReadiness catchingUp = coordination.advanced()
                    .auditManagedDocumentReadiness(A).orElseThrow();
            assertEquals(SessionStatus.CATCHING_UP, catchingUp.status());
            assertFalse(catchingUp.ready());
            assertEquals(0L, catchingUp.readyEpoch().orElseThrow());
            assertEquals(1L, catchingUp.committedEpoch());
            assertEquals(0L, a.snapshot().epoch(),
                    "normal reads stay on the last READY head");
            assertEquals(readyBeforeAttachment.blueId(),
                    a.snapshot().blueId());
            assertEquals(readyBeforeAttachment.publicEvents(),
                    a.snapshot().publicEvents(),
                    "normal reads must not expose committed-head events");
            assertEquals(1, a.history().size(),
                    "normal history must stop at the READY epoch");
            blue.coordination.api.DocumentSnapshot committed = coordination
                    .advanced().auditDocument(A);
            assertEquals(1L, committed.epoch());
            assertEquals(epochZero.blueId(),
                    committed.valueAt("/children/b").blueId());
            List<blue.coordination.api.DocumentRevision> committedHistory =
                    coordination.advanced().rawEngine().history(A);
            assertEquals(2, committedHistory.size());
            assertEquals(1, committedHistory.get(1).emittedEvents().size(),
                    "advanced audit retains the committed-head event");
            OperationCall selectedById = coordination.operations().on(A);
            assertEquals(readyBeforeAttachment.blueId(),
                    selectedById.target().exact().blueId(),
                    "normal id selection must bind the READY head");
            assertEquals(readyBeforeAttachment.epoch(),
                    selectedById.target().epochAtSelection());
            List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                    .auditManagedCatchUpPlans(A);
            assertEquals(1, plans.size());
            assertEquals(0L, plans.get(0).admittedSourceEpoch());
            assertEquals(1L, plans.get(0).nextSourceEpoch());
            assertEquals(2L, plans.get(0).requiredThroughSourceEpoch());

            DrainResult first = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(1, first.managedEpochApplications().size());
            assertTrue(first.paused());
            assertEquals(2L, coordination.advanced()
                    .auditManagedCatchUpPlans(A).get(0).nextSourceEpoch());
            assertEquals(readyBeforeAttachment.blueId(),
                    a.snapshot().blueId());
            assertEquals(readyBeforeAttachment.publicEvents(),
                    a.snapshot().publicEvents());
            assertEquals(1, a.history().size(),
                    "partially applied catch-up remains behind the READY head");
            assertEquals(2L,
                    coordination.advanced().auditDocument(A).epoch(),
                    "advanced audit follows the partially caught-up head");

            DrainResult second = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(1, second.managedEpochApplications().size());
            assertTrue(second.quiescent());
            assertEquals(2L, a.snapshot().longAt("/observedChanges"));
            assertEquals(0L, a.snapshot().longAt(
                    "/observedInitializations"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(coordination.advanced().rawEngine().history(A).size(),
                    a.history().size(),
                    "promotion makes the committed history application-visible");
            assertEquals(sourceReceiptIdentities, coordination.advanced()
                    .auditManagedEpochs(B).stream()
                    .map(ManagedEpochReceipt::receiptIdentity)
                    .toList());
            assertEquals(3, b.history().size(),
                    "catch-up must not append a source revision");
        }
    }

    @Test
    void retainedEpochAppliesOnlyItsSuffixAndCurrentStateCreatesNoPlan() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle retainedTimeline = coordination.timelines()
                    .register("sdk/retained/retained-consumer", ACTOR);
            TimelineHandle currentTimeline = coordination.timelines()
                    .register("sdk/retained/current-consumer", ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle retainedConsumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    RETAINED_CONSUMER,
                                    consumerYaml(
                                            RETAINED_CONSUMER,
                                            "sdk/retained/retained-consumer"))
                            .publicRoot()
                            .fromNow());
            DocumentHandle currentConsumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    CURRENT_CONSUMER,
                                    consumerYaml(
                                            CURRENT_CONSUMER,
                                            "sdk/retained/current-consumer"))
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            increment(coordination, b, bTimeline).execute();
            ExactBlueValue epochOne = b.history().get(1).after();
            increment(coordination, b, bTimeline).execute();
            ExactBlueValue current = b.snapshot().exact();
            List<String> sourceHistory = b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();

            // when
            EntryHandle retainedAttachment = coordination.operations()
                    .on(retainedConsumer)
                    .from(retainedTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", epochOne))
                    .submit();
            DrainResult retainedDrain = coordination.processing().drain();

            assertEquals(EntryDisposition.APPLIED,
                    retainedDrain.entry(retainedAttachment).disposition());
            assertEquals(1, retainedDrain.managedEpochApplications().size());
            assertEquals(3L, retainedDrain.managedEpochApplications().get(0)
                    .resultingSourceCursor());
            ManagedOccurrenceCatchUpPlan retainedPlan = coordination
                    .advanced().auditManagedCatchUpPlans(
                            RETAINED_CONSUMER).get(0);
            assertEquals(1L, retainedPlan.admittedSourceEpoch());
            assertEquals(3L, retainedPlan.nextSourceEpoch());
            assertEquals(1L, retainedConsumer.snapshot().longAt(
                    "/observedChanges"));

            EntryHandle currentAttachment = coordination.operations()
                    .on(currentConsumer)
                    .from(currentTimeline)
                    .call("attach")
                    .through("ownerChannel")
                    .request(request -> request.exact("child", current))
                    .submit();
            DrainResult currentDrain = coordination.processing().drain();

            // then
            assertEquals(EntryDisposition.APPLIED,
                    currentDrain.entry(currentAttachment).disposition());
            assertTrue(currentDrain.managedEpochApplications().isEmpty());
            assertTrue(coordination.advanced().auditManagedCatchUpPlans(
                    CURRENT_CONSUMER).isEmpty());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(CURRENT_CONSUMER)
                    .orElseThrow().ready());
            assertEquals(sourceHistory, b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList(), "neither retained nor current reuse reprocesses B");
        }
    }

    @Test
    void duplicateOccurrencesOwnIndependentCursorsAndShareSourceReceipts() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            String consumerTimelineId = "sdk/retained/duplicate-consumer";
            TimelineHandle consumerTimeline = coordination.timelines()
                    .register(consumerTimelineId, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle consumer = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    DUPLICATE_CONSUMER,
                                    duplicateConsumerYaml(consumerTimelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml())
                            .publicRoot()
                            .fromNow());
            ExactBlueValue epochZero = b.history().get(0).after();
            increment(coordination, b, bTimeline).execute();
            increment(coordination, b, bTimeline).execute();
            List<String> sourceHistory = b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();

            // when
            EntryHandle attachment = coordination.operations()
                    .on(consumer)
                    .from(consumerTimeline)
                    .call("attachBoth")
                    .through("ownerChannel")
                    .request(request -> request
                            .exact("first", epochZero)
                            .exact("second", epochZero))
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    admitted.entry(attachment).disposition());
            assertTrue(admitted.managedEpochApplications().isEmpty());

            DrainResult caughtUp = coordination.processing().drain();

            // then
            assertEquals(4, caughtUp.managedEpochApplications().size());
            List<ManagedOccurrenceCatchUpPlan> plans = coordination.advanced()
                    .auditManagedCatchUpPlans(DUPLICATE_CONSUMER);
            assertEquals(2, plans.size());
            assertEquals(List.of("/children/first", "/children/second"),
                    plans.stream()
                            .map(ManagedOccurrenceCatchUpPlan::targetPath)
                            .sorted()
                            .toList());
            assertEquals(List.of(3L, 3L), plans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::nextSourceEpoch)
                    .sorted()
                    .toList());
            assertEquals(2L, consumer.snapshot().longAt("/firstChanges"));
            assertEquals(2L, consumer.snapshot().longAt("/secondChanges"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(DUPLICATE_CONSUMER)
                    .orElseThrow().ready());
            assertEquals(sourceHistory, b.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList(), "four occurrence applications reuse two receipts");
        }
    }

    @Test
    void retainedReferenceReadsEachExactHistoricalCounterEpoch() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            TimelineHandle hostTimeline = coordination.timelines().register(
                    READ_THROUGH_HOST_TIMELINE, ACTOR);
            TimelineHandle sourceTimeline = coordination.timelines().register(
                    READ_THROUGH_SOURCE_TIMELINE, ACTOR);
            ExactBlueValue authoredSource = coordination.values().yaml(
                    readThroughSourceYaml());
            DocumentHandle host = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    READ_THROUGH_HOST,
                                    readThroughHostYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle source = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    READ_THROUGH_SOURCE,
                                    readThroughSourceYaml())
                            .publicRoot()
                            .fromNow());
            incrementByFour(coordination, source, sourceTimeline).execute();
            incrementByFour(coordination, source, sourceTimeline).execute();
            assertEquals(List.of(0L, 4L, 8L), source.history().stream()
                    .map(revision -> ((Number) revision.after().scalarAt(
                            "/counter")).longValue())
                    .toList());
            List<String> sourceHistory = source.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList();
            List<String> sourceReceipts = coordination.advanced()
                    .auditManagedEpochs(READ_THROUGH_SOURCE).stream()
                    .map(ManagedEpochReceipt::receiptIdentity)
                    .toList();
            List<String> sourceEventOccurrences = coordination.advanced()
                    .auditManagedEpochs(READ_THROUGH_SOURCE).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .map(ManagedEventOccurrence::eventOccurrenceIdentity)
                    .toList();
            String sourceHead = source.snapshot().blueId();
            long sourceEpoch = source.snapshot().epoch();

            // when
            EntryHandle attachment = coordination.operations()
                    .on(host)
                    .from(hostTimeline)
                    .call("attachChild")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", authoredSource))
                    .submit();
            DrainResult admitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    admitted.entry(attachment).disposition(),
                    admitted.entry(attachment).diagnostic().toString());
            assertTrue(admitted.managedEpochApplications().isEmpty());
            ManagedOccurrenceCatchUpPlan pending = coordination.advanced()
                    .auditManagedCatchUpPlans(READ_THROUGH_HOST).get(0);
            assertEquals(READ_THROUGH_HOST, pending.consumerDocumentId());
            assertEquals(READ_THROUGH_SOURCE, pending.sourceDocumentId());
            assertEquals("/child", pending.targetPath());
            assertEquals(-1L, pending.admittedSourceEpoch());
            assertEquals(0L, pending.nextSourceEpoch());
            assertEquals(2L, pending.requiredThroughSourceEpoch());
            assertEquals(ManagedCatchUpStatus.RUNNING, pending.status());
            assertEquals(List.of(pending.planIdentity()), coordination
                    .advanced()
                    .auditManagedCatchUpBarrier(pending.barrierIdentity())
                    .orElseThrow().planIdentities());
            assertEquals(ManagedCatchUpBarrierStatus.OPEN, coordination
                    .advanced()
                    .auditManagedCatchUpBarrier(pending.barrierIdentity())
                    .orElseThrow().status());
            assertEquals(pending.activationGeneration(), coordination
                    .advanced()
                    .auditManagedOccurrence(READ_THROUGH_HOST, "/child")
                    .orElseThrow().activationGeneration());
            assertEquals(READ_THROUGH_SOURCE, coordination.advanced()
                    .auditManagedOccurrence(READ_THROUGH_HOST, "/child")
                    .orElseThrow().targetDocumentId());
            assertEquals(SessionStatus.CATCHING_UP, coordination.advanced()
                    .auditManagedDocumentReadiness(READ_THROUGH_HOST)
                    .orElseThrow().status());
            assertEquals(-1L, auditLongAt(
                    coordination, READ_THROUGH_HOST, "/observedCounter"));

            DrainResult initialized = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(1L), initialized
                    .managedEpochApplications().stream()
                    .map(ManagedEpochApplicationReceipt
                            ::resultingSourceCursor)
                    .toList());
            assertEquals(-1L, auditLongAt(
                    coordination, READ_THROUGH_HOST, "/observedCounter"));
            assertEquals(1L, coordination.advanced()
                    .auditManagedCatchUpPlan(pending.planIdentity())
                    .orElseThrow().nextSourceEpoch());

            DrainResult four = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(List.of(2L), four.managedEpochApplications().stream()
                    .map(ManagedEpochApplicationReceipt
                            ::resultingSourceCursor)
                    .toList());
            assertEquals(4L, auditLongAt(
                    coordination, READ_THROUGH_HOST, "/observedCounter"));
            assertEquals(2L, coordination.advanced()
                    .auditManagedCatchUpPlan(pending.planIdentity())
                    .orElseThrow().nextSourceEpoch());
            assertFalse(coordination.advanced()
                    .auditManagedDocumentReadiness(READ_THROUGH_HOST)
                    .orElseThrow().ready());

            DrainResult eight = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(List.of(3L), eight.managedEpochApplications().stream()
                    .map(ManagedEpochApplicationReceipt
                            ::resultingSourceCursor)
                    .toList());
            List<ManagedEpochApplicationReceipt> applications = List.of(
                    initialized.managedEpochApplications().get(0),
                    four.managedEpochApplications().get(0),
                    eight.managedEpochApplications().get(0));
            assertEquals(List.of(0L, 1L, 2L), applications.stream()
                    .map(receipt -> coordination.advanced()
                            .auditManagedEpochReceipt(
                                    receipt.sourceReceiptIdentity())
                            .orElseThrow().epoch())
                    .toList());
            assertEquals(sourceReceipts, applications.stream()
                    .map(ManagedEpochApplicationReceipt
                            ::sourceReceiptIdentity)
                    .toList());
            assertEquals(List.of(0L, 4L, 8L), applications.stream()
                    .map(receipt -> ((Number) coordination.advanced()
                            .auditManagedEpochReceipt(
                                    receipt.sourceReceiptIdentity())
                            .orElseThrow().afterDocument().scalarAt(
                                    "/counter")).longValue())
                    .toList());
            assertTrue(applications.stream().allMatch(receipt ->
                    receipt.planIdentity().equals(pending.planIdentity())
                            && receipt.consumerDocumentId().equals(
                                    READ_THROUGH_HOST)));
            ManagedOccurrenceCatchUpPlan complete = coordination.advanced()
                    .auditManagedCatchUpPlan(pending.planIdentity())
                    .orElseThrow();
            assertEquals(pending.targetOccurrenceIdentity(),
                    complete.targetOccurrenceIdentity());
            assertEquals(pending.activationGeneration(),
                    complete.activationGeneration());
            assertEquals(3L, complete.nextSourceEpoch());
            assertEquals(ManagedCatchUpStatus.COMPLETE, complete.status());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE, coordination
                    .advanced()
                    .auditManagedCatchUpBarrier(pending.barrierIdentity())
                    .orElseThrow().status());
            ManagedDocumentReadiness ready = coordination.advanced()
                    .auditManagedDocumentReadiness(READ_THROUGH_HOST)
                    .orElseThrow();
            assertTrue(ready.ready());
            assertEquals(ready.committedEpoch(),
                    ready.readyEpoch().orElseThrow());
            assertEquals(8L, host.snapshot().longAt("/observedCounter"));
            Node child = NodePathEditor.getOrNull(
                    host.snapshot().exact().copyNode(), "/child");
            assertTrue(child.isReferenceOnly(),
                    "the managed occurrence must remain a compact reference");
            assertEquals(sourceHead, child.getBlueId());
            assertEquals(sourceHistory, source.history().stream()
                    .map(revision -> revision.after().blueId())
                    .toList(), "catch-up must not append source revisions");
            assertEquals(sourceReceipts, coordination.advanced()
                    .auditManagedEpochs(READ_THROUGH_SOURCE).stream()
                    .map(ManagedEpochReceipt::receiptIdentity)
                    .toList(), "catch-up must reuse immutable source receipts");
            assertEquals(sourceEventOccurrences, coordination.advanced()
                    .auditManagedEpochs(READ_THROUGH_SOURCE).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .map(ManagedEventOccurrence::eventOccurrenceIdentity)
                    .toList(), "catch-up must preserve event occurrences");
            assertEquals(sourceEpoch, source.snapshot().epoch());
            assertEquals(sourceHead, source.snapshot().blueId());
            assertEquals(1L, source.snapshot().longAt(
                    "/initializationCount"),
                    "catch-up must not reinitialize the source");
            assertEquals(8L, source.snapshot().longAt("/counter"));
        }
    }

    private static OperationCall increment(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline) {
        return coordination.operations()
                .on(source)
                .from(timeline)
                .call("increment")
                .through("ownerChannel")
                .request(request -> { });
    }

    private static OperationCall incrementByFour(
            BlueCoordination coordination,
            DocumentHandle source,
            TimelineHandle timeline) {
        return coordination.operations()
                .on(source)
                .from(timeline)
                .call("increment")
                .through("ownerChannel")
                .requestYaml("amount: 4");
    }

    private static long auditLongAt(
            BlueCoordination coordination,
            DocumentId documentId,
            String pointer) {
        Object value = coordination.advanced().auditDocument(documentId)
                .valueAt(pointer).copyNode().getValue();
        return ((Number) value).longValue();
    }

    private static String consumerYaml() {
        return consumerYaml(A, A_TIMELINE);
    }

    private static String consumerYaml(
            DocumentId documentId,
            String timelineId) {
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
                  fromBInitialized:
                    type: Embedded Node Channel
                    sourcePath: /children/b
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Initialized
                  onBInitialized:
                    type: Coordination/Sequential Workflow
                    channel: fromBInitialized
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Initialized
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedInitializations
                              val: {$add: [{$document: /observedInitializations}, 1]}
                          - $return: true
                  fromBChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/b
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                  onBChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromBChanged
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
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/A Attached
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String sourceYaml() {
        return sourceYaml(B, B_TIMELINE);
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
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/B Initialized
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

    private static String readThroughHostYaml() {
        return """
                documentId: %s
                observedCounter: -1
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
                  fromChild:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Read Through Changed
                  reflectChild:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Read Through Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedCounter
                              val: {$document: /child/counter}
                          - $return: true
                """.formatted(
                READ_THROUGH_HOST.value(),
                READ_THROUGH_HOST_TIMELINE,
                ACTOR);
    }

    private static String readThroughSourceYaml() {
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
                    request:
                      amount:
                        type: Integer
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
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/Read Through Changed
                          - $return: true
                """.formatted(
                READ_THROUGH_SOURCE.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                READ_THROUGH_SOURCE_TIMELINE,
                ACTOR);
    }

    private static String duplicateConsumerYaml(String timelineId) {
        return """
                documentId: %s
                children: {}
                firstChanges: 0
                secondChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  fromFirstChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/first
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                  onFirstChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromFirstChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /firstChanges
                              val: {$add: [{$document: /firstChanges}, 1]}
                          - $return: true
                  fromSecondChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/second
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                  onSecondChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromSecondChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/B Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /secondChanges
                              val: {$add: [{$document: /secondChanges}, 1]}
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachBoth:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      first: {}
                      second: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/first
                              val: {$binding: event/message/request/first}
                          - $appendChange:
                              op: add
                              path: /children/second
                              val: {$binding: event/message/request/second}
                          - $return: true
                """.formatted(
                        DUPLICATE_CONSUMER.value(), timelineId, ACTOR);
    }
}
