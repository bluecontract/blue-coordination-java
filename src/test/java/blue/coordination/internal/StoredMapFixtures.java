package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationImmutableObjectStore;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

final class StoredMapFixtures {
    static final String ORDER = "test/int-order/1";
    static final PersistentMapStorage.Limits LIMITS = new PersistentMapStorage.Limits(4096, 16, 2048, 1024, 64);
    static final PersistentMapCodec<Integer> INTS = new PersistentMapCodec<>() {
        @Override public String identity() { return "test/int32/1"; }
        @Override public byte[] encode(Integer value) { return ByteBuffer.allocate(4).putInt(value).array(); }
        @Override public Integer decode(byte[] bytes) {
            if (bytes.length != 4) throw new IllegalArgumentException("int32 length");
            return ByteBuffer.wrap(bytes).getInt();
        }
    };
    static final PersistentMapCodec<String> TEXT = new PersistentMapCodec<>() {
        @Override public String identity() { return "test/utf8/1"; }
        @Override public byte[] encode(String value) {
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                return bytes;
            } catch (CharacterCodingException failure) {
                throw new IllegalArgumentException("Malformed string is not canonical UTF-8", failure);
            }
        }
        @Override public String decode(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    };
    static PersistentOrderedMap<Integer, String> open(CoordinationImmutableObjectStore store, byte[] root) {
        return PersistentOrderedMap.stored(Comparator.naturalOrder(), ORDER, INTS, TEXT, store, LIMITS, root);
    }
    private StoredMapFixtures() { }
}
