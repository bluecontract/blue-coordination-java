package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.storage.CoordinationRecordAttempt;
import blue.coordination.api.storage.CoordinationRecordStore;
import blue.coordination.api.storage.CoordinationRecords.*;
import java.util.TreeMap;
import blue.coordination.api.SourceHistoryPrerequisite;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine SDK rows, supplied existing engines: these controls are not a full cold-engine factory proof. */
final class SdkRuntimePointMapsTest {
    private static final int MAX = 8 * 1024 * 1024;
    private static final SdkRuntimePointMaps.Limits LIMITS = new SdkRuntimePointMaps.Limits(
            new InsertionOrderedStorage.Limits(512 * 1024, 64 * 1024, 64 * 1024, 4096, 32,
                    256 * 1024, 4 * 1024 * 1024, 100),
            new SdkPointStorage.Limits(MAX, 128 * 1024, MAX, 100), 256 * 1024);

    @Test void logicalRowsRestoreActualSdkExecutionWithColdOwnedHandles() throws Exception {
        // given
        var records = new LogicalRecords(); var objects = new Bytes();
        try (var original = new Fixture(); var receiver = new Fixture(); var attempt = records.attempt()) {
            var binding = new blue.coordination.internal.LogicalPointStorage(attempt);
            try (var maps = SdkRuntimePointMaps.openLogical(original.runtime, objects, LIMITS, binding)) {
                original.runtime.installPointMaps(maps.maps());
                // when
                var result = original.execute(); var independent = receiver.execute();
                binding.stage(); var packet = attempt.prepare("sdk", List.of(), LogicalRecords.EVIDENCE);
                // then
                assertTrue(packet.queries().isEmpty(), "ordinary SDK work selects points, not all SDK families");
                assertTrue(records.publish(packet));
                assertThrows(NoncommittingExecutionException.class, maps::snapshot);
                try (var coldAttempt = records.attempt()) {
                    var coldObjects = objects.fresh();
                    var coldBinding = new blue.coordination.internal.LogicalPointStorage(coldAttempt);
                    try (var cold = SdkRuntimePointMaps.openLogical(receiver.runtime, coldObjects, LIMITS, coldBinding)) {
                        assertEquals(0, coldObjects.reads);
                        var restored = cold.maps().results().get(result.entry().blueId());
                        assertArrayEquals(original.codec.encode(result), receiver.codec.encode(restored));
                        assertEquals(independent.entry(), restored.entry());
                        assertNotEquals(result.entry(), restored.entry());
                        assertSame(restored, cold.maps().results().get(result.entry().blueId()));
                        assertTrue(cold.maps().intents().get(result.entry().blueId()).targeted());
                        assertNotNull(cold.maps().entries().get(result.entry().blueId()));
                        assertEquals(0, coldObjects.writes);
                    }
                }
            }
        }
    }

    @Test void differentLogicalSdkTimelinesPublishInBothOrdersWithoutAnInsertionCounter() throws Exception {
        // given
        for (boolean reverse : List.of(false, true)) {
            var records = new LogicalRecords(); var objects = new Bytes();
            try (var left = new Fixture(); var right = new Fixture(); var a = records.attempt(); var b = records.attempt()) {
                var ca = new blue.coordination.internal.LogicalPointStorage(a);
                var cb = new blue.coordination.internal.LogicalPointStorage(b);
                try (var ma = SdkRuntimePointMaps.openLogical(left.runtime, objects, LIMITS, ca);
                     var mb = SdkRuntimePointMaps.openLogical(right.runtime, objects, LIMITS, cb)) {
                    left.runtime.installPointMaps(ma.maps()); right.runtime.installPointMaps(mb.maps());
                    // when
                    left.runtime.registerTimeline("left", "alice"); right.runtime.registerTimeline("right", "alice");
                    ca.stage(); cb.stage();
                    var pa = a.prepare("left", List.of(), LogicalRecords.EVIDENCE);
                    var pb = b.prepare("right", List.of(), LogicalRecords.EVIDENCE);
                    // then
                    assertTrue(pa.queries().isEmpty()); assertTrue(pb.queries().isEmpty());
                    assertEquals(1, pa.mutations().size()); assertEquals(1, pb.mutations().size());
                    assertTrue(records.publish(reverse ? pb : pa)); assertTrue(records.publish(reverse ? pa : pb));
                    try (var owner = new Fixture(); var coldAttempt = records.attempt()) {
                        owner.runtime.registerTimeline("left", "alice"); owner.runtime.registerTimeline("right", "alice");
                        var binding = new blue.coordination.internal.LogicalPointStorage(coldAttempt);
                        try (var cold = SdkRuntimePointMaps.openLogical(owner.runtime, objects, LIMITS, binding)) {
                            assertEquals("left", cold.maps().timelines().get("left").id());
                            assertEquals("right", cold.maps().timelines().get("right").id());
                        }
                    }
                }
            }
        }
    }

