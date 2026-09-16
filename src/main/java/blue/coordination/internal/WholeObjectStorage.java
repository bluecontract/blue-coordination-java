package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.ExactNodeStorageCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Individually addressed body/provider/proof records, not SDK recovery state.
 * The caller owns the pinned metadata index and atomically publishes references
 * with its owning state. Retaining bytes here never publishes a reference.
 */
final class WholeObjectStorage {
    private static final int ENTRY = 0x434f4532; // COE2: exact Language-owned lanes.
    private static final int PROOF = 0x434f5032; // COP2: exact mutable proof wire.
    private final CoordinationImmutableObjectStore objects;
    private final ExactValueStorageCodec values;
    private final ExactNodeStorageCodec nodes;
    private final int maximumBytes;
    private final RootedStorageCache cache;
    private final String exactValueFamily;
    private final String cyclicPairFamily;
    private final Runnable cyclicPairVerificationObserver;

    WholeObjectStorage(CoordinationImmutableObjectStore objects, int maximumBytes, int maximumDepth) {
        this(objects, maximumBytes, maximumDepth, null);
    }

    WholeObjectStorage(CoordinationImmutableObjectStore objects, int maximumBytes, int maximumDepth, RootedStorageCache cache) {
        this(objects, maximumBytes, maximumDepth, cache, null);
    }

    WholeObjectStorage(CoordinationImmutableObjectStore objects, int maximumBytes, int maximumDepth,
            RootedStorageCache cache, Runnable cyclicPairVerificationObserver) {
        this.objects = Objects.requireNonNull(objects, "objects");
        if (maximumBytes < 128) throw new IllegalArgumentException("Object byte limit must be at least 128");
        this.maximumBytes = maximumBytes;
        this.cache = cache;
        this.exactValueFamily = ExactValueStorageCodec.cacheFamily(maximumBytes, maximumDepth);
        this.cyclicPairFamily = "blue-coordination/verified-stored-cyclic-pair/1/" + maximumBytes + "/" + maximumDepth;
        this.cyclicPairVerificationObserver = cyclicPairVerificationObserver;
        this.values = new ExactValueStorageCodec(maximumBytes, maximumDepth);
        this.nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
    }

    /** Complete, immutable point/range metadata supplied by the owning publication. */
    interface Index {
        Optional<String> entryAddress(String blueId);
        Optional<String> proofAddress(String masterBlueId);
        /** Only retained wire-provider members of this one exact master. */
        Iterable<String> cyclicMembers(String masterBlueId);
        int size();
    }

    /** Delta references only; member additions must join the same index publication. */
    record References(Map<String, String> entries, Map<String, String> proofs,
                      Map<String, List<String>> cyclicMembers) {
        References {
            entries = Map.copyOf(entries); proofs = Map.copyOf(proofs);
            var members = new LinkedHashMap<String, List<String>>();
            cyclicMembers.forEach((key, value) -> members.put(key, List.copyOf(value)));
            cyclicMembers = Map.copyOf(members);
        }
    }

    /** Opening performs no index scan, body reads, resolution or PROCESS. */
    WholeObjectBacking open(Index index) {
        Objects.requireNonNull(index, "index");
        return new WholeObjectBacking() {
            @Override public Optional<Entry> find(String blueId) {
                return physical(() -> index.entryAddress(blueId).map(address -> {
                    Entry entry = decodeEntry(read(address)).entry();
                    require(blueId.equals(entry.canonical().blueId()), "Stored object key differs from exact identity");
                    return entry;
                }));
            }
            @Override public Optional<CyclicSetProof> proof(String masterBlueId) {
                return physical(() -> index.proofAddress(masterBlueId).map(address -> decode(read(address), in -> {
                    require(in.readInt() == PROOF, "Wrong proof format");
                    require(masterBlueId.equals(text(in)), "Stored proof key differs from master");
                    return decodeProof(bytes(in));
                })));
            }
            @Override public Iterable<String> cyclicMembers(String masterBlueId) {
                return index.cyclicMembers(masterBlueId);
            }
            @Override public int size() { return index.size(); }
            @Override public Optional<Node> verifiedCyclicProviderDocument(String blueId) {
                return physical(() -> index.entryAddress(blueId).flatMap(address -> {
                    DecodedEntry decoded = decodeEntry(read(address));
                    require(blueId.equals(decoded.entry().canonical().blueId()), "Stored object key differs from exact identity");
                    Node wire = decoded.entry().cyclicProviderBody();
                    if (wire == null) return Optional.empty();
                    String master = BlueIds.cyclicSetMasterBlueId(blueId);
                    String proofAddress = index.proofAddress(master).orElseThrow(() ->
                            new CoordinationObjectStorageException("Stored cyclic provider body has no complete proof " + blueId));
                    byte[] proofFrame = read(proofAddress);
                    CyclicSetProof complete = decode(proofFrame, in -> {
                        require(in.readInt() == PROOF, "Wrong proof format");
                        require(master.equals(text(in)), "Stored proof key differs from master");
                        return decodeProof(bytes(in));
                    });
                    verifyCyclicPair(blueId, decoded.wireFrame(), proofFrame, wire, complete);
                    return Optional.of(wire.clone());
                }));
            }
        };
    }

