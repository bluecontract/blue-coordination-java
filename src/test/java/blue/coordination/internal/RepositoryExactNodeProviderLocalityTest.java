package blue.coordination.internal;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class RepositoryExactNodeProviderLocalityTest {

    @Test
    void nestedCoordinationLookupDoesNotOpenUnrelatedFinosDefinitions()
            throws Exception {
        ClassLoader original = Thread.currentThread()
                .getContextClassLoader();
        BlueRepository discovery = BlueRepository.current(original);
        Node compute = discovery.nodeByName("Coordination/Compute")
                .orElseThrow();
        Node inline = compute.getProperties().get("emitEvents");
        String computeBlueId = compute.getBlueId();
        String inlineBlueId = DirectBlueIdCalculator.calculateBlueId(inline);

        RecordingClassLoader recording = new RecordingClassLoader(original);
        Thread.currentThread().setContextClassLoader(recording);
        try {
            BlueRepository repository = BlueRepository.current(recording);
            String computeResource = repository
                    .definition("Coordination/Compute")
                    .orElseThrow()
                    .resourcePath();
            String unrelatedTradeResource = repository.definition(
                            "FINOS-CDM-6.0-d07/cdm/event/common/Trade")
                    .orElseThrow()
                    .resourcePath();
            recording.clear();

            BlueRuntime.RepositoryNodeProviders providers =
                    BlueRuntime.repositoryNodeProviders(repository);
            List<Node> definition = providers.repositoryProvider()
                    .fetchByBlueId(computeBlueId);
            assertNotNull(definition);
            List<Node> found = providers.exactNodes()
                    .fetchByBlueId(inlineBlueId);

            assertNotNull(found);
            List<String> definitionReads = recording.opened().stream()
                    .filter(path -> path.startsWith(
                            "blue/repo/definitions/"))
                    .toList();
            assertEquals(List.of(computeResource), definitionReads,
                    "only the demanded rc.21 graph may be opened");
            assertFalse(definitionReads.contains(unrelatedTradeResource),
                    "the unrelated rc.21 FINOS Trade graph stayed unread");
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private static final class RecordingClassLoader extends ClassLoader {
        private final List<String> opened = new ArrayList<>();

        private RecordingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            opened.add(name);
            return super.getResourceAsStream(name);
        }

        private void clear() {
            opened.clear();
        }

        private List<String> opened() {
            return List.copyOf(opened);
        }
    }
}
