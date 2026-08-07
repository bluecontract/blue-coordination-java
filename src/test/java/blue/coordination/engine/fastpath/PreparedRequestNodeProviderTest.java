package blue.coordination.engine.fastpath;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProviderResult;
import blue.coordination.engine.api.LocalityDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreparedRequestNodeProviderTest {
    @Test
    void copiesOnceThenMemoizesEveryProviderLookup() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(16);
        Node body = new Node().properties(
                "payload", new Node().value("pay-note"));
        String blueId = DirectBlueIdCalculator.calculateBlueId(body);
        ExactNodeHandle handle = interner.internCopy(blueId, body);
        Map<String, ExactNodeHandle> handles = new LinkedHashMap<>();
        handles.put(blueId, handle);
        PreparedRequestNodeProvider provider =
                new PreparedRequestNodeProvider(handles);

        List<Node> first = provider.fetchByBlueId(blueId);
        List<Node> second = provider.fetchByBlueId(blueId);

        assertSame(first.get(0), second.get(0),
                "one request must reuse its defensive snapshot");
        NodeProviderResult firstPortable =
                provider.fetchResultByBlueId(blueId);
        NodeProviderResult secondPortable =
                provider.fetchResultByBlueId(blueId);
        assertEquals(NodeProviderOutcome.FOUND, firstPortable.outcome());
        assertNotSame(
                firstPortable.nodes().get(0),
                secondPortable.nodes().get(0),
                "portable outcome access remains defensive");
        assertEquals(1, provider.loadedIdentityCount());
        assertTrue(provider.missedBlueIds().isEmpty());
    }

    @Test
    void neverFallsBackOutsideTheBoundBundle() {
        PreparedRequestNodeProvider provider =
                new PreparedRequestNodeProvider(
                        Collections.<String, ExactNodeHandle>emptyMap());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                provider.fetchResultByBlueId("missing").outcome());
        assertEquals(1, provider.missedBlueIds().size());
    }

    @Test
    void failsClosedWithoutAStoreFallbackForUnpreparedInventoryDemand() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(16);
        Node body = new Node().value("selected");
        String selected = DirectBlueIdCalculator.calculateBlueId(body);
        Map<String, ExactNodeHandle> handles = new LinkedHashMap<>();
        handles.put(selected, interner.internCopy(selected, body));
        String unprepared = "known-but-unprepared";
        PreparedRequestNodeProvider provider =
                new PreparedRequestNodeProvider(
                        handles,
                        Collections.singleton(selected),
                        blueId -> Collections.<Node>emptyList(),
                        Arrays.asList(selected, unprepared),
                        Arrays.asList(selected, unprepared),
                        Collections.<String>emptySet(),
                        1,
                        17L);

        NodeProviderResult result = provider.fetchResultByBlueId(unprepared);
        LocalityDiagnostics diagnostics = provider.diagnostics();

        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, result.outcome());
        assertEquals(0, diagnostics.fallbackReadCount());
        assertEquals(1, diagnostics.forbiddenReadCount());
        assertEquals(17L, diagnostics.loadedBytes());
    }

    @Test
    void lazilyMaterializesAnAllowedPreparedHandleWithoutAFallback() {
        ContentAddressedNodeInterner interner =
                new ContentAddressedNodeInterner(16);
        Node selectedBody = new Node().value("selected");
        String selected = DirectBlueIdCalculator.calculateBlueId(
                selectedBody);
        Node demandedBody = new Node().value("demanded");
        String demanded = DirectBlueIdCalculator.calculateBlueId(
                demandedBody);
        Map<String, ExactNodeHandle> selectedHandles = new LinkedHashMap<>();
        selectedHandles.put(
                selected,
                interner.internCopy(selected, selectedBody));
        Map<String, ExactNodeHandle> available = new LinkedHashMap<>(
                selectedHandles);
        available.put(
                demanded,
                interner.internCopy(demanded, demandedBody));
        PreparedRequestNodeProvider provider =
                new PreparedRequestNodeProvider(
                        selectedHandles,
                        Collections.singleton(selected),
                        available,
                        blueId -> Collections.<Node>emptyList(),
                        Arrays.asList(selected, demanded),
                        Arrays.asList(selected, demanded),
                        Collections.<String>emptySet(),
                        1,
                        17L);

        List<Node> first = provider.fetchByBlueId(demanded);
        List<Node> second = provider.fetchByBlueId(demanded);

        assertSame(first.get(0), second.get(0));
        assertTrue(provider.missedBlueIds().isEmpty());
        assertEquals(0, provider.diagnostics().fallbackReadCount());
        assertEquals(0, provider.diagnostics().forbiddenReadCount());
        assertEquals(Collections.singletonList(selected),
                provider.loadedBlueIds());
    }
}
