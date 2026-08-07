package blue.coordination.examples.support;

import blue.coordination.engine.CoordinationFragmentSliceLoader;
import blue.coordination.engine.CoordinationFragmentSlicePlanner;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentSlice;
import blue.coordination.engine.api.CoordinationFragmentSlicePlan;
import blue.coordination.engine.api.FragmentEdgeRecord;
import blue.coordination.engine.api.FragmentRootRecord;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-batch physical slice reconstruction and fail-closed tamper proof. */
final class CoordinationPhysicalSliceLoaderTest {

    @Test
    void shouldLoadAndReconstructOnlyTheSelectedEmbeddedRootClosure() {
        // given
        Fixture fixture = fixture();
        fixture.store.resetReadCounts();

        // when
        CoordinationFragmentSlicePlan plan =
                new CoordinationFragmentSlicePlanner().plan(
                        fixture.inventory, "/emb1");
        CoordinationFragmentSlice loaded =
                new CoordinationFragmentSliceLoader().load(
                        fixture.store, plan);

        // then
        assertEquals(1L, fixture.store.batchReadCount());
        assertEquals(0L, fixture.store.singleReadCount());
        assertEquals(2L, fixture.store.requestedIdentityCount());
        assertEquals(List.of(fixture.emb1Id, fixture.emb2Id).stream()
                        .sorted(blue.language.processor.ExternalOrderKey
                                ::compareTextCodePoints)
                        .toList(),
                loaded.fragmentBlueIds());
        assertEquals(2, loaded.exactFragments().size());
        assertFalse(loaded.exactFragments().containsKey(fixture.siblingId));
        assertTrue(loaded.fragmentCount()
                < fixture.inventory.fragmentBlueIds().size());
        assertEquals(NodeWireForm.get(fixture.expandedEmb1),
                NodeWireForm.get(loaded.exactSelectedRoot()));
    }

    @Test
    void shouldRejectAStoreBodyThatDoesNotMatchItsSelectedIdentity() {
        // given
        Fixture fixture = fixture();
        CoordinationFragmentSlicePlan plan =
                new CoordinationFragmentSlicePlanner().plan(
                        fixture.inventory, "/emb1");
        CoordinationFragmentStore tampered = new TamperingStore(
                fixture.store,
                fixture.emb2Id,
                parse("counter: 999"));

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CoordinationFragmentSliceLoader().load(
                        tampered, plan));

