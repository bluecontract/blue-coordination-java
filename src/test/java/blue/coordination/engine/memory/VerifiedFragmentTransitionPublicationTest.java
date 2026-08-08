package blue.coordination.engine.memory;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.coordination.engine.api.CoordinationFragmentInventory;
import blue.coordination.engine.api.CoordinationFragmentTransition;
import blue.coordination.engine.api.CoordinationScopeTransition;
import blue.coordination.engine.fastpath.AssembledInventoryDelta;
import blue.coordination.engine.fastpath.ContentAddressedNodeInterner;
import blue.coordination.engine.fastpath.FastFragmentDelta;
import blue.coordination.engine.fastpath.RequestDigestMemo;
import blue.coordination.engine.fastpath.ResultDeltaTransitionAssembler;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Atomicity and ownership proofs for verified fragment publication. */
final class VerifiedFragmentTransitionPublicationTest {

    @Test
    void shouldPublishVerifiedHandlesWithoutDtoCopiesOrBlueIdRehashes()
            throws Exception {
        // given
        VerifiedNodeAccessAuthority authority = authority();
        InMemoryCoordinationFragmentStore store = store();
        CoordinationDocumentSplitter splitter =
                CoordinationDocumentSplitter.forEventSplitting();
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                splitter.splitEvent(event("before", "stable"));
        CoordinationFragmentInventory prior = admit(store, priorGraph);
        CoordinationDocumentSplitter.SplitGraph resultGraph =
                splitter.splitEvent(event("after", "stable"));
        FastFragmentDelta delta = delta(
                authority,
                prior,
                CoordinationFragmentInventory.from(resultGraph),
                newBodies(prior, resultGraph));
        long beforeCount = store.physicalFragmentCount();

        // when
        store.putVerifiedTransition(authority, delta, true);

        // then
        assertTrue(store.physicalFragmentCount() > beforeCount);
        assertEquals(1L, store.verifiedTransitionPublicationCount());
        assertTrue(store.verifiedTransitionBorrowedNodeCount() > 0L);
        assertEquals(0L, delta.requestIdentityCalculations(authority));
        assertEquals(0L, delta.defensiveNodeCopies(authority));

        CoordinationFragmentTransition publicTransition =
                CoordinationFragmentTransition.fromVerifiedDelta(
                        authority, delta);
        Map<String, Node> escaped = publicTransition.newFragments();
        String first = escaped.keySet().iterator().next();
        escaped.get(first).name("caller mutation");
        Node stored = store.readCanonical(first).nodes().get(0);
        assertEquals(first, DirectBlueIdCalculator.calculateBlueId(stored));
        assertEquals(
                delta.newFragments(authority).size(),
                delta.defensiveNodeCopies(authority));
    }

    @Test
    void shouldPublishNothingWhenAnyImmutableWinnerConflicts()
            throws Exception {
        // given
        VerifiedNodeAccessAuthority authority = authority();
        InMemoryCoordinationFragmentStore store = store();
        CoordinationDocumentSplitter splitter =
                CoordinationDocumentSplitter.forEventSplitting();
        CoordinationDocumentSplitter.SplitGraph priorGraph =
                splitter.splitEvent(event("before", "prior"));
        CoordinationFragmentInventory prior = admit(store, priorGraph);

        // Schema enum order is intentionally excluded from semantic identity
        // but remains part of the exact physical wire representation.
        Node firstPhysicalForm = new Node()
                .schema(new Schema().enumValues(Arrays.asList(
                        new Node().value("A"),
                        new Node().value("B"))))
                .value("A");
        Node conflictingPhysicalForm = new Node()
                .schema(new Schema().enumValues(Arrays.asList(
                        new Node().value("B"),
                        new Node().value("A"))))
                .value("A");
        String collisionBlueId = DirectBlueIdCalculator.calculateBlueId(
                firstPhysicalForm);
        assertEquals(
                collisionBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        conflictingPhysicalForm));
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                Collections.singletonMap(
                        collisionBlueId, firstPhysicalForm));

        CoordinationDocumentSplitter.SplitGraph resultGraph =
                splitter.splitEvent(new Node().properties(
                        "revision", new Node().value("after"),
                        "collision", conflictingPhysicalForm,
                        "newBody", new Node().properties(
                                "payload", new Node().value("never publish"))));
        CoordinationFragmentInventory resulting =
                CoordinationFragmentInventory.from(resultGraph);
        Map<String, Node> newBodies = newBodies(prior, resultGraph);
        assertTrue(newBodies.containsKey(collisionBlueId));
        FastFragmentDelta delta = delta(
                authority, prior, resulting, newBodies);
        int beforeCount = store.physicalFragmentCount();

        // when
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> store.putVerifiedTransition(authority, delta, true));

        // then
        assertTrue(failure.getMessage().contains(
                "Conflicting immutable fragment content"));
        assertEquals(beforeCount, store.physicalFragmentCount());
        assertEquals(0L, store.verifiedTransitionPublicationCount());
        assertThrows(
                IllegalStateException.class,
                () -> store.requireInventory(
                        resulting.inventoryIdentity()));
    }

    private static FastFragmentDelta delta(
            VerifiedNodeAccessAuthority authority,
            CoordinationFragmentInventory prior,
            CoordinationFragmentInventory resulting,
            Map<String, Node> newBodies) {
        RequestDigestMemo digests = new RequestDigestMemo();
        for (Map.Entry<String, Node> entry : newBodies.entrySet()) {
            digests.bindVerified(entry.getValue(), entry.getKey());
        }
        AssembledInventoryDelta assembled = new AssembledInventoryDelta(
                authority,
                resulting,
                newBodies,
                Collections.<String, Node>emptyMap(),
                Collections.<CoordinationScopeTransition>emptyList());
        return new ResultDeltaTransitionAssembler(
                new ContentAddressedNodeInterner(0)).assemble(
                        authority, prior, assembled, digests);
    }

    private static Map<String, Node> newBodies(
            CoordinationFragmentInventory prior,
            CoordinationDocumentSplitter.SplitGraph resultGraph) {
        Set<String> priorBlueIds = new LinkedHashSet<String>(
                prior.fragmentBlueIds());
        Map<String, Node> result = new LinkedHashMap<String, Node>();
        for (Map.Entry<String, Node> entry
                : resultGraph.fragments().entrySet()) {
            if (!priorBlueIds.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static CoordinationFragmentInventory admit(
            InMemoryCoordinationFragmentStore store,
            CoordinationDocumentSplitter.SplitGraph graph) {
        store.putAllIfAbsent(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID,
                graph.fragments());
        CoordinationFragmentInventory inventory =
                CoordinationFragmentInventory.from(graph);
        store.putInventory(inventory);
        return inventory;
    }

    private static InMemoryCoordinationFragmentStore store() {
        return new InMemoryCoordinationFragmentStore(
                CoordinationDocumentSplitter.FRAGMENTATION_PROFILE_ID);
    }

    private static Node event(String revision, String payload) {
        return new Node().properties(
                "revision", new Node().value(revision),
                "payload", new Node().value(payload));
    }

    private static VerifiedNodeAccessAuthority authority() throws Exception {
        Constructor<VerifiedNodeAccessAuthority> constructor =
                VerifiedNodeAccessAuthority.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}
