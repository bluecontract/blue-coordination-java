package blue.coordination.processor.workflow;

import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgramKey;
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
    private final BexCompiledProgramKey sourceIdentity;
    private final String entry;
    private final long gasLimit;
    private final boolean emitEvents;
    private final boolean returnResult;
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
        this.sourceIdentity = BexCompiledProgramKey.from(source);
        this.entry = entry;
        this.gasLimit = gasLimit;
        this.emitEvents = emitEvents;
        this.returnResult = returnResult;
        this.approximateWeightBytes = approximateWeight(rawStepNode,
                rawDefinitionNode,
                programNode,
                definitionNode,
                entry,
                sourceIdentity);
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

    BexCompiledProgramKey sourceIdentity() {
        return sourceIdentity;
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

    long approximateWeightBytes() {
        return approximateWeightBytes;
    }

    private static long approximateWeight(FrozenNode rawStepNode,
                                          FrozenNode rawDefinitionNode,
                                          FrozenNode programNode,
                                          FrozenNode definitionNode,
                                          String entry,
                                          BexCompiledProgramKey sourceIdentity) {
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
        weight = saturatedAdd(weight, stringWeight(entry));
        weight = saturatedAdd(weight, stringWeight(sourceIdentity.programIdentity()));
        weight = saturatedAdd(weight, stringWeight(sourceIdentity.definitionIdentity()));
        weight = saturatedAdd(weight, stringWeight(sourceIdentity.entryName()));
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
