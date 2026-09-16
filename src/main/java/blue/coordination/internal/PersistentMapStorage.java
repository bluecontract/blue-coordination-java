package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Supplier;

/** Versioned physical AVL node format. No root publication or semantic policy. */
final class PersistentMapStorage<K, V> {
    private static final int NODE_MAGIC = 0x504d4e31; // PMN1
    private static final int ROOT_MAGIC = 0x504d5231; // PMR1
    private static final int IDENTITY_LIMIT = 256;
    private final Comparator<? super K> order;
    private final String orderingIdentity;
    private final PersistentMapCodec<K> keys;
    private final PersistentMapCodec<V> values;
    private final String keyIdentity;
    private final String valueIdentity;
    private final CoordinationImmutableObjectStore objects;
    private final Limits limits;
    private final ThreadLocal<LinkedHashMap<String, NodeBytes>> opened = new ThreadLocal<>();
    private final ThreadLocal<HashSet<String>> created = new ThreadLocal<>();

    /** Explicit physical bounds, never a protocol gas or closure limit. */
    record Limits(int nodeBytes, int keyBytes, int valueBytes, int descriptorBytes,
                  int cachedNodes) {
        Limits {
            if (nodeBytes < 128 || keyBytes < 1 || valueBytes < 1 || descriptorBytes < 64
                    || keyBytes > nodeBytes || valueBytes > nodeBytes || cachedNodes < 1) {
                throw new IllegalArgumentException("Invalid physical map bounds");
            }
        }
    }

