package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class JournalFixtureBodyCodecTest {
    private final JournalFixtureBodyCodec codec = new JournalFixtureBodyCodec(4096);

    @Test void canonicalAndResolvedConstructionModesRoundTripExactly() {
        Node body = new Node().properties("value", new Node().value("same content"));
        FrozenNode canonical = FrozenNode.fromNode(body);
        FrozenNode resolved = FrozenNode.fromResolvedNode(body);
        assertNotEquals(canonical.resolvedStructuralKey(), resolved.resolvedStructuralKey());
        assertEquals(canonical.resolvedStructuralKey(), codec.decode(codec.encode(canonical)).resolvedStructuralKey());
        assertEquals(resolved.resolvedStructuralKey(), codec.decode(codec.encode(resolved)).resolvedStructuralKey());
        assertTrue(codec.decode(codec.encode(canonical)).isStrictBlueIdValidation());
        assertFalse(codec.decode(codec.encode(resolved)).isStrictCanonical());
    }

    @Test void unsupportedUncheckedAndMixedModesAreRejectedRatherThanNormalized() {
        FrozenNode unchecked = FrozenNode.fromUncheckedCanonicalNode(new Node().value("content"));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(unchecked));
        FrozenNode mixed = FrozenNode.fromNode(new Node().properties("strict", new Node().value("strict")))
                .withProperty("resolved", FrozenNode.fromResolvedNode(new Node().value("resolved")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(mixed));
    }
}
