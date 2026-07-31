package blue.coordination.processor;

import blue.language.model.Node;
import blue.repo.BlueRepository;
import java.util.Map;

/**
 * Binary-compatible exact-content preprocessor retained for pre-release
 * consumers.
 *
 * <p>Repository aliases are no longer applied. The input is defensively
 * cloned, preserving every authored BlueId and type identity exactly.</p>
 */
public final class RepositoryTypeAliasPreprocessor {
    public RepositoryTypeAliasPreprocessor() {
    }

    public RepositoryTypeAliasPreprocessor(
            BlueRepository repository) {
    }

    public RepositoryTypeAliasPreprocessor(
            Map<String, String> aliases) {
    }

    public Node preprocess(Node node) {
        return node == null ? null : node.clone();
    }
}
