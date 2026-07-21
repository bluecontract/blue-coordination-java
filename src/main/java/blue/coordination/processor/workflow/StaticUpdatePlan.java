package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.MergeReverser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, scope-independent form of one literal Update Document changeset.
 *
 * <p>Authored pointers are resolved only when the step executes. Patch values
 * remain frozen through the Language boundary. Resolved-construction fallback
 * inputs are normalized once while the plan is compiled, never once per
 * execution.</p>
 */
final class StaticUpdatePlan {
    private final List<PatchTemplate> patches;
    private final String validationFailure;
    private final long approximateWeightBytes;

    private StaticUpdatePlan(List<PatchTemplate> patches,
                             String validationFailure,
                             long approximateWeightBytes) {
        this.patches = Collections.unmodifiableList(new ArrayList<PatchTemplate>(patches));
        this.validationFailure = validationFailure;
        this.approximateWeightBytes = approximateWeightBytes;
    }

    static StaticUpdatePlan compile(FrozenNode changeset) {
        return compile(changeset, null);
    }

    static StaticUpdatePlan compile(FrozenNode changeset, BexProcessingMetrics metrics) {
        String bexPath = StaticPayloadValidator.firstBexOperatorPath(changeset, "");
        if (bexPath != null) {
            return invalid("Update Document changeset must be static; BEX operator object is not allowed at "
                    + bexPath);
        }
        if (changeset == null || changeset.getItems() == null) {
            return invalid("Update Document changeset must be a static patch list");
        }
        List<PatchTemplate> templates = new ArrayList<PatchTemplate>(changeset.getItems().size());
        MergeReverser mergeReverser = new MergeReverser();
        long weight = 96L;
        for (int index = 0; index < changeset.getItems().size(); index++) {
            FrozenNode item = changeset.getItems().get(index);
            boolean resolvedConstruction = item != null && !item.isStrictCanonical();
            if (resolvedConstruction) {
                Node authoredItem = mergeReverser.reverseToMinimizedOverlay(item.toNode());
                item = authoredItem != null ? FrozenNode.fromNode(authoredItem) : null;
            }
            Map<String, FrozenNode> properties = item != null ? item.getProperties() : null;
            if (properties == null) {
                return invalid("Update Document changeset entry " + index
                        + " must be a static patch object");
            }
            ScalarText op = scalarText(properties.get("op"));
            if (op.invalidType) {
                return invalid("Update Document changeset entry " + index
                        + " field 'op' must be text");
            }
            ScalarText path = scalarText(properties.get("path"));
            if (path.invalidType) {
                return invalid("Update Document changeset entry " + index
                        + " field 'path' must be text");
            }
            if (op.value == null || op.value.trim().isEmpty()) {
                return invalid("Update Document patch operation is required");
            }
            if (path.value == null || path.value.trim().isEmpty()) {
                return invalid("Update Document patch path is required");
            }
            String normalizedOp = op.value.trim().toLowerCase(java.util.Locale.ROOT);
            JsonPatch.Op patchOp;
            if ("add".equals(normalizedOp)) {
                patchOp = JsonPatch.Op.ADD;
            } else if ("replace".equals(normalizedOp)) {
                patchOp = JsonPatch.Op.REPLACE;
            } else if ("remove".equals(normalizedOp)) {
                patchOp = JsonPatch.Op.REMOVE;
            } else {
                return invalid("Unsupported Update Document patch operation: " + op.value);
            }
            FrozenNode value = null;
            if (patchOp != JsonPatch.Op.REMOVE) {
                value = properties.get("val");
                if (value == null) {
                    return invalid("Update Document patch value is required for operation: "
                            + op.value);
                }
                if (!value.isStrictCanonical()) {
                    // Compatibility-only boundary for callers that compiled the
                    // workflow graph in resolved construction mode. Preserve the
                    // exact visible authored shape and pay this conversion once.
                    value = FrozenNode.fromNode(value.toNode());
                    resolvedConstruction = true;
                }
                if (resolvedConstruction && metrics != null) {
                    metrics.addMetric("staticUpdateResolvedValueCanonicalizations", 1L);
                }
            }
            templates.add(new PatchTemplate(patchOp, path.value, value));
            weight += 72L + stringWeight(path.value) + frozenWeight(value);
        }
        return new StaticUpdatePlan(templates, null, weight);
    }

