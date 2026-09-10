package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces the minimal authored Compute/definition projection admitted by
 * hosted BEX.
 *
 * <p>Resolved inheritance and unrelated contract structure are deliberately
 * excluded from the reusable plan identity. Registered type identities and
 * authored program fields are retained exactly; this layer never rewrites
 * repository aliases or performs contract processing.</p>
 */
final class ComputeProgramNormalizer {
    private static final String NORMALIZATION_VERSION =
            "compute-program-v11|exact-definition-identity|canonical-bex-source"
                    + "|strict-statements|exact-field-presence";

    private final BexProcessingMetrics metrics;

    ComputeProgramNormalizer() {
        this(null);
    }

    ComputeProgramNormalizer(BexProcessingMetrics metrics) {
        this.metrics = metrics;
    }

    String normalizationVersion() {
        return NORMALIZATION_VERSION;
    }

    /**
     * Normalizes only the authored Compute projection of a frozen step. This
     * avoids materializing unrelated resolved-contract content. Registered
     * type identities are preserved exactly; no runtime alias rewriting is
     * applied. The resulting frozen plan is reused on every warm invocation.
     */
    FrozenNode program(FrozenNode stepNode) {
        if (metrics != null) {
            metrics.incrementComputeProgramNormalizations();
        }
        return FrozenNode.fromResolvedNode(program(frozenProgramInput(stepNode)));
    }

    FrozenNode definition(FrozenNode definitionNode) {
        if (metrics != null) {
            metrics.incrementComputeDefinitionNormalizations();
            metrics.incrementComputeDefinitionMaterializations();
        }
        if (definitionNode == null) {
            throw new IllegalArgumentException(
                    "definitionNode must not be null");
        }
        /*
         * A verified provider definition is already immutable exact content.
         * Projecting it into a new object would replace the authored BlueId
         * with a hash of the projection and discard metadata. BEX reads only
         * constants/functions, so retaining the exact source is both safe and
         * necessary for exact compiled-plan identity.
         */
        return definitionNode;
    }

    /**
     * Projects the executable fields of an already-verified exact definition.
     *
     * <p>The exact provider node remains the plan/cache identity returned by
     * {@link #definition(FrozenNode)}. BEX, however, requires its
     * {@code constants}, {@code functions}, function arguments, and statement
     * lists to be authored containers without inherited Blue metadata. Keep
     * those two concerns separate instead of discarding the provider identity
     * or asking BEX to interpret resolved contract structure.</p>
     */
    FrozenNode definitionSource(FrozenNode definitionNode) {
        return definitionSource(definitionNode, false);
    }

    FrozenNode definitionSource(FrozenNode definitionNode, boolean resolvedDefinition) {
        if (definitionNode == null) {
            throw new IllegalArgumentException(
                    "definitionNode must not be null");
        }
        if (FrozenNodeUtil.rawScalar(definitionNode) != null
                || definitionNode.getItems() != null) {
            return FrozenNode.fromResolvedNode(
                    canonicalStaticSource(definitionNode.toNode()));
        }
        Node input = frozenDefinitionInput(definitionNode);
        if (resolvedDefinition) {
            omitInheritedEmptyMap(input, definitionNode, "constants");
            omitInheritedEmptyMap(input, definitionNode, "functions");
        }
        return FrozenNode.fromResolvedNode(definitionSource(input));
    }

    private void omitInheritedEmptyMap(Node input, FrozenNode definition, String key) {
        FrozenNode field = FrozenNodeUtil.property(definition, key);
        FrozenNode inherited = FrozenNodeUtil.property(definition.getType(), key);
        if (field == null || inherited == null
                || !field.resolvedStructuralKey().equals(inherited.resolvedStructuralKey())) {
            return;
        }
        // Resolution adds optional Dictionary declarations even when the
        // definition supplies no entries. Only an unchanged inherited empty
        // declaration is absent executable input; malformed authored values
        // and containers must still reach the ordinary compiler checks.
        Node contents = field.toNode().name(null).description(null).type((Node) null);
        if (NodeUtil.isEmpty(contents)) {
            input.getProperties().remove(key);
        }
    }

