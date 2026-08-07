package blue.coordination.engine.fastpath;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves retained references in time proportional to the PROCESS result's
 * changed representation, not to the entire prior Root.
 *
 * <p>The PROCESS result is engine/request-owned. This resolver rewrites that
 * object in place and structurally shares retained immutable subtrees from the
 * prepared epoch context. There is no full prior-Root scan and no deep clone
 * of either Root. Downstream code must treat the returned DAG as read-only.
 * A public result, if requested, is copied once at the public boundary.</p>
 */
public final class IndexedRetainedReferenceResolver {
    private final RetainedReferenceIndex retained;
    private final Object owner;

    public IndexedRetainedReferenceResolver(
            RetainedReferenceIndex retained, Object owner) {
        this.retained = Objects.requireNonNull(retained, "retained");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    public Node resolveRequestOwned(Node processResult) {
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        return resolve(
                Objects.requireNonNull(processResult, "processResult"),
                visited,
                new LinkedHashSet<String>());
    }

    private Node resolve(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        if (node.isReferenceOnly()) {
            String blueId = node.getBlueId();
            if (!activeBlueIds.add(blueId)) return node;
            Node expanded = retained.borrowExpandedTrusted(blueId, owner);
            activeBlueIds.remove(blueId);
            return expanded == null ? node : expanded;
        }
        if (!visited.add(node)) return node;

        replaceTypeIfChanged(node, visited, activeBlueIds);
        replaceItemTypeIfChanged(node, visited, activeBlueIds);
        replaceKeyTypeIfChanged(node, visited, activeBlueIds);
        replaceValueTypeIfChanged(node, visited, activeBlueIds);
        replaceContractsIfChanged(node, visited, activeBlueIds);
        replaceBlueIfChanged(node, visited, activeBlueIds);
        if (node.getItems() != null) {
            List<Node> original = node.getItems();
            List<Node> resolved = null;
            for (int index = 0; index < original.size(); index++) {
                Node before = original.get(index);
                Node after = resolve(before, visited, activeBlueIds);
                if (before != after) {
                    if (resolved == null) {
                        resolved = new ArrayList<Node>(original);
                    }
                    resolved.set(index, after);
                }
            }
            if (resolved != null) node.items(resolved);
        }
        if (node.getProperties() != null) {
            Map<String, Node> original = node.getProperties();
            Map<String, Node> resolved = null;
            for (Map.Entry<String, Node> entry
                    : original.entrySet()) {
                Node before = entry.getValue();
                Node after = resolve(before, visited, activeBlueIds);
                if (before != after) {
                    if (resolved == null) {
                        resolved = new LinkedHashMap<String, Node>(original);
                    }
                    resolved.put(entry.getKey(), after);
                }
            }
            if (resolved != null) node.properties(resolved);
        }
        return node;
    }

    private void replaceTypeIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getType();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.type(after);
    }

    private void replaceItemTypeIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getItemType();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.itemType(after);
    }

    private void replaceKeyTypeIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getKeyType();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.keyType(after);
    }

    private void replaceValueTypeIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getValueType();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.valueType(after);
    }

    private void replaceContractsIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getContracts();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.contracts(after);
    }

    private void replaceBlueIfChanged(
            Node node, Set<Node> visited, Set<String> activeBlueIds) {
        Node before = node.getBlue();
        if (before == null) return;
        Node after = resolve(before, visited, activeBlueIds);
        if (before != after) node.blue(after);
    }
}
