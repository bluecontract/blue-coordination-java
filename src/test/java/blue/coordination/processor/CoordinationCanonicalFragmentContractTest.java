package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationCanonicalFragmentContractTest {

    @Test
    void shouldRetainOneCanonicalFragmentForSameBlueIdAtDifferentCutOccurrences() {
        // Given
        Node shared = new Node().properties(
                "payload",
                scalar("same"));
        Node root = new Node()
                .properties(
                        "left", shared,
                        "right", shared.clone())
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                "/left",
                                "/right")));
        String sharedBlueId =
                BlueIdCalculator.calculateBlueId(
                        shared);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        List<CoordinationDocumentSplitter.EdgeOccurrence>
                occurrences = occurrences(
                split,
                CoordinationDocumentSplitter.EdgeKind
                        .EMBEDDED_ROOT,
                sharedBlueId);

        // Then
        assertEquals(
                CoordinationDocumentSplitter
                        .FRAGMENTATION_PROFILE_ID,
                split.fragmentationProfileIdentity());
        assertEquals(
                CoordinationDocumentSplitter
                        .EDGE_METADATA_SCHEMA_ID,
                split.edgeMetadataSchemaIdentity());
        assertEquals(2, occurrences.size());
        assertEquals(
                Arrays.asList(
                        "/left",
                        "/right"),
                Arrays.asList(
                        occurrences.get(0)
                                .absolutePointer(),
                        occurrences.get(1)
                                .absolutePointer()));
        assertTrue(
                occurrences.get(0)
                        .splitterCreated());
        assertTrue(
                occurrences.get(1)
                        .splitterCreated());
        assertEquals(
                1,
                countKey(
                        split.fragments(),
                        sharedBlueId));
        Node stored =
                split.fragments().get(
                        sharedBlueId);
        assertTrue(
                stored.getProperties()
                        .get("payload")
                        .isReferenceOnly(),
                "the stored representation is the canonical shallow node");
        assertEquals(
                NodeToMapListOrValue.get(root),
                NodeToMapListOrValue.get(
                        split.reconstruct()));
    }

    @Test
    void shouldPreserveAuthoredReferencesWhileReconstructingCreatedEdges() {
        // Given
        Node inline = new Node().properties(
                "payload",
                scalar("inline"));
        String authoredBlueId =
                BlueIdCalculator.calculateBlueId(
                        scalar("external"));
        Node event = new Node().properties(
                "inline", inline,
                "authored",
                new Node().blueId(
                        authoredBlueId));

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter
                        .forEventSplitting()
                        .splitEvent(event);
        Node reconstructed =
                split.reconstruct();
        CoordinationDocumentSplitter.EdgeOccurrence
                authored = occurrenceAt(
                split,
                "/authored");

        // Then
        assertTrue(
                authored.originalPureReference());
        assertFalse(
                authored.splitterCreated());
        assertFalse(
                split.fragments().containsKey(
                        authoredBlueId));
        assertTrue(
                reconstructed.getProperties()
                        .get("authored")
                        .isReferenceOnly());
        assertEquals(
                authoredBlueId,
                reconstructed.getProperties()
                        .get("authored")
                        .getBlueId());
        assertEquals(
                NodeToMapListOrValue.get(event),
                NodeToMapListOrValue.get(
                        reconstructed));
    }

    @Test
    void shouldDistinguishAuthoredExecutableBodyReferenceFromCreatedCut() {
        // Given
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().items(
                                scalar("external-step")));
        Node root = new Node().contracts(
                new Node().properties(
                        "operation",
                        new Node()
                                .type(new Node().blueId(
                                        SequentialWorkflowOperation
                                                .blueId()))
                                .properties(
                                        "channel",
                                        scalar("timeline"),
                                        "steps",
                                        new Node().blueId(
                                                bodyBlueId))));

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        CoordinationDocumentSplitter.EdgeOccurrence edge =
                occurrenceAt(
                        split,
                        "/contracts/operation/steps");

        // Then
        assertEquals(
                CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY,
                edge.edgeKind());
        assertTrue(
                edge.originalPureReference());
        assertFalse(
                edge.splitterCreated());
        assertFalse(
                split.fragments().containsKey(
                        bodyBlueId));
        assertEquals(
                bodyBlueId,
                split.reconstruct()
                        .getContracts()
                        .getProperties()
                        .get("operation")
                        .getProperties()
                        .get("steps")
                        .getBlueId());
    }

    @Test
    void shouldRejectMissingFragmentInventory() {
        // Given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        CoordinationDocumentSplitter.EdgeOccurrence
                created = firstCreatedEdge(
                split);
        Map<String, Node> missing =
                new TreeMap<>(
                        split.fragments());
        missing.remove(
                created.childBlueId());

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationFragmentReconstructor
                                .reconstruct(
                                        split
                                                .fragmentationProfileIdentity(),
                                        split.rootBlueId(),
                                        split.fragmentRoots(),
                                        missing,
                                        split.edgeOccurrences()));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains("missing"));
    }

    @Test
    void shouldRejectMixedCompleteAndCanonicalDirectRepresentations() {
        // Given
        Node child = new Node().properties(
                "payload",
                scalar("child"));
        Node event = new Node().properties(
                "child",
                child);
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter
                        .forEventSplitting()
                        .splitEvent(event);
        String childBlueId =
                BlueIdCalculator.calculateBlueId(
                        child);
        Map<String, Node> mixed =
                new TreeMap<>(
                        split.fragments());
        mixed.put(
                childBlueId,
                child.clone());

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationFragmentReconstructor
                                .reconstruct(
                                        split
                                                .fragmentationProfileIdentity(),
                                        split.rootBlueId(),
                                        split.fragmentRoots(),
                                        mixed,
                                        split.edgeOccurrences()));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains("nonphysical")
                        || failure.getMessage()
                        .contains("noncanonical"));
    }

    @Test
    void shouldRejectInconsistentEdgeOccurrenceInventory() {
        // Given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        List<CoordinationDocumentSplitter.EdgeOccurrence>
                inconsistent =
                new ArrayList<>(
                        split.edgeOccurrences());
        CoordinationDocumentSplitter.EdgeOccurrence original =
                firstCreatedEdge(
                        split);
        inconsistent.set(
                inconsistent.indexOf(original),
                copyWithChild(
                        original,
                        split.rootBlueId()));

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationFragmentReconstructor
                                .reconstruct(
                                        split
                                                .fragmentationProfileIdentity(),
                                        split.rootBlueId(),
                                        split.fragmentRoots(),
                                        split.fragments(),
                                        inconsistent));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains("disagrees"));
    }

    @Test
    void shouldAdmitDuplicateFragmentsIdempotentlyAndReturnDefensiveValues() {
        // Given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        String blueId =
                split.rootBlueId();
        Node fragment =
                split.fragments().get(
                        blueId);
        InMemoryStore store =
                new InMemoryStore();

        // When
        CoordinationFragmentAdmissionVerifier.AdmissionStatus first =
                CoordinationFragmentAdmissionVerifier.admit(
                        split.fragmentationProfileIdentity(),
                        blueId,
                        fragment,
                        store);
        CoordinationFragmentAdmissionVerifier.AdmissionStatus second =
                CoordinationFragmentAdmissionVerifier.admit(
                        split.fragmentationProfileIdentity(),
                        blueId,
                        fragment,
                        store);
        Node returned =
                store.read(
                        split.fragmentationProfileIdentity(),
                        blueId);
        returned.name("mutated");

        // Then
        assertEquals(
                CoordinationFragmentAdmissionVerifier
                        .AdmissionStatus.ADMITTED,
                first);
        assertEquals(
                CoordinationFragmentAdmissionVerifier
                        .AdmissionStatus.IDEMPOTENT_DUPLICATE,
                second);
        assertFalse(
                "mutated".equals(
                        store.read(
                                split.fragmentationProfileIdentity(),
                                blueId)
                                .getName()));
    }

    @Test
    void shouldRejectInconsistentConcurrentAdmissionWinner() {
        // Given
        Node child = new Node().properties(
                "payload",
                scalar("child"));
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter
                        .forEventSplitting()
                        .splitEvent(
                                new Node().properties(
                                        "child",
                                        child));
        String childBlueId =
                BlueIdCalculator.calculateBlueId(
                        child);
        Node canonical =
                split.fragments().get(
                        childBlueId);
        RacingStore store =
                new RacingStore(
                        child.clone());

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationFragmentAdmissionVerifier
                                .admit(
                                        split
                                                .fragmentationProfileIdentity(),
                                        childBlueId,
                                        canonical,
                                        store));

        // Then
        assertTrue(
                failure.getMessage()
                        .contains("canonical direct-node")
                        || failure.getMessage()
                        .contains("winner bytes disagree"));
    }

    @Test
    void shouldKeepCyclicMemberEdgeOpaqueWithoutFabricatingFragment() {
        // Given
        String masterBlueId =
                BlueIdCalculator.calculateBlueId(
                        scalar("cyclic-master"));
        String memberBlueId =
                masterBlueId + "#0";
        Node event = new Node().properties(
                "member",
                new Node().blueId(
                        memberBlueId));

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter
                        .forEventSplitting()
                        .splitEvent(event);
        Node reconstructed =
                split.reconstruct();
        CoordinationDocumentSplitter.EdgeOccurrence member =
                occurrenceAt(
                        split,
                        "/member");

        // Then
        assertTrue(
                member.originalPureReference());
        assertFalse(
                member.splitterCreated());
        assertFalse(
                split.fragments().containsKey(
                        memberBlueId));
        assertEquals(
                memberBlueId,
                reconstructed.getProperties()
                        .get("member")
                        .getBlueId());
    }

    @Test
    void shouldProduceStableInventoryIdentityIndependentOfReturnedCopies() {
        // Given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        CoordinationDocumentSplitter.SplitGraph repeated =
                eventSplit();
        String before =
                split.inventoryIdentity();
        Map<String, Node> returned =
                split.fragments();

        // When
        returned.get(
                split.rootBlueId())
                .description("caller mutation");
        String after =
                split.inventoryIdentity();

        // Then
        assertEquals(before, after);
        assertEquals(
                before,
                repeated.inventoryIdentity());
        assertEquals(
                split.edgeOccurrences(),
                repeated.edgeOccurrences());
        assertTrue(
                before.startsWith(
                        "sha256:"));
        assertNotEquals(
                CoordinationFragmentAdmissionVerifier
                        .physicalFragmentIdentity(
                                returned.get(
                                        split.rootBlueId())),
                CoordinationFragmentAdmissionVerifier
                        .physicalFragmentIdentity(
                                split.fragments().get(
                                        split.rootBlueId())));
    }

    private static CoordinationDocumentSplitter.SplitGraph
    eventSplit() {
        return CoordinationDocumentSplitter
                .forEventSplitting()
                .splitEvent(
                        new Node().properties(
                                "left",
                                new Node().properties(
                                        "payload",
                                        scalar("left")),
                                "right",
                                new Node().properties(
                                        "payload",
                                        scalar("right"))));
    }

    private static List<CoordinationDocumentSplitter.EdgeOccurrence>
    occurrences(
            CoordinationDocumentSplitter.SplitGraph split,
            CoordinationDocumentSplitter.EdgeKind kind,
            String childBlueId) {
        List<CoordinationDocumentSplitter.EdgeOccurrence> result =
                new ArrayList<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (occurrence.edgeKind() == kind
                    && childBlueId.equals(
                    occurrence.childBlueId())) {
                result.add(occurrence);
            }
        }
        result.sort(
                java.util.Comparator.comparing(
                        CoordinationDocumentSplitter
                        .EdgeOccurrence::absolutePointer));
        return result;
    }

    private static CoordinationDocumentSplitter.EdgeOccurrence
    occurrenceAt(
            CoordinationDocumentSplitter.SplitGraph split,
            String absolutePointer) {
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (absolutePointer.equals(
                    occurrence.absolutePointer())) {
                return occurrence;
            }
        }
        throw new AssertionError(
                "No occurrence at "
                        + absolutePointer);
    }

    private static CoordinationDocumentSplitter.EdgeOccurrence
    firstCreatedEdge(
            CoordinationDocumentSplitter.SplitGraph split) {
        for (CoordinationDocumentSplitter.EdgeOccurrence occurrence
                : split.edgeOccurrences()) {
            if (occurrence.splitterCreated()) {
                return occurrence;
            }
        }
        throw new AssertionError(
                "No splitter-created edge");
    }

    private static CoordinationDocumentSplitter.EdgeOccurrence
    copyWithChild(
            CoordinationDocumentSplitter.EdgeOccurrence source,
            String childBlueId) {
        return new CoordinationDocumentSplitter.EdgeOccurrence(
                source.fragmentationProfileIdentity(),
                source.schemaIdentity(),
                source.rootKind(),
                source.rootBlueId(),
                source.ownerNodeBlueId(),
                source.ownerScopePath(),
                source.absolutePointer(),
                source.ownerRelativePointer(),
                childBlueId,
                source.edgeKind(),
                source.originalPureReference(),
                source.splitterCreated(),
                source.handlerEffectiveTypeBlueId(),
                source.executableBodyField(),
                source.sourceContributionBlueIds());
    }

    private static int countKey(
            Map<String, Node> fragments,
            String blueId) {
        return fragments.containsKey(
                blueId) ? 1 : 0;
    }

    private static Node processEmbedded(
            String... paths) {
        List<Node> values =
                new ArrayList<>();
        for (String path : paths) {
            values.add(
                    scalar(path));
        }
        return new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(
                                values));
    }

    private static Node scalar(
            String value) {
        return new Node().value(
                value);
    }

    private static class InMemoryStore
            implements CoordinationFragmentAdmissionVerifier
            .ImmutableFragmentStore {

        private final Map<String, Node> values =
                new LinkedHashMap<>();

        @Override
        public Node read(
                String profileIdentity,
                String blueId) {
            Node retained =
                    values.get(
                            profileIdentity
                                    + ":"
                                    + blueId);
            return retained != null
                    ? retained.clone()
                    : null;
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node exactFragment) {
            String key =
                    profileIdentity
                            + ":"
                            + blueId;
            if (values.containsKey(
                    key)) {
                return false;
            }
            values.put(
                    key,
                    exactFragment.clone());
            return true;
        }
    }

    private static final class RacingStore
            extends InMemoryStore {

        private final Node winner;

        private RacingStore(
                Node winner) {
            this.winner =
                    winner;
        }

        @Override
        public boolean putIfAbsent(
                String profileIdentity,
                String blueId,
                Node ignored) {
            super.putIfAbsent(
                    profileIdentity,
                    blueId,
                    winner);
            return false;
        }
    }
}
