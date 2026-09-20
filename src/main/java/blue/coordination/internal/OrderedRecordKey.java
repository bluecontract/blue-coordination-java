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

    static <K, A, B> OrderedRecordKey<K> pair(String identity, OrderedRecordKey<A> first, OrderedRecordKey<B> second,
            Function<K, A> left, Function<K, B> right, java.util.function.BiFunction<A, B, K> construct) {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/" + identity + "/1"; }
            public byte[] encode(K key) { return tuple(first.encode(left.apply(key)), second.encode(right.apply(key))); }
            public K decode(byte[] bytes) {
                byte[][] parts = split(bytes, 2); return construct.apply(first.decode(parts[0]), second.decode(parts[1]));
            }
        };
    }

    static OrderedRecordKey<java.math.BigInteger> integer() {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/integer/1"; }
            public byte[] encode(java.math.BigInteger value) {
                int sign = value.signum(); if (sign == 0) return new byte[] {1};
                byte[] magnitude = value.abs().toByteArray();
                if (magnitude[0] == 0) magnitude = Arrays.copyOfRange(magnitude, 1, magnitude.length);
                var bytes = ByteBuffer.allocate(5 + magnitude.length).put((byte) (sign < 0 ? 0 : 2))
                        .putInt(sign < 0 ? ~magnitude.length : magnitude.length).put(magnitude).array();
                if (sign < 0) for (int i = 5; i < bytes.length; i++) bytes[i] = (byte) ~bytes[i];
                return bytes;
            }
            public java.math.BigInteger decode(byte[] bytes) {
                if (bytes.length == 1 && bytes[0] == 1) return java.math.BigInteger.ZERO;
                if (bytes.length < 6 || (bytes[0] != 0 && bytes[0] != 2)) throw new IllegalArgumentException("Invalid integer key");
                boolean negative = bytes[0] == 0; int length = ByteBuffer.wrap(bytes, 1, 4).getInt();
                if (negative) length = ~length;
                if (length != bytes.length - 5) throw new IllegalArgumentException("Invalid integer magnitude length");
                byte[] magnitude = Arrays.copyOfRange(bytes, 5, bytes.length);
                if (negative) for (int i = 0; i < magnitude.length; i++) magnitude[i] = (byte) ~magnitude[i];
                var value = new java.math.BigInteger(negative ? -1 : 1, magnitude);
                if (!Arrays.equals(bytes, encode(value))) throw new IllegalArgumentException("Noncanonical integer key");
                return value;
            }
        };
    }

    static OrderedRecordKey<blue.language.processor.ExternalOrderKey> externalOrder() {
        return new OrderedRecordKey<>() {
            public String identity() { return "blue-coordination/ordered-key/external-order/1"; }
            public byte[] encode(blue.language.processor.ExternalOrderKey value) {
                var components = new java.util.ArrayList<byte[]>();
                for (Object component : value.components()) {
                    boolean numeric = component instanceof java.math.BigInteger;
                    byte[] scalar = numeric ? integer().encode((java.math.BigInteger) component) : text().encode((String) component);
                    byte[] tagged = new byte[scalar.length + 1]; tagged[0] = (byte) (numeric ? 0 : 1);
                    System.arraycopy(scalar, 0, tagged, 1, scalar.length); components.add(tagged);
                }
                return tuple(components.toArray(byte[][]::new));
            }
            public blue.language.processor.ExternalOrderKey decode(byte[] bytes) {
                var result = new java.util.ArrayList<Object>();
                for (byte[] component : splitAll(bytes)) {
                    if (component.length == 0 || (component[0] != 0 && component[0] != 1))
                        throw new IllegalArgumentException("Invalid external-order scalar kind");
                    byte[] scalar = Arrays.copyOfRange(component, 1, component.length);
                    result.add(component[0] == 0 ? integer().decode(scalar) : text().decode(scalar));
                }
                return blue.language.processor.ExternalOrderKey.of(result);
            }
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
        byte[][] parts = splitAll(encoded);
        if (parts.length != count) throw new IllegalArgumentException("Wrong tuple arity");
        return parts;
    }

    static byte[][] splitAll(byte[] encoded) {
        var result = new java.util.ArrayList<byte[]>(); var part = new ByteArrayOutputStream();
        for (int offset = 0; offset < encoded.length; offset++) {
            int value = Byte.toUnsignedInt(encoded[offset]);
            if (value != 0) { part.write(value); continue; }
            if (++offset >= encoded.length) throw new IllegalArgumentException("Truncated tuple key");
            int escape = Byte.toUnsignedInt(encoded[offset]);
            if (escape == 255) { part.write(0); continue; }
            if (escape != 0) throw new IllegalArgumentException("Invalid tuple key");
            result.add(part.toByteArray()); part.reset();
        }
        byte[][] parts = result.toArray(byte[][]::new);
        if (part.size() != 0 || !Arrays.equals(tuple(parts), encoded)) throw new IllegalArgumentException("Noncanonical tuple key");
        return parts;
    }
}