    private static StaticUpdatePlan invalid(String message) {
        return new StaticUpdatePlan(Collections.<PatchTemplate>emptyList(), message, 64L + stringWeight(message));
    }

    boolean valid() {
        return validationFailure == null;
    }

    String validationFailure() {
        return validationFailure;
    }

    List<PatchTemplate> patches() {
        return patches;
    }

    long approximateWeightBytes() {
        return approximateWeightBytes;
    }

    private static ScalarText scalarText(FrozenNode node) {
        if (node == null) {
            return ScalarText.absent();
        }
        Object value = node.getValue();
        if (value == null) {
            return ScalarText.absent();
        }
        return value instanceof String
                ? ScalarText.value((String) value)
                : ScalarText.invalid();
    }

    private static long stringWeight(String value) {
        return value != null ? 40L + (long) value.length() * 2L : 0L;
    }

    private static long frozenWeight(FrozenNode node) {
        return frozenWeight(node, new IdentityHashMap<FrozenNode, Boolean>());
    }

    private static long frozenWeight(FrozenNode node,
                                     IdentityHashMap<FrozenNode, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return 0L;
        }
        long weight = 96L
                + stringWeight(node.getName())
                + stringWeight(node.getDescription())
                + stringWeight(node.getReferenceBlueId());
        if (node.getValue() instanceof String) {
            weight += stringWeight((String) node.getValue());
        } else if (node.getValue() != null) {
            weight += 32L;
        }
        if (node.getItems() != null) {
            weight += 16L + (long) node.getItems().size() * 8L;
            for (FrozenNode item : node.getItems()) {
                weight += frozenWeight(item, visited);
            }
        }
        if (node.getProperties() != null) {
            weight += 32L + (long) node.getProperties().size() * 40L;
            for (Map.Entry<String, FrozenNode> entry : node.getProperties().entrySet()) {
                weight += stringWeight(entry.getKey()) + frozenWeight(entry.getValue(), visited);
            }
        }
        weight += frozenWeight(node.getType(), visited);
        weight += frozenWeight(node.getItemType(), visited);
        weight += frozenWeight(node.getKeyType(), visited);
        weight += frozenWeight(node.getValueType(), visited);
        return weight;
    }

    static final class PatchTemplate {
        private final JsonPatch.Op op;
        private final String authoredPath;
        private final FrozenNode value;

        private PatchTemplate(JsonPatch.Op op, String authoredPath, FrozenNode value) {
            this.op = op;
            this.authoredPath = authoredPath;
            this.value = value;
        }

        FrozenJsonPatch bind(String absolutePath) {
            if (op == JsonPatch.Op.ADD) {
                return FrozenJsonPatch.add(absolutePath, value);
            }
            if (op == JsonPatch.Op.REPLACE) {
                return FrozenJsonPatch.replace(absolutePath, value);
            }
            return FrozenJsonPatch.remove(absolutePath);
        }

        String authoredPath() {
            return authoredPath;
        }
    }

    private static final class ScalarText {
        private static final ScalarText ABSENT = new ScalarText(null, false);
        private static final ScalarText INVALID = new ScalarText(null, true);

        private final String value;
        private final boolean invalidType;

        private ScalarText(String value, boolean invalidType) {
            this.value = value;
            this.invalidType = invalidType;
        }

        private static ScalarText absent() {
            return ABSENT;
        }

        private static ScalarText invalid() {
            return INVALID;
        }

        private static ScalarText value(String value) {
            return new ScalarText(value, false);
        }
    }
}
