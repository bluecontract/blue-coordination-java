package blue.coordination.api;

import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Initialization has no external Timeline Entry but always has exact causal evidence. */
final class DocumentRevisionInitializationCausalityTest {
    private static final DocumentId DOCUMENT = DocumentId.of("causal-document");
    private static final ExactValue STATE = ExactValue.verified(
            new Node().properties("documentId", new Node().value(
                    DOCUMENT.value())));
    private static final ExternalOrderKey ORDER =
            ExternalOrderKey.of(List.of(100L, "admission"));
    private static final String CAUSE = ExactValue.verified(
            new Node().properties("kind", new Node().value(
                    "Document Admission"))).blueId();

    @Test
    void initializationRequiresOrderAndIdentityIndependently() {
        IllegalArgumentException missingOrder = assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentRevision(
                        DOCUMENT,
                        0L,
                        0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        STATE,
                        STATE,
                        null,
                        null,
                        CAUSE,
                        null,
                        List.of(),
                        0L));
        IllegalArgumentException missingIdentity = assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentRevision(
                        DOCUMENT, 0L, 0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        STATE, STATE, null, ORDER, null, null,
                        List.of(), 0L));

        assertTrue(missingOrder.getMessage().contains("exact cause"));
        assertTrue(missingIdentity.getMessage().contains("exact cause"));
    }

    @Test
    void initializationWithTimelineEntryIsRejected() {
        TimelineEntry entry = new TimelineEntry(
                STATE,
                STATE,
                ORDER,
                ORDER,
                new Timeline("timeline", "actor"),
                "operation",
                "channel",
                100L,
                1L,
                1L);

        assertThrows(IllegalArgumentException.class,
                () -> new DocumentRevision(
                        DOCUMENT,
                        0L,
                        0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        STATE,
                        STATE,
                        entry,
                        ORDER,
                        entry.blueId(),
                        null,
                        List.of(),
                        0L));
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentRevision(
                        DOCUMENT, 1L, 1L,
                        DocumentRevision.Kind.TIMELINE_ENTRY,
                        STATE, STATE, entry, ORDER, CAUSE, null,
                        List.of(), 0L));
    }

    @Test
    void processorManagedInitializationRetainsCauseWithoutInventingTimelineFact() {
        Node event = new Node()
                .properties("type", new Node().value("Coordination/Event"))
                .properties("kind", new Node().value("Initialized"));
        DocumentRevision revision = new DocumentRevision(
                DOCUMENT,
                0L,
                0L,
                DocumentRevision.Kind.INITIALIZATION,
                STATE,
                STATE,
                null,
                ORDER,
                CAUSE,
                null,
                List.of(event),
                7L);

        assertTrue(revision.sourceEntry().isEmpty());
        assertEquals(ORDER, revision.sourceOrderKey().orElseThrow());
        assertEquals(CAUSE,
                revision.causalEntryBlueId().orElseThrow());
        assertEquals(1, revision.emittedEvents().size());
        assertEquals(7L, revision.processingGas());
    }

    @Test
    void initializationRejectsTextThatIsNotAnExactBlueId() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentRevision(
                        DOCUMENT, 0L, 0L,
                        DocumentRevision.Kind.INITIALIZATION,
                        STATE, STATE, null, ORDER, "arbitrary-text", null,
                        List.of(), 0L));
    }
}
