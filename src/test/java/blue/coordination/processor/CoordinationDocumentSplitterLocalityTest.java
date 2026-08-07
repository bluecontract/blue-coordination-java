package blue.coordination.processor;

import blue.language.provider.NodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-time-based structural locality evidence for the public Coordination
 * splitter. The fixture has a branching factor of five along a seven-scope
 * selected spine, five 16 KiB operation bodies per active scope, and four
 * unrelated embedded siblings at each non-leaf scope.
 */
class CoordinationDocumentSplitterLocalityTest {

    private static final int DEPTH = 6;
    private static final int BRANCHING_FACTOR = 5;
    private static final int OPERATIONS_PER_SCOPE = 5;
    private static final int BODY_BYTES = 16 * 1024;

    @Test
    void shouldDemandOnlySelectedSpineAndBodiesFromProvider() {
        // given
        Node root = selectedSpine(0);

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        RecordingProvider provider =
                new RecordingProvider(
                        canonicalProvider(
                                split));
        Set<String> expectedDemands =
                new LinkedHashSet<String>();

        String scopeBlueId = split.rootBlueId();
        for (int depth = 0; depth <= DEPTH; depth++) {
            Node scope = fetch(provider, scopeBlueId);
            expectedDemands.add(scopeBlueId);

            Node contracts = fetch(
                    provider,
                    scope.getContracts().getBlueId());
            expectedDemands.add(
                    scope.getContracts().getBlueId());
            Node selectedContractReference =
                    contracts.getProperties().get(
                            "selected");
            Node selectedContract = fetch(
                    provider,
                    selectedContractReference
                            .getBlueId());
            expectedDemands.add(
                    selectedContractReference
                            .getBlueId());
            Node selectedBody =
                    selectedContract
                            .getProperties().get(
                                    "steps");
            assertNotNull(selectedBody);
            assertTrue(selectedBody.isReferenceOnly());
            fetch(provider, selectedBody.getBlueId());
            expectedDemands.add(selectedBody.getBlueId());

            if (depth < DEPTH) {
                Node selectedChild =
                        NodePathEditor.getOrNull(scope, "/selected");
                assertNotNull(selectedChild);
                assertTrue(selectedChild.isReferenceOnly());
                scopeBlueId = selectedChild.getBlueId();
            }
        }

        // then
        assertEquals(expectedDemands, provider.demandedBlueIds());
        assertEquals(
                (DEPTH + 1) * 4,
                provider.calls(),
                "one scope, contract container, selected header, and selected "
                        + "body are read per active scope");

        Set<String> forbidden =
                new LinkedHashSet<String>(split.fragments().keySet());
        forbidden.removeAll(expectedDemands);
        assertFalse(forbidden.isEmpty());
        assertTrue(
                Collections.disjoint(
                        forbidden, provider.demandedBlueIds()),
                "no embedded sibling or decoy body may be demanded");

        long totalGraphBytes = encodedBytes(split.fragments().values());
        long selectedFragmentBytes = provider.returnedBytes();
        assertTrue(
                selectedFragmentBytes * 3L < totalGraphBytes,
                "selected bytes must remain structurally below the transitive graph: "
                        + selectedFragmentBytes + " selected of "
                        + totalGraphBytes + " total");
        assertEquals(0, provider.forbiddenDemands(forbidden));

        System.out.println(
                "Coordination splitter scale locality: branchingFactor="
                        + BRANCHING_FACTOR
                        + ", depth=" + DEPTH
                        + ", operationsPerScope="
                        + OPERATIONS_PER_SCOPE
                        + ", bodyBytes=" + BODY_BYTES
                        + ", totalGraphBytes="
                        + totalGraphBytes
                        + ", selectedFragmentBytes="
                        + selectedFragmentBytes
                        + ", providerCalls="
                        + provider.calls()
                        + ", exactDemandedBlueIds="
                        + provider.demandedBlueIds());
    }

    @Test
    void shouldNotReadEmbeddedRootsForRootOnlyPreparation() {
        // given
        Node root = selectedSpine(0);

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        RecordingProvider provider =
                new RecordingProvider(
                        canonicalProvider(
                                split));

        Node rootFragment =
                fetch(provider, split.rootBlueId());
        Node contracts =
                fetch(
                        provider,
                        rootFragment.getContracts()
                                .getBlueId());
        Node selected =
                fetch(
                        provider,
                        contracts.getProperties()
                                .get("selected")
                                .getBlueId());
        Node rootBody =
                selected.getProperties()
                        .get("steps");
        fetch(provider, rootBody.getBlueId());

        // then
        Set<String> embeddedRootBlueIds =
                blueIdsOfKind(
                        split.metadata(),
                        CoordinationDocumentSplitter.FragmentKind.EMBEDDED_ROOT);
        assertTrue(
                Collections.disjoint(
                        embeddedRootBlueIds,
                        provider.demandedBlueIds()));
        assertEquals(
                Arrays.asList(
                        split.rootBlueId(),
                        rootFragment.getContracts()
                                .getBlueId(),
                        contracts.getProperties()
                                .get("selected")
                                .getBlueId(),
                        rootBody.getBlueId()),
                new ArrayList<String>(
                        provider.demandedBlueIds()));
    }

