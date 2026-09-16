package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;
import static blue.coordination.internal.SessionStorageWire.*;
import static blue.coordination.internal.SessionRecordCodec.*;

/** Exact immutable append chunks. Mutable root publication and read fences remain the owning store's concern. */
final class PersistentAppendLogStorage<T> {
    private static final String ROOT = "blue-coordination/append-root/1", CHUNK = "blue-coordination/append-chunk/1";
    record Limits(int chunkBytes, int valueBytes, int descriptorBytes, int cachedChunks) {
        Limits {
            if (chunkBytes < 128 || valueBytes < 1 || valueBytes > chunkBytes || descriptorBytes < 64 || cachedChunks < 1)
                throw new IllegalArgumentException("Invalid physical append-log bounds");
        }
    }
    record Handle(String digest, int size) {
        Handle { require(digest != null && digest.matches("[0-9a-f]{64}") && size > 0, "Malformed append-log handle"); }
    }
    private record Frame(Handle previous, List<byte[]> rows) { }
    private final CoordinationImmutableObjectStore objects;
    private final String binding;
    private final PersistentMapCodec<T> codec;
    private final String codecIdentity;
    private final Limits limits;
    private final ThreadLocal<LinkedHashMap<Handle, Frame>> cache = new ThreadLocal<>();

    PersistentAppendLogStorage(CoordinationImmutableObjectStore objects, String binding, PersistentMapCodec<T> codec, Limits limits) {
        this.objects = Objects.requireNonNull(objects); this.codec = Objects.requireNonNull(codec); this.limits = Objects.requireNonNull(limits);
        require(binding != null && !binding.isBlank(), "Missing append-log binding"); this.binding = binding;
        this.codecIdentity = Objects.requireNonNull(codec.identity()); require(!codecIdentity.isBlank(), "Missing append-log row identity");
    }

    PersistentAppendLog<T> open(byte[] descriptor) {
        return scoped(() -> {
            Handle handle = descriptor == null ? null : decode(descriptor, limits.descriptorBytes(), r -> {
                require(ROOT.equals(text(r)), "Unknown append root format"); readBinding(r); return new RootHandle(readHandle(r));
            }).handle();
            if (handle != null) load(handle);
            return PersistentAppendLog.fromStorage(this, handle);
        });
    }
    private record RootHandle(Handle handle) { }

    PersistentAppendLog<T> retain(PersistentAppendLog<T> log) { return scoped(() -> log.storedCopy(this)); }

    byte[] descriptor(Handle handle) { return encode(limits.descriptorBytes(), w -> { w.text(ROOT); writeBinding(w); writeHandle(w, handle); }); }

    Handle append(Handle previous, List<T> appended) {
        return scoped(() -> {
            require(!appended.isEmpty(), "An immutable append chunk cannot be empty");
            int size = Math.addExact(previous == null ? 0 : previous.size(), appended.size());
            byte[] bytes = encode(limits.chunkBytes(), w -> {
                w.text(CHUNK); writeBinding(w); w.integer(size); writeHandle(w, previous); w.integer(appended.size());
                for (T row : appended) {
                    byte[] value = Objects.requireNonNull(codec.encode(codec.prepareForStorage(row)));
                    require(value.length <= limits.valueBytes(), "Append row exceeds physical bound"); w.bytes(value);
                }
            });
            String digest = digest(bytes); byte[] ack = objects.putIfAbsent(digest, bytes.clone());
            require(Arrays.equals(bytes, ack), "Append chunk retention acknowledged different bytes");
            return new Handle(digest, size);
        });
    }
    Handle previous(Handle handle) { return scoped(() -> load(handle).previous()); }
    List<T> chunk(Handle handle) {
        return scoped(() -> {
            var result = new ArrayList<T>();
            for (byte[] bytes : load(handle).rows()) {
                T value = Objects.requireNonNull(codec.decode(bytes.clone()));
                require(Arrays.equals(bytes, codec.encode(value)), "Noncanonical append row"); result.add(value);
            }
            return List.copyOf(result);
        });
    }
    boolean sameBinding(PersistentAppendLogStorage<?> other) { return binding.equals(other.binding) && codecIdentity.equals(other.codecIdentity); }

    private Frame load(Handle handle) {
        require(handle != null, "Cannot load empty append root"); var opened = cache.get(); var present = opened.get(handle); if (present != null) return present;
        byte[] bytes = objects.get(handle.digest(), limits.chunkBytes()).orElseThrow(() -> new blue.coordination.api.storage.CoordinationObjectStorageException("Missing append chunk " + handle.digest()));
        require(bytes != null && bytes.length <= limits.chunkBytes() && handle.digest().equals(digest(bytes)), "Invalid append chunk bytes or digest");
        Frame frame = decode(bytes, limits.chunkBytes(), r -> {
            require(CHUNK.equals(text(r)), "Unknown append chunk format"); readBinding(r); int size = r.integer();
            require(size == handle.size(), "Selected append size differs"); Handle previous = readHandle(r);
            int count = r.count(Integer.MAX_VALUE, 4); require(count > 0 && size - (previous == null ? 0L : previous.size()) == count,
                    "Append predecessor/chunk sizes are not contiguous");
            var rows = new ArrayList<byte[]>(); for (int i = 0; i < count; i++) rows.add(r.bytes(limits.valueBytes()));
            return new Frame(previous, List.copyOf(rows));
        });
        if (opened.size() == limits.cachedChunks()) opened.remove(opened.keySet().iterator().next()); opened.put(handle, frame); return frame;
    }
    <R> R scoped(Supplier<R> action) {
        if (cache.get() != null) return physical(action);
        cache.set(new LinkedHashMap<>()); try { return physical(action); } finally { cache.remove(); }
    }
    private void writeBinding(Writer w) { w.text(binding); w.text(codecIdentity); }
    private void readBinding(Reader r) { require(binding.equals(text(r)) && codecIdentity.equals(text(r)), "Append root/row binding differs"); }
    private static void writeHandle(Writer w, Handle handle) { optional(w, handle, (x, value) -> { x.text(value.digest()); x.integer(value.size()); }); }
    private static Handle readHandle(Reader r) { return optional(r, x -> new Handle(text(x), x.integer())); }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
