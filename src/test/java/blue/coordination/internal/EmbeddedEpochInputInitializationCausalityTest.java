package blue.coordination.internal;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Internal application time never replaces the initialization's causal source order. */
final class EmbeddedEpochInputInitializationCausalityTest {
    private static final DocumentId PARENT = DocumentId.of("causal-parent");
    private static final DocumentId CHILD = DocumentId.of("causal-child");
    private static final ExternalOrderKey ATTACHMENT_ORDER =
            ExternalOrderKey.of(List.of(100L, "attachment", "entry"));

    @Test
    void parentInputKeepsAttachmentOrderAndSeparateApplicationOrder() {
        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);
        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            WholeRequestEntryFactory entryFactory =
                    new WholeRequestEntryFactory(runtime, objects, metrics);
            ExactValue childState = objects.put(
                    new Node()
                            .properties("documentId",
                                    new Node().value(CHILD.value()))
                            .properties("initializationCount",
                                    new Node().value(1)),
                    "causal-child-state");
            DocumentRevision initialization = new DocumentRevision(
                    CHILD,
                    0L,
                    0L,
                    DocumentRevision.Kind.INITIALIZATION,
                    null,
                    childState,
                    null,
                    ATTACHMENT_ORDER,
                    childState.blueId(),
                    null,
                    List.of(),
                    17L);
            EmbeddingBinding binding = new EmbeddingBinding(
                    "causal-binding",
                    PARENT,
                    "/child",
                    CHILD,
                    1L,
                    ActivationMode.IMPORT_FULL_HISTORY,
                    null,
                    childState.blueId(),
                    null,
                    "proof",
                    childState.blueId(),
                    ATTACHMENT_ORDER);

            EmbeddedEpochInput input = EmbeddedEpochInput.create(
                    entryFactory,
                    objects,
                    binding,
                    initialization,
                    500L,
                    null);

            assertEquals(ATTACHMENT_ORDER, input.sourceOrder());
            assertEquals(childState.blueId(), input.originalEntryBlueId());
            assertEquals(500L, input.applicationTimestampMicros());
            assertNotEquals(input.sourceOrder(), input.applicationOrder());
            assertTrue(input.eventOccurrences().isEmpty());
        }
    }
}
