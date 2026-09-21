package blue.coordination.consumer;

import blue.coordination.api.DocumentInstanceRef;
import blue.coordination.api.DocumentInstanceRetirement;
import blue.coordination.api.DocumentInstanceStart;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecordStore;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import blue.coordination.sdk.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public host ports and lifecycle compiled exclusively against the built artifact. */
final class LogicalLifecycleBuiltJarConsumerTest {
    @Test
    void coldReplacementFencesOldWorkAndReplaysTheOriginalEntryAgainstBuiltJar() {
        // given
        assertTrue(BlueCoordination.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"));
        var host = new Host();
        var basis = host.transact(scope -> {
            scope.coordination().timelines().register("rcp2/source", "alice");
            var root = scope.coordination().documents().admitStaticProcessEmbedded(SOURCE,
                    ActivationPolicy.importFullHistory()).document("root");
            return scope.instancePosition(root.id());
        });
        String entryId = host.transact(scope -> {
            var root = scope.documentHandle(basis.instance().documentId()).orElseThrow();
            var entry = scope.coordination().events().from(scope.timelineHandle("rcp2/source").orElseThrow())
                    .exact(scope.coordination().values().yaml("""
                            type: Coordination/Timeline Entry
                            timeline: {type: MyOS/MyOS Timeline, timelineId: rcp2/source}
                            timestamp: 90
                            actor: {type: MyOS/Principal Actor, accountId: alice}
                            message:
                              type: Coordination/Operation Request
                              document: {blueId: %s}
                              requireExactDocumentVersion: false
                              operation: tick
                              channel: owner
                              request: {}
                            """.formatted(root.snapshot().blueId()))).submit();
            assertTrue(scope.coordination().processing().processNextStage(root).entry(entry).applied());
            return entry.blueId();
        });
        var original = host.transact(scope -> scope.instancePosition(basis.instance().documentId()));
        String originalJson = host.transact(scope -> scope.retainedRevision(original).after().json());
        Publication oldWork;
        try (var attempt = host.attempt(); var scope = host.open(attempt)) {
            scope.coordination().processing().processNextStage(scope.documentHandle(basis.instance().documentId()).orElseThrow());
            scope.stage(); oldWork = attempt.prepare("old-owner", List.of(), Host.EVIDENCE);
        }
        // when
        host.transact(scope -> {
            assertEquals(DocumentInstanceRetirement.Status.PREPARED, scope.retireInstance(basis.instance()).status());
            return null;
        });
        boolean staleAccepted = host.publish(oldWork);
        var replacement = new DocumentInstanceRef(basis.instance().documentId(), "public-jar-replacement");
        host.transact(scope -> {
            assertEquals(DocumentInstanceStart.Status.PREPARED, scope.startInstance(replacement, basis).status());
            return null;
        });
        host.transact(scope -> {
            var root = scope.documentHandle(replacement.documentId()).orElseThrow();
            assertEquals(0L, root.snapshot().longAt("/counter"));
            var result = scope.coordination().processing().processNextStage(root);
            assertTrue(result.entries().stream().anyMatch(resultEntry -> resultEntry.entry().blueId().equals(entryId) && resultEntry.applied()));
            return null;
        });
        // then
        assertFalse(staleAccepted);
        host.transact(scope -> {
            var root = scope.documentHandle(replacement.documentId()).orElseThrow();
            assertEquals(1L, root.snapshot().longAt("/counter"));
            assertEquals(originalJson, scope.retainedRevision(original).after().json());
            assertTrue(scope.coordination().processing().recordedResult(original.instance(), entryId).orElseThrow().applied());
            assertEquals(basis.instance(), original.instance());
            assertEquals(replacement, scope.instancePosition(root.id()).instance());
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, scope.coordination().processing().processNextStage(root).disposition());
            assertTrue(scope.coordination().documents().admitStaticProcessEmbedded(SOURCE,
                    ActivationPolicy.importFullHistory()).document("root").snapshot().epoch() == 0);
            return null;
        });
    }

