package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Structural bounds, not wall-clock thresholds, for session-local history selection. */
final class DocumentSessionHistoryRangeTest {
    private static final ExternalOrderKey ORDER = order(1);

    @ParameterizedTest
    @ValueSource(ints = {5, 20, 50})
    void selectedEpochCopiesOnlyItsOrderedRangeAndBinaryBounds(int positionsPerEpoch) throws Exception {
        var session = history(5, positionsPerEpoch);
        var all = session.representationTransitions();
        var counted = replaceRowsWithCountingList(session);
        for (long epoch : List.of(-1L, 0L, 1L, 2L, 3L, 4L, 5L, Long.MAX_VALUE)) {
            var expected = all.stream().filter(row -> row.epoch() == epoch).toList();
            counted.reads = 0;
            var selected = session.representationTransitionsAt(epoch);
            assertEquals(expected, selected);
            int maximumBounds = 2 * (32 - Integer.numberOfLeadingZeros(all.size()));
            assertTrue(counted.reads <= expected.size() + maximumBounds,
                    "Only the selected rows plus logarithmic bound probes may be read: " + counted.reads);
            assertThrows(UnsupportedOperationException.class, selected::clear);
        }
        var restored = DocumentSession.restoreStored(session.storedState());
        for (long epoch = 0; epoch < 5; epoch++) {
            assertEquals(session.representationTransitionsAt(epoch), restored.representationTransitionsAt(epoch));
        }
    }

    @Test void appendAndDetachedRollbackKeepPreviouslyReturnedRangesFrozen() {
        // given
        var session = history(3, 5);
        var prior = session.representationTransitionsAt(2);
        var original = session.storedState();
        var discarded = session.copyForAtomicPublication();
        // when
        rebind(discarded, 5);
        // then
        assertEquals(6, discarded.representationTransitionsAt(2).size());
        assertEquals(original, session.storedState(), "An unpublished copy cannot advance source indexes");
        assertEquals(5, prior.size());
        rebind(session, 5);
        assertEquals(6, session.representationTransitionsAt(2).size());
        assertEquals(5, prior.size(), "A captured range never follows the mutable session head");
        appendEpoch(session, 3);
        assertTrue(session.representationTransitionsAt(3).isEmpty());
        rebind(session, 0);
        assertEquals(1, session.representationTransitionsAt(3).size());
        assertEquals(6, session.representationTransitionsAt(2).size());
        var restored = DocumentSession.restoreStored(session.storedState());
        assertEquals(session.representationTransitions(), restored.representationTransitions());
    }

    @Test void returningToAnEarlierBlueIdRetainsEveryDistinctSameEpochPosition() {
        // given
        var session = history(1, 5);
        var original = session.currentRevision().after();
        session.rebindComponentRepresentation(0, layout(original), List.of(), "return-receipt", "return-publication");
        rebind(session, 6);
        session.rebindComponentRepresentation(0, layout(original), List.of(), "second-return-receipt", "second-return-publication");
        // when
        var rows = session.representationTransitionsAt(0);
        // then
        assertEquals(8, rows.size());
        assertEquals(rows.get(5).afterBlueId(), rows.get(7).afterBlueId());
        assertNotEquals(rows.get(5).transitionReceiptIdentity(), rows.get(7).transitionReceiptIdentity());
        assertEquals(rows, DocumentSession.restoreStored(session.storedState()).representationTransitionsAt(0));
    }

