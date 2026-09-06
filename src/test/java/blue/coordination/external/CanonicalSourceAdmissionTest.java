package blue.coordination.external;

import blue.language.processor.closure.FrozenNodeEvidenceCodec;
import blue.language.processor.closure.SameOriginAttachmentPolicy;
import blue.language.processor.closure.SourceExecutionBasis;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.InvalidExecutionEvidenceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalSourceAdmissionTest {
    @Test void missingOriginalAdmissionDoesNotSilentlyChooseAnEmptyAttachmentPolicy() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = f.source();
            var entry = CanonicalSourceHistoryTest.input("source input needs its original admission", 15, "account");
            var history = new CanonicalSourceHistory(f.core);
            var request = new CanonicalSourceHistory.Request(authored.documentId(), entry.order());
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request,
                    history.start(authored.documentId()), f.evidence(authored, List.of(entry), 20),
                    f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            var before = f.restore(birth.after().successfulView().orElseThrow());
            var result = history.prepareNext(request, birth.after(), f.evidence(before, List.of(entry), 20),
                    f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
            var need = assertInstanceOf(CanonicalSourceHistory.Await.class, result);
            assertTrue(need.keys().stream().anyMatch(key -> key.startsWith("source-input-admission:")));
            assertEquals(1L, birth.after().operations());
            assertEquals(0L, before.epoch());
            var original = OriginalSourceInputTestSupport.admit(f.core, birth.after(), request,
                    f.evidence(before, List.of(entry), 20), SameOriginAttachmentPolicy.empty(), f.blobs);
            // Offered candidate content is not original admission authority, even if internally valid.
            assertInstanceOf(CanonicalSourceHistory.Await.class, history.prepareNext(request, birth.after(), original.evidence(),
                    f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            var complete = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(original.request(), birth.after(),
                    original.evidence(), f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            var cold = history.resume(authored.documentId(), complete.after().recordIdentity().orElseThrow(),
                    key -> {
                        assertEquals(complete.after().recordIdentity().orElseThrow(), key, "Only the current prefix head is read");
                        return f.blobs.get(key);
                    }, FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(original.admission().identity(), cold.originalAdmissionIdentity().orElseThrow());
            assertEquals(complete.after().semanticPredecessor(), cold.semanticPredecessor());
        }
    }

    @Test void rejectsChangedPredecessorAndForeignOriginalMembersBeforeExecution() {
        try (var f = new CanonicalSourceHistoryTest.Fixture()) {
            var authored = f.source(); var entry = CanonicalSourceHistoryTest.input("original", 15, "account");
            var history = new CanonicalSourceHistory(f.core); var request = new CanonicalSourceHistory.Request(authored.documentId(), entry.order());
            var birth = assertInstanceOf(CanonicalSourceHistory.Step.class, history.prepareNext(request, history.start(authored.documentId()),
                    f.evidence(authored, List.of(), 20), f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            var before = f.restore(birth.after().successfulView().orElseThrow());
            var evidence = f.evidence(before, List.of(entry), 20);
            var source = authored.documentId(); var unrelated = new DocumentId("reverse-only-observer");
            for (boolean foreign : List.of(false, true)) {
                Map<DocumentId, String> bases = foreign ? Map.of(source, birth.after().basisIdentity(), unrelated,
                        SourceExecutionBasis.identity(unrelated, f.core.environment(), f.core.executionPolicy())) : Map.of(source, birth.after().basisIdentity());
                Map<DocumentId, String> predecessors = Map.of(source, foreign ? birth.after().semanticPredecessor().orElseThrow() : "sha256:" + "c".repeat(64));
                String root = SourceInputAdmission.encodeCandidate(entry, bases, predecessors, SameOriginAttachmentPolicy.empty(),
                        f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults());
                var offered = SourceInputAdmission.restore(root, f.blobs::get, FrozenNodeEvidenceCodec.Limits.defaults());
                var admittedRequest = new CanonicalSourceHistory.Request(source, entry.order(), Map.of(entry.entry().blueId(), root));
                assertThrows(InvalidExecutionEvidenceException.class, () -> history.prepareNext(admittedRequest, birth.after(),
                        evidence.withSourceInputAdmissions(List.of(offered)), f.blobs::put, FrozenNodeEvidenceCodec.Limits.defaults()));
            }
        }
    }
}
