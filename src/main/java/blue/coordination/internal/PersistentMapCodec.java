package blue.coordination.internal;

/**
 * Library-owned canonical value encoding for one physical index. Implementations
 * must name their format/version, preserve the exact typed value, bound decoding
 * before allocation, and return detached or immutable values. No Java object
 * serialization or arbitrary provider codecs are used by the map.
 */
interface PersistentMapCodec<T> {
    String identity();
    /**
     * Retains immutable dependencies before a new index value is encoded.
     * Returns an equivalent value whose physical references are ready to encode.
     * Invoked only while preparing a mutation, never during canonical read checks.
     */
    default T prepareForStorage(T value) { return value; }
    /** One mutation's preparation; ordinary codecs defer encoding until both values are prepared. */
    default PreparedEncoding<T> prepareEncoding(T value) {
        return PreparedEncoding.deferred(prepareForStorage(value));
    }
    /** Canonical encoding must not write physical objects. */
    byte[] encode(T value);
    /** Decoding and canonical re-encoding must not write physical objects. */
    T decode(byte[] bytes);

    /**
     * One-shot physical snapshot, never an identity certificate for later encode calls.
     * Consuming it does not replace the map's bounded canonical decode/re-encode checks.
     */
    final class PreparedEncoding<T> {
        private final T value;
        private final byte[] bytes;
        private boolean consumed;

        private PreparedEncoding(T value, byte[] bytes) {
            this.value = value; this.bytes = bytes;
        }
        static <T> PreparedEncoding<T> deferred(T value) {
            return new PreparedEncoding<>(java.util.Objects.requireNonNull(value, "prepared value"), null);
        }
        static <T> PreparedEncoding<T> encoded(byte[] bytes) {
            return new PreparedEncoding<>(null, java.util.Objects.requireNonNull(bytes, "prepared bytes").clone());
        }
        synchronized byte[] consume(PersistentMapCodec<T> codec) {
            if (consumed) throw new IllegalStateException("Prepared encoding was already consumed");
            consumed = true;
            return bytes == null ? codec.encode(value) : bytes.clone();
        }
    }
}
