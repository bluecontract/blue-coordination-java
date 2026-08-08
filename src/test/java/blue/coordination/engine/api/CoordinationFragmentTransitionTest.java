package blue.coordination.engine.api;

import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Closed-value tests for immutable fragment transition accounting. */
final class CoordinationFragmentTransitionTest {

    @Test
    void shouldIsolateConstructorAndAccessorNodesWithoutRehashingOnRead() {
        // given
        CoordinationDocumentSplitter.SplitGraph graph =
                CoordinationDocumentSplitter.forEventSplitting().splitEvent(
                        new Node().properties(
                                "payload", new Node().value("immutable")));
        CoordinationFragmentInventory resulting =
                CoordinationFragmentInventory.from(graph);
        Map<String, Node> supplied = new LinkedHashMap<String, Node>(
                graph.fragments());
        String rootBlueId = resulting.rootBlueId();
        Object expectedWire = NodeWireForm.get(supplied.get(rootBlueId));
        CoordinationFragmentTransition transition =
                new CoordinationFragmentTransition(
                        resulting,
                        supplied,
                        Collections.<String>emptySet(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<CoordinationScopeTransition>emptyList());

        // when
        supplied.get(rootBlueId).name("mutated-source");
        Map<String, Node> firstRead = transition.newFragments();
        firstRead.get(rootBlueId).name("mutated-result");
        Map<String, Node> secondRead = transition.newFragments();

        // then
        assertEquals(expectedWire, NodeWireForm.get(secondRead.get(
                rootBlueId)));
        assertEquals(
                rootBlueId,
                DirectBlueIdCalculator.calculateBlueId(
                        secondRead.get(rootBlueId)));
        assertNotEquals(
                NodeWireForm.get(firstRead.get(rootBlueId)),
                NodeWireForm.get(secondRead.get(rootBlueId)));
        assertThrows(
                UnsupportedOperationException.class,
                () -> secondRead.put("other", new Node().value("other")));
    }

    @Test
    void shouldExposeRetiredFragmentsWithoutDeletingImmutableContent() {
        // given
        CoordinationDocumentSplitter splitter =
                CoordinationDocumentSplitter.forEventSplitting();
        CoordinationDocumentSplitter.SplitGraph before = splitter.splitEvent(
                new Node().properties(
                        "beforeOnly", new Node().value("before")));
        CoordinationDocumentSplitter.SplitGraph after = splitter.splitEvent(
                new Node().properties(
                        "afterOnly", new Node().value("after")));
        CoordinationFragmentInventory resulting =
                CoordinationFragmentInventory.from(after);
        Set<String> retired = new LinkedHashSet<String>(
                before.fragments().keySet());
        retired.removeAll(after.fragments().keySet());

        // when
        CoordinationFragmentTransition transition =
                new CoordinationFragmentTransition(
                        resulting,
                        after.fragments(),
                        Collections.<String, Node>emptyMap(),
                        Collections.<String>emptySet(),
                        retired,
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<CoordinationScopeTransition>emptyList());

        // then
        assertEquals(retired, transition.retiredFragmentBlueIds());
        assertTrue(Collections.disjoint(
                transition.retiredFragmentBlueIds(),
                transition.resultingInventory().fragmentBlueIds()));
    }

    @Test
    void shouldRejectRetirementOfAFragmentStillInTheResultingInventory() {
        // given
        CoordinationDocumentSplitter.SplitGraph graph =
                CoordinationDocumentSplitter.forEventSplitting().splitEvent(
                        new Node().properties(
                                "retainedField",
                                new Node().value("retained")));
        CoordinationFragmentInventory resulting =
                CoordinationFragmentInventory.from(graph);
        String retainedBlueId = resulting.fragmentBlueIds().get(0);

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationFragmentTransition(
                        resulting,
                        graph.fragments(),
                        Collections.<String, Node>emptyMap(),
                        Collections.<String>emptySet(),
                        Collections.singleton(retainedBlueId),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<FragmentEdgeRecord>emptyList(),
                        Collections.<CoordinationScopeTransition>emptyList()));

        // then
        assertTrue(failure.getMessage().contains(
                "remain in the resulting inventory"));
    }
}
