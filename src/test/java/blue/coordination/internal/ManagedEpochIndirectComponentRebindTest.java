package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedEpochApplicationWork;
import blue.coordination.api.ManagedEpochReceipt;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ClosureHandle;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.DrainBudget;
import blue.coordination.sdk.DrainResult;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedClosure;
import blue.coordination.sdk.ManagedDocument;
import blue.coordination.sdk.ManagedEpochEvidenceFailure;
import blue.coordination.sdk.TimelineHandle;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained cyclic-source proof failures leave catch-up exactly retryable. */
final class ManagedEpochIndirectComponentRebindTest {
    private static final String ACTOR = "alice";
    private static final DocumentId A = DocumentId.of(
            "managed-indirect-rebind-a");
    private static final DocumentId B = DocumentId.of(
            "managed-indirect-rebind-b");
    private static final DocumentId C = DocumentId.of(
            "managed-indirect-rebind-c");
    private static final String A_TIMELINE =
            "managed-indirect-rebind/a";
    private static final String B_TIMELINE =
            "managed-indirect-rebind/b";

    @Test
    void missingRetainedCyclicSourceProofWaitsWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected =
                ManagedCatchUpStatus.WAITING_FOR_HISTORY;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.NOT_FOUND,
                expected,
                ManagedEpochEvidenceFailure.Status.WAITING_FOR_HISTORY,
                ManagedEpochEvidenceException.CYCLIC_PROOF_MISSING);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void unavailableRetainedCyclicSourceProofWaitsWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected =
                ManagedCatchUpStatus.WAITING_FOR_HISTORY;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.UNAVAILABLE,
                expected,
                ManagedEpochEvidenceFailure.Status.WAITING_FOR_HISTORY,
                ManagedEpochEvidenceException.CYCLIC_PROOF_UNAVAILABLE);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void invalidRetainedCyclicSourceProofBlocksWithoutAdvancingCursor() {
        // given
        ManagedCatchUpStatus expected = ManagedCatchUpStatus.BLOCKED;

        // when
        ManagedCatchUpStatus actual = assertCyclicProofFailure(
                CyclicProofFault.INVALID_EVIDENCE,
                expected,
                ManagedEpochEvidenceFailure.Status.BLOCKED,
                ManagedEpochEvidenceException.CYCLIC_PROOF_INVALID);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void componentMergeThatWouldAdvanceSourceEpochFailsClosed() {
        try (Scenario scenario = prepared()) {
            // given
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            DurableImage before = DurableImage.capture(scenario);

            // when
            ContractsClosureAdapter.ProjectionUnavailableException failure =
                    assertThrows(
                            ContractsClosureAdapter
                                    .ProjectionUnavailableException.class,
                            engine.contractsClosureAdapter()
                                    ::processNextManagedEpochApplication);

            // then
            assertTrue(failure.getMessage().contains(
                    "cannot append or reinterpret its immutable source"));
            assertEquals(before, DurableImage.capture(scenario));
            assertEquals(scenario.work().workIdentity(),
                    documents.nextCatchUpWork().orElseThrow().workIdentity());
            engine.restartFromStores();
            assertEquals(before, DurableImage.capture(scenario));
        }
    }

    private static ManagedCatchUpStatus assertCyclicProofFailure(
            CyclicProofFault fault,
            ManagedCatchUpStatus expectedPlanStatus,
            ManagedEpochEvidenceFailure.Status expectedSdkStatus,
            String expectedCode) {
        try (Scenario scenario = prepared()) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            ManagedEpochApplicationWork work = scenario.work();
            ManagedEpochReceipt sourceReceipt = documents
                    .managedEpochEvidence(
                            work.sourceDocumentId(), work.sourceEpoch())
                    .receipt();
            String cyclicAfterBlueId = sourceReceipt.afterBlueId();
            assertTrue(BlueIds.hasCyclicMemberSeparator(cyclicAfterBlueId));
            assertEquals(NodeProviderOutcome.FOUND,
                    engine.objects().cyclicSetProofFor(
                            cyclicAfterBlueId).outcome());

            ManagedOccurrenceCatchUpPlan planBefore = documents.catchUpPlan(
                    work.planIdentity()).orElseThrow();
            DocumentSession consumerBefore = documents.require(
                    work.consumerDocumentId());
            long consumerEpochBefore = consumerBefore.epoch();
            String consumerBlueIdBefore = consumerBefore
                    .currentRepresentation().blueId();
            var occurrenceBefore = documents.occurrenceInventory().find(
                    work.consumerDocumentId(), work.targetPath())
                    .orElseThrow();
            List<String> aHistoryBefore = history(engine, A);
            List<String> bHistoryBefore = history(engine, B);
            List<String> cHistoryBefore = history(engine, C);
            List<String> aReceiptsBefore = receiptIdentities(documents, A);
            List<String> bReceiptsBefore = receiptIdentities(documents, B);
            List<String> cReceiptsBefore = receiptIdentities(documents, C);
            long processCallsBefore = counter(
                    engine, ManagedEpochApplicationExecutor.PROCESS_CALLS);
            DurableImage durableBefore = DurableImage.capture(scenario);

            applyProofFault(engine.objects(), cyclicAfterBlueId, fault);
            try {
                ManagedEpochEvidenceException direct = assertThrows(
                        ManagedEpochEvidenceException.class,
                        () -> engine.contractsClosureAdapter()
                                .executeManagedEpochApplication(work));
                assertEquals(expectedPlanStatus, direct.planStatus());
                assertEquals(expectedCode, direct.code());
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));
                assertEquals(work.workIdentity(), documents
                        .nextCatchUpWork().orElseThrow().workIdentity());
                assertEquals(durableBefore, DurableImage.capture(scenario));

                engine.restartFromStores();
                ManagedEpochEvidenceException restarted = assertThrows(
                        ManagedEpochEvidenceException.class,
                        () -> engine.contractsClosureAdapter()
                                .executeManagedEpochApplication(work));
                assertEquals(direct.code(), restarted.code());
                assertEquals(direct.planStatus(), restarted.planStatus());
                assertEquals(direct.message(), restarted.message());
                assertEquals(durableBefore, DurableImage.capture(scenario));

                DrainResult drained = scenario.coordination().processing()
                        .drain(new DrainBudget(1L, 1L));

                assertEquals(1,
                        drained.managedEpochEvidenceFailures().size());
                ManagedEpochEvidenceFailure exposed = drained
                        .managedEpochEvidenceFailures().get(0);
                assertEquals(expectedSdkStatus, exposed.status());
                assertEquals(direct.code(), exposed.code());
                assertEquals(work.workIdentity(),
                        exposed.work().workIdentity());
                assertTrue(drained.managedEpochApplications().isEmpty());
                assertTrue(drained.managedEpochApplicationAttempts()
                        .isEmpty());

                ManagedOccurrenceCatchUpPlan waiting = documents.catchUpPlan(
                        work.planIdentity()).orElseThrow();
                assertEquals(planBefore.nextSourceEpoch(),
                        waiting.nextSourceEpoch());
                assertEquals(planBefore.requiredThroughSourceEpoch(),
                        waiting.requiredThroughSourceEpoch());
                assertEquals(expectedPlanStatus, waiting.status());
                assertEquals(direct.code(),
                        waiting.waitingCode().orElseThrow());
                assertTrue(documents.nextCatchUpWork().isEmpty());
                assertTrue(documents.catchUpApplicationByWork(
                        work.workIdentity()).isEmpty());

                assertSame(consumerBefore, documents.require(
                        work.consumerDocumentId()));
                assertEquals(consumerEpochBefore,
                        documents.require(A).epoch());
                assertEquals(consumerBlueIdBefore,
                        documents.require(A).currentRepresentation().blueId());
                assertEquals(occurrenceBefore,
                        documents.occurrenceInventory().find(
                                work.consumerDocumentId(), work.targetPath())
                                .orElseThrow());
                assertEquals(aHistoryBefore, history(engine, A));
                assertEquals(bHistoryBefore, history(engine, B));
                assertEquals(cHistoryBefore, history(engine, C));
                assertEquals(aReceiptsBefore,
                        receiptIdentities(documents, A));
                assertEquals(bReceiptsBefore,
                        receiptIdentities(documents, B));
                assertEquals(cReceiptsBefore,
                        receiptIdentities(documents, C));
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));

                engine.restartFromStores();
                ManagedOccurrenceCatchUpPlan reconstructed = documents
                        .catchUpPlan(work.planIdentity()).orElseThrow();
                assertEquals(waiting.snapshotIdentity(),
                        reconstructed.snapshotIdentity());
                assertEquals(expectedCode,
                        reconstructed.waitingCode().orElseThrow());
                assertEquals(planBefore.nextSourceEpoch(),
                        reconstructed.nextSourceEpoch());
                assertEquals(planBefore.requiredThroughSourceEpoch(),
                        reconstructed.requiredThroughSourceEpoch());
                assertEquals(consumerEpochBefore,
                        documents.require(A).epoch());
                assertEquals(consumerBlueIdBefore,
                        documents.require(A).currentRepresentation().blueId());
                assertEquals(occurrenceBefore,
                        documents.occurrenceInventory().find(
                                work.consumerDocumentId(), work.targetPath())
                                .orElseThrow());
                assertEquals(aHistoryBefore, history(engine, A));
                assertEquals(bHistoryBefore, history(engine, B));
                assertEquals(cHistoryBefore, history(engine, C));
                assertEquals(aReceiptsBefore,
                        receiptIdentities(documents, A));
                assertEquals(bReceiptsBefore,
                        receiptIdentities(documents, B));
                assertEquals(cReceiptsBefore,
                        receiptIdentities(documents, C));
                assertTrue(documents.catchUpApplicationByWork(
                        work.workIdentity()).isEmpty());
                assertEquals(processCallsBefore, counter(
                        engine,
                        ManagedEpochApplicationExecutor.PROCESS_CALLS));
                return reconstructed.status();
            } finally {
                if (fault == CyclicProofFault.UNAVAILABLE) {
                    engine.objects().restoreProviderAvailability(
                            cyclicAfterBlueId);
                }
            }
        }
    }

    private static void applyProofFault(
            WholeObjectStore objects,
            String cyclicMemberBlueId,
            CyclicProofFault fault) {
        if (fault == CyclicProofFault.UNAVAILABLE) {
            objects.forceProviderUnavailable(cyclicMemberBlueId);
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
            return;
        }
        Map<String, CyclicSetProof> proofs = retainedCyclicProofs(objects);
        String masterBlueId = BlueIds.cyclicSetMasterBlueId(
                cyclicMemberBlueId);
        if (fault == CyclicProofFault.NOT_FOUND) {
            assertTrue(proofs.remove(masterBlueId) != null,
                    "the fixture must remove one retained source proof");
            assertEquals(NodeProviderOutcome.NOT_FOUND,
                    objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
            return;
        }
        CyclicSetProof invalid = CyclicSetProof.fromDeclaredPlaceholderSet(
                List.of(new Node().properties(
                        "corrupt", new Node().value(true))));
        assertTrue(proofs.put(masterBlueId, invalid) != null,
                "the fixture must replace one retained source proof");
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                objects.cyclicSetProofFor(cyclicMemberBlueId).outcome());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CyclicSetProof> retainedCyclicProofs(
            WholeObjectStore objects) {
        try {
            Field field = WholeObjectStore.class.getDeclaredField(
                    "cyclicProofByMasterBlueId");
            field.setAccessible(true);
            return (Map<String, CyclicSetProof>) field.get(objects);
        } catch (ReflectiveOperationException inaccessible) {
            throw new AssertionError(
                    "Cannot fault-inject retained cyclic proof state",
                    inaccessible);
        }
    }

    private enum CyclicProofFault {
        NOT_FOUND,
        UNAVAILABLE,
        INVALID_EVIDENCE
    }

    private static Scenario prepared() {
        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());
            ClosureHandle sourceClosure = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document("b", B, sourceYaml())
                            .document("c", C, peerYaml())
                            .bindOccurrence("b", "/peer", "c")
                            .bindOccurrence("c", "/peer", "b")
                            .publicRoot("b")
                            .fromNow()
                            .build());
            DocumentHandle b = sourceClosure.document("b");
            DocumentHandle c = sourceClosure.document("c");
            assertFalse(a.exact().cyclicMember());
            assertTrue(b.exact().cyclicMember());
            assertTrue(c.exact().cyclicMember());
            ExactBlueValue bEpochZero = b.history().get(0).after();

            EntryResult sourceAdvanced = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("attachParent")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "parent", a.snapshot().exact()))
                    .execute();
            assertEquals(EntryDisposition.APPLIED,
                    sourceAdvanced.disposition(),
                    sourceAdvanced.diagnostic().toString());
            assertEquals(1L, b.snapshot().epoch());
            assertFalse(a.exact().cyclicMember());

            EntryHandle attachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachChild")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", bEpochZero))
                    .submit();
            DrainResult attached = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(EntryDisposition.APPLIED,
                    attached.entry(attachment).disposition(),
                    attached.entry(attachment).diagnostic().toString());
            assertTrue(attached.managedEpochApplications().isEmpty());
            assertFalse(a.exact().cyclicMember(),
                    "the historical B representation has no parent edge");

            DefaultCoordinationEngine engine =
                    (DefaultCoordinationEngine) coordination.advanced()
                            .rawEngine();
            ManagedEpochApplicationWork work = engine.documents()
                    .nextCatchUpWork().orElseThrow();
            assertEquals(A, work.consumerDocumentId());
            assertEquals(B, work.sourceDocumentId());
            assertEquals(1L, work.sourceEpoch());
            return new Scenario(
                    coordination, engine, a, b, c, aTimeline, work);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static List<String> history(
            DefaultCoordinationEngine engine,
            DocumentId documentId) {
        return engine.history(documentId).stream()
                .map(revision -> revision.epoch()
                        + "|" + revision.kind()
                        + "|" + revision.before()
                                .map(value -> value.blueId())
                                .orElse("-")
                        + "|" + revision.after().blueId()
                        + "|" + revision.managedEpochReceipt()
                                .map(ManagedEpochReceipt::receiptIdentity)
                                .orElse("-"))
                .toList();
    }

    private static List<String> receiptIdentities(
            InMemoryDocumentStore documents,
            DocumentId documentId) {
        return documents.managedEpochReceipts(documentId).stream()
                .map(ManagedEpochReceipt::receiptIdentity)
                .toList();
    }

    private static long counter(
            DefaultCoordinationEngine engine,
            String name) {
        return engine.metricsSnapshot().counters().getOrDefault(name, 0L);
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                finiteReactions: 0
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
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                      - /parent
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachParent:
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
                """.formatted(B.value(), B_TIMELINE, ACTOR);
    }

    private static String peerYaml() {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                """.formatted(C.value());
    }

    private record Scenario(
            BlueCoordination coordination,
            DefaultCoordinationEngine engine,
            DocumentHandle a,
            DocumentHandle b,
            DocumentHandle c,
            TimelineHandle aTimeline,
            ManagedEpochApplicationWork work) implements AutoCloseable {
        @Override
        public void close() {
            coordination.close();
        }
    }

    private record DurableImage(
            InMemoryDocumentStore.PublicationSnapshot publication,
            List<String> aHistory,
            List<String> bHistory,
            List<String> cHistory,
            List<String> aReceipts,
            List<String> bReceipts,
            List<String> cReceipts,
            String aRepresentation,
            String bRepresentation,
            String cRepresentation,
            String planSnapshotIdentity,
            int workCount,
            int applicationCount) {
        private DurableImage {
            aHistory = List.copyOf(aHistory);
            bHistory = List.copyOf(bHistory);
            cHistory = List.copyOf(cHistory);
            aReceipts = List.copyOf(aReceipts);
            bReceipts = List.copyOf(bReceipts);
            cReceipts = List.copyOf(cReceipts);
        }

        static DurableImage capture(Scenario scenario) {
            DefaultCoordinationEngine engine = scenario.engine();
            InMemoryDocumentStore documents = engine.documents();
            CatchUpPlanStore plans = documents.catchUpPlansSnapshot();
            return new DurableImage(
                    documents.publicationSnapshot(),
                    history(engine, A),
                    history(engine, B),
                    history(engine, C),
                    receiptIdentities(documents, A),
                    receiptIdentities(documents, B),
                    receiptIdentities(documents, C),
                    documents.require(A).currentRepresentation().blueId(),
                    documents.require(B).currentRepresentation().blueId(),
                    documents.require(C).currentRepresentation().blueId(),
                    documents.catchUpPlan(
                                    scenario.work().planIdentity())
                            .orElseThrow().snapshotIdentity(),
                    plans.workCount(),
                    plans.applicationCount());
        }
    }
}