    @Test void genuineRowsColdOpenLazilyWithNewOwnerHandlesAndNoProcessingOrReadTimeWrites() throws Exception {
        // given
        try (var original = new Fixture(); var receiver = new Fixture()) {
            var first = original.execute();
            // when
            var independent = receiver.execute();
            // then
            assertEquals(first.entry().blueId(), independent.entry().blueId());
            var bytes = new Bytes();
            try (var retained = SdkRuntimePointMaps.retain(original.runtime, bytes, LIMITS)) {
                var coldBytes = bytes.fresh(); long before = receiver.processingNanos();
                try (var cold = SdkRuntimePointMaps.open(receiver.runtime, coldBytes, LIMITS, retained.snapshot())) {
                    assertEquals(0, coldBytes.pointReads, "opening roots never reads SDK payload bodies");
                    var maps = cold.maps(); int reads = coldBytes.reads;
                    assertEquals(2, maps.timelines().size()); assertFalse(maps.intents().isEmpty());
                    assertEquals(reads, coldBytes.reads, "size uses root metadata only");
                    assertEquals(new ArrayList<>(original.runtime.storedMaps().timelines().keySet()), new ArrayList<>(maps.timelines().keySet()));
                    assertEquals(0, coldBytes.pointReads, "key iteration remains body-free");
                    var restored = maps.results().get(first.entry().blueId());
                    assertEquals(independent.entry(), restored.entry()); assertNotEquals(first.entry(), restored.entry());
                    assertSame(restored, maps.results().get(first.entry().blueId()));
                    assertArrayEquals(original.codec.encode(first), original.codec.encode(restored));
                    assertEquals(2, coldBytes.pointReads, "one result plus its registered Timeline");
                    assertTrue(maps.intents().get(first.entry().blueId()).targeted());
                    assertEquals(4, coldBytes.pointReads, "intent selects mandatory core row, no unrelated records");
                    assertSame(((DefaultCoordinationEngine) receiver.runtime.engine()).auditTimelineEntry(first.entry().blueId()).orElseThrow(),
                            maps.entries().get(first.entry().blueId()).entry());
                    assertEquals(TimelineActorKind.AGENT, maps.timelines().get("empty-agent").actorKind());
                    assertEquals(before, receiver.processingNanos()); assertEquals(0, coldBytes.writes);
                    assertThrows(IllegalArgumentException.class, () -> receiver.runtime.drainJournalThrough(first.entry(), new DrainBudget(1, 1)));
                }
            }
        }
    }

    @Test void installedMapsAcceptActualSdkAppendsAndPreserveInputAndResultOwnership() throws Exception {
        // given
        try (var fixture = new Fixture(); var maps = SdkRuntimePointMaps.empty(fixture.runtime, new Bytes(), LIMITS)) {
            fixture.runtime.installPointMaps(maps.maps());
            // when
            var result = fixture.execute();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            assertSame(result, maps.maps().results().get(result.entry().blueId()));
            assertEquals(1, maps.maps().entries().size()); assertEquals(1, maps.maps().intents().size());
            assertTrue(maps.maps().intents().get(result.entry().blueId()).targeted());
            var timeline = maps.maps().timelines().get("rcp2/source");
            assertSame(timeline, fixture.runtime.registerTimeline("rcp2/source", "alice"));
            assertEquals(List.of("rcp2/source", "empty-agent"), new ArrayList<>(maps.maps().timelines().keySet()));
        }
    }

