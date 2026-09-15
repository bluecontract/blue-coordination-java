package blue.coordination.processor.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.value.BexValue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.NodeWireForm;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BexProcessingEventBindingTest {
    @Test
    void shouldExposeTheAdmittedOriginalSourceIdentityToNodeBlueId() {
        // given
        Node original = new Node().type(new Node().name("Inline processing event type"))
                .properties("message", new Node().properties("operation", new Node().value("go")))
                .properties("values", new Node().items(Arrays.asList(
                        new Node().value("first"), new Node().value("second"))));
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(language.processing()).build();
             BexEngine bex = BexEngine.builder().build()) {
            String expected = contracts.runtimeAccess().languageRuntime()
                    .calculateSourceDocumentBlueId(original.clone());
            ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(
                    contracts.runtimeAccess(), original, expected, null);
            assertNotEquals(expected, evidence.frozenEvent().blueId());

            // when
            BexValue binding = BexWorkflowContextFactory.processingEventBinding(evidence.frozenEvent(), evidence);
            Object observed = bex.compileAndExecute(BexProgramSource.expression(FrozenNode.fromNode(
                    new Node().properties("$nodeBlueId", new Node()
                            .properties("$processingEvent", new Node().value(""))))),
                    BexExecutionContext.builder()
                            .document(new FrozenBexDocumentView(FrozenNode.fromNode(Nodes.emptyObject())))
                            .processingEvent(binding).gasLimit(10000L).build())
                    .value().toSimple();

            // then
            assertEquals(expected, observed);
            assertEquals(expected, binding.exactBlueId());
            assertEquals("go", binding.get("message").get("operation").toSimple());
            assertEquals(NodeWireForm.get(original), NodeWireForm.get(evidence.event()));
        }
    }

    @Test
    void shouldKeepExpandedAnnotationsSeparateFromOriginalEventIdentity() {
        // given
        Node original = new Node().properties("kind", new Node().value("go"));
        String expected = DirectBlueIdCalculator.calculateBlueId(original);
        String annotation = DirectBlueIdCalculator.calculateBlueId(new Node().value("annotation"));
        original.blueId(annotation);
        original.getProperties().get("kind").blueId(annotation);
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, original, expected, null);

        // when
        BexValue binding = BexWorkflowContextFactory.processingEventBinding(evidence.frozenEvent(), evidence);

        // then
        assertEquals(expected, binding.exactBlueId());
        assertEquals("go", binding.get("kind").toSimple());
        assertEquals(annotation, evidence.event().getBlueId());
    }

    @Test
    void shouldKeepTheStandaloneSnapshotFallbackWhenNoIdentityEvidenceWasRetained() {
        // given
        FrozenNode original = FrozenNode.fromNode(new Node().properties("kind", new Node().value("go")));

        // when
        BexValue binding = BexWorkflowContextFactory.processingEventBinding(original, null);

        // then
        assertEquals(original.blueId(), binding.exactBlueId());
        assertEquals("go", binding.get("kind").toSimple());
    }

    @Test
    void shouldLeaveAnAbsentOrUnusedOriginalEventUndefined() {
        // given
        FrozenNode original = null;

        // when
        BexValue binding = BexWorkflowContextFactory.processingEventBinding(original, null);

        // then
        assertTrue(binding.isUndefined());
    }
}