    @Test void publicationMembershipUsesRetainedObjectPositionAcrossAppendCopyAndRestore() throws Exception {
        // given
        try (var fixture = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var original = fixture.engine.documents().require(fixture.parent.id());
            var positions = original.storedState().rootedViewPositions();
            var first = positions.get(0).view();
            var current = original.rootedView();
            // when
            String currentInvocation = current.result().invocationIdentity();
            // then
            assertFalse(original.rootedPublicationIncludes(first, currentInvocation));
            var selectedPrefix = original.rootedPublicationPrefix(first);
            var copy = original.copyForAtomicPublication();
            for (int count : List.of(5, 20, 50)) {
                RootedDocumentView equalEndpoint = null;
                for (int index = 0; index < count; index++) {
                    equalEndpoint = RootedDocumentView.restoreStored(current.storedState());
                    RootedDocumentView notRetained = equalEndpoint;
                    assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                            () -> copy.requireRetainedRootedView(notRetained));
                    copy.retainRootedView(equalEndpoint);
                }
                assertNotSame(current, equalEndpoint);
                assertEquals(current.snapshot().closureIdentity(), equalEndpoint.snapshot().closureIdentity());
                assertTrue(copy.rootedPublicationIncludes(equalEndpoint, currentInvocation));
                assertSame(current, copy.rootedViewForInvocation(currentInvocation), "Original first publication wins lookup");
                assertEquals(selectedPrefix, copy.rootedPublicationPrefix(first));
                assertFalse(copy.rootedPublicationIncludes(first, currentInvocation));
                RootedDocumentView onlyInCopy = equalEndpoint;
                assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                        () -> original.requireRetainedRootedView(onlyInCopy));
                var restored = DocumentSession.restoreStored(copy.storedState());
                assertTrue(restored.rootedPublicationIncludes(equalEndpoint, currentInvocation));
                assertFalse(restored.rootedPublicationIncludes(first, currentInvocation));
                for (var boundary : List.of(order(1), order(100), order(150), order(200), order(201))) {
                    var expected = copy.storedState().rootedViewPositions().stream()
                            .filter(position -> position.boundary() == null || position.boundary().compareTo(boundary) < 0)
                            .reduce((left, right) -> right);
                    if (expected.isPresent()) {
                        assertSame(expected.orElseThrow().view(), restored.rootedViewBefore(boundary));
                    } else {
                        assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                                () -> restored.rootedViewBefore(boundary));
                    }
                }
            }
            assertSame(current, original.rootedView(), "Discarded staging has not changed the original source head");
        }
    }

    @Test void failedAtomicPublicationDoesNotLeakNewMembershipBeforeSuccessfulRetry() throws Exception {
        // given
        try (var fixture = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var documents = fixture.engine.documents();
            var before = documents.require(fixture.parent.id());
            var priorView = before.rootedView();
            var priorPrefix = before.rootedPublicationPrefix(priorView);
            fixture.append(300);
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            // when
            fixture.engine.contractsClosureAdapter().onStoreFailurePoint(point -> {
                if (point == MultiDocumentPublicationTransaction.FailurePoint.BEFORE_SWAP) {
                    reached.set(true);
                    throw new IllegalStateException("abort staged range indexes");
                }
            });
            try {
            // then
            assertThrows(RuntimeException.class, () -> fixture.blue.processing().processNext(fixture.parent)); }
            finally { fixture.engine.contractsClosureAdapter().onStoreFailurePoint(ignored -> { }); }
            assertTrue(reached.get());
            var aborted = documents.require(fixture.parent.id());
            assertSame(priorView, aborted.rootedView());
            assertEquals(priorPrefix, aborted.rootedPublicationPrefix(priorView));
            fixture.blue.processing().processNext(fixture.parent);
            var published = documents.require(fixture.parent.id());
            var next = published.rootedView();
            assertNotSame(priorView, next);
            assertTrue(published.rootedPublicationIncludes(next, next.result().invocationIdentity()));
            assertFalse(published.rootedPublicationIncludes(priorView, next.result().invocationIdentity()));
            assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                    () -> aborted.requireRetainedRootedView(next));
        }
    }

    private static CountingRows replaceRowsWithCountingList(DocumentSession session) throws Exception {
        var field = DocumentSession.class.getDeclaredField("componentRepresentationTransitions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var original = (List<DocumentSession.ComponentRepresentationTransition>) field.get(session);
        var counted = new CountingRows(original);
        field.set(session, counted);
        return counted;
    }

    private static final class CountingRows extends AbstractList<DocumentSession.ComponentRepresentationTransition> {
        private final List<DocumentSession.ComponentRepresentationTransition> rows;
        int reads;
        CountingRows(List<DocumentSession.ComponentRepresentationTransition> rows) { this.rows = rows; }
        @Override public int size() { return rows.size(); }
        @Override public DocumentSession.ComponentRepresentationTransition get(int index) { reads++; return rows.get(index); }
    }

    private static DocumentSession history(int epochs, int positions) {
        var initial = value("epoch-0"); var id = DocumentId.of("history-range");
        var revision = new DocumentRevision(id, 0, 0, DocumentRevision.Kind.INITIALIZATION,
                null, initial, null, ORDER, initial.blueId(), null, List.of(), 0, null);
        var session = new DocumentSession(id, initial, layout(initial), List.of(), ORDER, revision);
        for (int epoch = 0; epoch < epochs; epoch++) {
            if (epoch != 0) appendEpoch(session, epoch);
            for (int position = 0; position < positions; position++) rebind(session, position);
        }
        return session;
    }

    private static void appendEpoch(DocumentSession session, long epoch) {
        var after = value("epoch-" + epoch);
        var revision = new DocumentRevision(session.documentId(), epoch, epoch, DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                session.currentRepresentation(), after, null, null, List.of(), 0);
        session.commit(revision, layout(after), ORDER, List.of(), "epoch-receipt-" + epoch);
    }

    private static void rebind(DocumentSession session, int position) {
        String name = "epoch-" + session.epoch() + "-position-" + position;
        session.rebindComponentRepresentation(session.epoch(), layout(value(name)), List.of(), "receipt-" + name, "publication-" + name);
    }

    private static ExactValue value(String name) { return ExactValue.verified(new Node().value(name)); }
    private static EmbeddedOnlyLayout layout(ExactValue value) {
        return new EmbeddedOnlyLayout(value, value.frozen(), Map.of("/", value), List.of(), List.of(),
                EmbeddedLayoutPlan.managedRoot(new RoutingSurface(List.of(), false)));
    }
    private static ExternalOrderKey order(long timestamp) {
        return ExternalOrderKey.of(List.of(BigInteger.valueOf(timestamp), "timeline", "entry"));
    }
}
