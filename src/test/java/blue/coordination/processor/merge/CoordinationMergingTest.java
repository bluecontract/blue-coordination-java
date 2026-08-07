package blue.coordination.processor.merge;

import blue.language.provider.NodeProvider;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.runtime.BlueLanguage;
import blue.repo.coordination.Compute;
import blue.repo.coordination.ComputeDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationMergingTest {

    @Test
    void shouldWrapLanguageMergerExactlyOnce() {
        // given
        MergingProcessor languageMerger = new LanguageOwnedMergingProcessor();

        // when
        MergingProcessor wrapped = CoordinationMerging.wrap(languageMerger);
        MergingProcessor wrappedAgain = CoordinationMerging.wrap(wrapped);

        // then
        assertTrue(wrapped instanceof ComputeRuntimeDefaultMergingProcessor);
        assertSame(wrapped, wrappedAgain);
    }

    @Test
    void shouldPreserveLanguageMergeOutputAfterPostProcessing() {
        // given
        MergingProcessor languageMerger = new LanguageOwnedMergingProcessor();
        Node target = new Node().properties(
                "emitEvents", new Node().value(true),
                "returnResult", new Node().value(true));
        Node source = computeSource();
        MergingProcessor activeMerger = CoordinationMerging.wrap(
                languageMerger);

        // when
        activeMerger.process(target, source, null, null);
        activeMerger.postProcess(target, source, null, null);
        activeMerger.validateCompleted(target, true, "");

        // then
        assertTrue(activeMerger
                instanceof ComputeRuntimeDefaultMergingProcessor);
        assertEquals(
                "post-processed-by-language",
                target.getAsText("/phase"));
        assertEquals(
                2,
                target.getAsNode(
                        "/expr/$add")
                        .getItems().size());
        assertEquals(
                1,
                ((Number) target.getAsNode(
                        "/expr/$add")
                        .getItems().get(0)
                        .getValue()).intValue());
        assertEquals(
                2,
                ((Number) target.getAsNode(
                        "/expr/$add")
                        .getItems().get(1)
                        .getValue()).intValue());
        assertEquals(
                "literal-value",
                target.getAsText(
                        "/constants/literal"));
        assertNotNull(source.getAsNode("/expr/$add"));
        assertEquals(
                "literal-value",
                source.get(
                        "/constants/literal"));
    }

    @Test
    void shouldRejectNullLanguageMerger() {
        // given
        MergingProcessor missingMerger = null;

        // when
        NullPointerException failure = assertThrows(
                NullPointerException.class,
                () -> CoordinationMerging.wrap(missingMerger));

        // then
        assertEquals("current", failure.getMessage());
    }

    @Test
    void shouldResolveProcessEmbeddedWithoutInheritingTypeRootLabels() {
        // given
        Node authored = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(
                        new Node().value("/child")));

        // when
        Node resolved;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider())
                .build()) {
            resolved = language.resolution().resolve(authored);
        }

        // then
        Node paths = resolved.getAsNode("/paths");
        assertNotNull(paths);
        assertEquals(1, paths.getItems().size());
        assertEquals("/child", paths.getItems().get(0).getValue());
        assertNull(resolved.getName());
        assertNull(resolved.getDescription());
    }

    @Test
    void shouldKeepInheritedComputeMapsWhenChildCarriesOnlySchemaMetadata() {
        // given
        Node target = inheritedComputeDefinition();
        Node source = new Node()
                .type(new Node().blueId(ComputeDefinition.blueId()))
                .properties(
                        "constants", new Node().type("Dictionary"),
                        "functions", new Node().type("Dictionary"));
        MergingProcessor merger =
                new ComputeRuntimeDefaultMergingProcessor(
                        new NoOpMergingProcessor());

        // when
        merger.process(target, source, null, null);
        merger.postProcess(target, source, null, null);

        // then
        assertEquals("inherited literal",
                target.getAsText("/constants/inherited"));
        assertNotNull(target.getAsNode("/functions/inheritedFunction"));
    }

    @Test
    void shouldMergeAuthoredComputeMapsWithInheritedEntries() {
        // given
        Node target = inheritedComputeDefinition();
        Node source = new Node()
                .type(new Node().blueId(ComputeDefinition.blueId()))
                .properties(
                        "constants", new Node().properties(
                                "child", new Node().value("child literal")),
                        "functions", new Node().properties(
                                "childFunction", new Node().properties(
                                        "body", new Node().value("child body"))));
        MergingProcessor merger =
                new ComputeRuntimeDefaultMergingProcessor(
                        new NoOpMergingProcessor());

        // when
        merger.process(target, source, null, null);
        merger.postProcess(target, source, null, null);

        // then
        assertEquals("inherited literal",
                target.getAsText("/constants/inherited"));
        assertEquals("child literal",
                target.getAsText("/constants/child"));
        assertNotNull(target.getAsNode("/functions/inheritedFunction"));
        assertNotNull(target.getAsNode("/functions/childFunction"));
    }

    private static Node inheritedComputeDefinition() {
        return new Node()
                .type(new Node().blueId(ComputeDefinition.blueId()))
                .properties(
                        "constants", new Node().properties(
                                "inherited",
                                new Node().value("inherited literal")),
                        "functions", new Node().properties(
                                "inheritedFunction", new Node().properties(
                                        "body",
                                        new Node().value("inherited body"))));
    }

    private static Node computeSource() {
        return new Node()
                .type(new Node().blueId(Compute.blueId()))
                .properties(
                        "emitEvents", new Node().value(false),
                        "returnResult", new Node().value(false),
                        "expr", new Node().properties(
                                "$add", new Node().items(
                                        new Node().value(1),
                                        new Node().value(2))),
                        "constants", new Node().properties(
                                "literal",
                                new Node().value(
                                        "literal-value")));
    }

    private static final class LanguageOwnedMergingProcessor
            implements MergingProcessor {
        @Override
        public void process(
                Node target,
                Node source,
                NodeProvider nodeProvider,
                NodeResolver nodeResolver) {
            target.properties(new LinkedHashMap<String, Node>());
            target.properties(
                    "phase", new Node().value("processed-by-language"));
        }

        @Override
        public void postProcess(
                Node target,
                Node source,
                NodeProvider nodeProvider,
                NodeResolver nodeResolver) {
            target.properties(new LinkedHashMap<String, Node>());
            target.properties(
                    "phase",
                    new Node().value("post-processed-by-language"));
        }

        @Override
        public boolean hasCompletedValidation(Node node) {
            return true;
        }
    }

    private static final class NoOpMergingProcessor
            implements MergingProcessor {
        @Override
        public void process(
                Node target,
                Node source,
                NodeProvider nodeProvider,
                NodeResolver nodeResolver) {
        }

        @Override
        public boolean hasCompletedValidation(Node node) {
            return true;
        }
    }
}
