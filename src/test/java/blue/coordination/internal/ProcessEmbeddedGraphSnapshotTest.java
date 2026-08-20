package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ActivationMode;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Immutability and independent cursor-generation tests for graph snapshots. */
final class ProcessEmbeddedGraphSnapshotTest {
    private static final DocumentId PARENT = DocumentId.of("parent");
    private static final DocumentId OTHER_PARENT =
            DocumentId.of("other-parent");
    private static final DocumentId CHILD_A = DocumentId.of("child-a");
    private static final DocumentId CHILD_B = DocumentId.of("child-b");

    @Test
    void capturedSnapshotIsImmutableWhileCursorAndLaterTopologyAdvance() {
        // given
        EmbeddingBinding first = binding(
                "binding-a", "/a", CHILD_A, 1L);
        ProcessEmbeddedGraphSnapshot captured =
                ProcessEmbeddedGraphSnapshot.empty()
                        .reconcileParent(PARENT, List.of(first));
        List<EmbeddingBinding> capturedChildren = captured.children(PARENT);
        long capturedGeneration = captured.generation();

        // when
        EmbeddedEpochCursor initial = new EmbeddedEpochCursor(
                first.bindingId(), -1L);
        EmbeddedEpochCursor initialized = initial.advanceTo(0L);
        EmbeddedEpochCursor processed = initialized.advanceTo(1L);
        ProcessEmbeddedGraphSnapshot unchanged = captured.reconcileParent(
                PARENT, List.of(first));
        EmbeddingBinding second = binding(
                "binding-b", "/b", CHILD_B, 1L);
        ProcessEmbeddedGraphSnapshot advanced = captured.reconcileParent(
                PARENT, List.of(first, second));

        // then
        assertEquals(-1L, initial.appliedChildEpoch());
        assertEquals(0L, initialized.appliedChildEpoch());
        assertEquals(1L, processed.appliedChildEpoch());
        assertEquals(capturedGeneration, captured.generation());
        assertEquals(List.of(first), capturedChildren);
        assertTrue(captured.containsBinding(first.bindingId()));
        assertFalse(captured.containsBinding("missing"));
        assertThrows(UnsupportedOperationException.class,
                () -> capturedChildren.clear());
        assertThrows(UnsupportedOperationException.class,
                () -> captured.bindings().clear());
        assertSame(captured, unchanged,
                "cursor progress and identical reconciliation are not topology");
        assertEquals(capturedGeneration + 1L, advanced.generation());
        assertEquals(List.of(first), captured.children(PARENT),
                "the captured generation must not observe later topology");
        assertEquals(List.of(first, second), advanced.children(PARENT));
        assertTrue(advanced.containsBinding(second.bindingId()));
        assertEquals(List.of(first), captured.parents(CHILD_A));
        assertEquals(List.of(second), advanced.parents(CHILD_B));
    }

    @Test
    void oneAddedBindingRetainsEveryUnrelatedBucketAndRecord() {
        // given
        List<EmbeddingBinding> many = IntStream.range(0, 64)
                .mapToObj(index -> binding(
                        "binding-" + index,
                        "/children/" + index,
                        DocumentId.of("child-" + index), 1L))
                .toList();
        EmbeddingBinding unrelated = binding(
                OTHER_PARENT, "unrelated", "/other",
                DocumentId.of("other-child"), 1L);
        ProcessEmbeddedGraphSnapshot captured =
                ProcessEmbeddedGraphSnapshot.empty()
                        .reconcileParent(PARENT, many)
                        .reconcileParent(OTHER_PARENT, List.of(unrelated));
        List<EmbeddingBinding> unrelatedBucket =
                captured.children(OTHER_PARENT);
        List<EmbeddingBinding> retainedReverse = captured.parents(
                many.get(0).childDocumentId());
        EmbeddingBinding retainedRecord = captured.binding(
                many.get(0).bindingId());
        List<EmbeddingBinding> replacement = new ArrayList<>(many);
        replacement.add(binding(
                "binding-new", "/children/new",
                DocumentId.of("child-new"), 1L));
        EngineMetrics metrics = new EngineMetrics();

        // when
        ProcessEmbeddedGraphSnapshot advanced = captured.reconcileParent(
                PARENT, replacement, metrics);

        // then
        assertSame(unrelatedBucket, advanced.children(OTHER_PARENT));
        assertSame(retainedReverse, advanced.parents(
                many.get(0).childDocumentId()));
        assertSame(retainedRecord, advanced.binding(
                many.get(0).bindingId()));
        assertSame(many.get(0), advanced.children(PARENT).get(0));
        assertEquals(1L, metrics.counter(
                "temporal.graphForwardBucketsUpdated"));
        assertEquals(1L, metrics.counter(
                "temporal.graphReverseBucketsUpdated"));
        assertEquals(1L, metrics.counter(
                "temporal.graphBindingRecordsAdded"));
        assertEquals(0L, metrics.counter(
                "temporal.graphBindingRecordsRemoved"));
        assertEquals(64L, metrics.counter(
                "temporal.graphBindingRecordsRetained"));
    }

    private static EmbeddingBinding binding(
            String id,
            String path,
            DocumentId child,
            long activationGeneration) {
        return binding(PARENT, id, path, child, activationGeneration);
    }

    private static EmbeddingBinding binding(
            DocumentId parent,
            String id,
            String path,
            DocumentId child,
            long activationGeneration) {
        return new EmbeddingBinding(
                id,
                parent,
                path,
                child,
                activationGeneration,
                ActivationMode.IMPORT_FULL_HISTORY,
                null,
                "state-" + id,
                null,
                "proof-" + id,
                "attachment-" + id,
                ExternalOrderKey.of(List.of(100L, id)));
    }
}
