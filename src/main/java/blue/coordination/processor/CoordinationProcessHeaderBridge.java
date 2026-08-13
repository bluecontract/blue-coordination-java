package blue.coordination.processor;

import blue.coordination.processor.support.CoordinationProcessHeaderSupport;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;

/**
 * Coordination-owned exact PROCESS-header normalization and materialization.
 *
 * <p>The stable public facade and workflow implementation share one
 * package-neutral implementation, so exact-header behavior cannot drift while
 * package dependencies remain acyclic.</p>
 */
public final class CoordinationProcessHeaderBridge {

    private CoordinationProcessHeaderBridge() {
    }

    public static Node materializeVerifiedExactReference(
            NodeProvider provider,
            Node reference) {
        return CoordinationProcessHeaderSupport
                .materializeVerifiedExactReference(provider, reference);
    }

    public static Node canonicalExactCopy(Node resolvedContent) {
        return CoordinationProcessHeaderSupport
                .canonicalExactCopy(resolvedContent);
    }
}
