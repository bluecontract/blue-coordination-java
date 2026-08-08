package blue.coordination.engine.fastpath;

import blue.coordination.engine.CoordinationProcessingEngine
        .VerifiedNodeAccessAuthority;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Differential acceptance proof for retained-reference structural sharing. */
final class PersistentRetainedReferenceExpansionTest {

    @Test
    void shouldMatchFullExpansionWhileSharingRetainedAndUnchangedSubtrees() {
        // given
        Object owner = new Object();
        Node retained = new Node().properties(
                "payload", new Node().value("retained"),
                "nested", new Node().properties(
                        "answer", new Node().value(42)));
        String retainedBlueId = blueId(retained);
        RetainedReferenceIndex index = RetainedReferenceIndex.builder(owner)
                .add(retainedBlueId, retained)
                .build();
        Node stableInline = new Node().properties(
                "stable", new Node().value(true));
        Map<String, Node> stableProperties = stableInline.getProperties();
        Node referenceBranch = new Node().properties(
                "target", reference(retainedBlueId));
        Map<String, Node> referenceProperties = referenceBranch.getProperties();
        String missingBlueId = blueId(new Node().value("external-missing"));
        Node missing = reference(missingBlueId);
        Node list = new Node().items(
                reference(retainedBlueId),
                missing,
                reference(retainedBlueId));
        List<Node> originalItems = list.getItems();
        Node hybrid = new Node()
                .type(reference(retainedBlueId))
                .contracts(reference(retainedBlueId))
                .properties(
                        "stable", stableInline,
                        "branch", referenceBranch,
                        "list", list);
        Map<String, Node> rootProperties = hybrid.getProperties();
        Node fullOracle = fullyExpand(
                hybrid.clone(),
                Collections.singletonMap(retainedBlueId, retained));

        // when
        Node persistent = new IndexedRetainedReferenceResolver(index, owner)
                .resolveRequestOwned(hybrid);

        // then
        assertSame(hybrid, persistent,
                "the request-owned result remains the mutable ancestor spine");
        assertEquals(blueId(fullOracle), blueId(persistent));
        assertSame(rootProperties, persistent.getProperties(),
                "a parent map is retained when its direct child identities stay put");
        assertSame(stableInline, persistent.getProperties().get("stable"));
        assertSame(stableProperties, stableInline.getProperties(),
                "a reference-free subtree is not rebuilt");
        assertNotSame(referenceProperties, referenceBranch.getProperties(),
                "the direct reference-bearing property map is rebuilt once");
        assertNotSame(originalItems, list.getItems(),
                "the direct reference-bearing list is rebuilt once");
        assertSame(retained, persistent.getType());
        assertSame(retained, persistent.getContracts());
        assertSame(retained, referenceBranch.getProperties().get("target"));
        assertSame(retained, list.getItems().get(0));
        assertSame(retained, list.getItems().get(2),
                "duplicate identities share one admitted retained object");
        assertSame(missing, list.getItems().get(1));
        assertTrue(list.getItems().get(1).isReferenceOnly(),
                "an external reference outside the retained index stays unresolved");
    }

    @Test
    void shouldTerminateOnObjectCyclesAndRejectAnotherEpochOwner() {
        // given
        Object owner = new Object();
        RetainedReferenceIndex empty = RetainedReferenceIndex.builder(owner)
                .build();
        Node cyclic = new Node();
        cyclic.properties("self", cyclic);
        Map<String, Node> originalProperties = cyclic.getProperties();

        // when
        Node resolved = new IndexedRetainedReferenceResolver(empty, owner)
                .resolveRequestOwned(cyclic);
        IllegalArgumentException wrongOwner = assertThrows(
                IllegalArgumentException.class,
                () -> new IndexedRetainedReferenceResolver(
                        empty, new Object()).resolveRequestOwned(
                        reference("unknown")));

        // then
        assertSame(cyclic, resolved);
        assertSame(cyclic, resolved.getProperties().get("self"));
        assertSame(originalProperties, resolved.getProperties(),
                "cycle protection must not rebuild an unchanged container");
        assertTrue(wrongOwner.getMessage().contains("another epoch"));
    }

