package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Determinism and coverage proofs for the cycle-capable graph index. */
final class ProcessEmbeddedComponentIndexTest {
    private static final DocumentId A = DocumentId.of("a");
    private static final DocumentId B = DocumentId.of("b");
    private static final DocumentId C = DocumentId.of("c");
    private static final DocumentId D = DocumentId.of("d");

    @Test
    void selfCycleIsOneCyclicComponentWithoutCondensationEdges() {
        // given
        List<EmbeddingBinding> bindings = List.of(binding("a-a", A, A));

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromBindings(bindings);
        ProcessEmbeddedComponentIndex.Component component =
                index.component(A);

        // then
        assertEquals(List.of(A), component.members());
        assertTrue(component.cyclic());
        assertEquals(List.of(component), index.components());
        assertEquals(List.of(), index.targets(component));
        assertEquals(List.of(), index.sources(component));
        assertEquals(List.of(A), index.cohort(A).members());
    }

    @Test
    void twoCycleCollapsesToOneScalarOrderedComponent() {
        // given
        List<EmbeddingBinding> bindings = List.of(
                binding("b-a", B, A),
                binding("a-b", A, B));

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromBindings(bindings);
        ProcessEmbeddedComponentIndex.Component component =
                index.component(A);

        // then
        assertEquals(component, index.component(B));
        assertEquals(List.of(A, B), component.members());
        assertTrue(component.cyclic());
        assertEquals(List.of(component), index.components());
    }

    @Test
    void dagCondensationOrdersEveryTargetBeforeItsSource() {
        // given
        List<EmbeddingBinding> bindings = List.of(
                binding("a-c", A, C),
                binding("c-d", C, D),
                binding("a-b", A, B),
                binding("b-d", B, D));

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromBindings(bindings);

        // then
        assertEquals(List.of(
                        List.of(D),
                        List.of(B),
                        List.of(C),
                        List.of(A)),
                memberLists(index.components()));
        assertTargetBeforeSource(index, A, B);
        assertTargetBeforeSource(index, A, C);
        assertTargetBeforeSource(index, B, D);
        assertTargetBeforeSource(index, C, D);
        assertFalse(index.component(A).cyclic());
    }

    @Test
    void disconnectedCohortsUseMinimumMemberScalarOrder() {
        // given
        DocumentId z = DocumentId.of("z");

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromBindings(List.of(
                        binding("z-a", z, A),
                        binding("b-c", B, C)));

        // then
        assertEquals(List.of(
                        List.of(A, z),
                        List.of(B, C)),
                index.cohorts().stream()
                        .map(ProcessEmbeddedComponentIndex.Cohort::members)
                        .toList());
        assertEquals(List.of(List.of(A), List.of(z)),
                memberLists(index.cohorts().get(0).components()));
        assertEquals(List.of(List.of(C), List.of(B)),
                memberLists(index.cohorts().get(1).components()));
        assertEquals(index.cohort(A), index.cohort(z));
        assertFalse(index.cohort(A).equals(index.cohort(B)));
    }

    @Test
    void bindingInsertionOrderCannotChangeAnyIndexSurface() {
        // given
        List<EmbeddingBinding> forward = List.of(
                binding("a-b", A, B),
                binding("b-a", B, A),
                binding("b-c", B, C),
                binding("c-d", C, D));
        List<EmbeddingBinding> reverse = new ArrayList<>(forward);
        java.util.Collections.reverse(reverse);

        // when
        ProcessEmbeddedComponentIndex first =
                ProcessEmbeddedComponentIndex.fromBindings(forward);
        ProcessEmbeddedComponentIndex second =
                ProcessEmbeddedComponentIndex.fromBindings(reverse);

        // then
        assertEquals(first.documents(), second.documents());
        assertEquals(first.components(), second.components());
        assertEquals(first.cohorts(), second.cohorts());
        for (DocumentId document : first.documents()) {
            assertEquals(first.component(document), second.component(document));
            assertEquals(first.cohort(document), second.cohort(document));
            assertEquals(first.targets(first.component(document)),
                    second.targets(second.component(document)));
            assertEquals(first.sources(first.component(document)),
                    second.sources(second.component(document)));
        }
    }

