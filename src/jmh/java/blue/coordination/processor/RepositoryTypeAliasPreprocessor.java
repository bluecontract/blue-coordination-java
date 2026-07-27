package blue.coordination.processor;

import blue.language.model.Node;
import blue.repo.BlueRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Benchmark-fixture migration helper for preview repository aliases.
 *
 * <p>This source set is not included in the published runtime artifact.</p>
 */
final class RepositoryTypeAliasPreprocessor {
    private final Map<String, String> aliases;

    RepositoryTypeAliasPreprocessor(BlueRepository repository) {
        this.aliases = repository != null
                ? new LinkedHashMap<String, String>(
                        repository.typeAliases())
                : new LinkedHashMap<String, String>();
    }

    Node preprocess(Node node) {
        if (node == null) {
            return null;
        }
        Node copy = node.clone();
        resolve(copy);
        return copy;
    }

    private void resolve(Node node) {
        if (node == null) {
            return;
        }
        String blueId = aliasFor(node.getBlueId());
        if (blueId != null) {
            node.blueId(blueId);
        }

        node.type(resolveTypeNode(node.getType()));
        node.itemType(resolveTypeNode(node.getItemType()));
        node.keyType(resolveTypeNode(node.getKeyType()));
        node.valueType(resolveTypeNode(node.getValueType()));

        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                resolve(item);
            }
        }
        if (node.getProperties() != null) {
            for (Node value : node.getProperties().values()) {
                resolve(value);
            }
        }
        resolve(node.getContracts());
        resolve(node.getBlue());
    }

    private Node resolveTypeNode(Node typeNode) {
        if (typeNode == null) {
            return null;
        }
        String blueId = aliasFor(inlineText(typeNode));
        if (blueId != null) {
            return new Node().blueId(blueId);
        }
        resolve(typeNode);
        return typeNode;
    }

    private String inlineText(Node node) {
        if (node == null || !node.isInlineValue()
                || node.getValue() == null) {
            return null;
        }
        return String.valueOf(node.getValue());
    }

    private String aliasFor(String value) {
        return value != null ? aliases.get(value) : null;
    }
}
