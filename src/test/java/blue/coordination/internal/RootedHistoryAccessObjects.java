package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Detached physical bytes and test-only access accounting, never a substitute codec or proof. */
final class RootedHistoryAccessObjects implements CoordinationImmutableObjectStore {
    private static final String PREFIX = "blue-coordination/";
    private final Map<String, byte[]> bytes = new LinkedHashMap<>();
    private final List<Access> accesses = new ArrayList<>();
    private Set<String> existing = Set.of();
    private Set<String> originalPayloads = Set.of();
    private List<String> firstOldPayloadPutStack = List.of();
    private boolean measuring;

    record Access(String operation, String address, String category, int bytes, boolean existedBeforePhase) { }
    record Count(long calls, long bytes, int distinctAddresses) { }
    record Measurement(Map<String, Count> gets, Map<String, Count> puts,
            long oldPayloadPutCalls, long oldPayloadPutBytes, int distinctOldPayloadPuts,
            long unrequestedHistoricalPayloadGetCalls, int unrequestedHistoricalPayloadAddresses,
            List<String> unrequestedHistoricalAddresses,
            Map<String, Integer> sessionDescriptorBytes, List<Access> addresses,
            List<String> firstOldPayloadPutStack) { }

    @Override public byte[] putIfAbsent(String address, byte[] value) {
        byte[] prior = bytes.putIfAbsent(address, value.clone());
        if (prior != null && !Arrays.equals(prior, value)) throw new CoordinationObjectStorageException("Address collision");
        observe("PUT", address, value);
        return (prior == null ? value : prior).clone();
    }

    @Override public Optional<byte[]> get(String address, int maximumBytes) {
        byte[] value = bytes.get(address);
        if (value != null && value.length > maximumBytes) throw new CoordinationObjectStorageException("Physical read bound");
        observe("GET", address, value);
        return Optional.ofNullable(value == null ? null : value.clone());
    }

    RootedHistoryAccessObjects detachedCopy() {
        var copy = new RootedHistoryAccessObjects();
        bytes.forEach((address, value) -> copy.bytes.put(address, value.clone()));
        return copy;
    }

    void begin(Set<String> historicalPayloads) {
        if (measuring) throw new IllegalStateException("Nested measurement");
        accesses.clear(); existing = Set.copyOf(bytes.keySet()); originalPayloads = historicalPayloads;
        firstOldPayloadPutStack = List.of(); measuring = true;
    }

    Measurement end(Set<String> historicalPayloads, Set<String> requiredPayloads) {
        if (!measuring) throw new IllegalStateException("No active measurement");
        measuring = false;
        var unexpected = new LinkedHashSet<String>();
        var oldPuts = new LinkedHashSet<String>();
        long oldCalls = 0, oldBytes = 0, unrequestedCalls = 0;
        var descriptors = new LinkedHashMap<String, Integer>();
        for (var access : accesses) {
            if (access.operation().equals("GET") && historicalPayloads.contains(access.address())
                    && !requiredPayloads.contains(access.address())) {
                unrequestedCalls++; unexpected.add(access.address());
            }
            if (access.operation().equals("PUT") && historicalPayloads.contains(access.address()) && payload(access.category())) {
                oldCalls++; oldBytes += access.bytes(); oldPuts.add(access.address());
            }
            if (access.category().equals("session-descriptor")) descriptors.put(access.address(), access.bytes());
        }
        return new Measurement(counts("GET"), counts("PUT"), oldCalls, oldBytes, oldPuts.size(),
                unrequestedCalls, unexpected.size(), List.copyOf(unexpected), Map.copyOf(descriptors), List.copyOf(accesses),
                firstOldPayloadPutStack);
    }

    Set<String> historicalPayloadAddresses() {
        var result = new LinkedHashSet<String>();
        bytes.forEach((address, value) -> { if (payload(category(value))) result.add(address); });
        return Set.copyOf(result);
    }

    Map<String, Integer> sessionDescriptors() {
        var result = new LinkedHashMap<String, Integer>();
        bytes.forEach((address, value) -> {
            if (category(value).equals("session-descriptor")) result.put(address, value.length);
        });
        return Map.copyOf(result);
    }

    String categoryAt(String address) { return category(bytes.get(address)); }

    /** Test evidence outside measured phases; never reconstructs or changes the retained artifact. */
    byte[] diagnosticBytes(String address) {
        if (measuring) throw new IllegalStateException("Diagnostics must stay outside measured phases");
        byte[] selected = bytes.get(address);
        if (selected == null) throw new IllegalArgumentException("Missing diagnostic artifact " + address);
        return selected.clone();
    }

    Map<String, Integer> categoryInventory() {
        var result = new LinkedHashMap<String, Integer>();
        bytes.values().forEach(value -> result.merge(category(value), 1, Integer::sum));
        return Map.copyOf(result);
    }

    private void observe(String operation, String address, byte[] value) {
        if (!measuring) return;
        accesses.add(new Access(operation, address, category(value), value == null ? 0 : value.length,
                existing.contains(address)));
        if (operation.equals("PUT") && originalPayloads.contains(address) && firstOldPayloadPutStack.isEmpty()) {
            // One bounded diagnostic only when the zero-rewrite mechanism fails;
            // successful selective phases do not pay for stack collection.
            firstOldPayloadPutStack = StackWalker.getInstance().walk(frames -> frames.limit(32)
                    .map(Object::toString).toList());
        }
    }

    private Map<String, Count> counts(String operation) {
        var groups = new LinkedHashMap<String, List<Access>>();
        for (var access : accesses) if (access.operation().equals(operation))
            groups.computeIfAbsent(access.category(), ignored -> new ArrayList<>()).add(access);
        var result = new LinkedHashMap<String, Count>();
        groups.forEach((category, values) -> result.put(category, new Count(values.size(),
                values.stream().mapToLong(Access::bytes).sum(),
                (int) values.stream().map(Access::address).distinct().count())));
        return Map.copyOf(result);
    }

    private static boolean payload(String category) { return category.equals("revision-payload") || category.equals("view-payload"); }

    /** Header inspection counts actual records; it never reconstructs or validates their semantic values. */
    private static String category(byte[] value) {
        if (value == null) return "missing";
        if (value.length < Integer.BYTES) return "other";
        int first = ByteBuffer.wrap(value).getInt();
        if (first == 0x504d4e31) return "index-node";
        if (first >= 0 && first <= value.length - Integer.BYTES && first % 2 == 0 && first < 512) {
            String format = new String(value, Integer.BYTES, first, StandardCharsets.UTF_16BE);
            if (format.startsWith(PREFIX + "document-session-storage/")) return "session-descriptor";
            if (format.startsWith(PREFIX + "document-revision-storage/")) return "revision-payload";
            if (format.startsWith(PREFIX + "rooted-document-view-storage/")) return "view-payload";
            if (format.startsWith(PREFIX + "append-chunk/")) return "append-chunk";
            if (format.startsWith(PREFIX)) return format;
        }
        return "other";
    }

    static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
