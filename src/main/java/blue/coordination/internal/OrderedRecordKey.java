package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import java.util.function.Function;

/** Library-owned order-preserving keys, independent of private AVL value frames. */
interface OrderedRecordKey<K> extends PersistentMapCodec<K> {
    static OrderedRecordKey<String> text() {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/text/1"; }
            public byte[] encode(String value) {
                try {
                    var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value));
                    byte[] result = new byte[buffer.remaining()]; buffer.get(result); return result;
                } catch (CharacterCodingException failure) { throw new IllegalArgumentException("Invalid Unicode key", failure); }
            }
            public String decode(byte[] bytes) {
                try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
                catch (CharacterCodingException failure) { throw new IllegalArgumentException("Invalid UTF-8 key", failure); }
            }
        };
    }

    static OrderedRecordKey<Long> signedLong() {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/signed-long/1"; }
            public byte[] encode(Long value) { return ByteBuffer.allocate(Long.BYTES).putLong(value ^ Long.MIN_VALUE).array(); }
            public Long decode(byte[] bytes) {
                if (bytes.length != Long.BYTES) throw new IllegalArgumentException("Invalid signed-long key");
                return ByteBuffer.wrap(bytes).getLong() ^ Long.MIN_VALUE;
            }
        };
    }

    static OrderedRecordKey<DocumentId> document() {
        return mapped("document", text(), DocumentId::value, DocumentId::of);
    }

    static <K, T> OrderedRecordKey<K> mapped(String identity, OrderedRecordKey<T> codec,
            Function<K, T> encode, Function<T, K> decode) {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/" + identity + "/1"; }
            public byte[] encode(K value) { return codec.encode(encode.apply(value)); }
            public K decode(byte[] bytes) { return decode.apply(codec.decode(bytes)); }
        };
    }

    /** Zero escaping plus a two-zero terminator preserves scalar and tuple prefix ordering. */
    static byte[] tuple(byte[]... components) {
        var out = new ByteArrayOutputStream();
        for (byte[] component : components) {
            for (byte value : component) {
                out.write(value);
                if (value == 0) out.write(255);
            }
            out.write(0); out.write(0);
        }
        return out.toByteArray();
    }

    static byte[][] split(byte[] encoded, int count) {
        var result = new byte[count][]; var part = new ByteArrayOutputStream(); int index = 0;
        for (int offset = 0; offset < encoded.length; offset++) {
            int value = Byte.toUnsignedInt(encoded[offset]);
            if (value != 0) { part.write(value); continue; }
            if (++offset >= encoded.length) throw new IllegalArgumentException("Truncated tuple key");
            int escape = Byte.toUnsignedInt(encoded[offset]);
            if (escape == 255) { part.write(0); continue; }
            if (escape != 0 || index == count) throw new IllegalArgumentException("Invalid tuple key");
            result[index++] = part.toByteArray(); part.reset();
        }
        if (index != count || part.size() != 0 || !Arrays.equals(tuple(result), encoded))
            throw new IllegalArgumentException("Noncanonical tuple key");
        return result;
    }
}
