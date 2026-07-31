package blue.coordination.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodePathEditor;
import blue.language.utils.NodeTransformer;
import blue.language.utils.UncheckedObjectMapper;
import blue.repo.coordination.ChatWorkflowOperation;
import blue.repo.coordination.Compute;
import blue.repo.coordination.Operation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationDocumentSplitterTest {

    private final CoordinationDocumentSplitter splitter =
            CoordinationDocumentSplitter.forEventSplitting();

    @Test
    void shouldFailClosedWhenDocumentSplittingHasNoEffectiveCatalog() {
        // Given
        Fixture fixture = fixture();

        // When
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> splitter.splitDocument(
                                fixture.root));

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "effective fragmentation catalog"));
    }

    @Test
    void shouldClassifyEmbeddedCutsWithoutClassifyingUnrelatedSiblings() {
        // Given
        Fixture fixture = fixture();
        String exactRootBlueId =
                BlueIdCalculator.calculateBlueId(fixture.root);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);

        // Then
        assertEquals(exactRootBlueId, split.rootBlueId());
        assertEquals(
                exactRootBlueId,
                BlueIdCalculator.calculateBlueId(
                        split.fragmentedRoot()));
        assertEquals(
                exactRootBlueId,
                split.pureReference().getBlueId());

        Node fragmentedRoot = split.fragmentedRoot();
        Node childReference =
                NodePathEditor.getOrNull(
                        fragmentedRoot, "/child");
        assertNotNull(childReference);
        assertTrue(childReference.isReferenceOnly());
        assertEquals(
                fixture.childBlueId,
                childReference.getBlueId());

        Node sibling =
                NodePathEditor.getOrNull(
                        fragmentedRoot, "/sibling");
        assertNotNull(sibling);
        assertTrue(
                sibling.isReferenceOnly(),
                "the canonical direct-node profile stores every direct child "
                        + "uniformly");
        assertTrue(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .DOCUMENT_DIRECT_CHILD,
                "/sibling"));
        assertFalse(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .EMBEDDED_ROOT,
                "/sibling"));

        Node childFragment =
                fetchOne(
                        split.provider(),
                        fixture.childBlueId);
        assertEquals(
                fixture.childBlueId,
                BlueIdCalculator.calculateBlueId(
                        childFragment));
        Node grandchildReference =
                NodePathEditor.getOrNull(
                        childFragment, "/grandchild");
        assertNotNull(grandchildReference);
        assertTrue(
                grandchildReference.isReferenceOnly());
        assertEquals(
                fixture.grandchildBlueId,
                grandchildReference.getBlueId());
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EMBEDDED_ROOT,
                "/child"));
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EMBEDDED_ROOT,
                "/child/grandchild"));
    }

    @Test
    void shouldCutRegisteredBodiesAsCanonicalDirectFragments() {
        // Given
        Fixture fixture = fixture();

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);

        // Then
        assertTrue(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY,
                "/contracts/rootOperation/steps"));
        assertTrue(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY,
                "/child/contracts/childWorkflow/steps"));

        Node storedRootBody =
                split.fragments().get(
                        fixture.rootBodyBlueId);
        Node processRootBody =
                fetchOne(
                        split.provider(),
                        fixture.rootBodyBlueId);
        assertEquals(
                fixture.rootBodyBlueId,
                BlueIdCalculator.calculateBlueId(
                        processRootBody));
        assertTrue(
                storedRootBody.getItems().get(0)
                        .isReferenceOnly(),
                "the stored executable body uses the canonical shallow "
                        + "profile");
        assertFalse(
                processRootBody.getItems().get(0)
                        .isReferenceOnly(),
                "the PROCESS profile exposes a demanded step's direct "
                        + "fragment");
        assertFalse(
                NodePathEditor.getOrNull(
                        processRootBody,
                        "/0/payload")
                        .isReferenceOnly(),
                "the selected step exposes its exact authored payload");
        assertEquals(
                "root-step",
                NodePathEditor.getOrNull(
                        processRootBody,
                        "/0/payload/amount")
                        .getValue());
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                "/contracts/rootOperation/steps"));
        assertTrue(hasMetadata(
                        split.metadata(),
                        CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                        "/child/contracts/childWorkflow/steps"));
    }

    @Test
    void shouldServeInlineHeadersWithoutChangingCanonicalStoredFragments() {
        // Given
        Fixture fixture = fixture();
        Node exactContract =
                NodePathEditor.getOrNull(
                        fixture.root,
                        "/contracts/rootOperation");
        String contractBlueId =
                BlueIdCalculator.calculateBlueId(
                        exactContract);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);
        Node stored =
                split.fragments().get(
                        contractBlueId);
        Node processHeader =
                fetchOne(
                        split.provider(),
                        contractBlueId);
        Node processContracts =
                fetchOne(
                        split.provider(),
                        BlueIdCalculator.calculateBlueId(
                                fixture.root.getContracts()));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        fixture.root);
        Node storedRoot =
                split.fragments().get(
                        rootBlueId);
        Node processRoot =
                fetchOne(
                        split.provider(),
                        rootBlueId);
        Node processChild =
                fetchOne(
                        split.provider(),
                        fixture.childBlueId);

        // Then
        assertEquals(
                CoordinationDocumentSplitter
                        .PROCESS_HEADER_VIEW_PROFILE_ID,
                split.processHeaderViewProfileIdentity());
        assertTrue(
                NodePathEditor.getOrNull(
                        stored, "/channel")
                        .isReferenceOnly(),
                "the immutable storage inventory remains canonical");
        assertFalse(
                NodePathEditor.getOrNull(
                        processHeader, "/channel")
                        .isReferenceOnly(),
                "PROCESS receives the exact immutable dispatch header");
        assertEquals(
                "timeline",
                NodePathEditor.getOrNull(
                        processHeader, "/channel")
                        .getValue());
        assertTrue(
                NodePathEditor.getOrNull(
                        processHeader, "/steps")
                        .isReferenceOnly(),
                "the registered executable body remains cold");
        assertFalse(
                NodePathEditor.getOrNull(
                        processContracts,
                        "/rootOperation")
                        .isReferenceOnly(),
                "the PROCESS contracts-map view exposes a registered header");
        assertTrue(
                NodePathEditor.getOrNull(
                        processContracts,
                        "/rootOperation/steps")
                        .isReferenceOnly(),
                "an inlined registered header still leaves its body cold");
        assertTrue(
                NodePathEditor.getOrNull(
                        processContracts,
                        "/plainOperation")
                        .isReferenceOnly(),
                "the PROCESS contracts-map view leaves unregistered "
                        + "contracts cold");
        assertTrue(
                storedRoot.getContracts()
                        .isReferenceOnly(),
                "the canonical stored Root keeps its contracts map shallow");
        assertFalse(
                processRoot.getContracts()
                        .isReferenceOnly(),
                "the PROCESS Root view exposes its immutable contracts map");
        Node rootEmbeddedPath =
                NodePathEditor.getOrNull(
                        processRoot,
                        "/contracts/embedded/paths/0");
        Node childEmbeddedPath =
                NodePathEditor.getOrNull(
                        processChild,
                        "/contracts/embedded/paths/0");
        assertNotNull(
                rootEmbeddedPath,
                UncheckedObjectMapper.JSON_MAPPER
                        .valueToTree(processRoot)
                        .toString());
        assertNotNull(
                childEmbeddedPath,
                UncheckedObjectMapper.JSON_MAPPER
                        .valueToTree(processChild)
                        .toString());
        assertEquals(
                "/child",
                rootEmbeddedPath.getValue());
        assertEquals(
                "/grandchild",
                childEmbeddedPath.getValue());
        assertTrue(
                NodePathEditor.getOrNull(
                        processRoot,
                        "/contracts/rootOperation/steps")
                        .isReferenceOnly(),
                "the PROCESS scope view does not warm a selected body");
        assertTrue(
                NodePathEditor.getOrNull(
                        processChild,
                        "/contracts/childWorkflow/steps")
                        .isReferenceOnly(),
                "the PROCESS child view does not warm a reactive body");
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                split.provider()
                        .fetchResultByBlueId(
                                SequentialWorkflow.blueId())
                        .outcome(),
                "the PROCESS header view does not expose unrelated content");
    }

    @Test
    void shouldLeaveUnregisteredAndReferencedBodiesUnclaimed() {
        // Given
        Fixture fixture = fixture();

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);

        // Then
        Node reconstructed =
                split.reconstruct();
        Node referencedBody =
                NodePathEditor.getOrNull(
                        reconstructed,
                        "/contracts/referencedOperation/steps");
        assertTrue(referencedBody.isReferenceOnly());
        assertEquals(
                fixture.referencedBodyBlueId,
                referencedBody.getBlueId());
        assertFalse(
                split.fragments().containsKey(
                        fixture.referencedBodyBlueId),
                "an already-referenced body is not claimed as local content");

        Node unregisteredSteps =
                NodePathEditor.getOrNull(
                        reconstructed,
                        "/contracts/plainOperation/steps");
        assertNotNull(unregisteredSteps);
        assertFalse(
                unregisteredSteps.isReferenceOnly(),
                "a steps-shaped field is not executable without exact registry metadata");
    }

    @Test
    void shouldDeduplicateIdenticalExecutableBodyContent() {
        // Given
        Fixture fixture = fixture();

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);

        // Then
        assertTrue(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY,
                "/contracts/rootOperation/steps"));
        assertTrue(hasEdge(
                split.edgeOccurrences(),
                CoordinationDocumentSplitter.EdgeKind
                        .EXECUTABLE_BODY,
                "/contracts/chatOperation/steps"));
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                "/contracts/chatOperation/steps"));
        assertEquals(
                2,
                metadataCount(
                        split.metadata(),
                        CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                        fixture.rootBodyBlueId),
                "both registered handlers retain occurrence metadata");
        assertEquals(
                1,
                fragmentKeyCount(
                        split.fragments(),
                        fixture.rootBodyBlueId),
                "identical executable body content is stored once");
    }

    @Test
    void shouldReconstructExactDocumentAndDefensivelyExposeFragments() {
        // Given
        Fixture fixture = fixture();

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(fixture.root);
        Node reconstructed =
                reconstructAvailable(
                        split.pureReference(),
                        split.provider());

        // Then
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        split.originalRoot()),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        reconstructed),
                "recursively materializing every local fragment reconstructs the document");
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(
                        reconstructed));

        Map<String, Node> defensive =
                split.fragments();
        defensive.get(split.rootBlueId())
                .properties("tampered", scalar("yes"));
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(
                        fetchOne(
                                split.provider(),
                                split.rootBlueId())));
    }

    @Test
    void shouldUseExactDirectFragmentsWhenSplittingEvents() {
        // Given
        Node message = new Node()
                .properties(
                        "operation", scalar("increment"),
                        "channel", scalar("bob"),
                        "payload", new Node().properties(
                                "amount", scalar(3L)));
        Node event = new Node()
                .properties(
                        "timeline", scalar("alice"),
                        "actor", scalar("alice"),
                        "message", message);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitEvent(event);

        // Then
        assertEquals(
                BlueIdCalculator.calculateBlueId(event),
                split.rootBlueId());
        Node fragmented =
                split.fragmentedRoot();
        Node messageReference =
                NodePathEditor.getOrNull(
                        fragmented, "/message");
        assertNotNull(messageReference);
        assertTrue(messageReference.isReferenceOnly());
        assertEquals(
                BlueIdCalculator.calculateBlueId(message),
                messageReference.getBlueId());
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(fragmented));
        assertEquals(
                NodeProviderOutcome.FOUND,
                split.provider()
                        .fetchResultByBlueId(
                                messageReference.getBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                split.provider()
                        .fetchResultByBlueId(
                                SequentialWorkflow.blueId())
                        .outcome());
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EVENT_ROOT,
                "/"));
        Node reconstructed =
                reconstructAvailable(
                        split.pureReference(),
                        split.provider());
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        split.originalRoot()),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        reconstructed));
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(
                        reconstructed));
    }

    @Test
    void shouldRetainExternalCyclicEventTypeAsOpaqueEdge() {
        // Given
        String cyclicMemberBlueId =
                "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";
        Node operationRequest = new Node()
                .type(reference(
                        OperationRequest.blueId()))
                .properties(
                        "operation", scalar("increment"),
                        "channel", scalar("bob"),
                        "request", new Node().properties(
                                "amount", scalar(3L)));
        Node timelineEntry = new Node()
                .type(reference(
                        cyclicMemberBlueId))
                .properties(
                        "timeline", scalar("alice"),
                        "actor", scalar("alice"),
                        "message", operationRequest);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitEvent(timelineEntry);

        // Then
        assertEquals(
                cyclicMemberBlueId,
                split.fragmentedRoot()
                        .getType()
                        .getBlueId());
        Node messageReference =
                NodePathEditor.getOrNull(
                        split.fragmentedRoot(),
                        "/message");
        assertNotNull(messageReference);
        assertTrue(messageReference.isReferenceOnly());
        Node messageFragment =
                fetchOne(
                        split.provider(),
                        messageReference.getBlueId());
        assertEquals(
                OperationRequest.blueId(),
                messageFragment.getType().getBlueId());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                split.provider()
                        .fetchResultByBlueId(
                                cyclicMemberBlueId)
                        .outcome(),
                "external cyclic type content is never claimed as a local fragment");
        for (String blueId
                : split.fragments().keySet()) {
            assertFalse(
                    blueId.contains("#"),
                    "only ordinary exact local fragment identities are retained");
        }

        Node reconstructed =
                reconstructAvailable(
                        split.pureReference(),
                        split.provider());
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        timelineEntry),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        reconstructed));
        assertEquals(
                split.rootBlueId(),
                BlueIdCalculator.calculateBlueId(
                        reconstructed));
    }

    @Test
    void shouldOpenPureReferenceRootAndInheritedScopeWithLocalProvider() {
        // Given
        Node inheritedEmbedded = new Node()
                .type(reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(
                                scalar("/child")));
        Node rootType = new Node()
                .name("Inherited splitter Root")
                .contracts(
                        new Node().properties(
                                "embedded",
                                inheritedEmbedded));
        String rootTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        rootType);
        Node child = new Node().properties(
                "payload", scalar("present"));
        String childBlueId =
                BlueIdCalculator.calculateBlueId(
                        child);
        Node document = new Node()
                .type(reference(rootTypeBlueId))
                .properties(
                        "child",
                        reference(childBlueId));
        String documentBlueId =
                BlueIdCalculator.calculateBlueId(
                        document);
        Map<String, Node> exactContent =
                new java.util.LinkedHashMap<>();
        exactContent.put(
                rootTypeBlueId,
                rootType);
        exactContent.put(
                childBlueId,
                child);
        exactContent.put(
                documentBlueId,
                document);
        NodeProvider localProvider = blueId -> {
            Node retained =
                    exactContent.get(blueId);
            return retained != null
                    ? Collections.singletonList(
                            retained.clone())
                    : null;
        };

        try (Blue blue =
                     new Blue(
                             localProvider)) {
            // When
            IllegalStateException missingProvider =
                    assertThrows(
                            IllegalStateException.class,
                            () -> new CoordinationDocumentSplitter(
                                    blue.getDocumentProcessor())
                                    .splitDocument(
                                            reference(
                                                    documentBlueId)));
            CoordinationDocumentSplitter.SplitGraph split =
                    new CoordinationDocumentSplitter(
                            blue.getDocumentProcessor(),
                            localProvider)
                            .splitDocument(
                                    reference(
                                            documentBlueId));

            // Then
            assertTrue(
                    missingProvider.getMessage().contains(
                            "exact local NodeProvider"));
            Node childReference =
                    NodePathEditor.getOrNull(
                            split.fragmentedRoot(),
                            "/child");
            assertNotNull(childReference);
            assertTrue(childReference.isReferenceOnly());
            assertEquals(
                    childBlueId,
                    childReference.getBlueId());
            assertEquals(
                    documentBlueId,
                    split.rootBlueId());
            assertEquals(
                    childBlueId,
                    BlueIdCalculator.calculateBlueId(
                            fetchOne(
                                    split.provider(),
                                    childBlueId)));
            assertTrue(hasMetadata(
                    split.metadata(),
                    CoordinationDocumentSplitter.FragmentKind
                            .EMBEDDED_ROOT,
                    "/child"));
        }
    }

    @Test
    void shouldLeaveReferencedNestedComputeDefinitionUndemanded() {
        // Given
        Node largeDefinition =
                new Node().properties(
                        "source",
                        scalar(
                                repeat(
                                        'x',
                                        64 * 1024)));
        String definitionBlueId =
                BlueIdCalculator.calculateBlueId(
                        largeDefinition);
        Node laterCompute =
                new Node()
                        .type(reference(
                                Compute.blueId()))
                        .properties(
                                "definition",
                                reference(
                                        definitionBlueId));
        Node steps =
                new Node().items(
                        new Node().properties(
                                "label",
                                scalar("first")),
                        laterCompute);
        Node root =
                new Node().contracts(
                        new Node().properties(
                                "workflow",
                                workflow(
                                        SequentialWorkflowOperation
                                                .blueId(),
                                        steps)));
        int[] localProviderCalls = {0};
        NodeProvider localProvider = blueId -> {
            localProviderCalls[0]++;
            return definitionBlueId.equals(blueId)
                    ? Collections.singletonList(
                            largeDefinition.clone())
                    : null;
        };
        DocumentProcessor catalogProcessor =
                CoordinationFragmentationCatalogHarness
                        .processor(
                                root,
                                Collections.singletonMap(
                                        SequentialWorkflowOperation
                                                .blueId(),
                                        Collections.singletonList(
                                                "steps")));
        try {
            // When
            CoordinationDocumentSplitter.SplitGraph split =
                    new CoordinationDocumentSplitter(
                            catalogProcessor,
                            localProvider)
                            .splitDocument(root);

            // Then
            assertEquals(
                    0,
                    localProviderCalls[0],
                    "splitting must not open an unreachable later Compute definition");
            CoordinationDocumentSplitter.EdgeOccurrence
                    bodyEdge = edgeAt(
                    split.edgeOccurrences(),
                    CoordinationDocumentSplitter.EdgeKind
                            .EXECUTABLE_BODY,
                    "/contracts/workflow/steps");
            Node retainedSteps =
                    Objects.requireNonNull(
                            split.fragments().get(
                                    bodyEdge.childBlueId()),
                            "canonical retained steps")
                            .clone();
            Node laterStepReference =
                    retainedSteps.getItems().get(1);
            assertTrue(
                    laterStepReference.isReferenceOnly());
            Node retainedLaterStep =
                    fetchOne(
                            split.provider(),
                            laterStepReference.getBlueId());
            Node retainedDefinition =
                    NodePathEditor.getOrNull(
                            retainedLaterStep,
                            "/definition");
            assertNotNull(retainedDefinition);
            assertTrue(
                    retainedDefinition.isReferenceOnly());
            assertEquals(
                    definitionBlueId,
                    retainedDefinition.getBlueId());
            assertEquals(
                    0,
                    localProviderCalls[0],
                    "reading the selected direct body still leaves its nested "
                            + "Compute definition lazy");
        } finally {
            catalogProcessor.close();
        }
    }

    @Test
    void shouldPreparePureReferencesWithLazyVerifiedProvider() {
        // Given
        PreparationFixture fixture =
                preparationFixture();
        int[] providerCalls = {0};
        NodeProvider counted = blueId -> {
            providerCalls[0]++;
            return fixture.combined.fetchByBlueId(
                    blueId);
        };

        // When
        CoordinationDocumentSplitter.PreparedProcessingInput prepared =
                splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        fixture.evidence,
                        counted);

        // Then
        assertTrue(prepared.document().isReferenceOnly());
        assertTrue(prepared.event().isReferenceOnly());
        assertEquals(
                0,
                providerCalls[0],
                "preparation must not consume cold provider fragments");
        assertEquals(
                fixture.document.rootBlueId(),
                prepared.document().getBlueId());
        assertEquals(
                fixture.event.rootBlueId(),
                prepared.event().getBlueId());
        assertSame(
                fixture.evidence,
                prepared.evidence());
        assertEquals(
                NodeProviderOutcome.FOUND,
                prepared.provider()
                        .fetchResultByBlueId(
                                fixture.document
                                        .rootBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.FOUND,
                prepared.provider()
                        .fetchResultByBlueId(
                                fixture.event
                                        .rootBlueId())
                        .outcome());
        assertEquals(2, providerCalls[0]);

        prepared.document().blueId(
                SequentialWorkflow.blueId());
        assertEquals(
                fixture.document.rootBlueId(),
                prepared.document().getBlueId(),
                "prepared semantic inputs are defensive copies");
    }

    @Test
    void shouldRejectPreparedInputBoundToDifferentEventEvidence() {
        // Given
        PreparationFixture fixture =
                preparationFixture();
        VerifiedExecutionEvidence wrongEvent =
                evidence(
                        fixture.document.rootBlueId(),
                        fixture.source.childBlueId);

        // When
        IllegalArgumentException failure =
                assertThrows(
                IllegalArgumentException.class,
                () -> splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        wrongEvent,
                        fixture.combined));

        // Then
        assertNotNull(failure);
    }

    @Test
    void shouldPreserveMissingAndInvalidFragmentProviderOutcomes() {
        // Given
        PreparationFixture fixture =
                preparationFixture();
        NodeProvider invalidRoot = blueId ->
                fixture.document.rootBlueId()
                        .equals(blueId)
                        ? Collections.singletonList(
                        scalar("wrong-root"))
                        : fixture.combined
                        .fetchByBlueId(blueId);
        NodeProvider invalidEvent = blueId ->
                fixture.event.rootBlueId()
                        .equals(blueId)
                        ? Collections.singletonList(
                        scalar("wrong-event"))
                        : fixture.combined
                        .fetchByBlueId(blueId);

        // When
        CoordinationDocumentSplitter.PreparedProcessingInput
                missingEvent =
                splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        fixture.evidence,
                        fixture.document.provider());
        CoordinationDocumentSplitter.PreparedProcessingInput
                missingRoot =
                splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        fixture.evidence,
                        fixture.event.provider());
        CoordinationDocumentSplitter.PreparedProcessingInput
                invalidRootInput =
                splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        fixture.evidence,
                        invalidRoot);
        CoordinationDocumentSplitter.PreparedProcessingInput
                invalidEventInput =
                splitter.prepareForProcessing(
                        fixture.document.rootBlueId(),
                        fixture.event.rootBlueId(),
                        fixture.evidence,
                        invalidEvent);

        // Then
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                missingEvent.provider()
                        .fetchResultByBlueId(
                                fixture.event.rootBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                missingRoot.provider()
                        .fetchResultByBlueId(
                                fixture.document
                                        .rootBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                invalidRootInput.provider()
                        .fetchResultByBlueId(
                                fixture.document
                                        .rootBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                invalidEventInput.provider()
                        .fetchResultByBlueId(
                                fixture.event.rootBlueId())
                        .outcome());
    }

    @Test
    void shouldFailBeforeProducingFragmentsForMalformedEmbeddedPaths() {
        // Given
        Node root = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded("/")));

        // When
        IllegalArgumentException invalid =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> CoordinationDocumentSplitterTestSupport
                                .splitDocument(root));

        // Then
        assertTrue(
                invalid.getMessage().contains(
                        "cannot embed its declaring scope"));
    }

    @Test
    void shouldCutOverlappingEmbeddedPathsAtNearestDeclaredAncestor() {
        // Given
        Node grandchild = new Node()
                .properties(
                        "state",
                        scalar("grandchild"));
        Node child = new Node()
                .properties(
                        "state",
                        scalar("child"),
                        "grandchild",
                        grandchild);
        Node root = new Node()
                .properties(
                        "state",
                        scalar("root"),
                        "child",
                        child)
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded(
                                "/child",
                                "/child/grandchild")));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(root);
        String childBlueId =
                BlueIdCalculator.calculateBlueId(child);
        String grandchildBlueId =
                BlueIdCalculator.calculateBlueId(
                        grandchild);

        // When
        CoordinationDocumentSplitter.SplitGraph split =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(root);

        // Then
        Node rootFragment =
                Objects.requireNonNull(
                        split.fragments().get(
                                rootBlueId),
                        "canonical Root fragment")
                        .clone();
        Node childReference =
                NodePathEditor.getOrNull(
                        rootFragment,
                        "/child");
        assertNotNull(childReference);
        assertTrue(
                childReference.isReferenceOnly(),
                "the ancestor cut remains a pure reference");
        assertEquals(
                childBlueId,
                childReference.getBlueId());

        Node childFragment =
                Objects.requireNonNull(
                        split.fragments().get(
                                childBlueId),
                        "canonical child fragment")
                        .clone();
        Node grandchildReference =
                NodePathEditor.getOrNull(
                        childFragment,
                        "/grandchild");
        assertNotNull(grandchildReference);
        assertTrue(
                grandchildReference.isReferenceOnly(),
                "the descendant is cut inside its nearest declared ancestor fragment");
        assertEquals(
                grandchildBlueId,
                grandchildReference.getBlueId());
        assertEquals(
                childBlueId,
                BlueIdCalculator.calculateBlueId(
                        childFragment));

        Node grandchildFragment =
                fetchOne(
                        split.provider(),
                        grandchildBlueId);
        assertEquals(
                grandchildBlueId,
                BlueIdCalculator.calculateBlueId(
                        grandchildFragment));
        assertTrue(
                split.fragments().containsKey(
                        rootBlueId));
        assertTrue(
                split.fragments().containsKey(
                        childBlueId));
        assertTrue(
                split.fragments().containsKey(
                        grandchildBlueId));

        Node reconstructed =
                reconstructAvailable(
                        split.pureReference(),
                        split.provider());
        assertEquals(
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        root),
                UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                        reconstructed));
        assertEquals(
                rootBlueId,
                BlueIdCalculator.calculateBlueId(
                        reconstructed));
    }

    private static Fixture fixture() {
        Node grandchildBody =
                body("grandchild-step");
        Node grandchild = new Node()
                .properties(
                        "state", scalar("grandchild"))
                .contracts(new Node().properties(
                        "grandchildWorkflow",
                        workflow(
                                SequentialWorkflow.blueId(),
                                grandchildBody)));

        Node childBody =
                body("child-step");
        Node child = new Node()
                .properties(
                        "state", scalar("child"),
                        "grandchild", grandchild)
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded("/grandchild"),
                        "childWorkflow",
                        workflow(
                                SequentialWorkflow.blueId(),
                                childBody)));

        Node rootBody =
                body("root-step");
        Node rootOperation = workflow(
                SequentialWorkflowOperation.blueId(),
                rootBody);
        Node chatOperation = workflow(
                ChatWorkflowOperation.blueId(),
                rootBody.clone());
        String referencedBodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        body("already-external"));
        Node referencedOperation = workflow(
                SequentialWorkflowOperation.blueId(),
                reference(referencedBodyBlueId));
        Node plainSteps =
                body("must-remain-inline");
        Node plainOperation = workflow(
                Operation.blueId(),
                plainSteps);
        Node sibling = new Node()
                .properties(
                        "largeData",
                        scalar(
                                "this application subtree is not a Coordination scope"));
        Node rootContracts = new Node()
                .properties(
                        "embedded",
                        processEmbedded("/child"),
                        "rootOperation",
                        rootOperation,
                        "chatOperation",
                        chatOperation,
                        "referencedOperation",
                        referencedOperation)
                .properties(
                        "plainOperation",
                        plainOperation);
        Node root = new Node()
                .properties(
                        "state", scalar("root"),
                        "child", child,
                        "sibling", sibling)
                .contracts(rootContracts);
        return new Fixture(
                root,
                BlueIdCalculator.calculateBlueId(
                        child),
                BlueIdCalculator.calculateBlueId(
                        grandchild),
                BlueIdCalculator.calculateBlueId(
                        rootBody),
                referencedBodyBlueId);
    }

    private static Node processEmbedded(
            String... paths) {
        List<Node> pathNodes =
                new ArrayList<>();
        for (String path : paths) {
            pathNodes.add(scalar(path));
        }
        return new Node()
                .type(reference(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "paths",
                        new Node().items(pathNodes));
    }

    private static Node workflow(
            String typeBlueId,
            Node steps) {
        return new Node()
                .type(reference(typeBlueId))
                .properties(
                        "channel", scalar("timeline"),
                        "steps", steps);
    }

    private static Node body(
            String label) {
        return new Node()
                .items(Collections.singletonList(
                        new Node().properties(
                                "label", scalar(label),
                                "payload",
                                new Node().properties(
                                        "amount",
                                        scalar(label)))));
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
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static Node fetchOne(
            NodeProvider provider,
            String blueId) {
        NodeProviderResult result =
                provider.fetchResultByBlueId(blueId);
        assertEquals(
                NodeProviderOutcome.FOUND,
                result.outcome());
        assertEquals(1, result.nodes().size());
        return result.nodes().get(0);
    }

    private static Node reconstructAvailable(
            Node root,
            NodeProvider provider) {
        return NodeTransformer.transform(
                root,
                node -> {
                    if (!node.isReferenceOnly()) {
                        return node;
                    }
                    NodeProviderResult result =
                            provider.fetchResultByBlueId(
                                    node.getBlueId());
                    if (result.outcome()
                            == NodeProviderOutcome.NOT_FOUND) {
                        return node;
                    }
                    assertEquals(
                            NodeProviderOutcome.FOUND,
                            result.outcome());
                    assertEquals(1, result.nodes().size());
                    return result.nodes().get(0);
                });
    }

    private static boolean hasMetadata(
            List<CoordinationDocumentSplitter.FragmentMetadata> metadata,
            CoordinationDocumentSplitter.FragmentKind kind,
            String pointer) {
        for (CoordinationDocumentSplitter.FragmentMetadata entry
                : metadata) {
            if (entry.kind() == kind
                    && pointer.equals(entry.pointer())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEdge(
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges,
            CoordinationDocumentSplitter.EdgeKind kind,
            String pointer) {
        for (CoordinationDocumentSplitter.EdgeOccurrence edge
                : edges) {
            if (edge.edgeKind() == kind
                    && pointer.equals(
                    edge.absolutePointer())) {
                return true;
            }
        }
        return false;
    }

    private static CoordinationDocumentSplitter.EdgeOccurrence
    edgeAt(
            List<CoordinationDocumentSplitter.EdgeOccurrence> edges,
            CoordinationDocumentSplitter.EdgeKind kind,
            String pointer) {
        for (CoordinationDocumentSplitter.EdgeOccurrence edge
                : edges) {
            if (edge.edgeKind() == kind
                    && pointer.equals(
                    edge.absolutePointer())) {
                return edge;
            }
        }
        throw new AssertionError(
                "No "
                        + kind
                        + " edge at "
                        + pointer);
    }

    private static int metadataCount(
            List<CoordinationDocumentSplitter.FragmentMetadata> metadata,
            CoordinationDocumentSplitter.FragmentKind kind,
            String blueId) {
        int count = 0;
        for (CoordinationDocumentSplitter.FragmentMetadata entry
                : metadata) {
            if (entry.kind() == kind
                    && blueId.equals(entry.blueId())) {
                count++;
            }
        }
        return count;
    }

    private static int fragmentKeyCount(
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

    private PreparationFixture preparationFixture() {
        Fixture source = fixture();
        Node event = new Node()
                .properties(
                        "timeline", scalar("alice"),
                        "message", scalar("hello"));
        CoordinationDocumentSplitter.SplitGraph document =
                CoordinationDocumentSplitterTestSupport
                        .splitDocument(source.root);
        CoordinationDocumentSplitter.SplitGraph splitEvent =
                splitter.splitEvent(event);
        NodeProvider combined =
                new SequentialNodeProvider(
                        document.provider(),
                        splitEvent.provider());
        return new PreparationFixture(
                source,
                document,
                splitEvent,
                combined,
                evidence(
                        document.rootBlueId(),
                        splitEvent.rootBlueId()));
    }

    private static VerifiedExecutionEvidence evidence(
            String rootBlueId,
            String eventBlueId) {
        return VerifiedExecutionEvidence
                .builder(rootBlueId, eventBlueId)
                .revisions(7L, 7L)
                .runtimeRegistryIdentity(
                        "coordination-splitter-test")
                .eventOrderKey(
                        ExternalOrderKey.of(
                                Arrays.asList(
                                        12L,
                                        "entry")))
                .build();
    }

    private static final class PreparationFixture {
        private final Fixture source;
        private final CoordinationDocumentSplitter.SplitGraph
                document;
        private final CoordinationDocumentSplitter.SplitGraph
                event;
        private final NodeProvider combined;
        private final VerifiedExecutionEvidence evidence;

        private PreparationFixture(
                Fixture source,
                CoordinationDocumentSplitter.SplitGraph document,
                CoordinationDocumentSplitter.SplitGraph event,
                NodeProvider combined,
                VerifiedExecutionEvidence evidence) {
            this.source = source;
            this.document = document;
            this.event = event;
            this.combined = combined;
            this.evidence = evidence;
        }
    }

    private static final class Fixture {

        private final Node root;
        private final String childBlueId;
        private final String grandchildBlueId;
        private final String rootBodyBlueId;
        private final String referencedBodyBlueId;

        private Fixture(
                Node root,
                String childBlueId,
                String grandchildBlueId,
                String rootBodyBlueId,
                String referencedBodyBlueId) {
            this.root = root;
            this.childBlueId = childBlueId;
            this.grandchildBlueId =
                    grandchildBlueId;
            this.rootBodyBlueId =
                    rootBodyBlueId;
            this.referencedBodyBlueId =
                    referencedBodyBlueId;
        }
    }
}
