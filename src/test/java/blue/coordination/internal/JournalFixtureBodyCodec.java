package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only copy of MyOS ResolvedBodyCodec, source SHA-256 9939b4154b72ff750f2ffa0f0dca5bbc204ba9f4b43269f05669b1548f55bf0f.
 * Source: myos-coordination-external-state/src/main/java/blue/myos/mini/externalstate/store/ResolvedBodyCodec.java.
 * Adaptation: records strict canonical versus resolved construction and checks
 * the entire round trip before persistence. No production format is declared.
 * Host-private, lossless storage of a body, not Blue wire or a BlueId.
 * Ordinary Blue serialization can omit resolved metadata beside a reference.
 * This format retains it, schema keyword nodes, scalar types and null/empty
 * distinctions. Construction uses only public Node/Schema APIs; no Java object
 * deserialization or class loading. Admission/evidence verification remains the
 * existing processor's responsibility. The byte budget is operational only.
 */
final class JournalFixtureBodyCodec {
    private static final int MAGIC = 0x4d594231; // MYB1: MyOS resolved body format 1
    private final int maxBytes;

    JournalFixtureBodyCodec(int maxBytes) {
        if (maxBytes < 4) throw new IllegalArgumentException("Body byte budget must be at least 4");
        this.maxBytes = maxBytes;
    }

