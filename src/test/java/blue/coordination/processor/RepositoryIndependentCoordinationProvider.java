package blue.coordination.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Identity-verifying clone-on-read provider for repository-independent tests. */
public final class RepositoryIndependentCoordinationProvider
        implements NodeProvider {

    private final Map<String, Node> nodes;
    private final List<String> demands = new ArrayList<String>();

    /** Creates a provider from exact canonical content keyed by BlueId. */
    public RepositoryIndependentCoordinationProvider(
            Map<String, Node> canonicalNodes) {
        Objects.requireNonNull(canonicalNodes, "canonicalNodes");
        Map<String, Node> retained = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : canonicalNodes.entrySet()) {
            String expected = Objects.requireNonNull(
                    entry.getKey(), "canonical BlueId");
            Node canonical = Objects.requireNonNull(
                    entry.getValue(), "canonical node").clone();
            String actual = DirectBlueIdCalculator.calculateBlueId(canonical);
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "Provider content calculated to " + actual
                                + " for requested key " + expected);
            }
            retained.put(expected, canonical);
        }
        nodes = Collections.unmodifiableMap(retained);
    }

    /** Creates a provider containing the test-owned Coordination types. */
    public static RepositoryIndependentCoordinationProvider types() {
        return new RepositoryIndependentCoordinationProvider(
                RepositoryIndependentCoordinationTypes.canonicalTypes());
    }

    @Override
    public synchronized List<Node> fetchByBlueId(String blueId) {
        demands.add(blueId);
        Node canonical = nodes.get(blueId);
        if (canonical == null) {
            return null;
        }
        String actual = DirectBlueIdCalculator.calculateBlueId(canonical);
        if (!blueId.equals(actual)) {
            throw new IllegalStateException(
                    "Retained provider content changed identity from "
                            + blueId + " to " + actual);
        }
        return Collections.singletonList(canonical.clone());
    }

    /** Returns the ordered immutable demand trace. */
    public synchronized List<String> demands() {
        return Collections.unmodifiableList(
                new ArrayList<String>(demands));
    }

    /** Clears only observational demand history, never provider content. */
    public synchronized void clearDemands() {
        demands.clear();
    }
}
