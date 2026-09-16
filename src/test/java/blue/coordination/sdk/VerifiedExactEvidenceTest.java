package blue.coordination.sdk;

import blue.coordination.api.ExactValue;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class VerifiedExactEvidenceTest {
    @Test void ordinaryIssuerRetainsExactBytesAndDetachedMutableCopiesCannotAlterThem() {
        var mutable = new Node().properties("z", new Node().value(1), "a", new Node().value("original"));
        var value = ExactBlueValue.wrap(ExactValue.verified(mutable));
        String expected = value.json();
        var issued = value.verifiedEvidence();
        mutable.getProperties().get("a").value("changed input"); value.copyNode().name("changed copy");
        assertSame(issued, value.verifiedEvidence());
        assertEquals(expected, issued.exactContent()); assertEquals(value.blueId(), issued.blueId());
        assertTrue(issued.declaredPlaceholderSet().isEmpty());
        assertEquals(expected, ExactNodeProvider.of(value).findExactContent(value.blueId()).orElseThrow());
        assertTrue(Arrays.stream(VerifiedExactEvidence.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test void cyclicIssuerRetainsCompleteVerifiedProofAndRejectsTamperedUntrustedInputs() {
        var provider = new BasicNodeProvider(new Node().items(List.of(
                new Node().name("artifact-a").properties("peer", new Node().blueId("this#1")),
                new Node().name("artifact-b").properties("peer", new Node().blueId("this#0")))));
        String id = provider.getBlueIdByName("artifact-a");
        var proof = provider.cyclicSetProofFor(id).proof().orElseThrow();
        Node original = provider.fetchByBlueId(id).get(0);
        var value = ExactBlueValue.wrap(ExactValue.fromVerifiedProviderEvidence(id, original, proof));
        var issued = value.verifiedEvidence();
        assertEquals(value.json(), issued.exactContent()); assertEquals(id, issued.blueId());
        var members = issued.declaredPlaceholderSet().orElseThrow();
        assertEquals(proof.declaredPlaceholderSet().stream()
                .map(UncheckedObjectMapper.JSON_MAPPER::writeValueAsString).toList(), members);
        assertThrows(UnsupportedOperationException.class, () -> members.clear());
        original.name("tampered"); proof.declaredPlaceholderSet().get(0).name("caller copy");
        assertEquals(issued.exactContent(), value.json()); assertSame(issued, value.verifiedEvidence());
        assertThrows(IllegalArgumentException.class, () -> ExactValue.fromVerifiedProviderEvidence(id, original, proof));
        assertEquals(members, ExactNodeProvider.of(value).findExactEvidence(id).orElseThrow().declaredPlaceholderSet().orElseThrow());
    }
}
