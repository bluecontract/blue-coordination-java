package blue.coordination.processor.workflow;

import blue.coordination.processor.RepositoryTypeAliasPreprocessor;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.Map;

final class ComputeProgramNormalizer {
    private static final String NORMALIZATION_VERSION =
            "compute-program-v2|repository-aliases-3.0.0-rc.10";

    private final RepositoryTypeAliasPreprocessor typeAliasPreprocessor;
    private final BexProcessingMetrics metrics;

    ComputeProgramNormalizer() {
        this(new RepositoryTypeAliasPreprocessor(), null);
    }

    ComputeProgramNormalizer(BexProcessingMetrics metrics) {
        this(new RepositoryTypeAliasPreprocessor(), metrics);
    }

    ComputeProgramNormalizer(RepositoryTypeAliasPreprocessor typeAliasPreprocessor) {
        this(typeAliasPreprocessor, null);
    }

    private ComputeProgramNormalizer(RepositoryTypeAliasPreprocessor typeAliasPreprocessor,
                                     BexProcessingMetrics metrics) {
        if (typeAliasPreprocessor == null) {
            throw new IllegalArgumentException("typeAliasPreprocessor must not be null");
        }
        this.typeAliasPreprocessor = typeAliasPreprocessor;
        this.metrics = metrics;
    }

    String normalizationVersion() {
        return NORMALIZATION_VERSION;
    }

    /**
     * Normalizes only the authored Compute projection of a frozen step. This
     * avoids materializing unrelated resolved-contract content. The selected
     * subtrees still use the mutable alias preprocessor as a conservative,
     * semantics-preserving cold-path fallback; the resulting frozen plan is
     * reused on every warm invocation.
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
        return FrozenNode.fromResolvedNode(definition(frozenDefinitionInput(definitionNode)));
    }

    Node program(Node stepNode) {
        Node program = new Node();
        copyMetadata(program, stepNode);
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        putIfMeaningful(properties, "expr", NodeUtil.property(stepNode, "expr"));
        putIfMeaningful(properties, "do", normalizeDo(NodeUtil.property(stepNode, "do")));
        putIfMeaningful(properties, "definition", NodeUtil.property(stepNode, "definition"));
        putIfMeaningful(properties, "entry", NodeUtil.property(stepNode, "entry"));
        putIfMeaningful(properties, "constants", authoredMap(NodeUtil.property(stepNode, "constants")));
        putIfMeaningful(properties, "functions", normalizeFunctions(NodeUtil.property(stepNode, "functions")));
        putIfMeaningful(properties, "gasLimit", NodeUtil.property(stepNode, "gasLimit"));
        putIfMeaningful(properties, "emitEvents", NodeUtil.property(stepNode, "emitEvents"));
        putIfMeaningful(properties, "returnResult", NodeUtil.property(stepNode, "returnResult"));
        if (!properties.isEmpty()) {
            program.properties(properties);
        }
        return typeAliasPreprocessor.preprocess(program);
    }

    Node definition(Node definitionNode) {
        Node definition = new Node();
        copyMetadata(definition, definitionNode);
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        putIfMeaningful(properties, "constants", authoredMap(NodeUtil.property(definitionNode, "constants")));
        putIfMeaningful(properties, "functions", normalizeFunctions(NodeUtil.property(definitionNode, "functions")));
        if (!properties.isEmpty()) {
            definition.properties(properties);
        }
        return typeAliasPreprocessor.preprocess(definition);
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
        if (!properties.isEmpty()) {
            input.properties(properties);
        }
        return input;
    }

    private Node frozenDefinitionInput(FrozenNode source) {
        Node input = new Node();
        copyMetadata(input, source);
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        copyFrozenProperty(properties, source, "constants");
        copyFrozenProperty(properties, source, "functions");
        if (!properties.isEmpty()) {
            input.properties(properties);
        }
        return input;
    }

    private void copyFrozenProperty(Map<String, Node> target,
                                    FrozenNode source,
                                    String key) {
        FrozenNode value = source != null && source.getProperties() != null
                ? source.getProperties().get(key)
                : null;
        if (value != null) {
            target.put(key, value.toNode());
        }
    }

    private Node normalizeFunctions(Node functions) {
        if (functions == null || functions.getProperties() == null || functions.getProperties().isEmpty()) {
            return null;
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
        putIfMeaningful(properties, "args", authoredMap(NodeUtil.property(function, "args")));
        putIfMeaningful(properties, "expr", NodeUtil.property(function, "expr"));
        putIfMeaningful(properties, "do", normalizeDo(NodeUtil.property(function, "do")));
        return new Node().properties(properties);
    }

    private Node normalizeDo(Node doNode) {
        if (doNode == null || doNode.getItems() == null || doNode.getItems().isEmpty()) {
            return null;
        }
        java.util.List<Node> items = new java.util.ArrayList<Node>();
        for (Node item : doNode.getItems()) {
            items.add(normalizeStatement(item));
        }
        return new Node().items(items);
    }

    private Node normalizeStatement(Node statement) {
        if (NodeUtil.isEmpty(statement)) {
            return new Node().properties("$return", new Node());
        }
        return statement.clone();
    }

    private Node authoredMap(Node node) {
        if (node == null || node.getProperties() == null || node.getProperties().isEmpty()) {
            return null;
        }
        Map<String, Node> properties = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
            properties.put(entry.getKey(), entry.getValue().clone());
        }
        return new Node().properties(properties);
    }

    private void putIfMeaningful(Map<String, Node> properties, String key, Node value) {
        if (hasAuthoredContent(value)) {
            properties.put(key, value.clone());
        }
    }

    private boolean hasAuthoredContent(Node node) {
        return node != null
                && (node.getValue() != null
                || (node.getItems() != null && !node.getItems().isEmpty())
                || (node.getProperties() != null && !node.getProperties().isEmpty()));
    }

    private void copyMetadata(Node target, Node source) {
        if (source == null) {
            return;
        }
        target.name(source.getName());
        target.description(source.getDescription());
        target.type(source.getType() != null ? source.getType().clone() : null);
    }

    private void copyMetadata(Node target, FrozenNode source) {
        if (source == null) {
            return;
        }
        target.name(source.getName());
        target.description(source.getDescription());
        target.type(source.getType() != null ? source.getType().toNode() : null);
    }
}
