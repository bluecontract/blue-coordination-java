package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.DocumentProcessingResult;
import blue.language.merge.ResolvedSnapshot;
import blue.language.resolve.MinimizedOverlayBuilder;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BootstrapDocumentTransportRoundTripTest {

    @Test
    void shouldRoundTripInitializedBootstrapThroughMinimizedTransport() {
        // given
        BlueRepository repository = BlueRepository.current();
        CoordinationTestRuntime writer = configured(repository);
        Node source = writer.parseSourceYaml(bootstrapSource());
        source.blue(repository.importsDirective());

        // when
        ResolvedSnapshot authored = writer.resolveToSnapshot(source);
        DocumentProcessingResult initialization = writer.initializeDocument(authored);
        ResolvedSnapshot initialized =
                ProcessingResultTestSupport.snapshot(writer, initialization);
        Node minimized = new MinimizedOverlayBuilder().build(
                initialized.resolvedRoot());
        CoordinationTestRuntime reader = configured(repository);
        Node stored = reader.parseSourceJson(writer.nodeToJson(minimized));
        ResolvedSnapshot reloaded = reader.resolveToSnapshot(stored);

        // then
        assertNotNull(initialized.resolvedRoot().getAsNode(
                        "/contracts/declineBootstrap/request/type/type/inResponseTo/type/requestId"),
                "cold resolution must fully materialize nested inherited Request metadata");
        assertFalse(minimized.getContracts().getProperties().containsKey("declineBootstrap"),
                "the minimized overlay must omit type-derived bootstrap operations");
        assertEquals(initialized.blueId(), reloaded.blueId(), () ->
                "canonical difference: " + firstDifference(
                        NodeWireForm.get(initialized.canonicalRoot()),
                        NodeWireForm.get(reloaded.canonicalRoot()), ""));
        assertEquals(initialized.frozenResolvedRoot().resolvedStructuralKey(),
                reloaded.frozenResolvedRoot().resolvedStructuralKey(), () ->
                        "resolved difference: " + firstDifference(
                                NodeWireForm.get(initialized.resolvedRoot()),
                                NodeWireForm.get(reloaded.resolvedRoot()), ""));
    }

    private static CoordinationTestRuntime configured(
            BlueRepository repository) {
        return CoordinationTestResources.configuredBlue(repository);
    }

    private static String bootstrapSource() {
        return String.join("\n",
                "type: Bootstrap/Document Bootstrap",
                "status:",
                "  type: Coordination/Status Pending",
                "contracts:",
                "  bootstrapProviderChannel:",
                "    type: Coordination/Timeline Channel",
                "    timeline:",
                "      type: Coordination/Timeline",
                "      providerId: test-provider",
                "      timelineId: bootstrap-provider",
                "    actor:",
                "      type: Coordination/Principal Actor");
    }

    private static String firstDifference(Object expected, Object actual, String path) {
        if (Objects.equals(expected, actual)) {
            return null;
        }
        if (expected instanceof Map && actual instanceof Map) {
            Map<?, ?> expectedMap = (Map<?, ?>) expected;
            Map<?, ?> actualMap = (Map<?, ?>) actual;
            for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
                String childPath = path + "/" + entry.getKey();
                if (!actualMap.containsKey(entry.getKey())) {
                    return childPath;
                }
                String child = firstDifference(
                        entry.getValue(), actualMap.get(entry.getKey()), childPath);
                if (child != null) {
                    return child;
                }
            }
            for (Object key : actualMap.keySet()) {
                if (!expectedMap.containsKey(key)) {
                    return path + "/" + key;
                }
            }
            return path.isEmpty() ? "/" : path;
        }
        if (expected instanceof List && actual instanceof List) {
            List<?> expectedList = (List<?>) expected;
            List<?> actualList = (List<?>) actual;
            int commonSize = Math.min(expectedList.size(), actualList.size());
            for (int index = 0; index < commonSize; index++) {
                String child = firstDifference(expectedList.get(index), actualList.get(index),
                        path + "/" + index);
                if (child != null) {
                    return child;
                }
            }
            return path + "/" + commonSize;
        }
        return path.isEmpty() ? "/" : path;
    }
}
