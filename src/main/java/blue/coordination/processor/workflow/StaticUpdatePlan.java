package blue.coordination.processor.workflow;

import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.MinimizedOverlayBuilder;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final long RETAINED_EXACT_VALUE_BYTES = 96L;

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
        if (changeset == null || changeset.getItems() == null) {
            return invalid("Update Document changeset must be a static patch list");
        }
        List<PatchTemplate> templates = new ArrayList<PatchTemplate>(changeset.getItems().size());
        MinimizedOverlayBuilder overlayBuilder = new MinimizedOverlayBuilder();
        long weight = 96L;
        for (int index = 0; index < changeset.getItems().size(); index++) {
            FrozenNode item = changeset.getItems().get(index);
            boolean resolvedConstruction = item != null && !item.isStrictCanonical();
            if (resolvedConstruction) {
                Node authoredItem = overlayBuilder.build(item.toNode());
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
            if (op.value == null || op.value.isEmpty()) {
                return invalid("Update Document patch operation is required");
            }
            if (path.value == null || path.value.isEmpty()) {
                return invalid("Update Document patch path is required");
            }
            JsonPatch.Op patchOp;
            if ("add".equals(op.value)) {
                patchOp = JsonPatch.Op.ADD;
            } else if ("replace".equals(op.value)) {
                patchOp = JsonPatch.Op.REPLACE;
            } else if ("remove".equals(op.value)) {
                patchOp = JsonPatch.Op.REMOVE;
            } else {
                return invalid("Unsupported Update Document patch operation: " + op.value);
            }
            FrozenNode value = null;
            if (patchOp == JsonPatch.Op.REMOVE) {
                if (properties.containsKey("val")) {
                    return invalid(
                            "Update Document patch value must be absent for remove");
                }
            } else {
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
            /*
             * Exact admitted values are retained by identity. Cache
             * bookkeeping must not recursively walk or charge their payload.
             */
            weight += 72L + stringWeight(path.value)
                    + (value != null ? RETAINED_EXACT_VALUE_BYTES : 0L);
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
