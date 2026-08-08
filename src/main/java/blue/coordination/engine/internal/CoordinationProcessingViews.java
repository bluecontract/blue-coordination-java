package blue.coordination.engine.internal;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProviderResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Extracts the nonsemantic PROCESS views produced by the canonical splitter. */
public final class CoordinationProcessingViews {

    private CoordinationProcessingViews() {
    }

    /**
     * Returns only identity-equivalent representations that differ from the
     * canonical stored fragment. Executable bodies remain pure references.
     */
    public static Map<String, Node> collect(
            CoordinationDocumentSplitter.SplitGraph graph) {
        CoordinationDocumentSplitter.SplitGraph checked =
                Objects.requireNonNull(graph, "graph");
        Map<String, Node> physical = checked.fragments();
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry : physical.entrySet()) {
            NodeProviderResult provided = checked.provider()
                    .fetchResultByBlueId(entry.getKey());
            if (provided == null
                    || provided.outcome() != NodeProviderOutcome.FOUND
                    || provided.nodes().size() != 1) {
                throw new IllegalStateException(
                        "Splitter PROCESS view is unavailable or ambiguous: "
                                + entry.getKey());
            }
            Node view = provided.nodes().get(0).clone();
            String actual = DirectBlueIdCalculator.calculateBlueId(
                    view);
            if (!entry.getKey().equals(actual)) {
                throw new IllegalStateException(
                        "Splitter PROCESS view changed identity from "
                                + entry.getKey() + " to " + actual);
            }
            if (!NodeWireForm.get(entry.getValue()).equals(
                    NodeWireForm.get(view))) {
                result.put(entry.getKey(), view);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
