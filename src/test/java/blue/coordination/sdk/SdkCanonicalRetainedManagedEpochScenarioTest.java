package blue.coordination.sdk;

import blue.coordination.api.CoordinationMetrics;
import blue.coordination.api.DocumentId;
import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedDocumentReadiness;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.api.SessionStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact 17-step retained-epoch scenario, including replay and equality. */
final class SdkCanonicalRetainedManagedEpochScenarioTest {
    private static final String ACTOR = "alice";
    private static final String LIFECYCLE_CHANNEL_BLUE_ID =
            "2ukJitzzDKQWHJ5EVUtn3t4FXieGmNA1NdwFSqG8qcfo";
    private static final String LIFECYCLE_EVENT_BLUE_ID =
            "Gck5z8qnbcUvJNkawzKPghj14dJBw8GxkC9mh6cL5e5C";
    private static final DocumentId A = DocumentId.of(
            "sdk-canonical-retained-a");
    private static final DocumentId B = DocumentId.of(
            "sdk-canonical-retained-b");
    private static final DocumentId B_ANCHOR = DocumentId.of(
            "sdk-canonical-retained-b-anchor");
    private static final String A_TIMELINE = "sdk/canonical-retained/a";
    private static final String B_TIMELINE = "sdk/canonical-retained/b";

    @Test
    void canonicalScenarioReplaysToExactDurableEquality() {
        // given
        // Steps 1-15 produce one accepted public-SDK execution.
        ScenarioEvidence accepted = runCanonicalScenario();

        // when
        // Step 16 replays the same accepted deterministic input program in a
        // fresh in-memory runtime; it does not claim serialized-store restore.
        ScenarioEvidence rebuilt = runCanonicalScenario();

        // then
        // Step 17 proves exact durable and audit equality.
        assertEquals(accepted, rebuilt);
    }

    private static ScenarioEvidence runCanonicalScenario() {
        try (BlueCoordination coordination = LegacyContracts10TestProfile.builder().build()) {
            TimelineHandle aTimeline = coordination.timelines().register(
                    A_TIMELINE, ACTOR);
            TimelineHandle bTimeline = coordination.timelines().register(
                    B_TIMELINE, ACTOR);
            ArrayList<ManagedEpochApplicationReceipt> applications =
                    new ArrayList<>();
            ArrayList<ManagedSurfaceEvidence.ComponentTransition>
                    componentTransitions = new ArrayList<>();

            // 1. Admit A as a public Root.
            DocumentHandle a = coordination.documents().admit(
                    ManagedDocument.yaml(A, consumerYaml())
                            .publicRoot()
                            .fromNow());

            // 2. Admit initialized B as one non-public managed document.
            ExactBlueValue authoredInitialB = coordination.values().yaml(
                    sourceYaml());
            ClosureHandle bAdmission = coordination.documents().admit(
                    ManagedClosure.builder()
                            .document(
                                    "anchor",
                                    B_ANCHOR,
                                    sourceAnchorYaml())
                            .document("source", B, sourceYaml())
                            .bindOccurrence("anchor", "/source", "source")
                            .publicRoot("anchor")
                            .fromNow()
                            .build());
            DocumentHandle b = bAdmission.document("source");
            assertEquals(0L, ((Number) authoredInitialB.scalarAt(
                    "/initializationCount")).longValue());
            assertEquals(1L, b.snapshot().longAt("/initializationCount"));
            assertEquals(0L, b.snapshot().epoch());
            ManagedEpochReceipt initializationReceipt = coordination
                    .advanced().auditManagedEpoch(B, 0L).orElseThrow();
            assertEquals(0L, initializationReceipt.epoch(),
                    "the authored -1 position first materializes as epoch 0");
            assertTrue(initializationReceipt.beforeBlueId().isEmpty(),
                    "authored selection evidence is not a prior receipt epoch");
            assertEquals(1, initializationReceipt.emittedEvents().size());
            assertThrows(IllegalArgumentException.class, () -> coordination
                    .advanced().auditManagedEpoch(B, -1L),
                    "-1 is a selector/cursor sentinel, never a receipt epoch");
            assertTrue(coordination.advanced().auditManagedEpochs(B).stream()
                    .allMatch(receipt -> receipt.epoch() >= 0L));

            // 3. Increment non-public B twice, reaching retained epoch 2.
            assertApplied(increment(coordination, b, bTimeline).execute());
            assertApplied(increment(coordination, b, bTimeline).execute());
            assertEquals(2L, b.snapshot().epoch());
            assertEquals(2L, b.snapshot().longAt("/counter"));
            assertEquals(List.of(0L, 1L, 2L), coordination.advanced()
                    .auditManagedEpochs(B).stream()
                    .map(ManagedEpochReceipt::epoch)
                    .toList());
            assertTrue(coordination.advanced().auditManagedEpochs(B).stream()
                    .allMatch(receipt -> receipt.epoch() >= 0L));

            // 4. Promote B without changing state, history, gas, or receipts.
            List<RevisionEvidence> historyBeforePromotion = history(b);
            List<ReceiptEvidence> receiptsBeforePromotion = receipts(
                    coordination, B);
            int inputsBeforePromotion = coordination.advanced()
                    .auditTimelineEntries().size();
            DocumentHandle promoted = coordination.documents()
                    .promotePublicRoot(B);
            assertEquals(B, promoted.id());
            assertEquals(historyBeforePromotion, history(b));
            assertEquals(receiptsBeforePromotion, receipts(coordination, B));
            assertEquals(inputsBeforePromotion, coordination.advanced()
                    .auditTimelineEntries().size());

            // 5. Attach B's authored initial exact value at A's first path.
            EntryHandle firstAttachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachFirst")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", authoredInitialB))
                    .submit();
            DrainResult firstAdmitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertApplied(firstAdmitted, firstAttachment);
            assertTrue(firstAdmitted.managedEpochApplications().isEmpty());

