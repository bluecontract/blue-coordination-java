package blue.coordination.internal;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact identity parity for a committed embedded transition retry. */
final class EmbeddedReceiptRetryIdentityIntegrationTest {
    private static final String PARENT = "embedded-state-parent";
    private static final String CHILD = "embedded-counter-A";

    @Test
    void committedReceiptRetryDoesNotConsumeClockOrCreatePhantomInput()
            throws Exception {
        Scenario uninterrupted = run(false);
        Scenario retried = run(true);

        assertEquals(uninterrupted.objectsAfterRecovery(),
                retried.objectsAfterRecovery(),
                "receipt recovery cannot add a whole object");
        assertEquals(uninterrupted.nextEntryTimestamp(),
                retried.nextEntryTimestamp(),
                "the next external event keeps its exact timestamp");
        assertEquals(uninterrupted.nextEntryBlueId(),
                retried.nextEntryBlueId(),
                "the next external event keeps its exact BlueId");
        assertEquals(uninterrupted.embeddedInputs(),
                retried.embeddedInputs(),
                "the next real embedded input keeps its timestamp, event "
                        + "BlueId, request BlueId, and receipt identity");
        assertEquals(uninterrupted.parentHistory(), retried.parentHistory());
        assertEquals(uninterrupted.childHistory(), retried.childHistory());
        assertEquals(uninterrupted.finalWholeObjectCount(),
                retried.finalWholeObjectCount(),
                "retry and uninterrupted stores must have exact cardinality");
    }

    private static Scenario run(boolean injectFailure) throws Exception {
        try (DefaultCoordinationEngine engine =
                     DefaultCoordinationEngine.create()) {
            String childSource = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            engine.start(CHILD, childSource);
            engine.start(PARENT, resource(
                    "examples/clean/embedded-state-parent.yaml"));
            TimelineEntry attachment = engine.append(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childSource)));

            if (injectFailure) {
                engine.failOnceAt(DefaultCoordinationEngine.FailurePoint
                        .AFTER_APPLYING_CHILD_REVISION);
                assertThrows(
                        DefaultCoordinationEngine.InjectedFailureException.class,
                        () -> engine.drainThrough(
                                attachment.sourceOrderKey()));
                engine.restartFromStores();
            }
            engine.drainThrough(attachment.sourceOrderKey());
            int objectsAfterRecovery = engine.wholeObjectCount();

            TimelineEntry next = engine.append(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 7"));
            engine.drainThrough(next.sourceOrderKey());

            return new Scenario(
                    objectsAfterRecovery,
                    next.timestampMicros(),
                    next.blueId(),
                    embeddedInputs(coordinator(engine)),
                    revisions(engine.history(PARENT)),
                    revisions(engine.history(CHILD)),
                    engine.wholeObjectCount());
        }
    }

    private static List<EmbeddedInputEvidence> embeddedInputs(
            SequentialDrainCoordinator coordinator)
            throws ReflectiveOperationException {
        Map<String, EmbeddedEpochInput> inputs = fieldValue(
                coordinator, "committedEmbeddedInputs");
        return inputs.values().stream()
                .map(input -> new EmbeddedInputEvidence(
                        input.inputId(),
                        input.applicationTimestampMicros(),
                        input.exactEvent().blueId(),
                        input.exactRequest().blueId(),
                        input.toChildEpoch()))
                .toList();
    }

    private static List<RevisionEvidence> revisions(
            List<DocumentRevision> revisions) {
        return revisions.stream().map(revision -> new RevisionEvidence(
                revision.documentId().value(),
                revision.epoch(),
                revision.rootApplicationOrder(),
                revision.kind(),
                revision.before().map(value -> value.blueId()).orElse(null),
                revision.after().blueId(),
                revision.sourceEntry().map(TimelineEntry::blueId)
                        .orElse(null),
                revision.sourceOrderKey().orElse(null),
                revision.causalEntryBlueId().orElse(null),
                revision.catchUpCause().orElse(null),
                revision.emittedEvents().stream()
                        .map(DirectBlueIdCalculator::calculateBlueId)
                        .toList(),
                revision.processingGas())).toList();
    }

    private static SequentialDrainCoordinator coordinator(
            DefaultCoordinationEngine engine)
            throws ReflectiveOperationException {
        return fieldValue(engine, "drainCoordinator");
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldValue(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static String resource(String name) throws IOException {
        try (var input = EmbeddedReceiptRetryIdentityIntegrationTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Scenario(
            int objectsAfterRecovery,
            long nextEntryTimestamp,
            String nextEntryBlueId,
            List<EmbeddedInputEvidence> embeddedInputs,
            List<RevisionEvidence> parentHistory,
            List<RevisionEvidence> childHistory,
            int finalWholeObjectCount) {
    }

    private record EmbeddedInputEvidence(
            String inputId,
            long applicationTimestampMicros,
            String exactEventBlueId,
            String exactRequestBlueId,
            long childEpoch) {
    }

    private record RevisionEvidence(
            String documentId,
            long epoch,
            long rootApplicationOrder,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            String sourceEntryBlueId,
            ExternalOrderKey sourceOrder,
            String causalEntryBlueId,
            DocumentRevision.CatchUpCause catchUpCause,
            List<String> eventBlueIds,
            long processingGas) {
    }
}