    /**
     * Prewrites only the attempt delta. Failures leave at most unreachable bytes.
     * Complete resolver snapshots retain their original Language-owned evidence.
     */
    References retain(WholeObjectBacking.Changes changes) {
        return physical(() -> {
            var entries = new LinkedHashMap<String, String>();
            var proofs = new LinkedHashMap<String, String>();
            var members = new LinkedHashMap<String, List<String>>();
            for (var item : new TreeMap<>(changes.entries()).entrySet()) {
                require(item.getKey().equals(item.getValue().canonical().blueId()), "Changed object key differs");
                entries.put(item.getKey(), write(encodeEntry(item.getValue())));
                if (item.getValue().cyclicProviderBody() != null) {
                    String master = BlueIds.cyclicSetMasterBlueId(item.getKey());
                    var selected = new java.util.ArrayList<>(members.getOrDefault(master, List.of()));
                    selected.add(item.getKey()); members.put(master, List.copyOf(selected));
                }
            }
            for (var item : new TreeMap<>(changes.proofs()).entrySet()) {
                proofs.put(item.getKey(), write(encode(out -> {
                    out.writeInt(PROOF); text(out, item.getKey()); bytes(out, encodeProof(item.getValue()));
                })));
            }
            return new References(entries, proofs, members);
        });
    }

    private byte[] encodeEntry(WholeObjectBacking.Entry entry) {
        return encode(out -> {
            out.writeInt(ENTRY); bytes(out, encodeValue(entry.canonical()));
            out.writeBoolean(entry.provider() != null);
            if (entry.provider() != null) bytes(out, encodeValue(entry.provider()));
            Node wire = entry.cyclicProviderBody(); out.writeBoolean(wire != null);
            if (wire != null) bytes(out, nodes.encode(wire));
            text(out, entry.purpose());
        });
    }

    private record DecodedEntry(WholeObjectBacking.Entry entry, byte[] wireFrame) { }

    private DecodedEntry decodeEntry(byte[] encoded) {
        return decode(encoded, in -> {
            require(in.readInt() == ENTRY, "Wrong object entry format");
            ExactValue canonical = decodeValue(bytes(in));
            ExactValue provider = bool(in) ? decodeValue(bytes(in)) : null;
            byte[] wireFrame = bool(in) ? bytes(in) : null;
            Node wire = wireFrame == null ? null : nodes.decode(wireFrame);
            return new DecodedEntry(new WholeObjectBacking.Entry(canonical, provider, wire, text(in)), wireFrame);
        });
    }

    private void verifyCyclicPair(String blueId, byte[] wireFrame, byte[] proofFrame, Node wire, CyclicSetProof proof) {
        java.util.function.Supplier<Boolean> verify = () -> {
            if (cyclicPairVerificationObserver != null) cyclicPairVerificationObserver.run();
            ExactValue.fromVerifiedProviderEvidence(blueId, wire, proof);
            return Boolean.TRUE;
        };
        long keyBytes = 12L + 2L * blueId.length() + wireFrame.length + proofFrame.length;
        if (cache == null || keyBytes > Integer.MAX_VALUE || !cache.canRetainEncodedBytes(keyBytes)) {
            verify.get(); return;
        }
        // These frames and values were freshly decoded above, not supplied by
        // a public Node/proof implementation. Only success is retained.
        byte[] key = physical(() -> {
            var buffer = new ByteArrayOutputStream((int) keyBytes);
            try (var out = new DataOutputStream(buffer)) {
                text(out, blueId); bytes(out, wireFrame); bytes(out, proofFrame);
            }
            return buffer.toByteArray();
        });
        cache.decode(cyclicPairFamily, key, verify);
    }

