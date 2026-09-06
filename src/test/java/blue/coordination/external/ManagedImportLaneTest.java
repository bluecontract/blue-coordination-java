package blue.coordination.external;

import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.closure.*;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static blue.coordination.external.CanonicalSourceHistoryTest.input;
import static org.junit.jupiter.api.Assertions.*;

/** Real processing/receipt tests; activation descriptors model authenticated committed host input,
 * not a claim that dynamic FULL_HISTORY creator-to-lane minting is already wired. */
class ManagedImportLaneTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void oldSourceEntriesBecomeSeparateParentEpochsAfterK100WithoutRedeliveringItsTimelineInput() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = source(f);
            var k100 = input("attachment cut", 100, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, k100.order(),
                    List.of(input("E10", 10, "account"), input("E20", 20, "account")), 101);
            var birth = operation(prepared.steps().get(0));
            var e10 = sourceOperation(prepared.steps().get(1)); var e20 = sourceOperation(prepared.steps().get(2));
            var source0 = state(birth); var source1 = state(e10);
            var observerBirth = observer(f, source0, false, false);
            var before = state(observerBirth);
            var row = observerBirth.ownedOccurrenceBindings().get(0);
            var creator = singletonGroup(f.core.evaluate(
                    new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT),
                    evidence(before, source0, List.of(row), List.of(), Optional.empty(), Map.of(before.documentId(), observerBirth.operationId()),
                            List.of(k100), List.of(), Map.of())));
            assertEquals(ProcessorStatus.SUCCESS, creator.result().status());
            before = state(creator); row = creator.ownedOccurrenceBindings().get(0);
            assertEquals(BigInteger.ONE, before.document().get("/direct"));
            // The pending row and descriptor are the immutable accepted activation handed to this evaluator.
            row = pending(row);
            var lane = lane(row, birth, creator.operationId(), k100, prepared.boundary().cursor().basisIdentity());
            var authority = ManagedImportLane.PrefixAuthority.fromBoundary(lane, prepared.boundary());
            var cursor = ManagedImportLane.Cursor.start(lane);
            var due10 = new ManagedImportLane.Due(cursor, header(e10, source.documentId(), lane.canonicalSourceBasis()), Optional.of(authority));
            var selected10 = new ManagedImportSelection(List.of(due10));
            var oldCursor = Optional.of(k100.order());
            var evidence10 = evidence(before, source0, List.of(row), List.of(cold(f, e10)), oldCursor,
                    Map.of(before.documentId(), creator.operationId()), List.of(), List.of(), Map.of());
            var imported10 = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(selected10, evidence10));
            assertEquals(ProcessorStatus.SUCCESS, imported10.result().status());
            assertEquals(before.epoch() + 1, state(imported10).epoch());
            assertEquals(BigInteger.ONE, state(imported10).document().get("/seen"));
            assertEquals(BigInteger.ONE, state(imported10).document().get("/direct"), "E10 cannot run the consumer's old external delivery");
            assertTrue(imported10.input().isEmpty(), "No ordinary handledThrough delta");
            assertEquals(k100.order(), imported10.managedReaction().orElseThrow().activationCut());
            assertEquals(input("E10", 10, "account").order(), ((ExternalEventCause) imported10.invocation().cause()).sourceOrder());
            assertEquals(ManagedImportLane.Outcome.APPLIED, imported10.laneDeltas().get(0).outcome());
            assertFalse(imported10.laneDeltas().get(0).nextCursor().complete());
            assertFalse(imported10.ownedOccurrenceBindings().get(0).active(), "Historical processing must not promote pending to live");
            assertEquals(Long.valueOf(1), imported10.ownedOccurrenceBindings().get(0).pendingHistoricalEpoch());
            var encoded = OperationReceiptCodec.encode(imported10, f.blobs::put, LIMITS);
            // The actual cold consumer receipt was produced at K100, although its
            // retained source event is E10. A downstream F50 cannot install it.
            var producingProgram = OperationReceiptCodec.restoreSourceProgram(encoded.receiptIdentity(), f.blobs::get, LIMITS);
            var produced = state(imported10); var downstream = f.authored("name: downstream frontier observer", Map.of());
            var producedPin = ManagedReadPin.fromExactEvidence(produced.documentId(), produced.blueId(), produced.document(), null);
            var earlyFrontier = SourceFrontierSelectionTest.selection(f, downstream, produced, input("F50", 50, "other").order());
            assertThrows(IllegalArgumentException.class, () -> SourceFrontierView.fromRetainedEvidence(
                    earlyFrontier, producingProgram, Optional.empty(), producedPin));
            var atProduction = SourceFrontierSelectionTest.selection(f, downstream, produced, k100.order());
            var selectedProduced = SourceFrontierView.fromRetainedEvidence(atProduction, producingProgram, Optional.empty(), producedPin);
            assertEquals(Optional.of(k100.order()), selectedProduced.productionOrder());
            var restoredLanes = OperationReceiptCodec.restoreManagedLanes(encoded.receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(imported10.laneDeltas().get(0).nextCursor().positionIdentity(), restoredLanes.get(0).nextCursor().positionIdentity());
            cursor = restoredLanes.get(0).nextCursor();
            var due20 = new ManagedImportLane.Due(cursor, header(e20, source.documentId(), lane.canonicalSourceBasis()), Optional.of(authority));
            var selected20 = new ManagedImportSelection(List.of(due20));
            var before20 = state(imported10); var row20 = imported10.ownedOccurrenceBindings().get(0);
            var missing20 = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(selected20,
                    evidence(before20, source1, List.of(row20), List.of(), oldCursor, Map.of(before20.documentId(), imported10.operationId()), List.of(), List.of(), Map.of())));
            assertEquals(List.of("source-program:" + e20.operationId()), missing20.keys());
            assertEquals(1, cursor.successfulEpoch(), "Waiting for page/body M2 does not erase committed M1");
            var evidence20 = evidence(before20, source1, List.of(row20), List.of(cold(f, e20)), oldCursor,
                    Map.of(before20.documentId(), imported10.operationId()), List.of(), List.of(), Map.of());
            var imported20 = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(selected20, evidence20));
            assertEquals(ProcessorStatus.SUCCESS, imported20.result().status());
            assertEquals(before.epoch() + 2, state(imported20).epoch());
            assertEquals(BigInteger.TWO, state(imported20).document().get("/seen"));
            assertEquals(BigInteger.ONE, state(imported20).document().get("/direct"));
            assertTrue(imported20.laneDeltas().get(0).nextCursor().complete());
            var replay = assertInstanceOf(CoordinationCore.PreparedOperation.class, new CoordinationCore(f.processor, f.core.environment(), f.core.executionPolicy()).evaluate(selected20, evidence20));
            assertEquals(imported20.operationId(), replay.operationId());
            assertEquals(imported20.result().gasTraceIdentity(), replay.result().gasTraceIdentity());
            assertEquals(state(imported20).blueId(), state(replay).blueId());
        }
    }

    @Test
    void aliasMembershipIsExplicitAndAReplacedActivationCannotConsumeItsPredecessorsLane() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = source(f); var cut = input("attach aliases", 100, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, cut.order(), List.of(input("E10", 10, "account")), 101);
            var birth = operation(prepared.steps().get(0)); var advance = sourceOperation(prepared.steps().get(1));
            var observerBirth = observer(f, state(birth), true, false); var before = state(observerBirth);
            var rows = observerBirth.ownedOccurrenceBindings().stream().map(ManagedImportLaneTest::pending).toList();
            var laneA = lane(rows.get(0), birth, observerBirth.operationId(), cut, prepared.boundary().cursor().basisIdentity());
            var laneB = lane(rows.get(1), birth, observerBirth.operationId(), cut, prepared.boundary().cursor().basisIdentity());
            var a = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(laneA), header(advance, source.documentId(), laneA.canonicalSourceBasis()), Optional.empty());
            var b = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(laneB), header(advance, source.documentId(), laneB.canonicalSourceBasis()), Optional.empty());
            var cutEvidence = evidence(before, state(birth), rows, List.of(cold(f, advance)), Optional.of(cut.order()), Map.of(), List.of(), List.of(), Map.of());
            var both = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new ManagedImportSelection(List.of(b, a)), cutEvidence));
            assertEquals(ProcessorStatus.SUCCESS, both.result().status());
            assertEquals(before.epoch() + 1, state(both).epoch(), "One complete source position across aliases is one consumer operation");
            assertEquals(BigInteger.TWO, state(both).document().get("/reactions"));
            assertEquals(2, both.laneDeltas().size());
            var onlyB = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new ManagedImportSelection(List.of(b)), cutEvidence));
            assertEquals(BigInteger.ONE, state(onlyB).document().get("/reactions"), "Non-due /a must not replay because /b imports the same source");
            assertEquals(state(birth).blueId(), onlyB.ownedOccurrenceBindings().stream().filter(row -> row.occurrenceIdentity().equals(laneA.occurrenceIdentity())).findFirst().orElseThrow().expectedTargetBlueId());
            var replacement = ManagedOccurrenceBinding.derived(rows.get(1).bindingPolicyIdentity(), before.documentId(),
                    ScopeAddress.embedded(rows.get(1).sourcePath(), 2), source.documentId(), state(birth).blueId(), false, 0L);
            var stale = evidence(before, state(birth), List.of(rows.get(0), replacement), List.of(cold(f, advance)), Optional.of(cut.order()), Map.of(), List.of(), List.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(new ManagedImportSelection(List.of(b)), stale));
        }
    }

    @Test
    void producingOrderExcludesLateImportWithOldSourceProvenanceAndBareTagCannotSelectHistory() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = source(f); var cut50 = input("attach", 50, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, cut50.order(), List.of(), 51);
            var birth = operation(prepared.steps().get(0)); var observerBirth = observer(f, state(birth), false, false);
            var row = observerBirth.ownedOccurrenceBindings().get(0);
            var lane = lane(row, birth, observerBirth.operationId(), cut50, prepared.boundary().cursor().basisIdentity());
            // This is a header-shape fixture: retained E10 is deliberately not the K100 producing position.
            var late = new ManagedImportLane.Header(source.documentId(), lane.canonicalSourceBasis(), sha("late-source-import"), Optional.of(birth.operationId()),
                    CoordinationCore.OperationKind.MANAGED_RECEIPT_IMPORT, ProcessorStatus.SUCCESS, state(birth).blueId(), 0, state(birth).blueId(), 1,
                    Optional.of(input("K100", 100, "order-account").order()), sha("reaction-of-E10"));
            assertThrows(IllegalArgumentException.class, () -> new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane), late, Optional.empty()));
            assertFalse(new ObserverAttachmentPlan.HistoricalLane(Optional.empty(), cut50.order()).includes(late));
            var oldTag = f.core.evaluate(new CoordinationCore.WorkIntent(state(observerBirth).documentId(), CoordinationCore.OperationKind.MANAGED_RECEIPT_IMPORT),
                    evidence(state(observerBirth), state(birth), List.of(row), List.of(), Optional.empty(), Map.of(), List.of(), List.of(), Map.of()));
            assertEquals(List.of("managed-reaction-selection:" + state(observerBirth).documentId().value()), assertInstanceOf(CoordinationCore.NeedEvidence.class, oldTag).keys());
        }
    }

    private static ManagedDocumentSnapshot source(CanonicalSourceHistoryTest.Fixture f) {
        return source(f, false);
    }
    private static ManagedDocumentSnapshot source(CanonicalSourceHistoryTest.Fixture f, boolean fail) {
        return f.authored("""
                name: Historical source
                counter: 0
                contracts:
                  ingress:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                    actor: {type: MyOS/Principal Actor, accountId: account}
                  update:
                    type: Coordination/Sequential Workflow
                    channel: ingress
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /counter
                              val: %s
                      - type: Coordination/Trigger Event
                        event: source-updated
                """.formatted(fail ? "{$divide: [1, 0]}" : "{$add: [{$document: /counter}, 1]}"), Map.of());
    }

    @Test
    void failedSourceAdvancesOnlyTerminalLaneMetadataAndRoundTripsCold() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = source(f, true); var cut = input("attachment", 100, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, cut.order(), List.of(), 101);
            var birth = operation(prepared.steps().get(0)); var source0 = state(birth);
            var observerBirth = observer(f, source0, false, false); var before = state(observerBirth);
            var row = pending(observerBirth.ownedOccurrenceBindings().get(0));
            var sourceEvidence = f.evidence(source0, List.of(input("E10", 10, "account")), 101);
            sourceEvidence = new CoordinationCore.EvaluationEvidence(sourceEvidence.snapshot(), sourceEvidence.relevantTimelines(), sourceEvidence.prefixes(),
                    sourceEvidence.handledThrough(), sourceEvidence.fences(), Map.of(source.documentId(), birth.operationId()))
                    .withOperationFences(sourceEvidence.operationFences());
            var failed = singletonGroup(f.core.evaluate(
                    new CoordinationCore.WorkIntent(source.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), sourceEvidence));
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.result().status());
            var failureReceipt = OperationReceiptCodec.encode(failed, f.blobs::put, LIMITS);
            var failure = OperationReceiptCodec.restoreSourceFailure(failureReceipt.receiptIdentity(), f.blobs::get, LIMITS);
            var lane = lane(row, birth, observerBirth.operationId(), cut, prepared.boundary().cursor().basisIdentity());
            var due = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane), header(failed, source.documentId(), lane.canonicalSourceBasis()),
                    Optional.of(new ManagedImportLane.PrefixAuthority(lane.identity(), failed.operationId())));
            var evidence = evidence(before, source0, List.of(row), List.of(), Optional.of(cut.order()),
                    Map.of(before.documentId(), observerBirth.operationId()), List.of(), List.of(failure), Map.of());
            var progress = assertInstanceOf(CoordinationCore.ManagedProgress.class, f.core.evaluate(new ManagedImportSelection(List.of(due)), evidence));
            var delta = progress.laneDeltas().get(0);
            assertEquals(ManagedImportLane.Outcome.SOURCE_FAILURE, delta.outcome());
            assertEquals(source0.blueId(), delta.nextCursor().successfulBlueId()); assertEquals(0, delta.nextCursor().successfulEpoch());
            assertEquals(failed.operationId(), delta.nextCursor().lastTerminalSourceOperationIdentity());
            assertTrue(delta.nextCursor().hadFailures()); assertTrue(delta.nextCursor().complete());
            var progressRoot = ManagedProgressReceiptCodec.encode(progress, f.blobs::put, LIMITS);
            var restored = ManagedProgressReceiptCodec.decode(progressRoot, f.blobs::get, LIMITS);
            assertEquals(delta.nextCursor().positionIdentity(), restored.laneDeltas().get(0).nextCursor().positionIdentity());
            assertEquals(progress.managedReaction().identity(), restored.managedReaction().identity());
            assertEquals(BigInteger.ZERO, before.document().get("/seen")); assertEquals(0, before.epoch());
            var missing = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(new ManagedImportSelection(List.of(due)),
                    evidence(before, source0, List.of(row), List.of(), Optional.of(cut.order()), Map.of(), List.of(), List.of(), Map.of())));
            assertEquals(List.of("source-failure:" + failed.operationId()), missing.keys());
            var complete = f.prepare(new CanonicalSourceHistory(f.core), source, cut.order(), List.of(input("E10", 10, "account")), 101).boundary();
            var now = assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromNow(cut.order()), complete.cursor(), Optional.of(complete)));
            var nowLane = ManagedImportLane.Descriptor.fromPlan(row, observerBirth.operationId(), sha("now seed"), sha("now site"), now,
                    header(failed, source.documentId(), complete.cursor().basisIdentity()));
            var nowCursor = ManagedImportLane.Cursor.start(nowLane);
            assertEquals(failed.operationId(), nowCursor.lastTerminalSourceOperationIdentity(), "Terminal failure head is not replaced by a fabricated successful operation");
            assertEquals(source0.blueId(), nowCursor.successfulBlueId()); assertEquals(0, nowCursor.successfulEpoch()); assertTrue(nowCursor.complete());
            var frontier = assertInstanceOf(ObserverAttachmentPlan.Ready.class, ObserverAttachmentPlan.select(
                    ObserverAttachmentPlan.Selection.fromFrontier(cut.order(), cut.order()), complete.cursor(), Optional.of(complete)));
            var frontierLane = ManagedImportLane.Descriptor.fromPlan(row, observerBirth.operationId(), sha("frontier seed"), sha("frontier site"), frontier,
                    header(failed, source.documentId(), complete.cursor().basisIdentity()));
            assertTrue(ManagedImportLane.Cursor.start(frontierLane).complete(), "A zero-length frontier suffix has no runnable historical lane");
        }
    }

    @Test
    void deliveredHandlerWithNoAuthoredBodyChangeStillOwnsOneBusinessEpoch() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.authored("""
                    name: Event-only historical source
                    contracts:
                      ingress:
                        type: Coordination/Timeline Channel
                        timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                        actor: {type: MyOS/Principal Actor, accountId: account}
                      emit:
                        type: Coordination/Sequential Workflow
                        channel: ingress
                        steps: [{type: Coordination/Trigger Event, event: source-updated}]
                    """, Map.of());
            var cut = input("attachment", 100, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, cut.order(), List.of(input("E10", 10, "account")), 101);
            var birth = operation(prepared.steps().get(0)); var advance = sourceOperation(prepared.steps().get(1));
            var observerBirth = observer(f, state(birth), false, true); var before = state(observerBirth);
            var row = pending(observerBirth.ownedOccurrenceBindings().get(0));
            var lane = lane(row, birth, observerBirth.operationId(), cut, prepared.boundary().cursor().basisIdentity());
            var due = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane), header(advance, source.documentId(), lane.canonicalSourceBasis()), Optional.empty());
            var result = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new ManagedImportSelection(List.of(due)),
                    evidence(before, state(birth), List.of(row), List.of(cold(f, advance)), Optional.of(cut.order()), Map.of(), List.of(), List.of(), Map.of())));
            assertEquals(ProcessorStatus.SUCCESS, result.result().status());
            assertEquals(before.epoch() + 1, state(result).epoch());
            assertEquals(BigInteger.ZERO, state(result).document().get("/seen"));
            assertEquals(BigInteger.ZERO, state(result).document().get("/reactions"));
            assertEquals(List.of("observer-received"), result.result().publicEvents().stream().map(event -> event.event().getValue()).toList());
            assertEquals(ManagedImportLane.Outcome.APPLIED, result.laneDeltas().get(0).outcome());
        }
    }

    @Test
    void failedConsumerConsumesOneLanePositionButKeepsItsPinThenNextEpochAlignsTheGap() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = source(f); var cut = input("attachment", 100, "order-account");
            var prepared = f.prepare(new CanonicalSourceHistory(f.core), source, cut.order(),
                    List.of(input("E10", 10, "account"), input("E20", 20, "account")), 101);
            var birth = operation(prepared.steps().get(0)); var e10 = sourceOperation(prepared.steps().get(1)); var e20 = sourceOperation(prepared.steps().get(2));
            var source0 = state(birth);
            // Both attempts retain the same fixed execution policy. The handler deterministically
            // divides by zero only at counter=1; it is a lawful finite handler at counter=2.
            var observerBirth = observer(f, source0, false, false, true); var before = state(observerBirth);
            var row = pending(observerBirth.ownedOccurrenceBindings().get(0));
            var lane = lane(row, birth, observerBirth.operationId(), cut, prepared.boundary().cursor().basisIdentity());
            var authority = ManagedImportLane.PrefixAuthority.fromBoundary(lane, prepared.boundary());
            var first = new ManagedImportLane.Due(ManagedImportLane.Cursor.start(lane), header(e10, source.documentId(), lane.canonicalSourceBasis()), Optional.of(authority));
            var program10 = cold(f, e10);
            var evidence10 = evidence(before, source0, List.of(row), List.of(program10), Optional.of(cut.order()),
                    Map.of(before.documentId(), observerBirth.operationId()), List.of(), List.of(), Map.of());
            var failed = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new ManagedImportSelection(List.of(first)), evidence10));
            assertEquals(ProcessorStatus.RUNTIME_FATAL, failed.result().status());
            assertEquals(CoordinationCore.Disposition.CONSUMED, failed.disposition());
            assertEquals(before.blueId(), state(failed).blueId()); assertEquals(before.epoch(), state(failed).epoch());
            var delta = failed.laneDeltas().get(0);
            assertEquals(ManagedImportLane.Outcome.RUNTIME_FATAL, delta.outcome());
            assertEquals(source0.blueId(), delta.nextCursor().successfulBlueId()); assertEquals(0, delta.nextCursor().successfulEpoch());
            assertEquals(e10.operationId(), delta.nextCursor().lastTerminalSourceOperationIdentity());
            assertFalse(delta.nextCursor().complete()); assertTrue(delta.nextCursor().hadFailures());
            var failureReceipt = OperationReceiptCodec.encode(failed, f.blobs::put, LIMITS);
            var sourceReceipt = OperationReceiptCodec.encode(e10, f.blobs::put, LIMITS);
            var terminal = OperationReceiptCodec.restoreSourceFailure(failureReceipt.receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(failed.managedReaction().orElseThrow().identity(), terminal.managedReaction().orElseThrow().identity());
            assertEquals(cut.order(), terminal.managedReaction().orElseThrow().activationCut());
            assertEquals(e10.invocation().cause().causeIdentity(), terminal.causeIdentity(), "Original source cause remains provenance only");
            assertTrue(failed.sourceProgram().isEmpty());
            var restoredCursor = OperationReceiptCodec.restoreManagedLanes(failureReceipt.receiptIdentity(), f.blobs::get, LIMITS).get(0).nextCursor();
            var gap = OperationReceiptCodec.restoreSourceGap(failureReceipt.receiptIdentity(), before.documentId(), source.documentId(),
                    sourceReceipt.receiptIdentity(), f.blobs::get, LIMITS);
            var second = new ManagedImportLane.Due(restoredCursor, header(e20, source.documentId(), lane.canonicalSourceBasis()), Optional.of(authority));
            var resumed = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new ManagedImportSelection(List.of(second)),
                    evidence(before, source0, List.of(row), List.of(cold(f, e20)), Optional.of(cut.order()),
                            Map.of(before.documentId(), failed.operationId()), List.of(), List.of(), Map.of(source.documentId(), List.of(gap)))));
            assertEquals(ProcessorStatus.SUCCESS, resumed.result().status());
            assertEquals(before.epoch() + 1, state(resumed).epoch(), "Failed E10 creates no consumer business epoch");
            assertEquals(BigInteger.TWO, state(resumed).document().get("/seen"));
            assertEquals(BigInteger.ONE, state(resumed).document().get("/reactions"), "Source alignment is not a replay of the failed old embedded event");
            assertEquals(2, resumed.laneDeltas().get(0).nextCursor().successfulEpoch());
            assertTrue(resumed.laneDeltas().get(0).nextCursor().complete());
            assertTrue(resumed.laneDeltas().get(0).nextCursor().hadFailures());
            assertThrows(IllegalArgumentException.class, () -> new ManagedImportSelection(List.of(first, second)),
                    "Two different source-producing positions cannot be collapsed into one consumer epoch");
        }
    }

    private static CoordinationCore.PreparedOperation observer(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot source, boolean aliases, boolean noBodyChange) {
        return observer(f, source, aliases, noBodyChange, false);
    }
    private static CoordinationCore.PreparedOperation observer(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot source, boolean aliases,
            boolean noBodyChange, boolean failFirst) {
        f.nodes.put(source.blueId(), source.document());
        String observerStep = noBodyChange ? "- type: Coordination/Trigger Event\n  event: observer-received" : """
                - type: Coordination/Compute
                  do:
                    - $appendChange:
                        op: replace
                        path: /seen
                        val: %s
                    - $appendChange:
                        op: replace
                        path: /reactions
                        val: {$add: [{$document: /reactions}, 1]}
                """.formatted(failFirst ? "{$add: [{$divide: [0, {$subtract: [{$document: /source/counter}, 1]}]}, {$document: /source/counter}]}"
                        : "{$document: /source/counter}").stripTrailing();
        String yaml = """
                name: Historical observer
                seen: 0
                reactions: 0
                direct: 0
                contracts:
                  embedded:
                    type: {blueId: %s}
                    paths: %s
                  fromSource:
                    type: {blueId: %s}
                    sourcePath: /source
                  observe:
                    type: Coordination/Sequential Workflow
                    channel: fromSource
                    event: source-updated
                    steps:
                %s
                  creatorIngress:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                    actor: {type: MyOS/Principal Actor, accountId: order-account}
                  directIngress:
                    type: Coordination/Timeline Channel
                    timeline: {type: MyOS/MyOS Timeline, timelineId: timeline}
                    actor: {type: MyOS/Principal Actor, accountId: account}
                  creator:
                    type: Coordination/Sequential Workflow
                    channel: creatorIngress
                    steps: [{type: Coordination/Update Document, changeset: [{op: replace, path: /direct, val: 1}]}]
                  mustNotReplay:
                    type: Coordination/Sequential Workflow
                    channel: directIngress
                    steps: [{type: Coordination/Update Document, changeset: [{op: replace, path: /direct, val: 999}]}]
                """.formatted(RuntimeBlueIds.PROCESS_EMBEDDED, aliases ? "[/source, /alias]" : "[/source]",
                        RuntimeBlueIds.EMBEDDED_NODE_CHANNEL, observerStep.indent(6).stripTrailing());
        if (aliases) yaml += """
                  fromAlias:
                    type: {blueId: %s}
                    sourcePath: /alias
                  observeAlias:
                    type: Coordination/Sequential Workflow
                    channel: fromAlias
                    event: source-updated
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange:
                              op: replace
                              path: /reactions
                              val: {$add: [{$document: /reactions}, 1]}
                """.formatted(RuntimeBlueIds.EMBEDDED_NODE_CHANNEL);
        var authored = f.authored(yaml, aliases ? Map.of("source", source.blueId(), "alias", source.blueId()) : Map.of("source", source.blueId()));
        List<ManagedOccurrenceBinding> rows = new ArrayList<>();
        for (String path : aliases ? List.of("/source", "/alias") : List.of("/source")) rows.add(ManagedOccurrenceBinding.derived(
                f.core.environment().managedBindingPolicyIdentity(), authored.documentId(), ScopeAddress.embedded(path, 1), source.documentId(), source.blueId(), true, null));
        var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                evidence(authored, source, rows, List.of(), Optional.empty(), Map.of(), List.of(), List.of(), Map.of())));
        assertEquals(ProcessorStatus.SUCCESS, birth.result().status()); return birth;
    }

    private static ManagedImportLane.Descriptor lane(ManagedOccurrenceBinding row, CoordinationCore.PreparedOperation birth, String creator,
            CoordinationCore.TimelineInput cut, String basis) {
        return new ManagedImportLane.Descriptor(row.sourceDocumentId(), row.occurrenceIdentity(), row.targetDocumentId(), creator,
                sha("creator-seed:" + creator), sha("creator-site:" + creator), ObserverAttachmentPlan.Selection.fullHistory(cut.order()),
                basis, birth.operationId(), state(birth).blueId(), state(birth).epoch());
    }
    private static ManagedImportLane.Header header(CoordinationCore.PreparedOperation operation, DocumentId source, String basis) {
        return ManagedImportLane.Header.fromOperation(operation, source, basis);
    }
    private static ManagedImportLane.Header header(CoordinationCore.PreparedGroupOperation operation, DocumentId source, String basis) {
        return ManagedImportLane.Header.fromOperation(operation, source, basis);
    }
    private static ManagedOccurrenceBinding pending(ManagedOccurrenceBinding row) {
        return ManagedOccurrenceBinding.derived(row.bindingPolicyIdentity(), row.sourceDocumentId(), row.sourceAddress(), row.targetDocumentId(), row.expectedTargetBlueId(), false, 0L);
    }
    private static CoordinationCore.PreparedOperation operation(CanonicalSourceHistory.Step step) {
        return assertInstanceOf(CoordinationCore.PreparedOperation.class, step.evaluation());
    }
    private static CoordinationCore.PreparedGroupOperation sourceOperation(CanonicalSourceHistory.Step step) {
        return singletonGroup(step.evaluation());
    }
    private static CoordinationCore.PreparedGroupOperation singletonGroup(CoordinationCore.EvaluationResult result) {
        var groups = assertInstanceOf(CoordinationCore.PreparedOperations.class, result).operations();
        assertEquals(1, groups.size()); return groups.get(0);
    }
    private static ManagedDocumentSnapshot state(CoordinationCore.PreparedOperation operation) {
        var p = operation.projections().get(0); var result = p.result();
        return new ManagedDocumentSnapshot(p.lineage(), p.afterBlueId(), result.document(), result.initialized(), result.terminated(), true, p.afterEpoch(), 0);
    }
    private static ManagedDocumentSnapshot state(CoordinationCore.PreparedGroupOperation operation) {
        var p = operation.projections().get(0); var result = p.result();
        return new ManagedDocumentSnapshot(p.lineage(), p.afterBlueId(), result.document(), result.initialized(), result.terminated(), true, p.afterEpoch(), 0);
    }
    private static SourceObservationProgram cold(CanonicalSourceHistoryTest.Fixture f, CoordinationCore.PreparedOperation operation) {
        var receipt = OperationReceiptCodec.encode(operation, f.blobs::put, LIMITS);
        return OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), f.blobs::get, LIMITS);
    }
    private static SourceObservationProgram cold(CanonicalSourceHistoryTest.Fixture f, CoordinationCore.PreparedGroupOperation operation) {
        var receipt = OperationReceiptCodec.encode(operation, f.blobs::put, LIMITS);
        return OperationReceiptCodec.restoreSourceProgram(receipt.receiptIdentity(), f.blobs::get, LIMITS);
    }
    private static String sha(String label) { return ManagedImportLane.digest("test-fixture", label); }
    private static CoordinationCore.EvaluationEvidence evidence(ManagedDocumentSnapshot observer, ManagedDocumentSnapshot source,
            List<ManagedOccurrenceBinding> rows, List<SourceObservationProgram> programs, Optional<ExternalOrderKey> handled,
            Map<DocumentId, String> predecessors, List<CoordinationCore.TimelineInput> entries, List<SourceOperationFailure> failures,
            Map<DocumentId, List<SourceObservationGap>> gaps) {
        var documents = Map.of(observer.documentId(), observer, source.documentId(), source);
        var components = new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(documents.keySet(), rows)).stream()
                .map(component -> ClosureEvidenceFactory.acyclicComponent(documents.get(component.get(0)))).toList();
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.of(observer, source), rows, components, List.of(observer.documentId(), source.documentId()));
        return new CoordinationCore.EvaluationEvidence(snapshot, Set.of("timeline"), List.of(new CoordinationCore.TimelinePrefix("timeline", 101, entries)),
                handled, List.of(new CoordinationCore.ReadFence("consumer", "fixed-cut")), predecessors, programs, gaps, failures)
                .withOperationFences(Map.of(observer.documentId(), List.of(new CoordinationCore.ReadFence("consumer", "fixed-cut")),
                        source.documentId(), List.of(new CoordinationCore.ReadFence("source", "fixed-cut"))));
    }
}
