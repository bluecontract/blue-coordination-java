package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationRecords.Bytes;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.DocumentHandle;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Complete document-family assembly; engine control/SDK/journal integration is tested separately. */
final class LogicalDocumentStoreTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits SESSIONS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 16);
    private static final PersistentAppendLogStorage.Limits LOGS = new PersistentAppendLogStorage.Limits(40 * 1024 * 1024, MAX, 4096, 8);

    @Test void openingAllDocumentFamiliesDoesNotEnumerateOrReadAnyLogicalRecord() {
        // given
        var records = new LogicalRecordMapTest.Store(); var objects = new DocumentSessionStorageTest.Bytes();
        var attempt = records.attempt(); var context = new LogicalRecordContext(attempt);
        // when
        try (var opened = storage(objects).openLogical(context, 8, 256)) { assertNotNull(opened.state()); }
        var packet = attempt.prepare("open", List.of(), new Bytes(new byte[] {1}));
        // then
        assertTrue(packet.points().isEmpty()); assertTrue(packet.queries().isEmpty()); assertTrue(packet.mutations().isEmpty());
        assertEquals(0, objects.reads); assertEquals(0, objects.writes);
    }

    @Test void actualAdmissionAndRepeatedProcessingReopenAllDocumentFamiliesWithoutDescriptorRoots() throws Exception {
        // given
        var records = new LogicalRecordMapTest.Store(); var objects = new DocumentSessionStorageTest.Bytes();
        DocumentHandle root = null; var codec = new blue.language.processor.closure.ClosureProcessResultStorageCodec(MAX, 256);
        byte[] expected = null;
        try (var fixture = new DocumentSessionStorageTest.Fixture()) {
            // when
            for (int stage = 0; stage < 3; stage++) {
                try (var attempt = records.attempt()) {
                    var context = new LogicalRecordContext(attempt);
                    try (var opened = storage(objects).openLogical(context, 8, 256)) {
                        install(fixture, opened.state());
                        if (stage == 0) root = fixture.start(DocumentSessionStorageTest.resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
                        else fixture.process(root, fixture.append(root, "rcp2/source", "tick"));
                        var state = fixture.engine.documents().storedState();
                        expected = codec.encode(state.sessionIndex().get(root.id()).rootedView().result());
                        opened.stageLogical(state); context.flush();
                    }
                    assertTrue(records.publish(attempt.prepare("stage-" + stage, List.of(), new Bytes(new byte[] {1}))));
                }
            }
            // then
            try (var attempt = records.attempt()) {
                var context = new LogicalRecordContext(attempt);
                try (var opened = storage(objects.copy()).openLogical(context, 8, 256)) {
                    var state = opened.state(); var session = state.sessionIndex().get(root.id());
                    assertEquals(2, session.epoch()); assertArrayEquals(expected, codec.encode(session.rootedView().result()));
                    assertFalse(state.outbox().isEmpty()); assertFalse(state.checkpointEvidence().isEmpty());
                    assertTrue(state.closurePublicationReceiptIndex().size() >= 2);
                    assertEquals(2, state.managedEpochReceipts().exact(root.id(), 2).receipt().epoch());
                }
            }
        }
    }

    @Test void independentRealStagesOverOneDocumentStorePublishInBothOrdersWithoutSemanticRetry() throws Exception {
        // given
        for (boolean reverse : List.of(false, true)) {
            var records = new LogicalRecordMapTest.Store(); var objects = new DocumentSessionStorageTest.Bytes();
            var roots = new ArrayList<DocumentHandle>(); var inputs = new ArrayList<blue.coordination.sdk.EntryHandle>();
            var packets = new ArrayList<blue.coordination.api.storage.CoordinationRecords.Publication>();
            var expected = new ArrayList<byte[]>(); var codec = new blue.language.processor.closure.ClosureProcessResultStorageCodec(MAX, 256);
            try (var fixture = new DocumentSessionStorageTest.Fixture()) {
                try (var attempt = records.attempt()) {
                    var context = new LogicalRecordContext(attempt);
                    try (var opened = storage(objects).openLogical(context, 8, 256)) {
                        install(fixture, opened.state());
                        for (String timeline : List.of("rcp2/source", "independent/source")) {
                            var root = fixture.start(DocumentSessionStorageTest.resource("source.yaml").replace("rcp2/source", timeline), timeline, ActivationPolicy.fromNow());
                            roots.add(root); inputs.add(fixture.append(root, timeline, "tick"));
                        }
                        opened.stageLogical(fixture.engine.documents().storedState()); context.flush();
                    }
                    assertTrue(records.publish(attempt.prepare("admit", List.of(), new Bytes(new byte[] {1}))));
                }
                // when
                for (int index = 0; index < roots.size(); index++) {
                    try (var attempt = records.attempt()) {
                        var context = new LogicalRecordContext(attempt);
                        try (var opened = storage(objects).openLogical(context, 8, 256)) {
                            install(fixture, opened.state());
                            var completed = fixture.blue.processing().processStage(roots.get(index), inputs.get(index));
                            assertEquals(blue.coordination.sdk.ProcessingStageResult.Disposition.COMPLETED, completed.disposition());
                            var state = fixture.engine.documents().storedState();
                            expected.add(codec.encode(state.sessionIndex().get(roots.get(index).id()).rootedView().result()));
                            opened.stageLogical(state); context.flush();
                        }
                        packets.add(attempt.prepare("stage-" + index, List.of(), new Bytes(new byte[] {1})));
                    }
                }
                // then
                assertTrue(records.publish(packets.get(reverse ? 1 : 0)));
                assertTrue(records.publish(packets.get(reverse ? 0 : 1)), "Independent completed stage must publish without a semantic retry");
                try (var attempt = records.attempt(); var opened = storage(objects.copy()).openLogical(new LogicalRecordContext(attempt), 8, 256)) {
                    for (int index = 0; index < roots.size(); index++) {
                        var session = opened.state().sessionIndex().get(roots.get(index).id());
                        assertEquals(1, session.epoch()); assertArrayEquals(expected.get(index), codec.encode(session.rootedView().result()));
                    }
                }
            }
        }
    }

    @Test void coldParentAdmissionAndRetainedSourceCatchUpKeepTheirCompleteGraphAndReceiptIndexes() throws Exception {
        // given
        var records = new LogicalRecordMapTest.Store(); var objects = new DocumentSessionStorageTest.Bytes();
        DocumentHandle source = null, parent = null; String originalSource = null;
        try (var fixture = new DocumentSessionStorageTest.Fixture()) {
            // when
            for (int stage = 0; stage < 4; stage++) {
                try (var attempt = records.attempt()) {
                    var context = new LogicalRecordContext(attempt);
                    try (var opened = storage(objects).openLogical(context, 12, 512)) {
                        install(fixture, opened.state());
                        if (stage == 0) {
                            source = fixture.start(DocumentSessionStorageTest.resource("source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
                            originalSource = source.snapshot().blueId();
                        } else if (stage == 1) {
                            fixture.process(source, fixture.append(source, "rcp2/source", "tick")); fixture.retain(source);
                        } else if (stage == 2) {
                            parent = fixture.start(DocumentSessionStorageTest.resource("parent.yaml") + "\nchild: {blueId: " + originalSource + "}\n",
                                    "rcp2/parent", ActivationPolicy.fromNow());
                            assertFalse(fixture.engine.documents().catchUpPlansSnapshot().plansForConsumer(parent.id()).plans().isEmpty());
                        } else {
                            var result = fixture.blue.processing().processNextStage(parent);
                            assertEquals(blue.coordination.sdk.ProcessingStageResult.Disposition.COMPLETED, result.disposition());
                        }
                        opened.stageLogical(fixture.engine.documents().storedState()); context.flush();
                    }
                    assertTrue(records.publish(attempt.prepare("parent-stage-" + stage, List.of(), new Bytes(new byte[] {1}))));
                }
            }
            // then
            try (var attempt = records.attempt(); var opened = storage(objects.copy()).openLogical(new LogicalRecordContext(attempt), 12, 512)) {
                var state = opened.state();
                assertEquals(1, state.sessionIndex().get(source.id()).epoch());
                assertNotNull(state.sessionIndex().get(parent.id()));
                assertFalse(state.occurrenceInventory().rowsFrom(parent.id()).isEmpty());
                assertFalse(state.catchUpPlans().plansForConsumer(parent.id()).plans().isEmpty());
                assertEquals(1, state.managedEpochReceipts().exact(source.id(), 1).receipt().epoch());
            }
        }
    }

    private static StoredDocumentStore storage(DocumentSessionStorageTest.Bytes objects) {
        return new StoredDocumentStore(RootedEngineStorage.controlledNamespace(objects), MAPS, LOGS, SESSIONS);
    }
    private static void install(DocumentSessionStorageTest.Fixture fixture, InMemoryDocumentStore.StoreState state) throws Exception {
        var field = InMemoryDocumentStore.class.getDeclaredField("state"); field.setAccessible(true); field.set(fixture.engine.documents(), state);
    }
}
