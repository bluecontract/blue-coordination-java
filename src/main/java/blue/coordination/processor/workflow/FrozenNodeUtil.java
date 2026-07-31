package blue.coordination.processor.workflow;

import blue.language.model.Node;
import blue.language.processor.CoordinationProcessHeaderBridge;
import blue.language.snapshot.FrozenNode;

import java.math.BigInteger;

/**
 * Strict scalar and property accessors for immutable workflow snapshots.
 *
 * <p>The methods preserve the distinction between absence and the wrong Blue
 * scalar kind; callers receive deterministic validation failures instead of
 * Java coercions.</p>
 */
final class FrozenNodeUtil {
    private FrozenNodeUtil() {
    }

    static FrozenNode property(FrozenNode node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    static Node authoredOverlay(FrozenNode node) {
        if (node == null) {
            return null;
        }
        return CoordinationProcessHeaderBridge
                .canonicalExactCopy(
                        node.toNode());
    }

    static boolean isEmpty(FrozenNode node) {
        return node == null || (node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && (node.getProperties() == null
                || node.getProperties().isEmpty())
                && node.getContracts() == null
                && node.getReferenceBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null);
    }

    static Object rawScalar(FrozenNode node) {
        if (node == null) {
            return null;
        }
        if (node.getValue() != null) {
            return node.getValue();
        }
        if (node.getProperties() != null
                && node.getProperties().containsKey("value")) {
            return rawScalar(node.getProperties().get("value"));
        }
        return null;
    }

    static String text(FrozenNode node) {
        Object raw = rawScalar(node);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("Expected Text scalar");
        }
        return (String) raw;
    }

    static String textProperty(FrozenNode node, String key) {
        return text(property(node, key));
    }

    static boolean booleanProperty(
            FrozenNode node,
            String key,
            boolean defaultValue) {
        Object raw = rawScalar(property(node, key));
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean) {
            return ((Boolean) raw).booleanValue();
        }
        throw new IllegalArgumentException("Expected Boolean scalar for " + key);
    }

    static Long integer(FrozenNode node) {
        Object raw = rawScalar(node);
        if (raw instanceof BigInteger) {
            return Long.valueOf(((BigInteger) raw).longValueExact());
        }
        if (raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long) {
            return Long.valueOf(((Number) raw).longValue());
        }
        if (raw == null) {
            return null;
        }
        throw new IllegalArgumentException("Expected Integer scalar");
    }
}
