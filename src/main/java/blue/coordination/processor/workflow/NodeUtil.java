package blue.coordination.processor.workflow;

import blue.language.model.Node;

import java.util.Map;

/**
 * Strict scalar and property accessors for mutable workflow input nodes.
 *
 * <p>This class mirrors {@link FrozenNodeUtil} at the authored-input boundary
 * and intentionally performs no type coercion or reference materialization.</p>
 */
final class NodeUtil {
    private NodeUtil() {
    }

    static Node property(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }

    static boolean isEmpty(Node node) {
        return node == null
                || (node.getName() == null
                && node.getDescription() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getValue() == null
                && node.getItems() == null
                && empty(node.getProperties())
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null);
    }

    static Object rawScalar(Node node) {
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

    static String text(Node node) {
        Object raw = rawScalar(node);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException("Expected Text scalar");
        }
        return (String) raw;
    }

    static String textProperty(Node node, String key) {
        return text(property(node, key));
    }

    static boolean booleanProperty(
            Node node,
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

    private static boolean empty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

}