        // then
        assertTrue(failure.getMessage().contains("identity mismatch"));
    }

    private static Fixture fixture() {
        Node emb2 = parse("""
                kind: emb2
                counter: 2
                """);
        String emb2Id = blueId(emb2);
        Node emb1 = parse("""
                kind: emb1
                emb2:
                  blueId: %s
                """.formatted(emb2Id));
        String emb1Id = blueId(emb1);
        Node expandedEmb1 = parse("""
                kind: emb1
                emb2:
                  kind: emb2
                  counter: 2
                """);
        assertEquals(emb1Id, blueId(expandedEmb1),
                "Expanded and physical-reference forms must be identical");
        Node sibling = parse("kind: sibling");
        String siblingId = blueId(sibling);
        Node root = parse("""
                kind: root
                emb1:
                  blueId: %s
                sibling:
                  blueId: %s
                """.formatted(emb1Id, siblingId));
        String rootId = blueId(root);

        Map<String, Node> bodies = new LinkedHashMap<>();
        bodies.put(rootId, root);
        bodies.put(emb1Id, emb1);
        bodies.put(emb2Id, emb2);
        bodies.put(siblingId, sibling);
        CoordinationFragmentInventory inventory =
                new CoordinationFragmentInventory(
                        CoordinationFragmentInventory.SCHEMA_VERSION,
                        CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                        CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                        rootId,
                        new ArrayList<>(bodies.keySet()),
                        List.of(
                                root(rootId,
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT,
                                        ""),
                                root(emb1Id,
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/emb1"),
                                root(emb2Id,
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/emb1/emb2"),
                                root(siblingId,
                                        CoordinationDocumentSplitter
                                                .FragmentRootKind.DOCUMENT_SCOPE,
                                        "/sibling")),
                        List.of(
                                edge(rootId, rootId, "", "/emb1", "/emb1",
                                        emb1Id),
                                edge(rootId, emb1Id, "/emb1", "/emb1/emb2",
                                        "/emb2", emb2Id),
                                edge(rootId, rootId, "", "/sibling", "/sibling",
                                        siblingId)),
                        List.of());
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                bodies);
        store.putInventory(inventory);
        return new Fixture(store, inventory, emb1Id, emb2Id,
                siblingId, expandedEmb1);
    }

    private static FragmentRootRecord root(
            String blueId,
            CoordinationDocumentSplitter.FragmentRootKind kind,
            String path) {
        return new FragmentRootRecord(blueId, kind, path);
    }

    private static FragmentEdgeRecord edge(
            String semanticRootBlueId,
            String ownerBlueId,
            String ownerScopePath,
            String absolutePointer,
            String ownerRelativePointer,
            String childBlueId) {
        return new FragmentEdgeRecord(
                CoordinationDocumentSplitter.EDGE_METADATA_SCHEMA_ID,
                CoordinationDocumentSplitter.FragmentRootKind.DOCUMENT,
                semanticRootBlueId,
                ownerBlueId,
                ownerScopePath,
                absolutePointer,
                ownerRelativePointer,
                childBlueId,
                CoordinationDocumentSplitter.EdgeKind.DOCUMENT_DIRECT_CHILD,
                false,
                true,
                null,
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin.NONE,
                null,
                null,
                null,
                null,
                null,
                List.of());
    }

    private static String blueId(Node node) {
        return MyOsDemoKernel.runtime().calculateBlueId(node);
    }

    private static Node parse(String yaml) {
        return MyOsDemoKernel.runtime().parseSourceYaml(yaml);
    }

    private record Fixture(
            InMemoryCoordinationFragmentStore store,
            CoordinationFragmentInventory inventory,
            String emb1Id,
            String emb2Id,
            String siblingId,
            Node expandedEmb1) { }

    private static final class TamperingStore
            implements CoordinationFragmentStore {
        private final CoordinationFragmentStore delegate;
        private final String tamperedBlueId;
        private final Node tamperedBody;

        private TamperingStore(
                CoordinationFragmentStore delegate,
                String tamperedBlueId,
                Node tamperedBody) {
            this.delegate = delegate;
            this.tamperedBlueId = tamperedBlueId;
            this.tamperedBody = tamperedBody.clone();
        }

        @Override
        public String fragmentationProfileIdentity() {
            return delegate.fragmentationProfileIdentity();
        }

        @Override
        public Map<String, NodeProviderResult> readAll(
                Collection<String> blueIds) {
            Map<String, NodeProviderResult> result = new LinkedHashMap<>(
                    delegate.readAll(blueIds));
            if (result.containsKey(tamperedBlueId)) {
                result.put(tamperedBlueId, NodeProviderResult.found(
                        List.of(tamperedBody.clone())));
            }
            return result;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return delegate.fetchResultByBlueId(blueId);
        }

        @Override
        public Node read(String profileIdentity, String blueId) {
            return delegate.read(profileIdentity, blueId);
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment) {
            return delegate.putIfAbsent(
                    profileIdentity, blueId, exactFragment);
        }

        @Override
        public boolean putAllIfAbsent(
                String profileIdentity,
                Map<String, Node> exactFragments) {
            return delegate.putAllIfAbsent(profileIdentity, exactFragments);
        }

        @Override
        public void putProcessingViews(Map<String, Node> exactProcessingViews) {
            delegate.putProcessingViews(exactProcessingViews);
        }

        @Override
        public void putInventory(CoordinationFragmentInventory inventory) {
            delegate.putInventory(inventory);
        }

        @Override
        public CoordinationFragmentInventory requireInventory(
                String inventoryIdentity) {
            return delegate.requireInventory(inventoryIdentity);
        }
    }
}