    @Test void sourceResultUsesTheCompletePrerequisiteKeyAndRetainsTheStoppedParent() throws Exception {
        // given
        try (var f = new Fixture()) {
            var parent = f.start("parent.yaml", "rcp2/parent");
            var source = f.runtime.exactValue(RootedSdkFixture.resource("source.yaml"));
            f.exact.put(source.blueId(), source.json()); f.timelines.put("rcp2/source", f.runtime.registerTimeline("rcp2/source", "alice"));
            var sourceEntry = f.append(source.blueId(), "rcp2/source", "setCounter", 15, "counterValue: 5");
            var attach = f.append(parent.snapshot().blueId(), "rcp2/parent", "attach", 20, "child:\n  blueId: " + source.blueId());
            // when
            var stopped = f.runtime.processNextRoot(parent).entry(attach);
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, stopped.disposition());
            var admission = f.runtime.sourceHistoryPrerequisites(parent).get(0);
            assertTrue(f.runtime.processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var live = f.runtime.sourceHistoryPrerequisites(parent).get(0);
            f.runtime.processSourceHistoryPrerequisite(live);
            var result = f.runtime.sourceHistoryProcessingResult(live).orElseThrow();
            assertEquals(EntryDisposition.APPLIED, result.entry(sourceEntry).disposition());
            var objects = new Bytes();
            try (var stored = SdkRuntimePointMaps.retain(f.runtime, objects, LIMITS);
                 var cold = SdkRuntimePointMaps.open(f.runtime, objects.fresh(), LIMITS, stored.snapshot())) {
                long before = f.processingNanos(); String head = parent.snapshot().blueId();
                assertArrayEquals(f.codec.encode(result), f.codec.encode(cold.maps().sourceResults().get(live)));
                assertArrayEquals(f.codec.encode(stopped), f.codec.encode(cold.maps().results().get(attach.blueId())));
                var wrongCutoff = new SourceHistoryPrerequisite(live.selectionIdentity(), live.requestingRoot(),
                        live.requestingInvocationIdentity(), live.demandIdentity(), live.sourceDocumentId(), live.authoredBlueId(),
                        ExternalOrderKey.of(List.of(999L)), live.kind(), live.sourceEpoch(), live.sourceBlueId(), live.workIdentity(),
                        live.entryBlueId(), live.journalRevision(), live.routeGeneration(), live.sourceSurfaceIdentity(), live.diagnostic());
                assertNull(cold.maps().sourceResults().get(wrongCutoff));
                assertFalse(cold.maps().sourceResults().containsKey(wrongCutoff));
                assertEquals(before, f.processingNanos()); assertEquals(head, parent.snapshot().blueId());
            }
        }
    }

    @Test void foreignOwnersMissingCoreAndMissingPhysicalPayloadsFailClosedWithoutFallback() throws Exception {
        // given
        try (var original = new Fixture(); var receiver = new Fixture()) {
            var first = original.execute(); receiver.execute(); var objects = new Bytes();
            try (var stored = SdkRuntimePointMaps.retain(original.runtime, objects, LIMITS);
                 var empty = SdkRuntimePointMaps.empty(receiver.runtime, objects, LIMITS);
                 var cold = SdkRuntimePointMaps.open(receiver.runtime, objects.fresh(), LIMITS, stored.snapshot())) {
                // when
                var snapshot = cold.snapshot();
                // then
                assertThrows(NoncommittingExecutionException.class, () -> cold.maps().results().put(first.entry().blueId(), first));
                assertThrows(NoncommittingExecutionException.class,
                        () -> cold.maps().timelines().put("rcp2/source", original.timelines.get("rcp2/source")));
                same(snapshot, cold.snapshot());
                var missing = new EnumMap<>(snapshot.roots());
                missing.put(SdkRuntimePointMaps.Kind.ENTRIES, empty.snapshot().roots().get(SdkRuntimePointMaps.Kind.ENTRIES));
                try (var opened = SdkRuntimePointMaps.open(receiver.runtime, objects.fresh(), LIMITS, new SdkRuntimePointMaps.Snapshot(missing))) {
                    assertThrows(NoncommittingExecutionException.class, () -> opened.maps().intents().get(first.entry().blueId()));
                    assertNotNull(opened.maps().results().get(first.entry().blueId()), "advanced results need not have SDK submission membership");
                }
                var absent = objects.fresh(); absent.values.entrySet().removeIf(row -> pointKind(row.getValue(), "ENTRY_RESULT"));
                try (var opened = SdkRuntimePointMaps.open(receiver.runtime, absent, LIMITS, snapshot)) {
                    assertEquals(1, opened.maps().results().size());
                    assertThrows(NoncommittingExecutionException.class, () -> opened.maps().results().get(first.entry().blueId()));
                }
            }
        }
    }

