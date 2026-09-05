package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.internal.CoordinationTestControl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A's ready frontier depends transitively on B's owned historical C work. */
final class SdkPendingNestedSourceReadinessTest {
    private static final DocumentId A = DocumentId.of("pending-nested-a");
    private static final DocumentId B = DocumentId.of("pending-nested-b");
    private static final DocumentId C = DocumentId.of("pending-nested-c");
    private static final String TIMELINE = "pending-nested/alice";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bOwnedPendingCBlocksAUntilItsReadyFrontierAcrossRestartAndReceiptRecovery(boolean observes) {
        try (BlueCoordination blue = BlueCoordination.inMemory()) {
            CoordinationTestControl control = CoordinationTestControl.attach(blue.advanced().rawEngine());
            TimelineHandle timeline = blue.timelines().register(TIMELINE, "alice");
            DocumentHandle c = admit(blue, C, "/unused", observes);
            ExactBlueValue cZero = c.history().get(0).after();
            applied(operation(blue, c, timeline, "increment").requestYaml("{}").execute());
            applied(operation(blue, c, timeline, "increment").requestYaml("{}").execute());
            List<String> cReceipts = receipts(blue, C);
            String cExact = c.exact().json();
            DocumentHandle b = admit(blue, B, "/c", observes);
            ExactBlueValue bZero = b.history().get(0).after();
            applied(operation(blue, b, timeline, "increment").requestYaml("{}").execute());
            applied(operation(blue, b, timeline, "increment").requestYaml("{}").execute());
            for (ManagedEpochReceipt receipt : blue.advanced().auditManagedEpochs(B)) {
                assertFalse(receipt.afterDocument().json().contains("\"c\""));
            }
            DocumentHandle a = admit(blue, A, "/b", observes);
            EntryHandle attachB = operation(blue, a, timeline, "attach")
                    .request(request -> request.exact("embeddedContract", bZero)).submit();
            applied(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(attachB));
            for (int index = 0; index < 2; index++) {
                var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                assertEquals(A, work.consumerDocumentId());
                assertEquals(1, blue.processing().drainManagedEpochApplication(work.workIdentity())
                        .managedEpochApplications().size());
            }
            assertTrue(ready(blue, A));
            ManagedOccurrenceCatchUpPlan aPlan = onlyPlan(blue, A, B);
            long aReadyBefore = blue.advanced().auditManagedDocumentReadiness(A).orElseThrow()
                    .readyEpoch().orElseThrow();
            String aReadyBlueBefore = blue.advanced().auditManagedDocumentReadiness(A).orElseThrow()
                    .readyBlueId().orElseThrow();
            EntryHandle attachC = operation(blue, b, timeline, "attach")
                    .request(request -> request.exact("embeddedContract", cZero)).submit();
            applied(blue.processing().drainJournal(new DrainBudget(1L, 1L)).entry(attachC));
            ManagedEpochReceipt bThree = blue.advanced().auditManagedEpoch(B, 3L).orElseThrow();
            assertEquals(cZero.blueId(), bThree.afterDocument().valueAt("/c").blueId());
            ManagedOccurrenceCatchUpPlan bPlan = onlyPlan(blue, B, C);
            assertEquals(cZero.blueId(), bPlan.admittedSourceBlueId());
            assertEquals(0L, bPlan.admittedSourceEpoch());
            assertEquals(1L, bPlan.activationGeneration());
            assertFalse(ready(blue, B));
            assertFalse(ready(blue, A), "A must not be READY while B's owned C catch-up remains pending");
            assertEquals(aReadyBefore, blue.advanced().auditManagedDocumentReadiness(A)
                    .orElseThrow().readyEpoch().orElseThrow());
            assertEquals(aReadyBlueBefore, blue.advanced().auditManagedDocumentReadiness(A)
                    .orElseThrow().readyBlueId().orElseThrow());
            assertTrue(blue.advanced().auditManagedDocumentReadiness(A).orElseThrow()
                    .activeBarrierIdentities().contains(bPlan.barrierIdentity()),
                    "A's readiness fence must reference B's existing owned barrier, not clone a C plan");
            assertEquals(aPlan.planIdentity(), onlyPlan(blue, A, B).planIdentity());
            var before = blue.advanced().rawEngine().metrics();
            List<Long> appliedCEpochs = new ArrayList<>();
            for (long sourceEpoch = 1L; sourceEpoch <= 2L; sourceEpoch++) {
                control.restartFromStores();
                assertFalse(ready(blue, A));
                assertFalse(ready(blue, B));
                var work = blue.advanced().auditNextProcessingSelection().managedEpochApplicationWork().orElseThrow();
                assertEquals(B, work.consumerDocumentId());
                assertEquals(C, work.sourceDocumentId());
                assertEquals(sourceEpoch, work.sourceEpoch());
                long aEpoch = blue.advanced().auditDocument(A).epoch();
                DrainResult one = blue.processing().drainManagedEpochApplication(work.workIdentity());
                assertEquals(1, one.managedEpochApplications().size(), one.managedEpochApplicationAttempts().toString());
                var receipt = one.managedEpochApplications().get(0);
                assertEquals(B, receipt.consumerDocumentId());
                assertEquals(bPlan.targetOccurrenceIdentity(), work.targetOccurrenceIdentity());
                assertEquals(bPlan.planIdentity(), receipt.planIdentity());
                assertEquals(work.workIdentity(), receipt.workIdentity());
                assertEquals(blue.advanced().auditManagedEpoch(C, sourceEpoch).orElseThrow()
                        .receiptIdentity(), receipt.sourceReceiptIdentity());
                assertEquals(sourceEpoch + 1L, receipt.resultingSourceCursor());
                assertEquals(aEpoch + 1L, blue.advanced().auditDocument(A).epoch(),
                        "Every containing parent retains B's changed exact revision");
                assertEquals(blue.advanced().auditDocument(B).blueId(),
                        blue.advanced().auditDocument(A).valueAt("/b").blueId(),
                        "The parent revision must contain the exact newly committed B");
                appliedCEpochs.add(work.sourceEpoch());
                List<String> aHistory = receipts(blue, A);
                List<String> bHistory = receipts(blue, B);
                String aReadiness = blue.advanced().auditManagedDocumentReadiness(A).orElseThrow()
                        .readinessIdentity();
                String bReadiness = blue.advanced().auditManagedDocumentReadiness(B).orElseThrow()
                        .readinessIdentity();
                // Reconcile retained proof after reconstruction. A targeted
                // scheduler call is not an idempotent command-response API:
                // selecting the already committed work again must be rejected.
                control.restartFromStores();
                var recovered = blue.advanced().auditManagedEpochApplicationReceipt(
                        receipt.applicationReceiptIdentity()).orElseThrow();
                assertEquals(receipt.applicationReceiptIdentity(), recovered.applicationReceiptIdentity());
                assertEquals(work.workIdentity(), recovered.workIdentity());
                assertEquals(receipt.planIdentity(), recovered.planIdentity());
                assertEquals(receipt.sourceReceiptIdentity(), recovered.sourceReceiptIdentity());
                assertEquals(receipt.resultingSourceCursor(), recovered.resultingSourceCursor());
                assertEquals(aReadiness, blue.advanced().auditManagedDocumentReadiness(A).orElseThrow()
                        .readinessIdentity());
                assertEquals(bReadiness, blue.advanced().auditManagedDocumentReadiness(B).orElseThrow()
                        .readinessIdentity());
                var rejected = assertThrows(CoordinationException.class,
                        () -> blue.processing().drainManagedEpochApplication(work.workIdentity()));
                assertEquals(CoordinationErrorCode.PROCESSING_SELECTION_MISMATCH,
                        rejected.code());
                assertEquals(aHistory, receipts(blue, A));
                assertEquals(bHistory, receipts(blue, B));
                assertEquals(cReceipts, receipts(blue, C));
                assertEquals(cExact, c.exact().json());
                if (sourceEpoch == 1L) {
                    assertFalse(ready(blue, A));
                    assertFalse(ready(blue, B));
                }
            }
            assertEquals(List.of(1L, 2L), appliedCEpochs);
            assertTrue(ready(blue, B));
            assertTrue(ready(blue, A));
            assertEquals(ManagedCatchUpStatus.COMPLETE, onlyPlan(blue, B, C).status());
            assertEquals(aPlan.planIdentity(), onlyPlan(blue, A, B).planIdentity());
            assertEquals(bPlan.planIdentity(), onlyPlan(blue, B, C).planIdentity());
            assertEquals(0L, blue.advanced().rawEngine().metrics().counter(CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS)
                    - before.counter(CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS));
            assertEquals(0L, blue.advanced().rawEngine().metrics().counter(CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS)
                    - before.counter(CoordinationMetrics.Counter.DOCUMENT_INITIALIZATIONS));
            assertEquals(0L, blue.advanced().rawEngine().metrics().counter(CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT)
                    - before.counter(CoordinationMetrics.Counter.SOURCE_REPLAYS_PER_PARENT));
        }
    }

