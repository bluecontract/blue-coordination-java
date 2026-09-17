package blue.coordination.internal;

import blue.coordination.sdk.EntryDisposition;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Strict imported indexed roots must prove every derived index, not only valid tip payloads. */
final class DocumentSessionIndexedValidationTest {
    @Test void strictRestoreRejectsOmittedSecondaryRowsWithUnchangedPayloadsAndScalars() throws Exception {
        // given
        try (var f = scenario()) {
            // when
            var state = f.engine.documents().require(f.parent.id()).indexedState();
            // then
            assertEquals(state.layout().rootBlueId(), DocumentSession.restoreIndexed(state, null).currentRepresentation().blueId());
            for (String field : List.of("retainedStates", "sourceEntryEpochs", "causalEntryBounds", "representationRanges", "representationStatePositions",
                    "representationStates", "representationReceiptPositions", "invocationFirstPositions")) {
                var original = index(state, field);
                assertFalse(original.isEmpty(), "Fixture must genuinely exercise " + field);
                var changed = omitFirst(original);
                var tampered = replace(state, field, changed);
                assertSame(state.revisions().root(), tampered.revisions().root());
                assertSame(state.rootedViewPositions().root(), tampered.rootedViewPositions().root());
                assertEquals(state.epoch(), tampered.epoch());
                assertEquals(state.layout().rootBlueId(), tampered.layout().rootBlueId());
                assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreIndexed(tampered, null), field);
            }
        }
    }

    @Test void strictRestoreRejectsAValidLookingWrongMiddleMetadataValueWithoutChangingCountsOrEndpoints() throws Exception {
        // given
        try (var f = scenario()) {
            var state = f.engine.documents().require(f.parent.id()).indexedState();
            var changed = state.retainedStates().put(1L, new ManagedLineageIndex.RetainedState(state.documentId(), 1L,
                    state.retainedStates().get(0L).blueId())).map();
            // when
            var tampered = replace(state, "retainedStates", changed);
            // then
            assertEquals(state.retainedStates().size(), changed.size());
            assertEquals(state.retainedStates().get(0L), changed.get(0L));
            assertEquals(state.retainedStates().get(state.epoch()), changed.get(state.epoch()));
            assertNotEquals(state.retainedStates().get(1L), changed.get(1L));
            assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreIndexed(tampered, null));
        }
    }

    @Test void strictRestoreRejectsAnAlteredHistoricalGapDespiteUnchangedCurrentRepresentation() throws Exception {
        // given
        try (var f = scenario()) {
            var state = f.engine.documents().require(f.parent.id()).indexedState();
            // when
            long changed = state.lastAnchoredNonReplayableEpoch() == -1L ? 0L : -1L;
            // then
            assertTrue(changed < state.epoch());
            var tampered = replace(state, "lastAnchoredNonReplayableEpoch", changed);
            assertSame(state.rootedView(), tampered.rootedView());
            assertSame(state.revisions().root(), tampered.revisions().root());
            assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreIndexed(tampered, null));
        }
    }

    /** Reflection only builds a malformed private record; no application/runtime access path uses it. */
    private static DocumentSession.IndexedState replace(DocumentSession.IndexedState original, String field, Object value) throws Exception {
        RecordComponent[] fields = DocumentSession.IndexedState.class.getRecordComponents();
        Object[] values = new Object[fields.length]; boolean replaced = false;
        for (int i = 0; i < fields.length; i++) {
            values[i] = fields[i].getAccessor().invoke(original);
            if (fields[i].getName().equals(field)) { values[i] = value; replaced = true; }
        }
        assertTrue(replaced, "Known exact indexed field");
        var constructor = DocumentSession.IndexedState.class.getDeclaredConstructor(
                Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new));
        return constructor.newInstance(values);
    }
    private static PersistentOrderedMap<?, ?> index(DocumentSession.IndexedState state, String name) throws Exception {
        return (PersistentOrderedMap<?, ?>) DocumentSession.IndexedState.class.getDeclaredMethod(name).invoke(state);
    }
    private static <K, V> PersistentOrderedMap<K, V> omitFirst(PersistentOrderedMap<K, V> original) {
        return original.remove(original.minimum().entry().getKey()).map();
    }
    private static ManagedRepresentationVerificationMemoTest.Scenario scenario() throws Exception {
        var f = new ManagedRepresentationVerificationMemoTest.Scenario();
        try {
            for (long time : List.of(300L, 400L, 500L)) {
                var entry = f.append(time, "tick");
                assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(f.parent).entry(entry).disposition());
            }
            assertEquals(3L, f.parent.snapshot().epoch()); return f;
        } catch (RuntimeException | Error failure) { f.close(); throw failure; }
    }
}
