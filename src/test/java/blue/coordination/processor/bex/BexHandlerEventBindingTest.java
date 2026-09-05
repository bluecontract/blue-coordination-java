package blue.coordination.processor.bex;

import blue.bex.value.BexValue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BexHandlerEventBindingTest {
    @Test void matchingEvidenceSuppliesContentWithoutChangingTheExactBridgeOrEdge() {
        ExactEventIdentityEvidence proof = proof(607);
        FrozenNode bridge = bridge(proof.eventBlueId());
        BexValue binding = BexHandlerEventBinding.bind(bridge, proof);
        assertEquals(bridge.blueId(), binding.exactBlueId());
        assertEquals(proof.eventBlueId(), binding.get("event").exactBlueId());
        assertEquals("607", binding.get("event").get("amount").toSimple().toString());
        assertTrue(bridge.getProperties().get("event").isReferenceOnly());
    }

    @Test void differentOccurrenceCannotSupplyReferencedContent() {
        ExactEventIdentityEvidence expected = proof(607);
        BexValue binding = BexHandlerEventBinding.bind(bridge(expected.eventBlueId()), proof(608));
        assertEquals(expected.eventBlueId(), binding.get("event").exactBlueId());
        assertThrows(RuntimeException.class, () -> binding.get("event").get("amount"));
    }

    @Test void referenceOnlyEvidenceCannotInventAnEventBody() {
        ExactEventIdentityEvidence expected = proof(607);
        ExactEventIdentityEvidence opaque = ExactEventIdentityEvidence.verify(null,
                new Node().blueId(expected.eventBlueId()), expected.eventBlueId(), null);
        BexValue binding = BexHandlerEventBinding.bind(bridge(expected.eventBlueId()), opaque);
        assertThrows(RuntimeException.class, () -> binding.get("event").get("amount"));
    }

    private static ExactEventIdentityEvidence proof(int amount) {
        Node event = new Node().properties("amount", new Node().value(amount));
        return ExactEventIdentityEvidence.verify(null, event,
                DirectBlueIdCalculator.calculateBlueId(event), null);
    }

    private static FrozenNode bridge(String eventBlueId) {
        return FrozenNode.fromNode(new Node()
                .type(new Node().blueId(RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY))
                .properties("sourcePath", new Node().value("/child"))
                .properties("event", new Node().blueId(eventBlueId)));
    }
}