    public byte[] encode(FrozenNode body) {
        Objects.requireNonNull(body, "body");
        Node detached = body.toNode();
        boolean canonical = body.isStrictCanonical();
        FrozenNode reconstructed = canonical ? FrozenNode.fromNode(detached) : FrozenNode.fromResolvedNode(detached);
        if (!body.resolvedStructuralKey().equals(reconstructed.resolvedStructuralKey())) {
            throw new IllegalArgumentException("Fixture requires uniform canonical or normalized resolved construction");
        }
        var bytes = new LimitedBuffer(maxBytes);
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeBoolean(canonical);
            writeNode(output, detached);
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory body encoding failed", impossible);
        }
        return bytes.toByteArray();
    }

    public FrozenNode decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > maxBytes) throw capacity();
        try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw invalid("Unsupported body format");
            boolean canonical = readBoolean(input);
            Node body = readNode(input);
            if (body == null || input.available() != 0) throw invalid("Missing body or trailing bytes");
            return canonical ? FrozenNode.fromNode(body) : FrozenNode.fromResolvedNode(body);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Truncated or invalid resolved body", failure);
        }
    }

    private static void writeNode(DataOutputStream out, Node node) throws IOException {
        out.writeBoolean(node != null);
        if (node == null) return;
        writeText(out, node.getName());
        writeText(out, node.getDescription());
        writeNode(out, node.getType());
        writeNode(out, node.getItemType());
        writeNode(out, node.getKeyType());
        writeNode(out, node.getValueType());
        writeValue(out, node.getRawValue());
        writeNodes(out, node.getItems());
        Map<String, Node> properties = node.getProperties();
        out.writeInt(properties == null ? -1 : properties.size());
        if (properties != null) for (var property : properties.entrySet()) {
            writeText(out, property.getKey());
            writeNode(out, property.getValue());
        }
        writeNode(out, node.getContracts());
        writeText(out, node.getBlueId());
        writeSchema(out, node.getSchema());
        writeText(out, node.getMergePolicy());
        writeText(out, node.getPreviousBlueId());
        out.writeBoolean(node.getPosition() != null);
        if (node.getPosition() != null) out.writeInt(node.getPosition());
        writeNode(out, node.getBlue());
        out.writeBoolean(node.isInlineValue());
    }

    private static Node readNode(DataInputStream in) throws IOException {
        if (!readBoolean(in)) return null;
        var node = new Node().name(readText(in)).description(readText(in))
                .type(readNode(in)).itemType(readNode(in)).keyType(readNode(in)).valueType(readNode(in))
                .value(readValue(in)).items(readNodes(in));
        int size = count(in, true);
        if (size >= 0) {
            var properties = new LinkedHashMap<String, Node>();
            for (int index = 0; index < size; index++) {
                String key = requiredText(in);
                if (properties.containsKey(key)) throw invalid("Duplicate body property");
                properties.put(key, readNode(in));
            }
            node.properties(properties);
        }
        node.contracts(readNode(in)).blueId(readText(in)).schema(readSchema(in))
                .mergePolicy(readText(in)).previousBlueId(readText(in));
        if (readBoolean(in)) node.position(in.readInt());
        return node.blue(readNode(in)).inlineValue(readBoolean(in));
    }

    private static void writeSchema(DataOutputStream out, Schema schema) throws IOException {
        out.writeBoolean(schema != null);
        if (schema == null) return;
        writeText(out, schema.getBlueId());
        for (Node keyword : java.util.Arrays.asList(schema.getRequired(), schema.getMinLength(),
                schema.getMaxLength(), schema.getMinimum(), schema.getMaximum(), schema.getExclusiveMinimum(),
                schema.getExclusiveMaximum(), schema.getMultipleOf(), schema.getMinItems(), schema.getMaxItems(),
                schema.getUniqueItems(), schema.getMinFields(), schema.getMaxFields())) writeNode(out, keyword);
        writeNodes(out, schema.getEnum());
    }

    private static Schema readSchema(DataInputStream in) throws IOException {
        if (!readBoolean(in)) return null;
        return new Schema().blueId(readText(in)).required(readNode(in)).minLength(readNode(in))
                .maxLength(readNode(in)).minimum(readNode(in)).maximum(readNode(in))
                .exclusiveMinimum(readNode(in)).exclusiveMaximum(readNode(in)).multipleOf(readNode(in))
                .minItems(readNode(in)).maxItems(readNode(in)).uniqueItems(readNode(in))
                .minFields(readNode(in)).maxFields(readNode(in)).enumValues(readNodes(in));
    }

    private static void writeNodes(DataOutputStream out, List<Node> nodes) throws IOException {
        out.writeInt(nodes == null ? -1 : nodes.size());
        if (nodes != null) for (Node node : nodes) writeNode(out, node);
    }

    private static List<Node> readNodes(DataInputStream in) throws IOException {
        int size = count(in, true);
        if (size < 0) return null;
        var nodes = new ArrayList<Node>();
        for (int index = 0; index < size; index++) nodes.add(readNode(in));
        return nodes;
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        out.writeInt(text == null ? -1 : text.length());
        // Exact UTF-16 units, without UTF-8 replacement or DataOutput's 64KiB limit.
        if (text != null) for (int index = 0; index < text.length(); index++) out.writeChar(text.charAt(index));
    }

    private static String readText(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size == -1) return null;
        if (size < 0 || size > in.available() / 2) throw invalid("Invalid body text length");
        char[] chars = new char[size];
        for (int index = 0; index < size; index++) chars[index] = in.readChar();
        return new String(chars);
    }

    private static String requiredText(DataInputStream in) throws IOException {
        String value = readText(in);
        if (value == null) throw invalid("Required body text is absent");
        return value;
    }

    private static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) { out.writeByte(0); return; }
        if (value instanceof String text) { out.writeByte(1); writeText(out, text); }
        else if (value instanceof Boolean flag) { out.writeByte(2); out.writeBoolean(flag); }
        else if (value instanceof BigInteger integer) { out.writeByte(3); writeText(out, integer.toString()); }
        else if (value instanceof BigDecimal decimal) { out.writeByte(4); writeText(out, decimal.toString()); }
        else if (value instanceof Byte number) { out.writeByte(5); out.writeByte(number); }
        else if (value instanceof Short number) { out.writeByte(6); out.writeShort(number); }
        else if (value instanceof Integer number) { out.writeByte(7); out.writeInt(number); }
        else if (value instanceof Long number) { out.writeByte(8); out.writeLong(number); }
        else if (value instanceof Float number) { out.writeByte(9); out.writeInt(Float.floatToRawIntBits(number)); }
        else if (value instanceof Double number) { out.writeByte(10); out.writeLong(Double.doubleToRawLongBits(number)); }
        else if (value instanceof Character character) { out.writeByte(11); out.writeChar(character); }
        else if (value instanceof List<?> list) {
            out.writeByte(12); out.writeInt(list.size());
            for (Object item : list) writeValue(out, item);
        } else if (value instanceof Map<?, ?> map) {
            out.writeByte(13); out.writeInt(map.size());
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid("Non-text raw map key");
                writeText(out, key); writeValue(out, entry.getValue());
            }
        } else if (value.getClass().isArray()) {
            out.writeByte(14); writeText(out, arrayType(value.getClass().getComponentType()));
            out.writeInt(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) writeValue(out, Array.get(value, index));
        } else throw invalid("Unsupported non-Blue Java payload: " + value.getClass().getName());
    }

    private static Object readValue(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> requiredText(in);
            case 2 -> readBoolean(in);
            case 3 -> new BigInteger(requiredText(in));
            case 4 -> new BigDecimal(requiredText(in));
            case 5 -> in.readByte();
            case 6 -> in.readShort();
            case 7 -> in.readInt();
            case 8 -> in.readLong();
            case 9 -> Float.intBitsToFloat(in.readInt());
            case 10 -> Double.longBitsToDouble(in.readLong());
            case 11 -> in.readChar();
            case 12 -> {
                int size = count(in, false);
                var values = new ArrayList<>();
                for (int index = 0; index < size; index++) values.add(readValue(in));
                yield values;
            }
            case 13 -> {
                int size = count(in, false);
                var values = new LinkedHashMap<String, Object>();
                for (int index = 0; index < size; index++) {
                    String key = requiredText(in);
                    if (values.containsKey(key)) throw invalid("Duplicate raw map key");
                    values.put(key, readValue(in));
                }
                yield values;
            }
            case 14 -> {
                Class<?> component = arrayType(requiredText(in));
                int size = count(in, false);
                Object values = Array.newInstance(component, size);
                for (int index = 0; index < size; index++) Array.set(values, index, readValue(in));
                yield values;
            }
            default -> throw invalid("Unknown raw value tag");
        };
    }

    private static String arrayType(Class<?> type) {
        if (type.isArray()) return "[" + arrayType(type.getComponentType());
        String name = type.getName();
        arrayType(name); // Validate before writing; no arbitrary class resolution.
        return name;
    }

    private static Class<?> arrayType(String name) {
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') dimensions++;
        if (dimensions > 254) throw invalid("Raw array exceeds JVM dimension limit");
        Class<?> base = switch (name.substring(dimensions)) {
            case "byte" -> byte.class; case "short" -> short.class; case "int" -> int.class;
            case "long" -> long.class; case "float" -> float.class; case "double" -> double.class;
            case "boolean" -> boolean.class; case "char" -> char.class;
            case "java.lang.Object" -> Object.class; case "java.lang.String" -> String.class;
            case "java.lang.Byte" -> Byte.class; case "java.lang.Short" -> Short.class;
            case "java.lang.Integer" -> Integer.class; case "java.lang.Long" -> Long.class;
            case "java.lang.Float" -> Float.class; case "java.lang.Double" -> Double.class;
            case "java.lang.Boolean" -> Boolean.class; case "java.lang.Character" -> Character.class;
            case "java.math.BigInteger" -> BigInteger.class; case "java.math.BigDecimal" -> BigDecimal.class;
            default -> throw invalid("Unsupported raw array type: " + name);
        };
        return dimensions == 0 ? base : Array.newInstance(base, new int[dimensions]).getClass();
    }

    private static int count(DataInputStream in, boolean nullable) throws IOException {
        int count = in.readInt();
        if (count < (nullable ? -1 : 0) || count > in.available()) throw invalid("Invalid body collection length");
        return count;
    }

    private static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte();
        if (value > 1) throw invalid("Invalid body Boolean");
        return value == 1;
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private IllegalArgumentException capacity() {
        return new IllegalArgumentException("Fixture body capacity exceeded: " + maxBytes);
    }

    private static final class LimitedBuffer extends ByteArrayOutputStream {
        private final int maximum;
        private LimitedBuffer(int maximum) { this.maximum = maximum; }
        private void check(int length) {
            if (length > maximum - count) throw new IllegalArgumentException("Fixture body capacity exceeded: " + maximum);
        }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] values, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, values.length);
            check(length); super.write(values, offset, length);
        }
    }
}
