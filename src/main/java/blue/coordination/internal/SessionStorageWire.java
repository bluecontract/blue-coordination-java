package blue.coordination.internal;

import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.NoncommittingExecutionException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Bounded exact physical framing shared by library-owned storage codecs. */
final class SessionStorageWire {

    static byte[] encode(int maximumBytes, Consumer<Writer> encoder) {
        return physical(() -> {
            Writer writer = new Writer(maximumBytes);
            encoder.accept(writer);
            return writer.buffer.toByteArray();
        });
    }

    static <T> T decode(byte[] bytes, int maximumBytes, Function<Reader, T> decoder) {
        return physical(() -> {
            require(bytes != null && maximumBytes >= 0 && bytes.length <= maximumBytes,
                    "Oversized or missing physical value");
            Reader reader = new Reader(bytes.clone());
            T value = Objects.requireNonNull(decoder.apply(reader), "decoded physical value");
            require(reader.remaining() == 0, "Trailing physical value bytes");
            return value;
        });
    }

    static void order(Writer out, ExternalOrderKey order) {
        List<Object> components = Objects.requireNonNull(order, "order").components();
        out.integer(components.size());
        for (Object component : components) {
            if (component instanceof BigInteger integer) {
                out.integer(1); out.bytes(integer.toByteArray());
            } else if (component instanceof String text) {
                out.integer(2); out.text(text);
            } else {
                throw invalid("Unsupported canonical external-order component", null);
            }
        }
    }

    static ExternalOrderKey order(Reader in) {
        int count = in.count(Integer.MAX_VALUE, 8);
        List<Object> components = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int kind = in.integer();
            if (kind == 1) {
                byte[] bytes = in.bytes(in.remaining());
                require(bytes.length > 0, "Empty integer order component");
                BigInteger value = new BigInteger(bytes);
                require(Arrays.equals(bytes, value.toByteArray()), "Noncanonical integer order component");
                components.add(value);
            } else if (kind == 2) {
                components.add(in.text(in.remaining()));
            } else {
                throw invalid("Unknown external-order component", null);
            }
        }
        return ExternalOrderKey.of(components);
    }

    static <T> T physical(Supplier<T> operation) {
        try { return operation.get(); }
        catch (NoncommittingExecutionException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid("Physical storage codec failed", failure); }
    }

    static void require(boolean condition, String message) {
        if (!condition) throw invalid(message, null);
    }

    private static CoordinationObjectStorageException invalid(String message, Throwable cause) {
        return new CoordinationObjectStorageException(message, cause);
    }

    static final class Writer {
        private final BoundedBuffer buffer;
        private final DataOutputStream out;
        private Writer(int maximumBytes) {
            require(maximumBytes >= 0, "Invalid physical byte limit");
            buffer = new BoundedBuffer(maximumBytes);
            out = new DataOutputStream(buffer);
        }
        void integer(int value) { io(() -> out.writeInt(value)); }
        void longValue(long value) { io(() -> out.writeLong(value)); }
        void bool(boolean value) { io(() -> out.writeByte(value ? 1 : 0)); }
        void bytes(byte[] value) {
            Objects.requireNonNull(value, "bytes");
            require((long) value.length + 4 <= buffer.remaining(), "Physical frame exceeds byte limit");
            integer(value.length); io(() -> out.write(value));
        }
        void text(String value) {
            Objects.requireNonNull(value, "text");
            long length = 2L * value.length();
            require(length + 4 <= buffer.remaining(), "Physical text exceeds byte limit");
            integer((int) length);
            io(() -> { for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i)); });
        }
        void nullableText(String value) { bool(value != null); if (value != null) text(value); }
        private void io(IoAction action) {
            try { action.run(); } catch (IOException failure) { throw invalid("Cannot write physical value", failure); }
        }
    }

    static final class Reader {
        private final ByteArrayInputStream bytes;
        private final DataInputStream in;
        private Reader(byte[] value) { bytes = new ByteArrayInputStream(value); in = new DataInputStream(bytes); }
        int remaining() { return bytes.available(); }
        int integer() { return io(in::readInt); }
        long longValue() { return io(in::readLong); }
        boolean bool() {
            int value = io(in::readUnsignedByte);
            require(value <= 1, "Noncanonical boolean");
            return value == 1;
        }
        byte[] bytes(int maximum) {
            int count = integer();
            require(count >= 0 && count <= maximum && count <= remaining(), "Invalid physical frame length");
            return io(() -> in.readNBytes(count));
        }
        String text(int maximumBytes) {
            byte[] value = bytes(maximumBytes);
            require(value.length % 2 == 0, "Odd UTF-16 physical text length");
            char[] characters = new char[value.length / 2];
            for (int i = 0; i < characters.length; i++) {
                characters[i] = (char) ((value[2 * i] & 0xff) << 8 | (value[2 * i + 1] & 0xff));
            }
            return new String(characters);
        }
        String nullableText(int maximumBytes) { return bool() ? text(maximumBytes) : null; }
        int count(int maximumItems, int minimumBytesPerItem) {
            int count = integer();
            require(count >= 0 && maximumItems >= 0 && count <= maximumItems && minimumBytesPerItem > 0
                    && (long) count * minimumBytesPerItem <= remaining(), "Invalid physical item count");
            return count;
        }
        private <T> T io(IoSupplier<T> operation) {
            try { return operation.get(); } catch (IOException failure) { throw invalid("Truncated physical value", failure); }
        }
    }

    @FunctionalInterface private interface IoAction { void run() throws IOException; }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
    private static final class BoundedBuffer extends ByteArrayOutputStream {
        private final int maximum;
        BoundedBuffer(int maximum) { this.maximum = maximum; }
        int remaining() { return maximum - count; }
        private void check(int length) { require(length >= 0 && length <= remaining(), "Physical encoding bound exceeded"); }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            check(length); super.write(bytes, offset, length);
        }
    }
    private SessionStorageWire() { }
}
