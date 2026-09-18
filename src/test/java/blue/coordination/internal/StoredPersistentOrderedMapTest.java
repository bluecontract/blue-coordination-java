package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.NoncommittingExecutionException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class StoredPersistentOrderedMapTest {
    @TempDir Path temporary;

    @Test void controlledOwnerReusesSmallAuthenticatedNodesButNewOwnersAndRawStoresReadAgain() {
        // given
        try (var cache = new RootedStorageCache(4 * 1024 * 1024, 100, 1024 * 1024)) {
        var rows = new Rows(); var map = StoredMapFixtures.open(rows, null);
        for (int n = 0; n < 40; n++) map = map.put(n, "value-" + n).map();
        byte[] root = map.storedRootDescriptor();
        var controlled = RootedEngineStorage.controlledNamespace(rows, cache);
        // when
        var selected = StoredMapFixtures.open(controlled, root);
        // then
        assertEquals("value-21", selected.get(21)); int cold = rows.reads;
        for (int n = 0; n < 100; n++) assertEquals("value-21", selected.get(21));
        assertEquals(cold, rows.reads, "Repeated reads reuse this owner's authenticated structural frames");
        var reopened = StoredMapFixtures.open(controlled, root);
        assertEquals("value-21", reopened.get(21)); assertTrue(rows.reads > cold);
        var strict = StoredMapFixtures.open(rows, root); int before = rows.reads;
        strict.get(21); strict.get(21); assertTrue(rows.reads > before);
        assertEquals("changed", selected.put(21, "changed").map().get(21));
        assertEquals("value-21", selected.get(21), "Old immutable root remains exact after path copying");
        int beforeClear = rows.reads; cache.clear(); selected.get(21);
        assertTrue(rows.reads > beforeClear, "Process cache clear also invalidates non-owning node handles");
        rows.bytes.clear();
        assertThrows(CoordinationObjectStorageException.class, () -> StoredMapFixtures.open(controlled, root));
        assertThrows(CoordinationObjectStorageException.class, () -> strict.get(21));
        }
    }

    @Test void preparedEncodingKeepsGenericPreparationAndCanonicalValidationOrder() {
        // given
        var calls = new ArrayList<String>();
        var keys = new PersistentMapCodec<Integer>() {
            public String identity() { return StoredMapFixtures.INTS.identity(); }
            public Integer prepareForStorage(Integer value) { calls.add("prepare-key"); return value; }
            public byte[] encode(Integer value) { calls.add("encode-key"); return StoredMapFixtures.INTS.encode(value); }
            public Integer decode(byte[] bytes) { calls.add("decode-key"); return StoredMapFixtures.INTS.decode(bytes); }
        };
        var values = new PersistentMapCodec<String>() {
            public String identity() { return StoredMapFixtures.TEXT.identity(); }
            public String prepareForStorage(String value) { calls.add("prepare-value"); return value; }
            public byte[] encode(String value) { calls.add("encode-value"); return StoredMapFixtures.TEXT.encode(value); }
            public String decode(byte[] bytes) { calls.add("decode-value"); return StoredMapFixtures.TEXT.decode(bytes); }
        };
        var rows = new Rows();
        var map = PersistentOrderedMap.stored(Comparator.naturalOrder(), StoredMapFixtures.ORDER,
                keys, values, rows, StoredMapFixtures.LIMITS, null);
        // when
        var changed = map.put(7, "seven").map();
        // then
        assertEquals(List.of("prepare-key", "prepare-value", "encode-key", "decode-key", "encode-key",
                "encode-value", "decode-value", "encode-value"), calls);
        assertEquals(1, rows.writes);
        assertEquals("seven", changed.get(7));
        assertNull(map.get(7));
    }

    @Test void oneShotEncodingOwnsBytesAndStillEnforcesBoundsAndCanonicalDecoding() {
        // given
        byte[] supplied = StoredMapFixtures.TEXT.encode("owned");
        var prepared = PersistentMapCodec.PreparedEncoding.<String>encoded(supplied);
        supplied[0] = 'x';
        // when
        byte[] consumed = prepared.consume(StoredMapFixtures.TEXT);
        // then
        assertArrayEquals(StoredMapFixtures.TEXT.encode("owned"), consumed);
        consumed[0] = 'y';
        assertThrows(IllegalStateException.class, () -> prepared.consume(StoredMapFixtures.TEXT));

        var rows = new Rows(); int[] mode = {0}, decodes = {0};
        var codec = new PersistentMapCodec<String>() {
            public String identity() { return StoredMapFixtures.TEXT.identity(); }
            public PreparedEncoding<String> prepareEncoding(String value) {
                byte[] bytes = switch (mode[0]) {
                    case 1 -> new byte[]{(byte) 0xc3, 0x28}; // Invalid UTF-8 cannot round-trip canonically.
                    case 2 -> new byte[StoredMapFixtures.LIMITS.valueBytes() + 1];
                    default -> StoredMapFixtures.TEXT.encode(value);
                };
                return PreparedEncoding.encoded(bytes);
            }
            public byte[] encode(String value) { return StoredMapFixtures.TEXT.encode(value); }
            public String decode(byte[] bytes) { decodes[0]++; return StoredMapFixtures.TEXT.decode(bytes); }
        };
        var map = PersistentOrderedMap.stored(Comparator.naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, codec, rows, StoredMapFixtures.LIMITS, null);
        var valid = map.put(1, "one").map(); byte[] original = valid.storedRootDescriptor();
        int writes = rows.writes;
        mode[0] = 1; assertPhysical(() -> valid.put(2, "two"));
        assertEquals(writes, rows.writes, "A supplied frame does not bypass canonical checks");
        int before = decodes[0]; mode[0] = 2;
        assertPhysical(() -> map.put(2, "two"));
        assertEquals(before, decodes[0], "Reject the prepared frame's size before decoding it");
        assertEquals(writes, rows.writes); assertArrayEquals(original, valid.storedRootDescriptor());
        mode[0] = 0;
        assertEquals("two", valid.put(2, "two").map().get(2));
    }

    @Test void oneShotPreparationCapturesASnapshotWithoutCertifyingLaterMutableValues() {
        // given
        var mutable = new StringBuilder("captured");
        var codec = new PersistentMapCodec<StringBuilder>() {
            public String identity() { return "test/mutable-snapshot/1"; }
            public PreparedEncoding<StringBuilder> prepareEncoding(StringBuilder value) {
                var encoded = PreparedEncoding.<StringBuilder>encoded(encode(value));
                value.append("-later"); // Mutation after capture is outside this one snapshot.
                return encoded;
            }
            public byte[] encode(StringBuilder value) { return StoredMapFixtures.TEXT.encode(value.toString()); }
            public StringBuilder decode(byte[] bytes) { return new StringBuilder(StoredMapFixtures.TEXT.decode(bytes)); }
        };
        var rows = new Rows();
        var empty = PersistentOrderedMap.stored(Comparator.naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, codec, rows, StoredMapFixtures.LIMITS, null);
        // when
        var stored = empty.put(1, mutable).map();
        // then
        assertEquals("captured-later", mutable.toString());
        assertEquals("captured", stored.get(1).toString());
        assertArrayEquals(StoredMapFixtures.TEXT.encode("captured-later"), codec.encode(mutable));
        var cold = PersistentOrderedMap.stored(Comparator.naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, codec, rows.fresh(), StoredMapFixtures.LIMITS, stored.storedRootDescriptor());
        assertEquals("captured", cold.get(1).toString());
        assertNull(empty.get(1));
    }

    @Test void metadataMembershipPreservesKeyChecksAndAbsenceWithoutProjectingValues() {
        // given
        Rows rows = new Rows(); var source = StoredMapFixtures.open(rows, null);
        for (int key = 0; key < 31; key++) source = source.put(key, "v" + key).map();
        var original = source.storedRootDescriptor();
        var mapped = new ArrayList<Integer>(); var absent = new ArrayList<Integer>();
        var coldRows = rows.fresh();
        var projection = StoredMapFixtures.open(coldRows, original).projectValues((key, value) -> {
            mapped.add(key); return new StringBuilder(value);
        }, absent::add);
        var working = projection.open();
        // when
        int writes = coldRows.writes;
        // then
        assertTrue(working.containsKeyWithoutValue(5));
        assertFalse(working.containsKeyWithoutValue(-1));
        assertEquals(List.of(-1), absent); assertTrue(mapped.isEmpty());
        assertEquals(writes, coldRows.writes);
        assertTrue(working.containsKey(5)); assertEquals(List.of(5), mapped,
                "Existing value-validating containsKey remains unchanged");
        var removed = working.remove(5).map(); absent.clear();
        assertFalse(removed.containsKeyWithoutValue(5)); assertTrue(absent.isEmpty(),
                "A local removal must not report corrupt retained absence");
        coldRows.bytes.values().forEach(bytes -> bytes[0] ^= 1);
        assertPhysical(() -> working.containsKeyWithoutValue(5));
        assertPhysical(() -> working.containsKeyWithoutValue(-1));
    }

    @Test void lazyWorkingProjectionPreservesMutableValuesWithoutOpeningOrWritingUnselectedRows() {
        // given
        Rows rows = new Rows(); var source = StoredMapFixtures.open(rows, null);
        for (int key = 0; key < 127; key++) source = source.put(key, "v" + key).map();
        byte[] original = source.storedRootDescriptor();
        var mapped = new ArrayList<Integer>(); var retained = new ArrayList<Integer>();
        var projection = source.projectValues((key, value) -> { mapped.add(key); return new StringBuilder(value); });
        int reads = rows.reads, writes = rows.writes;
        // when
        var working = projection.open();
        // then
        assertEquals(reads, rows.reads); assertEquals(writes, rows.writes); assertTrue(mapped.isEmpty());
        assertEquals("v5", working.get(5).toString()); assertEquals(List.of(5), mapped); mapped.clear();
        var value = new StringBuilder("interim");
        working = working.put(128, value).map().remove(63).map().put(5, new StringBuilder("five")).map();
        assertTrue(mapped.isEmpty(), "Path copies and rotations must not hydrate unchanged values");
        assertEquals(writes, rows.writes, "Working mutations never encode incomplete session rows");
        assertSame(value, working.get(128)); value.replace(0, value.length(), "complete");
        var staged = projection.stage(working, (key, selected) -> { retained.add(key); return selected.toString(); });
        assertEquals(java.util.Set.of(5, 128), new java.util.HashSet<>(retained)); assertEquals(2, retained.size());
        assertTrue(mapped.isEmpty(), "Final staging reuses original physical values for unchanged rows");
        assertEquals("complete", staged.get(128)); assertEquals("five", staged.get(5)); assertNull(staged.get(63));
        var coldOriginal = StoredMapFixtures.open(rows.fresh(), original);
        assertNull(coldOriginal.get(128)); assertEquals("v5", coldOriginal.get(5)); assertEquals("v63", coldOriginal.get(63));
        assertTrue(rows.writes - writes < 30, "Stage only changed AVL paths, not the original 127 rows");
        assertArrayEquals(original, projection.stage(projection.open(), (key, selected) -> { throw new AssertionError("Unchanged row retained"); }).storedRootDescriptor());
    }

    @Test void lazyWorkingProjectionMatchesResidentAvlMetricsAcrossRotationsRemovalsAndColdStaging() {
        // given
        Rows rows = new Rows(); var source = StoredMapFixtures.open(rows, null);
        var resident = PersistentOrderedMap.<Integer, String>empty(Comparator.naturalOrder());
        for (int key = 0; key < 100; key++) { source = source.put(key, "v" + key).map(); resident = resident.put(key, "v" + key).map(); }
        var projection = source.projectValues((key, value) -> value); var working = projection.open();
        Random random = new Random(731L); int writes = rows.writes;
        for (int i = 0; i < 180; i++) {
            int key = random.nextInt(150); boolean remove = random.nextBoolean();
            var expected = remove ? resident.remove(key) : resident.put(key, "new" + i);
            // when
            var actual = remove ? working.remove(key) : working.put(key, "new" + i);
            // then
            assertEquals(expected.changed(), actual.changed()); assertEquals(expected.metrics(), actual.metrics());
            resident = expected.map(); working = actual.map();
            assertEquals(writes, rows.writes); assertEquals(resident.entries(), working.entries());
            assertEquals(resident.heightForTesting(), working.heightForTesting()); working.assertStructurallyValid();
        }
        var staged = projection.stage(working, (key, value) -> value);
        assertEquals(resident.entries(), StoredMapFixtures.open(rows.fresh(), staged.storedRootDescriptor()).entries());
        assertEquals(resident.heightForTesting(), staged.heightForTesting());
        var other = source.projectValues((key, value) -> value);
        var finalWorking = working;
        assertThrows(IllegalArgumentException.class, () -> other.stage(finalWorking, (key, value) -> value));
    }

    @Test void failedFinalProjectionStagingLeavesOriginalRootAndWorkingObjectsAvailableForRetry() {
        // given
        Rows rows = new Rows(); var source = StoredMapFixtures.open(rows, null);
        for (int key = 0; key < 31; key++) source = source.put(key, "v" + key).map();
        byte[] original = source.storedRootDescriptor();
        var projection = source.projectValues((key, value) -> new StringBuilder(value));
        var value = new StringBuilder("first"); var working = projection.open().put(9, value).map().put(32, new StringBuilder("extra")).map();
        // when
        rows.failAfterWrites = rows.writes + 2;
        // then
        assertPhysical(() -> projection.stage(working, (key, item) -> item.toString()));
        assertSame(value, working.get(9)); value.append("-final");
        rows.failAfterWrites = Integer.MAX_VALUE;
        var staged = projection.stage(working, (key, item) -> item.toString());
        assertEquals("first-final", staged.get(9)); assertEquals("extra", staged.get(32));
        assertEquals("v9", StoredMapFixtures.open(rows.fresh(), original).get(9));
        rows.unavailable = true;
        assertPhysical(() -> working.get(1));
        rows.unavailable = false;
        assertSame(value, working.get(9), "A selected replacement remains the same actual object");
    }

    @Test void coldRandomizedMutationsPreserveTheExistingAvlAlgorithmAndMetrics() {
        // given
        Rows rows = new Rows();
        var stored = StoredMapFixtures.open(rows, null);
        PersistentOrderedMap<Integer, String> resident = PersistentOrderedMap.empty(Comparator.naturalOrder());
        Random random = new Random(0x51A7E);
        for (int index = 0; index < 350; index++) {
            stored = StoredMapFixtures.open(rows.fresh(), stored.storedRootDescriptor());
            int key = random.nextInt(100);
            boolean remove = random.nextInt(3) == 0;
            var actual = remove ? stored.remove(key) : stored.put(key, "v" + index);
            // when
            var expected = remove ? resident.remove(key) : resident.put(key, "v" + index);
            // then
            assertEquals(expected.changed(), actual.changed());
            assertEquals(expected.metrics(), actual.metrics(), "same AVL comparisons/copies");
            assertEquals(resident.entries(), stored.entries(), "prior immutable root");
            stored = actual.map();
            resident = expected.map();
            assertEquals(resident.entries(), stored.entries());
            stored.assertStructurallyValid();
        }
    }

    @Test void residentShapeColdRangeBoundariesAndIndependentDescriptorsRemainExact() {
        // given
        Rows rows = new Rows();
        var resident = PersistentOrderedMap.<Integer, String>empty(Comparator.naturalOrder());
        for (int key : List.of(40, 10, 70, 5, 30, 60, 90, 20, 35)) resident = resident.put(key, "v" + key).map();
        var stored = resident.storedCopy(StoredMapFixtures.ORDER, StoredMapFixtures.INTS,
                StoredMapFixtures.TEXT, rows, StoredMapFixtures.LIMITS);
        byte[] descriptor = stored.storedRootDescriptor();
        // when
        var cold = StoredMapFixtures.open(rows.fresh(), descriptor);
        // then
        assertEquals(resident.heightForTesting(), cold.heightForTesting());
        assertEquals(resident.entries(), cold.entries());
        for (int key = 0; key < 100; key++) assertEquals(resident.read(key), cold.read(key));
        for (Integer lower : Arrays.asList(null, 0, 10, 20, 21, 90, 100)) {
            for (Integer upper : Arrays.asList(null, 0, 10, 20, 21, 90, 100)) {
                if (lower != null && upper != null && lower > upper) {
                    assertThrows(IllegalArgumentException.class, () -> cold.range(lower, upper));
                    continue;
                }
                List<Map.Entry<Integer, String>> expected = resident.entries().stream()
                        .filter(row -> (lower == null || row.getKey() >= lower) && (upper == null || row.getKey() < upper)).toList();
                List<Map.Entry<Integer, String>> actual = new ArrayList<>();
                cold.range(lower, upper).forEachRemaining(actual::add);
                assertEquals(expected, actual);
            }
        }
        // Independent immutable descriptors are not a mutable directory or a publication conflict protocol.
        var unrelated = StoredMapFixtures.open(rows, null).put(999, "other").map();
        byte[] unrelatedDescriptor = unrelated.storedRootDescriptor();
        var changed = cold.put(30, "changed");
        assertEquals(resident.put(30, "changed").metrics(), changed.metrics());
        assertEquals("v30", StoredMapFixtures.open(rows.fresh(), descriptor).get(30));
        assertEquals("changed", changed.map().get(30));
        assertArrayEquals(unrelatedDescriptor, unrelated.storedRootDescriptor());
        assertEquals(List.of(Map.entry(999, "other")), StoredMapFixtures.open(rows.fresh(), unrelatedDescriptor).entries());
    }

    @Test void physicalCacheMissesChangeNeitherLogicalComparisonsNorCopiedNodes() {
        // given
        Rows rows = new Rows();
        var stored = StoredMapFixtures.open(rows, null);
        var resident = PersistentOrderedMap.<Integer, String>empty(Comparator.naturalOrder());
        for (int key = 0; key < 63; key++) {
            stored = stored.put(key, "v" + key).map(); resident = resident.put(key, "v" + key).map();
        }
        byte[] descriptor = stored.storedRootDescriptor();
        for (int cachedNodes : List.of(1, 2, 64)) {
            var limits = new PersistentMapStorage.Limits(4096, 16, 2048, 1024, cachedNodes);
            Rows coldRows = rows.fresh();
            // when
            var cold = PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                    StoredMapFixtures.INTS, StoredMapFixtures.TEXT, coldRows, limits, descriptor);
            // then
            assertEquals(resident.read(2), cold.read(2));
            assertEquals(resident.read(-1), cold.read(-1));
            assertEquals(resident.put(2, "new").metrics(), cold.put(2, "new").metrics());
            assertEquals(resident.remove(31).metrics(), cold.remove(31).metrics());
            assertTrue(coldRows.reads > 0);
            int before = coldRows.reads;
            assertEquals(resident.read(2), cold.read(2));
            assertTrue(coldRows.reads > before, "An earlier operation never leaves an unbounded process cache");
        }
    }

    @Test void emptyAndLookupDescriptorsRejectDifferentCodecBindings() {
        // given
        Rows rows = new Rows();
        var empty = StoredMapFixtures.open(rows, null);
        // when
        byte[] descriptor = empty.storedRootDescriptor();
        // then
        assertEquals(0, rows.reads);
        assertTrue(StoredMapFixtures.open(rows, descriptor).isEmpty());
        assertEquals(0, rows.reads, "A bound empty descriptor has no physical node");
        var wrongText = new PersistentMapCodec<String>() {
            @Override public String identity() { return "test/different-text/1"; }
            @Override public byte[] encode(String value) { return StoredMapFixtures.TEXT.encode(value); }
            @Override public String decode(byte[] bytes) { return StoredMapFixtures.TEXT.decode(bytes); }
        };
        assertPhysical(() -> PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, wrongText, rows, StoredMapFixtures.LIMITS, descriptor));
        byte[] present = empty.put(1, "one").map().storedRootDescriptor();
        assertPhysical(() -> PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, wrongText, rows, StoredMapFixtures.LIMITS, present));
    }

    @Test void freshJvmContinuesFromFilesAndOriginalRootRemainsReadable() throws Exception {
        // given
        Path directory = temporary.resolve("objects");
        var map = StoredMapFixtures.open(new FileCoordinationObjectStore(directory, 4096), null);
        for (int key = 0; key < 127; key++) map = map.put(key, "value-" + key).map();
        byte[] original = map.storedRootDescriptor();
        Path before = temporary.resolve("before.root"), after = temporary.resolve("after.root");
        Files.write(before, original);
        String classpath = String.join(File.pathSeparator, List.of(
                location(StoredPersistentMapRestartMain.class), location(PersistentOrderedMap.class),
                location(NoncommittingExecutionException.class)));
        Path output = temporary.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, StoredPersistentMapRestartMain.class.getName(), directory.toString(),
                before.toString(), after.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = child.waitFor(30, TimeUnit.SECONDS);
        if (!finished)
        // when
        child.destroyForcibly();
        // then
        assertTrue(finished, "owned child did not complete");
        assertEquals(0, child.exitValue(), () -> readText(output));
        assertTrue(Files.readString(output).contains("COLD_MAP_OK"));
        var changed = StoredMapFixtures.open(new FileCoordinationObjectStore(directory, 4096), Files.readAllBytes(after));
        assertEquals("child-updated", changed.get(63));
        assertNull(changed.get(3));
        var old = StoredMapFixtures.open(new FileCoordinationObjectStore(directory, 4096), original);
        assertEquals("value-63", old.get(63));
        assertEquals("value-3", old.get(3));
        assertArrayEquals(original, Files.readAllBytes(before));
    }

    @Test void malformedUnvisitedCountCannotPreallocateExhaustiveOutputInSmallHeapJvm() throws Exception {
        // given
        Path directory = temporary.resolve("malformed-count-objects");
        var store = new FileCoordinationObjectStore(directory, 4096);
        byte[] childBytes = node(1, "one", null, 0, 0, 1, 1);
        String childId = PersistentMapStorage.digest(childBytes);
        store.putIfAbsent(childId, childBytes);
        byte[] parent = node(2, "two", childId, 1, Integer.MAX_VALUE - 1, 2, Integer.MAX_VALUE);
        store.putIfAbsent(PersistentMapStorage.digest(parent), parent);
        Path descriptor = temporary.resolve("malformed.root");
        Files.write(descriptor, rootFor(new Rows(), parent, 2, Integer.MAX_VALUE));
        String classpath = String.join(File.pathSeparator, List.of(location(StoredPersistentMapRestartMain.class),
                location(PersistentOrderedMap.class), location(NoncommittingExecutionException.class)));
        Path output = temporary.resolve("malformed-child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-cp", classpath, StoredPersistentMapRestartMain.class.getName(), directory.toString(),
                descriptor.toString(), "unused", "malformed-size").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = child.waitFor(30, TimeUnit.SECONDS);
        if (!finished)
        // when
        child.destroyForcibly();
        // then
        assertTrue(finished, "owned small-heap child did not complete");
        assertEquals(0, child.exitValue(), () -> readText(output));
        assertTrue(Files.readString(output).contains("MALFORMED_COUNT_REJECTED"));
    }

    @Test void coldLookupMutationAndRangeReadOnlyTheirPathsAmongUnrelatedKeys() {
        // given
        for (int count : List.of(127, 4095)) {
            Rows rows = new Rows();
            var map = StoredMapFixtures.open(rows, null);
            for (int key = 0; key < count; key++) map = map.put(key, "v" + key).map();
            byte[] root = map.storedRootDescriptor();
            Rows coldRows = rows.fresh();
            // when
            var cold = StoredMapFixtures.open(coldRows, root);
            // then
            assertEquals(1, coldRows.reads, "open authenticates only root");
            coldRows.reads = 0;
            var read = cold.read(42);
            assertEquals("v42", read.value());
            assertEquals(read.comparisons(), coldRows.reads);
            assertTrue(coldRows.reads <= 2 * (32 - Integer.numberOfLeadingZeros(count)));
            coldRows.reads = 0;
            var changed = cold.put(42, "changed");
            assertTrue(coldRows.reads < 80, "path copying must not enumerate realm");
            assertEquals("v42", StoredMapFixtures.open(rows.fresh(), root).get(42));
            assertEquals("changed", changed.map().get(42));
            coldRows.reads = 0;
            List<Map.Entry<Integer, String>> range = new ArrayList<>();
            cold.range(40, 43).forEachRemaining(range::add);
            assertEquals(List.of(Map.entry(40, "v40"), Map.entry(41, "v41"), Map.entry(42, "v42")), range);
            assertTrue(coldRows.reads < 80, "three-row range is bounded by paths and output");
            coldRows.reads = 0;
            var absent = cold.remove(-1);
            assertSame(cold, absent.map());
            assertEquals(0, absent.copiedNodes());
            assertTrue(coldRows.reads < 32);
        }
    }

    @Test void missingTamperedCollidingAndUnavailableObjectsFailNoncommitting() {
        // given
        Rows rows = new Rows();
        var map = StoredMapFixtures.open(rows, null).put(1, "one").map();
        byte[] root = map.storedRootDescriptor();
        String rootId = rootId(root);
        byte[] valid = rows.bytes.get(rootId).clone();
        // when
        rows.bytes.remove(rootId);
        // then
        assertPhysical(() -> StoredMapFixtures.open(rows.fresh(), root));
        rows.bytes.put(rootId, valid.clone());
        rows.bytes.get(rootId)[0] ^= 1;
        assertPhysical(() -> StoredMapFixtures.open(rows.fresh(), root));
        rows.bytes.put(rootId, valid);
        rows.collision = true;
        assertPhysical(() -> StoredMapFixtures.open(rows, root).put(2, "two"));
        rows.collision = false;
        rows.unavailable = true;
        assertPhysical(() -> StoredMapFixtures.open(rows, root));
        rows.unavailable = false;
        assertEquals(List.of(Map.entry(1, "one")), StoredMapFixtures.open(rows.fresh(), root).entries());
    }

    @Test void structuralReadsAndAvlCopiesNeverDecodeUnselectedValues() {
        // given
        Rows rows = new Rows();
        List<String> decoded = new ArrayList<>();
        PersistentMapCodec<String> values = new PersistentMapCodec<>() {
            @Override public String identity() { return StoredMapFixtures.TEXT.identity(); }
            @Override public byte[] encode(String value) { return StoredMapFixtures.TEXT.encode(value); }
            @Override public String decode(byte[] bytes) {
                String value = StoredMapFixtures.TEXT.decode(bytes);
                decoded.add(value);
                return value;
            }
        };
        var stored = PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, values, rows, StoredMapFixtures.LIMITS, null);
        var resident = PersistentOrderedMap.<Integer, String>empty(Comparator.naturalOrder());
        for (int key = 0; key < 127; key++) {
            stored = stored.put(key, "v" + key).map();
            resident = resident.put(key, "v" + key).map();
        }
        decoded.clear();
        // when
        stored = PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, values, rows.fresh(), StoredMapFixtures.LIMITS, stored.storedRootDescriptor());
        // then
        assertTrue(decoded.isEmpty(), "opening the root must not interpret its value");
        assertEquals(resident.keys(), stored.keys());
        stored.assertStructurallyValid();
        assertNull(stored.get(-1));
        assertTrue(decoded.isEmpty(), "key/structural work is not a value audit");
        assertEquals("v42", stored.get(42));
        assertEquals(List.of("v42"), decoded, "exactly the selected value is decoded once");
        decoded.clear();
        assertEquals(resident.minimum(), stored.minimum());
        assertEquals(List.of("v0"), decoded);
        decoded.clear();
        assertEquals(resident.higherThan(65), stored.higherThan(65));
        assertEquals(List.of("v66"), decoded);

        // Include insertions/replacements, successor removal and both rotation
        // directions. Physical copy counts/comparisons must remain Main's.
        Random random = new Random(0x5A77);
        for (int step = 0; step < 180; step++) {
            int key = random.nextInt(160);
            boolean remove = step % 3 == 0;
            String replacement = "new-" + step;
            decoded.clear();
            var actual = remove ? stored.remove(key) : stored.put(key, replacement);
            var expected = remove ? resident.remove(key) : resident.put(key, replacement);
            assertEquals(expected.metrics(), actual.metrics());
            assertEquals(expected.changed(), actual.changed());
            assertEquals(remove ? List.of() : List.of(replacement), decoded,
                    "only a newly supplied value is validated; copied ancestors retain exact frames");
            stored = actual.map();
            resident = expected.map();
            assertEquals(resident.entries(), stored.entries());
        }
    }

    @Test void unselectedNoncanonicalValueIsNotBlessedByStructuralCopy() throws Exception {
        // given
        Rows rows = new Rows();
        byte[] noncanonical = node(10, "z", null, 0, 0, 1, 1);
        // Publish an adversarial, hash-consistent one-byte UTF-8 value frame.
        // Selected decode returns U+FFFD; re-encode must detect the mismatch.
        // when
        int valueOffset = -1;
        for (int offset = 0; offset < noncanonical.length; offset++) {
            if (noncanonical[offset] == (byte) 'z') {
                // then
                assertEquals(-1, valueOffset, "the fixture marker must identify only the value frame");
                valueOffset = offset;
            }
        }
        assertTrue(valueOffset >= 0);
        noncanonical[valueOffset] = (byte) 0xff;
        var map = StoredMapFixtures.open(rows, rootFor(rows, noncanonical, 1, 1));
        assertEquals(List.of(10), map.keys());
        assertNull(map.get(1));
        assertPhysical(() -> map.get(10));
        var changed = map.put(1, "one").map().put(0, "zero").map();
        assertEquals("one", changed.get(1));
        assertEquals("zero", changed.get(0));
        assertPhysical(() -> changed.get(10));
        assertPhysical(changed::entries);
        // A genuine replacement still checks new bytes before allocating a
        // replacement root; copying opaque bytes grants no codec authority.
        assertPhysical(() -> changed.put(10, "\uD800"));
        assertPhysical(() -> changed.get(10));
    }

    @Test void canonicalMalformedMetadataOrderingAndBindingChecksNeedNoWholeScan() throws Exception {
        // given
        Rows rows = new Rows();
        byte[] left = node(99, "wrong-left", null, 0, 0, 1, 1);
        String leftId = PersistentMapStorage.digest(left);
        rows.bytes.put(leftId, left);
        byte[] parent = node(10, "root", leftId, 1, 1, 2, 2);
        // when
        var map = StoredMapFixtures.open(rows, rootFor(rows, parent, 2, 2));
        // then
        assertEquals(1, rows.reads, "root validation must not walk children");
        assertPhysical(() -> map.get(0));

        byte[] invalidDimensions = node(1, "one", null, 0, 0, 3, 1);
        assertPhysical(() -> StoredMapFixtures.open(rows, rootFor(rows, invalidDimensions, 1, 1)));
        byte[] trailing = Arrays.copyOf(node(1, "one", null, 0, 0, 1, 1), 120);
        assertPhysical(() -> StoredMapFixtures.open(rows, rootFor(rows, trailing, 1, 1)));
        byte[] malformed = node(1, "one", null, 0, 0, 1, 1);
        ByteBuffer.wrap(malformed).putInt(4, Integer.MAX_VALUE);
        assertPhysical(() -> StoredMapFixtures.open(rows, rootFor(rows, malformed, 1, 1)));

        byte[] proper = StoredMapFixtures.open(rows, null).put(1, "one").map().storedRootDescriptor();
        assertPhysical(() -> PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), "different-order/1",
                StoredMapFixtures.INTS, StoredMapFixtures.TEXT, rows, StoredMapFixtures.LIMITS, proper));
        assertPhysical(() -> StoredMapFixtures.open(rows, null).put(1, "\uD800"));
    }

    @Test void mutableInputOutputBytesCannotChangeStoredAuthorityAndBoundsArePhysical() {
        // given
        Rows rows = new Rows();
        PersistentMapCodec<byte[]> bytesCodec = new PersistentMapCodec<>() {
            @Override public String identity() { return "test/bytes/1"; }
            @Override public byte[] encode(byte[] value) { return value.clone(); }
            @Override public byte[] decode(byte[] bytes) { return bytes.clone(); }
        };
        var map = PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, bytesCodec, rows, StoredMapFixtures.LIMITS, null);
        byte[] input = {1, 2, 3};
        map = map.put(1, input).map();
        input[0] = 99;
        byte[] returned = map.get(1);
        // when
        returned[1] = 99;
        // then
        assertArrayEquals(new byte[] {1, 2, 3}, map.get(1));
        var fixed = map;
        assertPhysical(() -> fixed.put(2, new byte[2049]));
        assertArrayEquals(new byte[] {1, 2, 3}, map.get(1));
        byte[] descriptor = map.storedRootDescriptor();
        descriptor[0] ^= 1;
        assertPhysical(() -> PersistentOrderedMap.stored(Comparator.<Integer>naturalOrder(), StoredMapFixtures.ORDER,
                StoredMapFixtures.INTS, bytesCodec, rows, StoredMapFixtures.LIMITS, descriptor));
    }

    @Test void unavailableSelectedChildCannotBecomeAnAbsentKeyAndUnvisitedChildIsNotOpened() throws Exception {
        // given
        Rows rows = new Rows();
        byte[] child = node(5, "five", null, 0, 0, 1, 1);
        String childId = PersistentMapStorage.digest(child);
        rows.bytes.put(childId, child);
        byte[] parent = node(10, "ten", childId, 1, 1, 2, 2);
        byte[] root = rootFor(rows, parent, 2, 2);
        rows.bytes.remove(childId);
        // when
        var cold = StoredMapFixtures.open(rows, root);
        // then
        assertEquals("ten", cold.get(10), "root hit does not open unrelated descendants");
        assertNull(cold.get(11), "authenticated absent branch is ordinary absence");
        assertPhysical(() -> cold.get(5));
        assertPhysical(() -> cold.get(4));
        rows.bytes.put(childId, child);
        assertEquals("five", StoredMapFixtures.open(rows.fresh(), root).get(5));
    }

    @Test void maliciousRootBalanceAndOversizedHostResultsAreRejected() throws Exception {
        // given
        Rows rows = new Rows();
        byte[] leaf = node(1, "one", null, 0, 0, 1, 1);
        String leafId = PersistentMapStorage.digest(leaf);
        rows.bytes.put(leafId, leaf);
        byte[] branch = node(2, "two", leafId, 1, 1, 2, 2);
        String branchId = PersistentMapStorage.digest(branch);
        rows.bytes.put(branchId, branch);
        // when
        byte[] unbalanced = node(3, "three", branchId, 2, 2, 3, 3);
        // then
        assertPhysical(() -> StoredMapFixtures.open(rows, rootFor(rows, unbalanced, 3, 3)));

        byte[] proper = StoredMapFixtures.open(rows, null).put(1, "one").map().storedRootDescriptor();
        CoordinationImmutableObjectStore oversized = new CoordinationImmutableObjectStore() {
            @Override public byte[] putIfAbsent(String digest, byte[] bytes) { return new byte[4097]; }
            @Override public Optional<byte[]> get(String digest, int maximumBytes) {
                assertEquals(4096, maximumBytes, "host receives preallocation cap");
                return Optional.of(new byte[maximumBytes + 1]);
            }
        };
        assertPhysical(() -> StoredMapFixtures.open(oversized, proper));
        assertPhysical(() -> StoredMapFixtures.open(oversized, null).put(1, "one"));
        byte[] extra = Arrays.copyOf(proper, proper.length + 1);
        assertPhysical(() -> StoredMapFixtures.open(rows, extra));
    }

    @Test void failedWriteAfterSomePathCopiesLeavesOriginalRootAuthoritative() {
        // given
        Rows rows = new Rows();
        var map = StoredMapFixtures.open(rows, null);
        for (int key = 0; key < 31; key++) map = map.put(key, "v" + key).map();
        byte[] original = map.storedRootDescriptor();
        List<Map.Entry<Integer, String>> expected = map.entries();
        rows.failAfterWrites = rows.writes + 2;
        // when
        var fixed = map;
        // then
        assertPhysical(() -> fixed.put(0, "tentative"));
        rows.failAfterWrites = Integer.MAX_VALUE;
        assertArrayEquals(original, map.storedRootDescriptor());
        assertEquals(expected, StoredMapFixtures.open(rows.fresh(), original).entries());
    }

    @Test void rangeFailureDoesNotAdvanceAndExhaustionMatchesResidentContract() {
        // given
        Rows rows = new Rows();
        var map = StoredMapFixtures.open(rows, null);
        for (int key = 1; key <= 3; key++) map = map.put(key, "v" + key).map();
        var range = StoredMapFixtures.open(rows, map.storedRootDescriptor()).range(null, 4);
        // First read is hasNext(); fail after it, while next() is constructing
        // the result and the prospective traversal stack.
        // when
        rows.failReadAt = rows.reads + 2;
        // then
        assertPhysical(range::next);
        rows.failReadAt = Integer.MAX_VALUE;
        assertEquals(Map.entry(1, "v1"), range.next(), "failed read must not consume the row");
        assertEquals(Map.entry(2, "v2"), range.next());
        assertEquals(Map.entry(3, "v3"), range.next());
        assertFalse(range.hasNext());
        assertThrows(NoSuchElementException.class, range::next);
        var resident = PersistentOrderedMap.<Integer, String>empty(Comparator.naturalOrder()).range(null, null);
        assertThrows(NoSuchElementException.class, resident::next);
    }

    private static void assertPhysical(org.junit.jupiter.api.function.Executable operation) {
        assertInstanceOf(NoncommittingExecutionException.class,
                assertThrows(CoordinationObjectStorageException.class, operation));
    }
    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static String readText(Path file) {
        try { return Files.readString(file); } catch (Exception failure) { return failure.toString(); }
    }
    private static String rootId(byte[] descriptor) {
        return HexFormat.of().formatHex(Arrays.copyOfRange(descriptor, descriptor.length - 40, descriptor.length - 8));
    }
    private static byte[] rootFor(Rows rows, byte[] node, int height, int size) throws Exception {
        String digest = PersistentMapStorage.digest(node);
        rows.bytes.put(digest, node.clone());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x504d5231); binding(out); out.writeByte(1);
            out.write(HexFormat.of().parseHex(digest)); out.writeInt(height); out.writeInt(size);
        }
        return bytes.toByteArray();
    }
    private static byte[] node(int key, String value, String left, int leftHeight, int leftSize,
            int height, int size) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x504d4e31); binding(out);
            field(out, StoredMapFixtures.INTS.encode(key)); field(out, StoredMapFixtures.TEXT.encode(value));
            out.writeByte(left == null ? 0 : 1);
            if (left != null) { out.write(HexFormat.of().parseHex(left)); out.writeInt(leftHeight); out.writeInt(leftSize); }
            out.writeByte(0); out.writeInt(height); out.writeInt(size);
        }
        return bytes.toByteArray();
    }
    private static void binding(DataOutputStream out) throws Exception {
        for (String text : List.of(StoredMapFixtures.ORDER, StoredMapFixtures.INTS.identity(), StoredMapFixtures.TEXT.identity())) {
            field(out, text.getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void field(DataOutputStream out, byte[] bytes) throws Exception {
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static final class Rows implements CoordinationImmutableObjectStore {
        final Map<String, byte[]> bytes;
        int reads, writes;
        int failAfterWrites = Integer.MAX_VALUE;
        int failReadAt = Integer.MAX_VALUE;
        boolean collision, unavailable;
        Rows() { this(new LinkedHashMap<>()); }
        Rows(Map<String, byte[]> bytes) { this.bytes = bytes; }
        Rows fresh() { return new Rows(bytes); }
        @Override public byte[] putIfAbsent(String digest, byte[] input) {
            if (++writes > failAfterWrites) throw new IllegalStateException("physical write fault");
            if (collision) return new byte[] {9};
            byte[] previous = bytes.putIfAbsent(digest, input.clone());
            if (previous != null && !Arrays.equals(previous, input)) throw new IllegalStateException("collision");
            return bytes.get(digest).clone();
        }
        @Override public Optional<byte[]> get(String digest, int maximum) {
            reads++;
            if (reads == failReadAt) throw new IllegalStateException("transient selected node read fault");
            if (unavailable) throw new IllegalStateException("physical read fault");
            byte[] value = bytes.get(digest);
            if (value != null && value.length > maximum) throw new IllegalStateException("physical byte cap");
            return Optional.ofNullable(value == null ? null : value.clone());
        }
    }
}