    @Test
    void shouldShareVerifiedHandlesWhenGraftingWithinTheEngineOwner()
            throws Exception {
        Object owner = new Object();
        Node retained = new Node().value("retained");
        String retainedBlueId = blueId(retained);
        ExactNodeHandle retainedHandle = ExactNodeHandle.adoptAndVerify(
                retainedBlueId, retained, owner);
        RetainedReferenceIndex prior = RetainedReferenceIndex.builder(owner)
                .add(retainedHandle)
                .build();
        assertSame(prior,
                prior.withVerifiedHandles(
                        Collections.<ExactNodeHandle>emptyList(), owner),
                "an empty projection extension is allocation-free");
        Node expanded = new Node().value("expanded");
        String expandedBlueId = blueId(expanded);
        Node resultingRoot = new Node().properties("expanded", expanded);

        RetainedReferenceIndex resulting = prior.graftVerifiedExpanded(
                resultingRoot,
                Collections.singletonMap("/expanded", expandedBlueId),
                owner,
                owner,
                new RequestDigestMemo(),
                authority());

        assertSame(retainedHandle, resulting.find(retainedBlueId),
                "same-domain immutable handles need no successor wrapper");
        assertEquals(expandedBlueId,
                resulting.find(expandedBlueId).blueId());
    }

    private static Node fullyExpand(
            Node root, Map<String, Node> retained) {
        return fullyExpand(
                root,
                retained,
                Collections.newSetFromMap(
                        new IdentityHashMap<Node, Boolean>()),
                new LinkedHashSet<String>());
    }

    private static VerifiedNodeAccessAuthority authority() throws Exception {
        Constructor<VerifiedNodeAccessAuthority> constructor =
                VerifiedNodeAccessAuthority.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Node fullyExpand(
            Node node,
            Map<String, Node> retained,
            Set<Node> visited,
            Set<String> activeBlueIds) {
        if (node.isReferenceOnly()) {
            String blueId = node.getBlueId();
            Node exact = retained.get(blueId);
            if (exact == null || !activeBlueIds.add(blueId)) return node;
            Node expanded = fullyExpand(
                    exact.clone(), retained, visited, activeBlueIds);
            activeBlueIds.remove(blueId);
            return expanded;
        }
        if (!visited.add(node)) return node;
        if (node.getType() != null) {
            node.type(fullyExpand(
                    node.getType(), retained, visited, activeBlueIds));
        }
        if (node.getItemType() != null) {
            node.itemType(fullyExpand(
                    node.getItemType(), retained, visited, activeBlueIds));
        }
        if (node.getKeyType() != null) {
            node.keyType(fullyExpand(
                    node.getKeyType(), retained, visited, activeBlueIds));
        }
        if (node.getValueType() != null) {
            node.valueType(fullyExpand(
                    node.getValueType(), retained, visited, activeBlueIds));
        }
        if (node.getContracts() != null) {
            node.contracts(fullyExpand(
                    node.getContracts(), retained, visited, activeBlueIds));
        }
        if (node.getBlue() != null) {
            node.blue(fullyExpand(
                    node.getBlue(), retained, visited, activeBlueIds));
        }
        if (node.getItems() != null) {
            List<Node> expanded = new ArrayList<>();
            for (Node item : node.getItems()) {
                expanded.add(fullyExpand(
                        item, retained, visited, activeBlueIds));
            }
            node.items(expanded);
        }
        if (node.getProperties() != null) {
            Map<String, Node> expanded = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                expanded.put(
                        entry.getKey(),
                        fullyExpand(
                                entry.getValue(),
                                retained,
                                visited,
                                activeBlueIds));
            }
            node.properties(expanded);
        }
        return node;
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }
}
