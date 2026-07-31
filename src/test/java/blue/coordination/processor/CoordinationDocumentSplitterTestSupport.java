package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.processor.DocumentProcessor;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs document splitting through the same effective catalog as a configured
 * Coordination processor. Tests deliberately do not reintroduce an
 * authored-contract scanner.
 */
final class CoordinationDocumentSplitterTestSupport {

    private CoordinationDocumentSplitterTestSupport() {
    }

    static CoordinationDocumentSplitter.SplitGraph splitDocument(
            Node exactRoot) {
        DocumentProcessor processor =
                CoordinationFragmentationCatalogHarness
                        .processor(
                                exactRoot,
                                standardBodyFields());
        try {
            return new CoordinationDocumentSplitter(
                    processor)
                    .splitDocument(exactRoot);
        } finally {
            processor.close();
        }
    }

    private static Map<String, List<String>>
    standardBodyFields() {
        Map<String, List<String>> result =
                new LinkedHashMap<>();
        result.put(
                SequentialWorkflow.blueId(),
                Collections.singletonList(
                        "steps"));
        result.put(
                SequentialWorkflowOperation.blueId(),
                Collections.singletonList(
                        "steps"));
        result.put(
                ChatWorkflowOperation.blueId(),
                Collections.singletonList(
                        "steps"));
        return result;
    }
}
