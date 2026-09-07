package blue.coordination.processor.workflow;

import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompilerRuntimeAccess;
import blue.bex.result.BexMetricsRecorder;
import blue.language.model.Node;
import blue.language.processor.SelectedExecutableBody;
import blue.language.snapshot.FrozenNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Compiler-only view of static type bodies; execution keeps exact references. */
final class ComputeStaticTypeValidation {
    private ComputeStaticTypeValidation() {}

    static void validate(BexProgramSource source, StepExecutionContext context) {
        SelectedExecutableBody selected = context.processorContext().selectedExecutableBody("steps");
        Node program = source.programNode().toNode();
        Node definition = source.definitionNode().map(FrozenNode::toNode).orElse(null);
        Set<String> opened = new HashSet<>();
        boolean changed = fields(program, selected, opened);
        if (definition != null) changed |= fields(definition, selected, opened);
        if (changed) {
            Map<String, Node> fields = new LinkedHashMap<>();
            fields.put("program", program);
            if (definition != null) fields.put("definition", definition);
            FrozenNode literal = FrozenNode.fromResolvedNode(new Node().properties(Map.of(
                    "$literal", new Node().properties(fields))));
            // Literal escape keeps all executable operators as data while the
            // BEX compiler checks static fields recursively. Never put this
            // expanded proof view in the engine's canonical-identity cache:
            // its representation must not replace the executed exact source.
            BexCompilerRuntimeAccess.compile(BexProgramSource.expression(literal),
                    new BexMetricsRecorder(), blueId -> false, "static-type-validation-v1");
        }
    }

    private static boolean fields(Node node, SelectedExecutableBody selected, Set<String> opened) {
        if (node == null || node.isReferenceOnly()) return false;
        int before = opened.size();
        node.type(type(node.getType(), selected, opened));
        node.itemType(type(node.getItemType(), selected, opened));
        node.keyType(type(node.getKeyType(), selected, opened));
        node.valueType(type(node.getValueType(), selected, opened));
        boolean changed = opened.size() != before;
        changed |= fields(node.getContracts(), selected, opened);
        if (node.getProperties() != null) for (Node value : node.getProperties().values()) {
            changed |= fields(value, selected, opened);
        }
        if (node.getItems() != null) for (Node value : node.getItems()) {
            changed |= fields(value, selected, opened);
        }
        return changed;
    }

    private static Node type(Node node, SelectedExecutableBody selected, Set<String> opened) {
        if (node == null) return null;
        if (!node.isReferenceOnly()) {
            fields(node, selected, opened);
            return node;
        }
        if (!opened.add(node.getBlueId())) return node;
        if (selected == null) throw new IllegalStateException("Static BEX types require selected-body evidence");
        Node body = selected.materializeExactReference(node.getBlueId()).toNode();
        // These are verified canonical definition bytes, never completed instances.
        fields(body, selected, opened);
        return body;
    }
}
