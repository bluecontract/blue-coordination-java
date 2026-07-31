package blue.coordination.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.provider.SequentialNodeProvider;
import java.util.List;

/**
 * Binary-compatible exact-content provider wrapper retained for pre-release
 * consumers.
 *
 * <p>The former implementation repaired Repository content. That behavior is
 * intentionally gone: this wrapper delegates the exact request and exact
 * result without rewriting type identities or document content.</p>
 */
public final class CoordinationRepositoryCompatibilityNodeProvider
        implements NodeProvider {
    private final NodeProvider delegate;

    public CoordinationRepositoryCompatibilityNodeProvider(
            NodeProvider delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException(
                    "delegate must not be null");
        }
        this.delegate = delegate;
    }

    public static boolean isInstalled(NodeProvider provider) {
        if (provider
                instanceof CoordinationRepositoryCompatibilityNodeProvider) {
            return true;
        }
        if (provider instanceof SequentialNodeProvider) {
            for (NodeProvider child
                    : ((SequentialNodeProvider) provider)
                    .getNodeProviders()) {
                if (isInstalled(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        return delegate.fetchByBlueId(blueId);
    }
}
