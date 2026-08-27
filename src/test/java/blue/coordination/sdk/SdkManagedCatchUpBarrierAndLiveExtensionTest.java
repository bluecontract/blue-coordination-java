package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpBarrier;
import blue.coordination.api.ManagedCatchUpBarrierStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK acceptance for an extendable multi-child managed-epoch barrier. */
final class SdkManagedCatchUpBarrierAndLiveExtensionTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId A = DocumentId.of(
            "sdk-managed-barrier-a");
    private static final DocumentId B = DocumentId.of(
            "sdk-managed-barrier-b");
    private static final DocumentId C = DocumentId.of(
            "sdk-managed-barrier-c");
    private static final String A_TIMELINE = "sdk/managed-barrier/a";
    private static final String B_TIMELINE = "sdk/managed-barrier/b";
    private static final String C_TIMELINE = "sdk/managed-barrier/c";

    private static final String SOURCE_RECEIPTS_READ =
            "managedEpoch.catchUp.sourceReceiptsRead";
    private static final String PLANS_OPENED =
            "managedEpoch.catchUp.plansOpened";
    private static final String OCCURRENCES_ADVANCED =
            "managedEpoch.catchUp.occurrencesAdvanced";
    private static final String AFFECTED_DOCUMENTS_OPENED =
            "managedEpoch.catchUp.affectedDocumentsOpened";
    private static final String PROCESS_CALLS =
            "managedEpoch.catchUp.processCalls";
    private static final String SOURCE_PROCESS_CALLS =
            "managedEpoch.catchUp.sourceProcessCalls";
    private static final String COMPONENT_FINALIZATIONS =
            "managedEpoch.catchUp.componentFinalizations";
    private static final String UNRELATED_DOCUMENTS_SCANNED =
            "managedEpoch.catchUp.unrelatedDocumentsScanned";
    private static final String CONTRACTS_COMPONENT_FINALIZATIONS =
            "contracts.closure.tentativeComponentFinalizations";
    private static final String AUTOMATIC_OCCURRENCE_RETRIES =
            "contracts.occurrenceResolver.retries";
    private static final String PROVIDER_REFERENCE_SUBSTITUTIONS =
            "managedEpoch.catchUp.providerReferenceSubstitutions";

    @Test
    void multiChildBarrierExtendsBeforeReadyAndPreventsDirectOvertaking() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            TimelineHandle cTimeline = coordination.timelines().register(
                    C_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml(B, B_TIMELINE))
                            .publicRoot()
                            .fromNow());
            DocumentHandle c = coordination.documents().admit(
                    ManagedDocument.yaml(C, sourceYaml(C, C_TIMELINE))
                            .publicRoot()
                            .fromNow());
            blue.coordination.api.DocumentSnapshot readyBeforeAttachment =
                    coordination.advanced().rawEngine().document(A);
            ExactBlueValue bZero = b.history().get(0).after();
            ExactBlueValue cZero = c.history().get(0).after();

            assertApplied(increment(coordination, b, bTimeline).execute());
            assertApplied(increment(coordination, c, cTimeline).execute());
            assertApplied(increment(coordination, b, bTimeline).execute());
            assertApplied(increment(coordination, c, cTimeline).execute());
            List<String> bHistoryBeforeAttachment = historyBlueIds(b);
            List<String> cHistoryBeforeAttachment = historyBlueIds(c);
            List<String> expectedApplicationOrder = new ArrayList<>(List.of(
                    receiptIdentity(coordination, B, 1L),
                    receiptIdentity(coordination, C, 1L),
                    receiptIdentity(coordination, B, 2L),
                    receiptIdentity(coordination, C, 2L)));

            // when
            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachBoth")
                    .through("ownerChannel")
                    .request(request -> request
                            .exact("b", bZero)
                            .exact("c", cZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            List<ManagedOccurrenceCatchUpPlan> admittedPlans = coordination
                    .advanced().auditManagedCatchUpPlans(A);
            assertEquals(2, admittedPlans.size());
            assertEquals(List.of("/children/b", "/children/c"),
                    admittedPlans.stream()
                            .map(ManagedOccurrenceCatchUpPlan::targetPath)
                            .sorted()
                            .toList());
            assertEquals(1, admittedPlans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::barrierIdentity)
                    .distinct()
                    .count());
            String barrierIdentity = admittedPlans.get(0).barrierIdentity();
            ManagedCatchUpBarrier barrier = coordination.advanced()
                    .auditManagedCatchUpBarrier(barrierIdentity)
                    .orElseThrow();
            assertEquals(ManagedCatchUpBarrierStatus.OPEN, barrier.status());
            assertEquals(admittedPlans.stream()
                            .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                            .sorted()
                            .toList(),
                    barrier.planIdentities());
            assertEquals(List.of(2L, 2L), admittedPlans.stream()
                    .map(ManagedOccurrenceCatchUpPlan
                            ::requiredThroughSourceEpoch)
                    .sorted()
                    .toList());
            assertCatchingUp(coordination, barrierIdentity);

            CoordinationTestControl.MetricsSnapshot beforeCatchUp =
                    control.metricsSnapshot();
            ArrayList<String> actualApplicationOrder = new ArrayList<>();
            DrainResult firstApplication = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            recordOnlyApplication(firstApplication, actualApplicationOrder);
            assertCatchingUp(coordination, barrierIdentity);
            CoordinationTestControl.MetricsSnapshot afterFirstApplication =
                    control.metricsSnapshot();

            EntryHandle direct = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("markDirect")
                    .through("ownerChannel")
                    .request(request -> { })
                    .submit();
            EntryHandle directById = coordination.operations()
                    .on(A)
                    .from(aTimeline)
                    .call("markDirect")
                    .through("ownerChannel")
                    .request(request -> { })
                    .submit();
            EntryHandle bThree = increment(
                    coordination, b, bTimeline).submit();
            DrainResult extension = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            assertFalse(extension.find(direct).isPresent(),
                    "the direct A entry must remain behind its active barrier");
            assertFalse(extension.find(directById).isPresent(),
                    "id-selected A work must remain behind the same barrier");
            assertEquals(EntryDisposition.APPLIED,
                    extension.entry(bThree).disposition(),
                    extension.entry(bThree).diagnostic().toString());
            assertTrue(extension.managedEpochApplications().isEmpty(),
                    "the one-transition budget belongs to genuine B3");
            expectedApplicationOrder.add(
                    receiptIdentity(coordination, B, 3L));
            List<String> bHistoryAfterExtension = historyBlueIds(b);
            assertEquals(bHistoryBeforeAttachment.size() + 1,
                    bHistoryAfterExtension.size());
            assertEquals(cHistoryBeforeAttachment, historyBlueIds(c));
            ManagedOccurrenceCatchUpPlan extendedB = planFor(
                    coordination, B);
            assertEquals(2L, extendedB.nextSourceEpoch());
            assertEquals(3L, extendedB.requiredThroughSourceEpoch(),
                    "the active B plan must extend in the B3 commit");
            assertEquals(2L, planFor(coordination, C)
                    .requiredThroughSourceEpoch());
            assertCatchingUp(coordination, barrierIdentity);
            CoordinationTestControl.MetricsSnapshot afterExtension =
                    control.metricsSnapshot();
            assertEquals(1L, publicCounterDelta(
                    beforeCatchUp,
                    afterExtension,
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));

            boolean observedPartialActivation = false;
            while (actualApplicationOrder.size()
                    < expectedApplicationOrder.size()) {
                DrainResult application = coordination.processing().drain(
                        new DrainBudget(1L, 1L));
                recordOnlyApplication(application, actualApplicationOrder);
                assertFalse(application.find(direct).isPresent());
                assertFalse(application.find(directById).isPresent());
                if (actualApplicationOrder.size()
                        < expectedApplicationOrder.size()) {
                    assertCatchingUp(coordination, barrierIdentity);
                }
                if (!observedPartialActivation
                        && planFor(coordination, C).status().terminal()
                        && !planFor(coordination, B).status().terminal()) {
                    blue.coordination.api.DocumentSnapshot partial =
                            coordination.advanced().rawEngine().document(A);
                    assertEquals(readyBeforeAttachment.blueId(),
                            partial.blueId(),
                            "ordinary reads must retain the old ready body");
                    assertEquals(readyBeforeAttachment.embeddedChildren(),
                            partial.embeddedChildren(),
                            "an activated sibling must not leak into the "
                                    + "ready topology while another plan holds "
                                    + "the barrier");
                    assertTrue(partial.embeddedChildren().isEmpty());

                    control.restartFromStores();
                    blue.coordination.api.DocumentSnapshot restarted =
                            coordination.advanced().rawEngine().document(A);
                    assertEquals(partial.blueId(), restarted.blueId());
                    assertEquals(partial.embeddedChildren(),
                            restarted.embeddedChildren(),
                            "restart must preserve the same ready topology");
                    observedPartialActivation = true;
                }
            }

            assertEquals(expectedApplicationOrder, actualApplicationOrder,
                    "B/C epochs must interleave by original source order");
            assertTrue(observedPartialActivation,
                    "C must activate while B still holds the shared barrier");
            ManagedOccurrenceCatchUpPlan completedB = planFor(
                    coordination, B);
            ManagedOccurrenceCatchUpPlan completedC = planFor(
                    coordination, C);
            assertTrue(completedB.status().terminal());
            assertTrue(completedC.status().terminal());
            assertEquals(4L, completedB.nextSourceEpoch());
            assertEquals(3L, completedB.requiredThroughSourceEpoch());
            assertEquals(3L, completedC.nextSourceEpoch());
            assertEquals(2L, completedC.requiredThroughSourceEpoch());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(barrierIdentity)
                            .orElseThrow().status());
            ManagedDocumentReadiness ready = coordination.advanced()
                    .auditManagedDocumentReadiness(A).orElseThrow();
            assertTrue(ready.ready());
            assertTrue(ready.activeBarrierIdentities().isEmpty());
            assertEquals(3L, a.snapshot().longAt("/bChanges"));
            assertEquals(2L, a.snapshot().longAt("/cChanges"));
            assertEquals(0L, a.snapshot().longAt("/directCount"));
            assertEquals(1L, b.snapshot().longAt("/initializationCount"));
            assertEquals(3L, b.snapshot().longAt("/counter"));
            assertEquals(1L, c.snapshot().longAt("/initializationCount"));
            assertEquals(2L, c.snapshot().longAt("/counter"));
            assertEquals(Map.of(
                            "/children/b", B,
                            "/children/c", C),
                    coordination.advanced().rawEngine().document(A)
                            .embeddedChildren(),
                    "the complete barrier publishes body and topology together");
            assertEquals(bHistoryAfterExtension, historyBlueIds(b),
                    "managed delivery must not append or reprocess B");
            assertEquals(cHistoryBeforeAttachment, historyBlueIds(c),
                    "managed delivery must not append or reprocess C");

            CoordinationTestControl.MetricsSnapshot afterCatchUp =
                    control.metricsSnapshot();
            assertCatchUpMetrics(
                    beforeCatchUp,
                    afterFirstApplication,
                    afterExtension,
                    afterCatchUp);
            assertEquals(1L, publicCounterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS),
                    "only genuine B3 may add a source external PROCESS");
            assertEquals(5L, publicCounterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.EMBEDDED_EPOCH_PROCESS_CALLS));
            assertEquals(5L, publicCounterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.PARENT_EPOCH_APPLICATIONS));
            assertEquals(0L, publicCounterDelta(
                    beforeCatchUp,
                    afterCatchUp,
                    CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS));
            assertForbiddenStructuralDeltasAreZero(
                    beforeCatchUp, afterCatchUp);

            DrainResult directDrain = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            DrainResult directByIdDrain = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(EntryDisposition.APPLIED,
                    directDrain.entry(direct).disposition(),
                    directDrain.entry(direct).diagnostic().toString());
            assertFalse(directDrain.find(directById).isPresent());
            assertEquals(EntryDisposition.APPLIED,
                    directByIdDrain.entry(directById).disposition(),
                    directByIdDrain.entry(directById).diagnostic().toString());
            assertEquals(2L, a.snapshot().longAt("/directCount"));
            assertEquals(3L, publicCounterDelta(
                    beforeCatchUp,
                    control.metricsSnapshot(),
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(bHistoryAfterExtension, historyBlueIds(b));
            assertEquals(cHistoryBeforeAttachment, historyBlueIds(c));
        }
    }

    @Test
    void boundedDrainsFairlyAdvanceContinuingSourceTrafficAndCatchUp() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml(B, B_TIMELINE))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue bZero = b.history().get(0).after();
            assertApplied(increment(coordination, b, bTimeline).execute());
            assertApplied(increment(coordination, b, bTimeline).execute());

            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachB")
                    .through("ownerChannel")
                    .request(request -> request.exact("b", bZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            ManagedOccurrenceCatchUpPlan admitted = planFor(coordination, B);
            String barrierIdentity = admitted.barrierIdentity();
            long expectedCursor = admitted.nextSourceEpoch();
            long expectedFrontier = admitted.requiredThroughSourceEpoch();
            assertCatchingUp(coordination, barrierIdentity);

            EntryHandle direct = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("markDirect")
                    .through("ownerChannel")
                    .request(request -> { })
                    .submit();
            ArrayList<EntryHandle> continuingSourceEntries = new ArrayList<>();
            continuingSourceEntries.add(
                    increment(coordination, b, bTimeline).submit());

            // when
            for (int turn = 0; turn < 6; turn++) {
                DrainResult one = coordination.processing().drain(
                        new DrainBudget(1L, 1L));
                assertFalse(one.find(direct).isPresent(),
                        "direct consumer work cannot cross the active barrier");
                if (turn % 2 == 0) {
                    expectedCursor = Math.addExact(expectedCursor, 1L);
                    assertEquals(1, one.managedEpochApplications().size());
                } else {
                    expectedFrontier = Math.addExact(expectedFrontier, 1L);
                    assertTrue(one.managedEpochApplications().isEmpty());
                }
                ManagedOccurrenceCatchUpPlan current = planFor(
                        coordination, B);
                String turnDiagnostic = "turn=" + turn
                        + ", entries=" + one.entries()
                        + ", applications="
                        + one.managedEpochApplications()
                        + ", attempts="
                        + one.managedEpochApplicationAttempts()
                        + ", status=" + current.status()
                        + ", cursor=" + current.nextSourceEpoch()
                        + ", frontier="
                        + current.requiredThroughSourceEpoch()
                        + ", occurrence=" + coordination.advanced()
                                .auditManagedOccurrence(A, "/children/b")
                        + ", readiness=" + coordination.advanced()
                                .auditManagedDocumentReadiness(A);
                assertEquals(expectedCursor, current.nextSourceEpoch(),
                        turnDiagnostic);
                assertEquals(expectedFrontier,
                        current.requiredThroughSourceEpoch(), turnDiagnostic);
                assertCatchingUp(coordination, barrierIdentity);
                if (turn == 1) {
                    control.restartFromStores();
                }
                if (turn % 2 == 1 && turn < 5) {
                    // SDK targets are exact at submission. Keep one valid
                    // source call pending across the intervening managed turn.
                    continuingSourceEntries.add(
                            increment(coordination, b, bTimeline).submit());
                }
            }

            int settlingDrains = 0;
            while (!coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready()
                    && settlingDrains < 32) {
                DrainResult one = coordination.processing().drain(
                        new DrainBudget(1L, 1L));
                assertFalse(one.find(direct).isPresent());
                settlingDrains++;
            }
            ManagedOccurrenceCatchUpPlan completed = planFor(coordination, B);
            DrainResult directDrain = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(3, continuingSourceEntries.size());
            assertEquals(admitted.nextSourceEpoch() + 3L, expectedCursor,
                    "catch-up receives every other contested bounded turn");
            assertEquals(admitted.requiredThroughSourceEpoch() + 3L,
                    expectedFrontier,
                    "source commits receive every other contested turn");
            assertTrue(settlingDrains < 32,
                    "a finite source stream must eventually settle");
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(barrierIdentity)
                            .orElseThrow().status());
            assertTrue(completed.status().terminal());
            assertEquals(6L, completed.nextSourceEpoch());
            assertEquals(5L, completed.requiredThroughSourceEpoch());
            assertEquals(5L, b.snapshot().longAt("/counter"));
            assertEquals(5L, a.snapshot().longAt("/bChanges"));
            assertEquals(EntryDisposition.APPLIED,
                    directDrain.entry(direct).disposition(),
                    directDrain.entry(direct).diagnostic().toString());
            assertEquals(1L, a.snapshot().longAt("/directCount"));
        }
    }

    @Test
    void completedSiblingGatesLaterSourceUntilOlderWorkAcrossRestart() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            TimelineHandle cTimeline = coordination.timelines().register(
                    C_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, nestedGrowthConsumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, sourceYaml(B, B_TIMELINE))
                            .publicRoot()
                            .fromNow());
            DocumentHandle c = coordination.documents().admit(
                    ManagedDocument.yaml(C, sourceYaml(C, C_TIMELINE))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue bZero = b.history().get(0).after();
            ExactBlueValue cZero = c.history().get(0).after();
            assertApplied(increment(coordination, b, bTimeline).execute());
            assertApplied(increment(coordination, c, cTimeline).execute());
            List<String> bHistoryBeforeExtension = historyBlueIds(b);
            List<String> cHistory = historyBlueIds(c);

            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachBAndRetainC")
                    .through("ownerChannel")
                    .request(request -> request
                            .exact("b", bZero)
                            .exact("candidateC", cZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            List<ManagedOccurrenceCatchUpPlan> initialPlans = coordination
                    .advanced().auditManagedCatchUpPlans(A);
            assertEquals(1, initialPlans.size());
            assertEquals(B, initialPlans.get(0).sourceDocumentId());
            String barrierIdentity = initialPlans.get(0).barrierIdentity();
            assertCatchingUp(coordination, barrierIdentity);
            CoordinationTestControl.MetricsSnapshot beforeNestedApplication =
                    control.metricsSnapshot();

            // when
            DrainResult nestedApplication = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(1,
                    nestedApplication.managedEpochApplications().size(),
                    "entries=" + nestedApplication.entries()
                            + ", attempts=" + nestedApplication
                                    .managedEpochApplicationAttempts()
                            + ", diagnostic="
                            + nestedApplication.diagnostic()
                            + ", providerReferenceSubstitutions="
                            + delta(
                                    beforeNestedApplication,
                                    control.metricsSnapshot(),
                                    PROVIDER_REFERENCE_SUBSTITUTIONS));
            assertEquals(receiptIdentity(coordination, B, 1L),
                    nestedApplication.managedEpochApplications().get(0)
                            .sourceReceiptIdentity());
            List<ManagedOccurrenceCatchUpPlan> extendedPlans = coordination
                    .advanced().auditManagedCatchUpPlans(A);
            assertEquals(2, extendedPlans.size());
            assertEquals(List.of(B, C), extendedPlans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::sourceDocumentId)
                    .sorted((left, right) -> left.value().compareTo(
                            right.value()))
                    .toList());
            assertEquals(1L, extendedPlans.stream()
                    .map(ManagedOccurrenceCatchUpPlan::barrierIdentity)
                    .distinct()
                    .count());
            assertTrue(extendedPlans.stream().allMatch(plan ->
                    plan.barrierIdentity().equals(barrierIdentity)));
            ManagedCatchUpBarrier extendedBarrier = coordination.advanced()
                    .auditManagedCatchUpBarrier(barrierIdentity)
                    .orElseThrow();
            assertEquals(extendedPlans.stream()
                            .map(ManagedOccurrenceCatchUpPlan::planIdentity)
                            .sorted()
                            .toList(),
                    extendedBarrier.planIdentities());
            assertTrue(planFor(coordination, B).status().terminal());
            assertFalse(planFor(coordination, C).status().terminal());
            assertEquals(cZero.blueId(), coordination.advanced()
                    .auditDocument(A).valueAt("/children/c").blueId());
            assertCatchingUp(coordination, barrierIdentity);
            assertEquals(1L, delta(
                    beforeNestedApplication,
                    control.metricsSnapshot(),
                    AUTOMATIC_OCCURRENCE_RETRIES));
            assertEquals(bHistoryBeforeExtension, historyBlueIds(b),
                    "retained B must never be reprocessed");
            assertEquals(cHistory, historyBlueIds(c),
                    "retained C must never be reprocessed");

            EntryHandle bTwo = increment(coordination, b, bTimeline).submit();
            control.restartFromStores();
            DrainResult sourceFence = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertTrue(sourceFence.find(bTwo).isEmpty(),
                    "B2 must remain pending while its affected closure "
                            + "contains catching-up A");
            assertEquals(1, sourceFence.managedEpochApplications().size());
            assertEquals(receiptIdentity(coordination, C, 1L),
                    sourceFence.managedEpochApplications().get(0)
                            .sourceReceiptIdentity());
            assertEquals(1L, a.snapshot().longAt("/bChanges"),
                    "later B2 must not overtake older C1");
            assertEquals(1L, a.snapshot().longAt("/cChanges"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(barrierIdentity)
                            .orElseThrow().status());
            assertEquals(bHistoryBeforeExtension, historyBlueIds(b),
                    "gated B2 must remain unprocessed across reconstruction");
            assertEquals(cHistory, historyBlueIds(c),
                    "retained C must never be reprocessed");

            control.restartFromStores();
            DrainResult completed = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    completed.entry(bTwo).disposition(),
                    completed.entry(bTwo).toString());
            assertTrue(completed.managedEpochApplications().isEmpty(),
                    "B2 executes once through ordinary closure processing "
                            + "after the barrier clears");
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(ManagedCatchUpBarrierStatus.COMPLETE,
                    coordination.advanced()
                            .auditManagedCatchUpBarrier(barrierIdentity)
                            .orElseThrow().status());
            assertEquals(2L, a.snapshot().longAt("/bChanges"));
            assertEquals(1L, a.snapshot().longAt("/cChanges"));
            assertEquals(bHistoryBeforeExtension.size() + 1,
                    historyBlueIds(b).size(),
                    "B2 is processed exactly once after the barrier clears");
            assertEquals(cHistory, historyBlueIds(c));
        }
    }

    @Test
    void retainedConsumerEpochPropagatesThroughAlreadyActiveParent() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // given
            CoordinationTestControl control = CoordinationTestControl.attach(
                    coordination.advanced().rawEngine());
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            TimelineHandle cTimeline = coordination.timelines().register(
                    C_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, activeParentYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle b = coordination.documents().admit(
                    ManagedDocument.yaml(B, nestedConsumerYaml())
                            .publicRoot()
                            .fromNow());
            DocumentHandle c = coordination.documents().admit(
                    ManagedDocument.yaml(C, sourceYaml(C, C_TIMELINE))
                            .publicRoot()
                            .fromNow());
            ExactBlueValue bZero = b.history().get(0).after();
            ExactBlueValue cZero = c.history().get(0).after();
            assertApplied(coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachB")
                    .through("ownerChannel")
                    .request(request -> request.exact("b", bZero))
                    .execute());
            assertTrue(coordination.advanced()
                    .auditManagedOccurrence(A, "/child")
                    .orElseThrow()
                    .active());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow()
                    .ready());
            assertApplied(increment(coordination, c, cTimeline).execute());
            List<String> cHistory = historyBlueIds(c);
            List<String> cReceipts = receiptIdentities(coordination, C);
            List<String> cEvents = eventIdentities(coordination, C);

            EntryHandle attachment = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("attachC")
                    .through("ownerChannel")
                    .request(request -> request.exact("c", cZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            ManagedOccurrenceCatchUpPlan plan = coordination.advanced()
                    .auditManagedCatchUpPlans(B).get(0);
            assertEquals(C, plan.sourceDocumentId());
            assertEquals(1L, plan.nextSourceEpoch());
            assertEquals(1L, plan.requiredThroughSourceEpoch());
            assertEquals(SessionStatus.CATCHING_UP, coordination.advanced()
                    .auditManagedDocumentReadiness(B)
                    .orElseThrow()
                    .status());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow()
                    .ready());
            long aEpochBefore = a.snapshot().epoch();
            CoordinationTestControl.MetricsSnapshot beforeApplication =
                    control.metricsSnapshot();

            // when
            DrainResult application = coordination.processing().drain(
                    new DrainBudget(1L, 1L));

            // then
            assertEquals(1, application.managedEpochApplications().size());
            ManagedEpochApplicationReceipt applied = application
                    .managedEpochApplications().get(0);
            assertEquals(B, applied.consumerDocumentId());
            assertEquals(receiptIdentity(coordination, C, 1L),
                    applied.sourceReceiptIdentity());
            assertEquals(1L, b.snapshot().longAt("/observedCChanges"));
            assertEquals(1L, a.snapshot().longAt("/observedBChanges"));
            assertEquals(aEpochBefore + 1L, a.snapshot().epoch(),
                    "A must commit in the same affected closure as B<-C");
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow()
                    .ready());
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(B)
                    .orElseThrow()
                    .ready());
            assertEquals(cHistory, historyBlueIds(c),
                    "retained C history must remain immutable");
            assertEquals(cReceipts, receiptIdentities(coordination, C));
            assertEquals(cEvents, eventIdentities(coordination, C));
            CoordinationTestControl.MetricsSnapshot afterApplication =
                    control.metricsSnapshot();
            assertEquals(0L, delta(
                    beforeApplication,
                    afterApplication,
                    SOURCE_PROCESS_CALLS));
            assertEquals(0L, publicCounterDelta(
                    beforeApplication,
                    afterApplication,
                    CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
        }
    }

    private static void assertCatchUpMetrics(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot afterFirstApplication,
            CoordinationTestControl.MetricsSnapshot afterExtension,
            CoordinationTestControl.MetricsSnapshot after) {
        assertEquals(5L, delta(before, after, SOURCE_RECEIPTS_READ));
        assertEquals(5L, delta(before, after, PLANS_OPENED));
        assertEquals(5L, delta(before, after, OCCURRENCES_ADVANCED));
        assertEquals(15L, delta(before, after, AFFECTED_DOCUMENTS_OPENED));
        assertEquals(5L, delta(before, after, PROCESS_CALLS));
        assertEquals(0L, delta(before, after, SOURCE_PROCESS_CALLS));
        assertEquals(0L, delta(
                before, after, UNRELATED_DOCUMENTS_SCANNED));
        long catchUpFinalizations = Math.addExact(
                delta(before,
                        afterFirstApplication,
                        CONTRACTS_COMPONENT_FINALIZATIONS),
                delta(afterExtension,
                        after,
                        CONTRACTS_COMPONENT_FINALIZATIONS));
        assertEquals(catchUpFinalizations,
                delta(before, after, COMPONENT_FINALIZATIONS));
    }

    private static void assertForbiddenStructuralDeltasAreZero(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after) {
        for (CoordinationMetrics.Counter counter : List.of(
                CoordinationMetrics.Counter.FULL_ENVIRONMENT_SCANS,
                CoordinationMetrics.Counter.UNRELATED_DOCUMENT_READS,
                CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT,
                CoordinationMetrics.Counter.POST_PROCESS_FULL_PROJECTIONS,
                CoordinationMetrics.Counter.PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY,
                CoordinationMetrics.Counter.CHILD_PROCESS_RERUNS_ON_PARENT_RETRY)) {
            assertEquals(0L, publicCounterDelta(before, after, counter),
                    counter.name());
        }
    }

    private static void assertCatchingUp(
            BlueCoordination coordination,
            String barrierIdentity) {
        ManagedDocumentReadiness readiness = coordination.advanced()
                .auditManagedDocumentReadiness(A).orElseThrow();
        assertEquals(SessionStatus.CATCHING_UP, readiness.status());
        assertFalse(readiness.ready());
        assertEquals(List.of(barrierIdentity),
                readiness.activeBarrierIdentities());
        assertEquals(ManagedCatchUpBarrierStatus.OPEN,
                coordination.advanced()
                        .auditManagedCatchUpBarrier(barrierIdentity)
                        .orElseThrow().status());
    }

    private static ManagedOccurrenceCatchUpPlan planFor(
            BlueCoordination coordination,
            DocumentId source) {
        return coordination.advanced().auditManagedCatchUpPlans(A).stream()
                .filter(plan -> plan.sourceDocumentId().equals(source))
                .findFirst()
                .orElseThrow();
    }

    private static String receiptIdentity(
            BlueCoordination coordination,
            DocumentId source,
            long epoch) {
        return coordination.advanced().auditManagedEpoch(source, epoch)
                .orElseThrow().receiptIdentity();
    }

    private static void recordOnlyApplication(
            DrainResult drain,
            List<String> identities) {
        assertEquals(1, drain.managedEpochApplications().size());
        ManagedEpochApplicationReceipt application = drain
                .managedEpochApplications().get(0);
        identities.add(application.sourceReceiptIdentity());
    }

    private static List<String> historyBlueIds(DocumentHandle document) {
        return document.history().stream()
                .map(revision -> revision.after().blueId())
                .toList();
    }

    private static List<String> receiptIdentities(
            BlueCoordination coordination,
            DocumentId documentId) {
        return coordination.advanced().auditManagedEpochs(documentId).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static List<String> eventIdentities(
            BlueCoordination coordination,
            DocumentId documentId) {
        return coordination.advanced().auditManagedEpochs(documentId).stream()
                .flatMap(receipt -> receipt.emittedEvents().stream())
                .map(ManagedEventOccurrence::managedEventIdentity)
                .toList();
    }

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
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

    private static long delta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            String counter) {
        return Math.subtractExact(
                after.counters().getOrDefault(counter, 0L),
                before.counters().getOrDefault(counter, 0L));
    }

    private static long publicCounterDelta(
            CoordinationTestControl.MetricsSnapshot before,
            CoordinationTestControl.MetricsSnapshot after,
            CoordinationMetrics.Counter counter) {
        return delta(before, after, counter.name());
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                children: {}
                bChanges: 0
                cChanges: 0
                directCount: 0
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
                      kind: CatchUp/Source Changed
                  onBChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromBChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /bChanges
                              val: {$add: [{$document: /bChanges}, 1]}
                          - $return: true
                  fromCChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/c
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                  onCChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromCChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /cChanges
                              val: {$add: [{$document: /cChanges}, 1]}
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
                      b: {}
                      c: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/b
                              val: {$binding: event/message/request/b}
                          - $appendChange:
                              op: add
                              path: /children/c
                              val: {$binding: event/message/request/c}
                          - $return: true
                  attachB:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      b: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/b
                              val: {$binding: event/message/request/b}
                          - $return: true
                  markDirect:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /directCount
                              val: {$add: [{$document: /directCount}, 1]}
                          - $return: true
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String nestedGrowthConsumerYaml() {
        return """
                documentId: %s
                children: {}
                bChanges: 0
                cChanges: 0
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
                      kind: CatchUp/Source Changed
                  onBChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromBChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /bChanges
                              val: {$add: [{$document: /bChanges}, 1]}
                          - $if:
                              cond: {$eq: [{$document: /bChanges}, 0]}
                              then:
                                - $appendChange:
                                    op: add
                                    path: /children/c
                                    val: {$document: /candidateC}
                          - $return: true
                  fromCChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/c
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                  onCChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromCChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /cChanges
                              val: {$add: [{$document: /cChanges}, 1]}
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachBAndRetainC:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      b: {}
                      candidateC: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /candidateC
                              val: {$binding: event/message/request/candidateC}
                          - $appendChange:
                              op: add
                              path: /children/b
                              val: {$binding: event/message/request/b}
                          - $return: true
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String activeParentYaml() {
        return """
                documentId: %s
                observedBChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromBChanged:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Nested Consumer Changed
                  onBChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromBChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Nested Consumer Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedBChanges
                              val: {$add: [{$document: /observedBChanges}, 1]}
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachB:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      b: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/b}
                          - $return: true
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String nestedConsumerYaml() {
        return """
                documentId: %s
                observedCChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromCChanged:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                  onCChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromCChanged
                    event:
                      type: Coordination/Event
                      kind: CatchUp/Source Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedCChanges
                              val: {$add: [{$document: /observedCChanges}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: CatchUp/Nested Consumer Changed
                          - $return: true
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachC:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      c: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /child
                              val: {$binding: event/message/request/c}
                          - $return: true
                """.formatted(B.value(), B_TIMELINE, ACTOR);
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
