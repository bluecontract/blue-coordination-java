package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.lang.reflect.RecordComponent;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Endpoint indexing preserves the old full-history causal filter without assuming contiguous matches. */
final class DocumentSessionCausalRevisionBoundsTest {
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(4 * 1024 * 1024, 128, 64L * 1024 * 1024);
    private static final String A = value("cause-a").blueId();
    private static final String B = value("cause-b").blueId();
    private static final String ABSENT = value("absent-cause").blueId();
    private static final ExternalOrderKey ORDER = ExternalOrderKey.of(List.of(BigInteger.ONE, "timeline", "entry"));

    @Test void noncontiguousCauseIncludesInitializationAndNoSourceEntryWithoutChangingCapturedCopies() {
        var session = history(A, B, A, null, A);
        assertTrue(session.revisions().stream().allMatch(revision -> revision.sourceEntry().isEmpty()));
        assertTrue(session.indexedState().sourceEntryEpochs().isEmpty());
        assertTrue(session.revisionForEntry(A).isEmpty(), "Source-entry lookup is intentionally not causal lookup");
        var selected = session.causalRevisionEndpoints(A);
        assertEquals(List.of(0L, 4L), epochs(selected));
        assertEquals(DocumentRevision.Kind.INITIALIZATION, selected.get(0).kind());
        assertEquals(DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION, selected.get(1).kind());
        assertEquals(fullHistoryEndpoints(session, A), selected);
        assertEquals(List.of(1L), epochs(session.causalRevisionEndpoints(B)));
        assertTrue(session.causalRevisionEndpoints(ABSENT).isEmpty());
        assertThrows(UnsupportedOperationException.class, selected::clear);

        var copy = session.copyForAtomicPublication();
        append(copy, A);
        assertEquals(List.of(0L, 5L), epochs(copy.causalRevisionEndpoints(A)));
        assertEquals(List.of(0L, 4L), epochs(session.causalRevisionEndpoints(A)));
        assertEquals(List.of(0L, 4L), epochs(selected), "A captured answer cannot follow a later append");
        var restored = DocumentSession.restoreStored(copy.storedState());
        assertEquals(fullHistoryEndpoints(copy, A), restored.causalRevisionEndpoints(A));
        assertTrue(restored.causalRevisionEndpoints(ABSENT).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 20, 50})
    void coldEndpointLookupReadsOnlyTwoRequiredRevisionsAndMatchesTheOldFullHistoryReference(int count) {
        var causes = new String[count];
        Arrays.fill(causes, B);
        causes[1] = A; causes[count - 2] = A;
        var session = history(causes);
        var expected = fullHistoryEndpoints(session, A);
        assertEquals(List.of(1L, count - 2L), epochs(expected));
        var codec = new SessionRecordCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth());
        var selectedAddresses = expected.stream().map(codec::encodeRevision)
                .map(RootedHistoryAccessObjects::digest).collect(Collectors.toSet());
        var objects = new RootedHistoryAccessObjects();
        var storage = new DocumentSessionStorage(RootedEngineStorage.controlledNamespace(objects), LIMITS);
        String address = storage.retain(session);
        Set<String> historical = objects.historicalPayloadAddresses();
        assertEquals(count, historical.size(), "The cold fixture contains every numbered revision payload");
        try (var scope = storage.openScope()) {
            var cold = scope.open(session.documentId(), address);
            objects.begin(historical);
            var actual = cold.causalRevisionEndpoints(A);
            assertTrue(cold.causalRevisionEndpoints(ABSENT).isEmpty());
            var measured = objects.end(historical, selectedAddresses);
            assertEquals(2, measured.gets().get("revision-payload").calls());
            assertEquals(0, measured.unrequestedHistoricalPayloadGetCalls());
            assertTrue(measured.puts().isEmpty());
            assertEquals(epochs(expected), epochs(actual));
            for (int index = 0; index < expected.size(); index++) {
                assertArrayEquals(codec.encodeRevision(expected.get(index)), codec.encodeRevision(actual.get(index)),
                        "Selected before/after values, cause, events and gas remain byte-exact");
            }
            assertEquals(address, scope.retain(cold), "Read-only endpoint selection cannot alter retained session bytes");
        }
    }

    @Test void strictRestoreRejectsAnOmittedCauseAndIncompleteMatchingEndpoints() throws Exception {
        var session = history(A, B, A, null, A);
        var state = session.indexedState();
        assertEquals(fullHistoryEndpoints(session, A), DocumentSession.restoreIndexed(state, null).causalRevisionEndpoints(A));
        var original = state.causalEntryBounds();
        var corruptions = List.of(original.remove(A).map(),
                original.put(A, new DocumentSession.CausalRevisionBounds(2, 4)).map(),
                original.put(A, new DocumentSession.CausalRevisionBounds(0, 2)).map());
        for (var changed : corruptions) {
            var tampered = withCausalBounds(state, changed);
            assertSame(state.revisions().root(), tampered.revisions().root());
            assertEquals(state.epoch(), tampered.epoch());
            assertThrows(IllegalArgumentException.class, () -> DocumentSession.restoreIndexed(tampered, null),
                    "Valid matching endpoints do not prove they are the first and last occurrences");
        }
    }

    @Test void controlledSelectedEndpointsStillRejectACrossCauseLink() throws Exception {
        var state = history(A, B, A, null, A).indexedState();
        var wrong = state.causalEntryBounds().put(A, new DocumentSession.CausalRevisionBounds(1, 4)).map();
        var selected = DocumentSession.restoreControlledIndexed(withCausalBounds(state, wrong), null);
        assertThrows(IllegalStateException.class, () -> selected.causalRevisionEndpoints(A));
    }

    private static List<DocumentRevision> fullHistoryEndpoints(DocumentSession session, String cause) {
        var matching = session.revisions().stream()
                .filter(revision -> revision.causalEntryBlueId().filter(cause::equals).isPresent()).toList();
        return matching.size() < 2 ? matching : List.of(matching.get(0), matching.get(matching.size() - 1));
    }

    private static List<Long> epochs(List<DocumentRevision> revisions) {
        return revisions.stream().map(DocumentRevision::epoch).toList();
    }

    private static DocumentSession history(String... causes) {
        if (causes.length == 0 || causes[0] == null) throw new IllegalArgumentException("Initialization needs a cause");
        var id = DocumentId.of("causal-history-range");
        var initial = value("epoch-0");
        var revision = new DocumentRevision(id, 0, 0, DocumentRevision.Kind.INITIALIZATION,
                null, initial, null, ORDER, causes[0], null, List.of(), 0, null);
        var session = new DocumentSession(id, initial, layout(initial), List.of(), ORDER, revision);
        for (int epoch = 1; epoch < causes.length; epoch++) append(session, causes[epoch]);
        return session;
    }

    private static void append(DocumentSession session, String cause) {
        long epoch = session.epoch() + 1;
        var after = value("epoch-" + epoch);
        var revision = new DocumentRevision(session.documentId(), epoch, epoch, DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                session.currentRepresentation(), after, null, cause == null ? null : ORDER, cause, null,
                List.of(new Node().value("event-" + epoch)), epoch * 3, null);
        session.commit(revision, layout(after), ORDER, List.of(), "epoch-receipt-" + epoch);
    }

    /** Corrupt only a private derived index while preserving the original exact revision payloads. */
    private static DocumentSession.IndexedState withCausalBounds(DocumentSession.IndexedState original,
            PersistentOrderedMap<String, DocumentSession.CausalRevisionBounds> changed) throws Exception {
        RecordComponent[] components = DocumentSession.IndexedState.class.getRecordComponents();
        Object[] values = new Object[components.length]; boolean replaced = false;
        for (int index = 0; index < components.length; index++) {
            values[index] = components[index].getAccessor().invoke(original);
            if (components[index].getName().equals("causalEntryBounds")) { values[index] = changed; replaced = true; }
        }
        assertTrue(replaced);
        var constructor = DocumentSession.IndexedState.class.getDeclaredConstructor(
                Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
        return constructor.newInstance(values);
    }

    private static ExactValue value(String name) { return ExactValue.verified(new Node().value(name)); }
    private static EmbeddedOnlyLayout layout(ExactValue value) {
        return new EmbeddedOnlyLayout(value, value.frozen(), Map.of("/", value), List.of(), List.of(),
                EmbeddedLayoutPlan.managedRoot(new RoutingSurface(List.of(), false)));
    }
}
