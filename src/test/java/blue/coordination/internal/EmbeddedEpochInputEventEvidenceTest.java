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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-closed validation for exact event evidence before parent PROCESS. */
final class EmbeddedEpochInputEventEvidenceTest {
    private static final DocumentId PARENT = DocumentId.of("event-parent");
    private static final DocumentId CHILD = DocumentId.of("event-child");
    private static final ExternalOrderKey ATTACHMENT_ORDER =
            ExternalOrderKey.of(List.of(100L, "attachment"));

    @Test
    void eventWithoutExactEffectiveTypeCannotCreateAParentProcessInput() {
        // given

        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);

        try (BlueRuntime runtime = BlueRuntime.create(objects)) {
            WholeRequestEntryFactory entryFactory =
                    new WholeRequestEntryFactory(runtime, objects, metrics);
            ExactValue childState = objects.put(
                    new Node()
                            .properties("documentId",
                                    new Node().value(CHILD.value()))
                            .properties("value", new Node().value(1)),
                    "malformed-event-child-state");
            Node eventWithoutType = new Node().properties(
                    "kind", new Node().value("Missing/Type"));
            DocumentRevision childRevision = new DocumentRevision(
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
                    List.of(eventWithoutType),
                    0L);

            // when
            EmbeddingBinding binding = new EmbeddingBinding(
                    "event-binding",
                    PARENT,
                    "/child",
                    CHILD,
                    1L,
                    ActivationMode.IMPORT_FULL_HISTORY,
                    null,
                    childState.blueId(),
                    null,
                    "test-admission-proof",
                    childState.blueId(),
                    ATTACHMENT_ORDER);

            // then
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> EmbeddedEpochInput.create(
                            entryFactory,
                            objects,
                            binding,
                            childRevision,
                            101L,
                            null));

            assertTrue(failure.getMessage().contains(
                    "has no exact effective type"), failure::getMessage);
            assertEquals(childState.blueId(), childRevision.after().blueId());
            assertEquals(0L, metrics.counter(
                    "process.embeddedEpochProcessCalls"),
                    "invalid evidence must fail before frozen parent PROCESS");
        }
    }
}