    private byte[] encodeValue(ExactValue value) {
        byte[] frame = cache == null ? null : cache.canonicalEncoding(exactValueFamily, value);
        return frame == null ? values.encode(value) : frame;
    }

    private ExactValue decodeValue(byte[] frame) {
        return cache == null ? values.decode(frame)
                : cache.decodeCanonical(exactValueFamily, frame, values::decode, values::encode);
    }

    private byte[] encodeProof(CyclicSetProof proof) {
        return nodes.encode(new Node().items(proof.declaredPlaceholderSet()));
    }
    private CyclicSetProof decodeProof(byte[] bytes) {
        return CyclicSetProof.fromDeclaredPlaceholderSet(nodes.decode(bytes).getItems());
    }

    private String write(byte[] bytes) {
        String address = digest(bytes);
        byte[] acknowledged = objects.putIfAbsent(address, bytes.clone());
        require(acknowledged != null && Arrays.equals(bytes, acknowledged), "Immutable byte acknowledgement differs");
        return address;
    }

    private byte[] read(String address) {
        require(address != null && address.matches("[0-9a-f]{64}"), "Invalid physical address");
        byte[] bytes = objects.get(address, maximumBytes).orElseThrow(() ->
                new CoordinationObjectStorageException("Missing selected whole-object bytes " + address));
        require(bytes != null && bytes.length <= maximumBytes, "Selected object exceeds byte limit");
        byte[] detached = bytes.clone();
        require(address.equals(digest(detached)), "Selected object digest differs");
        return detached;
    }

    private byte[] encode(Encoder encoder) {
        return physical(() -> {
            var buffer = new LimitedBuffer(maximumBytes);
            try (var out = new DataOutputStream(buffer)) { encoder.encode(out); }
            return buffer.toByteArray();
        });
    }
    private <T> T decode(byte[] bytes, Decoder<T> decoder) {
        return physical(() -> {
            try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                T result = decoder.decode(in); require(in.available() == 0, "Trailing whole-object bytes"); return result;
            }
        });
    }
    private static void bytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length); out.write(value);
    }
    private static byte[] bytes(DataInputStream in) throws IOException {
        int size = in.readInt(); require(size >= 0 && size <= in.available(), "Invalid byte field length");
        return in.readNBytes(size);
    }
    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length()); for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
    private static String text(DataInputStream in) throws IOException {
        int count = in.readInt(); require(count >= 0 && count <= in.available() / 2, "Invalid text field length");
        var chars = new char[count]; for (int i = 0; i < count; i++) chars[i] = in.readChar(); return new String(chars);
    }
    private static boolean bool(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); require(value <= 1, "Invalid Boolean field"); return value == 1;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new CoordinationObjectStorageException(message);
    }
    private static <T> T physical(Physical<T> action) {
        try { return action.run(); }
        catch (CoordinationObjectStorageException failure) { throw failure; }
        catch (Exception failure) { throw new CoordinationObjectStorageException("Whole-object storage failed", failure); }
    }
    @FunctionalInterface private interface Physical<T> { T run() throws Exception; }
    @FunctionalInterface private interface Encoder { void encode(DataOutputStream out) throws IOException; }
    @FunctionalInterface private interface Decoder<T> { T decode(DataInputStream in) throws IOException; }
    private static final class LimitedBuffer extends ByteArrayOutputStream {
        private final int maximum;
        LimitedBuffer(int maximum) { this.maximum = maximum; }
        @Override public synchronized void write(int value) {
            require(count < maximum, "Whole-object exceeds byte limit"); super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            require(length <= maximum - count, "Whole-object exceeds byte limit"); super.write(bytes, offset, length);
        }
    }
}
