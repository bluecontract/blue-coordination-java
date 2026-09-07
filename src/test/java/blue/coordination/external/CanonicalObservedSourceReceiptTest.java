package blue.coordination.external;

import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Physical current-head residency must not change an independent semantic receipt. */
class CanonicalObservedSourceReceiptTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test void freshSourceAndColdSourceAlreadyAtItsAfterHeadProduceTheSameConsumerReceipt() { run(false); }
    @Test void failedConsumerReceiptKeepsExactReferenceAndGapWithoutCopyingSourceCacheBodies() { run(true); }

    @Test void coldBorrowedExternalProducerKeepsItsIndependentlyAdmittedDifferentPolicy() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var producerCore = new CoordinationCore(f.processor, f.core.environment(),
                    ClosureEvidenceFactory.executionPolicy(90_000, Map.of(), "independent-producer-policy"));
            var source = f.source(); var entry = CanonicalSourceHistoryTest.input("different producer policy", 15, "account");
            var history = new CanonicalSourceHistory(producerCore);
            var request = new CanonicalSourceHistory.Request(source.documentId(), entry.order());
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request, history.start(source.documentId()),
                    f.evidence(source, List.of(entry), 20), f.blobs::put, LIMITS));
            var before = f.restore(birth.after().successfulView().orElseThrow());
            var admitted = OriginalSourceInputTestSupport.admit(producerCore, birth.after(), request,
                    f.evidence(before, List.of(entry), 20), SameOriginAttachmentPolicy.empty(), f.blobs);
            var step = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(admitted.request(), birth.after(),
                    admitted.evidence(), f.blobs::put, LIMITS));
            var sourceProgram = OperationReceiptCodec.restoreSourceProgram(step.receiptIdentity(), f.blobs::get, LIMITS);
            var observer = f.observer("independent policy observer", birth.after().successfulView().orElseThrow());
            var producerBasis = SourceExecutionBasis.identity(source.documentId(), producerCore.environment(), producerCore.executionPolicy());
            var base = f.observerEvidence(observer, before, List.of(entry), List.of(sourceProgram), Optional.empty());
            var evidence = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                    base.handledThrough(), List.of(), Map.of(source.documentId(), birth.after().semanticPredecessor().orElseThrow()), List.of(sourceProgram))
                    .withExpectedSourceBases(Map.of(source.documentId(), producerBasis));
            var result = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(
                    new CoordinationCore.WorkIntent(observer.before().documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT), evidence));
            assertEquals(1, result.operations().size()); var parent = result.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, parent.result().status());
            assertNotEquals(parent.invocation().executionPolicy().identity(), sourceProgram.executionPolicy().identity());
            verifyBorrowedAuthorityAtLanguageBoundary(f, parent, Map.of(source.documentId(), producerBasis,
                    observer.before().documentId(), SourceExecutionBasis.identity(observer.before().documentId(), f.core.environment(), f.core.executionPolicy())));
        }
    }

    private void run(boolean failingConsumer) {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var source = f.source(); var entry = CanonicalSourceHistoryTest.input("advance source", 15, "account");
            var history = f.prepare(new CanonicalSourceHistory(f.core), source,
                    CanonicalSourceHistoryTest.input("complete cut", 20, "other").order(), List.of(entry), 21);
            var initial = history.steps().get(0); var produced = history.steps().get(1);
            var sourceBefore = f.restore(initial.after().successfulView().orElseThrow());
            var sourceAfter = f.restore(produced.after().successfulView().orElseThrow());
            var observer = failingConsumer ? failingObserver(f, sourceBefore)
                    : f.observer("same observer with different physical source residency", initial.after().successfulView().orElseThrow());
            var base = f.observerEvidence(observer, sourceBefore, List.of(entry), List.of(), Optional.empty());
            Map<DocumentId, String> predecessors = Map.of(source.documentId(), initial.after().semanticPredecessor().orElseThrow());
            var fresh = new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                    base.handledThrough(), List.of(), predecessors);
            var originalSource = FreshProducerAdmissionTest.admission(f.core, source.documentId(), entry, predecessors,
                    SameOriginAttachmentPolicy.empty(), f.blobs);
            fresh = fresh.withSourceInputAdmissions(List.of(originalSource))
                    .withOriginalSourceInputRoots(Map.of(source.documentId(), originalSource.identity()));
            var work = new CoordinationCore.WorkIntent(observer.before().documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            var actualFresh = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(work, fresh));
            assertEquals(2, actualFresh.operations().size());
            var freshConsumer = actualFresh.operations().stream().filter(group -> group.ownedLineages().contains(observer.before().documentId())).findFirst().orElseThrow();
            var coldSource = OperationReceiptCodec.restoreSourceProgram(produced.receiptIdentity(), f.blobs::get, LIMITS);
            var advancedCut = ClosureEvidenceFactory.affectedClosure(0, List.of(observer.before(), sourceAfter), List.of(observer.binding()),
                    List.of(ClosureEvidenceFactory.acyclicComponent(sourceAfter), ClosureEvidenceFactory.acyclicComponent(observer.before())),
                    base.snapshot().publicRootDocumentIds(), List.of(ManagedReadPin.fromExactEvidence(source.documentId(), sourceBefore.blueId(), sourceBefore.document(), null)));
            var cached = new CoordinationCore.EvaluationEvidence(advancedCut, base.relevantTimelines(), base.prefixes(), base.handledThrough(),
                    List.of(), predecessors, List.of(coldSource));
            var originalSourceBasis = Map.of(source.documentId(), SourceExecutionBasis.identity(source.documentId(), f.core.environment(), f.core.executionPolicy()));
            var emptyCut = new CoordinationCore.EvaluationEvidence(advancedCut, base.relevantTimelines(),
                    List.of(new CoordinationCore.TimelinePrefix("timeline", 21, List.of())), Optional.empty(), List.of(), predecessors, List.of(coldSource));
            var missingBasis = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(work, emptyCut));
            assertEquals(List.of("source-execution-basis:" + source.documentId().value()), missingBasis.keys(),
                    "Unverified header explanation must not turn a stale read cut into Idle");
            assertThrows(IllegalArgumentException.class, () -> f.core.evaluate(work,
                    emptyCut.withExpectedSourceBases(Map.of(source.documentId(), "c".repeat(64)))));
            assertInstanceOf(CoordinationCore.Idle.class, f.core.evaluate(work, emptyCut.withExpectedSourceBases(originalSourceBasis)));
            var actualCached = assertInstanceOf(CoordinationCore.PreparedOperations.class,
                    f.core.evaluate(work, cached.withExpectedSourceBases(originalSourceBasis)));
            assertEquals(1, actualCached.operations().size()); var cachedConsumer = actualCached.operations().get(0);
            assertEquals(failingConsumer ? ProcessorStatus.RUNTIME_FATAL : ProcessorStatus.SUCCESS, cachedConsumer.result().status());
            assertEquals(freshConsumer.operationId(), cachedConsumer.operationId());
            assertEquals(freshConsumer.result().gasTraceIdentity(), cachedConsumer.result().gasTraceIdentity());
            var freshReceipt = OperationReceiptCodec.encode(freshConsumer, f.blobs::put, LIMITS);
            var cachedReceipt = OperationReceiptCodec.encode(cachedConsumer, f.blobs::put, LIMITS);
            assertEquals(freshReceipt.sourceProgramIdentity(), cachedReceipt.sourceProgramIdentity(), "Retained source execution is canonical");
            assertEquals(freshReceipt.receiptIdentity(), cachedReceipt.receiptIdentity(), "Receipt metadata cannot encode the provider's newer resident source head");
            if (!failingConsumer) verifyBorrowedAuthorityAtLanguageBoundary(f, freshConsumer);
            var restored = OperationReceiptCodec.decode(cachedReceipt.receiptIdentity(), f.blobs::get, LIMITS);
            assertEquals(List.of(new OperationReceiptCodec.ObservedSource(observer.before().documentId(), source.documentId(), sourceBefore.blueId(), sourceBefore.epoch())), restored.observedSources());
            if (failingConsumer) {
                assertTrue(restored.readPins().isEmpty(), "Failure does not accept optional dependency body caches");
                var owner = OperationReceiptCodec.restoreState(cachedReceipt.receiptIdentity(), observer.before().documentId(), true, 0, f.blobs::get, LIMITS);
                assertEquals(observer.before().blueId(), owner.blueId()); assertEquals(observer.before().epoch(), owner.epoch());
                assertEquals(sourceBefore.blueId(), owner.document().getProperties().get("child").getBlueId());
                var gap = OperationReceiptCodec.restoreSourceGap(cachedReceipt.receiptIdentity(), owner.documentId(), source.documentId(),
                        produced.receiptIdentity(), f.blobs::get, LIMITS);
                assertEquals(sourceBefore.blueId(), gap.observedBlueId()); assertEquals(sourceBefore.epoch(), gap.observedEpoch());
            }
        }
    }

    private static void verifyBorrowedAuthorityAtLanguageBoundary(CanonicalSourceHistoryTest.Fixture f,
                                                                  CoordinationCore.PreparedGroupOperation producedObserver) {
        Map<DocumentId, String> expected = new TreeMap<>();
        var source = producedObserver.sourceProgram().orElseThrow();
        for (var program : List.of(source, source.borrowedPrograms().get(0))) for (DocumentId owner : program.ownedDocumentIds())
            expected.put(owner, SourceExecutionBasis.identity(owner, f.core.environment(), f.core.executionPolicy()));
        verifyBorrowedAuthorityAtLanguageBoundary(f, producedObserver, expected);
    }

    private static void verifyBorrowedAuthorityAtLanguageBoundary(CanonicalSourceHistoryTest.Fixture f,
            CoordinationCore.PreparedGroupOperation producedObserver, Map<DocumentId, String> correct) {
        var retained = OperationReceiptCodec.encode(producedObserver, f.blobs::put, LIMITS);
        var source = OperationReceiptCodec.restoreSourceProgram(retained.receiptIdentity(), f.blobs::get, LIMITS);
        assertFalse(source.borrowedPrograms().isEmpty(), "Use an actual observer program retaining its producer DAG");
        var borrowed = source.borrowedPrograms().get(0);
        var origin = producedObserver.invocation();
        var body = origin.snapshot().managedDocument(borrowed.ownedDocumentIds().iterator().next()).document().clone()
                .name("independent downstream compatibility witness");
        String id = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(body);
        var consumer = new ManagedDocumentSnapshot(new DocumentId(id), id, body, true, false, true, 0, 0);
        List<ManagedDocumentSnapshot> states = new ArrayList<>(origin.snapshot().managedDocuments()); states.add(consumer);
        Map<DocumentId, ManagedDocumentSnapshot> byId = new TreeMap<>(); states.forEach(state -> byId.put(state.documentId(), state));
        List<ComponentSnapshot> components = new ArrayList<>();
        for (var members : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), origin.snapshot().occurrences()))) {
            assertEquals(1, members.size()); components.add(ClosureEvidenceFactory.acyclicComponent(byId.get(members.get(0))));
        }
        var cut = ClosureEvidenceFactory.affectedClosure(0, states, origin.snapshot().occurrences(), components,
                new ArrayList<>(byId.keySet()), origin.snapshot().readPins());
        var invocation = ClosureEvidenceFactory.processClosure(cut, origin.cause(), origin.directDeliveries(), origin.executionPolicy(), origin.environment());
        Map<DocumentId, String> wrong = new TreeMap<>(correct);
        borrowed.ownedDocumentIds().forEach(owner -> wrong.put(owner, "c".repeat(64)));
        try (var contracts = new BlueClosureContracts(f.processor)) {
            var accepted = contracts.processExternalScope(invocation,
                    Set.of(consumer.documentId()), List.of(source), Map.of(), List.of(), correct);
            assertTrue(accepted.isComplete(), () -> "Cross-policy retained source requested " + accepted.resourceDemands().stream()
                    .map(demand -> demand.kind() + ":" + demand.sourceDocumentId() + ":" + demand.sourcePath() + ":" + demand.suppliedValueBlueId()).toList());
            var rejected = assertThrows(IllegalArgumentException.class, () -> contracts.processExternalScope(invocation, Set.of(consumer.documentId()),
                    List.of(source), Map.of(), List.of(), wrong));
            assertTrue(rejected.getMessage().contains("expected producer execution basis"), rejected::getMessage);
        }
    }

    private static CanonicalSourceHistoryTest.Observer failingObserver(CanonicalSourceHistoryTest.Fixture f, ManagedDocumentSnapshot source) {
        var authored = f.authored("""
                name: failing observer with different physical source residency
                seen: 0
                contracts:
                  embedded:
                    type: Process Embedded
                    paths: [/child]
                  fromSource:
                    type: Embedded Node Channel
                    sourcePath: /child
                  fail:
                    type: Coordination/Sequential Workflow
                    channel: fromSource
                    steps:
                      - type: Coordination/Compute
                        do:
                          - $appendChange: {op: replace, path: /seen, val: {$divide: [1, 0]}}
                          - $return: true
                """, Map.of("child", source.blueId()));
        var binding = f.binding(authored, source);
        var initial = new CanonicalSourceHistoryTest.Observer(authored, binding);
        var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                f.observerEvidence(initial, source, List.of(), List.of(), Optional.empty())));
        assertEquals(ProcessorStatus.SUCCESS, birth.result().status());
        var encoded = OperationReceiptCodec.encode(birth, f.blobs::put, LIMITS);
        return new CanonicalSourceHistoryTest.Observer(OperationReceiptCodec.restoreState(encoded.receiptIdentity(), authored.documentId(), true, 0, f.blobs::get, LIMITS), binding);
    }
}
