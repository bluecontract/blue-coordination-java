package blue.coordination.processor.merge;

import blue.language.Blue;
import blue.language.merge.MergingProcessor;

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

    public static void install(Blue blue) {
        if (blue == null) {
            throw new IllegalArgumentException("blue must not be null");
        }
        MergingProcessor current = blue.getMergingProcessor();
        if (current
                instanceof ComputeRuntimeDefaultMergingProcessor) {
            return;
        }
        blue.mergingProcessor(
                new ComputeRuntimeDefaultMergingProcessor(
                        current));
    }
}
