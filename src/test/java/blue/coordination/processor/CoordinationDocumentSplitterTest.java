package blue.coordination.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
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
import blue.repo.coordination.Operation;
import blue.repo.coordination.OperationRequest;
import blue.repo.coordination.SequentialWorkflow;
import blue.repo.coordination.SequentialWorkflowOperation;
import blue.repo.coordination.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinationDocumentSplitterTest {

    private final CoordinationDocumentSplitter splitter =
            new CoordinationDocumentSplitter();

    @Test
    void documentSplittingCutsEmbeddedRootsAndRegisteredBodiesOnly() {
        Fixture fixture = fixture();
        String exactRootBlueId =
                BlueIdCalculator.calculateBlueId(fixture.root);

        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitDocument(fixture.root);

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
        assertFalse(
                sibling.isReferenceOnly(),
                "an unrelated application sibling remains inline");

        Node rootSelectedBody =
                NodePathEditor.getOrNull(
                        fragmentedRoot,
                        "/contracts/rootOperation/steps");
        assertTrue(rootSelectedBody.isReferenceOnly());
        assertEquals(
                fixture.rootBodyBlueId,
                rootSelectedBody.getBlueId());

        Node chatSelectedBody =
                NodePathEditor.getOrNull(
                        fragmentedRoot,
                        "/contracts/chatOperation/steps");
        assertTrue(chatSelectedBody.isReferenceOnly());
        assertEquals(
                fixture.rootBodyBlueId,
                chatSelectedBody.getBlueId(),
                "identical bodies share one content identity");

        Node referencedBody =
                NodePathEditor.getOrNull(
                        fragmentedRoot,
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
                        fragmentedRoot,
                        "/contracts/plainOperation/steps");
        assertNotNull(unregisteredSteps);
        assertFalse(
                unregisteredSteps.isReferenceOnly(),
                "a steps-shaped field is not executable without exact registry metadata");

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
        assertTrue(
                NodePathEditor.getOrNull(
                        childFragment,
                        "/contracts/childWorkflow/steps")
                        .isReferenceOnly());

        Node rootBody =
                fetchOne(
                        split.provider(),
                        fixture.rootBodyBlueId);
        assertEquals(
                fixture.rootBodyBlueId,
                BlueIdCalculator.calculateBlueId(
                        rootBody));
        assertFalse(
                rootBody.getItems().get(0)
                        .isReferenceOnly(),
                "an executable body is retained as one complete coarse fragment");

        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EMBEDDED_ROOT,
                "/child"));
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EMBEDDED_ROOT,
                "/child/grandchild"));
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                "/contracts/rootOperation/steps"));
        assertTrue(hasMetadata(
                split.metadata(),
                CoordinationDocumentSplitter.FragmentKind.EXECUTABLE_BODY,
                "/child/contracts/childWorkflow/steps"));
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

        Node reconstructed =
                reconstructAvailable(
                        split.pureReference(),
                        split.provider());
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
    void eventSplittingUsesExactDirectFragments() {
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

        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitEvent(event);

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
    void typedTimelineEntryRetainsExternalCyclicMemberType() {
        assertTrue(
                TimelineEntry.blueId().contains("#"),
                "the published Timeline Entry type is a cyclic-set member");
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
                        TimelineEntry.blueId()))
                .properties(
                        "timeline", scalar("alice"),
                        "actor", scalar("alice"),
                        "message", operationRequest);

        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitEvent(timelineEntry);

        assertEquals(
                TimelineEntry.blueId(),
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
                                TimelineEntry.blueId())
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
    void preparedInputContainsOnlyTwoPureReferencesAndLazyVerifiedProvider() {
        Fixture fixture = fixture();
        Node event = new Node()
                .properties(
                        "timeline", scalar("alice"),
                        "message", scalar("hello"));
        CoordinationDocumentSplitter.SplitGraph document =
                splitter.splitDocument(fixture.root);
        CoordinationDocumentSplitter.SplitGraph splitEvent =
                splitter.splitEvent(event);
        NodeProvider combined =
                new SequentialNodeProvider(
                        document.provider(),
                        splitEvent.provider());
        int[] providerCalls = {0};
        NodeProvider counted = blueId -> {
            providerCalls[0]++;
            return combined.fetchByBlueId(blueId);
        };
        VerifiedExecutionEvidence evidence =
                evidence(
                        document.rootBlueId(),
                        splitEvent.rootBlueId());

        CoordinationDocumentSplitter.PreparedProcessingInput prepared =
                splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        evidence,
                        counted);

        assertTrue(prepared.document().isReferenceOnly());
        assertTrue(prepared.event().isReferenceOnly());
        assertEquals(
                0,
                providerCalls[0],
                "preparation must not consume cold provider fragments");
        assertEquals(
                document.rootBlueId(),
                prepared.document().getBlueId());
        assertEquals(
                splitEvent.rootBlueId(),
                prepared.event().getBlueId());
        assertSame(evidence, prepared.evidence());
        assertEquals(
                NodeProviderOutcome.FOUND,
                prepared.provider()
                        .fetchResultByBlueId(
                                document.rootBlueId())
                        .outcome());
        assertEquals(
                NodeProviderOutcome.FOUND,
                prepared.provider()
                        .fetchResultByBlueId(
                                splitEvent.rootBlueId())
                        .outcome());
        assertEquals(2, providerCalls[0]);

        prepared.document().blueId(
                SequentialWorkflow.blueId());
        assertEquals(
                document.rootBlueId(),
                prepared.document().getBlueId(),
                "prepared semantic inputs are defensive copies");

        VerifiedExecutionEvidence wrongEvent =
                evidence(
                        document.rootBlueId(),
                        fixture.childBlueId);
        assertThrows(
                IllegalArgumentException.class,
                () -> splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        wrongEvent,
                        combined));
        CoordinationDocumentSplitter.PreparedProcessingInput
                missingEvent =
                splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        evidence,
                        document.provider());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                missingEvent.provider()
                        .fetchResultByBlueId(
                                splitEvent.rootBlueId())
                        .outcome());

        CoordinationDocumentSplitter.PreparedProcessingInput
                missingRoot =
                splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        evidence,
                        splitEvent.provider());
        assertEquals(
                NodeProviderOutcome.NOT_FOUND,
                missingRoot.provider()
                        .fetchResultByBlueId(
                                document.rootBlueId())
                        .outcome());

        NodeProvider invalidRoot = blueId ->
                document.rootBlueId().equals(blueId)
                        ? Collections.singletonList(
                                scalar("wrong-root"))
                        : combined.fetchByBlueId(blueId);
        CoordinationDocumentSplitter.PreparedProcessingInput
                invalidRootInput =
                splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        evidence,
                        invalidRoot);
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                invalidRootInput.provider()
                        .fetchResultByBlueId(
                                document.rootBlueId())
                        .outcome());

        NodeProvider invalidEvent = blueId ->
                splitEvent.rootBlueId().equals(blueId)
                        ? Collections.singletonList(
                                scalar("wrong-event"))
                        : combined.fetchByBlueId(blueId);
        CoordinationDocumentSplitter.PreparedProcessingInput
                invalidEventInput =
                splitter.prepareForProcessing(
                        document.rootBlueId(),
                        splitEvent.rootBlueId(),
                        evidence,
                        invalidEvent);
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                invalidEventInput.provider()
                        .fetchResultByBlueId(
                                splitEvent.rootBlueId())
                        .outcome());
    }

    @Test
    void malformedEmbeddedPathsFailBeforeProducingFragments() {
        Node root = new Node()
                .contracts(new Node().properties(
                        "embedded",
                        processEmbedded("/")));

        IllegalArgumentException invalid =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> splitter.splitDocument(root));

        assertTrue(
                invalid.getMessage().contains(
                        "cannot embed its declaring scope"));
    }

    @Test
    void overlappingEmbeddedPathsCutAtNearestDeclaredAncestor() {
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

        CoordinationDocumentSplitter.SplitGraph split =
                splitter.splitDocument(root);

        Node rootFragment =
                fetchOne(
                        split.provider(),
                        rootBlueId);
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
                fetchOne(
                        split.provider(),
                        childBlueId);
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
        assertEquals(
                3,
                split.fragments().size(),
                "every declared Root is retained exactly once");

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
