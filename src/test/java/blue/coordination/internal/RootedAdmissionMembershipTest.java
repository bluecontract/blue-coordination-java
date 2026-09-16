package blue.coordination.internal;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Membership needs the exact retained object, not materialization of its publication prefix. */
final class RootedAdmissionMembershipTest {
    @Test void selectedAdmissionChecksExactMembershipWithoutReadingEarlierPositions() throws Exception {
        try (var fixture = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var documents = fixture.engine.documents();
            var session = documents.require(fixture.parent.id());
            var current = session.rootedView();
            var boundary = current.logicalBoundary();
            assertNotNull(boundary);
            var positionField = DocumentSession.class.getDeclaredField("rootedViewPositions");
            positionField.setAccessible(true);
            for (int targetPositions : List.of(5, 20, 50)) {
                @SuppressWarnings("unchecked")
                var positions = (List<DocumentSession.RootedViewPosition>) positionField.get(session);
                assertTrue(positions.size() < targetPositions);
                while (positions.size() < targetPositions) {
                    session.retainRootedView(RootedDocumentView.restoreStored(current.storedState()));
                }
                var selected = session.rootedView();
                var sources = new RootedAdmissionSources(boundary, Map.of(session.documentId(), selected));
                var detached = RootedDocumentView.restoreStored(selected.storedState());
                assertNotSame(selected, detached);
                assertEquals(selected.result().invocationIdentity(), detached.result().invocationIdentity());
                assertEquals(selected.snapshot().closureIdentity(), detached.snapshot().closureIdentity());
                var unretained = new RootedAdmissionSources(boundary, Map.of(session.documentId(), detached));
                var expectedPrefix = positions.stream().map(position -> position.view().result().invocationIdentity())
                        .collect(java.util.stream.Collectors.toSet());
                var counted = new CountingPositions(positions);
                positionField.set(session, counted);
                try {
                    assertEquals(expectedPrefix, session.rootedPublicationPrefix(selected),
                            "The former caller algorithm remains the reference for this same retained input");
                    assertEquals(targetPositions, counted.reads,
                            "Constructing the formerly discarded set reads the complete retained prefix");
                    counted.reads = 0;
                    assertSame(selected, sources.selected(session.documentId(), boundary, documents));
                    assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                            () -> unretained.selected(session.documentId(), boundary, documents));
                    assertEquals(0, counted.reads,
                            "Both accepted and rejected membership checks must avoid publication-prefix traversal");
                } finally {
                    positionField.set(session, positions);
                }
            }
        }
    }

    private static final class CountingPositions extends AbstractList<DocumentSession.RootedViewPosition> {
        private final List<DocumentSession.RootedViewPosition> positions;
        private int reads;
        CountingPositions(List<DocumentSession.RootedViewPosition> positions) { this.positions = positions; }
        @Override public int size() { return positions.size(); }
        @Override public DocumentSession.RootedViewPosition get(int index) {
            reads++;
            return positions.get(index);
        }
    }
}