    @Test
    void everyEndpointAndExplicitIsolatedDocumentIsCoveredExactlyOnce() {
        // given
        DocumentId isolated = DocumentId.of("isolated");

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromDocumentsAndBindings(
                        List.of(isolated, D, A),
                        List.of(
                                binding("a-b", A, B),
                                binding("b-c", B, C)));
        List<DocumentId> componentMembers = index.components().stream()
                .flatMap(component -> component.members().stream())
                .toList();

        // then
        assertEquals(List.of(A, B, C, D, isolated), index.documents());
        assertEquals(index.documents().size(),
                new LinkedHashSet<>(componentMembers).size());
        assertEquals(new LinkedHashSet<>(index.documents()),
                new LinkedHashSet<>(componentMembers));
        for (DocumentId document : index.documents()) {
            assertTrue(index.component(document).members().contains(document));
            assertTrue(index.cohort(document).members().contains(document));
        }
        assertEquals(List.of(D), index.component(D).members());
        assertEquals(List.of(isolated), index.cohort(isolated).members());
        assertThrows(IllegalArgumentException.class,
                () -> index.component(DocumentId.of("missing")));
    }

    @Test
    void documentOrderingUsesUnicodeScalarValuesInsteadOfUtf16Units() {
        // given
        DocumentId privateUseBmp = DocumentId.of("\uE000");
        DocumentId supplementary = DocumentId.of("\uD800\uDC00");

        // when
        ProcessEmbeddedComponentIndex index =
                ProcessEmbeddedComponentIndex.fromDocumentsAndBindings(
                        List.of(supplementary, privateUseBmp), List.of());

        // then
        assertTrue(privateUseBmp.compareTo(supplementary) < 0);
        assertEquals(List.of(privateUseBmp, supplementary),
                index.documents());
        assertEquals(List.of(
                        List.of(privateUseBmp),
                        List.of(supplementary)),
                index.cohorts().stream()
                        .map(ProcessEmbeddedComponentIndex.Cohort::members)
                        .toList());
    }

    @Test
    void legacySnapshotRejectsCyclesUntilCoordinatorSelectsExplicitIndex() {
        // given
        EmbeddingBinding aToB = binding("a-b", A, B);
        EmbeddingBinding bToA = binding("b-a", B, A);
        ProcessEmbeddedGraphSnapshot legacy =
                ProcessEmbeddedGraphSnapshot.empty()
                        .reconcileParent(A, List.of(aToB));

        // when
        assertThrows(IllegalStateException.class,
                () -> legacy.reconcileParent(B, List.of(bToA)));
        ProcessEmbeddedComponentIndex explicit =
                ProcessEmbeddedComponentIndex.fromBindings(
                        List.of(aToB, bToA));

        // then
        assertTrue(explicit.component(A).cyclic());
        assertEquals(List.of(A, B), explicit.component(A).members());
        assertEquals(legacy.componentIndex().documents(), List.of(A, B));
    }

    @Test
    void duplicateBindingIdentityIsRejectedDeterministically() {
        // given
        List<EmbeddingBinding> duplicateBindings = List.of(
                binding("duplicate", A, B),
                binding("duplicate", C, D));

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ProcessEmbeddedComponentIndex.fromBindings(
                        duplicateBindings));

        // then
        assertTrue(failure.getMessage().contains(
                "Duplicate Process Embedded binding"));
    }

    private static void assertTargetBeforeSource(
            ProcessEmbeddedComponentIndex index,
            DocumentId source,
            DocumentId target) {
        int sourcePosition = index.components().indexOf(
                index.component(source));
        int targetPosition = index.components().indexOf(
                index.component(target));
        assertTrue(targetPosition < sourcePosition,
                () -> target + " must precede " + source);
    }

    private static List<List<DocumentId>> memberLists(
            List<ProcessEmbeddedComponentIndex.Component> components) {
        return components.stream()
                .map(ProcessEmbeddedComponentIndex.Component::members)
                .toList();
    }

    private static EmbeddingBinding binding(
            String id,
            DocumentId parent,
            DocumentId child) {
        return new EmbeddingBinding(
                id,
                parent,
                "/" + id,
                child,
                1L,
                ActivationMode.IMPORT_FULL_HISTORY,
                null,
                "state-" + id,
                null,
                "proof-" + id,
                "attachment-" + id,
                ExternalOrderKey.of(List.of(100L, id)));
    }
}
