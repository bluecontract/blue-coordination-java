package blue.coordination.internal;

import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Physical payload interning must not replace already captured live view objects. */
final class DocumentSessionOwnedViewStorageTest {
    private static final DocumentSessionStorage.Limits LIMITS =
            new DocumentSessionStorage.Limits(40 * 1024 * 1024, 256, 512L * 1024 * 1024);
    private static final ExternalOrderKey AFTER =
            ExternalOrderKey.of(List.of(BigInteger.valueOf(1_000), "timeline", "entry"));

    @Test void controlledRetentionPreservesDistinctLiveAliasesAndColdPublicationMeaning() throws Exception {
        // given
        try (var fixture = new ManagedRepresentationVerificationMemoTest.Scenario()) {
            var session = fixture.engine.documents().require(fixture.parent.id()).copyForAtomicPublication();
            var original = session.rootedView();
            var aliasOne = RootedDocumentView.restoreStored(original.storedState());
            // when
            var aliasTwo = RootedDocumentView.restoreStored(original.storedState());
            // then
            assertNotSame(original, aliasOne);
            assertNotSame(aliasOne, aliasTwo);
            int firstAliasOrdinal = session.indexedState().rootedViewPositions().size();
            session.retainRootedView(aliasOne);
            session.retainRootedView(aliasTwo);
            var before = session.storedState();
            var beforePrefix = session.rootedPublicationPrefix(aliasTwo);
            assertSame(aliasTwo, session.rootedViewBefore(AFTER));
            var bytes = new RootedHistoryAccessObjects();
            var storage = new DocumentSessionStorage(RootedEngineStorage.controlledNamespace(bytes), LIMITS);
            String address;
            try (var scope = storage.openScope()) {
                address = scope.retain(session);
                assertEquals(scope.addressOf(original), scope.addressOf(aliasOne));
                assertEquals(scope.addressOf(aliasOne), scope.addressOf(aliasTwo));
                assertSame(aliasTwo, session.rootedView(), "Staging cannot replace a captured current view");
                assertSame(aliasTwo, session.rootedViewBefore(AFTER));
                assertEquals(before, session.storedState(), "Only physical backing changed");
                assertSame(aliasTwo, DocumentSession.restoreStored(session.storedState()).rootedView());
                assertEquals(firstAliasOrdinal, retainedOrdinal(session, aliasOne));
                assertEquals(firstAliasOrdinal + 1L, retainedOrdinal(session, aliasTwo));
                assertSame(original, session.rootedViewForInvocation(original.result().invocationIdentity()));
                for (var view : List.of(original, aliasOne, aliasTwo))
                    assertDoesNotThrow(() -> session.requireRetainedRootedView(view));
                var unretained = RootedDocumentView.restoreStored(original.storedState());
                assertThrows(ContractsClosureAdapter.ProjectionUnavailableException.class,
                        () -> session.requireRetainedRootedView(unretained));
                assertEquals(address, scope.retain(session), "The overlay is not serialized");

                var branch = session.copyForAtomicPublication();
                var branchOnly = RootedDocumentView.restoreStored(original.storedState());
                branch.retainRootedView(branchOnly);
                scope.retain(branch);
                assertSame(branchOnly, branch.rootedViewBefore(AFTER));
                assertEquals(firstAliasOrdinal + 2L, retainedOrdinal(branch, branchOnly));
                assertSame(aliasTwo, session.rootedViewBefore(AFTER), "A staged copy owns its overlay append");
                assertEquals(before, session.storedState());
            }
            try (var cold = storage.openScope()) {
                var restored = cold.open(session.documentId(), address);
                assertEquals(before.epoch(), restored.epoch());
                assertEquals(before.layout().rootBlueId(), restored.currentRepresentation().blueId());
                var rows = new SessionRecordCodec(LIMITS.maximumRecordBytes(), LIMITS.maximumDepth());
                assertEquals(before.revisions().size(), restored.revisions().size());
                for (int epoch = 0; epoch < before.revisions().size(); epoch++)
                    assertArrayEquals(rows.encodeRevision(before.revisions().get(epoch)),
                            rows.encodeRevision(restored.revision(epoch)), "Exact revision bytes, including gas and events");
                assertEquals(beforePrefix, restored.rootedPublicationPrefix(restored.rootedView()));
                assertEquals(original.result().invocationIdentity(), restored.rootedView().result().invocationIdentity());
                assertSame(restored.rootedView(), restored.rootedViewBefore(AFTER),
                        "A fresh owner uses one canonical object for the identical stored payload");
                assertEquals(address, cold.retain(restored));
            }
        }
    }

    private static long retainedOrdinal(DocumentSession session, RootedDocumentView view) throws Exception {
        var field = DocumentSession.class.getDeclaredField("rootedViewFirstPositions");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var positions =
                (SessionIdentityPositions<RootedDocumentView>) field.get(session);
        return positions.get(view);
    }
}