    @Test
    void shouldReconstructExactGraphAndDeduplicateSharedBodies() {
        // given
        Node sharedBody = body("shared", BODY_BYTES);
        Node root = new Node()
                .properties("state", scalar("root"))
                .contracts(new Node().properties(
                        "first",
                        workflow(
                                SequentialWorkflowOperation.blueId(),
                                sharedBody),
                        "second",
                        workflow(
                                ChatWorkflowOperation.blueId(),
                                sharedBody.clone()),
                        "reactive",
                        workflow(
                                SequentialWorkflow.blueId(),
                                body("reactive", 128))));

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        Node reconstructed = expandKnownFragments(
                split.pureReference(),
                split.fragments(),
                new LinkedHashSet<String>());

        // then
        assertEquals(
                NodeWireForm.get(root),
                NodeWireForm.get(reconstructed));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                DirectBlueIdCalculator.calculateBlueId(reconstructed));

        String sharedBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(sharedBody);
        assertTrue(split.fragments().containsKey(sharedBodyBlueId));
        int sharedOccurrences = 0;
        for (CoordinationDocumentSplitter.FragmentMetadata metadata
                : split.metadata()) {
            if (metadata.kind()
                    == CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY
                    && sharedBodyBlueId.equals(metadata.blueId())) {
                sharedOccurrences++;
            }
        }
        assertEquals(
                2,
                sharedOccurrences,
                "two handler headers retain distinct diagnostic occurrences");
        assertEquals(
                1,
                countKey(split.fragments(), sharedBodyBlueId),
                "content-addressed storage retains the shared body once");
    }

    private static Node selectedSpine(int depth) {
        Map<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put("depth", scalar(depth));
        Map<String, Node> contracts =
                operationContracts(depth);
        if (depth < DEPTH) {
            List<String> embeddedPaths =
                    new ArrayList<String>();
            properties.put("selected", selectedSpine(depth + 1));
            embeddedPaths.add("/selected");
            for (int sibling = 1;
                 sibling < BRANCHING_FACTOR;
                 sibling++) {
                String key = "other" + sibling;
                properties.put(
                        key,
                        new Node()
                                .properties(
                                        "depth", scalar(depth + 1),
                                        "branch", scalar(key),
                                        "largeUnrelatedData",
                                        scalar(repeat(
                                                (char) ('a' + sibling),
                                                BODY_BYTES))));
                embeddedPaths.add("/" + key);
            }
            contracts.put(
                    "embedded",
                    processEmbedded(embeddedPaths));
        }
        return new Node()
                .properties(properties)
                .contracts(new Node().properties(contracts));
    }

    private static Map<String, Node> operationContracts(
            int depth) {
        Map<String, Node> contracts =
                new LinkedHashMap<String, Node>();
        contracts.put(
                "selected",
                workflow(
                        SequentialWorkflowOperation.blueId(),
                        body("selected-" + depth, BODY_BYTES)));
        for (int operation = 1;
             operation < OPERATIONS_PER_SCOPE;
             operation++) {
            String typeBlueId = operation % 2 == 0
                    ? ChatWorkflowOperation.blueId()
                    : SequentialWorkflow.blueId();
            contracts.put(
                    "decoy" + operation,
                    workflow(
                            typeBlueId,
                            body(
                                    "decoy-" + depth + "-"
                                            + operation,
                                    BODY_BYTES)));
        }
        return contracts;
    }

    private static Node workflow(
            String typeBlueId,
            Node steps) {
        return new Node()
                .type(reference(typeBlueId))
                .properties(
                        "channel", scalar("timeline"),
                        "order", scalar(0),
                        "steps", steps);
    }

    private static Node processEmbedded(
            List<String> paths) {
        List<Node> values =
                new ArrayList<Node>();
        for (String path : paths) {
            values.add(scalar(path));
        }
        return new Node()
                .type(reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(values));
    }

    private static Node body(
            String label,
            int bytes) {
        return new Node().items(
                new Node().properties(
                        "label", scalar(label),
                        "payload", scalar(
                                repeat(
                                        (char) ('A'
                                                + Math.abs(
                                                label.hashCode())
                                                % 20),
                                        bytes))));
    }

    private static String repeat(
            char value,
            int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static Node fetch(
            NodeProvider provider,
            String blueId) {
        List<Node> nodes =
                provider.fetchByBlueId(blueId);
        assertNotNull(nodes);
        assertEquals(1, nodes.size());
        return nodes.get(0);
    }

    private static NodeProvider canonicalProvider(
            CoordinationDocumentSplitter.SplitGraph split) {
        Map<String, Node> fragments =
                split.fragments();
        return blueId -> {
            Node exact = fragments.get(
                    blueId);
            return exact != null
                    ? Collections.singletonList(
                            exact.clone())
                    : null;
        };
    }

    private static Set<String> blueIdsOfKind(
            List<CoordinationDocumentSplitter.FragmentMetadata> metadata,
            CoordinationDocumentSplitter.FragmentKind kind) {
        Set<String> result =
                new LinkedHashSet<String>();
        for (CoordinationDocumentSplitter.FragmentMetadata entry
                : metadata) {
            if (entry.kind() == kind) {
                result.add(entry.blueId());
            }
        }
        return result;
    }

    private static long encodedBytes(
            Iterable<Node> nodes) {
        long total = 0L;
        for (Node node : nodes) {
            total += encodedBytes(node);
        }
        return total;
    }

    private static long encodedBytes(Node node) {
        try {
            return UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(node)
                    .getBytes(StandardCharsets.UTF_8)
                    .length;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not encode exact fragment",
                    exception);
        }
    }

    private static int countKey(
            Map<String, Node> fragments,
            String blueId) {
        int count = 0;
        for (String key : fragments.keySet()) {
            if (blueId.equals(key)) {
                count++;
            }
        }
        return count;
    }

    private static Node expandKnownFragments(
            Node node,
            Map<String, Node> fragments,
            Set<String> active) {
        if (node == null) {
            return null;
        }
        if (node.isReferenceOnly()) {
            Node fragment = fragments.get(node.getBlueId());
            if (fragment == null) {
                return node.clone();
            }
            assertTrue(
                    active.add(node.getBlueId()),
                    "fragment cycle at " + node.getBlueId());
            try {
                return expandKnownFragments(
                        fragment, fragments, active);
            } finally {
                active.remove(node.getBlueId());
            }
        }

        Node expanded = node.clone();
        expanded.type(expandKnownFragments(
                node.getType(), fragments, active));
        expanded.itemType(expandKnownFragments(
                node.getItemType(), fragments, active));
        expanded.keyType(expandKnownFragments(
                node.getKeyType(), fragments, active));
        expanded.valueType(expandKnownFragments(
                node.getValueType(), fragments, active));
        expanded.contracts(expandKnownFragments(
                node.getContracts(), fragments, active));
        expanded.blue(expandKnownFragments(
                node.getBlue(), fragments, active));
        if (node.getItems() != null) {
            List<Node> items =
                    new ArrayList<Node>();
            for (Node item : node.getItems()) {
                items.add(expandKnownFragments(
                        item, fragments, active));
            }
            expanded.items(items);
        }
        if (node.getProperties() != null) {
            Map<String, Node> properties =
                    new LinkedHashMap<String, Node>();
            for (Map.Entry<String, Node> property
                    : node.getProperties().entrySet()) {
                properties.put(
                        property.getKey(),
                        expandKnownFragments(
                                property.getValue(),
                                fragments,
                                active));
            }
            expanded.properties(properties);
        }
        return expanded;
    }

    private static final class RecordingProvider
            implements NodeProvider {

        private final NodeProvider delegate;
        private final Set<String> demandedBlueIds =
                new LinkedHashSet<String>();
        private int calls;
        private long returnedBytes;

        private RecordingProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            calls++;
            demandedBlueIds.add(blueId);
            List<Node> nodes =
                    delegate.fetchByBlueId(blueId);
            if (nodes != null) {
                returnedBytes += encodedBytes(nodes);
            }
            return nodes;
        }

        private int calls() {
            return calls;
        }

        private long returnedBytes() {
            return returnedBytes;
        }

        private Set<String> demandedBlueIds() {
            return Collections.unmodifiableSet(
                    demandedBlueIds);
        }

        private int forbiddenDemands(
                Set<String> forbidden) {
            int result = 0;
            for (String demanded : demandedBlueIds) {
                if (forbidden.contains(demanded)) {
                    result++;
                }
            }
            return result;
        }
    }
}
