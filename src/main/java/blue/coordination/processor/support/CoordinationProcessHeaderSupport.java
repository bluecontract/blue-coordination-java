package blue.coordination.processor.support;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderUnavailableException;
import blue.language.registry.NodeProviderWrapper;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Coordination-owned exact PROCESS-header normalization and materialization.
 *
 * <p>The implementation uses only current public Language APIs. In
 * particular, callers provide the exact provider explicitly; this class does
 * not reach into a {@code DocumentProcessor}'s private snapshot manager.</p>
 */
public final class CoordinationProcessHeaderSupport {

    private CoordinationProcessHeaderSupport() {
    }

    /**
     * Materializes one exact reference through a strictly verified provider.
     * Typed provider outcomes remain distinguishable in the thrown failure.
     *
     * @param provider exact public provider boundary
     * @param reference exact pure reference
     * @return owned canonical exact content without a redundant root BlueId
     */
    public static Node materializeVerifiedExactReference(
            NodeProvider provider,
            Node reference) {
        Node checked = Objects.requireNonNull(reference, "reference");
        if (!checked.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "PROCESS header materialization requires a pure reference");
        }
        String expected = BlueIds.requirePlainBlueId(
                checked.getBlueId(),
                "reference.blueId");
        NodeProviderResult result = NodeProviderWrapper.wrap(
                Objects.requireNonNull(provider, "provider"))
                .fetchResultByBlueId(expected);
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new ProviderUnavailableException(
                    result.diagnostic().orElse(
                            "Verified PROCESS header provider is unavailable for "
                                    + expected));
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(
                    "Invalid PROCESS header evidence for " + expected
                            + ": "
                            + result.diagnostic().orElse("no diagnostic"));
        }
        if (result.outcome() == NodeProviderOutcome.NOT_FOUND) {
            throw new IllegalStateException(
                    "Verified PROCESS header evidence was not found for "
                            + expected);
        }
        List<Node> nodes = result.nodes();
        if (nodes.size() != 1) {
            throw new IllegalArgumentException(
                    "Verified PROCESS header provider returned "
                            + nodes.size() + " values for " + expected);
        }
        Node exact = canonicalExactCopy(nodes.get(0));
        if (exact.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Verified PROCESS header provider returned another pure reference for "
                            + expected);
        }
        String actual = DirectBlueIdCalculator.calculateBlueId(exact);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Verified PROCESS header content hashes to " + actual
                            + ", expected " + expected);
        }
        return exact;
    }

    /**
     * Returns an owned exact copy with resolved-provider provenance removed.
     * Nominal type definitions are restored to their exact authored
     * references, while anonymous type content remains inline.
     *
     * @param resolvedContent exact or resolved content owned by the caller
     * @return canonical exact defensive copy
     */
    public static Node canonicalExactCopy(Node resolvedContent) {
        Node exact = Objects.requireNonNull(
                resolvedContent,
                "resolvedContent").clone();
        clearMaterializationProvenance(
                exact,
                new IdentityHashMap<Node, Boolean>());
        return exact;
    }

    private static void clearMaterializationProvenance(
            Node node,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null
                || node.isReferenceOnly()) {
            return;
        }
        if (node.getBlueId() != null) {
            node.blueId(null);
        }
        node.type(nominalReference(node.getType()));
        node.itemType(nominalReference(node.getItemType()));
        node.keyType(nominalReference(node.getKeyType()));
        node.valueType(nominalReference(node.getValueType()));
        clearMaterializationProvenance(node.getType(), visited);
        clearMaterializationProvenance(node.getItemType(), visited);
        clearMaterializationProvenance(node.getKeyType(), visited);
        clearMaterializationProvenance(node.getValueType(), visited);
        clearMaterializationProvenance(node.getContracts(), visited);
        clearMaterializationProvenance(node.getBlue(), visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                clearMaterializationProvenance(child, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                clearMaterializationProvenance(child, visited);
            }
        }
        clearSchema(node.getSchema(), visited);
    }

    private static Node nominalReference(Node type) {
        return type != null
                && type.getBlueId() != null
                && !type.isReferenceOnly()
                ? new Node().blueId(type.getBlueId())
                : type;
    }

    private static void clearSchema(
            Schema schema,
            IdentityHashMap<Node, Boolean> visited) {
        if (schema == null || schema.isReferenceOnly()) {
            return;
        }
        if (schema.getBlueId() != null) {
            schema.blueId(null);
        }
        clearMaterializationProvenance(schema.getRequired(), visited);
        clearMaterializationProvenance(schema.getMinLength(), visited);
        clearMaterializationProvenance(schema.getMaxLength(), visited);
        clearMaterializationProvenance(schema.getMinimum(), visited);
        clearMaterializationProvenance(schema.getMaximum(), visited);
        clearMaterializationProvenance(
                schema.getExclusiveMinimum(), visited);
        clearMaterializationProvenance(
                schema.getExclusiveMaximum(), visited);
        clearMaterializationProvenance(schema.getMultipleOf(), visited);
        clearMaterializationProvenance(schema.getMinItems(), visited);
        clearMaterializationProvenance(schema.getMaxItems(), visited);
        clearMaterializationProvenance(schema.getUniqueItems(), visited);
        clearMaterializationProvenance(schema.getMinFields(), visited);
        clearMaterializationProvenance(schema.getMaxFields(), visited);
        if (schema.getEnum() != null) {
            for (Node child : schema.getEnum()) {
                clearMaterializationProvenance(child, visited);
            }
        }
    }
}