    Node program(Node stepNode) {
        Node program = new Node();
        copyMetadata(program, stepNode);
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        putIfPresent(properties, "expr", NodeUtil.property(stepNode, "expr"));
        putIfPresent(properties, "do", normalizeDo(NodeUtil.property(stepNode, "do")));
        putIfMeaningful(properties, "definition", NodeUtil.property(stepNode, "definition"));
        putIfPresent(properties, "entry", NodeUtil.property(stepNode, "entry"));
        putIfPresent(properties, "constants", authoredMap(NodeUtil.property(stepNode, "constants")));
        putIfPresent(properties, "functions", normalizeFunctions(NodeUtil.property(stepNode, "functions")));
        putIfPresent(properties, "gasLimit", NodeUtil.property(stepNode, "gasLimit"));
        putIfPresent(properties, "emitEvents", NodeUtil.property(stepNode, "emitEvents"));
        putIfPresent(properties, "returnResult", NodeUtil.property(stepNode, "returnResult"));
        program.properties(properties);
        return program;
    }

    Node definition(Node definitionNode) {
        return definitionSource(definitionNode);
    }

    private Node definitionSource(Node definitionNode) {
        if (definitionNode == null) {
            throw new IllegalArgumentException(
                    "definitionNode must not be null");
        }
        if (definitionNode.getValue() != null
                || definitionNode.getItems() != null) {
            return canonicalStaticSource(definitionNode);
        }
        Node definition = new Node();
        copyMetadata(definition, definitionNode);
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        putIfMeaningful(
                properties,
                "constants",
                authoredMap(
                        NodeUtil.property(
                                definitionNode,
                                "constants")));
        putIfMeaningful(
                properties,
                "functions",
                normalizeFunctions(
                        NodeUtil.property(
                                definitionNode,
                                "functions")));
        definition.properties(properties);
        return definition;
    }

    private Node frozenProgramInput(FrozenNode source) {
        Node input = new Node();
        copyMetadata(input, source);
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        copyFrozenProperty(properties, source, "expr");
        copyFrozenProperty(properties, source, "do");
        copyFrozenProperty(properties, source, "definition");
        copyFrozenProperty(properties, source, "entry");
        copyFrozenProperty(properties, source, "constants");
        copyFrozenProperty(properties, source, "functions");
        copyFrozenProperty(properties, source, "gasLimit");
        copyFrozenProperty(properties, source, "emitEvents");
        copyFrozenProperty(properties, source, "returnResult");
        input.properties(properties);
        return input;
    }

    private Node frozenDefinitionInput(FrozenNode source) {
        Node input = new Node();
        copyMetadata(input, source);
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        copyFrozenProperty(properties, source, "constants");
        copyFrozenProperty(properties, source, "functions");
        input.properties(properties);
        return input;
    }

    private void copyFrozenProperty(Map<String, Node> target,
                                    FrozenNode source,
                                    String key) {
        FrozenNode value = source != null && source.getProperties() != null
                ? source.getProperties().get(key)
                : null;
        if (value != null) {
            Node mutable = value.toNode();
            if ("do".equals(key)) {
                mutable = normalizeDo(mutable);
            } else if ("functions".equals(key)) {
                mutable = normalizeFunctions(mutable);
            } else if ("constants".equals(key)) {
                mutable = authoredMap(mutable);
            }
            if (mutable != null) {
                target.put(key, mutable);
            }
        }
    }

