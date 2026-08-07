package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.EffectiveFragmentationCatalog;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.repo.BlueRepository;

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
        return splitWithCurrentCatalog(exactRoot);
    }

    /** Uses the current public modular Contracts facade and real catalog. */
    static CoordinationDocumentSplitter.SplitGraph
    splitCollectionDocument(Node exactRoot) {
        return splitWithCurrentCatalog(exactRoot);
    }

    /**
     * Captures the exact public Language scope-plan view consumed by the
     * splitter together with the split derived from that same immutable
     * catalog. This is structural inspection only; it does not execute
     * PROCESS or manufacture execution evidence.
     */
    static CollectionInspection inspectCollectionDocument(
            Node exactRoot) {
        ContractProcessorRegistry registry =
                standardRegistry();
        BlueRepository repository = BlueRepository.current();
        NodeProvider provider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                registry.exactTypeProvider(),
                repository.nodeProvider());
        try (BlueLanguage language = BlueLanguage.builder()
                     .nodeProvider(provider)
                     .preprocessingAliases(
                             repository.preprocessingAliases())
                     .build();
             BlueContracts contracts =
                     BlueContracts.builder(language.processing())
                             .runtimeRegistry(registry)
                             .build()) {
            EffectiveFragmentationCatalog catalog =
                    contracts.effectiveFragmentationCatalog(exactRoot);
            CoordinationDocumentSplitter.SplitGraph split =
                    CoordinationDocumentSplitter.fromEffectiveCatalog(
                                    ignoredRoot -> catalog,
                                    provider)
                            .splitDocument(exactRoot);
            return new CollectionInspection(catalog, split);
        }
    }

    private static CoordinationDocumentSplitter.SplitGraph
    splitWithCurrentCatalog(Node exactRoot) {
        ContractProcessorRegistry registry =
                standardRegistry();
        BlueRepository repository = BlueRepository.current();
        NodeProvider provider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                registry.exactTypeProvider(),
                repository.nodeProvider());
        try (BlueLanguage language = BlueLanguage.builder()
                     .nodeProvider(provider)
                     .preprocessingAliases(
                             repository.preprocessingAliases())
                     .build();
             BlueContracts contracts =
                     BlueContracts.builder(language.processing())
                             .runtimeRegistry(registry)
                             .build()) {
            EffectiveFragmentationCatalog catalog =
                    contracts.effectiveFragmentationCatalog(exactRoot);
            /*
             * The returned SplitGraph is used after this short-lived
             * Language/Contracts inspection scope closes.  Bind it to the
             * immutable catalog without retaining the borrowed Contracts
             * runtime as a lazy fallback. These fixtures are exact inline
             * Roots; authored cold references must remain cold.
             */
            return CoordinationDocumentSplitter.fromEffectiveCatalog(
                            ignoredRoot -> catalog,
                            null)
                    .splitDocument(exactRoot);
        }
    }

    private static ContractProcessorRegistry standardRegistry() {
        return CoordinationProcessors.configure(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults())
                .build();
    }

    static final class CollectionInspection {

        private final EffectiveFragmentationCatalog catalog;
        private final CoordinationDocumentSplitter.SplitGraph split;

        private CollectionInspection(
                EffectiveFragmentationCatalog catalog,
                CoordinationDocumentSplitter.SplitGraph split) {
            this.catalog = catalog;
            this.split = split;
        }

        EffectiveFragmentationCatalog catalog() {
            return catalog;
        }

        CoordinationDocumentSplitter.SplitGraph split() {
            return split;
        }
    }
}
