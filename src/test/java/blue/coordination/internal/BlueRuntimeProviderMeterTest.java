package blue.coordination.internal;

import blue.language.api.NodeProviderOutcome;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.repo.BlueRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueRuntimeProviderMeterTest {

    @Test
    void leafMeteringPreservesSequentialAndCyclicProviderCapabilities() {
        // given

        EngineMetrics metrics = new EngineMetrics();
        WholeObjectStore objects = new WholeObjectStore(metrics);

        // when
        try (BlueRuntime runtime = BlueRuntime.create(objects, metrics)) {

            // then
            assertTrue(runtime.nodeProvider()
                    instanceof SequentialNodeProvider);
            assertTrue(runtime.nodeProvider()
                    instanceof CyclicAwareNodeProvider);
            SequentialNodeProvider sequential =
                    (SequentialNodeProvider) runtime.nodeProvider();
            List<NodeProvider> leaves = sequential.getNodeProviders();
            assertEquals(4, leaves.size());
            NodeProvider repositoryLeaf = leaves.get(2);
            assertTrue(repositoryLeaf instanceof CyclicAwareNodeProvider);

            BlueRepository repository = BlueRepository.current();
            String cyclicMemberBlueId = repository.qualifiedNames().stream()
                    .map(name -> repository.definition(name).orElseThrow()
                            .blueId())
                    .filter(blueId -> blueId.indexOf('#') >= 0)
                    .findFirst()
                    .orElseThrow();

            assertEquals(
                    NodeProviderOutcome.FOUND,
                    new VerifyingNodeProvider(runtime.nodeProvider())
                            .fetchResultByBlueId(cyclicMemberBlueId)
                            .outcome());

            long beforeVerified = exactReads(metrics);
            assertEquals(
                    NodeProviderOutcome.FOUND,
                    new VerifyingNodeProvider(repositoryLeaf)
                            .fetchResultByBlueId(cyclicMemberBlueId)
                            .outcome());
            assertEquals(1L, exactReads(metrics) - beforeVerified);

            long beforeLegacy = exactReads(metrics);
            assertFalse(repositoryLeaf.fetchByBlueId(cyclicMemberBlueId)
                    .isEmpty());
            assertEquals(1L, exactReads(metrics) - beforeLegacy);

            long beforeTyped = exactReads(metrics);
            assertEquals(
                    NodeProviderOutcome.FOUND,
                    repositoryLeaf.fetchResultByBlueId(cyclicMemberBlueId)
                            .outcome());
            assertEquals(1L, exactReads(metrics) - beforeTyped);
        }
    }

    private static long exactReads(EngineMetrics metrics) {
        return metrics.snapshot().counters().getOrDefault(
                BlueRuntime.PROVIDER_EXACT_NODE_READS, 0L);
    }
}