    private Node normalizeFunctions(Node functions) {
        if (functions == null) {
            return null;
        }
        if (functions.getProperties() == null) {
            return canonicalStaticSource(functions);
        }
        Map<String, Node> normalized = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : functions.getProperties().entrySet()) {
            normalized.put(entry.getKey(), normalizeFunction(entry.getValue()));
        }
        return new Node().properties(normalized);
    }

    private Node normalizeFunction(Node function) {
        if (function == null || function.getProperties() == null) {
            return function != null ? function.clone() : new Node();
        }
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        putIfPresent(properties, "args", authoredMap(NodeUtil.property(function, "args")));
        putIfPresent(properties, "expr", NodeUtil.property(function, "expr"));
        putIfPresent(properties, "do", normalizeDo(NodeUtil.property(function, "do")));
        return new Node().properties(properties);
    }

    private Node normalizeDo(Node doNode) {
        if (doNode == null) {
            return null;
        }
        if (doNode.getItems() == null) {
            return canonicalStaticSource(doNode);
        }
        java.util.List<Node> items = new java.util.ArrayList<Node>();
        for (Node item : doNode.getItems()) {
            items.add(normalizeStatement(item));
        }
        return new Node().items(items);
    }

    private Node normalizeStatement(Node statement) {
        // Empty statement objects are invalid BEX source. Preserve authored
        // content so compilation rejects it; never turn it into executable
        // behavior as a compatibility rewrite.
        return canonicalStaticSource(statement);
    }

    private Node authoredMap(Node node) {
        if (node == null) {
            return null;
        }
        if (node.getProperties() == null) {
            return canonicalStaticSource(node);
        }
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
            properties.put(entry.getKey(),
                    canonicalStaticSource(entry.getValue()));
        }
        return new Node().properties(properties);
    }

    private void putIfMeaningful(Map<String, Node> properties, String key, Node value) {
        if (hasAuthoredContent(value)) {
            properties.put(key, canonicalStaticSource(value));
        }
    }

    private void putIfPresent(Map<String, Node> properties,
                              String key,
                              Node value) {
        // BEX selects expression bodies by field presence and accepts an
        // ordinary empty object as a literal expression. Emptiness is not
        // permission to substitute the default statement program.
        if (value != null) {
            properties.put(key, canonicalStaticSource(value));
        }
    }

    /**
     * Restores canonical pure-reference shape inside a resolved executable
     * view.  Language may retain a provider BlueId beside resolved fields so
     * hosts can inspect effective content.  Those sibling fields are not part
     * of the authored BEX literal and would make a transient Blue output
     * invalid if compiled as object members.
     */
    private Node canonicalStaticSource(Node source) {
        if (source == null) {
            return null;
        }
        if (source.getBlueId() != null) {
            return new Node().blueId(source.getBlueId());
        }
        Node normalized = source.clone();
        normalized.type(canonicalStaticSource(source.getType()));
        normalized.itemType(canonicalStaticSource(source.getItemType()));
        normalized.keyType(canonicalStaticSource(source.getKeyType()));
        normalized.valueType(canonicalStaticSource(source.getValueType()));
        normalized.blue(canonicalStaticSource(source.getBlue()));
        normalized.contracts(canonicalStaticSource(source.getContracts()));
        if (source.getItems() != null) {
            java.util.List<Node> items = new java.util.ArrayList<Node>();
            for (Node item : source.getItems()) {
                items.add(canonicalStaticSource(item));
            }
            normalized.items(items);
        }
        if (source.getProperties() != null) {
            Map<String, Node> properties = new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                properties.put(entry.getKey(),
                        canonicalStaticSource(entry.getValue()));
            }
            normalized.properties(properties);
        }
        return normalized;
    }

    private boolean hasAuthoredContent(Node node) {
        return !NodeUtil.isEmpty(node);
    }

    private void copyMetadata(Node target, Node source) {
        if (source == null) {
            return;
        }
        target.name(source.getName());
        target.description(source.getDescription());
        target.type(canonicalStaticSource(source.getType()));
    }

    private void copyMetadata(Node target, FrozenNode source) {
        if (source == null) {
            return;
        }
        target.name(source.getName());
        target.description(source.getDescription());
        target.type(source.getType() != null
                ? canonicalStaticSource(source.getType().toNode())
                : null);
    }
}
