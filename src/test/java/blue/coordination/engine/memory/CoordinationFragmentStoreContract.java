package blue.coordination.engine.memory;

import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.spi.CoordinationFragmentStore;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class CoordinationFragmentStoreContract {

    abstract CoordinationFragmentStore createStore();

    @Test
    void shouldStoreAndReadAnExactFragmentDefensively() {
        // given
        CoordinationFragmentStore store = createStore();
        Node exact = new Node().properties(
                "value", new Node().value("immutable"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);

        // when
        boolean installed = store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);
        Node firstRead = store.read(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId);
        firstRead.properties("mutated", new Node().value(true));
        Node secondRead = store.read(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId);

        // then
        assertTrue(installed);
        assertNotSame(exact, firstRead);
        assertEquals(NodeWireForm.get(exact), NodeWireForm.get(secondRead));
        assertEquals(blueId,
                DirectBlueIdCalculator.calculateBlueId(secondRead));
    }

    @Test
    void shouldDeduplicateAnIdempotentFragmentWrite() {
        // given
        CoordinationFragmentStore store = createStore();
        Node exact = new Node().value("same");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);

        // when
        boolean first = store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);
        boolean second = store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact.clone());

        // then
        assertTrue(first);
        assertFalse(second);
    }

    @Test
    void shouldTreatAnExactBatchRetryAsIdempotent() {
        // given
        CoordinationFragmentStore store = createStore();
        Node first = new Node().value("batch-same-first");
        Node second = new Node().value("batch-same-second");
        String firstId = DirectBlueIdCalculator.calculateBlueId(first);
        String secondId = DirectBlueIdCalculator.calculateBlueId(second);
        Map<String, Node> exact = mapOf(
                firstId, first,
                secondId, second);
        assertTrue(store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                exact));

        // when
        boolean installed = store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                mapOf(
                        firstId, first.clone(),
                        secondId, second.clone()));

        // then
        assertFalse(installed);
        assertEquals(
                NodeWireForm.get(first),
                NodeWireForm.get(store.read(
                        CoordinationEngineStorageTestFixtures.PROFILE,
                        firstId)));
        assertEquals(
                NodeWireForm.get(second),
                NodeWireForm.get(store.read(
                        CoordinationEngineStorageTestFixtures.PROFILE,
                        secondId)));
    }

    @Test
    void shouldPreserveTheOriginalStateWhenABatchReusesAnIdWithDifferentBytes() {
        // given
        CoordinationFragmentStore store = createStore();
        Node original = new Node().value("immutable-original");
        String originalId = DirectBlueIdCalculator.calculateBlueId(original);
        store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                originalId,
                original);
        Node newFragment = new Node().value("batch-new-fragment");
        String newFragmentId = DirectBlueIdCalculator.calculateBlueId(
                newFragment);
        Node conflictingBytes = new Node().value("different-bytes");
        Map<String, Node> batch = mapOf(
                newFragmentId, newFragment,
                originalId, conflictingBytes);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.putAllIfAbsent(
                        CoordinationEngineStorageTestFixtures.PROFILE,
                        batch));

        // then
        assertTrue(failure.getMessage().contains("identity")
                || failure.getMessage().contains("Conflicting"));
        assertEquals(
                NodeWireForm.get(original),
                NodeWireForm.get(store.read(
                        CoordinationEngineStorageTestFixtures.PROFILE,
                        originalId)));
        assertNull(store.read(
                CoordinationEngineStorageTestFixtures.PROFILE,
                newFragmentId));
    }

    @Test
    void shouldValidateEveryBatchFragmentBeforeInstallingAnyOfThem() {
        // given
        CoordinationFragmentStore store = createStore();
        Node valid = new Node().value("valid");
        Node invalid = new Node().value("invalid");
        String validBlueId = DirectBlueIdCalculator.calculateBlueId(valid);
        Map<String, Node> batch = new LinkedHashMap<String, Node>();
        batch.put(validBlueId, valid);
        batch.put("not-the-invalid-node-blue-id", invalid);

        // when
        assertThrows(
                IllegalStateException.class,
                () -> store.putAllIfAbsent(
                        CoordinationEngineStorageTestFixtures.PROFILE,
                        batch));

        // then
        assertNull(store.read(
                CoordinationEngineStorageTestFixtures.PROFILE,
                validBlueId));
    }

    @Test
    void shouldRejectReadsAndWritesForAnotherFragmentationProfile() {
        // given
        CoordinationFragmentStore store = createStore();
        Node exact = new Node().value("profile-bound");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);

        // when
        IllegalArgumentException writeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfAbsent("another-profile", blueId, exact));
        IllegalArgumentException readFailure = assertThrows(
                IllegalArgumentException.class,
                () -> store.read("another-profile", blueId));

        // then
        assertTrue(writeFailure.getMessage().contains("profile"));
        assertTrue(readFailure.getMessage().contains("profile"));
    }

    @Test
    void shouldReturnOneExactOutcomeForEachBatchIdentityInRequestOrder() {
        // given
        CoordinationFragmentStore store = createStore();
        Node first = new Node().value("first");
        Node second = new Node().value("second");
        String firstId = DirectBlueIdCalculator.calculateBlueId(first);
        String secondId = DirectBlueIdCalculator.calculateBlueId(second);
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                mapOf(firstId, first, secondId, second));
        List<String> requested = Arrays.asList(
                secondId,
                "absent-fragment",
                firstId);

        // when
        Map<String, NodeProviderResult> outcomes = store.readAll(requested);

        // then
        assertEquals(requested,
                new ArrayList<String>(outcomes.keySet()));
        assertEquals(NodeProviderOutcome.FOUND,
                outcomes.get(secondId).outcome());
        assertEquals(NodeProviderOutcome.NOT_FOUND,
                outcomes.get("absent-fragment").outcome());
        assertEquals(NodeProviderOutcome.FOUND,
                outcomes.get(firstId).outcome());
        assertThrows(
                UnsupportedOperationException.class,
                () -> outcomes.put("extra", NodeProviderResult.notFound()));
    }

    @Test
    void shouldBatchProcessViewsWithoutChangingCanonicalPhysicalReads() {
        // given
        CoordinationFragmentStore store = createStore();
        Node child = new Node().value("process-header-child");
        String childBlueId =
                DirectBlueIdCalculator.calculateBlueId(child);
        Node canonical = new Node().properties("header", child.clone());
        String ownerBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonical);
        Node processingView = new Node().properties(
                "header", new Node().blueId(childBlueId));
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                mapOf(ownerBlueId, canonical, childBlueId, child));
        store.putProcessingViews(Collections.singletonMap(
                ownerBlueId, processingView));

        // when
        Node physical = store.readAll(Collections.singleton(ownerBlueId))
                .get(ownerBlueId).nodes().get(0);
        Node process = store.readProcessingAll(
                        Collections.singleton(ownerBlueId))
                .get(ownerBlueId).nodes().get(0);

        // then
        assertFalse(physical.getProperties().get("header")
                .isReferenceOnly());
        assertTrue(process.getProperties().get("header")
                .isReferenceOnly());
        assertEquals(ownerBlueId,
                DirectBlueIdCalculator.calculateBlueId(physical));
        assertEquals(ownerBlueId,
                DirectBlueIdCalculator.calculateBlueId(process));
    }

    @Test
    void shouldExposeNodeProviderFoundAndNotFoundSemantics() {
        // given
        CoordinationFragmentStore store = createStore();
        Node exact = new Node().value("provider");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
        store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);

        // when
        List<Node> found = store.fetchByBlueId(blueId);
        NodeProviderResult foundResult = store.fetchResultByBlueId(blueId);
        NodeProviderResult absentResult =
                store.fetchResultByBlueId("absent-fragment");

        // then
        assertEquals(1, found.size());
        assertEquals(blueId,
                DirectBlueIdCalculator.calculateBlueId(found.get(0)));
        assertEquals(NodeProviderOutcome.FOUND, foundResult.outcome());
        assertEquals(NodeProviderOutcome.NOT_FOUND, absentResult.outcome());
    }

    @Test
    void shouldRejectAnInventoryUntilEveryPhysicalBodyIsPresent() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("inventory-body");

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.putInventory(graph.inventory));

        // then
        assertTrue(failure.getMessage().contains("absent"));
    }

    @Test
    void shouldPersistAndRehydrateAnInventoryIdempotently() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph("inventory");
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                graph.split.fragments());

        // when
        store.putInventory(graph.inventory);
        store.putInventory(graph.inventory);
        CoordinationFragmentInventory restored = store.requireInventory(
                graph.inventory.inventoryIdentity());

        // then
        assertNotSame(graph.inventory, restored);
        assertEquals(graph.inventory.toMap(), restored.toMap());
    }

    @Test
    void shouldFailClosedWhenARequiredInventoryIsAbsent() {
        // given
        CoordinationFragmentStore store = createStore();

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.requireInventory("sha256:absent"));

        // then
        assertTrue(failure.getMessage().contains("absent"));
    }

    @Test
    void shouldPersistAnEmptyInventoryScopedProcessSurfaceImmutably() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph graph =
                CoordinationEngineStorageTestFixtures.graph(
                        "empty-process-surface");
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                graph.split.fragments());

        // when / then
        assertThrows(
                IllegalStateException.class,
                () -> store.putProcessingViews(
                        graph.inventory.inventoryIdentity(),
                        Collections.<String, Node>emptyMap()));
        store.putInventory(graph.inventory);
        store.putProcessingViews(
                graph.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        store.putProcessingViews(
                graph.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        assertThrows(
                IllegalStateException.class,
                () -> store.putProcessingViews(
                        graph.inventory.inventoryIdentity(),
                        Collections.singletonMap(
                                graph.inventory.rootBlueId(),
                                processingView(
                                        graph,
                                        graph.inventory.rootBlueId()))));
    }

    @Test
    void shouldRejectAProcessViewOutsideItsNamedInventory() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph owner =
                CoordinationEngineStorageTestFixtures.graph(
                        "process-owner");
        CoordinationEngineStorageTestFixtures.FragmentGraph other =
                CoordinationEngineStorageTestFixtures.graph(
                        "process-outsider");
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                owner.split.fragments());
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                other.split.fragments());
        store.putInventory(owner.inventory);

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.putProcessingViews(
                        owner.inventory.inventoryIdentity(),
                        Collections.singletonMap(
                                other.inventory.rootBlueId(),
                                processingView(
                                        other,
                                        other.inventory.rootBlueId()))));

        // then
        assertTrue(failure.getMessage().contains("outside inventory"));
    }

    @Test
    void shouldKeepSharedBlueIdProcessViewsScopedToEachInventory() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph first =
                CoordinationEngineStorageTestFixtures.graph("shared-first");
        CoordinationEngineStorageTestFixtures.FragmentGraph second =
                CoordinationEngineStorageTestFixtures.graph("shared-second");
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                first.split.fragments());
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                second.split.fragments());
        store.putInventory(first.inventory);
        store.putInventory(second.inventory);
        Set<String> shared = new LinkedHashSet<String>(
                first.inventory.fragmentBlueIds());
        shared.retainAll(second.inventory.fragmentBlueIds());
        assertFalse(shared.isEmpty(),
                "The fixture must contain its shared kind fragment");
        String sharedBlueId = null;
        Node canonical = null;
        for (String candidate : shared) {
            Node physical = store.read(
                    CoordinationEngineStorageTestFixtures.PROFILE,
                    candidate);
            if (physical != null && !physical.isReferenceOnly()) {
                sharedBlueId = candidate;
                canonical = physical;
                break;
            }
        }
        if (sharedBlueId == null) {
            throw new AssertionError(
                    "The fixture must contain a shared physical fragment");
        }
        Node referenceView = new Node().blueId(sharedBlueId);
        store.putProcessingViews(
                first.inventory.inventoryIdentity(),
                Collections.singletonMap(sharedBlueId, canonical));
        store.putProcessingViews(
                second.inventory.inventoryIdentity(),
                Collections.singletonMap(sharedBlueId, referenceView));

        // when
        Map<String, Collection<String>> request =
                new LinkedHashMap<String, Collection<String>>();
        request.put(
                first.inventory.inventoryIdentity(),
                Collections.singleton(sharedBlueId));
        request.put(
                second.inventory.inventoryIdentity(),
                Collections.singleton(sharedBlueId));
        CoordinationFragmentStore.InventoryFragmentRepresentations read =
                store.readRepresentationsByInventory(request);
        Node firstView = read.byInventory()
                .get(first.inventory.inventoryIdentity())
                .processing().get(sharedBlueId).nodes().get(0);
        Node secondView = read.byInventory()
                .get(second.inventory.inventoryIdentity())
                .processing().get(sharedBlueId).nodes().get(0);

        // then
        assertFalse(firstView.isReferenceOnly());
        assertTrue(secondView.isReferenceOnly());
        assertEquals(sharedBlueId,
                DirectBlueIdCalculator.calculateBlueId(firstView));
        assertEquals(sharedBlueId,
                DirectBlueIdCalculator.calculateBlueId(secondView));
    }

    @Test
    void shouldNotLeakCanonicalBodiesAcrossInventoryScopedReads() {
        // given
        CoordinationFragmentStore store = createStore();
        CoordinationEngineStorageTestFixtures.FragmentGraph owner =
                CoordinationEngineStorageTestFixtures.graph("read-owner");
        CoordinationEngineStorageTestFixtures.FragmentGraph other =
                CoordinationEngineStorageTestFixtures.graph("read-outsider");
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                owner.split.fragments());
        store.putAllIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                other.split.fragments());
        store.putInventory(owner.inventory);
        store.putInventory(other.inventory);
        store.putProcessingViews(
                owner.inventory.inventoryIdentity(),
                Collections.<String, Node>emptyMap());
        String outsider = other.inventory.rootBlueId();

        // when
        NodeProviderResult canonical = store.readCanonical(outsider);
        NodeProviderResult single = store.readProcessing(
                owner.inventory.inventoryIdentity(), outsider);
        NodeProviderResult batch = store.readProcessingAll(
                owner.inventory.inventoryIdentity(),
                Collections.singleton(outsider)).get(outsider);
        CoordinationFragmentStore.FragmentRepresentations combined =
                store.readRepresentations(
                        owner.inventory.inventoryIdentity(),
                        Collections.singleton(outsider));
        Map<String, Collection<String>> request =
                new LinkedHashMap<String, Collection<String>>();
        request.put(
                owner.inventory.inventoryIdentity(),
                Collections.singleton(outsider));
        CoordinationFragmentStore.FragmentRepresentations partitioned =
                store.readRepresentationsByInventory(request).byInventory()
                        .get(owner.inventory.inventoryIdentity());

        // then
        assertEquals(NodeProviderOutcome.FOUND, canonical.outcome());
        assertEquals(NodeProviderOutcome.NOT_FOUND, single.outcome());
        assertEquals(NodeProviderOutcome.NOT_FOUND, batch.outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                combined.processing().get(outsider).outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                combined.physical().get(outsider).outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                partitioned.processing().get(outsider).outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                partitioned.physical().get(outsider).outcome());
    }

    @Test
    void shouldFailClosedForEveryAbsentInventoryScopedRead() {
        // given
        CoordinationFragmentStore store = createStore();
        Node exact = new Node().value("globally-present");
        String blueId = DirectBlueIdCalculator.calculateBlueId(exact);
        store.putIfAbsent(
                CoordinationEngineStorageTestFixtures.PROFILE,
                blueId,
                exact);
        String absentInventory = "sha256:absent-inventory";

        // when / then
        assertThrows(
                IllegalStateException.class,
                () -> store.readProcessing(absentInventory, blueId));
        assertThrows(
                IllegalStateException.class,
                () -> store.readProcessingAll(
                        absentInventory,
                        Collections.singleton(blueId)));
        assertThrows(
                IllegalStateException.class,
                () -> store.readRepresentations(
                        absentInventory,
                        Collections.singleton(blueId)));
        Map<String, Collection<String>> partitioned =
                new LinkedHashMap<String, Collection<String>>();
        partitioned.put(
                absentInventory,
                Collections.<String>emptyList());
        assertThrows(
                IllegalStateException.class,
                () -> store.readRepresentationsByInventory(partitioned));
    }

    private static Node processingView(
            CoordinationEngineStorageTestFixtures.FragmentGraph graph,
            String blueId) {
        NodeProviderResult result = graph.split.provider()
                .fetchResultByBlueId(blueId);
        if (result.outcome() != NodeProviderOutcome.FOUND
                || result.nodes().size() != 1) {
            throw new AssertionError(
                    "Fixture has no PROCESS view for " + blueId);
        }
        return result.nodes().get(0);
    }

    private static Map<String, Node> mapOf(
            String firstId,
            Node first,
            String secondId,
            Node second) {
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        result.put(firstId, first);
        result.put(secondId, second);
        return result;
    }
}
