package blue.coordination.sdk;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.InsertionOrderedStorage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Closed named descriptor transport, not SDK semantic authority or a publication root. */
final class SdkSelectionStorage {
    private static final String FORMAT = "blue-coordination/rooted-sdk-selection/1";
    private static final String CONFIGURATION = "sdk/configuration";
    private static final String ENGINE = "engine/";
    private final SdkStorageCodec codec;

    SdkSelectionStorage(int maximumBytes) { codec = new SdkStorageCodec(new Object(), maximumBytes); }

    record Parts(SdkStorageCodec.Configuration configuration, SdkRuntimePointMaps.Snapshot maps,
            Map<String, byte[]> engine) {
        Parts { Objects.requireNonNull(configuration); Objects.requireNonNull(maps); engine = copy(engine); }
        @Override public Map<String, byte[]> engine() { return copy(engine); }
    }

    Map<String, byte[]> encode(Parts parts) {
        var slots = new LinkedHashMap<String, byte[]>();
        slots.put(CONFIGURATION, codec.encode(List.of(FORMAT, parts.configuration())));
        for (var kind : SdkRuntimePointMaps.Kind.values()) {
            var root = parts.maps().roots().get(kind);
            slots.put(slot(kind), codec.encode(List.of(FORMAT, kind.name(), parts.configuration(),
                    root.keys(), root.order(), root.nextSequence())));
        }
        new TreeMap<>(parts.engine()).forEach((name, bytes) -> slots.put(ENGINE + name, bytes));
        return copy(slots);
    }

    Parts decode(Map<String, byte[]> selected) {
        var slots = copy(selected);
        var configurationFields = fields(required(slots, CONFIGURATION));
        require(configurationFields.size() == 2 && FORMAT.equals(configurationFields.get(0)),
                "Invalid SDK selection configuration frame");
        require(configurationFields.get(1) instanceof SdkStorageCodec.Configuration,
                "Missing exact SDK selection configuration");
        var configuration = (SdkStorageCodec.Configuration) configurationFields.get(1);
        var roots = new EnumMap<SdkRuntimePointMaps.Kind, InsertionOrderedStorage.Snapshot>(SdkRuntimePointMaps.Kind.class);
        for (var kind : SdkRuntimePointMaps.Kind.values()) {
            var frame = fields(required(slots, slot(kind)));
            require(frame.size() == 6 && FORMAT.equals(frame.get(0)) && kind.name().equals(frame.get(1))
                    && configuration.equals(frame.get(2)) && frame.get(3) instanceof byte[]
                    && frame.get(4) instanceof byte[] && frame.get(5) instanceof Long,
                    "SDK selection family differs from its name/configuration");
            long sequence = (Long) frame.get(5);
            require(sequence >= 0, "Negative SDK insertion sequence");
            roots.put(kind, new InsertionOrderedStorage.Snapshot((byte[]) frame.get(3), (byte[]) frame.get(4), sequence));
        }
        var engine = new LinkedHashMap<String, byte[]>();
        slots.forEach((name, bytes) -> {
            if (name.startsWith(ENGINE)) {
                require(name.length() > ENGINE.length(), "Empty engine selection name");
                engine.put(name.substring(ENGINE.length()), bytes);
            } else {
                require(name.equals(CONFIGURATION) || roots.keySet().stream().anyMatch(kind -> name.equals(slot(kind))),
                        "Unknown SDK selection name: " + name);
            }
        });
        require(!engine.isEmpty(), "Missing complete engine selection");
        return new Parts(configuration, new SdkRuntimePointMaps.Snapshot(roots), engine);
    }

    private List<?> fields(byte[] bytes) { return codec.decode(bytes, List.class); }
    private static String slot(SdkRuntimePointMaps.Kind kind) { return "sdk/" + kind.name(); }
    private static byte[] required(Map<String, byte[]> slots, String name) {
        var bytes = slots.get(name); require(bytes != null, "Missing selected SDK slot: " + name); return bytes;
    }
    static Map<String, byte[]> copy(Map<String, byte[]> source) {
        var owned = new LinkedHashMap<String, byte[]>();
        Objects.requireNonNull(source, "slots").forEach((name, bytes) -> {
            require(name != null && !name.isBlank(), "Invalid selected slot name");
            require(bytes != null, "Missing selected slot bytes"); owned.put(name, bytes.clone());
        });
        return Collections.unmodifiableMap(owned);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new CoordinationObjectStorageException(message);
    }
}
