package blue.coordination.internal;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RepositoryDemandSeededExactCacheTest {

    @Test
    void repeatedNestedLookupUsesImmutableCacheWithoutAnotherOwnerRead() {
        // given
        Node child = new Node().value("nested");
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(child);
        Node root = new Node().properties("child", child);
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        root.blueId(rootBlueId);
        CountingProvider delegate = new CountingProvider()
                .put(rootBlueId, List.of(root));
        BlueRuntime.RepositoryNodeProviders providers =
                BlueRuntime.repositoryNodeProviders(delegate);

        // when
        providers.repositoryProvider().fetchByBlueId(rootBlueId);
        Node first = providers.exactNodes()
                .fetchByBlueId(childBlueId).get(0);
        first.value("caller mutation");
        Node second = providers.exactNodes()
                .fetchByBlueId(childBlueId).get(0);

        // then
        assertEquals(1, delegate.reads());
        assertEquals("nested", second.getValue());
    }

    @Test
    void observerPreservesLegacyListsAndEveryTypedOutcomeExactly() {
        // given
        Node foundNode = new Node().value("found");
        List<Node> legacy = new ArrayList<>(List.of(foundNode));
        NodeProviderResult found = NodeProviderResult.found(legacy);
        NodeProviderResult notFound = NodeProviderResult.notFound();
        NodeProviderResult unavailable = NodeProviderResult.unavailable(
                "temporarily absent");
        NodeProviderResult invalid = NodeProviderResult.invalidEvidence(
                "bad proof");
        OutcomeProvider delegate = new OutcomeProvider(
                legacy, found, notFound, unavailable, invalid);
        BlueRuntime.RepositoryNodeProviders providers =
                BlueRuntime.repositoryNodeProviders(delegate);
        // when
        NodeProvider observing = providers.repositoryProvider();

        // then
        assertSame(legacy, observing.fetchByBlueId("legacy"));
        assertSame(found, observing.fetchResultByBlueId("found"));
        assertSame(notFound, observing.fetchResultByBlueId("not-found"));
        assertSame(unavailable,
                observing.fetchResultByBlueId("unavailable"));
        assertSame(invalid, observing.fetchResultByBlueId("invalid"));
        assertEquals(NodeProviderOutcome.FOUND, found.outcome());
        assertNotNull(providers.exactNodes().fetchByBlueId(
                DirectBlueIdCalculator.calculateBlueId(foundNode)));
    }

    @Test
    void declaredIdentityCollisionRejectsWholeBatchAtomically() {
        // given
        Node retained = new Node().value("retained");
        String retainedBlueId = DirectBlueIdCalculator.calculateBlueId(
                retained);
        BlueRuntime.RepositoryExactNodeCache cache =
                new BlueRuntime.RepositoryExactNodeCache();
        cache.observe(List.of(retained));
        List<String> before = cache.cachedBlueIds();

        Node validNew = new Node().value("valid-new");
        String validNewBlueId = DirectBlueIdCalculator.calculateBlueId(
                validNew);
        Node malformed = new Node()
                .value("different-content")
                .blueId(retainedBlueId);

        // when
        assertThrows(IllegalStateException.class,
                () -> cache.observe(List.of(validNew, malformed)));

        // then
        assertEquals(before, cache.cachedBlueIds());
        assertNull(cache.fetchByBlueId(validNewBlueId));
        assertNotNull(cache.fetchByBlueId(retainedBlueId));
    }

    @Test
    void publicationOrderIsCodePointDeterministic() {
        // given
        Node alpha = new Node().value("alpha");
        Node omega = new Node().value("omega");
        BlueRuntime.RepositoryExactNodeCache forward =
                new BlueRuntime.RepositoryExactNodeCache();
        BlueRuntime.RepositoryExactNodeCache reverse =
                new BlueRuntime.RepositoryExactNodeCache();

        // when
        forward.observe(List.of(alpha, omega));
        reverse.observe(List.of(omega, alpha));

        // then
        assertEquals(forward.cachedBlueIds(), reverse.cachedBlueIds());
        for (String blueId : forward.cachedBlueIds()) {
            assertEquals(
                    NodeWireForm.get(
                            forward.fetchByBlueId(blueId).get(0)),
                    NodeWireForm.get(
                            reverse.fetchByBlueId(blueId).get(0)));
        }
    }

    @Test
    void concurrentPublicationsRetainEveryDemandedGraphAtomically()
            throws Exception {
        // given
        BlueRuntime.RepositoryExactNodeCache cache =
                new BlueRuntime.RepositoryExactNodeCache();
        List<Node> nodes = new ArrayList<>();
        List<String> blueIds = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            Node node = new Node().value("concurrent-" + index);
            nodes.add(node);
            blueIds.add(DirectBlueIdCalculator.calculateBlueId(node));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Node node : nodes) {
                futures.add(executor.submit(() -> {
                    start.await();
                    cache.observe(List.of(node));
                    return null;
                }));
            }
            // when
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        // then
        for (String blueId : blueIds) {
            assertNotNull(cache.fetchByBlueId(blueId));
        }
        assertEquals(blueIds.size(), cache.cachedBlueIds().size());
    }

    @Test
    void observerIndexesReturnedGraphWithoutChasingReferences() {
        // given
        Node target = new Node().value("must stay unread");
        String targetBlueId = DirectBlueIdCalculator.calculateBlueId(target);
        Node root = new Node().properties(
                "target", new Node().blueId(targetBlueId));
        String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
        root.blueId(rootBlueId);
        CountingProvider delegate = new CountingProvider()
                .put(rootBlueId, List.of(root))
                .put(targetBlueId, List.of(target));
        BlueRuntime.RepositoryNodeProviders providers =
                BlueRuntime.repositoryNodeProviders(delegate);

        // when
        providers.repositoryProvider().fetchByBlueId(rootBlueId);

        // then
        assertEquals(1, delegate.reads());
        assertNull(providers.exactNodes().fetchByBlueId(targetBlueId));
    }

    @Test
    void cyclicCapabilitiesDelegateWhileExactCacheStaysOrdinaryOnly() {
        // given
        String memberBlueId = "cyclic-master#0";
        Node member = new Node()
                .blueId(memberBlueId)
                .properties("body", new Node().value("member"));
        CyclicSetProofResult proof = CyclicSetProofResult.unavailable(
                "proof transport sentinel");
        CyclicProvider delegate = new CyclicProvider(
                memberBlueId, List.of(member), proof);
        BlueRuntime.RepositoryNodeProviders providers =
                BlueRuntime.repositoryNodeProviders(delegate);

        // when
        NodeProvider observing = providers.repositoryProvider();

        // then
        assertInstanceOf(CyclicAwareNodeProvider.class, observing);
        CyclicAwareNodeProvider cyclic =
                (CyclicAwareNodeProvider) observing;
        assertTrue(cyclic.hasVerifiedContentForBlueId(memberBlueId));
        assertSame(proof, cyclic.cyclicSetProofFor(memberBlueId));
        assertSame(delegate.nodes(),
                observing.fetchByBlueId(memberBlueId));
        assertNull(providers.exactNodes().fetchByBlueId(memberBlueId));
        NodeProvider ordinaryCache = providers.exactNodes();
        assertFalse(ordinaryCache instanceof CyclicAwareNodeProvider);
    }

    private static final class CountingProvider implements NodeProvider {
        private final Map<String, List<Node>> nodes = new LinkedHashMap<>();
        private final AtomicInteger reads = new AtomicInteger();

        private CountingProvider put(String blueId, List<Node> value) {
            nodes.put(blueId, value);
            return this;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            reads.incrementAndGet();
            return nodes.get(blueId);
        }

        private int reads() {
            return reads.get();
        }
    }

    private static final class OutcomeProvider implements NodeProvider {
        private final List<Node> legacy;
        private final NodeProviderResult found;
        private final NodeProviderResult notFound;
        private final NodeProviderResult unavailable;
        private final NodeProviderResult invalid;

        private OutcomeProvider(
                List<Node> legacy,
                NodeProviderResult found,
                NodeProviderResult notFound,
                NodeProviderResult unavailable,
                NodeProviderResult invalid) {
            this.legacy = legacy;
            this.found = found;
            this.notFound = notFound;
            this.unavailable = unavailable;
            this.invalid = invalid;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return "legacy".equals(blueId) ? legacy : null;
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return switch (blueId) {
                case "found" -> found;
                case "unavailable" -> unavailable;
                case "invalid" -> invalid;
                default -> notFound;
            };
        }
    }

    private static final class CyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final List<Node> nodes;
        private final CyclicSetProofResult proof;

        private CyclicProvider(
                String memberBlueId,
                List<Node> nodes,
                CyclicSetProofResult proof) {
            this.memberBlueId = memberBlueId;
            this.nodes = nodes;
            this.proof = proof;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return memberBlueId.equals(blueId) ? nodes : null;
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return memberBlueId.equals(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return memberBlueId.equals(blueId)
                    ? proof
                    : CyclicSetProofResult.notFound();
        }

        private List<Node> nodes() {
            return nodes;
        }
    }
}