    PersistentMapStorage(Comparator<? super K> order, String orderingIdentity,
            PersistentMapCodec<K> keys, PersistentMapCodec<V> values,
            CoordinationImmutableObjectStore objects, Limits limits) {
        this.order = Objects.requireNonNull(order, "order");
        this.orderingIdentity = identity(orderingIdentity);
        this.keys = Objects.requireNonNull(keys, "keys");
        this.values = Objects.requireNonNull(values, "values");
        this.keyIdentity = identity(keys.identity());
        this.valueIdentity = identity(values.identity());
        this.objects = Objects.requireNonNull(objects, "objects");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    <T> T scoped(Supplier<T> operation) {
        if (opened.get() != null) return operation.get();
        opened.set(new LinkedHashMap<>());
        created.set(new HashSet<>());
        try { return operation.get(); }
        catch (CoordinationObjectStorageException failure) { throw failure; }
        catch (RuntimeException failure) {
            throw new CoordinationObjectStorageException("Physical map operation failed", failure);
        } finally { opened.remove(); created.remove(); }
    }

    PersistentOrderedMap.TreeNode<K, V> open(byte[] descriptor) {
        if (descriptor == null) return null;
        return scoped(() -> {
            byte[] checked = boundedCopy(descriptor, limits.descriptorBytes(), "root descriptor");
            try (DataInputStream input = input(checked)) {
                require(input.readInt() == ROOT_MAGIC, "Unknown root format");
                readBinding(input);
                Handle handle = readHandle(input);
                require(input.available() == 0, "Trailing root descriptor bytes");
                if (handle == null) return null;
                StoredNode root = new StoredNode(handle, null, null);
                root.data(); // authenticate root metadata without opening descendants
                return root;
            } catch (IOException failure) { throw invalid("Malformed root descriptor", failure); }
        });
    }

    byte[] descriptor(PersistentOrderedMap.TreeNode<K, V> root) {
        return encode(limits.descriptorBytes(), output -> {
            output.writeInt(ROOT_MAGIC);
            writeBinding(output);
            writeHandle(output, handle(root));
        });
    }

    PersistentOrderedMap.TreeNode<K, V> create(K key, V value,
            PersistentOrderedMap.TreeNode<K, V> left,
            PersistentOrderedMap.TreeNode<K, V> right) {
        var preparedKey = Objects.requireNonNull(keys.prepareEncoding(key), "prepared key");
        var preparedValue = Objects.requireNonNull(values.prepareEncoding(value), "prepared value");
        byte[] keyBytes = canonicalEncode(keys, preparedKey, limits.keyBytes());
        byte[] valueBytes = canonicalEncode(values, preparedValue, limits.valueBytes());
        return createEncoded(keyBytes, valueBytes, left, right);
    }

    /** Rebranch an authenticated row without interpreting its unchanged value. */
    PersistentOrderedMap.TreeNode<K, V> rebranch(PersistentOrderedMap.TreeNode<K, V> original,
            PersistentOrderedMap.TreeNode<K, V> left,
            PersistentOrderedMap.TreeNode<K, V> right) {
        handle(original); // Require this exact physical binding before casting.
        NodeBytes row = ((StoredNode) original).data();
        return createEncoded(row.key(), row.value(), left, right);
    }

    private PersistentOrderedMap.TreeNode<K, V> createEncoded(byte[] keyBytes, byte[] valueBytes,
            PersistentOrderedMap.TreeNode<K, V> left,
            PersistentOrderedMap.TreeNode<K, V> right) {
        Handle leftHandle = handle(left), rightHandle = handle(right);
        int height = Math.addExact(Math.max(height(leftHandle), height(rightHandle)), 1);
        int size = Math.addExact(Math.addExact(size(leftHandle), size(rightHandle)), 1);
        byte[] bytes = encode(limits.nodeBytes(), output -> {
            output.writeInt(NODE_MAGIC);
            writeBinding(output);
            writeBytes(output, keyBytes);
            writeBytes(output, valueBytes);
            writeHandle(output, leftHandle);
            writeHandle(output, rightHandle);
            output.writeInt(height);
            output.writeInt(size);
        });
        String digest = digest(bytes);
        byte[] retained;
        try { retained = objects.putIfAbsent(digest, bytes.clone()); }
        catch (RuntimeException failure) { throw invalid("Immutable node write unavailable", failure); }
        retained = boundedCopy(retained, limits.nodeBytes(), "retained node");
        require(digest.equals(digest(retained)) && Arrays.equals(bytes, retained),
                "Immutable node collision or changed retained bytes");
        created.get().add(digest);
        return new StoredNode(new Handle(digest, height, size), null, null);
    }

    private Handle handle(PersistentOrderedMap.TreeNode<K, V> node) {
        if (node == null) return null;
        if (!(node instanceof PersistentMapStorage<?, ?>.StoredNode)) {
            throw invalid("Cannot mix resident and stored nodes", null);
        }
        StoredNode selected = (StoredNode) node;
        require(selected.owner() == this, "Cannot mix physical map bindings");
        return selected.handle;
    }

    private final class StoredNode extends PersistentOrderedMap.TreeNode<K, V> {
        private final Handle handle;
        private final byte[] lower;
        private final byte[] upper;
        StoredNode(Handle handle, byte[] lower, byte[] upper) {
            super(handle.height(), handle.size());
            this.handle = handle;
            this.lower = lower == null ? null : lower.clone();
            this.upper = upper == null ? null : upper.clone();
        }
        PersistentMapStorage<K, V> owner() { return PersistentMapStorage.this; }
        NodeBytes data() {
            NodeBytes data = load(handle);
            K key = decode(keys, data.key(), limits.keyBytes());
            require(lower == null || order.compare(decode(keys, lower, limits.keyBytes()), key) < 0,
                    "Node violates inherited lower ordering bound");
            require(upper == null || order.compare(key, decode(keys, upper, limits.keyBytes())) < 0,
                    "Node violates inherited upper ordering bound");
            return data;
        }
        @Override K key() { return decode(keys, data().key(), limits.keyBytes()); }
        @Override V value() { return decode(values, data().value(), limits.valueBytes()); }
        @Override PersistentOrderedMap.TreeNode<K, V> left() {
            NodeBytes data = data();
            return data.left() == null ? null : new StoredNode(data.left(), lower, data.key());
        }
        @Override PersistentOrderedMap.TreeNode<K, V> right() {
            NodeBytes data = data();
            return data.right() == null ? null : new StoredNode(data.right(), data.key(), upper);
        }
    }

    private NodeBytes load(Handle expected) {
        LinkedHashMap<String, NodeBytes> cache = opened.get();
        require(cache != null, "Node access outside bounded operation scope");
        NodeBytes data = cache.get(expected.digest());
        if (data == null) {
            byte[] bytes;
            try {
                bytes = objects.get(expected.digest(), limits.nodeBytes())
                        .orElseThrow(() -> invalid("Missing immutable map node " + expected.digest(), null));
            } catch (RuntimeException failure) { throw invalid("Immutable node read unavailable", failure); }
            bytes = boundedCopy(bytes, limits.nodeBytes(), "node");
            require(expected.digest().equals(digest(bytes)), "Immutable node digest mismatch");
            try (DataInputStream input = input(bytes)) {
                require(input.readInt() == NODE_MAGIC, "Unknown node format");
                readBinding(input);
                byte[] key = readBytes(input, limits.keyBytes());
                byte[] value = readBytes(input, limits.valueBytes());
                Handle left = readHandle(input), right = readHandle(input);
                int height = input.readInt(), size = input.readInt();
                require(input.available() == 0, "Trailing node bytes");
                require(height == 1 + Math.max(height(left), height(right))
                        && size == 1L + size(left) + size(right), "Inconsistent node dimensions");
                // Existing AVL rotations allocate transient unbalanced nodes. Only
                // nodes constructed in this operation can have that temporary shape.
                int maximumBalance = created.get().contains(expected.digest()) ? 2 : 1;
                require(Math.abs(height(left) - height(right)) <= maximumBalance,
                        "Invalid AVL branch heights");
                decode(keys, key, limits.keyBytes());
                // The authenticated, bounded value frame is interpreted only
                // when selected. Traversal and path copying are structural;
                // opening a root is not an audit of every retained payload.
                data = new NodeBytes(key, value, left, right, height, size);
            } catch (IOException failure) { throw invalid("Malformed node bytes", failure); }
            if (cache.size() >= limits.cachedNodes()) cache.remove(cache.keySet().iterator().next());
            cache.put(expected.digest(), data);
        }
        require(expected.height() == data.height() && expected.size() == data.size(),
                "Node disagrees with authenticated parent dimensions");
        return data;
    }

    private record Handle(String digest, int height, int size) {
        Handle {
            require(digest != null && digest.matches("[0-9a-f]{64}"), "Invalid physical node address");
            require(height > 0 && height <= 45 && size >= height, "Invalid node handle dimensions");
        }
    }
    private record NodeBytes(byte[] key, byte[] value, Handle left, Handle right, int height, int size) { }
    private static int height(Handle handle) { return handle == null ? 0 : handle.height(); }
    private static int size(Handle handle) { return handle == null ? 0 : handle.size(); }

    private void writeBinding(DataOutputStream output) throws IOException {
        writeBytes(output, orderingIdentity.getBytes(StandardCharsets.UTF_8));
        writeBytes(output, keyIdentity.getBytes(StandardCharsets.UTF_8));
        writeBytes(output, valueIdentity.getBytes(StandardCharsets.UTF_8));
    }
    private void readBinding(DataInputStream input) throws IOException {
        require(orderingIdentity.equals(text(readBytes(input, IDENTITY_LIMIT)))
                && keyIdentity.equals(text(readBytes(input, IDENTITY_LIMIT)))
                && valueIdentity.equals(text(readBytes(input, IDENTITY_LIMIT))), "Physical map binding mismatch");
    }
    private static void writeHandle(DataOutputStream output, Handle handle) throws IOException {
        output.writeBoolean(handle != null);
        if (handle != null) {
            output.write(HexFormat.of().parseHex(handle.digest()));
            output.writeInt(handle.height());
            output.writeInt(handle.size());
        }
    }
    private static Handle readHandle(DataInputStream input) throws IOException {
        int present = input.readUnsignedByte();
        require(present == 0 || present == 1, "Invalid child tag");
        if (present == 0) return null;
        require(input.available() >= 40, "Truncated child handle");
        byte[] digest = input.readNBytes(32);
        return new Handle(HexFormat.of().formatHex(digest), input.readInt(), input.readInt());
    }
    private static byte[] readBytes(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        require(length >= 0 && length <= maximum && length <= input.available(), "Invalid bounded field length");
        return input.readNBytes(length);
    }
    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }
    private static <T> byte[] canonicalEncode(PersistentMapCodec<T> codec,
            PersistentMapCodec.PreparedEncoding<T> prepared, int maximum) {
        byte[] bytes = boundedCopy(prepared.consume(codec), maximum, "encoded value");
        decode(codec, bytes, maximum);
        return bytes;
    }
    private static <T> T decode(PersistentMapCodec<T> codec, byte[] bytes, int maximum) {
        T value = Objects.requireNonNull(codec.decode(bytes.clone()), "decoded value");
        byte[] encoded = boundedCopy(codec.encode(value), maximum, "re-encoded value");
        require(Arrays.equals(bytes, encoded), "Noncanonical physical value codec");
        return value;
    }
    private static byte[] boundedCopy(byte[] bytes, int maximum, String label) {
        require(bytes != null && bytes.length <= maximum, "Missing or oversized " + label);
        return bytes.clone();
    }
    private static String identity(String text) {
        require(text != null && !text.isBlank()
                && text.getBytes(StandardCharsets.UTF_8).length <= IDENTITY_LIMIT, "Invalid codec/order identity");
        return text;
    }
    private static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw invalid("Invalid UTF-8 identity", failure); }
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    @FunctionalInterface private interface Writer { void write(DataOutputStream output) throws IOException; }
    private static byte[] encode(int maximum, Writer writer) {
        // Every individual field is bounded before it is copied into this stream.
        ByteArrayOutputStream bytes = new BoundedOutput(maximum);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
            require(bytes.size() <= maximum, "Encoded physical object exceeds configured bound");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw invalid("Cannot encode physical map object", impossible); }
    }
    private static final class BoundedOutput extends ByteArrayOutputStream {
        private final int maximum;
        BoundedOutput(int maximum) { super(Math.min(maximum, 1024)); this.maximum = maximum; }
        @Override public synchronized void write(int value) {
            require(count < maximum, "Encoded physical object exceeds configured bound");
            super.write(value);
        }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            require(length <= maximum - count, "Encoded physical object exceeds configured bound");
            super.write(bytes, offset, length);
        }
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw invalid(message, null);
    }
    private static CoordinationObjectStorageException invalid(String message, Throwable cause) {
        if (cause instanceof CoordinationObjectStorageException selected) return selected;
        return cause == null ? new CoordinationObjectStorageException(message)
                : new CoordinationObjectStorageException(message, cause);
    }
}