            // 6. Reuse B and expose A's committed-but-not-ready barrier.
            assertEquals(B, coordination.advanced()
                    .auditManagedOccurrence(A, "/children/first")
                    .orElseThrow().targetDocumentId());
            ManagedDocumentReadiness catchingUp = coordination.advanced()
                    .auditManagedDocumentReadiness(A).orElseThrow();
            assertEquals(SessionStatus.CATCHING_UP, catchingUp.status());
            assertFalse(catchingUp.ready());
            ManagedOccurrenceCatchUpPlan firstPlan = planAt(
                    coordination, "/children/first");
            assertEquals(-1L, firstPlan.admittedSourceEpoch());
            assertEquals(0L, firstPlan.nextSourceEpoch());
            assertEquals(2L, firstPlan.requiredThroughSourceEpoch());

            // 7. Apply B0, B1, and B2 one immutable item at a time.
            List<RevisionEvidence> sourceHistoryBeforeCatchUp = history(b);
            long sourceProcessCallsBefore = externalProcessCalls(coordination);
            for (long sourceEpoch = 0L; sourceEpoch <= 2L; sourceEpoch++) {
                DrainResult one = coordination.processing().drain(
                        new DrainBudget(1L, 1L));
                assertEquals(1, one.managedEpochApplications().size());
                ManagedEpochApplicationReceipt applied = one
                        .managedEpochApplications().get(0);
                applications.add(applied);
                assertEquals(A, applied.consumerDocumentId());
                assertEquals(sourceEpoch + 1L,
                        applied.resultingSourceCursor());
                assertEquals(coordination.advanced()
                                .auditManagedEpoch(B, sourceEpoch)
                                .orElseThrow().receiptIdentity(),
                        applied.sourceReceiptIdentity());
            }

            // 8. A received initialization and both retained change events.
            assertEquals(1L, a.snapshot().longAt(
                    "/firstInitializations"));
            assertEquals(2L, a.snapshot().longAt("/firstChanges"));
            assertEquals(0L, a.snapshot().longAt("/secondChanges"));

