package blue.coordination.processor;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.repo.BlueRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Current, immutable Language/Contracts composition shared by Coordination
 * benchmarks.
 *
 * <p>The fixture deliberately exposes the named modular services instead of
 * recreating the removed mutable {@code Blue} aggregate API. Repository
 * content comes from the locally built fixed Repository dependency selected
 * by the Coordination build.</p>
 */
final class CoordinationBenchmarkRuntime implements AutoCloseable {
    private final BlueLanguage language;
    private final BlueContracts contracts;
    private final DocumentProcessor processor;

    private CoordinationBenchmarkRuntime(
            BlueLanguage language,
            BlueContracts contracts,
            DocumentProcessor processor) {
        this.language = language;
        this.contracts = contracts;
        this.processor = processor;
    }

    static CoordinationBenchmarkRuntime create() {
        ClassLoader classLoader = CoordinationBenchmarkRuntime.class
                .getClassLoader();
        BlueRepository repository = BlueRepository.current(classLoader);
        NodeProvider provider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                repository.nodeProvider());
        Map<String, String> imports =
                new LinkedHashMap<String, String>();
        imports.putAll(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        imports.putAll(repository.preprocessingAliases());
        BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .preprocessingAliases(imports)
                .environmentImports(imports)
                .build();
        CoordinationProcessorOptions options =
                CoordinationProcessorOptions.builder()
                        .language(language)
                        .build();
        DocumentProcessor processor = CoordinationProcessors.configure(
                DocumentProcessor.builder().nodeProvider(provider),
                options)
                .build();
        BlueContracts contracts = CoordinationProcessors.contracts(
                language, options);
        return new CoordinationBenchmarkRuntime(
                language,
                contracts,
                processor);
    }

    BlueLanguage language() {
        return language;
    }

    DocumentProcessor processor() {
        return processor;
    }

    BlueContracts contracts() {
        return contracts;
    }

    Node preprocess(Node source) {
        return language.preprocessing().preprocess(
                Objects.requireNonNull(source, "source"));
    }

    Node resolve(Node source) {
        return language.resolution().resolve(
                Objects.requireNonNull(source, "source"));
    }

    String nodeToJson(Node node) {
        return language.codec().write(
                Objects.requireNonNull(node, "node"),
                BlueFormat.JSON);
    }

    @Override
    public void close() {
        contracts.close();
        processor.close();
        language.close();
    }
}
