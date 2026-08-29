package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-SDK-only acceptance of the bundled Contracts 1.0 runtime. */
final class SdkAcceptanceTest {
    private static final String ACTOR = "alice";

    @Test
    void counterAppliesPlusThreeThenMinusOne() {
        // given
        String timelineId = "sdk/counter/alice";
        DocumentId counterId = DocumentId.of("sdk-counter");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    counterId,
                                    counterDocument(counterId, timelineId))
                            .publicRoot()
                            .fromNow());

            // when
            EntryResult increment = coordination.operations().on(counter)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .execute();
            EntryResult decrement = coordination.operations().on(counter)
                    .from(timeline)
                    .call("decrement")
                    .through("ownerChannel")
                    .requestYaml("amount: 1")
                    .execute();

            // then
            assertApplied(increment, counterId);
            assertApplied(decrement, counterId);
            assertEquals(2L, counter.snapshot().longAt("/counter"));
            assertEquals(2L, counter.snapshot().epoch());
            assertEquals(List.of(0L, 1L, 2L), counter.history().stream()
                    .map(DocumentRevision::epoch)
                    .toList());
            assertTrue(increment.stats().gas() > 0L);
            assertTrue(decrement.stats().gas() > 0L);
            assertEquals(List.of(counterId),
                    increment.stats().documentStepOrder());
            assertEquals(List.of(counterId),
                    decrement.stats().documentStepOrder());
        }
    }

    @Test
    void fullHistoryAdmissionIsReadyBeforeALaterDrainReplaysHistory() {
        // given
        String timelineId = "sdk/history/full/alice";
        DocumentId counterId = DocumentId.of("sdk-history-full-counter");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ExactBlueValue firstEvent = providerIncrement(
                    coordination, timelineId, 2_100_000_000_001_001L,
                    1L, null);
            EntryHandle first = coordination.events().from(timeline)
                    .exact(firstEvent)
                    .submit();
            ExactBlueValue secondEvent = providerIncrement(
                    coordination, timelineId, 2_100_000_000_001_002L,
                    2L, first.blueId());
            EntryHandle second = coordination.events().from(timeline)
                    .exact(secondEvent)
                    .submit();

            // when
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    counterId,
                                    counterDocument(counterId, timelineId))
                            .publicRoot()
                            .activation(ActivationPolicy
                                    .importFullHistory()));
            DocumentSnapshot initialized = counter.snapshot();
            DrainResult drained = coordination.processing().drain();

            // then
            assertTrue(initialized.ready());
            assertEquals(0L, initialized.epoch());
            assertEquals(0L, initialized.longAt("/counter"));
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(first).disposition());
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(second).disposition());
            assertTrue(drained.quiescent());
            assertEquals(3L, counter.snapshot().longAt("/counter"));
            assertEquals(2L, counter.snapshot().epoch());
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.TIMELINE_ENTRY),
                    counter.history().stream()
                            .map(DocumentRevision::kind)
                            .toList());
            assertEquals(List.of(first, second), counter.history().stream()
                    .skip(1L)
                    .map(revision -> revision.sourceEntry().orElseThrow())
                    .toList());
        }
    }

    @Test
    void frontierAdmissionReplaysOnlyEntriesStrictlyAfterExactEvidence() {
        // given
        String timelineId = "sdk/history/frontier/alice";
        DocumentId counterId = DocumentId.of(
                "sdk-history-frontier-counter");
        long firstTimestamp = 2_100_000_000_002_001L;
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            EntryHandle first = coordination.events().from(timeline)
                    .exact(providerIncrement(
                            coordination,
                            timelineId,
                            firstTimestamp,
                            1L,
                            null))
                    .submit();
            EntryHandle second = coordination.events().from(timeline)
                    .exact(providerIncrement(
                            coordination,
                            timelineId,
                            firstTimestamp + 1L,
                            2L,
                            first.blueId()))
                    .submit();
            EntryHandle third = coordination.events().from(timeline)
                    .exact(providerIncrement(
                            coordination,
                            timelineId,
                            firstTimestamp + 2L,
                            3L,
                            second.blueId()))
                    .submit();
            ExactBlueValue frontierEvidence = coordination.values().yaml("""
                    components:
                      - %d
                      - %s
                      - %s
                    """.formatted(
                    firstTimestamp, timelineId, first.blueId()));

            // when
            DocumentHandle counter = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    counterId,
                                    counterDocument(counterId, timelineId))
                            .publicRoot()
                            .activation(ActivationPolicy
                                    .importFromFrontier(frontierEvidence)));
            DocumentSnapshot initialized = counter.snapshot();
            DrainResult drained = coordination.processing().drain();

            // then
            assertTrue(initialized.ready());
            assertEquals(0L, initialized.epoch());
            assertEquals(0L, initialized.longAt("/counter"));
            assertEquals(EntryDisposition.NO_MATCH,
                    drained.entry(first).disposition());
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(second).disposition());
            assertEquals(EntryDisposition.APPLIED,
                    drained.entry(third).disposition());
            assertTrue(drained.quiescent());
            assertEquals(5L, counter.snapshot().longAt("/counter"));
            assertEquals(2L, counter.snapshot().epoch());
            assertEquals(List.of(second, third), counter.history().stream()
                    .skip(1L)
                    .map(revision -> revision.sourceEntry().orElseThrow())
                    .toList());
        }
    }

    @Test
    void exactOrderTargetDoesNotProcessStandalonePayNote() {
        // given
        String timelineId = "sdk/targeting/alice";
        DocumentId orderId = DocumentId.of("sdk-order");
        DocumentId payNoteId = DocumentId.of("sdk-paynote");
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle order = coordination.documents().admit(
                    ManagedDocument.yaml(orderId,
                                    targetedDocument(orderId, timelineId))
                            .publicRoot()
                            .fromNow());
            DocumentHandle payNote = coordination.documents().admit(
                    ManagedDocument.yaml(payNoteId,
                                    targetedDocument(payNoteId, timelineId))
                            .publicRoot()
                            .fromNow());
            String payNoteBefore = payNote.snapshot().blueId();

            // when
            EntryResult result = coordination.operations().on(order)
                    .from(timeline)
                    .call("markProcessed")
                    .through("ownerChannel")
                    .execute();

            // then
            assertApplied(result, orderId);
            assertEquals(1L, order.snapshot().longAt("/processed"));
            assertEquals(0L, payNote.snapshot().longAt("/processed"));
            assertEquals(0L, payNote.snapshot().epoch());
            assertEquals(payNoteBefore, payNote.snapshot().blueId());
            assertEquals(Set.of(orderId), changedDocuments(result));
        }
    }

    @Test
    void validBroadcastWithNoAcceptingChannelIsTerminalNoMatch() {
        // given
        String timelineId = "sdk/no-match/source";
        String accountId = "outsider";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, accountId);
            ExactBlueValue event = coordination.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: 2100000000000001
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                    message:
                      type: Coordination/Operation Request
                      operation: orphanFact
                      channel: outsideChannel
                      request: {fact: valid-but-unmatched}
                    """.formatted(timelineId, accountId));

            // when
            EntryResult result = coordination.events().from(timeline)
                    .exact(event)
                    .execute();

            // then
            assertEquals(EntryDisposition.NO_MATCH, result.disposition());
            assertTrue(result.closures().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(ProcessingStats.zero(), result.stats());
            assertFalse(result.diagnostic().present());
        }
    }

    @Test
    void missingExactTargetIsRejectedWithPreciseDiagnostic() {
        // given
        DocumentId missingId = DocumentId.of("sdk-missing-target");
        String timelineId = "sdk/missing/alice";
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);

            // when
            EntryResult result = coordination.operations().on(missingId)
                    .from(timeline)
                    .call("advance")
                    .through("ownerChannel")
                    .execute();

            // then
            assertEquals(EntryDisposition.REJECTED,
                    result.disposition());
            assertEquals("TARGET_DOCUMENT_NOT_FOUND",
                    result.diagnostic().code());
            assertEquals(missingId.value(),
                    result.diagnostic().details().get("documentId"));
            assertEquals("advance",
                    result.diagnostic().details().get("operation"));
            assertEquals("ownerChannel",
                    result.diagnostic().details().get("channel"));
            assertTrue(result.closures().isEmpty());
            assertEquals(0L, result.stats().gas());
        }
    }

    @Test
    void finiteTwoMemberCycleReportsExactPublicEvidence() {
        // given
        String prefix = "sdk-two-ring";
        List<String> expectedStepOrder = List.of(
                "sdk-two-ring-0", "sdk-two-ring-1", "sdk-two-ring-0");
        try (FiniteRingFixture fixture = finiteRingFixture(prefix, 2)) {
            String initialMaster = assertCyclicComponent(
                    fixture.handles(), fixture.ids());
            fixture.handles().values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));

            // when
            EntryResult result = executeFiniteRing(fixture);

            // then
            assertFiniteRingResult(
                    fixture, result, initialMaster,
                    expectedStepOrder, 1_364L);
        }
    }

    @Test
    void finiteThreeMemberCycleReportsExactPublicEvidence() {
        // given
        String prefix = "sdk-three-ring";
        List<String> expectedStepOrder = List.of(
                "sdk-three-ring-0", "sdk-three-ring-1",
                "sdk-three-ring-2", "sdk-three-ring-0");
        try (FiniteRingFixture fixture = finiteRingFixture(prefix, 3)) {
            String initialMaster = assertCyclicComponent(
                    fixture.handles(), fixture.ids());
            fixture.handles().values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));

            // when
            EntryResult result = executeFiniteRing(fixture);

            // then
            assertFiniteRingResult(
                    fixture, result, initialMaster,
                    expectedStepOrder, 1_787L);
        }
    }

    @Test
    void fiveMemberSharedAnchorCyclePreservesExactStepOrder() {
        // given
        DocumentId a = DocumentId.of("sdk-branch-a");
        DocumentId b1 = DocumentId.of("sdk-branch-b1");
        DocumentId b2 = DocumentId.of("sdk-branch-b2");
        DocumentId c1 = DocumentId.of("sdk-branch-c1");
        DocumentId c2 = DocumentId.of("sdk-branch-c2");
        List<DocumentId> members = List.of(a, b1, b2, c1, c2);
        String timelineId = "sdk/branching/shared";
        ManagedClosure closure = ManagedClosure.builder()
                .document("a", a, branchingA(a, timelineId))
                .document("b1", b1,
                        branchingB(b1, "branch-c1", "branch-result-1"))
                .document("b2", b2,
                        branchingB(b2, "branch-c2", "branch-result-2"))
                .document("c1", c1,
                        branchingC(c1, "branch-start-1", "branch-c1"))
                .document("c2", c2,
                        branchingC(c2, "branch-start-2", "branch-c2"))
                .bindOccurrence("a", "/branches/b1", "b1")
                .bindOccurrence("b1", "/child", "c1")
                .bindOccurrence("c1", "/root", "a")
                .bindOccurrence("a", "/branches/b2", "b2")
                .bindOccurrence("b2", "/child", "c2")
                .bindOccurrence("c2", "/root", "a")
                .publicRoot("a")
                .fromNow()
                .build();

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle admitted = coordination.documents().admit(closure);
            Map<DocumentId, DocumentHandle> handles = handles(admitted);
            Map<DocumentId, String> before = blueIds(handles);
            String initialMaster = assertCyclicComponent(handles, members);
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));

            // when
            EntryResult result = coordination.operations()
                    .on(admitted.document("a"))
                    .from(timeline)
                    .call("start")
                    .through("ownerChannel")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(),
                    "initialMaster=" + initialMaster + ", before=" + before
                            + ", diagnostic=" + result.diagnostic());
            assertEquals(1, result.closures().size());
            assertEquals(List.of(a, c1, b1, a, c2, b2, a),
                    result.stats().documentStepOrder());
            assertExactPublicEvents(
                    coordination,
                    result.publicEvents(),
                    List.of(
                            new ExpectedPublicEvent(a, "branch-start-1"),
                            new ExpectedPublicEvent(a, "branch-ack"),
                            new ExpectedPublicEvent(a, "branch-start-2"),
                            new ExpectedPublicEvent(a, "branch-ack"),
                            new ExpectedPublicEvent(a, "branching-done")));
            assertAllExactEvidence(
                    result,
                    handles,
                    List.of(members),
                    before,
                    1L,
                    3_768L);
            assertNotEquals(initialMaster,
                    assertCyclicComponent(handles, members));
            assertEquals("done",
                    admitted.document("a").snapshot().textAt("/phase"));
            assertEquals("done",
                    admitted.document("a").snapshot().textAt("/branch1"));
            assertEquals("done",
                    admitted.document("a").snapshot().textAt("/branch2"));
            assertEquals("contributed", admitted.document("b1")
                    .snapshot().textAt("/phase"));
            assertEquals("contributed", admitted.document("b2")
                    .snapshot().textAt("/phase"));
            assertEquals("observed", admitted.document("c1")
                    .snapshot().textAt("/phase"));
            assertEquals("observed", admitted.document("c2")
                    .snapshot().textAt("/phase"));
        }
    }

    @Test
    void oneBroadcastPreservesTwoDisconnectedCycleResults() {
        // given
        DocumentId a1 = DocumentId.of("sdk-disjoint-a1");
        DocumentId b1 = DocumentId.of("sdk-disjoint-b1");
        DocumentId a2 = DocumentId.of("sdk-disjoint-a2");
        DocumentId b2 = DocumentId.of("sdk-disjoint-b2");
        String timelineId = "sdk/disjoint/shared";
        ManagedClosure closure = ManagedClosure.builder()
                .document("a1", a1,
                        disjointA(a1, timelineId, "disjoint-one"))
                .document("b1", b1,
                        disjointB(b1, "disjoint-one"))
                .document("a2", a2,
                        disjointA(a2, timelineId, "disjoint-two"))
                .document("b2", b2,
                        disjointB(b2, "disjoint-two"))
                .bindOccurrence("a1", "/peer", "b1")
                .bindOccurrence("b1", "/peer", "a1")
                .bindOccurrence("a2", "/peer", "b2")
                .bindOccurrence("b2", "/peer", "a2")
                .publicRoot("a1")
                .publicRoot("a2")
                .fromNow()
                .build();

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle admitted = coordination.documents().admit(closure);
            Map<DocumentId, DocumentHandle> handles = handles(admitted);
            Map<DocumentId, String> before = blueIds(handles);
            String initialFirstMaster = assertCyclicComponent(
                    handles, List.of(a1, b1));
            String initialSecondMaster = assertCyclicComponent(
                    handles, List.of(a2, b2));
            assertNotEquals(initialFirstMaster, initialSecondMaster);
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));
            ExactBlueValue event = coordination.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: 2100000000000101
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                    message:
                      type: Coordination/Operation Request
                      operation: start
                      channel: sharedChannel
                      request: {}
                    """.formatted(timelineId, ACTOR));

            // when
            EntryResult result = coordination.events().from(timeline)
                    .exact(event)
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertEquals(2, result.closures().size());
            assertTrue(result.closures().stream().allMatch(
                    ClosureResult::applied));
            assertEquals(List.of(
                            Set.of(a1, b1),
                            Set.of(a2, b2)),
                    result.closures().stream()
                            .map(closureResult -> closureResult.changes()
                                    .stream()
                                    .map(DocumentChange::documentId)
                                    .collect(Collectors.toUnmodifiableSet()))
                            .toList());
            assertEquals(List.of(a1, b1, a1, a2, b2, a2),
                    result.stats().documentStepOrder());
            assertAllExactEvidence(
                    result,
                    handles,
                    List.of(List.of(a1, b1), List.of(a2, b2)),
                    before,
                    1L,
                    2_746L);
            assertEquals(List.of(a1, b1, a1), result.closures()
                    .get(0).stats().documentStepOrder());
            assertEquals(List.of(a2, b2, a2), result.closures()
                    .get(1).stats().documentStepOrder());
            assertExactGas(result.closures().get(0).stats(), 1_373L);
            assertExactGas(result.closures().get(1).stats(), 1_373L);
            assertExactPublicEvents(
                    coordination,
                    result.closures().get(0).publicEvents(),
                    List.of(new ExpectedPublicEvent(
                            a1, "disjoint-one-start")));
            assertExactPublicEvents(
                    coordination,
                    result.closures().get(1).publicEvents(),
                    List.of(new ExpectedPublicEvent(
                            a2, "disjoint-two-start")));
            assertExactPublicEvents(
                    coordination,
                    result.publicEvents(),
                    List.of(
                            new ExpectedPublicEvent(
                                    a1, "disjoint-one-start"),
                            new ExpectedPublicEvent(
                                    a2, "disjoint-two-start")));
            String firstMaster = assertCyclicComponent(
                    handles, List.of(a1, b1));
            String secondMaster = assertCyclicComponent(
                    handles, List.of(a2, b2));
            assertNotEquals(initialFirstMaster, firstMaster);
            assertNotEquals(initialSecondMaster, secondMaster);
            assertNotEquals(firstMaster, secondMaster);
        }
    }

    @Test
    void gasLoopRollsBackAndIsExactlyRepeatable() {
        // given
        List<DocumentId> expectedOrder = expectedAlternatingLoopOrder(
                DocumentId.of("sdk-gas-loop-a"),
                DocumentId.of("sdk-gas-loop-b"),
                742);

        // when
        GasLoopEvidence first = runGasLoop();
        GasLoopEvidence retry = runGasLoop();

        // then
        assertEquals(first, retry);
        assertEquals(expectedOrder, first.documentStepOrder());
        assertEquals(99_967L, first.gas());
    }

    @Test
    void detachBreaksTheLoopAndTheLaterCallTerminates() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            DynamicLoop scenario = admitDynamicLoop(
                    coordination, "sdk-detach");
            Map<DocumentId, DocumentHandle> handles = dynamicHandles(
                    scenario);
            Map<DocumentId, String> initial = blueIds(handles);
            String initialMaster = assertCyclicComponent(
                    handles, List.of(scenario.a().id(), scenario.b().id()));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));

            // when
            EntryResult rejected = coordination.operations()
                    .on(scenario.a())
                    .from(scenario.signalTimeline())
                    .call("startLoop")
                    .through("signalChannel")
                    .execute();

            // then
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    rejected.disposition());
            assertEquals(1, rejected.closures().size());
            assertEquals(rejected.stats(),
                    rejected.closures().get(0).stats());
            assertTrue(rejected.diagnostic().present());
            assertTrue(rejected.closures().get(0).changes().isEmpty());
            assertTrue(rejected.publicEvents().isEmpty());
            assertTrue(rejected.closures().get(0).publicEvents().isEmpty());
            assertEquals(0L, rejected.stats().committedTransitions());
            assertEquals(2L, rejected.stats().documentsOpened());
            assertEquals(expectedAlternatingLoopOrder(
                            scenario.a().id(), scenario.b().id(), 712),
                    rejected.stats().documentStepOrder());
            assertExactGas(rejected.stats(), 99_997L);
            assertEquals(initial, blueIds(handles));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));
            assertEquals(initialMaster, assertCyclicComponent(
                    handles, List.of(scenario.a().id(), scenario.b().id())));

            EntryResult detached = coordination.operations()
                    .on(scenario.b())
                    .from(scenario.controlTimeline())
                    .call("detach")
                    .through("controlChannel")
                    .execute();

            assertEquals(EntryDisposition.APPLIED,
                    detached.disposition());
            assertSingleAppliedClosure(detached);
            assertEquals(1, detached.closures().size());
            assertEquals(detached.stats(), detached.closures().get(0).stats());
            assertEquals(List.of(scenario.b().id()),
                    detached.stats().documentStepOrder());
            assertEquals(2L, detached.stats().committedTransitions());
            assertEquals(2L, detached.stats().documentsOpened());
            assertExactGas(detached.stats(), 736L);
            assertTrue(detached.publicEvents().isEmpty());
            assertExactChangeEvidence(
                    detached,
                    handles,
                    initial,
                    Map.of(
                            scenario.a().id(), 1L,
                            scenario.b().id(), 1L));
            assertAcyclicMembers(handles.values());
            Map<DocumentId, String> afterDetach = blueIds(handles);
            assertNotEquals(initial.get(scenario.a().id()),
                    afterDetach.get(scenario.a().id()));
            assertNotEquals(initial.get(scenario.b().id()),
                    afterDetach.get(scenario.b().id()));
            assertFalse(coordination.advanced()
                    .auditDocument(scenario.b().id())
                    .embeddedChildren().containsKey("/peer"));

            EntryResult accepted = coordination.operations()
                    .on(scenario.a())
                    .from(scenario.signalTimeline())
                    .call("startLoop")
                    .through("signalChannel")
                    .execute();

            assertEquals(EntryDisposition.APPLIED,
                    accepted.disposition());
            assertSingleAppliedClosure(accepted);
            assertEquals(List.of(scenario.a().id()),
                    accepted.stats().documentStepOrder());
            assertEquals(1L, accepted.stats().committedTransitions());
            assertEquals(2L, accepted.stats().documentsOpened());
            assertExactGas(accepted.stats(), 707L);
            assertExactPublicEvents(
                    coordination,
                    accepted.publicEvents(),
                    List.of(new ExpectedPublicEvent(
                            scenario.a().id(), "LOOP")));
            assertExactChangeEvidence(
                    accepted,
                    Map.of(scenario.a().id(), scenario.a()),
                    afterDetach,
                    Map.of(scenario.a().id(), 2L));
            assertEquals(afterDetach.get(scenario.b().id()),
                    scenario.b().snapshot().blueId());
            assertCurrentHistory(scenario.b(), 1L);
            assertAcyclicMembers(handles.values());
            assertEquals(1L,
                    scenario.a().snapshot().longAt("/loopStarts"));
            assertTrue(accepted.stats().gas() < rejected.stats().gas());
        }
    }

    @Test
    void removeAndReaddProducesFreshAuthenticatedCycleIdentity() {
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            DynamicLoop scenario = admitDynamicLoop(
                    coordination, "sdk-reactivation");
            Map<DocumentId, DocumentHandle> handles = dynamicHandles(
                    scenario);
            Map<DocumentId, String> initial = blueIds(handles);
            String initialMaster = assertCyclicComponent(
                    handles, List.of(scenario.a().id(), scenario.b().id()));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));
            assertEquals(scenario.a().id(), coordination.advanced()
                    .auditDocument(scenario.b().id())
                    .embeddedChildren().get("/peer"));
            assertManagedOccurrence(
                    coordination.advanced()
                            .auditManagedOccurrence(
                                    scenario.b().id(), "/peer")
                            .orElseThrow(),
                    scenario.a().id(),
                    1L,
                    true);

            // when
            EntryResult detached = coordination.operations()
                    .on(scenario.b())
                    .from(scenario.controlTimeline())
                    .call("detach")
                    .through("controlChannel")
                    .execute();

            // then
            assertEquals(EntryDisposition.APPLIED,
                    detached.disposition());
            assertSingleAppliedClosure(detached);
            assertEquals(List.of(scenario.b().id()),
                    detached.stats().documentStepOrder());
            assertEquals(2L, detached.stats().committedTransitions());
            assertEquals(2L, detached.stats().documentsOpened());
            assertExactGas(detached.stats(), 736L);
            assertTrue(detached.publicEvents().isEmpty());
            assertExactChangeEvidence(
                    detached,
                    handles,
                    initial,
                    Map.of(
                            scenario.a().id(), 1L,
                            scenario.b().id(), 1L));
            assertAcyclicMembers(handles.values());
            Map<DocumentId, String> afterDetach = blueIds(handles);
            assertManagedOccurrence(
                    coordination.advanced()
                            .auditManagedOccurrence(
                                    scenario.b().id(), "/peer")
                            .orElseThrow(),
                    scenario.a().id(),
                    2L,
                    false);

            EntryResult finite = coordination.operations()
                    .on(scenario.a())
                    .from(scenario.signalTimeline())
                    .call("startLoop")
                    .through("signalChannel")
                    .execute();
            assertEquals(EntryDisposition.APPLIED, finite.disposition());
            assertSingleAppliedClosure(finite);
            assertEquals(List.of(scenario.a().id()),
                    finite.stats().documentStepOrder());
            assertEquals(1L, finite.stats().committedTransitions());
            assertEquals(2L, finite.stats().documentsOpened());
            assertExactGas(finite.stats(), 707L);
            assertExactPublicEvents(
                    coordination,
                    finite.publicEvents(),
                    List.of(new ExpectedPublicEvent(
                            scenario.a().id(), "LOOP")));
            assertExactChangeEvidence(
                    finite,
                    Map.of(scenario.a().id(), scenario.a()),
                    afterDetach,
                    Map.of(scenario.a().id(), 2L));
            assertEquals(afterDetach.get(scenario.b().id()),
                    scenario.b().snapshot().blueId());
            assertCurrentHistory(scenario.b(), 1L);
            assertAcyclicMembers(handles.values());
            Map<DocumentId, String> beforeReadd = blueIds(handles);

            EntryResult readded = coordination.operations()
                    .on(scenario.b())
                    .from(scenario.controlTimeline())
                    .call("readd")
                    .through("controlChannel")
                    .request(request -> request.exact(
                            "peer", scenario.a().exact()))
                    .execute();

            assertEquals(EntryDisposition.APPLIED,
                    readded.disposition(), "before=" + beforeReadd
                            + ", diagnostic=" + readded.diagnostic());
            assertSingleAppliedClosure(readded);
            assertEquals(List.of(scenario.b().id()),
                    readded.stats().documentStepOrder());
            assertEquals(2L, readded.stats().committedTransitions());
            assertEquals(2L, readded.stats().documentsOpened());
            assertExactGas(readded.stats(), 1_255L);
            assertTrue(readded.publicEvents().isEmpty());
            assertExactChangeEvidence(
                    readded,
                    handles,
                    beforeReadd,
                    Map.of(
                            scenario.a().id(), 3L,
                            scenario.b().id(), 2L));
            String readdedA = scenario.a().snapshot().blueId();
            String readdedB = scenario.b().snapshot().blueId();
            String readdedMaster = assertCyclicComponent(
                    handles, List.of(scenario.a().id(), scenario.b().id()));
            assertNotEquals(initialMaster, readdedMaster);
            assertNotEquals(initial.get(scenario.a().id()), readdedA);
            assertNotEquals(initial.get(scenario.b().id()), readdedB);
            assertNotEquals(afterDetach.get(scenario.a().id()), readdedA);
            assertNotEquals(afterDetach.get(scenario.b().id()), readdedB);
            assertNotEquals(beforeReadd.get(scenario.a().id()), readdedA);
            assertNotEquals(beforeReadd.get(scenario.b().id()), readdedB);
            assertEquals(scenario.a().id(), coordination.advanced()
                    .auditDocument(scenario.b().id())
                    .embeddedChildren().get("/peer"));
            assertManagedOccurrence(
                    coordination.advanced()
                            .auditManagedOccurrence(
                                    scenario.b().id(), "/peer")
                            .orElseThrow(),
                    scenario.a().id(),
                    2L,
                    true);
        }
    }

    @Test
    void submitIsAppendOnlyAndDrainMatchesExecute() {
        // given
        String timelineId = "sdk/parity/alice";
        DocumentId id = DocumentId.of("sdk-parity-counter");
        EntryResult submittedResult;
        DocumentSnapshot submittedSnapshot;

        // when
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle document = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    id, counterDocument(id, timelineId))
                            .publicRoot()
                            .fromNow());

            EntryHandle submitted = coordination.operations().on(document)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .submit();

            assertEquals(0L, document.snapshot().epoch());
            assertEquals(0L, document.snapshot().longAt("/counter"));
            DrainResult drain = coordination.processing().drain();
            submittedResult = drain.entry(submitted);
            submittedSnapshot = document.snapshot();
            assertTrue(drain.quiescent());
        }

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            DocumentHandle document = coordination.documents().admit(
                    ManagedDocument.yaml(
                                    id, counterDocument(id, timelineId))
                            .publicRoot()
                            .fromNow());
            EntryResult executed = coordination.operations().on(document)
                    .from(timeline)
                    .call("increment")
                    .through("ownerChannel")
                    .requestYaml("amount: 3")
                    .execute();

            // then
            assertEquals(executed.disposition(),
                    submittedResult.disposition());
            assertEquals(executed.stats().gas(),
                    submittedResult.stats().gas());
            assertEquals(executed.stats().documentStepOrder(),
                    submittedResult.stats().documentStepOrder());
            assertEquals(executed.publicEvents().stream()
                            .map(PublicEvent::blueId).toList(),
                    submittedResult.publicEvents().stream()
                            .map(PublicEvent::blueId).toList());
            assertEquals(document.snapshot().blueId(),
                    submittedSnapshot.blueId());
            assertEquals(document.snapshot().epoch(),
                    submittedSnapshot.epoch());
            assertEquals(document.snapshot().longAt("/counter"),
                    submittedSnapshot.longAt("/counter"));
        }
    }

    private static FiniteRingFixture finiteRingFixture(
            String prefix,
            int size) {
        String timelineId = prefix + "/alice";
        List<DocumentId> ids = IntStream.range(0, size)
                .mapToObj(index -> DocumentId.of(prefix + "-" + index))
                .toList();
        ManagedClosure.Builder builder = ManagedClosure.builder();
        for (int index = 0; index < size; index++) {
            String alias = "m" + index;
            builder.document(alias, ids.get(index), ringDocument(
                    ids.get(index), timelineId, index, size));
            int previous = Math.floorMod(index - 1, size);
            builder.bindOccurrence(alias, "/previous", "m" + previous);
        }
        ManagedClosure definition = builder.publicRoot("m0")
                .fromNow()
                .build();

        BlueCoordination coordination = BlueCoordination.inMemory();
        try {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    definition);
            Map<DocumentId, DocumentHandle> handles = handles(closure);
            Map<DocumentId, String> before = blueIds(handles);
            return new FiniteRingFixture(
                    coordination, ids, closure, handles, before, timeline);
        } catch (RuntimeException | Error failure) {
            coordination.close();
            throw failure;
        }
    }

    private static EntryResult executeFiniteRing(FiniteRingFixture fixture) {
        return fixture.coordination().operations()
                .on(fixture.closure().document("m0"))
                .from(fixture.timeline())
                .call("start")
                .through("ownerChannel")
                .execute();
    }

    private static void assertFiniteRingResult(
            FiniteRingFixture fixture,
            EntryResult result,
            String initialMaster,
            List<String> expectedStepOrder,
            long expectedGas) {
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        assertEquals(1, result.closures().size());
        assertEquals(expectedStepOrder.stream()
                        .map(DocumentId::of)
                        .toList(),
                result.stats().documentStepOrder());
        assertExactPublicEvents(
                fixture.coordination(),
                result.publicEvents(),
                List.of(new ExpectedPublicEvent(
                        fixture.ids().get(0), "ring-0")));
        assertAllExactEvidence(
                result,
                fixture.handles(),
                List.of(fixture.ids()),
                fixture.before(),
                1L,
                expectedGas);
        assertNotEquals(initialMaster,
                assertCyclicComponent(fixture.handles(), fixture.ids()));
        assertEquals("done", fixture.closure().document("m0")
                .snapshot().textAt("/phase"));
        for (int index = 1; index < fixture.ids().size(); index++) {
            assertEquals("relayed-" + index,
                    fixture.closure().document("m" + index)
                            .snapshot().textAt("/phase"));
        }
    }

    private static GasLoopEvidence runGasLoop() {
        DocumentId a = DocumentId.of("sdk-gas-loop-a");
        DocumentId b = DocumentId.of("sdk-gas-loop-b");
        String timelineId = "sdk/gas-loop/alice";
        ManagedClosure definition = ManagedClosure.builder()
                .document("a", a,
                        gasLoopDocument(a, timelineId, "/peer", true))
                .document("b", b,
                        gasLoopDocument(b, timelineId, "/peer", false))
                .bindOccurrence("a", "/peer", "b")
                .bindOccurrence("b", "/peer", "a")
                .publicRoot("a")
                .fromNow()
                .build();

        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            TimelineHandle timeline = coordination.timelines().register(
                    timelineId, ACTOR);
            ClosureHandle closure = coordination.documents().admit(
                    definition);
            Map<DocumentId, DocumentHandle> handles = handles(closure);
            Map<DocumentId, String> before = blueIds(handles);
            String beforeMaster = assertCyclicComponent(
                    handles, List.of(a, b));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));

            EntryResult result = coordination.operations()
                    .on(closure.document("a"))
                    .from(timeline)
                    .call("startLoop")
                    .through("ownerChannel")
                    .execute();

            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    result.disposition());
            assertEquals(1, result.closures().size());
            assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED,
                    result.closures().get(0).disposition());
            assertTrue(result.diagnostic().present());
            assertTrue(result.closures().get(0).changes().isEmpty());
            assertTrue(result.publicEvents().isEmpty());
            assertTrue(result.closures().get(0).publicEvents().isEmpty());
            assertEquals(result.stats(), result.closures().get(0).stats());
            assertEquals(0L, result.stats().committedTransitions());
            assertEquals(2L, result.stats().documentsOpened());
            assertEquals(expectedAlternatingLoopOrder(a, b, 742),
                    result.stats().documentStepOrder());
            assertExactGas(result.stats(), 99_967L);
            assertEquals(before, blueIds(handles));
            handles.values().forEach(handle ->
                    assertCurrentHistory(handle, 0L));
            assertEquals(beforeMaster,
                    assertCyclicComponent(handles, List.of(a, b)));
            assertTrue(coordination.processing().drain().entries().isEmpty(),
                    "a terminal gas failure is not silently retried");

            return new GasLoopEvidence(
                    result.entry().blueId(),
                    result.closures().get(0).closureId(),
                    result.stats().gas(),
                    result.stats().documentStepOrder(),
                    result.stats().counters(),
                    result.diagnostic().code(),
                    before.get(a),
                    before.get(b),
                    closure.document("a").snapshot().blueId(),
                    closure.document("b").snapshot().blueId());
        }
    }

    private static DynamicLoop admitDynamicLoop(
            BlueCoordination coordination,
            String prefix) {
        DocumentId aId = DocumentId.of(prefix + "-a");
        DocumentId bId = DocumentId.of(prefix + "-b");
        String signalTimelineId = prefix + "/signal";
        String controlTimelineId = prefix + "/control";
        ManagedClosure definition = ManagedClosure.builder()
                .document("a", aId,
                        dynamicLoopA(aId, signalTimelineId))
                .document("b", bId,
                        dynamicLoopB(bId, controlTimelineId))
                .bindOccurrence("a", "/peer", "b")
                .bindOccurrence("b", "/peer", "a")
                .publicRoot("a")
                .publicRoot("b")
                .fromNow()
                .build();
        TimelineHandle signal = coordination.timelines().register(
                signalTimelineId, ACTOR);
        TimelineHandle control = coordination.timelines().register(
                controlTimelineId, ACTOR);
        ClosureHandle closure = coordination.documents().admit(definition);
        return new DynamicLoop(
                closure.document("a"),
                closure.document("b"),
                signal,
                control);
    }

    private static String cyclicMaster(String memberBlueId) {
        int separator = memberBlueId.lastIndexOf('#');
        if (separator <= 0) {
            throw new AssertionError(
                    "Expected cyclic member BlueId, got " + memberBlueId);
        }
        return memberBlueId.substring(0, separator);
    }

    private static List<DocumentId> expectedAlternatingLoopOrder(
            DocumentId first,
            DocumentId second,
            int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> index % 2 == 0 ? first : second)
                .toList();
    }

    private static void assertAllExactEvidence(
            EntryResult result,
            Map<DocumentId, DocumentHandle> handles,
            List<List<DocumentId>> components,
            Map<DocumentId, String> before,
            long epoch,
            long expectedGas) {
        Set<DocumentId> members = components.stream()
                .flatMap(List::stream)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        assertTrue(result.applied());
        assertFalse(result.diagnostic().present());
        assertExactGas(result.stats(), expectedGas);
        assertEquals((long) members.size(),
                result.stats().committedTransitions());
        assertEquals((long) members.size(),
                result.stats().documentsOpened());
        assertFalse(result.entry().blueId().isBlank());
        assertTrue(result.closures().stream().allMatch(closure ->
                !closure.closureId().isBlank()
                        && closure.applied()
                        && !closure.diagnostic().present()));
        assertTrue(result.publicEvents().stream().allMatch(event ->
                !event.blueId().isBlank()
                        && event.blueId().equals(event.exact().blueId())));
        assertEquals(members, handles.keySet());
        assertExactChangeEvidence(
                result,
                handles,
                before,
                members.stream().collect(Collectors.toUnmodifiableMap(
                        id -> id,
                        ignored -> epoch)));
        components.forEach(component ->
                assertCyclicComponent(handles, component));
        assertEquals(result.publicEvents(), result.closures().stream()
                .flatMap(closure -> closure.publicEvents().stream())
                .toList());
        if (result.closures().size() == 1) {
            assertEquals(result.stats(), result.closures().get(0).stats());
        }
    }

    private static Map<DocumentId, DocumentHandle> handles(
            ClosureHandle closure) {
        LinkedHashMap<DocumentId, DocumentHandle> result =
                new LinkedHashMap<>();
        closure.documents().values().forEach(handle ->
                result.put(handle.id(), handle));
        return Map.copyOf(result);
    }

    private static Map<DocumentId, DocumentHandle> dynamicHandles(
            DynamicLoop scenario) {
        return Map.of(
                scenario.a().id(), scenario.a(),
                scenario.b().id(), scenario.b());
    }

    private static Map<DocumentId, String> blueIds(
            Map<DocumentId, DocumentHandle> handles) {
        return handles.values().stream().collect(
                Collectors.toUnmodifiableMap(
                        DocumentHandle::id,
                        handle -> handle.snapshot().blueId()));
    }

    private static String assertCyclicComponent(
            Map<DocumentId, DocumentHandle> handles,
            List<DocumentId> orderedMembers) {
        assertFalse(orderedMembers.isEmpty());
        String master = cyclicMaster(handles.get(orderedMembers.get(0))
                .snapshot().blueId());
        Set<String> observed = orderedMembers.stream()
                .map(handles::get)
                .map(handle -> handle.snapshot().blueId())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> expected = IntStream.range(0, orderedMembers.size())
                .mapToObj(index -> master + "#" + index)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(expected, observed);
        for (DocumentId member : orderedMembers) {
            DocumentHandle handle = handles.get(member);
            assertTrue(handle.exact().cyclicMember());
            assertEquals(master, cyclicMaster(handle.snapshot().blueId()));
            assertEquals(handle.snapshot().blueId(),
                    handle.exact().blueId());
        }
        return master;
    }

    private static void assertAcyclicMembers(
            Iterable<DocumentHandle> handles) {
        for (DocumentHandle handle : handles) {
            assertFalse(handle.exact().cyclicMember());
            assertFalse(handle.snapshot().blueId().contains("#"));
            assertEquals(handle.snapshot().blueId(),
                    handle.exact().blueId());
        }
    }

    private static void assertCurrentHistory(
            DocumentHandle handle,
            long expectedEpoch) {
        List<DocumentRevision> history = handle.history();
        assertEquals(expectedEpoch + 1L, (long) history.size());
        assertEquals(IntStream.rangeClosed(0, Math.toIntExact(expectedEpoch))
                        .asLongStream()
                        .boxed()
                        .toList(),
                history.stream().map(DocumentRevision::epoch).toList());
        assertEquals(DocumentRevision.Kind.INITIALIZATION,
                history.get(0).kind());
        for (int index = 0; index < history.size(); index++) {
            DocumentRevision revision = history.get(index);
            assertEquals(handle.id(), revision.documentId());
            assertEquals((long) index, revision.epoch());
            assertFalse(revision.after().blueId().isBlank());
            if (index > 0) {
                assertEquals(history.get(index - 1).after(),
                        revision.before().orElseThrow());
            }
        }
        assertEquals(expectedEpoch, handle.snapshot().epoch());
        assertEquals(handle.exact(), history.get(history.size() - 1).after());
        assertEquals(handle.snapshot().blueId(),
                history.get(history.size() - 1).after().blueId());
    }

    private static void assertExactChangeEvidence(
            EntryResult result,
            Map<DocumentId, DocumentHandle> changedHandles,
            Map<DocumentId, String> before,
            Map<DocumentId, Long> expectedEpochs) {
        Map<DocumentId, DocumentChange> changes = result.closures().stream()
                .flatMap(closure -> closure.changes().stream())
                .collect(Collectors.toUnmodifiableMap(
                        DocumentChange::documentId,
                        change -> change));
        assertEquals(expectedEpochs.keySet(), changes.keySet());
        assertEquals(expectedEpochs.keySet(), changedHandles.keySet());
        expectedEpochs.forEach((id, epoch) -> {
            DocumentHandle handle = changedHandles.get(id);
            DocumentChange change = changes.get(id);
            assertEquals(epoch.longValue(), change.epoch());
            assertEquals(before.get(id),
                    change.before().orElseThrow().blueId());
            assertEquals(handle.exact(), change.after());
            assertEquals(handle.snapshot().blueId(),
                    change.after().blueId());
            assertEquals(result.publicEvents().stream()
                            .filter(event -> event.sourceDocument()
                                    .filter(id::equals).isPresent())
                            .toList(),
                    change.publicEvents());
            assertCurrentHistory(handle, epoch);
        });
    }

    private static void assertExactGas(
            ProcessingStats stats,
            long expectedGas) {
        long counterGas = stats.counters().values().stream()
                .reduce(0L, Math::addExact);
        assertEquals(expectedGas, stats.gas());
        assertEquals(expectedGas, counterGas);
    }

    private static void assertExactPublicEvents(
            BlueCoordination coordination,
            List<PublicEvent> actual,
            List<ExpectedPublicEvent> expected) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            ExpectedPublicEvent event = expected.get(index);
            ExactBlueValue exact = coordination.values().yaml("""
                    type: Coordination/Event
                    kind: %s
                    """.formatted(event.kind()));
            PublicEvent observed = actual.get(index);
            assertEquals(exact, observed.exact());
            assertEquals(exact.blueId(), observed.blueId());
            assertEquals(event.source(),
                    observed.sourceDocument().orElseThrow());
            assertTrue(observed.occurrencePath().isEmpty());
        }
    }

    private static void assertManagedOccurrence(
            ManagedOccurrenceAudit audit,
            DocumentId target,
            long generation,
            boolean active) {
        assertEquals(target, audit.targetDocumentId());
        assertEquals(generation, audit.activationGeneration());
        assertEquals(active, audit.active());
    }

    private static void assertSingleAppliedClosure(EntryResult result) {
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        assertTrue(result.applied());
        assertFalse(result.diagnostic().present());
        assertEquals(1, result.closures().size());
        ClosureResult closure = result.closures().get(0);
        assertTrue(closure.applied());
        assertFalse(closure.diagnostic().present());
        assertEquals(result.stats(), closure.stats());
        assertEquals(result.publicEvents(), closure.publicEvents());
    }

    private static void assertApplied(
            EntryResult result,
            DocumentId expectedDocument) {
        assertEquals(EntryDisposition.APPLIED, result.disposition());
        assertTrue(result.applied());
        assertFalse(result.diagnostic().present());
        assertEquals(Set.of(expectedDocument), changedDocuments(result));
    }

    private static Set<DocumentId> changedDocuments(EntryResult result) {
        return result.closures().stream()
                .flatMap(closure -> closure.changes().stream())
                .map(DocumentChange::documentId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String counterDocument(
            DocumentId documentId,
            String timelineId) {
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
                    request:
                      amount: {type: Integer}
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
                          - $return: true
                  decrement:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request:
                      amount: {type: Integer}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val:
                                $subtract:
                                  - $document: /counter
                                  - $binding: event/message/request/amount
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static ExactBlueValue providerIncrement(
            BlueCoordination coordination,
            String timelineId,
            long timestamp,
            long amount,
            String previousEntryBlueId) {
        String predecessor = previousEntryBlueId == null
                ? ""
                : "prevEntry:\n  blueId: " + previousEntryBlueId + "\n";
        return coordination.values().yaml("""
                type: Coordination/Timeline Entry
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: %s
                timestamp: %d
                %sactor:
                  type: MyOS/Principal Actor
                  accountId: %s
                message:
                  type: Coordination/Operation Request
                  operation: increment
                  channel: ownerChannel
                  request:
                    amount: %d
                """.formatted(
                timelineId, timestamp, predecessor, ACTOR, amount));
    }

    private static String targetedDocument(
            DocumentId documentId,
            String timelineId) {
        return """
                documentId: %s
                processed: 0
                contracts:
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  markProcessed:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /processed, val: 1}
                          - $return: true
                """.formatted(documentId.value(), timelineId, ACTOR);
    }

    private static String ringDocument(
            DocumentId id,
            String timelineId,
            int index,
            int size) {
        String external = index == 0 ? """
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: ring-0}
                          - $return: true
                """.formatted(timelineId, ACTOR) : "";
        String response;
        if (index == 0) {
            response = """
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                    """;
        } else {
            response = """
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: relayed-%d}
                          - $appendEvent: {type: Coordination/Event, kind: ring-%d}
                          - $return: true
                    """.formatted(index, index);
        }
        int incoming = Math.floorMod(index - 1, size);
        return """
                documentId: %s
                phase: initial
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /previous
                  fromPrevious:
                    type: Embedded Node Channel
                    sourcePath: /previous
                    event: {type: Coordination/Event, kind: ring-%d}
                  onPrevious:
                    type: Coordination/Sequential Workflow
                    channel: fromPrevious
                    event: {type: Coordination/Event, kind: ring-%d}
                    steps:
                %s
                %s
                """.formatted(
                id.value(), incoming, incoming,
                response.indent(4).stripTrailing(),
                external.stripTrailing());
    }

    private static String branchingA(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                phase: initial
                branch1: pending
                branch2: pending
                contracts:
                  embedded:
                    type: Process Embedded
                    collectionPaths:
                      - /branches
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: branch-start-1}
                          - $return: true
                  fromB1:
                    type: Embedded Node Channel
                    sourcePath: /branches/b1
                    event: {type: Coordination/Event, kind: branch-result-1}
                  onB1:
                    type: Coordination/Sequential Workflow
                    channel: fromB1
                    event: {type: Coordination/Event, kind: branch-result-1}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch1, val: done}
                          - $appendEvent: {type: Coordination/Event, kind: branch-ack}
                          - $appendEvent: {type: Coordination/Event, kind: branch-start-2}
                          - $return: true
                  fromB2:
                    type: Embedded Node Channel
                    sourcePath: /branches/b2
                    event: {type: Coordination/Event, kind: branch-result-2}
                  onB2:
                    type: Coordination/Sequential Workflow
                    channel: fromB2
                    event: {type: Coordination/Event, kind: branch-result-2}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /branch2, val: done}
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $appendEvent: {type: Coordination/Event, kind: branch-ack}
                          - $appendEvent: {type: Coordination/Event, kind: branching-done}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String branchingB(
            DocumentId id,
            String incomingKind,
            String outgoingKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /child
                  fromChild:
                    type: Embedded Node Channel
                    sourcePath: /child
                    event: {type: Coordination/Event, kind: %s}
                  contribute:
                    type: Coordination/Sequential Workflow
                    channel: fromChild
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: contributed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(id.value(), incomingKind,
                incomingKind, outgoingKind);
    }

    private static String branchingC(
            DocumentId id,
            String incomingKind,
            String outgoingKind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /root
                  fromRoot:
                    type: Embedded Node Channel
                    sourcePath: /root
                    event: {type: Coordination/Event, kind: %s}
                  observe:
                    type: Coordination/Sequential Workflow
                    channel: fromRoot
                    event: {type: Coordination/Event, kind: %s}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: observed}
                          - $appendEvent: {type: Coordination/Event, kind: %s}
                          - $return: true
                """.formatted(id.value(), incomingKind,
                incomingKind, outgoingKind);
    }

    private static String disjointA(
            DocumentId id,
            String timelineId,
            String kind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                  sharedChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  start:
                    type: Coordination/Sequential Workflow Operation
                    channel: sharedChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: started}
                          - $appendEvent: {type: Coordination/Event, kind: %s-start}
                          - $return: true
                  fromPeer:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: %s-done}
                  finish:
                    type: Coordination/Sequential Workflow
                    channel: fromPeer
                    event: {type: Coordination/Event, kind: %s-done}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: done}
                          - $return: true
                """.formatted(id.value(), timelineId, ACTOR,
                kind, kind, kind);
    }

    private static String disjointB(
            DocumentId id,
            String kind) {
        return """
                documentId: %s
                phase: initial
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                  fromPeer:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: %s-start}
                  relay:
                    type: Coordination/Sequential Workflow
                    channel: fromPeer
                    event: {type: Coordination/Event, kind: %s-start}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /phase, val: relayed}
                          - $appendEvent: {type: Coordination/Event, kind: %s-done}
                          - $return: true
                """.formatted(id.value(), kind, kind, kind);
    }

    private static String gasLoopDocument(
            DocumentId id,
            String timelineId,
            String peerPath,
            boolean publicRoot) {
        String external = publicRoot ? """
                  ownerChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(timelineId, ACTOR) : "";
        return """
                documentId: %s
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - %s
                  fromPeer:
                    type: Embedded Node Channel
                    sourcePath: %s
                    event: {type: Coordination/Event, kind: LOOP}
                  onPeerLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromPeer
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                %s
                """.formatted(
                id.value(), peerPath, peerPath, external.stripTrailing());
    }

    private static String dynamicLoopA(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                loopStarts: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                  signalChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  startLoop:
                    type: Coordination/Sequential Workflow Operation
                    channel: signalChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /loopStarts
                              val: {$add: [{$document: /loopStarts}, 1]}
                          - $appendEvent: {type: Coordination/Event, kind: LOOP}
                          - $return: true
                  fromPeer:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromPeer
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private static String dynamicLoopB(
            DocumentId id,
            String timelineId) {
        return """
                documentId: %s
                phase: attached
                contracts:
                  embedded:
                    type: Process Embedded
                    paths:
                      - /peer
                  controlChannel:
                    type: Coordination/Timeline Channel
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    actor:
                      type: MyOS/Principal Actor
                      accountId: %s
                  detach:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: remove, path: /peer}
                          - $appendChange: {op: replace, path: /phase, val: detached}
                          - $return: true
                  readd:
                    type: Coordination/Sequential Workflow Operation
                    channel: controlChannel
                    request:
                      peer: {}
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: add
                              path: /peer
                              val: {$binding: event/message/request/peer}
                          - $appendChange: {op: replace, path: /phase, val: attached}
                          - $return: true
                  fromPeer:
                    type: Embedded Node Channel
                    sourcePath: /peer
                    event: {type: Coordination/Event, kind: LOOP}
                  relayLoop:
                    type: Coordination/Sequential Workflow
                    channel: fromPeer
                    event: {type: Coordination/Event, kind: LOOP}
                    steps:
                      - type: Coordination/Trigger Event
                        event: {type: Coordination/Event, kind: LOOP}
                """.formatted(id.value(), timelineId, ACTOR);
    }

    private record FiniteRingFixture(
            BlueCoordination coordination,
            List<DocumentId> ids,
            ClosureHandle closure,
            Map<DocumentId, DocumentHandle> handles,
            Map<DocumentId, String> before,
            TimelineHandle timeline) implements AutoCloseable {
        private FiniteRingFixture {
            ids = List.copyOf(ids);
            handles = Map.copyOf(handles);
            before = Map.copyOf(before);
        }

        @Override
        public void close() {
            coordination.close();
        }
    }

    private record GasLoopEvidence(
            String entryBlueId,
            String closureId,
            long gas,
            List<DocumentId> documentStepOrder,
            Map<String, Long> counters,
            String diagnosticCode,
            String beforeA,
            String beforeB,
            String afterA,
            String afterB) {
        private GasLoopEvidence {
            documentStepOrder = List.copyOf(documentStepOrder);
            counters = Map.copyOf(counters);
        }
    }

    private record ExpectedPublicEvent(DocumentId source, String kind) {
    }

    private record DynamicLoop(
            DocumentHandle a,
            DocumentHandle b,
            TimelineHandle signalTimeline,
            TimelineHandle controlTimeline) {
    }
}
