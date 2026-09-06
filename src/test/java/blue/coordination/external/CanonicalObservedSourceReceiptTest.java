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
            var actualCached = assertInstanceOf(CoordinationCore.PreparedOperations.class, f.core.evaluate(work, cached));
            assertEquals(1, actualCached.operations().size()); var cachedConsumer = actualCached.operations().get(0);
            assertEquals(failingConsumer ? ProcessorStatus.RUNTIME_FATAL : ProcessorStatus.SUCCESS, cachedConsumer.result().status());
            assertEquals(freshConsumer.operationId(), cachedConsumer.operationId());
            assertEquals(freshConsumer.result().gasTraceIdentity(), cachedConsumer.result().gasTraceIdentity());
            var freshReceipt = OperationReceiptCodec.encode(freshConsumer, f.blobs::put, LIMITS);
            var cachedReceipt = OperationReceiptCodec.encode(cachedConsumer, f.blobs::put, LIMITS);
            assertEquals(freshReceipt.sourceProgramIdentity(), cachedReceipt.sourceProgramIdentity(), "Retained source execution is canonical");
            assertEquals(freshReceipt.receiptIdentity(), cachedReceipt.receiptIdentity(), "Receipt metadata cannot encode the provider's newer resident source head");
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
