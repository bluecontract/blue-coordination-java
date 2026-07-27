package blue.coordination.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodePathEditor;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.NodeTransformer;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural evidence for deep Coordination selection surfaces.
 *
 * <p>This test models physical provider demand only. A selected target admits
 * the Root-to-target scope chain, the selected operation body at the target,
 * and the explicitly allow-listed causal body at every scope on that chain.
 * It does not execute Contracts and therefore makes no processing-parity
 * claim.</p>
 */
class CoordinationDocumentSplitterDeepLocalityTest {

    private static final String ROOT = "/";
    private static final String EMB1 = "/emb1";
    private static final String EMB2 = "/emb1/emb2";
    private static final String EMB3 =
            "/emb1/emb2/emb3";
    private static final List<String> ACTIVE_SCOPE_PATHS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            ROOT,
                            EMB1,
                            EMB2,
                            EMB3));
    private static final int SIBLINGS_PER_SCOPE = 2;
    private static final int BODY_BYTES = 4096;

    @Test
    void completeFragmentInventoryReconstructsExactDeepRoot() {
        Fixture fixture = Fixture.create();
        CoordinationDocumentSplitter.SplitGraph split =
                new CoordinationDocumentSplitter()
                        .splitDocument(fixture.root);

        Node reconstructed =
                NodeTransformer.transform(
                        split.pureReference(),
                        node -> {
                            if (!node.isReferenceOnly()) {
                                return node;
                            }
                            Node fragment =
                                    split.fragments().get(
                                            node.getBlueId());
                            return fragment != null
                                    ? fragment
                                    : node;
                        });

        assertEquals(
                NodeToMapListOrValue.get(
                        fixture.root),
                NodeToMapListOrValue.get(
                        reconstructed));
        assertEquals(
                BlueIdCalculator.calculateBlueId(
                        fixture.root),
                split.rootBlueId());
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(
                        reconstructed));

        for (Map.Entry<String, Node> fragment
                : split.fragments().entrySet()) {
            assertEquals(
                    fragment.getKey(),
                    BlueIdCalculator.calculateBlueId(
                            fragment.getValue()));
            NodeProviderResult result =
                    split.provider()
                            .fetchResultByBlueId(
                                    fragment.getKey());
            assertEquals(
                    NodeProviderOutcome.FOUND,
                    result.outcome());
            assertEquals(1, result.nodes().size());
        }

        assertEquals(
                ACTIVE_SCOPE_PATHS.size()
                        * SIBLINGS_PER_SCOPE,
                fixture.siblingRootBlueIds.size(),
                "every active level declares two sibling embedded roots");
        for (String siblingBlueId
                : fixture.siblingRootBlueIds) {
            assertTrue(
                    hasMetadata(
                            split.metadata(),
                            CoordinationDocumentSplitter
                                    .FragmentKind
                                    .EMBEDDED_ROOT,
                            siblingBlueId));
        }
        assertEquals(
                ACTIVE_SCOPE_PATHS.size() * 4,
                executableBodyMetadataCount(
                        split.metadata()),
                "every active scope retains selected, causal, and two decoy bodies");
    }

    @Test
    void rootOnlySurfaceDemandsNoChildOrSiblingRoot() {
        DemandProof proof =
                demandSurface(
                        Collections.singletonList(
                                ROOT));
        Set<String> childAndSiblingRoots =
                new LinkedHashSet<>(
                        proof.fixture.scopeBlueIds
                                .values());
        childAndSiblingRoots.remove(
                proof.fixture.scopeBlueIds.get(
                        ROOT));
        childAndSiblingRoots.addAll(
                proof.fixture.siblingRootBlueIds);

        assertTrue(
                Collections.disjoint(
                        childAndSiblingRoots,
                        proof.provider
                                .demandedBlueIds()));
        assertEquals(
                3,
                proof.provider.calls(),
                "Root header plus selected and causal Root bodies only");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectionSurfaces")
    void selectedScopeUnionDemandsOnlyChainAndAllowListedBodies(
            String label,
            List<String> selectedScopePaths) {
        DemandProof proof =
                demandSurface(
                        selectedScopePaths);

        assertEquals(
                proof.expectedBlueIds,
                proof.provider.demandedBlueIds(),
                label);
        assertEquals(
                proof.expectedBlueIds.size(),
                proof.provider.calls(),
                "each exact fragment is demanded at most once");

        Set<String> forbidden =
                new LinkedHashSet<>(
                        proof.split.fragments()
                                .keySet());
        forbidden.removeAll(
                proof.expectedBlueIds);
        assertTrue(
                Collections.disjoint(
                        forbidden,
                        proof.provider
                                .demandedBlueIds()),
                "no fragment outside the selected-chain union may be demanded");
        assertTrue(
                Collections.disjoint(
                        proof.fixture.siblingRootBlueIds,
                        proof.provider
                                .demandedBlueIds()),
                "declared sibling embedded roots remain references");
        assertTrue(
                Collections.disjoint(
                        proof.fixture.decoyBodyBlueIds,
                        proof.provider
                                .demandedBlueIds()),
                "decoy operation and reactive bodies remain references");

        for (String selectedPath
                : selectedScopePaths) {
            assertTrue(
                    proof.provider
                            .demandedBlueIds()
                            .contains(
                                    proof.fixture
                                            .selectedBodyBlueIds
                                            .get(selectedPath)));
        }
        for (String chainPath
                : selectedChainUnion(
                        selectedScopePaths)) {
            assertTrue(
                    proof.provider
                            .demandedBlueIds()
                            .contains(
                                    proof.fixture
                                            .causalBodyBlueIds
                                            .get(chainPath)));
        }
    }

    private static Stream<Arguments> selectionSurfaces() {
        return Stream.of(
                Arguments.of(
                        "Root",
                        Collections.singletonList(
                                ROOT)),
                Arguments.of(
                        "Emb1",
                        Collections.singletonList(
                                EMB1)),
                Arguments.of(
                        "Emb2",
                        Collections.singletonList(
                                EMB2)),
                Arguments.of(
                        "Emb3",
                        Collections.singletonList(
                                EMB3)),
                Arguments.of(
                        "Root + Emb3",
                        Arrays.asList(
                                ROOT,
                                EMB3)),
                Arguments.of(
                        "Root + Emb1 + Emb2 + Emb3",
                        ACTIVE_SCOPE_PATHS));
    }

    private static DemandProof demandSurface(
            List<String> selectedScopePaths) {
        Fixture fixture = Fixture.create();
        CoordinationDocumentSplitter.SplitGraph split =
                new CoordinationDocumentSplitter()
                        .splitDocument(fixture.root);
        Set<String> expectedBlueIds =
                expectedBlueIds(
                        fixture,
                        selectedScopePaths);
        StrictRecordingProvider provider =
                new StrictRecordingProvider(
                        split.provider(),
                        expectedBlueIds);
        DemandSession session =
                new DemandSession(provider);

        for (String selectedPath
                : selectedScopePaths) {
            List<String> chain =
                    chainTo(selectedPath);
            Node scope = null;
            String priorPath = null;
            for (String scopePath : chain) {
                if (priorPath == null) {
                    scope = session.demand(
                            fixture.scopeBlueIds.get(
                                    ROOT));
                } else {
                    String childKey =
                            lastSegment(scopePath);
                    Node childReference =
                            NodePathEditor.getOrNull(
                                    scope,
                                    "/" + childKey);
                    assertNotNull(childReference);
                    assertTrue(
                            childReference
                                    .isReferenceOnly());
                    assertEquals(
                            fixture.scopeBlueIds.get(
                                    scopePath),
                            childReference.getBlueId());
                    scope = session.demand(
                            childReference.getBlueId());
                }

                Node causalReference =
                        NodePathEditor.getOrNull(
                                scope,
                                "/contracts/causalReaction/steps");
                assertNotNull(causalReference);
                assertTrue(
                        causalReference
                                .isReferenceOnly());
                assertEquals(
                        fixture.causalBodyBlueIds
                                .get(scopePath),
                        causalReference.getBlueId());
                session.demand(
                        causalReference.getBlueId());
                priorPath = scopePath;
            }

            Node selectedReference =
                    NodePathEditor.getOrNull(
                            scope,
                            "/contracts/selectedOperation/steps");
            assertNotNull(selectedReference);
            assertTrue(
                    selectedReference
                            .isReferenceOnly());
            assertEquals(
                    fixture.selectedBodyBlueIds
                            .get(selectedPath),
                    selectedReference.getBlueId());
            session.demand(
                    selectedReference.getBlueId());
        }

        return new DemandProof(
                fixture,
                split,
                provider,
                expectedBlueIds);
    }

    private static Set<String> expectedBlueIds(
            Fixture fixture,
            List<String> selectedScopePaths) {
        Set<String> expected =
                new LinkedHashSet<>();
        for (String selectedPath
                : selectedScopePaths) {
            for (String chainPath
                    : chainTo(selectedPath)) {
                expected.add(
                        fixture.scopeBlueIds.get(
                                chainPath));
                expected.add(
                        fixture.causalBodyBlueIds.get(
                                chainPath));
            }
            expected.add(
                    fixture.selectedBodyBlueIds.get(
                            selectedPath));
        }
        return expected;
    }

    private static Set<String> selectedChainUnion(
            List<String> selectedScopePaths) {
        Set<String> result =
                new LinkedHashSet<>();
        for (String path : selectedScopePaths) {
            result.addAll(chainTo(path));
        }
        return result;
    }

    private static List<String> chainTo(
            String selectedPath) {
        int targetIndex =
                ACTIVE_SCOPE_PATHS.indexOf(
                        selectedPath);
        if (targetIndex < 0) {
            throw new IllegalArgumentException(
                    "Unknown selected scope path: "
                            + selectedPath);
        }
        return new ArrayList<>(
                ACTIVE_SCOPE_PATHS.subList(
                        0, targetIndex + 1));
    }

    private static String lastSegment(
            String path) {
        return path.substring(
                path.lastIndexOf('/') + 1);
    }

    private static boolean hasMetadata(
            List<CoordinationDocumentSplitter.FragmentMetadata> metadata,
            CoordinationDocumentSplitter.FragmentKind kind,
            String blueId) {
        for (CoordinationDocumentSplitter.FragmentMetadata entry
                : metadata) {
            if (entry.kind() == kind
                    && blueId.equals(entry.blueId())) {
                return true;
            }
        }
        return false;
    }

    private static int executableBodyMetadataCount(
            List<CoordinationDocumentSplitter.FragmentMetadata> metadata) {
        int result = 0;
        for (CoordinationDocumentSplitter.FragmentMetadata entry
                : metadata) {
            if (entry.kind()
                    == CoordinationDocumentSplitter
                            .FragmentKind
                            .EXECUTABLE_BODY) {
                result++;
            }
        }
        return result;
    }

    private static Node processEmbedded(
            List<String> paths) {
        List<Node> values =
                new ArrayList<>();
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

    private static Node handler(
            String typeBlueId,
            String channel,
            String operation,
            Node body) {
        return new Node()
                .type(reference(typeBlueId))
                .properties(
                        "channel", scalar(channel),
                        "operation", scalar(operation),
                        "order", scalar(0L),
                        "steps", body);
    }

    private static Node body(
            String label) {
        return new Node().items(
                new Node().properties(
                        "label", scalar(label),
                        "payload", scalar(
                                repeat(
                                        (char) ('A'
                                                + Math.abs(
                                                label.hashCode())
                                                % 20),
                                        BODY_BYTES))));
    }

    private static Node scalar(
            Object value) {
        return new Node().value(value);
    }

    private static Node reference(
            String blueId) {
        return new Node().blueId(blueId);
    }

    private static String repeat(
            char value,
            int count) {
        char[] characters =
                new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static String appendPath(
            String parent,
            String child) {
        return ROOT.equals(parent)
                ? ROOT + child
                : parent + "/" + child;
    }

    private static final class Fixture {

        private final Node root;
        private final Map<String, String> scopeBlueIds;
        private final Map<String, String> selectedBodyBlueIds;
        private final Map<String, String> causalBodyBlueIds;
        private final Set<String> decoyBodyBlueIds;
        private final Set<String> siblingRootBlueIds;

        private Fixture(
                Node root,
                Map<String, String> scopeBlueIds,
                Map<String, String> selectedBodyBlueIds,
                Map<String, String> causalBodyBlueIds,
                Set<String> decoyBodyBlueIds,
                Set<String> siblingRootBlueIds) {
            this.root = root;
            this.scopeBlueIds =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    scopeBlueIds));
            this.selectedBodyBlueIds =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    selectedBodyBlueIds));
            this.causalBodyBlueIds =
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(
                                    causalBodyBlueIds));
            this.decoyBodyBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    decoyBodyBlueIds));
            this.siblingRootBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    siblingRootBlueIds));
        }

        private static Fixture create() {
            FixtureBuilder builder =
                    new FixtureBuilder();
            Node root =
                    builder.activeScope(
                            0, ROOT);
            return new Fixture(
                    root,
                    builder.scopeBlueIds,
                    builder.selectedBodyBlueIds,
                    builder.causalBodyBlueIds,
                    builder.decoyBodyBlueIds,
                    builder.siblingRootBlueIds);
        }
    }

    private static final class FixtureBuilder {

        private final Map<String, String> scopeBlueIds =
                new LinkedHashMap<>();
        private final Map<String, String> selectedBodyBlueIds =
                new LinkedHashMap<>();
        private final Map<String, String> causalBodyBlueIds =
                new LinkedHashMap<>();
        private final Set<String> decoyBodyBlueIds =
                new LinkedHashSet<>();
        private final Set<String> siblingRootBlueIds =
                new LinkedHashSet<>();

        private Node activeScope(
                int depth,
                String scopePath) {
            Map<String, Node> properties =
                    new LinkedHashMap<>();
            properties.put(
                    "scope",
                    scalar(scopePath));
            List<String> embeddedPaths =
                    new ArrayList<>();

            if (depth
                    < ACTIVE_SCOPE_PATHS.size() - 1) {
                String selectedChild =
                        "emb" + (depth + 1);
                String selectedChildPath =
                        appendPath(
                                scopePath,
                                selectedChild);
                properties.put(
                        selectedChild,
                        activeScope(
                                depth + 1,
                                selectedChildPath));
                embeddedPaths.add(
                        "/" + selectedChild);
            }

            for (int sibling = 1;
                 sibling <= SIBLINGS_PER_SCOPE;
                 sibling++) {
                String key =
                        "sibling" + sibling;
                Node siblingRoot = new Node()
                        .properties(
                                "owner", scalar(scopePath),
                                "branch", scalar(key),
                                "payload", scalar(
                                        repeat(
                                                (char) ('k'
                                                        + sibling),
                                                BODY_BYTES)));
                properties.put(key, siblingRoot);
                embeddedPaths.add("/" + key);
                siblingRootBlueIds.add(
                        BlueIdCalculator
                                .calculateBlueId(
                                        siblingRoot));
            }

            Node selectedBody =
                    body("selected-" + depth);
            Node causalBody =
                    body("causal-" + depth);
            Node decoyOperationBody =
                    body("decoy-operation-"
                            + depth);
            Node decoyReactionBody =
                    body("decoy-reaction-"
                            + depth);
            selectedBodyBlueIds.put(
                    scopePath,
                    BlueIdCalculator.calculateBlueId(
                            selectedBody));
            causalBodyBlueIds.put(
                    scopePath,
                    BlueIdCalculator.calculateBlueId(
                            causalBody));
            decoyBodyBlueIds.add(
                    BlueIdCalculator.calculateBlueId(
                            decoyOperationBody));
            decoyBodyBlueIds.add(
                    BlueIdCalculator.calculateBlueId(
                            decoyReactionBody));

            Map<String, Node> contracts =
                    new LinkedHashMap<>();
            contracts.put(
                    "embedded",
                    processEmbedded(
                            embeddedPaths));
            contracts.put(
                    "selectedOperation",
                    handler(
                            SequentialWorkflowOperation
                                    .blueId(),
                            "timeline-" + depth,
                            "selected-" + depth,
                            selectedBody));
            contracts.put(
                    "causalReaction",
                    handler(
                            SequentialWorkflow.blueId(),
                            "causal-" + depth,
                            "react-" + depth,
                            causalBody));
            contracts.put(
                    "decoyOperation",
                    handler(
                            ChatWorkflowOperation.blueId(),
                            "timeline-" + depth,
                            "decoy-" + depth,
                            decoyOperationBody));
            contracts.put(
                    "decoyReaction",
                    handler(
                            SequentialWorkflow.blueId(),
                            "decoy-" + depth,
                            "ignore-" + depth,
                            decoyReactionBody));

            Node scope = new Node()
                    .properties(properties)
                    .contracts(
                            new Node().properties(
                                    contracts));
            scopeBlueIds.put(
                    scopePath,
                    BlueIdCalculator.calculateBlueId(
                            scope));
            return scope;
        }
    }

    private static final class DemandSession {

        private final NodeProvider provider;
        private final Map<String, Node> cache =
                new LinkedHashMap<>();

        private DemandSession(
                NodeProvider provider) {
            this.provider = provider;
        }

        private Node demand(
                String blueId) {
            Node cached = cache.get(blueId);
            if (cached != null) {
                return cached.clone();
            }
            List<Node> nodes =
                    provider.fetchByBlueId(
                            blueId);
            assertNotNull(nodes);
            assertEquals(1, nodes.size());
            Node exact = nodes.get(0);
            assertEquals(
                    blueId,
                    BlueIdCalculator.calculateBlueId(
                            exact));
            cache.put(blueId, exact.clone());
            return exact;
        }
    }

    private static final class StrictRecordingProvider
            implements NodeProvider {

        private final NodeProvider delegate;
        private final Set<String> allowedBlueIds;
        private final Set<String> demandedBlueIds =
                new LinkedHashSet<>();
        private int calls;

        private StrictRecordingProvider(
                NodeProvider delegate,
                Set<String> allowedBlueIds) {
            this.delegate = delegate;
            this.allowedBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    allowedBlueIds));
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            assertTrue(
                    allowedBlueIds.contains(
                            blueId),
                    "forbidden fragment demand: "
                            + blueId);
            assertTrue(
                    demandedBlueIds.add(
                            blueId),
                    "duplicate provider demand: "
                            + blueId);
            calls++;
            List<Node> nodes =
                    delegate.fetchByBlueId(
                            blueId);
            assertNotNull(
                    nodes,
                    "allowed exact fragment is missing: "
                            + blueId);
            return nodes;
        }

        private Set<String> demandedBlueIds() {
            return Collections.unmodifiableSet(
                    demandedBlueIds);
        }

        private int calls() {
            return calls;
        }
    }

    private static final class DemandProof {

        private final Fixture fixture;
        private final CoordinationDocumentSplitter.SplitGraph split;
        private final StrictRecordingProvider provider;
        private final Set<String> expectedBlueIds;

        private DemandProof(
                Fixture fixture,
                CoordinationDocumentSplitter.SplitGraph split,
                StrictRecordingProvider provider,
                Set<String> expectedBlueIds) {
            this.fixture = fixture;
            this.split = split;
            this.provider = provider;
            this.expectedBlueIds =
                    Collections.unmodifiableSet(
                            new LinkedHashSet<>(
                                    expectedBlueIds));
        }
    }
}
