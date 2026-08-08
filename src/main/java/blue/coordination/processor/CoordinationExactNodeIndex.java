package blue.coordination.processor;

import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invocation-local bottom-up identity index for exact ordinary Blue nodes.
 *
 * <p>Calling the direct calculator separately for every subtree repeatedly
 * walks all descendants and is quadratic for a deep document. This index
 * calculates each object occurrence once. A parent is hashed from the same
 * identity-equivalent shallow representation used by canonical direct-node
 * fragmentation, so already calculated child identities make parent work
 * proportional only to its direct width.</p>
 *
 * <p>The class is package private and retains caller-owned nodes only for the
 * duration of one immutable fragmentation blueprint. Consumers clone a node
 * before mutation; the retained references are never exposed publicly.</p>
 */
public final class CoordinationExactNodeIndex {

    private final IdentityHashMap<Node, String> identities =
            new IdentityHashMap<Node, String>();
    private final IdentityHashMap<Node, Boolean> active =
            new IdentityHashMap<Node, Boolean>();
    private final Map<String, Node> nodesByBlueId =
            new LinkedHashMap<String, Node>();
    private final IdentityHashMap<Node, Node> directFragments =
            new IdentityHashMap<Node, Node>();
    private long identityCalculationCount;

    /** Indexes an exact inline node and returns its strict direct identity. */
    public synchronized String blueId(Node supplied) {
        Node node = java.util.Objects.requireNonNull(
                supplied, "supplied");
        if (node.isReferenceOnly()) {
            return BlueIds.requireBlueIdOrCyclicMember(
                    node.getBlueId(), "supplied.blueId");
        }
        String retained = identities.get(node);
        if (retained != null) {
            return retained;
        }
        if (active.put(node, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(
                    "Inline object cycle cannot be indexed as exact Blue "
                            + "content");
        }
        try {
            Node direct = directNode(node);
            String calculated =
                    DirectBlueIdCalculator.calculateBlueId(direct);
            identityCalculationCount++;
            identities.put(node, calculated);
            directFragments.put(node, direct);
            nodesByBlueId.putIfAbsent(calculated, node);
            return calculated;
        } finally {
            active.remove(node);
        }
    }

    /** Returns the identity-equivalent shallow canonical representation. */
    synchronized Node directFragment(Node exactNode) {
        blueId(exactNode);
        Node direct = directFragments.get(exactNode);
        if (direct == null) {
            throw new IllegalArgumentException(
                    "A pure reference has no local direct fragment body");
        }
        return direct.clone();
    }

    /** Internal read-only identity map; retained nodes must not be mutated. */
    synchronized Map<String, Node> nodesByBlueId() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<String, Node>(nodesByBlueId));
    }

    /** Number of inline object occurrences actually hashed by this index. */
    public synchronized long identityCalculationCount() {
        return identityCalculationCount;
    }

    private Node directNode(Node source) {
        Node direct = new Node()
                .name(source.getName())
                .description(source.getDescription())
                .type(referenceFor(source.getType()))
                .itemType(referenceFor(source.getItemType()))
                .keyType(referenceFor(source.getKeyType()))
                .valueType(referenceFor(source.getValueType()))
                .value(source.getRawValue())
                .contracts(referenceFor(source.getContracts()))
                .blueId(source.getBlueId())
                .schema(directSchema(source.getSchema()))
                .mergePolicy(source.getMergePolicy())
                .previousBlueId(source.getPreviousBlueId())
                .position(source.getPosition())
                .blue(referenceFor(source.getBlue()))
                .inlineValue(source.isInlineValue())
                .preprocessingTransformationConfiguration(
                        source.isPreprocessingTransformationConfiguration());
        if (source.getItems() != null) {
            List<Node> items = new ArrayList<Node>(
                    source.getItems().size());
            for (Node item : source.getItems()) {
                items.add(referenceFor(item));
            }
            direct.items(items);
        }
        if (source.getProperties() != null) {
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> property
                    : source.getProperties().entrySet()) {
                properties.put(
                        property.getKey(),
                        referenceFor(property.getValue()));
            }
            direct.properties(properties);
        }
        return direct;
    }

    private Node referenceFor(Node child) {
        if (child == null) {
            return null;
        }
        String childBlueId = child.isReferenceOnly()
                ? BlueIds.requireBlueIdOrCyclicMember(
                        child.getBlueId(), "child.blueId")
                : blueId(child);
        return new Node().blueId(childBlueId);
    }

    private Schema directSchema(Schema source) {
        if (source == null) {
            return null;
        }
        if (source.isReferenceOnly()) {
            return new Schema().blueId(
                    BlueIds.requireBlueIdOrCyclicMember(
                            source.getBlueId(), "schema.blueId"));
        }
        /* Language's canonical direct-fragment profile keeps the closed
         * count/boolean schema keywords inline. Only numeric bounds and enum
         * entries may be decorated semantic child nodes and therefore become
         * references. Starting from the exact clone preserves typed scalar
         * spellings such as required: true. */
        Schema direct = source.clone()
                .minimum(schemaValue(source.getMinimum()))
                .maximum(schemaValue(source.getMaximum()))
                .exclusiveMinimum(
                        schemaValue(source.getExclusiveMinimum()))
                .exclusiveMaximum(
                        schemaValue(source.getExclusiveMaximum()))
                .multipleOf(schemaValue(source.getMultipleOf()));
        if (source.getEnum() != null) {
            List<Node> values = new ArrayList<Node>(
                    source.getEnum().size());
            for (Node value : source.getEnum()) {
                values.add(schemaValue(value));
            }
            direct.enumValues(values);
        }
        return direct;
    }

    private Node schemaValue(Node value) {
        if (value == null) {
            return null;
        }
        return CoordinationDocumentSplitter.isPlainSchemaScalar(value)
                ? value.clone()
                : referenceFor(value);
    }
}
