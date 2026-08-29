package blue.coordination.internal;

import blue.coordination.sdk.ExactNodeEvidence;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.api.NodeProviderOutcome;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.NodeProvider;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused authentication coverage for application cyclic provider evidence. */
final class VerifiedApplicationCyclicProviderTest {
    @Test
    void verifiedAdapterPublishesBodyAndCompleteProofForEveryMember() {
        // given
        BasicNodeProvider source = new BasicNodeProvider(
                new Node().items(List.of(
                        new Node().name("adapter-cycle-a").properties(
                                "peer", new Node().blueId("this#1"))
                                .contracts(new Node().properties(
                                        "embedded", processEmbedded("/peer"))),
                        new Node().name("adapter-cycle-b").properties(
                                "peer", new Node().blueId("this#0"))
                                .contracts(new Node().properties(
                                        "embedded", processEmbedded("/peer"))))));
        String first = source.getBlueIdByName("adapter-cycle-a");
        String second = source.getBlueIdByName("adapter-cycle-b");
        CyclicSetProof proof = source.cyclicSetProofFor(first)
                .proof().orElseThrow();
        Map<String, ExactNodeEvidence> evidence = Map.of(
                first, ExactNodeEvidence.cyclic(
                        json(source.fetchByBlueId(first).get(0)), proof),
                second, ExactNodeEvidence.cyclic(
                        json(source.fetchByBlueId(second).get(0)), proof));
        ExactNodeProvider application = ExactNodeProvider.withEvidence(
                requested -> Optional.ofNullable(evidence.get(requested)));

        // when
        NodeProvider verified = Contracts10StaticEmbeddedAdmissionCompiler
                .verifiedProvider(application);
        List<Node> firstRead = verified.fetchByBlueId(first);
        List<Node> secondRead = verified.fetchByBlueId(second);

        // then
        assertEquals("adapter-cycle-a", firstRead.get(0).getName());
        assertEquals("adapter-cycle-b", secondRead.get(0).getName());
        assertTrue(verified instanceof CyclicAwareNodeProvider);
        CyclicAwareNodeProvider cyclic = (CyclicAwareNodeProvider) verified;
        assertEquals(NodeProviderOutcome.FOUND,
                cyclic.cyclicSetProofFor(first).outcome());
        assertEquals(NodeProviderOutcome.FOUND,
                cyclic.cyclicSetProofFor(second).outcome());
        assertEquals(proof.declaredPlaceholderSet().size(),
                cyclic.cyclicSetProofFor(first).proof().orElseThrow()
                        .declaredPlaceholderSet().size());
    }

    private static String json(Node node) {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(node);
    }

    private static Node processEmbedded(String path) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(
                        new Node().value(path)));
    }
}
