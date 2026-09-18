package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.ActivationPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static blue.coordination.internal.DocumentSessionStorageTest.resource;
import static org.junit.jupiter.api.Assertions.*;

/** Real session/history codecs through the logical primary indexes, without a shared descriptor. */
final class LogicalDocumentPrimaryIndexesTest {
    private static final PersistentMapStorage.Limits MAP = new PersistentMapStorage.Limits(1024 * 1024, 4096, 512 * 1024, 4096, 32);
    private static final DocumentSessionStorage.Limits SESSION = new DocumentSessionStorage.Limits(32 * 1024 * 1024, 256, 256L * 1024 * 1024);

    @Test void independentSessionHeadsAndLineagesColdReopenAfterEitherPublicationOrder() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var a = f.start(resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            var b = f.start(resource("source.yaml").replace("rcp2/source", "independent"), "independent", ActivationPolicy.fromNow());
            f.process(a, f.append(a, "rcp2/source", "tick"));
            f.process(b, f.append(b, "independent", "tick"));
            var codecs = new StoredDocumentIndexes(f.bytes, MAP, SESSION);
            // when
            for (boolean reverse : List.of(false, true)) {
                var store = new LogicalRecordMapTest.Store();
                var pa = prepare(store, codecs, f.engine.documents().require(a.id()), "a");
                var pb = prepare(store, codecs, f.engine.documents().require(b.id()), "b");
                assertTrue(store.publish(reverse ? pb : pa)); assertTrue(store.publish(reverse ? pa : pb));
                // then
                assertEquals(Set.of(Family.SESSION, Family.GRAPH_GENERATION, Family.LINEAGE_DOCUMENT, Family.LINEAGE_AUTHORED,
                        Family.LINEAGE_INITIALIZED, Family.LINEAGE_RETAINED, Family.LINEAGE_CURRENT),
                        pa.mutations().stream().map(m -> m.key().family()).collect(java.util.stream.Collectors.toSet()));
                assertTrue(pa.queries().isEmpty()); assertTrue(pb.queries().isEmpty());
                try (var attempt = store.attempt(); var cold = new DocumentSessionStorage(f.bytes.copy(), SESSION).openScope()) {
                    var context = new LogicalRecordContext(attempt);
                    var coldIndexes = new StoredDocumentIndexes(f.bytes.copy(), MAP, SESSION);
                    var heads = coldIndexes.openLogicalSessions(context);
                    var allLineages = coldIndexes.openLogicalLineages(context);
                    var lineages = allLineages.storedState().documents();
                    var graph = coldIndexes.openLogicalGenerations(context);
                    var generations = graph.storedState().generations();
                    for (DocumentId id : List.of(a.id(), b.id())) {
                        var address = heads.get(id); var session = cold.open(id, address.address());
                        var lineage = lineages.get(id);
                        assertEquals(id, address.documentId()); assertEquals(1L, session.epoch());
                        assertEquals(session.currentRepresentation().blueId(), lineage.currentBlueId());
                        assertEquals(session.epoch(), lineage.currentEpoch());
                        try (var owner = coldIndexes.openOwner(1)) {
                            var selected = owner.find(heads, allLineages, graph, id).orElseThrow();
                            assertEquals(session.currentRepresentation().blueId(), selected.session().currentRepresentation().blueId());
                        }
                        assertEquals(session.rootedView().result().graphGeneration(), generations.get(id));
                        assertArrayEquals(new ClosureProcessResultStorageCodecAdapter().encode(f.engine.documents().require(id)),
                                new ClosureProcessResultStorageCodecAdapter().encode(session));
                    }
                }
            }
        }
    }

    private static Publication prepare(LogicalRecordMapTest.Store store, StoredDocumentIndexes indexes,
            DocumentSession session, String identity) {
        var attempt = store.attempt(); var context = new LogicalRecordContext(attempt);
        indexes.openLogicalSessions(context).put(session.documentId(), indexes.retainSession(session)).map().selectLogicalRecords();
        indexes.selectLogicalLineages(indexes.openLogicalLineages(context).withNewLineage(session));
        indexes.openLogicalGenerations(context).storedState().generations()
                .put(session.documentId(), session.rootedView().result().graphGeneration()).map().selectLogicalRecords();
        context.flush(); return attempt.prepare(identity, List.of(), new Bytes(new byte[] {1}));
    }

    private static final class ClosureProcessResultStorageCodecAdapter {
        byte[] encode(DocumentSession session) {
            return new blue.language.processor.closure.ClosureProcessResultStorageCodec(SESSION.maximumRecordBytes(), SESSION.maximumDepth())
                    .encode(session.rootedView().result());
        }
    }
}
