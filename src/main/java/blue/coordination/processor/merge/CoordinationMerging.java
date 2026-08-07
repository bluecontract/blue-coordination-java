package blue.coordination.processor.merge;

import blue.language.merge.MergingProcessor;

import java.util.Objects;

/**
 * Installs the narrow Coordination workflow-AST preservation adapter.
 *
 * <p>The adapter always delegates ordinary merging and validation to the
 * caller-selected Language processor. It preserves only authored Compute
 * program fields until the Coordination workflow boundary evaluates them.</p>
 */
public final class CoordinationMerging {
    private CoordinationMerging() {
    }

    public static MergingProcessor wrap(MergingProcessor current) {
        Objects.requireNonNull(current, "current");
        if (current
                instanceof ComputeRuntimeDefaultMergingProcessor) {
            return current;
        }
        return new ComputeRuntimeDefaultMergingProcessor(current);
    }
}