    private static boolean ready(BlueCoordination blue, DocumentId id) {
        return blue.advanced().auditManagedDocumentReadiness(id).orElseThrow().ready();
    }

    private static ManagedOccurrenceCatchUpPlan onlyPlan(BlueCoordination blue, DocumentId consumer,
                                                        DocumentId source) {
        var plans = blue.advanced().auditManagedCatchUpPlans(consumer);
        assertEquals(1, plans.size(), "No duplicate or foreign-owned nested plans");
        assertEquals(consumer, plans.get(0).consumerDocumentId());
        assertEquals(source, plans.get(0).sourceDocumentId());
        return plans.get(0);
    }

    private static List<String> receipts(BlueCoordination blue, DocumentId id) {
        return blue.advanced().auditManagedEpochs(id).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
    }

    private static DocumentHandle admit(BlueCoordination blue, DocumentId id, String childPath,
                                         boolean observes) {
        return blue.documents().admit(ManagedDocument.yaml(id, yaml(id, childPath, observes))
                .publicRoot().fromNow());
    }

    private static OperationCall operation(BlueCoordination blue, DocumentHandle document,
                                           TimelineHandle timeline, String name) {
        return blue.operations().on(document).from(timeline).call(name).through("ownerChannel");
    }

    private static void applied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
    }

    private static String yaml(DocumentId id, String childPath, boolean observes) {
        return """
                documentId: %s
                counter: 0
                observedChanges: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [%s]
                %s
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
                              kind: PendingNested/Changed
                          - $return: true
                  attach:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      embeddedContract: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: %s
                              val: {$binding: event/message/request/embeddedContract}
                          - $return: true
                """.formatted(id.value(), childPath, observes ? listener(childPath) : "",
                        TIMELINE, childPath);
    }

    private static String listener(String childPath) {
        return """
                  fromChild:
                    type: Embedded Node Channel
                    sourcePath: %s
                    event:
                      type: Coordination/Event
                      kind: PendingNested/Changed
                  onChild:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    event:
                      type: Coordination/Event
                      kind: PendingNested/Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /observedChanges
                              val: {$add: [{$document: /observedChanges}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: PendingNested/Changed
                          - $return: true
                """.formatted(childPath);
    }
}
