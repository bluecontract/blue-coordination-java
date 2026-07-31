package blue.language.processor;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodePathEditor;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationDocumentSplitterEffectiveBodyTest {

    @Test
    void shouldResolveInheritedReferencedBodyWithoutFetchingIt() {
        // Given
        Fixture fixture = fixture(true);
        List<String> requests = new ArrayList<>();
        NodeProvider provider = provider(
                fixture, requests);
        DocumentProcessor processor =
                processor(fixture);
        try {
            // When
            CoordinationDocumentSplitter.SplitGraph split =
                    new CoordinationDocumentSplitter(
                            processor,
                            provider)
                            .splitDocument(
                                    fixture.root);

            // Then
            assertEquals(
                    fixture.rootBlueId,
                    split.rootBlueId());
            assertEquals(
                    fixture.rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            split.fragmentedRoot()));
            assertFalse(
                    requests.contains(
                            fixture
                                    .inheritedContributionBlueId),
                    "an exact pure-reference descriptor must keep its Source cold");
            assertFalse(
                    requests.contains(
                            fixture.bodyBlueId),
                    "source inspection must not fetch an already-referenced body");
            assertFalse(
                    split.fragments().containsKey(
                            fixture.bodyBlueId),
                    "an inherited pure-reference body is already cold");
        } finally {
            processor.close();
        }
    }

    @Test
    void shouldSplitInheritedInlineBodyThroughItsExactOwningContribution() {
        // Given
        Fixture fixture = fixture(false);
        List<String> requests =
                new ArrayList<>();
        DocumentProcessor processor =
                processor(fixture);
        try {
            // When
            CoordinationDocumentSplitter.SplitGraph split =
                    new CoordinationDocumentSplitter(
                            processor,
                            provider(
                                    fixture,
                                    requests))
                            .splitDocument(
                                    fixture.root);

            // Then
            Node sourceFragment =
                    split.fragments().get(
                            fixture
                                    .inheritedContributionBlueId);
            Node bodyFragment =
                    split.fragments().get(
                            fixture.bodyBlueId);
            assertEquals(
                    fixture.rootBlueId,
                    split.rootBlueId());
            assertEquals(
                    fixture.rootBlueId,
                    BlueIdCalculator.calculateBlueId(
                            split.fragmentedRoot()));
            assertNotNull(
                    sourceFragment,
                    "the exact owning Source contribution must remain reachable");
            assertEquals(
                    fixture.inheritedContributionBlueId,
                    BlueIdCalculator.calculateBlueId(
                            sourceFragment));
            Node sourceBody =
                    NodePathEditor.getOrNull(
                            sourceFragment,
                            "/steps");
            assertNotNull(
                    sourceBody);
            assertTrue(
                    sourceBody.isReferenceOnly(),
                    "the owning Source must retain its identity through an exact cold edge");
            assertEquals(
                    fixture.bodyBlueId,
                    sourceBody.getBlueId());
            assertNotNull(
                    bodyFragment,
                    "the exact inherited inline body must be retained");
            assertEquals(
                    fixture.bodyBlueId,
                    BlueIdCalculator.calculateBlueId(
                            bodyFragment));
            List<Node> providedSource =
                    split.provider().fetchByBlueId(
                            fixture
                                    .inheritedContributionBlueId);
            assertEquals(
                    1,
                    providedSource.size());
            assertEquals(
                    fixture.inheritedContributionBlueId,
                    BlueIdCalculator.calculateBlueId(
                            providedSource.get(0)));
            assertEquals(
                    1,
                    Collections.frequency(
                            requests,
                            fixture
                                    .inheritedContributionBlueId),
                    "only the exact owning Source contribution may be opened");
            assertFalse(
                    requests.contains(
                            fixture.bodyBlueId),
                    "splitting inline content must not ask the provider for that body");
        } finally {
            processor.close();
        }
    }

    private static DocumentProcessor processor(
            Fixture fixture) {
        Map<String, List<String>> paths =
                Collections.singletonMap(
                        "/",
                        Collections.<String>emptyList());
        Map<String, List<EffectiveContractSnapshot>>
                contracts =
                Collections.singletonMap(
                        "/",
                        Collections.singletonList(
                                fixture.snapshot));
        EffectiveFragmentationCatalog catalog =
                new EffectiveFragmentationCatalog(
                        fixture.rootBlueId,
                        paths,
                        contracts);
        return new DocumentProcessor() {
            @Override
            public EffectiveFragmentationCatalog
            effectiveFragmentationCatalog(
                    Node document) {
                assertEquals(
                        fixture.rootBlueId,
                        BlueIdCalculator.calculateBlueId(
                                document));
                return catalog;
            }
        };
    }

    private static NodeProvider provider(
            Fixture fixture,
            List<String> requests) {
        Map<String, Node> content =
                new LinkedHashMap<>();
        content.put(
                fixture.inheritedContributionBlueId,
                fixture.inheritedContribution);
        content.put(
                fixture.bodyBlueId,
                fixture.body);
        return blueId -> {
            requests.add(blueId);
            Node found = content.get(blueId);
            return found != null
                    ? Collections.singletonList(
                            found.clone())
                    : null;
        };
    }

    private static Fixture fixture(
            boolean referencedBody) {
        Node body =
                new Node().items(
                        new Node().properties(
                                "label",
                                new Node().value(
                                        "inherited")));
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        body);
        Node inheritedContribution =
                new Node()
                        .type(new Node().blueId(
                                SequentialWorkflowOperation
                                        .blueId()))
                        .properties(
                                "steps",
                                referencedBody
                                        ? new Node().blueId(
                                                bodyBlueId)
                                        : body.clone());
        String inheritedContributionBlueId =
                BlueIdCalculator.calculateBlueId(
                        inheritedContribution);
        Node directContribution =
                new Node()
                        .type(new Node().blueId(
                                inheritedContributionBlueId))
                        .properties(
                                "channel",
                                new Node().value(
                                        "timeline"));
        String directContributionBlueId =
                BlueIdCalculator.calculateBlueId(
                        directContribution);
        Node root =
                new Node().contracts(
                        new Node().properties(
                                "workflow",
                                directContribution));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        root);
        ExecutableBodySourceDescriptor sourceDescriptor =
                new ExecutableBodySourceDescriptor(
                        "/",
                        "workflow",
                        SequentialWorkflowOperation
                                .blueId(),
                        "steps",
                        bodyBlueId,
                        Arrays.asList(
                                inheritedContributionBlueId,
                                directContributionBlueId),
                        inheritedContributionBlueId,
                        "/steps",
                        referencedBody);
        EffectiveContractSnapshot snapshot =
                EffectiveContractSnapshot
                        .builder("/", "workflow")
                        .sourceContribution(
                                inheritedContributionBlueId)
                        .sourceContribution(
                                directContributionBlueId)
                        .effectiveTypeBlueId(
                                SequentialWorkflowOperation
                                        .blueId())
                        .role("handler")
                        .executableBody(
                                "steps",
                                bodyBlueId)
                        .executableBodySourceDescriptor(
                                "steps",
                                sourceDescriptor)
                        .build();
        return new Fixture(
                root,
                rootBlueId,
                body,
                bodyBlueId,
                inheritedContribution,
                inheritedContributionBlueId,
                snapshot);
    }

    private static final class Fixture {
        private final Node root;
        private final String rootBlueId;
        private final Node body;
        private final String bodyBlueId;
        private final Node inheritedContribution;
        private final String inheritedContributionBlueId;
        private final EffectiveContractSnapshot snapshot;

        private Fixture(
                Node root,
                String rootBlueId,
                Node body,
                String bodyBlueId,
                Node inheritedContribution,
                String inheritedContributionBlueId,
                EffectiveContractSnapshot snapshot) {
            this.root = root;
            this.rootBlueId = rootBlueId;
            this.body = body;
            this.bodyBlueId = bodyBlueId;
            this.inheritedContribution =
                    inheritedContribution;
            this.inheritedContributionBlueId =
                    inheritedContributionBlueId;
            this.snapshot = snapshot;
        }
    }
}