    private static final class Host implements CoordinationImmutableObjectStore {
        static final Bytes EVIDENCE = new Bytes(new byte[] {1});
        static final Address ADDRESS = new Address("jar-account", "stable-domain");
        final TreeMap<Key, Value> records = new TreeMap<>();
        final Map<String, byte[]> objects = new HashMap<>();
        final Map<String, Bytes> committed = new HashMap<>();
        final RootedCoordinationStorage.Limits limits;
        final RootedCoordinationStorage.Configuration configuration;
        long command;
        Host() {
            int max = 32 * 1024 * 1024;
            limits = new RootedStorageBounds(
                    new RootedStorageBounds.Engine(40 * 1024 * 1024, 65536, max, 8192, 32,
                            max, 256, 256L * 1024 * 1024, 100, 2048, 100, 256L * 1024 * 1024,
                            40 * 1024 * 1024, max, 16),
                    new RootedStorageBounds.Sdk(new RootedStorageBounds.Index(512 * 1024, 65536,
                            65536, 8192, 32, 256 * 1024, 4L * 1024 * 1024, 100),
                            max, 128 * 1024, max, 100, 256 * 1024)).toLimits();
            try (var blue = BlueCoordination.inMemory()) {
                configuration = RootedCoordinationStorage.configuration(blue, limits);
            }
        }
        CoordinationRecordAttempt attempt() {
            var snapshot = new TreeMap<>(records);
            return new CoordinationRecordAttempt(new CoordinationRecordStore.ReadScope() {
                boolean closed;
                void check() { if (closed) throw new IllegalStateException("Closed read scope"); }
                public Address address() { check(); return ADDRESS; }
                public Value read(Key key) { check(); return snapshot.getOrDefault(key, Value.absent()); }
                public List<Row> query(Range range) { check(); return rows(snapshot, range); }
                public void close() { closed = true; }
            });
        }
        RootedCoordinationStorage.LogicalScope open(CoordinationRecordAttempt attempt) {
            return RootedCoordinationStorage.controlledRepository(this).openLogical(limits, configuration, attempt, id -> Optional.empty());
        }
        <T> T transact(Function<RootedCoordinationStorage.LogicalScope, T> action) {
            Publication packet; T result;
            try (var attempt = attempt(); var scope = open(attempt)) {
                result = action.apply(scope); scope.stage();
                packet = attempt.prepare("command-" + ++command, List.of(), EVIDENCE);
            }
            assertTrue(publish(packet)); return result;
        }
        boolean publish(Publication packet) {
            assertEquals(ADDRESS, packet.address());
            if (committed.containsKey(packet.id())) { assertEquals(committed.get(packet.id()), packet.digest()); return true; }
            for (var point : packet.points()) if (!point.expected().equals(records.getOrDefault(point.key(), Value.absent()))) return false;
            for (var query : packet.queries()) if (!query.expected().equals(rows(records, query.range()))) return false;
            for (var artifact : packet.artifacts()) assertEquals(artifact.bytes(), objects.get(artifact.sha256().hex()).length);
            for (var fact : packet.immutableFacts()) {
                var prior = records.get(fact.key());
                if (prior != null) assertEquals(fact.content(), prior.content());
            }
            for (var fact : packet.immutableFacts()) records.putIfAbsent(fact.key(), new Value(1, fact.content()));
            for (var mutation : packet.mutations()) records.put(mutation.key(), new Value(records.getOrDefault(mutation.key(), Value.absent()).revision() + 1, mutation.content()));
            committed.put(packet.id(), packet.digest()); return true;
        }
        static List<Row> rows(TreeMap<Key, Value> data, Range range) {
            return data.entrySet().stream().filter(e -> range.contains(e.getKey()) && e.getValue().present())
                    .map(e -> new Row(e.getKey(), e.getValue())).toList();
        }
        public byte[] putIfAbsent(String address, byte[] bytes) {
            objects.putIfAbsent(address, bytes.clone()); assertArrayEquals(objects.get(address), bytes); return objects.get(address).clone();
        }
        public Optional<byte[]> get(String address, int maximumBytes) {
            var bytes = objects.get(address);
            if (bytes != null && bytes.length > maximumBytes) throw new IllegalArgumentException("Physical byte limit exceeded");
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
    }

    private static final String SOURCE = """
            name: RCP2 Source
            counter: 0
            contracts:
              owner:
                type: Coordination/Timeline Channel
                timeline:
                  type: MyOS/MyOS Timeline
                  timelineId: rcp2/source
                actor:
                  type: MyOS/Principal Actor
                  accountId: alice
              tick:
                type: Coordination/Sequential Workflow Operation
                channel: owner
                request: {}
                steps:
                - type: Coordination/Compute
                  do:
                  - $appendChange:
                      op: replace
                      path: /counter
                      val:
                        $add:
                        - $document: /counter
                        - 1
                  - $appendEvent:
                      type: Coordination/Event
                      kind: RCP2/Tick
                  - $return: true
              setCounter:
                type: Coordination/Sequential Workflow Operation
                channel: owner
                request:
                  counterValue:
                    type: Integer
                steps:
                - type: Coordination/Compute
                  do:
                  - $appendChange:
                      op: replace
                      path: /counter
                      val:
                        $binding: event/message/request/counterValue
                  - $appendEvent:
                      type: Coordination/Event
                      kind: RCP2/Tick
                  - $return: true
            """;
}