    @Test void mismatchedConfigurationAndClosedSdkRejectEvenWarmValues() throws Exception {
        // given
        var f = new Fixture(); var objects = new Bytes();
        // when
        f.execute();
        try (var stored = SdkRuntimePointMaps.retain(f.runtime, objects, LIMITS);
             var wrong = new Fixture(ContractsExecutionPolicy.exactSharedGas(9000, "different"))) {
            // then
            assertThrows(NoncommittingExecutionException.class, () -> SdkRuntimePointMaps.open(wrong.runtime, objects.fresh(), LIMITS, stored.snapshot()));
            var maps = stored.maps(); var value = maps.timelines().get("rcp2/source"); assertNotNull(value);
            var iterator = maps.timelines().keySet().iterator();
            f.close();
            assertThrows(NoncommittingExecutionException.class, () -> maps.timelines().get("rcp2/source"));
            assertThrows(NoncommittingExecutionException.class, iterator::hasNext);
            assertThrows(NoncommittingExecutionException.class, maps.timelines()::size);
        } finally { f.close(); }
    }

    @Test void installedScopeAndSdkCloseInEitherOrderReleaseAllMapViews() throws Exception {
        // given
        for (boolean scopeFirst : List.of(true, false)) {
            var fixture = new Fixture();
            try {
                var stored = SdkRuntimePointMaps.empty(fixture.runtime, new Bytes(), LIMITS);
                try {
                    var maps = stored.maps(); fixture.runtime.installPointMaps(maps);
                    // when
                    fixture.runtime.registerTimeline("empty", "alice");
                    // then
                    assertNotNull(maps.timelines().get("empty"));
                    if (scopeFirst) stored.close();
                    fixture.close(); // closes all five installed maps even after owner.closed becomes true
                    stored.close();
                    for (Map<?, ?> map : List.of(maps.timelines(), maps.entries(), maps.intents(), maps.results(), maps.sourceResults())) {
                        assertInstanceOf(AutoCloseable.class, map);
                        assertThrows(NoncommittingExecutionException.class, map::size);
                        ((AutoCloseable) map).close(); // group close remains idempotent
                    }
                } finally { stored.close(); }
            } finally { fixture.close(); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, String> exact = new LinkedHashMap<>();
        final Map<String, TimelineHandle> timelines = new LinkedHashMap<>();
        final SdkStorageCodec codec = new SdkStorageCodec(new Object(), MAX);
        final SdkCoordinationRuntime runtime;
        Fixture() { this(ContractsExecutionPolicy.releaseDefault()); }
        Fixture(ContractsExecutionPolicy policy) {
            var manifest = BundledContracts10Release.manifest();
            runtime = SdkCoordinationRuntime.create(new Object(), manifest.blueLanguageSpecification(), manifest.contractsSpecification(),
                    id -> Optional.ofNullable(exact.get(id)), true, policy);
        }
        DocumentHandle start(String resource, String timeline) throws IOException {
            timelines.put(timeline, runtime.registerTimeline(timeline, "alice"));
            var document = new DocumentCatalog(runtime).admitStaticProcessEmbedded(RootedSdkFixture.resource(resource), ActivationPolicy.importFullHistory()).document("root");
            exact.put(document.snapshot().blueId(), document.snapshot().exact().json()); return document;
        }
        EntryResult execute() throws IOException {
            var source = start("source.yaml", "rcp2/source"); runtime.registerTimeline("empty-agent", "agent-account", TimelineActorKind.AGENT);
            var result = new OperationGateway(runtime).on(source).from(timelines.get("rcp2/source"))
                    .call("setCounter").through("owner").requestYaml("counterValue: 5").execute();
            assertEquals(EntryDisposition.APPLIED, result.disposition()); return result;
        }
        EntryHandle append(String target, String timeline, String operation, long timestamp, String request) {
            var envelope = runtime.exactValue("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: %s
                    timestamp: %d
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      document:
                        blueId: %s
                      operation: %s
                      channel: owner
                      request:
                    %s
                    """.formatted(timeline, timestamp, target, operation, request.indent(4)));
            return new EventGateway(runtime).from(timelines.get(timeline)).exact(envelope).submit();
        }
        long processingNanos() { return CoordinationTestControl.attach(runtime.engine()).metricsSnapshot().phaseNanos().getOrDefault("contracts.closure.processor", 0L); }
        @Override public void close() { runtime.close(); }
    }
    static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> values = new LinkedHashMap<>(); int reads, writes, pointReads;
        @Override public byte[] putIfAbsent(String digest, byte[] bytes) { writes++; values.putIfAbsent(digest, bytes.clone()); return values.get(digest).clone(); }
        @Override public Optional<byte[]> get(String digest, int maximum) {
            reads++; byte[] value = values.get(digest);
            if (value != null && value.length > maximum) throw new CoordinationObjectStorageException("Oversized test object");
            if (value != null && isPoint(value)) pointReads++;
            return Optional.ofNullable(value == null ? null : value.clone());
        }
        Bytes fresh() { var copied = new Bytes(); values.forEach((key, value) -> copied.values.put(key, value.clone())); return copied; }
    }
    private static boolean isPoint(byte[] bytes) { return pointKind(bytes, null); }
    private static boolean pointKind(byte[] bytes, String kind) {
        try {
            var fields = new SdkStorageCodec(new Object(), MAX).decode(bytes, List.class);
            return fields.size() == 5 && "blue-coordination/rooted-sdk-point/1".equals(fields.get(0))
                    && (kind == null || kind.equals(fields.get(1)));
        } catch (NoncommittingExecutionException ignored) { return false; }
    }
    private static void same(SdkRuntimePointMaps.Snapshot expected, SdkRuntimePointMaps.Snapshot actual) {
        assertEquals(expected.roots().keySet(), actual.roots().keySet());
        expected.roots().forEach((kind, a) -> { var b = actual.roots().get(kind);
            assertArrayEquals(a.keys(), b.keys()); assertArrayEquals(a.order(), b.order()); assertEquals(a.nextSequence(), b.nextSequence()); });
    }
    static final class LogicalRecords {
        static final blue.coordination.api.storage.CoordinationRecords.Bytes EVIDENCE = new blue.coordination.api.storage.CoordinationRecords.Bytes(new byte[] {1});
        static final Address ADDRESS = new Address("sdk-test", "instance");
        final TreeMap<Key, Value> data = new TreeMap<>();
        CoordinationRecordAttempt attempt() {
            var snapshot = new TreeMap<>(data);
            return new CoordinationRecordAttempt(new CoordinationRecordStore.ReadScope() {
                boolean closed;
                void open() { if (closed) throw new IllegalStateException("Closed fixture snapshot"); }
                public Address address() { open(); return ADDRESS; }
                public Value read(Key key) { open(); return snapshot.getOrDefault(key, Value.absent()); }
                public List<Row> query(Range range) { open(); return rows(snapshot, range); }
                public Optional<Row> first(Range range) { return query(range).stream().findFirst(); }
                public void close() { closed = true; }
            });
        }
        boolean publish(Publication packet) {
            for (var point : packet.points()) if (!point.expected().equals(data.getOrDefault(point.key(), Value.absent()))) return false;
            for (var query : packet.queries()) if (!query.expected().equals(rows(data, query.range()))) return false;
            for (var fact : packet.immutableFacts()) {
                var prior = data.get(fact.key());
                if (prior != null && !fact.content().equals(prior.content())) throw new IllegalArgumentException("Different immutable fact");
            }
            for (var fact : packet.immutableFacts()) data.putIfAbsent(fact.key(), new Value(1, fact.content()));
            for (var mutation : packet.mutations()) data.put(mutation.key(), new Value(data.getOrDefault(mutation.key(), Value.absent()).revision() + 1, mutation.content()));
            return true;
        }
        private static List<Row> rows(TreeMap<Key, Value> data, Range range) {
            return data.entrySet().stream().filter(e -> range.contains(e.getKey()) && e.getValue().content() != null)
                    .map(e -> new Row(e.getKey(), e.getValue())).toList();
        }
    }
}
