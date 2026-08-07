package blue.coordination.engine.fastpath;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;

final class ContentAddressedNodeInternerTest {
    @Test
    void reusesVerifiedBodyAcrossInventoriesAndEpochs() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(0);
        Node body = new Node().properties("stable", new Node().value(true));
        String id = DirectBlueIdCalculator.calculateBlueId(body);

        ExactNodeHandle first = interner.internCopy(id, body);
        interner.retainAll(Collections.singletonList(id));
        ExactNodeHandle second = interner.internCopy(id, body.clone());

        assertSame(first, second);
        assertEquals(1, interner.size());
        interner.releaseAll(Collections.singletonList(id));
        assertEquals(0, interner.size());
    }

    @Test
    void rejectsConflictingContentEvenWhenTheClaimedKeyAlreadyExists() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(4);
        Node admitted = new Node().value("admitted");
        String id = DirectBlueIdCalculator.calculateBlueId(admitted);
        interner.internCopy(id, admitted);

        assertThrows(
                IllegalArgumentException.class,
                () -> interner.internCopy(id, new Node().value("forged")));
    }

    @Test
    void separatesCanonicalAndProcessingRepresentations() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(4);
        Node body = new Node().value("same-canonical-content");
        String id = DirectBlueIdCalculator.calculateBlueId(body);

        ExactNodeHandle physical = interner.internCopy(id, body);
        ExactNodeHandle processing = interner.internCopy(
                "processing:inventory", id, body);

        assertNotSame(physical, processing);
        assertEquals(2, interner.size());
    }
}
