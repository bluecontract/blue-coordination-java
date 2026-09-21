package blue.coordination.sdk;

import blue.coordination.api.DocumentId;
import blue.coordination.api.DocumentInstancePosition;
import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual library publications and cold instance history; lifecycle retirement is covered separately. */
final class LogicalInstanceHistoryTest {
    @Test void archivedPublicationDoesNotFollowOrConflictWithTheNextActiveHead() throws Exception {
        // given
        var account = new Account();
        DocumentId id = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            return scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root").id();
        });
        var original = account.transact(scope -> scope.instancePosition(id));
        var one = account.transact(scope -> {
            var root = scope.documentHandle(id).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "setCounter", "counterValue: 5");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return scope.instancePosition(id);
        });
        String expectedReceipt = account.transact(scope -> scope.retainedRevision(one).managedEpochReceipt().orElseThrow().receiptIdentity());
        // Select old exact evidence before a later writer commits.
        Publication retainedReader;
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(5, ((Number) scope.retainedRevision(one).after().scalarAt("/counter")).longValue());
            scope.stage(); retainedReader = attempt.prepare("held-history", List.of(), Account.EVIDENCE);
        }
        // when
        account.transact(scope -> {
            var root = scope.documentHandle(id).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "setCounter", "counterValue: 9");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return null;
        });
        // then
        assertTrue(account.records.publish(retainedReader));
        assertTrue(retainedReader.points().stream().noneMatch(point ->
                point.key().family() == Family.SESSION || point.key().family() == Family.INSTANCE_BINDING));
        account.transact(scope -> {
            assertEquals(9, scope.documentHandle(id).orElseThrow().snapshot().longAt("/counter"));
            assertEquals(0, ((Number) scope.retainedRevision(original).after().scalarAt("/counter")).longValue());
            assertEquals(5, ((Number) scope.retainedRevision(one).after().scalarAt("/counter")).longValue());
            assertEquals(expectedReceipt, scope.retainedRevision(one).managedEpochReceipt().orElseThrow().receiptIdentity());
            assertEquals(one.instance(), scope.instancePosition(id).instance());
            return null;
        });
    }

    @Test void coldAdmissionReplayRequiresItsOriginalInstanceAssociation() {
        // given
        var account = new Account();
        var id = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            return scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root").id();
        });
        account.transact(scope -> {
            var root = scope.documentHandle(id).orElseThrow();
            var entry = account.append(scope, root, "rcp2/source", "owner", "setCounter", "counterValue: 7");
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return null;
        });
        account.transact(scope -> {
            var replay = scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            assertEquals(7, replay.snapshot().longAt("/counter"), "Ordinary admission replay remains idempotent");
            return null;
        });
        var associations = account.records.data.keySet().stream().filter(key -> key.family() == Family.INSTANCE_PUBLICATION).toList();
        assertFalse(associations.isEmpty());
        // when
        associations.forEach(account.records.data::remove); // Exact native metadata fault; session and receipt bytes stay intact.
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertThrows(RuntimeException.class, () -> scope.coordination().documents().admitStaticProcessEmbedded(
                    resource("source.yaml"), ActivationPolicy.importFullHistory()));
            assertThrows(RuntimeException.class, scope::stage);
            assertThrows(IllegalStateException.class, () -> attempt.prepare("missing-replay-association", List.of(), Account.EVIDENCE));
        }
    }

    @Test void missingLazyRevisionCannotLeaveTheArchiveReaderStageable() {
        // given
        var account = new Account();
        var position = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            return scope.instancePosition(root.id());
        });
        var revisions = account.objects.values.entrySet().stream().filter(row -> {
            byte[] tag = "blue-coordination/document-revision-storage/2".getBytes(java.nio.charset.StandardCharsets.UTF_16BE);
            var bytes = row.getValue();
            return bytes.length > 4 + tag.length && java.nio.ByteBuffer.wrap(bytes).getInt() == tag.length
                    && Arrays.equals(tag, Arrays.copyOfRange(bytes, 4, 4 + tag.length));
        }).map(Map.Entry::getKey).toList();
        assertFalse(revisions.isEmpty(), "Fault must remove actual numbered revision artifacts");
        // when
        revisions.forEach(account.objects.values::remove);
        // then
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            assertEquals(0, ((Number) scope.retainedRepresentation(position).scalarAt("/counter")).longValue(),
                    "The archived session/view is valid; the missing numbered revision is still lazy");
            assertThrows(RuntimeException.class, () -> scope.retainedRevision(position));
            assertThrows(RuntimeException.class, scope::stage, "Failed lazy history reads must retire the complete owner");
            assertThrows(IllegalStateException.class, () -> attempt.prepare("invalid-after-missing-revision", List.of(), Account.EVIDENCE));
        }
    }

    @Test void forgedInstanceDocumentEpochAndInvocationCannotRelabelRetainedHistory() {
        // given
        var account = new Account();
        var position = account.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            return scope.instancePosition(root.id());
        });
        // when
        for (var bad : List.of(
                new DocumentInstancePosition(new DocumentInstanceRef(position.instance().documentId(), "unknown"), position.epoch(), position.invocationIdentity()),
                new DocumentInstancePosition(new DocumentInstanceRef(DocumentId.of("other-document"), position.instance().instanceId()), position.epoch(), position.invocationIdentity()),
                new DocumentInstancePosition(position.instance(), position.epoch() + 1, position.invocationIdentity()),
                new DocumentInstancePosition(position.instance(), position.epoch(), "other-invocation"))) {
            try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
                // then
                assertThrows(RuntimeException.class, () -> scope.retainedRevision(bad));
                assertThrows(RuntimeException.class, scope::stage, "Invalid selected evidence retires the attempt");
            }
        }
        account.transact(scope -> { assertEquals(0, scope.retainedRevision(position).epoch()); return null; });
    }

    @Test void fullOwnerCyclicPublicationAndRealSplitKeepTheOriginalInstanceWitness() {
        // given
        var account = new Account();
        var ids = account.transact(scope -> {
            var blue = scope.coordination(); blue.timelines().register("rcp2/cycle", "alice");
            var b = blue.documents().admitStaticProcessEmbedded(resource("cycle-b.yaml") + """
                  emitUnmatched:
                    type: Coordination/Sequential Workflow Operation
                    channel: ownerChannel
                    request: {}
                    steps:
                    - type: Coordination/Compute
                      do:
                      - $appendEvent:
                          type: Coordination/Event
                          kind: RCP2/Unmatched
                      - $return: true
                """, ActivationPolicy.importFullHistory()).document("root");
            account.exact.put(b.snapshot().blueId(), b.snapshot().exact().json());
            String authored = resource("cycle-a.yaml") + "\npeer:\n  blueId: " + b.snapshot().blueId() + "\n";
            var exact = blue.values().yaml(authored); account.exact.put(exact.blueId(), exact.json());
            var a = blue.documents().admitStaticProcessEmbedded(authored, ActivationPolicy.importFullHistory()).document("root");
            account.authoredA = exact.blueId(); return List.of(a.id(), b.id());
        });
        account.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var connect = account.append(scope, b, "rcp2/cycle", "ownerChannel", "connectA", "a:\n  blueId: " + account.authoredA);
            assertTrue(scope.coordination().processing().processNext(b).entry(connect).applied());
            assertTrue(scope.coordination().processing().processNext(b).quiescent());
            return null;
        });
        var beforeSplit = account.transact(scope -> {
            var a = scope.documentHandle(ids.get(0)).orElseThrow();
            var subject = account.append(scope, a, "rcp2/cycle", "ownerChannel", "startFinite", "{}");
            var result = scope.coordination().processing().processNext(a).entry(subject);
            assertTrue(result.applied());
            assertEquals(Set.copyOf(ids), result.closures().stream().flatMap(closure -> closure.changes().stream())
                    .map(DocumentChange::documentId).collect(java.util.stream.Collectors.toSet()));
            return ids.stream().map(scope::instancePosition).toList();
        });
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var blocked = scope.retireInstance(beforeSplit.get(0).instance());
            assertEquals(blue.coordination.api.DocumentInstanceRetirement.Status.BLOCKED_POLICY_REQUIRED, blocked.status());
            assertTrue(blocked.blockers().stream().anyMatch(reason -> reason.startsWith("ACTIVE_COMPONENT")));
            scope.stage();
            var proof = attempt.prepare("blocked-cycle-retirement", List.of(), Account.EVIDENCE);
            assertTrue(proof.mutations().isEmpty(), "A policy block cannot partially retire an owner");
        }
        var receipts = account.transact(scope -> beforeSplit.stream().map(position ->
                scope.retainedRevision(position).managedEpochReceipt().orElseThrow().receiptIdentity()).toList());
        String cyclicRepresentation = account.transact(scope -> scope.retainedRepresentation(beforeSplit.get(0)).blueId());
        var representationOnly = account.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var entry = account.append(scope, b, "rcp2/cycle", "ownerChannel", "emitUnmatched", "{}");
            assertTrue(scope.coordination().processing().processNext(b).entry(entry).applied());
            var changed = scope.instancePosition(ids.get(0));
            assertEquals(beforeSplit.get(0).epoch(), changed.epoch(), "A keeps its numbered epoch");
            assertNotEquals(beforeSplit.get(0).invocationIdentity(), changed.invocationIdentity());
            return changed;
        });
        account.transact(scope -> {
            var oldRepresentation = scope.retainedRepresentation(beforeSplit.get(0));
            var changedRepresentation = scope.retainedRepresentation(representationOnly);
            assertEquals(cyclicRepresentation, oldRepresentation.blueId());
            assertTrue(oldRepresentation.cyclicMember());
            assertTrue(changedRepresentation.cyclicMember());
            assertNotEquals(oldRepresentation.blueId(), changedRepresentation.blueId(), "Invocation position selects the exact cyclic representation");
            assertEquals(receipts.get(0), scope.retainedRevision(representationOnly).managedEpochReceipt().orElseThrow().receiptIdentity(),
                    "Representation changes must not rewrite the original numbered receipt");
            assertNotEquals(scope.retainedRevision(representationOnly).after().blueId(), changedRepresentation.blueId());
            return null;
        });
        // when
        account.transact(scope -> {
            var b = scope.documentHandle(ids.get(1)).orElseThrow();
            var detach = account.append(scope, b, "rcp2/cycle", "ownerChannel", "detachA", "{}");
            var result = scope.coordination().processing().processNext(b).entry(detach);
            assertTrue(result.applied());
            assertEquals(Set.copyOf(ids), result.closures().stream().flatMap(closure -> closure.changes().stream())
                    .map(DocumentChange::documentId).collect(java.util.stream.Collectors.toSet()), "Split must publish the complete entry owner set");
            assertEquals("detached", b.snapshot().textAt("/phase"));
            return null;
        });
        // then
        account.transact(scope -> {
            for (int i = 0; i < ids.size(); i++) {
                assertEquals(receipts.get(i), scope.retainedRevision(beforeSplit.get(i)).managedEpochReceipt().orElseThrow().receiptIdentity());
                assertEquals(beforeSplit.get(i).instance(), scope.instancePosition(ids.get(i)).instance());
                assertTrue(scope.instancePosition(ids.get(i)).epoch() > beforeSplit.get(i).epoch());
            }
            assertTrue(scope.coordination().processing().processNext(scope.documentHandle(ids.get(0)).orElseThrow()).quiescent());
            assertTrue(scope.coordination().processing().processNext(scope.documentHandle(ids.get(1)).orElseThrow()).quiescent());
            return null;
        });
        var postSplit = account.transact(scope -> ids.stream().map(scope::instancePosition).toList());
        var retired = account.transact(scope -> scope.retireInstance(postSplit.get(0).instance()));
        assertEquals(blue.coordination.api.DocumentInstanceRetirement.Status.PREPARED, retired.status(), retired.blockers().toString());
        account.transact(scope -> {
            assertTrue(scope.documentHandle(ids.get(0)).isEmpty());
            assertEquals(postSplit.get(1), scope.instancePosition(ids.get(1)));
            assertEquals(cyclicRepresentation, scope.retainedRepresentation(beforeSplit.get(0)).blueId());
            assertEquals(receipts.get(0), scope.retainedRevision(beforeSplit.get(0)).managedEpochReceipt().orElseThrow().receiptIdentity());
            return null;
        });
        var next = new DocumentInstanceRef(ids.get(0), "after-real-split");
        try (var attempt = account.records.attempt(); var scope = account.open(attempt)) {
            var blocked = scope.startInstance(next, beforeSplit.get(0));
            assertEquals(blue.coordination.api.DocumentInstanceStart.Status.BLOCKED_BASIS, blocked.status());
            assertTrue(blocked.blockers().stream().anyMatch(reason -> reason.startsWith("STARTING_CYCLIC_COMPONENT")));
            scope.stage(); assertTrue(attempt.prepare("blocked-old-cycle-basis", List.of(), Account.EVIDENCE).mutations().isEmpty());
        }
        var started = account.transact(scope -> scope.startInstance(next, postSplit.get(0)));
        assertEquals(blue.coordination.api.DocumentInstanceStart.Status.PREPARED, started.status(), started.blockers().toString());
        account.transact(scope -> {
            assertEquals(next, scope.instancePosition(ids.get(0)).instance());
            assertEquals(postSplit.get(1), scope.instancePosition(ids.get(1)), "Starting A2 must preserve B1's exact position");
            var a = scope.documentHandle(ids.get(0)).orElseThrow();
            var touch = account.append(scope, a, "rcp2/cycle", "ownerChannel", "touchA", "{}");
            var result = scope.coordination().processing().processNextStage(a).entry(touch);
            assertTrue(result.applied());
            assertEquals(Set.of(ids.get(0)), result.closures().stream().flatMap(closure -> closure.changes().stream())
                    .map(DocumentChange::documentId).collect(java.util.stream.Collectors.toSet()));
            assertEquals("usable-a", a.snapshot().textAt("/phase"));
            assertEquals(postSplit.get(1), scope.instancePosition(ids.get(1)), "A2 processing cannot restore B's old cycle projection");
            assertEquals(cyclicRepresentation, scope.retainedRepresentation(beforeSplit.get(0)).blueId());
            assertEquals(receipts.get(0), scope.retainedRevision(beforeSplit.get(0)).managedEpochReceipt().orElseThrow().receiptIdentity());
            return null;
        });
    }

    private static String resource(String name) {
        try { return RootedSdkFixture.resource(name); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    static final class Account {
        static final Bytes EVIDENCE = new Bytes(new byte[] {1});
        final SdkRuntimePointMapsTest.LogicalRecords records = new SdkRuntimePointMapsTest.LogicalRecords();
        final SdkRuntimePointMapsTest.Bytes objects = new SdkRuntimePointMapsTest.Bytes();
        final RootedCoordinationStorage.Limits limits;
        final RootedCoordinationStorage.Configuration configuration;
        final Map<String, String> exact = new HashMap<>(), previous = new HashMap<>();
        String authoredA;
        long command, timestamp = 90;
        Account() {
            var base = RootedCoordinationStorageTest.LIMITS; var e = base.engine();
            limits = new RootedCoordinationStorage.Limits(new blue.coordination.internal.RootedEngineStorage.Limits(
                    e.indexNodeBytes(), e.keyBytes(), e.valueBytes(), e.descriptorBytes(), e.cachedNodes(),
                    e.maximumRecordBytes(), e.maximumDepth(), e.maximumScopeBytes(), e.maximumSelectedSessions(),
                    2048, e.maximumPendingEntries(), e.maximumPendingBytes(), e.logChunkBytes(), e.logValueBytes(), e.cachedLogChunks()), base.sdk());
            try (var blue = BlueCoordination.inMemory()) { configuration = RootedCoordinationStorage.configuration(blue, limits); }
        }
        RootedCoordinationStorage.LogicalScope open(blue.coordination.api.storage.CoordinationRecordAttempt attempt) {
            var coldBytes = new blue.coordination.api.storage.CoordinationImmutableObjectStore() {
                public byte[] putIfAbsent(String address, byte[] bytes) { return objects.putIfAbsent(address, bytes); }
                public Optional<byte[]> get(String address, int maximumBytes) { return objects.get(address, maximumBytes); }
            };
            return RootedCoordinationStorage.controlledRepository(coldBytes).openLogical(limits, configuration,
                    attempt, id -> Optional.ofNullable(exact.get(id)));
        }
        <T> T transact(Function<RootedCoordinationStorage.LogicalScope, T> action) {
            try (var attempt = records.attempt(); var scope = open(attempt)) {
                T result = action.apply(scope); scope.stage();
                assertTrue(records.publish(attempt.prepare("command-" + ++command, List.of(), EVIDENCE)));
                return result;
            }
        }
        EntryHandle append(RootedCoordinationStorage.LogicalScope scope, DocumentHandle target,
                String timeline, String channel, String operation, String request) {
            String yaml = """
                    type: Coordination/Timeline Entry
                    timeline: {type: MyOS/MyOS Timeline, timelineId: %s}
                    timestamp: %d
                    actor: {type: MyOS/Principal Actor, accountId: alice}
                    message:
                      type: Coordination/Operation Request
                      document: {blueId: %s}
                      requireExactDocumentVersion: false
                      operation: %s
                      channel: %s
                      request:
                    %s
                    """.formatted(timeline, timestamp, target.snapshot().blueId(), operation, channel, request.indent(4));
            timestamp += 10;
            if (previous.containsKey(timeline)) yaml += "\nprevEntry: {blueId: " + previous.get(timeline) + "}\n";
            var blue = scope.coordination();
            var entry = blue.events().from(scope.timelineHandle(timeline).orElseThrow()).exact(blue.values().yaml(yaml)).submit();
            previous.put(timeline, entry.blueId()); return entry;
        }
    }
}
