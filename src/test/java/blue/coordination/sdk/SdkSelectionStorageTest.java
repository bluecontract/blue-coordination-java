package blue.coordination.sdk;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.InsertionOrderedStorage;
import blue.language.processor.NoncommittingExecutionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Closed descriptor transport controls only; dummy engine bytes are never installed as an engine. */
final class SdkSelectionStorageTest {
    private static final int MAX = 256 * 1024;
    private static final SdkRuntimePointMaps.Limits LIMITS = new SdkRuntimePointMaps.Limits(
            new InsertionOrderedStorage.Limits(64 * 1024, 16 * 1024, 16 * 1024, 4096, 8, MAX, MAX, 32),
            new SdkPointStorage.Limits(MAX, 64 * 1024, MAX, 32), MAX);

    @Test void allFiveFamiliesAndExactConfigurationRoundTripWithoutReadingBodies() {
        try (var blue = BlueCoordination.inMemory()) {
            var objects = new Bytes();
            try (var maps = SdkRuntimePointMaps.empty(blue.runtimeForStorage(), objects, LIMITS)) {
                var codec = new SdkSelectionStorage(MAX);
                var parts = new SdkSelectionStorage.Parts(blue.runtimeForStorage().storageConfiguration(),
                        maps.snapshot(), Map.of("control", new byte[] { 4, 5 }, "documents/rows", new byte[] { 6 }));
                var selected = codec.encode(parts);
                var decoded = codec.decode(selected);
                assertEquals(parts.configuration(), decoded.configuration());
                assertEquals(8, selected.size());
                assertEquals(List.of("sdk/configuration", "sdk/TIMELINES", "sdk/INTENTS", "sdk/RESULTS",
                        "sdk/ENTRIES", "sdk/SOURCE_RESULTS", "engine/control", "engine/documents/rows"),
                        new ArrayList<>(selected.keySet()));
                same(selected, codec.encode(decoded));
                assertEquals(0, objects.reads); assertEquals(0, objects.writes);
            }
        }
    }

    @Test void missingForeignSwappedOrCorruptFamilyFramesNeverBecomeEmptyMaps() {
        withSelection((codec, selected) -> {
            for (String missing : selected.keySet()) {
                var copy = new LinkedHashMap<>(selected); copy.remove(missing);
                assertThrows(NoncommittingExecutionException.class, () -> codec.decode(copy));
            }
            var foreign = new LinkedHashMap<>(selected); foreign.put("sdk/unknown", new byte[] { 1 });
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(foreign));
            var swapped = new LinkedHashMap<>(selected); swapped.put("sdk/RESULTS", selected.get("sdk/INTENTS"));
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(swapped));
            var corrupt = new LinkedHashMap<>(selected); var bytes = selected.get("sdk/RESULTS").clone();
            bytes[bytes.length - 1] ^= 1; corrupt.put("sdk/RESULTS", bytes);
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(corrupt));
            var emptyEngineName = new LinkedHashMap<>(selected); emptyEngineName.put("engine/", new byte[] { 1 });
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(emptyEngineName));
        });
    }

    @Test void rechecksummedNegativeSequenceAndForeignConfigurationFailClosed() {
        withSelection((codec, selected) -> {
            var transport = new SdkStorageCodec(new Object(), MAX);
            List<?> decoded = transport.decode(selected.get("sdk/RESULTS"), List.class);
            var fields = new ArrayList<Object>(decoded);
            fields.set(5, -1L);
            var negative = new LinkedHashMap<>(selected); negative.put("sdk/RESULTS", transport.encode(fields));
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(negative));
            fields.set(5, 0L);
            var original = (SdkStorageCodec.Configuration) fields.get(2);
            fields.set(2, new SdkStorageCodec.Configuration(original.language(), original.contracts(), original.bundledRelease(),
                    !original.contentDerivedDocumentIds(), original.policy(), original.bundledIdentities()));
            var foreign = new LinkedHashMap<>(selected); foreign.put("sdk/RESULTS", transport.encode(fields));
            assertThrows(NoncommittingExecutionException.class, () -> codec.decode(foreign));
        });
    }

    @Test void selectedAndDecodedDescriptorsOwnEveryByteArray() {
        withSelection((codec, selected) -> {
            var original = new LinkedHashMap<String, byte[]>(); selected.forEach((k, v) -> original.put(k, v.clone()));
            var snapshot = new RootedCoordinationStorage.Selection(original);
            original.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0)); original.clear();
            same(selected, snapshot.slots());
            var detached = snapshot.slots(); detached.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            same(selected, snapshot.slots());
            assertThrows(UnsupportedOperationException.class, () -> detached.put("foreign", new byte[0]));
            var decoded = codec.decode(snapshot.slots()); var engine = decoded.engine();
            engine.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            same(selected, codec.encode(decoded));
        });
    }

    @Test void eachDescriptorIsBoundedAndNoApplicationDefaultsAreInvented() {
        withSelection((codec, selected) -> {
            assertThrows(NoncommittingExecutionException.class, () -> new SdkSelectionStorage(128).decode(selected));
            assertThrows(NoncommittingExecutionException.class, () -> new SdkSelectionStorage(128).encode(codec.decode(selected)));
        });
        var maps = LIMITS.maps();
        assertThrows(IllegalArgumentException.class, () -> new RootedCoordinationStorage.SdkLimits(maps, 0, 128, 0, 0, MAX));
        assertThrows(IllegalArgumentException.class, () -> new RootedCoordinationStorage.SdkLimits(maps, MAX, 128, -1, 0, MAX));
    }

    private static void withSelection(java.util.function.BiConsumer<SdkSelectionStorage, Map<String, byte[]>> action) {
        try (var blue = BlueCoordination.inMemory(); var maps = SdkRuntimePointMaps.empty(blue.runtimeForStorage(), new Bytes(), LIMITS)) {
            var codec = new SdkSelectionStorage(MAX);
            action.accept(codec, codec.encode(new SdkSelectionStorage.Parts(blue.runtimeForStorage().storageConfiguration(),
                    maps.snapshot(), Map.of("control", new byte[] { 1, 2, 3 }))));
        }
    }
    private static void same(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet()); expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }
    private static final class Bytes implements CoordinationImmutableObjectStore {
        private int reads, writes;
        @Override public byte[] putIfAbsent(String address, byte[] bytes) {
            writes++; throw new CoordinationObjectStorageException("Unexpected descriptor-only write");
        }
        @Override public Optional<byte[]> get(String address, int maximumBytes) {
            reads++; throw new CoordinationObjectStorageException("Unexpected descriptor-only read");
        }
    }
}