            // 9. A is READY and B was never reprocessed during catch-up.
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());
            assertEquals(sourceHistoryBeforeCatchUp, history(b));
            assertEquals(sourceProcessCallsBefore,
                    externalProcessCalls(coordination));

            // 10. Attach retained B epoch 1 at A's second occurrence path.
            ExactBlueValue bAtEpochOne = b.history().get(1).after();
            EntryHandle secondAttachment = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("attachSecond")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "child", bAtEpochOne))
                    .selectManagedEpoch(ManagedEpochSelector.exact(
                            B,
                            1L,
                            bAtEpochOne.blueId(),
                            "/children/second"))
                    .submit();
            DrainResult secondAdmitted = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertApplied(secondAdmitted, secondAttachment);
            assertTrue(secondAdmitted.managedEpochApplications().isEmpty());
            ManagedOccurrenceCatchUpPlan secondPlan = planAt(
                    coordination, "/children/second");
            assertEquals(1L, secondPlan.admittedSourceEpoch());
            assertEquals(2L, secondPlan.nextSourceEpoch());
            assertEquals(2L, secondPlan.requiredThroughSourceEpoch());

            // 11. Only retained B2 applies through the second occurrence.
            DrainResult secondCaughtUp = coordination.processing().drain(
                    new DrainBudget(1L, 1L));
            assertEquals(1,
                    secondCaughtUp.managedEpochApplications().size());
            ManagedEpochApplicationReceipt secondApplication =
                    secondCaughtUp.managedEpochApplications().get(0);
            applications.add(secondApplication);
            assertEquals(secondPlan.planIdentity(),
                    secondApplication.planIdentity());
            assertEquals(3L, secondApplication.resultingSourceCursor());
            assertEquals(1L, a.snapshot().longAt("/secondChanges"));
            assertTrue(coordination.advanced()
                    .auditManagedDocumentReadiness(A)
                    .orElseThrow().ready());

            // 12. B3 is processed once and delivered live through both
            // occurrences by the ordinary connected closure. Completed
            // historical plans stay complete and are not reopened as
            // managed-application work.
            long processCallsBeforeB3 = externalProcessCalls(coordination);
            int sourceHistorySizeBeforeB3 = b.history().size();
            EntryHandle bThreeEntry = increment(
                    coordination, b, bTimeline).submit();
            DrainResult bThree = coordination.processing().drain();
            assertApplied(bThree, bThreeEntry);
            assertEquals(processCallsBeforeB3 + 1L,
                    externalProcessCalls(coordination));
            assertEquals(sourceHistorySizeBeforeB3 + 1, b.history().size());
            assertEquals(3L, b.snapshot().epoch());
            assertTrue(bThree.managedEpochApplications().isEmpty());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    coordination.advanced().auditManagedCatchUpPlan(
                            firstPlan.planIdentity()).orElseThrow().status());
            assertEquals(ManagedCatchUpStatus.COMPLETE,
                    coordination.advanced().auditManagedCatchUpPlan(
                            secondPlan.planIdentity()).orElseThrow().status());
            assertEquals(3L, a.snapshot().longAt("/firstChanges"));
            assertEquals(2L, a.snapshot().longAt("/secondChanges"));
            assertEquals(3L, b.snapshot().longAt("/counter"));
            for (String path : List.of("/children/first", "/children/second")) {
                assertEquals(b.snapshot().blueId(), blue.language.model.NodePathEditor
                        .getOrNull(a.snapshot().exact().copyNode(), path).getBlueId(),
                        "the parent's exact occurrence must publish B3");
            }

            // 13. B embeds current A and the ordinary resolver forms A <-> B.
            EntryResult cycleFormed = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("attachA")
                    .through("ownerChannel")
                    .request(request -> request.exact(
                            "parent", a.snapshot().exact()))
                    .execute();
            assertApplied(cycleFormed);
            addComponentTransitions(componentTransitions, cycleFormed);
            DrainResult cycleSettled = coordination.processing().drain();
            applications.addAll(cycleSettled.managedEpochApplications());
            assertTrue(cycleSettled.quiescent());
            ManagedSurfaceEvidence.ComponentState cyclicComponent =
                    requireCyclicComponent(cycleFormed);
            assertEquals(Set.of(A, B),
                    Set.copyOf(cyclicComponent.memberDocumentIds()));
            assertCyclicTogether(a, b);

            // 14. A -> B -> A runs once as a finite cyclic event route.
            long firstRepliesBefore = a.snapshot().longAt(
                    "/firstCycleReplies");
            long secondRepliesBefore = a.snapshot().longAt(
                    "/secondCycleReplies");
            long relaysBefore = b.snapshot().longAt("/cycleRelays");
            EntryResult finiteCycle = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("startCycle")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            assertApplied(finiteCycle);
            addComponentTransitions(componentTransitions, finiteCycle);
            DrainResult finiteSettled = coordination.processing().drain();
            applications.addAll(finiteSettled.managedEpochApplications());
            assertTrue(finiteSettled.quiescent());
            assertEquals(firstRepliesBefore + 1L,
                    a.snapshot().longAt("/firstCycleReplies"));
            assertEquals(secondRepliesBefore + 1L,
                    a.snapshot().longAt("/secondCycleReplies"));
            assertEquals(relaysBefore + 1L,
                    b.snapshot().longAt("/cycleRelays"));
            assertCyclicTogether(a, b);

            // 15. Detach A from B, dissolve the cycle, and prove finite A work.
            EntryResult detached = coordination.operations()
                    .on(b)
                    .from(bTimeline)
                    .call("detachA")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            assertApplied(detached);
            addComponentTransitions(componentTransitions, detached);
            DrainResult detachSettled = coordination.processing().drain();
            applications.addAll(detachSettled.managedEpochApplications());
            assertTrue(detachSettled.quiescent());
            assertAcyclic(a, b);
            long relaysAfterDetach = b.snapshot().longAt("/cycleRelays");
            long firstRepliesAfterDetach = a.snapshot().longAt(
                    "/firstCycleReplies");
            long secondRepliesAfterDetach = a.snapshot().longAt(
                    "/secondCycleReplies");
            EntryResult acyclic = coordination.operations()
                    .on(a)
                    .from(aTimeline)
                    .call("startCycle")
                    .through("ownerChannel")
                    .request(request -> { })
                    .execute();
            assertApplied(acyclic);
            addComponentTransitions(componentTransitions, acyclic);
            assertEquals(relaysAfterDetach,
                    b.snapshot().longAt("/cycleRelays"));
            assertEquals(firstRepliesAfterDetach,
                    a.snapshot().longAt("/firstCycleReplies"));
            assertEquals(secondRepliesAfterDetach,
                    a.snapshot().longAt("/secondCycleReplies"));

            assertTrue(coordination.advanced()
                    .auditManagedOccurrence(A, "/children/first")
                    .orElseThrow().active());
            assertTrue(coordination.advanced()
                    .auditManagedOccurrence(A, "/children/second")
                    .orElseThrow().active());
            assertFalse(coordination.advanced()
                    .auditManagedOccurrence(B, "/parent")
                    .orElseThrow().active());
            assertTrue(coordination.advanced()
                    .auditManagedCatchUpPlans(A).stream()
                    .allMatch(plan -> plan.status()
                            == ManagedCatchUpStatus.COMPLETE));

            assertEquals(9, coordination.advanced()
                    .auditTimelineEntries().size());
            for (DocumentId id : List.of(A, B, B_ANCHOR)) {
                assertTrue(coordination.advanced()
                        .auditManagedDocumentReadiness(id)
                        .orElseThrow().ready());
            }
            return evidence(
                    coordination,
                    applications,
                    componentTransitions,
                    cyclicComponent);
        }
    }

    private static ScenarioEvidence evidence(
            BlueCoordination coordination,
            List<ManagedEpochApplicationReceipt> applications,
            List<ManagedSurfaceEvidence.ComponentTransition>
                    componentTransitions,
            ManagedSurfaceEvidence.ComponentState cyclicComponent) {
        return new ScenarioEvidence(
                coordination.advanced().auditTimelineEntries().stream()
                        .map(SdkCanonicalRetainedManagedEpochScenarioTest
                                ::ledgerEntry)
                        .toList(),
                List.of(
                        historyEvidence(coordination, A),
                        historyEvidence(coordination, B),
                        historyEvidence(coordination, B_ANCHOR)),
                List.of(
                        receiptStream(coordination, A),
                        receiptStream(coordination, B),
                        receiptStream(coordination, B_ANCHOR)),
                coordination.advanced().auditManagedCatchUpPlans(A).stream()
                        .map(SdkCanonicalRetainedManagedEpochScenarioTest
                                ::planEvidence)
                        .sorted(Comparator.comparing(
                                PlanEvidence::planIdentity))
                        .toList(),
                List.of(
                        occurrenceEvidence(
                                coordination, A, "/children/first"),
                        occurrenceEvidence(
                                coordination, A, "/children/second"),
                        occurrenceEvidence(
                                coordination, B, "/parent")),
                applications.stream()
                        .map(SdkCanonicalRetainedManagedEpochScenarioTest
                                ::applicationEvidence)
                        .toList(),
                List.copyOf(componentTransitions),
                componentEvidence(cyclicComponent),
                List.of(
                        documentAudit(coordination, A),
                        documentAudit(coordination, B),
                        documentAudit(coordination, B_ANCHOR)),
                List.of(
                        readinessEvidence(coordination, A),
                        readinessEvidence(coordination, B),
                        readinessEvidence(coordination, B_ANCHOR)));
    }

    private static ManagedOccurrenceCatchUpPlan planAt(
            BlueCoordination coordination,
            String targetPath) {
        return coordination.advanced().auditManagedCatchUpPlans(A).stream()
                .filter(plan -> plan.targetPath().equals(targetPath))
                .max(Comparator.comparingLong(
                        ManagedOccurrenceCatchUpPlan::activationGeneration))
                .orElseThrow();
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

    private static void assertApplied(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition(),
                result.diagnostic().toString());
    }

    private static void assertApplied(
            DrainResult result,
            EntryHandle entry) {
        assertApplied(result.entry(entry));
    }

    private static long externalProcessCalls(
            BlueCoordination coordination) {
        return coordination.advanced().rawEngine().metrics().counter(
                CoordinationMetrics.Counter.EXTERNAL_PROCESS_CALLS);
    }

    private static List<RevisionEvidence> history(DocumentHandle document) {
        return document.history().stream()
                .map(SdkCanonicalRetainedManagedEpochScenarioTest
                        ::revisionEvidence)
                .toList();
    }

    private static List<ReceiptEvidence> receipts(
            BlueCoordination coordination,
            DocumentId documentId) {
        return coordination.advanced().auditManagedEpochs(documentId).stream()
                .map(SdkCanonicalRetainedManagedEpochScenarioTest
                        ::receiptEvidence)
                .toList();
    }

    private static void addComponentTransitions(
            List<ManagedSurfaceEvidence.ComponentTransition> target,
            EntryResult result) {
        result.closures().stream()
                .map(ClosureResult::managedSurfaceEvidence)
                .flatMap(evidence -> evidence.componentTransitions().stream())
                .forEach(target::add);
    }

    private static ManagedSurfaceEvidence.ComponentState
            requireCyclicComponent(EntryResult result) {
        return result.closures().stream()
                .map(ClosureResult::managedSurfaceEvidence)
                .flatMap(evidence -> evidence.componentTransitions().stream())
                .flatMap(transition -> transition.after().stream())
                .filter(component -> component.kind()
                        == ManagedSurfaceEvidence.ComponentKind.CYCLIC)
                .filter(component -> Set.copyOf(component.memberDocumentIds())
                        .equals(Set.of(A, B)))
                .findFirst()
                .orElseThrow();
    }

    private static void assertCyclicTogether(
            DocumentHandle first,
            DocumentHandle second) {
        assertTrue(first.exact().cyclicMember());
        assertTrue(second.exact().cyclicMember());
        assertEquals(cyclicMaster(first.snapshot().blueId()),
                cyclicMaster(second.snapshot().blueId()));
    }

    private static void assertAcyclic(DocumentHandle... documents) {
        for (DocumentHandle document : documents) {
            assertFalse(document.exact().cyclicMember());
            assertFalse(document.snapshot().blueId().contains("#"));
        }
    }

    private static String cyclicMaster(String memberBlueId) {
        int separator = memberBlueId.lastIndexOf('#');
        assertTrue(separator > 0, memberBlueId);
        return memberBlueId.substring(0, separator);
    }

    private static LedgerEntryEvidence ledgerEntry(
            TimelineEntrySnapshot entry) {
        return new LedgerEntryEvidence(
                entry.blueId(),
                entry.exact().json(),
                entry.timeline().id(),
                entry.timeline().accountId(),
                entry.previousEntryBlueId().orElse(null),
                entry.operation(),
                entry.channel(),
                entry.timestampMicros(),
                entry.globalSequence(),
                entry.timelineSequence());
    }

    private static HistoryEvidence historyEvidence(
            BlueCoordination coordination,
            DocumentId documentId) {
        return new HistoryEvidence(
                documentId,
                coordination.documents().require(documentId).history().stream()
                        .map(SdkCanonicalRetainedManagedEpochScenarioTest
                                ::revisionEvidence)
                        .toList());
    }

    private static RevisionEvidence revisionEvidence(
            DocumentRevision revision) {
        return new RevisionEvidence(
                revision.epoch(),
                revision.kind(),
                revision.before().map(ExactBlueValue::blueId).orElse(null),
                revision.after().blueId(),
                revision.publicEvents().stream()
                        .map(PublicEvent::blueId)
                        .toList(),
                revision.processingGas(),
                revision.managedEpochReceipt()
                        .map(ManagedEpochReceipt::receiptIdentity)
                        .orElse(null));
    }

    private static ReceiptStreamEvidence receiptStream(
            BlueCoordination coordination,
            DocumentId documentId) {
        return new ReceiptStreamEvidence(
                documentId,
                receipts(coordination, documentId));
    }

    private static ReceiptEvidence receiptEvidence(
            ManagedEpochReceipt receipt) {
        return new ReceiptEvidence(
                receipt.receiptIdentity(),
                receipt.epoch(),
                receipt.kind(),
                receipt.beforeBlueId().orElse(null),
                receipt.afterBlueId(),
                receipt.originalCauseIdentity(),
                receipt.sourceEntry()
                        .map(TimelineEntrySnapshot::blueId)
                        .orElse(null),
                receipt.contractsTransitionReceiptIdentity(),
                receipt.commitCompanionIdentity(),
                receipt.emittedEvents().stream()
                        .map(event -> new ManagedEventEvidence(
                                event.managedEventIdentity(),
                                event.ordinal(),
                                event.eventOccurrenceOrdinal(),
                                event.eventOccurrenceIdentity(),
                                event.eventBlueId(),
                                event.publicAtSource()))
                        .toList(),
                receipt.processingGas());
    }

    private static PlanEvidence planEvidence(
            ManagedOccurrenceCatchUpPlan plan) {
        return new PlanEvidence(
                plan.planIdentity(),
                plan.snapshotIdentity(),
                plan.barrierIdentity(),
                plan.targetOccurrenceIdentity(),
                plan.targetPath(),
                plan.activationGeneration(),
                plan.sourceDocumentId(),
                plan.admittedSourceEpoch(),
                plan.admittedSourceBlueId(),
                plan.nextSourceEpoch(),
                plan.requiredThroughSourceEpoch(),
                plan.causedByIdentity(),
                plan.status(),
                plan.waitingCode().orElse(null),
                plan.waitingMessage().orElse(null));
    }

    private static OccurrenceEvidence occurrenceEvidence(
            BlueCoordination coordination,
            DocumentId owner,
            String path) {
        ManagedOccurrenceAudit occurrence = coordination.advanced()
                .auditManagedOccurrence(owner, path)
                .orElseThrow();
        return new OccurrenceEvidence(
                owner,
                path,
                occurrence.targetDocumentId(),
                occurrence.activationGeneration(),
                occurrence.active());
    }

    private static ApplicationEvidence applicationEvidence(
            ManagedEpochApplicationReceipt receipt) {
        return new ApplicationEvidence(
                receipt.applicationReceiptIdentity(),
                receipt.workIdentity(),
                receipt.planIdentity(),
                receipt.sourceReceiptIdentity(),
                receipt.contractsInvocationIdentity(),
                receipt.contractsResultIdentity(),
                receipt.commitCompanionIdentity(),
                receipt.consumerDocumentId(),
                receipt.consumerRevisionEpoch(),
                receipt.consumerRevisionReceiptIdentity(),
                receipt.consumerCommittedBlueId(),
                receipt.resultingSourceCursor());
    }

    private static ComponentEvidence componentEvidence(
            ManagedSurfaceEvidence.ComponentState component) {
        return new ComponentEvidence(
                component.componentIdentity(),
                component.componentStateIdentity(),
                component.componentGeneration(),
                component.kind(),
                component.memberDocumentIds(),
                component.memberBlueIds(),
                component.masterBlueId().orElse(null),
                component.cyclicProofIdentity().orElse(null));
    }

    private static DocumentAuditEvidence documentAudit(
            BlueCoordination coordination,
            DocumentId documentId) {
        blue.coordination.api.DocumentSnapshot snapshot = coordination
                .advanced().auditDocument(documentId);
        return new DocumentAuditEvidence(
                documentId,
                snapshot.epoch(),
                snapshot.status(),
                snapshot.authoredInitialBlueId(),
                snapshot.blueId(),
                snapshot.current().isCyclicMember(),
                snapshot.embeddedChildren().entrySet().stream()
                        .map(entry -> entry.getKey() + "="
                                + entry.getValue().value())
                        .sorted()
                        .toList(),
                snapshot.processingRootBlueId());
    }

    private static ReadinessEvidence readinessEvidence(
            BlueCoordination coordination,
            DocumentId documentId) {
        ManagedDocumentReadiness readiness = coordination.advanced()
                .auditManagedDocumentReadiness(documentId)
                .orElseThrow();
        return new ReadinessEvidence(
                readiness.readinessIdentity(),
                documentId,
                readiness.committedEpoch(),
                readiness.committedBlueId(),
                readiness.readyEpoch().orElse(-1L),
                readiness.readyBlueId().orElse(null),
                readiness.status(),
                readiness.waitingCode().orElse(null),
                readiness.waitingMessage().orElse(null),
                readiness.activeBarrierIdentities());
    }

    private static String sourceAnchorYaml() {
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /source
                """.formatted(B_ANCHOR.value());
    }

    private static String sourceYaml() {
        return """
                documentId: %s
                initializationCount: 0
                counter: 0
                cycleRelays: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /parent
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
                              kind: Canonical/B Initialized
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
                              kind: Canonical/B Changed
                          - $return: true
                  attachA:
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
                  detachA:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /parent}
                          - $return: true
                  fromCycleStart:
                    type: Embedded Node Channel
                    sourcePath: /parent
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Start
                  relayCycle:
                    type: Coordination/Sequential Workflow
                    channel: fromCycleStart
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Start
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /cycleRelays
                              val: {$add: [{$document: /cycleRelays}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Canonical/Cycle Reply
                          - $return: true
                """.formatted(
                B.value(),
                LIFECYCLE_CHANNEL_BLUE_ID,
                LIFECYCLE_EVENT_BLUE_ID,
                B_TIMELINE,
                ACTOR);
    }

    private static String consumerYaml() {
        return """
                documentId: %s
                children: {}
                firstInitializations: 0
                firstChanges: 0
                secondChanges: 0
                cycleStarts: 0
                firstCycleReplies: 0
                secondCycleReplies: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /children
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  attachFirst:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/first
                              val: {$binding: event/message/request/child}
                          - $return: true
                  attachSecond:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      child: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /children/second
                              val: {$binding: event/message/request/child}
                          - $return: true
                  startCycle:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /cycleStarts
                              val: {$add: [{$document: /cycleStarts}, 1]}
                          - $appendEvent:
                              type: Coordination/Event
                              kind: Canonical/Cycle Start
                          - $return: true
                  fromFirstInitialized:
                    type: Embedded Node Channel
                    sourcePath: /children/first
                    event:
                      type: Coordination/Event
                      kind: Canonical/B Initialized
                  observeFirstInitialized:
                    type: Coordination/Sequential Workflow
                    channel: fromFirstInitialized
                    event:
                      type: Coordination/Event
                      kind: Canonical/B Initialized
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /firstInitializations
                              val: {$add: [{$document: /firstInitializations}, 1]}
                          - $return: true
                  fromFirstChanged:
                    type: Embedded Node Channel
                    sourcePath: /children/first
                    event:
                      type: Coordination/Event
                      kind: Canonical/B Changed
                  observeFirstChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromFirstChanged
                    event:
                      type: Coordination/Event
                      kind: Canonical/B Changed
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
                      kind: Canonical/B Changed
                  observeSecondChanged:
                    type: Coordination/Sequential Workflow
                    channel: fromSecondChanged
                    event:
                      type: Coordination/Event
                      kind: Canonical/B Changed
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /secondChanges
                              val: {$add: [{$document: /secondChanges}, 1]}
                          - $return: true
                  fromFirstCycleReply:
                    type: Embedded Node Channel
                    sourcePath: /children/first
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Reply
                  observeFirstCycleReply:
                    type: Coordination/Sequential Workflow
                    channel: fromFirstCycleReply
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Reply
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /firstCycleReplies
                              val: {$add: [{$document: /firstCycleReplies}, 1]}
                          - $return: true
                  fromSecondCycleReply:
                    type: Embedded Node Channel
                    sourcePath: /children/second
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Reply
                  observeSecondCycleReply:
                    type: Coordination/Sequential Workflow
                    channel: fromSecondCycleReply
                    event:
                      type: Coordination/Event
                      kind: Canonical/Cycle Reply
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /secondCycleReplies
                              val: {$add: [{$document: /secondCycleReplies}, 1]}
                          - $return: true
                """.formatted(A.value(), A_TIMELINE, ACTOR);
    }

    private record ScenarioEvidence(
            List<LedgerEntryEvidence> ledger,
            List<HistoryEvidence> histories,
            List<ReceiptStreamEvidence> receipts,
            List<PlanEvidence> plans,
            List<OccurrenceEvidence> cursors,
            List<ApplicationEvidence> applications,
            List<ManagedSurfaceEvidence.ComponentTransition>
                    componentTransitions,
            ComponentEvidence cyclicComponent,
            List<DocumentAuditEvidence> documents,
            List<ReadinessEvidence> readiness) {
        private ScenarioEvidence {
            ledger = List.copyOf(ledger);
            histories = List.copyOf(histories);
            receipts = List.copyOf(receipts);
            plans = List.copyOf(plans);
            cursors = List.copyOf(cursors);
            applications = List.copyOf(applications);
            componentTransitions = List.copyOf(componentTransitions);
            documents = List.copyOf(documents);
            readiness = List.copyOf(readiness);
        }
    }

    private record LedgerEntryEvidence(
            String blueId,
            String exactJson,
            String timelineId,
            String accountId,
            String previousEntryBlueId,
            String operation,
            String channel,
            long timestampMicros,
            long globalSequence,
            long timelineSequence) { }

    private record HistoryEvidence(
            DocumentId documentId,
            List<RevisionEvidence> revisions) {
        private HistoryEvidence {
            revisions = List.copyOf(revisions);
        }
    }

    private record RevisionEvidence(
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            List<String> publicEventBlueIds,
            long processingGas,
            String managedReceiptIdentity) {
        private RevisionEvidence {
            publicEventBlueIds = List.copyOf(publicEventBlueIds);
        }
    }

    private record ReceiptStreamEvidence(
            DocumentId documentId,
            List<ReceiptEvidence> receipts) {
        private ReceiptStreamEvidence {
            receipts = List.copyOf(receipts);
        }
    }

    private record ReceiptEvidence(
            String receiptIdentity,
            long epoch,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            String originalCauseIdentity,
            String sourceEntryBlueId,
            String contractsTransitionReceiptIdentity,
            String commitCompanionIdentity,
            List<ManagedEventEvidence> events,
            long processingGas) {
        private ReceiptEvidence {
            events = List.copyOf(events);
        }
    }

    private record ManagedEventEvidence(
            String managedEventIdentity,
            long ordinal,
            long eventOccurrenceOrdinal,
            String eventOccurrenceIdentity,
            String eventBlueId,
            boolean publicAtSource) { }

    private record PlanEvidence(
            String planIdentity,
            String snapshotIdentity,
            String barrierIdentity,
            String targetOccurrenceIdentity,
            String targetPath,
            long activationGeneration,
            DocumentId sourceDocumentId,
            long admittedSourceEpoch,
            String admittedSourceBlueId,
            long nextSourceEpoch,
            long requiredThroughSourceEpoch,
            String causedByIdentity,
            ManagedCatchUpStatus status,
            String waitingCode,
            String waitingMessage) { }

    private record OccurrenceEvidence(
            DocumentId ownerDocumentId,
            String targetPath,
            DocumentId targetDocumentId,
            long activationGeneration,
            boolean active) { }

    private record ApplicationEvidence(
            String applicationReceiptIdentity,
            String workIdentity,
            String planIdentity,
            String sourceReceiptIdentity,
            String contractsInvocationIdentity,
            String contractsResultIdentity,
            String commitCompanionIdentity,
            DocumentId consumerDocumentId,
            long consumerRevisionEpoch,
            String consumerRevisionReceiptIdentity,
            String consumerCommittedBlueId,
            long resultingSourceCursor) { }

    private record ComponentEvidence(
            String componentIdentity,
            String componentStateIdentity,
            long componentGeneration,
            ManagedSurfaceEvidence.ComponentKind kind,
            List<DocumentId> memberDocumentIds,
            List<String> memberBlueIds,
            String masterBlueId,
            String cyclicProofIdentity) {
        private ComponentEvidence {
            memberDocumentIds = List.copyOf(memberDocumentIds);
            memberBlueIds = List.copyOf(memberBlueIds);
        }
    }

    private record DocumentAuditEvidence(
            DocumentId documentId,
            long epoch,
            SessionStatus status,
            String authoredInitialBlueId,
            String currentBlueId,
            boolean cyclicMember,
            List<String> embeddedChildren,
            String processingRootBlueId) {
        private DocumentAuditEvidence {
            embeddedChildren = List.copyOf(embeddedChildren);
        }
    }

    private record ReadinessEvidence(
            String readinessIdentity,
            DocumentId documentId,
            long committedEpoch,
            String committedBlueId,
            long readyEpoch,
            String readyBlueId,
            SessionStatus status,
            String waitingCode,
            String waitingMessage,
            List<String> activeBarrierIdentities) {
        private ReadinessEvidence {
            activeBarrierIdentities = List.copyOf(activeBarrierIdentities);
        }
    }
}
