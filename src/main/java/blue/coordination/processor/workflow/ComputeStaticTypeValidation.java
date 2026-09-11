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

    static void validate(BexProgramSource source, StepExecutionContext context,
                         String definitionPointer) {
        SelectedExecutableBody selected = context.processorContext().selectedExecutableBody("steps");
        Node program = source.programNode().toNode();
        Node definition = source.definitionNode().map(FrozenNode::toNode).orElse(null);
        Set<String> opened = new HashSet<>();
        TypeAccess executableTypes = (blueId, pointer) -> {
            if (selected == null) {
                throw new IllegalStateException("Static BEX types require selected-body evidence");
            }
            return selected.materializeExactReference(blueId).toNode();
        };
        boolean changed = fields(program, executableTypes, opened, "");
        if (definition != null) {
            // This type identifies the definition contract, not a static Blue
            // literal inside its executable constants and functions. Inherited
            // definition types can themselves contain ordinary BEX programs.
            definition.type((Node) null);
            TypeAccess definitionTypes = definitionPointer == null
                    ? executableTypes
                    : (blueId, pointer) -> {
                        // A named definition is reached through the working
                        // document, outside the selected steps. Resolve only
                        // this exact static-field occurrence through the same
                        // invocation-owned document access as the definition.
                        FrozenNode resolved = context.workingResolvedAt(pointer);
                        if (resolved == null || resolved.isReferenceOnly()) {
                            throw new IllegalStateException(
                                    "Static BEX type is unresolved at " + pointer);
                        }
                        return resolved.toNode();
                    };
            changed |= fields(definition, definitionTypes, opened,
                    definitionPointer != null ? definitionPointer : "");
        }
        if (changed) {
            Map<String, Node> fields = new LinkedHashMap<>();
            fields.put("program", program);
            if (definition != null) fields.put("definition", definition);
            FrozenNode literal = FrozenNode.fromResolvedNode(new Node().properties(Map.of(
                    "$literal", new Node().properties(fields))));
            // Literal escape keeps executable operators as data while the
            // compiler checks static fields recursively. The expanded proof
            // must never replace the canonical source in the execution cache.
            BexCompilerRuntimeAccess.compile(BexProgramSource.expression(literal),
                    new BexMetricsRecorder(), blueId -> false, "static-type-validation-v1");
        }
    }

    private static boolean fields(Node node, TypeAccess access, Set<String> opened,
                                  String pointer) {
        if (node == null || node.isReferenceOnly()) return false;
        // Resolved document reads can retain an exact identity beside the
        // expanded fields. It is proof metadata, not part of an operator's
        // authored shape (and must not hide a forbidden static expression).
        node.blueId(null);
        int before = opened.size();
        node.type(type(node.getType(), access, opened, pointer + "/type"));
        node.itemType(type(node.getItemType(), access, opened, pointer + "/itemType"));
        node.keyType(type(node.getKeyType(), access, opened, pointer + "/keyType"));
        node.valueType(type(node.getValueType(), access, opened, pointer + "/valueType"));
        boolean changed = opened.size() != before;
        changed |= fields(node.getContracts(), access, opened, pointer + "/contracts");
        if (node.getProperties() != null) for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
            changed |= fields(entry.getValue(), access, opened,
                    pointer + "/" + entry.getKey().replace("~", "~0").replace("/", "~1"));
        }
        if (node.getItems() != null) for (int i = 0; i < node.getItems().size(); i++) {
            changed |= fields(node.getItems().get(i), access, opened, pointer + "/" + i);
        }
        return changed;
    }

    private static Node type(Node node, TypeAccess access, Set<String> opened,
                             String pointer) {
        if (node == null) return null;
        if (!node.isReferenceOnly()) {
            fields(node, access, opened, pointer);
            return node;
        }
        if (!opened.add(node.getBlueId())) return node;
        Node body = access.open(node.getBlueId(), pointer);
        fields(body, access, opened, pointer);
        return body;
    }

    private interface TypeAccess {
        Node open(String blueId, String pointer);
    }
}
