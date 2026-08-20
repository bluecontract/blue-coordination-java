package blue.coordination.internal;

import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.DocumentSnapshot;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-closed application reads for each independent graph proof dimension. */
final class ApplicationReadinessProofIntegrationTest {
    private static final DocumentId PARENT =
            DocumentId.of("embedded-state-parent");
    private static final DocumentId CHILD =
            DocumentId.of("embedded-counter-A");

    @Test
    void rejectsCurrentReadyMarkerWhileAnEmbeddedBarrierIsOpen()
            throws Exception {
        try (ReadyFixture fixture = readyFixture()) {
            // given
            Map<DocumentId, String> openBarriers = mapField(
                    fixture.coordinator(), "openBarrierByParent");

            // when
            openBarriers.put(PARENT, "test-open-barrier");

            // then
            assertRejectedButAuditable(
                    fixture, "an embedded catch-up barrier remains open");
        }
    }

    @Test
    void rejectsCurrentReadyMarkerWhenTheCommittedOccurrenceHasNoBinding()
            throws Exception {
        try (ReadyFixture fixture = readyFixture()) {
            // given
            ProcessEmbeddedGraphSnapshot withoutBinding = fixture
                    .coordinator().graphSnapshot().reconcileParent(
                            PARENT, List.of());

            // when
            setField(fixture.coordinator(), "graph", withoutBinding);

            // then
            assertRejectedButAuditable(
                    fixture, "published graph does not match current "
                            + "Process Embedded occurrence count");
        }
    }

    @Test
    void rejectsCurrentReadyMarkerWhenTheBindingHasNoCursor()
            throws Exception {
        try (ReadyFixture fixture = readyFixture()) {
            // given
            Map<String, EmbeddedEpochCursor> cursors = mapField(
                    fixture.coordinator(), "cursors");

            // when
            cursors.remove(fixture.binding().bindingId());

            // then
            assertRejectedButAuditable(
                    fixture, "missing embedded epoch cursor");
        }
    }

    @Test
    void rejectsCurrentReadyMarkerWhenTheCursorIsBehindItsChild()
            throws Exception {
        try (ReadyFixture fixture = readyFixture()) {
            // given
            Map<String, EmbeddedEpochCursor> cursors = mapField(
                    fixture.coordinator(), "cursors");
            assertEquals(0L, fixture.child().epoch());

            // when
            cursors.put(
                    fixture.binding().bindingId(),
                    new EmbeddedEpochCursor(
                            fixture.binding().bindingId(), -1L));

            // then
            assertRejectedButAuditable(
                    fixture, "parent cursor -1 is behind child epoch 0");
        }
    }

    @Test
    void rejectsCurrentReadyMarkerWhenParentStateDiffersFromCursorState()
            throws Exception {
        try (ReadyFixture fixture = readyFixture()) {
            // given
            DocumentSession parent = fixture.parent();

            // when
            replaceCurrentParentStateWithMismatch(parent);

            // then
            assertRejectedButAuditable(
                    fixture, "parent state/cursor mismatch at /child");
        }
    }

    private static ReadyFixture readyFixture() throws Exception {
        DefaultCoordinationEngine engine = DefaultCoordinationEngine.create();
        try {
            String childSource = resource(
                    "examples/clean/embedded-counter.yaml");
            DocumentSession child = engine.start(CHILD, childSource);
            DocumentSession parent = engine.start(
                    PARENT,
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            var attachment = engine.append(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childSource)));
            assertTrue(engine.drainThrough(
                    attachment.sourceOrderKey()).quiescent());

            SequentialDrainCoordinator coordinator = fieldValue(
                    engine, "drainCoordinator");
            EmbeddingBinding binding = coordinator.bindingsForParent(PARENT)
                    .stream().findFirst().orElseThrow();
            assertEquals(SessionStatus.READY, parent.status());
            assertEquals(parent.epoch(), parent.readyEpoch());
            assertEquals(parent.epoch(), parent.graphPublishedEpoch());
            assertEquals(child.epoch(), coordinator.cursor(
                    binding.bindingId()).appliedChildEpoch());
            assertEquals(parent.epoch(), engine.document(PARENT).epoch());
            return new ReadyFixture(
                    engine, coordinator, parent, child, binding);
        } catch (Exception | Error failure) {
            engine.close();
            throw failure;
        }
    }

    private static void assertRejectedButAuditable(
            ReadyFixture fixture,
            String expectedDiagnostic) {
        DocumentSnapshot committed = fixture.engine().auditDocument(PARENT);
        assertEquals(SessionStatus.READY, committed.status(),
                "the seam isolates the composite proof from the status flag");

        CoordinationException failure = assertThrows(
                CoordinationException.class,
                () -> fixture.engine().document(PARENT));
        assertEquals(CoordinationErrorCode.DOCUMENT_NOT_READY, failure.code());
        assertTrue(failure.getMessage().contains(expectedDiagnostic),
                failure::getMessage);

        DocumentSnapshot audited = fixture.engine().auditDocument(PARENT);
        assertEquals(committed.epoch(), audited.epoch());
        assertEquals(committed.blueId(), audited.blueId());
        assertEquals(SessionStatus.READY, audited.status(),
                "an application-read rejection must not hide audit state");
        assertEquals(
                committed.valueAt("/child").blueId(),
                audited.valueAt("/child").blueId(),
                "the committed child state remains auditable");
    }

    private static void replaceCurrentParentStateWithMismatch(
            DocumentSession parent) throws ReflectiveOperationException {
        List<DocumentRevision> revisions = fieldValue(parent, "revisions");
        int lastIndex = revisions.size() - 1;
        DocumentRevision current = revisions.get(lastIndex);
        Node mismatchedRoot = current.after().copyNode();
        Node child = mismatchedRoot.getProperties().get("child");
        child.getProperties().put(
                "counter", new Node().value(BigInteger.valueOf(999L)));
        ExactValue mismatched = ExactValue.verified(mismatchedRoot);
        revisions.set(lastIndex, new DocumentRevision(
                current.documentId(),
                current.epoch(),
                current.rootApplicationOrder(),
                current.kind(),
                current.before().orElse(null),
                mismatched,
                current.sourceEntry().orElse(null),
                current.sourceOrderKey().orElse(null),
                current.causalEntryBlueId().orElse(null),
                current.catchUpCause().orElse(null),
                current.emittedEvents(),
                current.processingGas()));
    }

    private static String resource(String name) throws IOException {
        try (var input = ApplicationReadinessProofIntegrationTest.class
                .getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> mapField(
            Object target,
            String name) throws ReflectiveOperationException {
        return (Map<K, V>) fieldValue(target, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldValue(
            Object target,
            String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(
            Object target,
            String name,
            Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record ReadyFixture(
            DefaultCoordinationEngine engine,
            SequentialDrainCoordinator coordinator,
            DocumentSession parent,
            DocumentSession child,
            EmbeddingBinding binding) implements AutoCloseable {
        @Override
        public void close() {
            engine.close();
        }
    }
}
