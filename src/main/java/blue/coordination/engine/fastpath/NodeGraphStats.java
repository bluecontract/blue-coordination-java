package blue.coordination.engine.fastpath;

import blue.language.model.Node;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Allocation-conscious graph counters used by strict hot-path budgets. */
public final class NodeGraphStats {
    private final long nodes;
    private final long references;
    private final long scalarBytes;

    public NodeGraphStats(long nodes, long references, long scalarBytes) {
        if (nodes < 0L || references < 0L || scalarBytes < 0L
                || references > nodes) {
            throw new IllegalArgumentException("Invalid graph statistics");
        }
        this.nodes = nodes;
        this.references = references;
        this.scalarBytes = scalarBytes;
    }

    public long nodes() { return nodes; }
    public long references() { return references; }
    public long scalarBytes() { return scalarBytes; }

    public static NodeGraphStats measure(Node root) {
        if (root == null) return new NodeGraphStats(0L, 0L, 0L);
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        ArrayDeque<Node> stack = new ArrayDeque<Node>();
        stack.push(root);
        long nodes = 0L;
        long references = 0L;
        long scalarBytes = 0L;
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            if (!visited.add(node)) continue;
            nodes++;
            if (node.isReferenceOnly()) references++;
            Object value = node.getRawValue();
            if (value != null) scalarBytes += value.toString().length() * 2L;
            push(stack, node.getType());
            push(stack, node.getItemType());
            push(stack, node.getKeyType());
            push(stack, node.getValueType());
            push(stack, node.getContracts());
            push(stack, node.getBlue());
            if (node.getItems() != null) {
                for (Node child : node.getItems()) push(stack, child);
            }
            Map<String, Node> properties = node.getProperties();
            if (properties != null) {
                for (Map.Entry<String, Node> entry : properties.entrySet()) {
                    scalarBytes += entry.getKey().length() * 2L;
                    push(stack, entry.getValue());
                }
            }
        }
        return new NodeGraphStats(nodes, references, scalarBytes);
    }

    public double nodeReductionAgainst(NodeGraphStats full) {
        NodeGraphStats checked = Objects.requireNonNull(full, "full");
        if (checked.nodes == 0L) return 0.0d;
        return 1.0d - ((double) nodes / (double) checked.nodes);
    }

    private static void push(ArrayDeque<Node> stack, Node node) {
        if (node != null) stack.push(node);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof NodeGraphStats)) return false;
        NodeGraphStats other = (NodeGraphStats) value;
        return nodes == other.nodes
                && references == other.references
                && scalarBytes == other.scalarBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Long.valueOf(nodes),
                Long.valueOf(references),
                Long.valueOf(scalarBytes));
    }

    @Override
    public String toString() {
        return "NodeGraphStats{nodes=" + nodes
                + ", references=" + references
                + ", scalarBytes=" + scalarBytes + '}';
    }
}
