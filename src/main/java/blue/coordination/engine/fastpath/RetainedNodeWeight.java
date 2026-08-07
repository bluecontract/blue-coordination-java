package blue.coordination.engine.fastpath;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Allocation-light retained-weight estimate for engine-owned mutable Nodes.
 *
 * <p>The constants intentionally follow the conservative object model used
 * by {@code FrozenNode.approximateRetainedWeightBytes()}. Shared objects are
 * counted once by identity. The estimate never serializes, hashes or clones a
 * Node, so cache accounting does not reintroduce semantic work on the commit
 * path.</p>
 */
public final class RetainedNodeWeight {

    private static final long NODE_BYTES = 112L;
    private static final long SCHEMA_BYTES = 80L;
    private static final long STRING_BYTES = 48L;
    private static final long LIST_BYTES = 32L;
    private static final long MAP_BYTES = 64L;
    private static final long MAP_ENTRY_BYTES = 40L;
    private static final long ARRAY_BYTES = 24L;
    private static final long REFERENCE_BYTES = 8L;

    private RetainedNodeWeight() {
    }

    /** Estimates one or more mutable graphs with identity de-duplication. */
    public static long approximateRetainedWeightBytes(Node... roots) {
        Accumulator accounting = new Accumulator();
        ArrayDeque<Node> pending = new ArrayDeque<Node>();
        if (roots != null) {
            for (Node root : roots) {
                if (root != null) pending.addLast(root);
            }
        }
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (!accounting.addShallow(node)) continue;
            pushOrdinaryChildren(node, pending);
        }
        return Math.max(1L, accounting.retainedWeightBytes());
    }

    /** Creates accounting which can piggyback on an existing graph walk. */
    static Accumulator accumulator() {
        return new Accumulator();
    }

    private static void pushOrdinaryChildren(
            Node node, ArrayDeque<Node> pending) {
        if (node.getType() != null) pending.addLast(node.getType());
        if (node.getItemType() != null) {
            pending.addLast(node.getItemType());
        }
        if (node.getKeyType() != null) pending.addLast(node.getKeyType());
        if (node.getValueType() != null) {
            pending.addLast(node.getValueType());
        }
        if (node.getContracts() != null) {
            pending.addLast(node.getContracts());
        }
        if (node.getBlue() != null) pending.addLast(node.getBlue());
        if (node.getItems() != null) pending.addAll(node.getItems());
        if (node.getProperties() != null) {
            pending.addAll(node.getProperties().values());
        }
    }

    /** Mutable request-local estimator; it never escapes into cache keys. */
    static final class Accumulator {
        private final IdentityHashMap<Object, Boolean> seen =
                new IdentityHashMap<Object, Boolean>();
        private long retainedWeightBytes;

        /**
         * Accounts for a node and directly owned values and containers.
         * Ordinary Node children are left to the caller's existing walk;
         * schema keyword children are included here because that walk does
         * not visit them.
         */
        boolean addShallow(Node supplied) {
            Node node = Objects.requireNonNull(supplied, "node");
            if (seen.put(node, Boolean.TRUE) != null) return false;
            add(NODE_BYTES);
            addString(node.getName());
            addString(node.getDescription());
            addValue(node.getRawValue());
            addString(node.getBlueId());
            addString(node.getMergePolicy());
            addString(node.getPreviousBlueId());
            addListContainer(node.getItems());
            addMapContainer(node.getProperties());
            addSchema(node.getSchema());
            return true;
        }

        long retainedWeightBytes() {
            return retainedWeightBytes;
        }

        private void addSchema(Schema schema) {
            if (schema == null || seen.put(schema, Boolean.TRUE) != null) {
                return;
            }
            add(SCHEMA_BYTES);
            addString(schema.getBlueId());
            ArrayDeque<Node> pending = new ArrayDeque<Node>();
            addIfPresent(pending, schema.getRequired());
            addIfPresent(pending, schema.getMinLength());
            addIfPresent(pending, schema.getMaxLength());
            addIfPresent(pending, schema.getMinimum());
            addIfPresent(pending, schema.getMaximum());
            addIfPresent(pending, schema.getExclusiveMinimum());
            addIfPresent(pending, schema.getExclusiveMaximum());
            addIfPresent(pending, schema.getMultipleOf());
            addIfPresent(pending, schema.getMinItems());
            addIfPresent(pending, schema.getMaxItems());
            addIfPresent(pending, schema.getUniqueItems());
            addIfPresent(pending, schema.getMinFields());
            addIfPresent(pending, schema.getMaxFields());
            List<Node> enumValues = schema.getEnum();
            addListContainer(enumValues);
            if (enumValues != null) pending.addAll(enumValues);
            while (!pending.isEmpty()) {
                Node node = pending.removeLast();
                if (!addShallow(node)) continue;
                pushOrdinaryChildren(node, pending);
            }
        }

        private void addListContainer(List<?> values) {
            if (values == null
                    || seen.put(values, Boolean.TRUE) != null) {
                return;
            }
            add(LIST_BYTES);
            add(saturatedMultiply(REFERENCE_BYTES, values.size()));
        }

        private void addMapContainer(Map<?, ?> values) {
            if (values == null
                    || seen.put(values, Boolean.TRUE) != null) {
                return;
            }
            add(MAP_BYTES);
            add(saturatedMultiply(MAP_ENTRY_BYTES, values.size()));
            for (Object key : values.keySet()) {
                if (key instanceof String) addString((String) key);
            }
        }

        private void addValue(Object value) {
            if (value == null) return;
            if (value instanceof String) {
                addString((String) value);
                return;
            }
            if (seen.put(value, Boolean.TRUE) != null) return;
            if (value instanceof BigInteger) {
                add(48L + 4L * ((((BigInteger) value).abs().bitLength()
                        + 31L) / 32L));
                return;
            }
            if (value instanceof BigDecimal) {
                add(64L);
                addValue(((BigDecimal) value).unscaledValue());
                return;
            }
            if (value instanceof Boolean) {
                add(16L);
                return;
            }
            if (value instanceof Number) {
                add(24L);
                return;
            }
            if (value instanceof List) {
                List<?> values = (List<?>) value;
                add(LIST_BYTES);
                add(saturatedMultiply(REFERENCE_BYTES, values.size()));
                for (Object item : values) addValue(item);
                return;
            }
            if (value instanceof Map) {
                Map<?, ?> values = (Map<?, ?>) value;
                add(MAP_BYTES);
                add(saturatedMultiply(MAP_ENTRY_BYTES, values.size()));
                for (Map.Entry<?, ?> entry : values.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        addString((String) entry.getKey());
                    } else {
                        add(32L);
                    }
                    addValue(entry.getValue());
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                add(ARRAY_BYTES);
                add(saturatedMultiply(
                        value.getClass().getComponentType().isPrimitive()
                                ? 8L : REFERENCE_BYTES,
                        length));
                if (!value.getClass().getComponentType().isPrimitive()) {
                    for (int index = 0; index < length; index++) {
                        addValue(Array.get(value, index));
                    }
                }
                return;
            }
            // Unknown immutable scalar implementation.
            add(64L);
        }

        private void addString(String value) {
            if (value == null || seen.put(value, Boolean.TRUE) != null) {
                return;
            }
            add(STRING_BYTES + 2L * value.length());
        }

        private void add(long value) {
            retainedWeightBytes = saturatedAdd(
                    retainedWeightBytes, value);
        }
    }

    private static void addIfPresent(
            ArrayDeque<Node> pending, Node value) {
        if (value != null) pending.addLast(value);
    }

    static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right
                ? Long.MAX_VALUE
                : left + right;
    }

    static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right
                ? Long.MAX_VALUE
                : left * right;
    }
}
