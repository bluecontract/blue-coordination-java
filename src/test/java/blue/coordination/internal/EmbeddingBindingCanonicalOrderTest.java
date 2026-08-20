package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Process Embedded siblings follow Unicode code-point path order, never BlueId order. */
final class EmbeddingBindingCanonicalOrderTest {
    private static final ExternalOrderKey ORDER =
            ExternalOrderKey.of(List.of(100L, "entry"));

    @Test
    void supplementaryCodePointUsesCanonicalTextOrderRatherThanUtf16Order() {
        // given

        String privateUse = "/games/\uE000";

        // when
        String supplementary = "/games/\uD800\uDC00";

        // then
        assertNotEquals(
                Integer.signum(privateUse.compareTo(supplementary)),
                Integer.signum(ExternalOrderKey.compareTextCodePoints(
                        privateUse, supplementary)),
                "this pair deliberately distinguishes UTF-16 from code points");

        List<EmbeddingBinding> bindings = new ArrayList<>(List.of(
                binding(supplementary, "aaa-child"),
                binding(privateUse, "zzz-child")));
        bindings.sort(EmbeddingBinding.WITHIN_PARENT_ORDER);

        assertEquals(List.of(privateUse, supplementary),
                bindings.stream().map(
                        EmbeddingBinding::absolutePath).toList());
    }

    @Test
    void absolutePathPrecedesChildIdentityAndActivationGeneration() {
        // given

        List<EmbeddingBinding> bindings = new ArrayList<>(List.of(
                binding("/games/zulu", "aaa-child", 1L),
                binding("/games/alpha", "zzz-child", 9L),
                binding("/games/middle", "middle-child", 3L)));

        // when
        bindings.sort(EmbeddingBinding.WITHIN_PARENT_ORDER);

        // then
        assertEquals(List.of(
                        "/games/alpha",
                        "/games/middle",
                        "/games/zulu"),
                bindings.stream().map(
                        EmbeddingBinding::absolutePath).toList());
    }

    @Test
    void documentAndRoutingTextUseTheSameCodePointOrder() {
        // given

        String privateUse = "\uE000";
        String supplementary = "\uD800\uDC00";
        List<EmbeddingBinding> bindings = new ArrayList<>(List.of(
                binding("/same", supplementary),
                binding("/same", privateUse)));

        // when
        bindings.sort(EmbeddingBinding.WITHIN_PARENT_ORDER);

        // then
        assertEquals(List.of(privateUse, supplementary), bindings.stream()
                .map(binding -> binding.childDocumentId().value()).toList());
        assertEquals(-1, Integer.signum(
                DocumentId.of(privateUse).compareTo(
                        DocumentId.of(supplementary))));

        RoutingSurface surface = new RoutingSurface(List.of(
                definition("/" + supplementary, supplementary),
                definition("/" + privateUse, privateUse)), false);
        assertEquals(List.of("/" + privateUse, "/" + supplementary),
                surface.definitions().stream()
                        .map(RoutingSurface.Definition::scopePath).toList());
        assertEquals(List.of(privateUse, supplementary),
                surface.definitions().get(0).sources().stream()
                        .map(RoutingSurface.SourceAddress::timelineId).toList());
    }

    @Test
    void barrierCandidatesUseActivationGenerationBeforeChildEpoch() {
        // given

        ExactValue state = ExactValue.verified(new Node().value("state"));
        DocumentRevision laterEpoch = revision(state, 9L);
        DocumentRevision earlierEpoch = revision(state, 0L);
        List<SequentialDrainCoordinator.BarrierCandidate> candidates =
                new ArrayList<>(List.of(
                        SequentialDrainCoordinator.BarrierCandidate.revision(
                                "barrier", 1,
                                binding("/same", "same-child", 2L),
                                earlierEpoch, ORDER),
                        SequentialDrainCoordinator.BarrierCandidate.revision(
                                "barrier", 1,
                                binding("/same", "same-child", 1L),
                                laterEpoch, ORDER)));

        // when
        candidates.sort(SequentialDrainCoordinator.BarrierCandidate.ORDER);

        // then
        assertEquals(List.of(1L, 2L), candidates.stream()
                .map(candidate -> candidate.binding().activationGeneration())
                .toList(), "activation generation must precede child epoch");
    }

    @Test
    void bindingIdentityIsInjectiveWhenParentAndPathContainDelimiters() {
        // given

        DocumentId firstParent = DocumentId.of("tenant");
        String firstPath = "/offers|/summer";
        DocumentId secondParent = DocumentId.of("tenant|/offers");

        // when
        String secondPath = "/summer";

        // then
        assertEquals(
                firstParent.value() + "|" + firstPath + "|1",
                secondParent.value() + "|" + secondPath + "|1",
                "the former delimiter concatenation collides for this tuple");

        String firstId = SequentialDrainCoordinator.bindingId(
                firstParent, firstPath, 1L);
        String secondId = SequentialDrainCoordinator.bindingId(
                secondParent, secondPath, 1L);

        assertNotEquals(firstId, secondId);
        assertEquals(firstId, SequentialDrainCoordinator.bindingId(
                firstParent, firstPath, 1L),
                "structured binding identity must remain deterministic");
        ProcessEmbeddedGraphSnapshot graph = ProcessEmbeddedGraphSnapshot
                .empty()
                .reconcileParent(firstParent, List.of(binding(
                        firstParent, firstId, firstPath, "first-child", 1L)))
                .reconcileParent(secondParent, List.of(binding(
                        secondParent, secondId, secondPath,
                        "second-child", 1L)));
        assertEquals(2, graph.bindings().size(),
                "both formerly colliding occurrences must coexist");
    }

    private static EmbeddingBinding binding(
            String path,
            String child) {
        return binding(path, child, 1L);
    }

    private static EmbeddingBinding binding(
            String path,
            String child,
            long generation) {
        return binding(
                DocumentId.of("parent"),
                "binding|" + path + "|" + child + "|" + generation,
                path,
                child,
                generation);
    }

    private static EmbeddingBinding binding(
            DocumentId parent,
            String bindingId,
            String path,
            String child,
            long generation) {
        return new EmbeddingBinding(
                bindingId,
                parent,
                path,
                DocumentId.of(child),
                generation,
                ActivationMode.IMPORT_FULL_HISTORY,
                null,
                "admitted-blue-id",
                null,
                "proof",
                "attachment-entry",
                ORDER);
    }

    private static RoutingSurface.Definition definition(
            String path,
            String operation) {
        return new RoutingSurface.Definition(
                path, operation, "channel",
                List.of(
                        new RoutingSurface.SourceAddress(
                                "\uD800\uDC00", "actor"),
                        new RoutingSurface.SourceAddress(
                                "\uE000", "actor")));
    }

    private static DocumentRevision revision(ExactValue state, long epoch) {
        return new DocumentRevision(
                DocumentId.of("same-child"), epoch, epoch,
                DocumentRevision.Kind.CATCH_UP_COMPLETED,
                state, state, null, null, List.of(), 0L);
    }
}
