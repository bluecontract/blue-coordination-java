package blue.coordination.processor;

import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        // given
        Fixture fixture = fixture(true);
        List<String> requests = new ArrayList<>();
        NodeProvider provider = provider(
                fixture, requests);
        CoordinationDocumentSplitter splitter =
                splitter(fixture, provider);
        requests.clear();

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitDocument(fixture.root);

        // then
        assertEquals(
                    fixture.rootBlueId,
                    split.rootBlueId());
        assertEquals(
                    fixture.rootBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
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
    }

    @Test
    void shouldSplitInheritedInlineBodyThroughItsExactOwningContribution() {
        // given
        Fixture fixture = fixture(false);
        List<String> requests =
                new ArrayList<>();
        CoordinationDocumentSplitter splitter =
                splitter(fixture, provider(fixture, requests));
        requests.clear();

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitDocument(fixture.root);

        // then
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
                    DirectBlueIdCalculator.calculateBlueId(
                            split.fragmentedRoot()));
        assertNotNull(
                    sourceFragment,
                    "the exact owning Source contribution must remain reachable: "
                            + split.fragments().keySet());
        assertEquals(
                    fixture.inheritedContributionBlueId,
                    DirectBlueIdCalculator.calculateBlueId(
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
                    DirectBlueIdCalculator.calculateBlueId(
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
                    DirectBlueIdCalculator.calculateBlueId(
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
    }

    @Test
    void shouldRehydrateInlineBodyFromAnAlreadyFragmentedContribution() {
        // given
        Fixture fixture = fixture(false);
        Node fragmentedContribution =
                fixture.inheritedContribution.clone();
        NodePathEditor.put(
                fragmentedContribution,
                "/steps",
                new Node().blueId(fixture.bodyBlueId));
        List<String> requests = new ArrayList<>();
        CoordinationDocumentSplitter splitter = splitter(
                fixture,
                provider(fixture, new ArrayList<>()),
                provider(fixture, requests, fragmentedContribution));
        requests.clear();

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitDocument(fixture.root);

        // then
        assertNotNull(split.fragments().get(
                fixture.inheritedContributionBlueId));
        assertNotNull(split.fragments().get(fixture.bodyBlueId));
        assertEquals(1, Collections.frequency(
                requests, fixture.inheritedContributionBlueId));
        assertEquals(1, Collections.frequency(
                requests, fixture.bodyBlueId));
    }

    private static CoordinationDocumentSplitter splitter(
            Fixture fixture,
            NodeProvider provider) {
        return splitter(fixture, provider, provider);
    }

    private static CoordinationDocumentSplitter splitter(
            Fixture fixture,
            NodeProvider catalogContentProvider,
            NodeProvider localProvider) {
        BlueRuntimeTypeRegistry runtimeTypes =
                BlueRuntimeTypeRegistry.getDefault();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                fixture.handlerTypeBlueId,
                                fixture.handlerType,
                                new InheritedBodyHandlerProcessor())
                        .build();
        NodeProvider catalogProvider =
                new SequentialNodeProvider(
                        runtimeTypes.asProcessorSnapshotProvider(),
                        registry.exactTypeProvider(),
                        catalogContentProvider);
        EffectiveFragmentationCatalog catalog;
        try (BlueLanguage language = BlueLanguage.builder()
                     .nodeProvider(catalogProvider)
                     .build();
             BlueContracts contracts =
                     BlueContracts.builder(language.processing())
                             .runtimeRegistry(registry)
                             .build()) {
            catalog = contracts.effectiveFragmentationCatalog(
                    fixture.root);
        }
        return CoordinationDocumentSplitter.fromEffectiveCatalog(
                document -> {
                    assertEquals(
                            fixture.rootBlueId,
                            DirectBlueIdCalculator.calculateBlueId(
                                    document));
                    return catalog;
                },
                localProvider);
    }

    private static NodeProvider provider(
            Fixture fixture,
            List<String> requests) {
        return provider(
                fixture,
                requests,
                fixture.inheritedContribution);
    }

    private static NodeProvider provider(
            Fixture fixture,
            List<String> requests,
            Node inheritedContribution) {
        Map<String, Node> content =
                new LinkedHashMap<>();
        content.put(
                fixture.handlerTypeBlueId,
                fixture.handlerType);
        content.put(
                fixture.scopeTypeBlueId,
                fixture.scopeType);
        content.put(
                fixture.inheritedContributionBlueId,
                inheritedContribution);
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
                DirectBlueIdCalculator.calculateBlueId(
                        body);
        Node handlerType =
                new Node()
                        .name("Inherited Body Handler")
                        .type(new Node().blueId(
                                RuntimeBlueIds.HANDLER));
        String handlerTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        handlerType);
        Node inheritedContribution =
                new Node()
                        .properties(
                                "steps",
                                referencedBody
                                        ? new Node().blueId(
                                                bodyBlueId)
                                        : body.clone());
        String inheritedContributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        inheritedContribution);
        Node scopeType =
                new Node()
                        .name("Inherited Body Scope")
                        .contracts(
                                new Node().properties(
                                        "workflow",
                                        inheritedContribution));
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        scopeType);
        Node directContribution =
                new Node()
                        .type(new Node().blueId(
                                handlerTypeBlueId))
                        .properties(
                                "channel",
                                new Node().value(
                                        "timeline"));
        Node root =
                new Node()
                        .type(new Node().blueId(
                                scopeTypeBlueId))
                        .contracts(
                                new Node().properties(
                                        "workflow",
                                        directContribution));
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        root);
        return new Fixture(
                root,
                rootBlueId,
                body,
                bodyBlueId,
                handlerType,
                handlerTypeBlueId,
                inheritedContribution,
                inheritedContributionBlueId,
                scopeType,
                scopeTypeBlueId);
    }

    private static final class Fixture {
        private final Node root;
        private final String rootBlueId;
        private final Node body;
        private final String bodyBlueId;
        private final Node handlerType;
        private final String handlerTypeBlueId;
        private final Node inheritedContribution;
        private final String inheritedContributionBlueId;
        private final Node scopeType;
        private final String scopeTypeBlueId;

        private Fixture(
                Node root,
                String rootBlueId,
                Node body,
                String bodyBlueId,
                Node handlerType,
                String handlerTypeBlueId,
                Node inheritedContribution,
                String inheritedContributionBlueId,
                Node scopeType,
                String scopeTypeBlueId) {
            this.root = root;
            this.rootBlueId = rootBlueId;
            this.body = body;
            this.bodyBlueId = bodyBlueId;
            this.handlerType = handlerType;
            this.handlerTypeBlueId =
                    handlerTypeBlueId;
            this.inheritedContribution =
                    inheritedContribution;
            this.inheritedContributionBlueId =
                    inheritedContributionBlueId;
            this.scopeType = scopeType;
            this.scopeTypeBlueId = scopeTypeBlueId;
        }
    }

    public static final class InheritedBodyHandler
            extends HandlerContract {
        private Node steps;

        public InheritedBodyHandler() {
        }

        public Node getSteps() {
            return steps;
        }

        public void setSteps(Node steps) {
            this.steps = steps;
        }
    }

    private static final class InheritedBodyHandlerProcessor
            implements HandlerProcessor<InheritedBodyHandler> {
        @Override
        public Class<InheritedBodyHandler> contractType() {
            return InheritedBodyHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("steps");
        }

        @Override
        public void execute(
                InheritedBodyHandler contract,
                ProcessorExecutionContext context) {
            throw new UnsupportedOperationException(
                    "Catalog inspection must not execute handlers");
        }
    }
}
