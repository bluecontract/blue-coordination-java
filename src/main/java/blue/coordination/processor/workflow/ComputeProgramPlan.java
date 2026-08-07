package blue.coordination.processor.workflow;

import blue.bex.api.BexProgramSource;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, reusable input plan for one exact Compute program and definition.
 * Compiled BEX artifacts deliberately remain owned by BEX's bounded cache.
 */
final class ComputeProgramPlan {
    private static final long PLAN_OVERHEAD_BYTES = 256L;
    private static final long NODE_OVERHEAD_BYTES = 96L;
    private static final long COLLECTION_ENTRY_OVERHEAD_BYTES = 32L;

    private final FrozenNode programNode;
    private final FrozenNode definitionNode;
    private final BexProgramSource source;
    private final String entry;
    private final long gasLimit;
    private final boolean emitEvents;
    private final boolean returnResult;
    private final boolean processingEventRequired;
    private final long approximateWeightBytes;

    ComputeProgramPlan(FrozenNode programNode,
                       FrozenNode definitionNode,
                       BexProgramSource source,
                       String entry,
                       long gasLimit,
                       boolean emitEvents,
                       boolean returnResult,
                       FrozenNode rawStepNode,
                       FrozenNode rawDefinitionNode) {
        if (programNode == null) {
            throw new IllegalArgumentException("programNode must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (gasLimit <= 0L) {
            throw new IllegalArgumentException("gasLimit must be positive");
        }
        this.programNode = programNode;
        this.definitionNode = definitionNode;
        this.source = source;
        this.entry = entry;
        this.gasLimit = gasLimit;
        this.emitEvents = emitEvents;
        this.returnResult = returnResult;
        this.processingEventRequired =
                containsProcessingEventReference(
                        programNode)
                        || containsProcessingEventReference(
                        definitionNode);
        this.approximateWeightBytes = approximateWeight(rawStepNode,
                rawDefinitionNode,
                programNode,
                definitionNode,
                source.definitionNode().orElse(null),
                entry,
                source.kind());
    }

    FrozenNode programNode() {
        return programNode;
    }

    FrozenNode definitionNode() {
        return definitionNode;
    }

    BexProgramSource source() {
        return source;
    }

    String entry() {
        return entry;
    }

    long gasLimit() {
        return gasLimit;
    }

    boolean emitEvents() {
        return emitEvents;
    }

    boolean returnResult() {
        return returnResult;
    }

    boolean processingEventRequired() {
        return processingEventRequired;
    }

    long approximateWeightBytes() {
        return approximateWeightBytes;
    }

    private static long approximateWeight(FrozenNode rawStepNode,
                                          FrozenNode rawDefinitionNode,
                                          FrozenNode programNode,
                                          FrozenNode definitionNode,
                                          FrozenNode sourceDefinitionNode,
                                          String entry,
                                          BexProgramSource.Kind sourceKind) {
        long weight = PLAN_OVERHEAD_BYTES;
        // Raw nodes approximate the independently retained structural cache
        // keys, while normalized nodes approximate the plan/source graph.
        // Deduplicate sharing within each retained graph, not across them.
        IdentityHashMap<FrozenNode, Boolean> keyNodes = new IdentityHashMap<FrozenNode, Boolean>();
        weight = saturatedAdd(weight, nodeWeight(rawStepNode, keyNodes));
        weight = saturatedAdd(weight, nodeWeight(rawDefinitionNode, keyNodes));
        IdentityHashMap<FrozenNode, Boolean> planNodes = new IdentityHashMap<FrozenNode, Boolean>();
        weight = saturatedAdd(weight, nodeWeight(programNode, planNodes));
        weight = saturatedAdd(weight, nodeWeight(definitionNode, planNodes));
        weight = saturatedAdd(weight,
                nodeWeight(sourceDefinitionNode, planNodes));
        weight = saturatedAdd(weight, stringWeight(entry));
        weight = saturatedAdd(weight,
                stringWeight(sourceKind.name()));
        return Math.max(PLAN_OVERHEAD_BYTES, weight);
    }

    private static long nodeWeight(FrozenNode root,
                                   IdentityHashMap<FrozenNode, Boolean> visited) {
        if (root == null) {
            return 0L;
        }
        long weight = 0L;
        Deque<FrozenNode> pending = new ArrayDeque<FrozenNode>();
        pending.push(root);
        while (!pending.isEmpty()) {
            FrozenNode node = pending.pop();
            if (visited.put(node, Boolean.TRUE) != null) {
                continue;
            }
            weight = saturatedAdd(weight, NODE_OVERHEAD_BYTES);
            weight = saturatedAdd(weight, stringWeight(node.getName()));
            weight = saturatedAdd(weight, stringWeight(node.getDescription()));
            weight = saturatedAdd(weight, stringWeight(node.getReferenceBlueId()));
            weight = saturatedAdd(weight, stringWeight(node.getMergePolicy()));
            weight = saturatedAdd(weight, stringWeight(node.getPreviousBlueId()));
            Object value = node.getValue();
            if (value instanceof String) {
                weight = saturatedAdd(weight, stringWeight((String) value));
            } else if (value != null) {
                weight = saturatedAdd(weight, 32L);
            }
            pushIfPresent(pending, node.getType());
            pushIfPresent(pending, node.getItemType());
            pushIfPresent(pending, node.getKeyType());
            pushIfPresent(pending, node.getValueType());
            pushIfPresent(pending, node.getContracts());
            pushIfPresent(pending, node.getBlue());
            List<FrozenNode> items = node.getItems();
            if (items != null) {
                weight = saturatedAdd(weight,
                        saturatedMultiply(COLLECTION_ENTRY_OVERHEAD_BYTES, items.size()));
                for (FrozenNode item : items) {
                    pushIfPresent(pending, item);
                }
            }
            Map<String, FrozenNode> properties = node.getProperties();
            if (properties != null) {
                weight = saturatedAdd(weight,
                        saturatedMultiply(COLLECTION_ENTRY_OVERHEAD_BYTES, properties.size()));
                for (Map.Entry<String, FrozenNode> property : properties.entrySet()) {
                    weight = saturatedAdd(weight, stringWeight(property.getKey()));
                    pushIfPresent(pending, property.getValue());
                }
            }
        }
        return weight;
    }

    private static boolean containsProcessingEventReference(
            FrozenNode root) {
        if (root == null) {
            return false;
        }
        Deque<FrozenNode> pending =
                new ArrayDeque<FrozenNode>();
        IdentityHashMap<FrozenNode, Boolean> visited =
                new IdentityHashMap<FrozenNode, Boolean>();
        pending.add(root);
        while (!pending.isEmpty()) {
            FrozenNode current =
                    pending.removeFirst();
            if (current == null
                    || visited.put(
                    current, Boolean.TRUE) != null) {
                continue;
            }
            Object value = current.getValue();
            if (value instanceof String) {
                String text = (String) value;
                if ("processingEvent".equals(text)
                        || text.startsWith(
                        "processingEvent/")) {
                    return true;
                }
            }
            Map<String, FrozenNode> properties =
                    current.getProperties();
            if (properties != null) {
                if (properties.containsKey(
                        "$processingEvent")) {
                    return true;
                }
                pending.addAll(
                        properties.values());
            }
            List<FrozenNode> items =
                    current.getItems();
            if (items != null) {
                pending.addAll(items);
            }
        }
        return false;
    }

    private static void pushIfPresent(Deque<FrozenNode> pending, FrozenNode node) {
        if (node != null) {
            pending.push(node);
        }
    }

    private static long stringWeight(String value) {
        return value == null ? 0L : 40L + (2L * value.length());
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) {
            return left;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(long left, int right) {
        if (left <= 0L || right <= 0) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }
}
