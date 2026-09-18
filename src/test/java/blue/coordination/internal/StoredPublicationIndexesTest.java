package blue.coordination.internal;

import blue.coordination.api.ContractsClosureAdmissionReceipt;
import blue.coordination.api.DocumentId;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.sdk.*;
import blue.language.processor.closure.ClosureProcessResultStorageCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class StoredPublicationIndexesTest {
    private static final int MAX = 32 * 1024 * 1024;
    private static final DocumentSessionStorage.Limits LIMITS = new DocumentSessionStorage.Limits(MAX, 256, 256L * 1024 * 1024);
    private static final PersistentMapStorage.Limits MAPS = new PersistentMapStorage.Limits(40 * 1024 * 1024, 4096, MAX, 4096, 8);

    @Test void logicalReceiptsReopenExactResultsAndIndependentPublicationMemberships() throws Exception {
        // given
        var records = new LogicalRecordMapTest.Store(); var bytes = new Bytes();
        var packets = new ArrayList<blue.coordination.api.storage.CoordinationRecords.Publication>();
        var expected = new LinkedHashMap<String, byte[]>();
        var codec = new ClosureProcessResultStorageCodec(MAX, 256);
        try (var fixture = new DocumentSessionStorageTest.Fixture()) {
            // when
            for (String timeline : List.of("rcp2/source", "other/source")) {
                var root = fixture.start(resource("rooted", "source.yaml").replace("rcp2/source", timeline), timeline, ActivationPolicy.fromNow());
                fixture.process(root, fixture.append(root, timeline, "tick"));
                var state = fixture.engine.documents().storedState();
                var receipt = state.closurePublicationReceiptIndex().entries().stream()
                        .map(Map.Entry::getValue).filter(row -> row.documentIds().contains(root.id()))
                        .findFirst().orElseThrow();
                try (var binding = new Binding(bytes); var attempt = records.attempt()) {
                    var context = new LogicalRecordContext(attempt); String id = receipt.publicationIdentity();
                    binding.indexes.openLogicalGeneric(context).put(id, true).map().selectLogicalRecords();
                    binding.indexes.openLogicalClosures(context).put(id, receipt).map().selectLogicalRecords();
                    context.flush(); packets.add(attempt.prepare(id, List.of(), new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1})));
                    expected.put(id, codec.encode(receipt.attempt().processResult()));
                }
            }
        }
        // then
        for (boolean reverse : List.of(false, true)) {
            var target = new LogicalRecordMapTest.Store();
            assertTrue(target.publish(packets.get(reverse ? 1 : 0))); assertTrue(target.publish(packets.get(reverse ? 0 : 1)));
            try (var binding = new Binding(bytes.copy()); var attempt = target.attempt()) {
                var context = new LogicalRecordContext(attempt); var generic = binding.indexes.openLogicalGeneric(context);
                var admissions = binding.indexes.openLogicalAdmissions(context); var closures = binding.indexes.openLogicalClosures(context);
                for (var row : expected.entrySet()) {
                    var receipt = StoredPublicationIndexes.checkedClosure(row.getKey(), closures.get(row.getKey()), generic, admissions);
                    assertArrayEquals(row.getValue(), codec.encode(receipt.attempt().processResult()));
                }
            }
        }
    }

    @Test void coldSelectedPublicationAndOriginalRowsShareOneViewScopeWithoutWrites() throws Exception {
        // given
        var bytes = new Bytes(); byte[] genericRoot, admissionRoot, closureRoot, event, checkpoint;
        String identity, admissionIdentity, sourceAddress, sourceViewAddress, rowIdentity; DocumentId sourceId;
        try (var f = new DocumentSessionStorageTest.Fixture(); var original = new Binding(bytes)) {
            var source = f.start(resource("rooted", "source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            sourceId = source.id(); f.retain(source);
            var parent = f.start(resource("rooted", "parent.yaml") + "\nchild: {blueId: " + source.snapshot().blueId() + "}\n",
                    "rcp2/parent", ActivationPolicy.fromNow());
            var admission = f.engine.documents().publicationSnapshot().admissionReceipts().values().stream()
                    .filter(row -> row.documentIds().contains(parent.id())).findFirst().orElseThrow(); admissionIdentity = admission.publicationIdentity();
            var input = f.append(source, "rcp2/source", "tick");
            var drain = f.engine.processRootInput(parent.id(), f.engine.auditTimelineEntry(input.blueId()).orElseThrow());
            var receipt = f.engine.documents().closurePublicationReceipt(drain.contractsAttemptsFor(input.blueId()).get(0).publicationIdentity()).orElseThrow();
            identity = receipt.publicationIdentity();
            sourceAddress = original.sessions.retain(f.engine.documents().require(sourceId));
            sourceViewAddress = original.sessions.viewAddress(f.engine.documents().require(sourceId).rootedView());
            var sourceDrain = f.engine.processRootInput(sourceId, f.engine.auditTimelineEntry(input.blueId()).orElseThrow());
            var rowReceipt = f.engine.documents().closurePublicationReceipt(sourceDrain.contractsAttemptsFor(input.blueId()).get(0).publicationIdentity()).orElseThrow();
            rowIdentity = rowReceipt.publicationIdentity();
            var membership = PersistentOrderedMap.<String, Boolean>empty(EmbeddingBinding.TEXT_ORDER).put(identity, true).map()
                    .put(admissionIdentity, true).map().put(rowReceipt.publicationIdentity(), true).map();
            genericRoot = original.indexes.retainGeneric(membership).storedRootDescriptor();
            admissionRoot = original.indexes.retainAdmissions(PersistentOrderedMap.<String, ContractsClosureAdmissionReceipt>empty(EmbeddingBinding.TEXT_ORDER)
                    .put(admissionIdentity, admission).map()).storedRootDescriptor();
            closureRoot = original.indexes.retainClosures(PersistentOrderedMap.<String, ContractsClosurePublicationReceipt>empty(EmbeddingBinding.TEXT_ORDER)
                    .put(identity, receipt).map().put(rowReceipt.publicationIdentity(), rowReceipt).map()).storedRootDescriptor();
            // when
            var result = rowReceipt.attempt().processResult();
            // then
            assertFalse(result.publicEvents().isEmpty()); assertFalse(result.checkpointWrites().isEmpty());
            event = original.results.outboxCodec().encode(result.publicEvents().get(0));
            checkpoint = original.results.checkpointCodec().encode(result.checkpointWrites().get(0));
        }
        bytes.records.put("0".repeat(64), new byte[]{1, 2, 3});
        try (var cold = new Binding(bytes.copy())) {
            int writes = cold.bytes.writes;
            var generic = cold.indexes.openGeneric(genericRoot); var admissions = cold.indexes.openAdmissions(admissionRoot);
            var closures = cold.indexes.openClosures(closureRoot);
            var source = cold.scope.open(sourceId, sourceAddress);
            var receipt = StoredPublicationIndexes.checkedClosure(identity, closures.get(identity), generic, admissions);
            assertSame(source.rootedView(), receipt.rootedTerminalEvidence().storedState().rooted().histories().values().stream()
                    .map(history -> history.admissionSources().storedViews().get(sourceId)).filter(Objects::nonNull).findFirst().orElseThrow());
            StoredPublicationIndexes.checkedAdmission(admissionIdentity, admissions.get(admissionIdentity), generic, closures);
            assertNull(closures.get("missing"));
            var exactEvent = cold.results.outboxCodec().decode(event); var exactCheckpoint = cold.results.checkpointCodec().decode(checkpoint);
            assertArrayEquals(event, cold.results.outboxCodec().encode(exactEvent));
            assertArrayEquals(checkpoint, cold.results.checkpointCodec().encode(exactCheckpoint));
            assertSame(exactEvent, cold.results.outboxCodec().decode(event));
            assertEquals(writes, cold.bytes.writes); assertFalse(cold.bytes.reads.contains("0".repeat(64)));
            assertThrows(CoordinationObjectStorageException.class, () -> StoredPublicationIndexes.checkedClosure("wrong", receipt, generic, admissions));
            assertThrows(CoordinationObjectStorageException.class, () -> StoredPublicationIndexes.checkedClosure(identity, receipt,
                    PersistentOrderedMap.empty(EmbeddingBinding.TEXT_ORDER), admissions));
            assertThrows(CoordinationObjectStorageException.class, () -> StoredPublicationIndexes.checkedClosure(identity, receipt,
                    generic, PersistentOrderedMap.<String, ContractsClosureAdmissionReceipt>empty(EmbeddingBinding.TEXT_ORDER)
                            .put(identity, admissions.get(admissionIdentity)).map()));
            assertThrows(CoordinationObjectStorageException.class, () -> cold.indexes.openAdmissions(closureRoot));
        }
        var damaged = bytes.copy(); damaged.records.get(sourceViewAddress)[0] ^= 1;
        try (var cold = new Binding(damaged)) {
            var closures = cold.indexes.openClosures(closureRoot);
            assertEquals(rowIdentity, closures.get(rowIdentity).publicationIdentity());
            assertFalse(damaged.reads.contains(sourceViewAddress), "Unselected parent history remains unopened");
            assertThrows(CoordinationObjectStorageException.class, () -> closures.get(identity), "Selecting that exact damaged history fails closed");
        }
    }

    @Test void namedResultBoundsFailureCloseAndUnregisteredRowsFailBeforePublication() throws Exception {
        // given
        try (var f = new DocumentSessionStorageTest.Fixture()) {
            var source = f.start(resource("rooted", "source.yaml"), "rcp2/source", ActivationPolicy.fromNow());
            f.process(source, f.append(source, "rcp2/source", "tick"));
            var result = f.engine.documents().require(source.id()).rootedView().result();
            var bytes = new Bytes(); byte[] reference;
            // when
            String address;
            try (var rows = new StoredResultRows(bytes, LIMITS)) {
                // then
                assertThrows(CoordinationObjectStorageException.class, () -> rows.outboxCodec().encode(result.publicEvents().get(0)));
                address = rows.retain(result); int writes = bytes.writes; assertEquals(address, rows.retain(result)); assertEquals(writes, bytes.writes);
                reference = rows.outboxCodec().encode(result.publicEvents().get(0));
                var copy = new ClosureProcessResultStorageCodec(MAX, 256).decode(new ClosureProcessResultStorageCodec(MAX, 256).encode(result));
                assertThrows(CoordinationObjectStorageException.class, () -> rows.outboxCodec().encode(copy.publicEvents().get(0)));
            }
            var closed = new StoredResultRows(bytes, LIMITS); closed.close();
            assertThrows(CoordinationObjectStorageException.class, () -> closed.openResult(address));
            assertThrows(CoordinationObjectStorageException.class, () -> closed.retain(result));
            byte[] retained = bytes.records.remove(address);
            try (var cold = new StoredResultRows(bytes, LIMITS)) { assertThrows(CoordinationObjectStorageException.class, () -> cold.outboxCodec().decode(reference)); }
            bytes.records.put(address, retained.clone()); bytes.records.get(address)[0] ^= 1;
            try (var cold = new StoredResultRows(bytes, LIMITS)) { assertThrows(CoordinationObjectStorageException.class, () -> cold.outboxCodec().decode(reference)); }
            bytes.records.put(address, retained);
            var shortScope = new DocumentSessionStorage.Limits(Math.max(1024, retained.length), 256, Math.max(1024, retained.length));
            int writes = bytes.writes;
            try (var bounded = new StoredResultRows(bytes, shortScope)) { assertThrows(CoordinationObjectStorageException.class, () -> bounded.retain(result)); }
            assertEquals(writes, bytes.writes, "Scope capacity is checked before the original result write");
            try (var bounded = new StoredResultRows(bytes, shortScope)) { assertThrows(CoordinationObjectStorageException.class, () -> bounded.outboxCodec().decode(reference)); }
            bytes.badAck = true;
            try (var bad = new StoredResultRows(bytes, LIMITS)) { assertThrows(CoordinationObjectStorageException.class, () -> bad.retain(result)); }
            bytes.badAck = false;
            try (var valid = new StoredResultRows(bytes, LIMITS)) { assertArrayEquals(reference, valid.outboxCodec().encode(valid.outboxCodec().decode(reference))); }
        }
    }

    @Test void failedDependentStagingLeavesOriginalRootAndActualDeclaredRejectionIntact() throws Exception {
        // given
        var bytes = new Bytes(); byte[] root; String identity;
        try (var blue = BlueCoordination.builder().build(); var original = new Binding(bytes)) {
            var engine = (DefaultCoordinationEngine) blue.advanced().rawEngine();
            var timeline = blue.timelines().register("rooted/rejected-birth", "alice");
            var host = blue.documents().admit(ManagedDocument.yaml(DocumentId.of("rooted-rejected-birth-host"), resource("rooted-managed-rejections", "host.yaml")).publicRoot().fromNow());
            String childYaml = resource("rooted-managed-rejections", "child.yaml");
            var child = blue.documents().draft(DocumentId.of("rooted-rejected-birth-child"), blue.values().yaml(childYaml));
            var wrong = blue.values().yaml(childYaml.replace("state: draft", "state: altered"));
            var entry = blue.operations().on(host).from(timeline).call("wrongExactState").through("ownerChannel")
                    .request(request -> request.managed("order", child).exact("wrong", wrong)).expectOccurrence("/orders/expected", child).submit();
            var batch = engine.contractsClosureAdapter().captureRoot(host.id(), engine.auditTimelineEntry(entry.blueId()).orElseThrow());
            // when
            var rejection = engine.contractsClosureAdapter().processAndPublish(batch).get(0).rejectedBirth();
            // then
            assertNotNull(rejection);
            identity = rejection.terminalKey(); var empty = original.indexes.openRejections(null); byte[] emptyRoot = empty.storedRootDescriptor();
            bytes.failWrites = true;
            assertThrows(CoordinationObjectStorageException.class, () -> empty.put(identity, rejection));
            bytes.failWrites = false; assertArrayEquals(emptyRoot, empty.storedRootDescriptor()); assertNull(empty.get(identity));
            root = empty.put(identity, rejection).map().storedRootDescriptor();
        }
        try (var cold = new Binding(bytes.copy())) {
            int writes = cold.bytes.writes; var rejection = cold.indexes.openRejections(root).get(identity);
            var checked = StoredPublicationIndexes.checkedRejection(identity, rejection); var state = checked.storedState();
            assertSame(state.selected().managedDraftPlan(), state.executed().managedDraftPlan());
            assertTrue(state.attempt().resourceDemands().stream().anyMatch(demand -> demand == state.issues().get(0).demand()));
            assertThrows(CoordinationObjectStorageException.class, () -> StoredPublicationIndexes.checkedRejection("other", rejection));
            assertEquals(writes, cold.bytes.writes);
        }
    }

    private static final class Binding implements AutoCloseable {
        final Bytes bytes; final DocumentSessionStorage sessions; final DocumentSessionStorage.OpenScope scope;
        final StoredResultRows results; final StoredPublicationIndexes indexes;
        Binding(Bytes bytes) {
            this.bytes = bytes; sessions = new DocumentSessionStorage(bytes, LIMITS); scope = sessions.openScope();
            results = new StoredResultRows(bytes, LIMITS); indexes = new StoredPublicationIndexes(bytes, MAPS, sessions, scope, results, 256);
        }
        public void close() { indexes.close(); scope.close(); results.close(); }
    }
    private static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> records = new LinkedHashMap<>(); final Set<String> reads = new HashSet<>(); int writes; boolean failWrites, badAck;
        public byte[] putIfAbsent(String key, byte[] value) {
            if (failWrites) throw new CoordinationObjectStorageException("Injected physical failure"); writes++;
            byte[] old = records.putIfAbsent(key, value.clone());
            if (old != null && !Arrays.equals(old, value)) throw new CoordinationObjectStorageException("Conflicting immutable bytes");
            return badAck ? new byte[]{0} : (old == null ? value : old).clone();
        }
        public Optional<byte[]> get(String key, int maximumBytes) {
            reads.add(key); byte[] value = records.get(key);
            if (value != null && value.length > maximumBytes) throw new CoordinationObjectStorageException("Selected object bound");
            return Optional.ofNullable(value == null ? null : value.clone());
        }
        Bytes copy() { var next = new Bytes(); records.forEach((key, value) -> next.records.put(key, value.clone())); return next; }
    }
    private static String resource(String directory, String file) throws Exception {
        try (var in = StoredPublicationIndexesTest.class.getResourceAsStream("/" + directory + "/" + file)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
