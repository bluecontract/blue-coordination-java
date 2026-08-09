package blue.coordination.processor;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProvider;
import blue.language.processor.ExternalOrderKey;
import blue.repo.BlueRepository;
import blue.repo.RepositoryDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lazy exact-content index over every inline node in the current Repository.
 *
 * <p>The generated Repository provider addresses complete definitions. The
 * effective-contract catalog also names inherited inline Source
 * contributions by their own content BlueIds. This derived provider exposes
 * those already-verified subtrees without introducing aliases or replacement
 * generated types.</p>
 */
final class CurrentRepositoryExactNodeProvider implements NodeProvider {

    private final BlueRepository repository;
    private final ClassLoader classLoader;
    private volatile Map<String, Node> exactNodes;

    CurrentRepositoryExactNodeProvider(BlueRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        ClassLoader contextClassLoader =
                Thread.currentThread().getContextClassLoader();
        this.classLoader = contextClassLoader != null
                ? contextClassLoader
                : CurrentRepositoryExactNodeProvider.class.getClassLoader();
    }

    @Override
    public List<Node> fetchByBlueId(String blueId) {
        Node found = exactNodes().get(Objects.requireNonNull(
                blueId, "blueId"));
        return found == null
                ? null
                : Collections.singletonList(found.clone());
    }

    private Map<String, Node> exactNodes() {
        Map<String, Node> current = exactNodes;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = exactNodes;
            if (current == null) {
                current = buildIndex();
                exactNodes = current;
            }
        }
        return current;
    }

    private Map<String, Node> buildIndex() {
        List<String> qualifiedNames = new ArrayList<String>(
                repository.qualifiedNames());
        Collections.sort(
                qualifiedNames,
                ExternalOrderKey::compareTextCodePoints);
        Map<String, Node> indexed = new LinkedHashMap<String, Node>();
        IdentityHashMap<Node, Boolean> visited =
                new IdentityHashMap<Node, Boolean>();
        for (String qualifiedName : qualifiedNames) {
            RepositoryDefinition manifestDefinition = repository
                    .definition(qualifiedName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Current Repository manifest has no definition "
                                    + qualifiedName));
            Node definition = manifestDefinition.blueId().indexOf('#') >= 0
                    ? repository.nodeByName(qualifiedName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Current Repository provider has no cyclic "
                                    + "definition " + qualifiedName))
                    : readAuthoredDefinition(manifestDefinition);
            index(definition, indexed, visited);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private Node readAuthoredDefinition(RepositoryDefinition definition) {
        try (InputStream input = classLoader.getResourceAsStream(
                definition.resourcePath())) {
            if (input == null) {
                throw new IllegalStateException(
                        "Current Repository resource not found: "
                                + definition.resourcePath());
            }
            return UncheckedObjectMapper.JSON_MAPPER.readValue(
                    input, Node.class);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read current Repository resource: "
                            + definition.resourcePath(), failure);
        }
    }

    private static void index(
            Node node,
            Map<String, Node> indexed,
            IdentityHashMap<Node, Boolean> visited) {
        if (node == null || node.isReferenceOnly()
                || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        Node exact = node.clone();
        String declaredBlueId = exact.getBlueId();
        boolean addressableContent = declaredBlueId == null
                || declaredBlueId.indexOf('#') < 0;
        if (declaredBlueId != null && addressableContent) {
            exact.blueId(null);
        }
        if (addressableContent) {
            String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
            if (declaredBlueId != null && !declaredBlueId.equals(blueId)) {
                throw new IllegalStateException(
                        "Current Repository subtree declares "
                                + declaredBlueId + " but calculates to "
                                + blueId);
            }
            Node prior = indexed.get(blueId);
            if (prior == null) {
                indexed.put(blueId, exact);
            } else if (!NodeWireForm.get(prior).equals(
                    NodeWireForm.get(exact))) {
                throw new IllegalStateException(
                        "Current Repository contains conflicting exact "
                                + "content for " + blueId);
            }
        }
        index(node.getType(), indexed, visited);
        index(node.getItemType(), indexed, visited);
        index(node.getKeyType(), indexed, visited);
        index(node.getValueType(), indexed, visited);
        index(node.getBlue(), indexed, visited);
        index(node.getContracts(), indexed, visited);
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                index(child, indexed, visited);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                index(child, indexed, visited);
            }
        }
    }
}

