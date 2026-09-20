package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.ExactNodeProvider;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.repo.coordination.OperationRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contrasting exact type/shape and missing-evidence admission controls. */
final class GeneralTimelineClassificationTest {
    @Test
    void businessShapesRetainExactInputWithAbsentOperationFacet() {
        // given
        List<Node> messages = List.of(new Node().value("credit"), new Node().value(7L),
                new Node().items(List.of(new Node().value("credit"))), Nodes.emptyObject(),
                new Node().properties("operation", new Node().value("business"))
                        .properties("channel", new Node().value("not-routing"))
                        .properties("document", new Node().value("not-a-target"))
                        .properties("requireExactDocumentVersion", new Node().value("business"))
                        .properties("request", Nodes.emptyObject())
                        .properties("mandate", new Node().properties("onBehalfOf", new Node().value("data"))));
        for (Node message : messages) {
            try (var sdk = BlueCoordination.inMemory()) {
                var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
                Node event = GeneralTimelineEntryMother.event(message);
                // when
                var first = engine.appendTimelineEntry(event);
                var duplicate = engine.appendTimelineEntry(event);
                // then
                assertTrue(first.stored()); assertFalse(duplicate.stored());
                assertTrue(first.entry().operationDetails().isEmpty());
                assertTrue(ExactValue.verified(event).sameExactValue(first.entry().exactEvent()));
                assertEquals(1, duplicate.journalEntryCount());
                assertEquals(first.entry().blueId(), duplicate.entry().blueId());
            }
        }
    }

    @Test
    void verifiedNonOperationSubtypeRemainsGeneralAndCorruptEvidenceCannotBecomeGeneral() {
        // given
        var declared = ExactValue.verified(new Node().name("BusinessCredit"));
        Node message = new Node().type(new Node().blueId(declared.blueId()))
                .properties("amount", new Node().value(7L));
        Node event = GeneralTimelineEntryMother.event(message);
        var available = new AtomicBoolean(false);
        try (var sdk = BlueCoordination.builder().exactNodeProvider(id -> id.equals(declared.blueId())
                ? Optional.of(blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(
                        available.get() ? declared.copyNode() : new Node().name("WrongCredit")))
                : Optional.empty()).build()) {
            var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
            // when
            assertThrows(RuntimeException.class, () -> engine.appendTimelineEntry(event));
            available.set(true);
            var accepted = engine.appendTimelineEntry(event);
            // then
            assertTrue(accepted.stored());
            assertEquals(1L, accepted.entry().globalSequence());
            assertEquals(1L, accepted.entry().timelineSequence());
            assertTrue(accepted.entry().operationDetails().isEmpty());
            assertTrue(ExactValue.verified(event).sameExactValue(accepted.entry().exactEvent()));
        }
    }

    @Test
    void malformedTypedOperationsNeverFallBackToGeneral() {
        // given
        List<Node> bad = List.of(operation().properties("operation", new Node().value("")),
                operation().properties("channel", new Node().value(7L)),
                new Node().type(new Node().blueId(OperationRequest.blueId())),
                operation().properties("requireExactDocumentVersion", new Node().value("true")),
                operation().properties("requireExactDocumentVersion", new Node().value(true)));
        for (Node message : bad) {
            try (var sdk = BlueCoordination.inMemory()) {
                var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
                // when
                assertThrows(IllegalArgumentException.class,
                        () -> engine.appendTimelineEntry(GeneralTimelineEntryMother.event(message)));
                var accepted = engine.appendTimelineEntry(GeneralTimelineEntryMother.event(operation()));
                // then
                assertEquals(1L, accepted.entry().globalSequence());
                assertEquals(1L, accepted.entry().timelineSequence());
                assertEquals("credit", accepted.entry().operation());
                assertEquals("owner", accepted.entry().channel());
            }
        }
    }

    @Test
    void missingTypeEvidenceRejectsAndCanRetryTheSameExactInput() {
        // given
        Node type = new Node().name("CreditRecorded").type(new Node().blueId(OperationRequest.blueId()));
        ExactValue exactType = ExactValue.verified(type);
        AtomicBoolean available = new AtomicBoolean();
        try (var sdk = BlueCoordination.builder().exactNodeProvider(id -> available.get()
                && id.equals(exactType.blueId()) ? Optional.of(blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(exactType.copyNode())) : Optional.empty()).build()) {
            var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
            Node event = GeneralTimelineEntryMother.event(operation().type(new Node().blueId(exactType.blueId())));
            String original = ExactValue.verified(event).blueId();
            // when
            assertThrows(RuntimeException.class, () -> engine.appendTimelineEntry(event));
            available.set(true);
            var accepted = engine.appendTimelineEntry(event);
            // then
            assertEquals(original, accepted.entry().blueId());
            assertEquals(1L, accepted.entry().globalSequence());
            assertTrue(accepted.entry().operationDetails().isPresent());
        }
    }

    @Test
    void referencedGeneralMessageIsClassifiedWithoutRewritingEnvelope() {
        // given
        ExactValue message = ExactValue.verified(new Node().properties("amount", new Node().value(7L)));
        try (var sdk = BlueCoordination.builder()
                .exactNodeProvider(ExactNodeProvider.of(message.blueId(), blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(message.copyNode()))).build()) {
            var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
            Node event = GeneralTimelineEntryMother.event(new Node().blueId(message.blueId()));
            // when
            var accepted = engine.appendTimelineEntry(event);
            // then
            assertTrue(accepted.entry().operationDetails().isEmpty());
            assertTrue(accepted.entry().exactEvent().canonicalAt("/message").isReferenceOnly());
            assertTrue(ExactValue.verified(event).sameExactValue(accepted.entry().exactEvent()));
        }
    }

    @Test
    void envelopeDelegationRemainsRejected() {
        // given
        try (var sdk = BlueCoordination.inMemory()) {
            var engine = sdk.advanced().rawEngine(); engine.registerTimeline("general-entry", "alice");
            Node event = GeneralTimelineEntryMother.event(Nodes.emptyObject());
            // when
            assertThrows(IllegalArgumentException.class,
                    () -> engine.appendTimelineEntry(event.clone().properties("onBehalfOf", Nodes.emptyObject())));
            var accepted = engine.appendTimelineEntry(event);
            // then
            assertEquals(1L, accepted.entry().globalSequence());
        }
    }

    private static Node operation() {
        return new Node().type(new Node().blueId(OperationRequest.blueId()))
                .properties("operation", new Node().value("credit"))
                .properties("channel", new Node().value("owner"));
    }
}
