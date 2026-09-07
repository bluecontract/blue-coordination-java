package blue.coordination.external;

import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Metadata publication uses the target's authority, never a union of source heads. */
class MetadataProgressAuthorityTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();

    @Test
    void ignoredInputPreservesAttributedTargetFencesAndExcludesUnrelatedHeads() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var before = initialized(f);
            var other = f.authored("name: Unrelated owner", Map.of());
            var input = CanonicalSourceHistoryTest.input("ignored actor", 15, "someone-else");
            var base = withUnrelatedOwner(f.evidence(before, List.of(input), 20), other);
            var targetFences = List.of(new CoordinationCore.ReadFence("target-head", "7"),
                    new CoordinationCore.ReadFence("target-work", "3"));
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            for (String otherRevision : List.of("1", "2")) {
                for (var legacy : List.of(List.<CoordinationCore.ReadFence>of(),
                        List.of(new CoordinationCore.ReadFence("legacy-all-heads", "9")))) {
                    var cut = copyWithFences(base, legacy, Map.of(before.documentId(), targetFences,
                            other.documentId(), List.of(new CoordinationCore.ReadFence("unrelated-head", otherRevision))));
                    var progress = assertInstanceOf(CoordinationCore.MetadataProgress.class, f.core.evaluate(intent, cut));
                    assertEquals(targetFences, progress.fences());
                    assertEquals(before.documentId(), progress.lineage());
                    assertEquals(input, progress.input());
                    assertTrue(progress.consumedSourceOperations().isEmpty());
                    assertEquals(before.blueId(), cut.snapshot().managedDocument(before.documentId()).blueId());
                    assertEquals(before.epoch(), cut.snapshot().managedDocument(before.documentId()).epoch());
                }
            }
        }
    }

    @Test
    void attributedModeRequestsMissingTargetAuthorityInsteadOfUsingAnotherOwnersOrLegacyFences() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var before = initialized(f);
            var other = f.authored("name: Other fence owner", Map.of());
            var input = CanonicalSourceHistoryTest.input("ignored actor", 15, "someone-else");
            var base = withUnrelatedOwner(f.evidence(before, List.of(input), 20), other);
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            for (var legacy : List.of(List.<CoordinationCore.ReadFence>of(), base.fences())) {
                var cut = copyWithFences(base, legacy,
                        Map.of(other.documentId(), List.of(new CoordinationCore.ReadFence("other-head", "1"))));
                var need = assertInstanceOf(CoordinationCore.NeedEvidence.class, f.core.evaluate(intent, cut));
                assertEquals(List.of("group-read-fences:" + before.documentId().value()), need.keys());
            }
        }
    }

    @Test
    void legacyOnlyAndExplicitlyFenceFreeEvaluationRemainSupported() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var before = initialized(f);
            var input = CanonicalSourceHistoryTest.input("ignored actor", 15, "someone-else");
            var base = f.evidence(before, List.of(input), 20);
            var intent = new CoordinationCore.WorkIntent(before.documentId(), CoordinationCore.OperationKind.EXTERNAL_INPUT);
            for (var fences : List.of(List.<CoordinationCore.ReadFence>of(), base.fences())) {
                var legacy = assertInstanceOf(CoordinationCore.MetadataProgress.class,
                        f.core.evaluate(intent, copyWithFences(base, fences, Map.of())));
                var attributed = assertInstanceOf(CoordinationCore.MetadataProgress.class,
                        f.core.evaluate(intent, copyWithFences(base, List.of(), Map.of(before.documentId(), fences))));
                assertEquals(fences, legacy.fences());
                assertEquals(legacy, attributed);
            }
            var explicitlyEmpty = assertInstanceOf(CoordinationCore.MetadataProgress.class,
                    f.core.evaluate(intent, copyWithFences(base, base.fences(), Map.of(before.documentId(), List.of()))));
            assertTrue(explicitlyEmpty.fences().isEmpty(),
                    "An explicit empty target entry must not fall back to nonempty legacy fences");
        }
    }

    private static ManagedDocumentSnapshot initialized(CanonicalSourceHistoryTest.Fixture f) {
        var authored = f.source();
        var birth = assertInstanceOf(CoordinationCore.PreparedOperation.class, f.core.evaluate(
                new CoordinationCore.WorkIntent(authored.documentId(), CoordinationCore.OperationKind.INITIALIZATION),
                f.evidence(authored, List.of(), 20)));
        var receipt = OperationReceiptCodec.encode(birth, f.blobs::put, LIMITS);
        return OperationReceiptCodec.restoreState(receipt.receiptIdentity(), authored.documentId(), true, 0, f.blobs::get, LIMITS);
    }

    private static CoordinationCore.EvaluationEvidence withUnrelatedOwner(
            CoordinationCore.EvaluationEvidence base, ManagedDocumentSnapshot other) {
        var states = new TreeMap<DocumentId, ManagedDocumentSnapshot>();
        base.snapshot().managedDocuments().forEach(state -> states.put(state.documentId(), state));
        states.put(other.documentId(), other);
        var snapshot = ClosureEvidenceFactory.affectedClosure(0, List.copyOf(states.values()), List.of(),
                states.values().stream().map(ClosureEvidenceFactory::acyclicComponent).toList(), List.copyOf(states.keySet()));
        return new CoordinationCore.EvaluationEvidence(snapshot, base.relevantTimelines(), base.prefixes(),
                base.handledThrough(), base.fences(), base.precedingOperations());
    }

    private static CoordinationCore.EvaluationEvidence copyWithFences(CoordinationCore.EvaluationEvidence base,
            List<CoordinationCore.ReadFence> legacy, Map<DocumentId, List<CoordinationCore.ReadFence>> attributed) {
        return new CoordinationCore.EvaluationEvidence(base.snapshot(), base.relevantTimelines(), base.prefixes(),
                base.handledThrough(), legacy, base.precedingOperations()).withOperationFences(attributed);
    }
}
