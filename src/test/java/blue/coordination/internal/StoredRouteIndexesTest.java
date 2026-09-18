package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.processor.TimelineProviderSupport;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.processor.SubscriptionDelta;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class StoredRouteIndexesTest {
    static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(65536, 4096, 60000, 2048, 64);
    @TempDir Path temporary;

    @Test void coldRoutesPreserveExactShapeDeliveriesGenerationAndMutationCounters() {
        // given
        var bytes = new Bytes(); var writer = new StoredRouteIndexes(bytes, LIMITS);
        var aMetrics = new EngineMetrics(); var bMetrics = new EngineMetrics();
        var a = new OperationRouteIndex(aMetrics); var b = new OperationRouteIndex(bMetrics);
        for (int i : List.of(7, 2, 13, 1, 9, 4)) {
            replace(a, i, "timeline/" + i); replace(b, i, "timeline/" + i);
        }
        b = writer.retainPartition(b, bMetrics, ignored -> null, ignored -> null);
        var roots = roots(writer, b); var cold = new StoredRouteIndexes(bytes.fresh(), LIMITS);
        // when
        b = cold.open(roots::get, b.generation(), bMetrics, ignored -> null, ignored -> null);
        // then
        assertEquals(a.generation(), b.generation());
        assertEquals(a.storedIndexes().rows().heightForTesting(), b.storedIndexes().rows().heightForTesting());
        for (int i : List.of(1, 2, 4, 7, 9, 13, 0)) {
            var entry = entry("timeline/" + i);
            assertEquals(a.route(entry), b.route(entry));
            assertEquals(a.selectDirectDeliveries(entry), b.selectDirectDeliveries(entry));
        }
        for (String timeline : List.of("timeline/7", "timeline/new", "timeline/new")) {
            replace(a, 7, timeline); replace(b, 7, timeline);
            assertEquals(a.generation(), b.generation());
            assertEquals(aMetrics.snapshot().counters(), bMetrics.snapshot().counters());
            assertEquals(a.storedIndexes().rows().entries(), b.storedIndexes().rows().entries());
            assertEquals(a.storedIndexes().keysByDocument().entries(), b.storedIndexes().keysByDocument().entries());
        }
        var stale = b.prepareReplacement(List.of(replacement(7, "timeline/stale")));
        replace(b, 7, "timeline/final"); long generation = b.generation();
        assertThrows(IllegalStateException.class, stale::publish);
        assertEquals(generation, b.generation());
        assertEquals(List.of(id(7)), cold.open(roots::get, 6L, new EngineMetrics(), ignored -> null, ignored -> null).route(entry("timeline/7")));
        b.clear();
        assertEquals(generation + 1, b.generation());
        assertTrue(b.storedIndexes().rows().isStored());
        var emptyRoots = roots(cold, b);
        assertTrue(cold.open(emptyRoots::get, b.generation(), new EngineMetrics(), ignored -> null, ignored -> null).route(entry("timeline/final")).isEmpty());
    }

    @Test void openingAndOneRouteDoNotReplayOrResolveUnrelatedDocuments() {
        // given
        var bytes = new Bytes(); var storage = new StoredRouteIndexes(bytes, LIMITS);
        var index = new OperationRouteIndex(new EngineMetrics());
        for (int i = 0; i < 255; i++) replace(index, i, "timeline/" + i);
        var stored = storage.retainPartition(index, new EngineMetrics(), ignored -> { throw new AssertionError("session scan"); },
                ignored -> { throw new AssertionError("head scan"); });
        var roots = roots(storage, stored); var coldBytes = bytes.fresh(); var cold = new StoredRouteIndexes(coldBytes, LIMITS);
        var metrics = new EngineMetrics();
        // when
        var opened = cold.open(roots::get, index.generation(), metrics, ignored -> { throw new AssertionError("session scan"); },
                ignored -> { throw new AssertionError("head scan"); });
        // then
        assertEquals(2, coldBytes.reads, "only the two selected root metadata nodes");
        assertEquals(new EngineMetrics().snapshot().counters(), metrics.snapshot().counters());
        assertEquals(List.of(id(127)), opened.route(entry("timeline/127")));
        assertTrue(coldBytes.reads < 50, "exact route reads AVL paths, not the route catalog");
        assertEquals(0, coldBytes.writes);
    }

    @Test void failedPreparationMissingBytesAndForeignRootsFailWithoutPublication() {
        // given
        var bytes = new Bytes(); var storage = new StoredRouteIndexes(bytes, LIMITS);
        var resident = new OperationRouteIndex(new EngineMetrics()); replace(resident, 1, "timeline/1");
        var stored = storage.retainPartition(resident, new EngineMetrics(), ignored -> null, ignored -> null);
        var roots = roots(storage, stored); long generation = stored.generation();
        // when
        bytes.failWriteAt = bytes.writes + 2;
        // then
        assertThrows(NoncommittingExecutionException.class, () -> replace(stored, 1, "timeline/new"));
        sameRoots(roots, roots(storage, stored)); assertEquals(generation, stored.generation());
        bytes.failWriteAt = -1;
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(root -> null, generation, new EngineMetrics(), ignored -> null, ignored -> null));
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(root -> roots.get(StoredRouteIndexes.Root.DOCUMENT_KEYS), generation,
                new EngineMetrics(), ignored -> null, ignored -> null));
        assertThrows(NoncommittingExecutionException.class, () -> storage.open(roots::get, -1L, new EngineMetrics(), ignored -> null, ignored -> null));
        var missing = new Bytes(); var unavailable = new StoredRouteIndexes(missing, LIMITS);
        assertThrows(NoncommittingExecutionException.class, () -> unavailable.open(roots::get, generation, new EngineMetrics(), ignored -> null, ignored -> null));
        var damaged = bytes.fresh(); damaged.values.replaceAll((key, value) -> { var copy = value.clone(); copy[copy.length - 1] ^= 1; return copy; });
        var corrupt = new StoredRouteIndexes(damaged, LIMITS);
        assertThrows(NoncommittingExecutionException.class, () -> corrupt.open(roots::get, generation, new EngineMetrics(), ignored -> null, ignored -> null));
    }

    @Test void fileOnlyFreshJvmReopensBothFamiliesWithoutRebuild() throws Exception {
        // given
        Path objects = temporary.resolve("objects");
        var bytes = new FileCoordinationObjectStore(objects, LIMITS.nodeBytes());
        var routes = new StoredRouteIndexes(bytes, LIMITS);
        var index = new OperationRouteIndex(new EngineMetrics()); replace(index, 1, "timeline/1");
        var stored = routes.retainPartition(index, new EngineMetrics(), ignored -> null, ignored -> null);
        for (var root : StoredRouteIndexes.Root.values()) Files.write(temporary.resolve(root.name() + ".route"), routes.root(stored, root));
        var sources = new StoredActiveSourceIndexes(bytes, LIMITS);
        var active = new ContractsActiveSourceTimelineIndex(List.of(id(1)));
        active.refresh(List.of(id(1)), ManagedOccurrenceInventory.empty(), ignored -> List.of("timeline/1"));
        active = sources.retainPartition(active, new EngineMetrics());
        for (var root : StoredActiveSourceIndexes.Root.values()) Files.write(temporary.resolve(root.name() + ".source"), sources.root(active, root));
        var classpath = new LinkedHashSet<String>();
        for (ClassLoader loader = getClass().getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) for (var url : urls.getURLs())
                if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
        }
        classpath.addAll(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        Path log = temporary.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                String.join(java.io.File.pathSeparator, classpath), Restart.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = child.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished)
        // when
        child.destroyForcibly();
        // then
        assertTrue(finished, "owned child did not complete");
        assertEquals(0, child.exitValue(), () -> { try { return Files.readString(log); } catch (Exception failure) { return failure.toString(); } });
        assertTrue(Files.readString(log).contains("COLD_ROUTES_OK"));
    }

    public static final class Restart {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]); var bytes = new FileCoordinationObjectStore(directory.resolve("objects"), LIMITS.nodeBytes());
            var routes = new StoredRouteIndexes(bytes, LIMITS); var roots = new EnumMap<StoredRouteIndexes.Root, byte[]>(StoredRouteIndexes.Root.class);
            for (var root : StoredRouteIndexes.Root.values()) roots.put(root, Files.readAllBytes(directory.resolve(root.name() + ".route")));
            var index = routes.open(roots::get, 1L, new EngineMetrics(), ignored -> { throw new AssertionError("session replay"); }, ignored -> null);
            if (!index.route(entry("timeline/1")).equals(List.of(id(1))) || index.generation() != 1L) throw new AssertionError("cold routes differ");
            replace(index, 1, "timeline/new");
            if (!index.route(entry("timeline/new")).equals(List.of(id(1))) || index.generation() != 2L) throw new AssertionError("cold mutation differs");
            var sources = new StoredActiveSourceIndexes(bytes, LIMITS); var sr = new EnumMap<StoredActiveSourceIndexes.Root, byte[]>(StoredActiveSourceIndexes.Root.class);
            for (var root : StoredActiveSourceIndexes.Root.values()) sr.put(root, Files.readAllBytes(directory.resolve(root.name() + ".source")));
            if (!sources.open(sr::get, new EngineMetrics()).timelineIds().equals(Set.of("timeline/1"))) throw new AssertionError("cold source union differs");
            System.out.println("COLD_ROUTES_OK");
        }
    }

    static DocumentId id(int index) { return DocumentId.of("document-" + index); }
    static OperationRouteIndex.Replacement replacement(int index, String timeline) {
        var surface = new RoutingSurface(List.of(new RoutingSurface.Definition("/", "increment", "owner", timeline, "alice")), false);
        var subscription = new SubscriptionDelta.Entry("/", "owner", "type", List.of("source"), 0,
                TimelineProviderSupport.exactScalarEventKeys(timeline, "alice"), "checkpoint", 0L,
                ExternalOrderKey.of(List.of(java.math.BigInteger.ZERO, "admission\ud800")), null);
        return new OperationRouteIndex.Replacement(id(index), surface, List.of(subscription));
    }
    static void replace(OperationRouteIndex index, int document, String timeline) { index.prepareReplacement(List.of(replacement(document, timeline))).publish(); }
    static TimelineEntry entry(String timeline) {
        var event = ExactValue.verified(new Node().value(timeline));
        var order = ExternalOrderKey.of(List.of(java.math.BigInteger.ONE.shiftLeft(70), timeline, event.blueId()));
        return new TimelineEntry(event, Optional.of(ExactValue.verified(new Node().value("request"))), order, order,
                new Timeline(timeline, "alice"), "increment", "owner", 1L, 1L, 1L);
    }
    static EnumMap<StoredRouteIndexes.Root, byte[]> roots(StoredRouteIndexes storage, OperationRouteIndex index) {
        var result = new EnumMap<StoredRouteIndexes.Root, byte[]>(StoredRouteIndexes.Root.class);
        for (var root : StoredRouteIndexes.Root.values()) result.put(root, storage.root(index, root)); return result;
    }
    static <K> void sameRoots(Map<K, byte[]> expected, Map<K, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet()); expected.forEach((key, value) -> assertArrayEquals(value, actual.get(key)));
    }
    static final class Bytes implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> values = new LinkedHashMap<>(); int reads, writes, failWriteAt = -1;
        @Override public byte[] putIfAbsent(String digest, byte[] bytes) {
            if (++writes == failWriteAt) throw new RuntimeException(new java.io.IOException("injected write failure"));
            values.putIfAbsent(digest, bytes.clone()); return values.get(digest).clone();
        }
        @Override public Optional<byte[]> get(String digest, int maximum) {
            reads++; byte[] bytes = values.get(digest);
            if (bytes != null && bytes.length > maximum) throw new IllegalStateException("oversized fixture row");
            return Optional.ofNullable(bytes == null ? null : bytes.clone());
        }
        Bytes fresh() { var result = new Bytes(); values.forEach((key, value) -> result.values.put(key, value.clone())); return result; }
    }
}
