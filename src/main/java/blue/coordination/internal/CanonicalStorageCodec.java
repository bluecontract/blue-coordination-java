package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import java.util.Objects;

/**
 * Opt-in for complete, deeply immutable values with no owner-bound dependencies.
 * First raw decode and raw encode establish the complete canonical frame before
 * cache insertion. Existing map canonical checks remain in place; their encode
 * may reuse only that verified value's exact retained object identity.
 */
final class CanonicalStorageCodec<T> implements PersistentMapCodec<T> {
    private final PersistentMapCodec<T> raw;
    private final RootedStorageCache cache;
    private final String family;
    private final int maximumBytes;

    CanonicalStorageCodec(PersistentMapCodec<T> raw, RootedStorageCache cache, int maximumBytes, int maximumDepth) {
        this(raw, cache, maximumBytes, maximumDepth, "");
    }

    CanonicalStorageCodec(PersistentMapCodec<T> raw, RootedStorageCache cache, int maximumBytes, int maximumDepth,
            String additionalValidationProfile) {
        this.raw = Objects.requireNonNull(raw); this.cache = cache;
        if (maximumBytes < 1 || maximumDepth < 1) throw new IllegalArgumentException("Invalid canonical codec limits");
        this.maximumBytes = maximumBytes;
        family = raw.identity() + "/" + maximumBytes + "/" + maximumDepth + "/"
                + Objects.requireNonNull(additionalValidationProfile);
    }

    @Override public String identity() { return raw.identity(); }
    @Override public T prepareForStorage(T value) { return raw.prepareForStorage(value); }
    @Override public byte[] encode(T value) {
        byte[] known = cache == null ? null : cache.canonicalEncoding(family, value);
        return known == null ? raw.encode(value) : known;
    }
    @Override public T decode(byte[] bytes) {
        if (bytes == null || bytes.length > maximumBytes)
            throw new CoordinationObjectStorageException("Missing or oversized canonical value");
        return cache == null ? raw.decode(bytes)
                : cache.decodeCanonical(family, bytes, raw::decode, raw::encode);
    }
}
