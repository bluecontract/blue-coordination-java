package blue.coordination.processor;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
        // given
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
                DirectBlueIdCalculator.calculateBlueId(
                        shared);

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);
        List<CoordinationDocumentSplitter.EdgeOccurrence>
                occurrences = occurrences(
                split,
                CoordinationDocumentSplitter.EdgeKind
                        .EMBEDDED_ROOT,
                sharedBlueId);

        // then
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
                NodeWireForm.get(root),
                NodeWireForm.get(
                        split.reconstruct()));
    }

    @Test
    void shouldPreserveAuthoredReferencesWhileReconstructingCreatedEdges() {
        // given
        Node inline = new Node().properties(
                "payload",
                scalar("inline"));
        String authoredBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        scalar("external"));
        Node event = new Node().properties(
                "inline", inline,
                "authored",
                new Node().blueId(
                        authoredBlueId));

        // when
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

        // then
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
                NodeWireForm.get(event),
                NodeWireForm.get(
                        reconstructed));
    }

    @Test
    void shouldDistinguishAuthoredReferenceFromCreatedCut() {
        // given
        Node inline = new Node().items(
                scalar("inline-step"));
        String authoredBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().items(
                                scalar("external-step")));
        Node event = new Node().properties(
                "inline", inline,
                "authored", new Node().blueId(authoredBlueId));

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitter.forEventSplitting()
                        .splitEvent(event);
        CoordinationDocumentSplitter.EdgeOccurrence authored =
                occurrenceAt(split, "/authored");
        CoordinationDocumentSplitter.EdgeOccurrence created =
                occurrenceAt(split, "/inline");

        // then
        assertEquals(
                CoordinationDocumentSplitter.EdgeKind
                        .EVENT_DIRECT_CHILD,
                authored.edgeKind());
        assertTrue(authored.originalPureReference());
        assertFalse(authored.splitterCreated());
        assertFalse(created.originalPureReference());
        assertTrue(created.splitterCreated());
        assertFalse(split.fragments().containsKey(authoredBlueId));
    }

    @Test
    void shouldRejectMissingFragmentInventory() {
        // given
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

        // when
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

        // then
        assertTrue(
                failure.getMessage()
                        .contains("missing"));
    }

    @Test
    void shouldRejectMixedCompleteAndCanonicalDirectRepresentations() {
        // given
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
                DirectBlueIdCalculator.calculateBlueId(
                        child);
        Map<String, Node> mixed =
                new TreeMap<>(
                        split.fragments());
        mixed.put(
                childBlueId,
                child.clone());

        // when
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

        // then
        assertTrue(
                failure.getMessage()
                        .contains("nonphysical")
                        || failure.getMessage()
                        .contains("noncanonical"));
    }

    @Test
    void shouldRejectInconsistentEdgeOccurrenceInventory() {
        // given
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

        // when
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

        // then
        assertTrue(
                failure.getMessage()
                        .contains("disagrees"));
    }

    @Test
    void shouldAdmitDuplicateFragmentsIdempotentlyAndReturnDefensiveValues() {
        // given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        String blueId =
                split.rootBlueId();
        Node fragment =
                split.fragments().get(
                        blueId);
        InMemoryStore store =
                new InMemoryStore();

        // when
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

        // then
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
        // given
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
                DirectBlueIdCalculator.calculateBlueId(
                        child);
        Node canonical =
                split.fragments().get(
                        childBlueId);
        RacingStore store =
                new RacingStore(
                        child.clone());

        // when
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

        // then
        assertTrue(
                failure.getMessage()
                        .contains("canonical direct-node")
                        || failure.getMessage()
                        .contains("winner bytes disagree"));
    }

    @Test
    void shouldAdmitCompleteFragmentInventoryAtomicallyAndIdempotently() {
        // given
        CoordinationDocumentSplitter.SplitGraph split = eventSplit();
        InMemoryStore store = new InMemoryStore();

        // when
        CoordinationFragmentAdmissionVerifier.AdmissionStatus first =
                CoordinationFragmentAdmissionVerifier.admitInventory(
                        split.fragmentationProfileIdentity(),
                        split.fragmentRoots(),
                        split.fragments(),
                        split.edgeOccurrences(),
                        store);
        CoordinationFragmentAdmissionVerifier.AdmissionStatus second =
                CoordinationFragmentAdmissionVerifier.admitInventory(
                        split.fragmentationProfileIdentity(),
                        split.fragmentRoots(),
                        split.fragments(),
                        split.edgeOccurrences(),
                        store);

        // then
        assertEquals(
                CoordinationFragmentAdmissionVerifier.AdmissionStatus.ADMITTED,
                first);
        assertEquals(
                CoordinationFragmentAdmissionVerifier.AdmissionStatus
                        .IDEMPOTENT_DUPLICATE,
                second);
        assertEquals(split.fragments().size(), store.size());
    }

    @Test
    void shouldRejectConflictingAtomicInventoryWithoutPartialAdmission() {
        // given
        CoordinationDocumentSplitter.SplitGraph split = eventSplit();
        InMemoryStore store = new InMemoryStore();
        store.putIfAbsent(
                split.fragmentationProfileIdentity(),
                split.rootBlueId(),
                split.originalRoot());

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CoordinationFragmentAdmissionVerifier.admitInventory(
                        split.fragmentationProfileIdentity(),
                        split.fragmentRoots(),
                        split.fragments(),
                        split.edgeOccurrences(),
                        store));

        // then
        assertTrue(
                failure.getMessage().contains("winner")
                        || failure.getMessage().contains("canonical")
                        || failure.getMessage().contains("Atomic store"));
        assertEquals(1, store.size());
    }

    @Test
    void shouldKeepCyclicMemberEdgeOpaqueWithoutFabricatingFragment() {
        // given
        String masterBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        scalar("cyclic-master"));
        String memberBlueId =
                masterBlueId + "#0";
        Node event = new Node().properties(
                "member",
                new Node().blueId(
                        memberBlueId));

        // when
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

        // then
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
        // given
        CoordinationDocumentSplitter.SplitGraph split =
                eventSplit();
        CoordinationDocumentSplitter.SplitGraph repeated =
                eventSplit();
        String before =
                split.inventoryIdentity();
        Map<String, Node> returned =
                split.fragments();

        // when
        returned.get(
                split.rootBlueId())
                .description("caller mutation");
        String after =
                split.inventoryIdentity();

        // then
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

    @Test
    void shouldRetainNestedCollectionDeclarationProvenanceAndEscapedKeys() {
        // given
        Node lesson = new Node().properties(
                "state",
                scalar("ready"));
        Node project = projectTemplate(lesson);
        Node root = collectionRoot(project);

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root);
        List<CoordinationDocumentSplitter.EdgeOccurrence> embedded =
                embeddedOccurrences(split);
        CoordinationDocumentSplitter.EdgeOccurrence rootMember =
                occurrenceAt(
                        split,
                        "/projects/a~0key");
        CoordinationDocumentSplitter.EdgeOccurrence nestedMember =
                occurrenceAt(
                        split,
                        "/projects/a~0key/lessons/lesson~12");
        CoordinationDocumentSplitter.EdgeOccurrence explicit =
                occurrenceAt(
                        split,
                        "/projects/a~0key/featured");

        // then
        assertEquals(
                Arrays.asList(
                        "/projects/a~0key",
                        "/projects/a~0key/featured",
                        "/projects/a~0key/lessons/lesson~01",
                        "/projects/a~0key/lessons/lesson~12",
                        "/projects/z~1key",
                        "/projects/z~1key/featured",
                        "/projects/z~1key/lessons/lesson~01",
                        "/projects/z~1key/lessons/lesson~12"),
                absolutePointers(embedded));
        assertEquals("/", rootMember.declaringScopePath());
        assertEquals(
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin
                        .COLLECTION_MEMBER,
                rootMember.embeddedOrigin());
        assertEquals(
                "/projects",
                rootMember.collectionDeclarationPath());
        assertEquals("a~key", rootMember.collectionMemberKey());
        assertEquals(
                "/projects/a~0key",
                nestedMember.declaringScopePath());
        assertEquals(
                "/lessons",
                nestedMember.collectionDeclarationPath());
        assertEquals("lesson/2", nestedMember.collectionMemberKey());
        assertEquals(
                CoordinationDocumentSplitter.EmbeddedEdgeOrigin.EXPLICIT,
                explicit.embeddedOrigin());
        assertEquals("/featured", explicit.explicitDeclarationPath());
        assertEquals(null, explicit.collectionDeclarationPath());
        assertEquals(
                NodeWireForm.get(root),
                NodeWireForm.get(split.reconstruct()));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(root),
                DirectBlueIdCalculator.calculateBlueId(
                        split.reconstruct()));
    }

    @Test
    void shouldKeepSameChildIdentityAtSeveralCollectionKeysAsSeparateOccurrences() {
        // given
        Node lesson = new Node().properties(
                "state",
                scalar("shared"));
        Node project = projectTemplate(lesson);
        Node root = collectionRoot(project);
        String projectBlueId =
                DirectBlueIdCalculator.calculateBlueId(project);
        String lessonBlueId =
                DirectBlueIdCalculator.calculateBlueId(lesson);

        // when
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root);

        // then
        assertEquals(
                2,
                occurrences(
                        split,
                        CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT,
                        projectBlueId).size());
        assertEquals(
                1,
                countKey(split.fragments(), projectBlueId));
        assertEquals(
                1,
                countKey(split.fragments(), lessonBlueId));
        assertEquals(
                6,
                occurrences(
                        split,
                        CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT,
                        lessonBlueId).size());
    }

    @Test
    void shouldRejectListCollectionTargetThroughLanguageCatalog() {
        // given
        Node root = invalidCollectionRoot(
                new Node().items(
                        new Node().properties(
                                "state", scalar("invalid"))));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("collection"));
        assertTrue(failure.getMessage().contains("object"));
    }

    @Test
    void shouldRejectScalarCollectionTargetThroughLanguageCatalog() {
        // given
        Node root = invalidCollectionRoot(scalar("invalid"));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("collection"));
        assertTrue(failure.getMessage().contains("object"));
    }

    @Test
    void shouldRejectScalarCollectionMemberThroughLanguageCatalog() {
        // given
        Node root = invalidCollectionRoot(
                new Node().properties(
                        "bad-member",
                        scalar("invalid")));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("member"));
        assertTrue(failure.getMessage().contains("object"));
    }

    @Test
    void shouldRejectOpaqueCyclicCollectionMemberThroughLanguageCatalog() {
        // given
        String cyclicMaster = DirectBlueIdCalculator.calculateBlueId(
                scalar("cyclic-master"));
        Node root = invalidCollectionRoot(
                new Node().properties(
                        "cyclic-member",
                        new Node().blueId(cyclicMaster + "#0")));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("cyclic-set"));
        assertTrue(failure.getMessage().contains("/projects"));
    }

    @Test
    void shouldRejectWildcardCollectionDeclarationThroughLanguageCatalog() {
        // given
        Node root = new Node()
                .properties(
                        "projects",
                        new Node().properties(
                                "one",
                                new Node().properties(
                                        "state", scalar("ready"))))
                .contracts(new Node().properties(
                        "embedded",
                        processEmbeddedCollections(
                                "/projects/*")));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("selector"));
    }

    @Test
    void shouldRejectReservedCollectionDeclarationThroughLanguageCatalog() {
        // given
        Node root = new Node().contracts(new Node().properties(
                "embedded",
                processEmbeddedCollections(
                        "/contracts")));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("reserved"));
    }

    @Test
    void shouldRejectExplicitAndCollectionDeclarationOverlapThroughLanguageCatalog() {
        // given
        Node root = new Node()
                .properties(
                        "projects",
                        new Node().properties(
                                "one",
                                new Node().properties(
                                        "state", scalar("ready"))))
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                Collections.singletonList(
                                        "/projects/one"),
                                Collections.singletonList(
                                        "/projects"))));

        // when
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                () -> CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(root));

        // then
        assertTrue(failure.getMessage().contains("Overlapping"));
    }

    @Test
    void shouldRejectCollectionEdgeMetadataWhoseRawKeyDisagreesWithPointer() {
        // given
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitCollectionDocument(
                                collectionRoot(
                                        projectTemplate(
                                                new Node().properties(
                                                        "state",
                                                        scalar("ready")))));
        CoordinationDocumentSplitter.EdgeOccurrence source = occurrenceAt(
                split,
                "/projects/a~0key");

        // when
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new CoordinationDocumentSplitter.EdgeOccurrence(
                        source.fragmentationProfileIdentity(),
                        source.schemaIdentity(),
                        source.rootKind(),
                        source.rootBlueId(),
                        source.ownerNodeBlueId(),
                        source.ownerScopePath(),
                        source.absolutePointer(),
                        source.ownerRelativePointer(),
                        source.childBlueId(),
                        source.edgeKind(),
                        source.originalPureReference(),
                        source.splitterCreated(),
                        source.declaringScopePath(),
                        source.embeddedOrigin(),
                        source.explicitDeclarationPath(),
                        source.collectionDeclarationPath(),
                        "another-key",
                        source.handlerEffectiveTypeBlueId(),
                        source.executableBodyField(),
                        source.sourceContributionBlueIds()));

        // then
        assertTrue(failure.getMessage().contains("member key"));
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

    private static Node collectionRoot(Node project) {
        Map<String, Node> projects = new LinkedHashMap<>();
        projects.put("z/key", project.clone());
        projects.put("a~key", project.clone());
        return new Node()
                .properties(
                        "projects",
                        new Node().properties(projects))
                .contracts(new Node().properties(
                        "embedded",
                        processEmbeddedCollections(
                                "/projects")));
    }

    private static Node projectTemplate(Node lesson) {
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put("lesson/2", lesson.clone());
        lessons.put("lesson~1", lesson.clone());
        return new Node()
                .properties(
                        "featured",
                        lesson.clone(),
                        "lessons",
                        new Node().properties(lessons))
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                Collections.singletonList(
                                        "/featured"),
                                Collections.singletonList(
                                        "/lessons"))));
    }

    private static Node invalidCollectionRoot(Node collection) {
        return new Node()
                .properties("projects", collection)
                .contracts(new Node().properties(
                        "embedded",
                        processEmbeddedCollections(
                                "/projects")));
    }

    private static List<CoordinationDocumentSplitter.EdgeOccurrence>
    embeddedOccurrences(
            CoordinationDocumentSplitter.SplitGraph split) {
        List<CoordinationDocumentSplitter.EdgeOccurrence> result =
                new ArrayList<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence edge
                : split.edgeOccurrences()) {
            if (edge.edgeKind()
                    == CoordinationDocumentSplitter.EdgeKind.EMBEDDED_ROOT) {
                result.add(edge);
            }
        }
        result.sort(java.util.Comparator.comparing(
                CoordinationDocumentSplitter.EdgeOccurrence::absolutePointer));
        return result;
    }

    private static List<String> absolutePointers(
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges) {
        List<String> result = new ArrayList<>();
        for (CoordinationDocumentSplitter.EdgeOccurrence edge : edges) {
            result.add(edge.absolutePointer());
        }
        return result;
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
                source.declaringScopePath(),
                source.embeddedOrigin(),
                source.explicitDeclarationPath(),
                source.collectionDeclarationPath(),
                source.collectionMemberKey(),
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
        return processEmbedded(
                Arrays.asList(paths),
                Collections.<String>emptyList());
    }

    private static Node processEmbeddedCollections(
            String... collectionPaths) {
        return processEmbedded(
                Collections.<String>emptyList(),
                Arrays.asList(collectionPaths));
    }

    private static Node processEmbedded(
            List<String> paths,
            List<String> collectionPaths) {
        List<Node> values =
                new ArrayList<>();
        for (String path : paths) {
            values.add(
                    scalar(path));
        }
        List<Node> collectionValues = new ArrayList<>();
        for (String path : collectionPaths) {
            collectionValues.add(scalar(path));
        }
        Node contract = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED));
        Map<String, Node> properties = new LinkedHashMap<>();
        if (!values.isEmpty()) {
            properties.put("paths", new Node().items(values));
        }
        if (!collectionValues.isEmpty()) {
            properties.put(
                    "collectionPaths",
                    new Node().items(collectionValues));
        }
        return contract.properties(properties);
    }

    private static Node scalar(
            String value) {
        return new Node().value(
                value);
    }

    private static class InMemoryStore
            implements CoordinationFragmentAdmissionVerifier
            .AtomicImmutableFragmentStore {

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

        @Override
        public boolean putAllIfAbsent(
                String profileIdentity,
                Map<String, Node> exactFragments) {
            for (Map.Entry<String, Node> entry
                    : exactFragments.entrySet()) {
                Node existing = values.get(
                        profileIdentity + ":" + entry.getKey());
                if (existing != null
                        && !NodeWireForm.get(existing).equals(
                        NodeWireForm.get(entry.getValue()))) {
                    return false;
                }
            }
            boolean changed = false;
            for (Map.Entry<String, Node> entry
                    : exactFragments.entrySet()) {
                String key = profileIdentity + ":" + entry.getKey();
                if (!values.containsKey(key)) {
                    values.put(key, entry.getValue().clone());
                    changed = true;
                }
            }
            return changed;
        }

        private int size() {
            return values.size();
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
