package blue.coordination.processor.workflow;

import blue.bex.api.BexEngine;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.ExternalBlockerProbeAssertions;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingRuntime;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.repo.BlueRepository;
import blue.repo.coordination.ChatMessage;
import blue.repo.coordination.SequentialWorkflowStep;
import blue.repo.coordination.TerminateProcessing;
import blue.repo.coordination.UpdateDocument;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Differential coverage for the test-only legacy mutable Update Document lane. */
class FrozenUpdateDocumentDifferentialTest {
    private static final String TEXT_BLUE_ID =
            "GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC";

    @Test
    void shouldMatchLegacyLaneForOrderedStructuralTypedReferenceAndReentrantUpdates() {
        // Given
        DocumentFactory factory = new DocumentFactory() {
            @Override
            public Node build(BlueRepository repository) {
                return broadPatchDocument(repository);
            }
        };

        // When
        Outcome frozen = run(false, factory);
        Outcome legacy = run(true, factory);

        // Then
        boolean exactSelectedBodyLoss =
                frozen.status
                        == ProcessorStatus.RUNTIME_FATAL
                        && frozen.errorCategory
                        == ProcessorErrorCategory
                        .RuntimeExecutionFailure
                        && "Update Document patch value reference "
                        .concat(
                                "has no resolved selected-body value")
                        .equals(frozen.failureReason)
                        && "initial".equals(
                        frozen.document.getAsText(
                                "/status"))
                        && frozen.triggeredEventsJson
                        .isEmpty()
                        && legacy.status
                        == ProcessorStatus.SUCCESS
                        && legacy.failureReason == null
                        && metric(
                        frozen.metrics,
                        "frozenPatchesHandedToLanguage")
                        > 0L;
        ExternalBlockerProbeAssertions.classify(
                "bex-admitted-exact-value-materialization",
                "BEX admitted-exact canonical materialization defect:",
                exactSelectedBodyLoss,
                frozen.status == legacy.status
                        && frozen.status
                        == ProcessorStatus.SUCCESS,
                "frozenStatus=" + frozen.status
                        + ", frozenCategory="
                        + frozen.errorCategory
                        + ", frozenDiagnostic="
                        + frozen.failureReason
                        + ", frozenEvents="
                        + frozen.triggeredEventsJson
                        .size()
                        + ", frozenPatchHandoffs="
                        + metric(
                        frozen.metrics,
                        "frozenPatchesHandedToLanguage")
                        + ", legacyStatus="
                        + legacy.status
                        + ", legacyDiagnostic="
                        + legacy.failureReason);
        assertEquivalent(frozen, legacy);
        assertBroadPatchEffects(frozen);
        assertHandoffMetrics(frozen, legacy);
    }

    private static void assertBroadPatchEffects(Outcome frozen) {
        assertEquals("second", frozen.document.getAsText("/status"));
        assertEquals("child-after-parent", frozen.document.getAsText("/parent/child"));
        assertEquals("ZERO", frozen.document.getAsText("/rows/0"));
        assertEquals("inserted", frozen.document.getAsText("/rows/1"));
        assertEquals("one", frozen.document.getAsText("/rows/2"));
        assertNull(nodeAt(frozen.document, "/removeMe"));
        assertEquals(TEXT_BLUE_ID, frozen.document.getAsNode("/pureReference").getBlueId());
        assertEquals("typed text", frozen.document.get("/typed"));
        assertEquals(2, frozen.document.getAsNode("/generalized").getItems().size());
        assertEquals("embedded payload", frozen.document.getAsNode("/embeddedValue").getName());
        assertEquals("seen", frozen.document.getAsText("/observed"));
        assertFalse(frozen.triggeredEventsJson.isEmpty());
    }

    private static void assertHandoffMetrics(Outcome frozen, Outcome legacy) {
        assertTrue(metric(frozen.metrics, "frozenPatchesHandedToLanguage") > 0L);
        assertEquals(0L, metric(frozen.metrics, "mutablePatchesHandedToLanguage"));
        assertTrue(metric(legacy.metrics, "mutablePatchesHandedToLanguage") > 0L);
    }

    @Test
    void shouldMatchLegacyFailureAndCommittedPrefixWhenPatchNFails() {
        // Given
        DocumentFactory factory = new DocumentFactory() {
            @Override
            public Node build(BlueRepository repository) {
                return failureDocument(repository);
            }
        };

        // When
        Outcome frozen = run(false, factory);
        Outcome legacy = run(true, factory);

        // Then
        assertEquivalentFailure(frozen, legacy);
        assertAtomicRollback(frozen);
    }

    private static void assertAtomicRollback(Outcome frozen) {
        assertEquals(ProcessorStatus.RUNTIME_FATAL, frozen.status);
        assertNotNull(frozen.failureReason);
        assertTrue(frozen.failureReason.contains(
                "Path does not exist for remove: /patchNTarget"), frozen.failureReason);
        assertEquals("initial", frozen.document.getAsText("/status"));
        assertNull(nodeAt(frozen.document, "/secondPrefix"));
        assertEquals(
                "present during preview",
                frozen.document.getAsText("/patchNTarget"));
        assertNull(nodeAt(frozen.document, "/mustNotAppear"));
        assertTrue(frozen.triggeredEventsJson.isEmpty(),
                "atomic failure must expose no public event prefix");
        assertTrue(metric(frozen.metrics, "frozenPatchesHandedToLanguage") >= 4L,
                "the immutable plan crosses the Language boundary before its atomic apply fails");
    }

    @Test
    void shouldKeepPriorChangesAndSkipLaterPatchesAfterDeclarativeTermination() {
        // Given
        DocumentFactory factory = new DocumentFactory() {
            @Override
            public Node build(BlueRepository repository) {
                return terminationDocument(repository);
            }
        };

        // When
        Outcome frozen = run(false, factory);
        Outcome legacy = run(true, factory);

        // Then
        assertEquivalent(frozen, legacy);
        assertEquals("before termination", frozen.document.getAsText("/status"));
        assertNull(nodeAt(frozen.document, "/mustNotAppear"));
        assertNotNull(frozen.document.get("/contracts/terminated"));
        assertEquals(TerminateProcessing.blueId(),
                frozen.document.get("/contracts/terminated/cause"));
        assertEquals("finished intentionally",
                frozen.document.get("/contracts/terminated/reason"));
        assertEquals(1L, metric(frozen.metrics, "frozenPatchesHandedToLanguage"));
    }

    @Test
    void shouldMatchLegacyPointerResolutionInsideEmbeddedScope() {
        // Given
        DocumentFactory factory = new DocumentFactory() {
            @Override
            public Node build(BlueRepository repository) {
                return embeddedDocument(repository);
            }
        };

        // When
        Outcome frozen = run(false, factory);
        Outcome legacy = run(true, factory);

        // Then
        assertEquivalent(frozen, legacy);
        assertEquals(100, ((Number) frozen.document.get("/counter")).intValue());
        assertEquals(7, ((Number) frozen.document.get("/child/counter")).intValue());
    }

    @Test
    void shouldMatchLegacyExpandedReferenceLikeValueAtLanguageBoundary() {
        // Given
        BlueRepository repository = BlueRepository.latest();
        // Repository lookup returns a resolved view whose root combines blueId with expanded
        // content. Remove the reference marker to model the equivalent authored expansion;
        // FrozenJsonPatch must continue rejecting the ambiguous resolved representation.
        Node expanded = repository.nodeByBlueId(ChatMessage.blueId())
                .orElseThrow(() -> new AssertionError("Chat Message type missing"))
                .clone()
                .blueId(null);
        Node mutableDocument = new Node();
        Node frozenDocument = new Node();

        // When
        new DocumentProcessingRuntime(mutableDocument).applyPatches("/", Collections.singletonList(
                JsonPatch.add("/expanded", expanded.clone())));
        new DocumentProcessingRuntime(frozenDocument).applyFrozenPatches("/", Collections.singletonList(
                FrozenJsonPatch.add("/expanded", FrozenNode.fromNode(expanded))));

        Blue blue = CoordinationTestResources.configuredBlue(repository);
        try {
            // Then
            assertEquals(blue.calculateBlueId(mutableDocument), blue.calculateBlueId(frozenDocument));
            assertEquals(mutableDocument.getAsNode("/expanded").getName(),
                    frozenDocument.getAsNode("/expanded").getName());
        } finally {
            blue.close();
        }
    }

    private static Node broadPatchDocument(BlueRepository repository) {
        Map<String, Node> contracts = ownerContracts();
        contracts.put("allUpdates", documentUpdateChannel("/"));
        contracts.put("statusUpdates", documentUpdateChannel("/status"));
        contracts.put("observedUpdates", documentUpdateChannel("/observed"));
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep(
                        patch("add", "/added", new Node().value("added")),
                        patch("replace", "/status", new Node().value("first")),
                        patch("replace", "/status", new Node().value("second")),
                        patch("replace", "/parent", new Node()
                                .properties("child", new Node().value("from-parent"))
                                .properties("keep", new Node().value(false))),
                        patch("replace", "/parent/child", new Node().value("child-after-parent")),
                        patch("add", "/rows/1", new Node().value("inserted")),
                        patch("replace", "/rows/0", new Node().value("ZERO")),
                        patch("remove", "/rows/3", null),
                        patch("remove", "/removeMe", null),
                        patch("add", "/typed", new Node()
                                .type(new Node().blueId(TEXT_BLUE_ID))
                                .value("typed text")),
                        patch("add", "/generalized", new Node()
                                .type("List")
                                .itemType(new Node().blueId(TEXT_BLUE_ID))
                                .items(new Node().value("one"), new Node().value("two"))),
                        patch("add", "/pureReference", new Node().blueId(TEXT_BLUE_ID)),
                        patch("add", "/embeddedValue", new Node()
                                .name("embedded payload")
                                .properties("counter", new Node().value(1))))));
        contracts.put("reentrantWriter", directWorkflowMatching("statusUpdates",
                new Node().type("Document Update"),
                updateDocumentStep(patch("replace", "/observed", new Node().value("seen")))));
        contracts.put("reentrantObserver", directWorkflowMatching("observedUpdates",
                new Node().type("Document Update"),
                triggerEventStep("reentrant update observed")));
        return root(repository, contracts)
                .properties("status", new Node().value("initial"))
                .properties("observed", new Node().value("not yet"))
                .properties("removeMe", new Node().value("gone"))
                .properties("parent", new Node()
                        .properties("child", new Node().value("old"))
                        .properties("keep", new Node().value(true)))
                .properties("rows", new Node().items(
                        new Node().value("zero"),
                        new Node().value("one"),
                        new Node().value("two")));
    }

    private static Node failureDocument(BlueRepository repository) {
        Map<String, Node> contracts = ownerContracts();
        contracts.put("statusUpdates", documentUpdateChannel("/status"));
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep(
                        patch("replace", "/status", new Node().value("prefix-one")),
                        patch("add", "/secondPrefix", new Node().value("prefix-two")),
                        patch("remove", "/patchNTarget", null),
                        patch("add", "/mustNotAppear", new Node().value(true)))));
        // Patch 1 routes a Document Update that removes patch N's target. The
        // complete outer sequence therefore previews successfully, patch 1 and
        // patch 2 commit, then the runtime rebase makes patch N fail. This is
        // deliberately different from a preview-time batch rollback.
        contracts.put("invalidatePatchN", directWorkflowMatching("statusUpdates",
                new Node().type("Document Update"),
                updateDocumentStep(patch("remove", "/patchNTarget", null))));
        return root(repository, contracts)
                .properties("status", new Node().value("initial"))
                .properties("patchNTarget", new Node().value("present during preview"));
    }

    private static Node terminationDocument(BlueRepository repository) {
        Map<String, Node> contracts = ownerContracts();
        contracts.put("writer", directWorkflow("owner",
                updateDocumentStep(patch("replace", "/status", new Node().value("before termination"))),
                new Node().type("Coordination/Terminate Processing")
                        .properties("reason", new Node().value("finished intentionally")),
                updateDocumentStep(patch("add", "/mustNotAppear", new Node().value(true)))));
        return root(repository, contracts).properties("status", new Node().value("initial"));
    }

    private static Node embeddedDocument(BlueRepository repository) {
        Map<String, Node> childContracts = ownerContracts();
        childContracts.put("writer", directWorkflow("owner",
                updateDocumentStep(patch("replace", "/counter", new Node().value(7)))));
        Map<String, Node> rootContracts = new LinkedHashMap<String, Node>();
        rootContracts.put("embedded", new Node()
                .type("Process Embedded")
                .properties("paths", new Node().items(new Node().value("/child"))));
        return root(repository, rootContracts)
                .properties("counter", new Node().value(100))
                .properties("child", new Node()
                        .name("Child")
                        .properties("counter", new Node().value(0))
                        .properties("contracts", new Node().properties(childContracts)));
    }

    private static Node root(BlueRepository repository, Map<String, Node> contracts) {
        return new Node()
                .blue(repository.typeAliasBlue())
                .name("Frozen Update Differential")
                .properties("contracts", new Node().properties(contracts));
    }

    private static Map<String, Node> ownerContracts() {
        Map<String, Node> contracts = new LinkedHashMap<String, Node>();
        contracts.put("owner", TestTimelineProvider.channel("owner"));
        return contracts;
    }

    private static Node directWorkflow(String channel, Node... steps) {
        return new Node()
                .type("Coordination/Sequential Workflow")
                .properties("channel", new Node().value(channel))
                .properties("steps", new Node().items(steps));
    }

    private static Node directWorkflowMatching(String channel, Node event, Node... steps) {
        return directWorkflow(channel, steps).properties("event", event);
    }

    private static Node updateDocumentStep(Node... patches) {
        return new Node()
                .type("Coordination/Update Document")
                .properties("changeset", new Node().items(patches));
    }

    private static Node patch(String op, String path, Node value) {
        Node patch = new Node()
                .properties("op", new Node().value(op))
                .properties("path", new Node().value(path));
        if (value != null) {
            patch.properties("val", value);
        }
        return patch;
    }

    private static Node documentUpdateChannel(String path) {
        return new Node()
                .type("Document Update Channel")
                .properties("path", new Node().value(path));
    }

    private static Node triggerEventStep(String message) {
        return new Node()
                .type("Coordination/Trigger Event")
                .properties("event", new Node()
                        .type("Coordination/Chat Message")
                        .properties("message", new Node().value(message)));
    }

    private static Outcome run(boolean legacy, DocumentFactory factory) {
        BlueRepository repository = BlueRepository.latest();
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        SequentialWorkflowRunner runner = legacy
                ? legacyRunner(metrics)
                : SequentialWorkflowRunner.withBexEngine(
                        BexEngine.builder().build(), 100_000L, metrics);
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        try {
            CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                    .sequentialWorkflowRunner(runner)
                    .processingMetrics(metrics)
                    .build());
            Node initialized = blue.initializeDocument(blue.preprocess(factory.build(repository))).document();
            Node event = TestTimelineProvider.timelineEntry(blue,
                    repository,
                    "owner",
                    1,
                    TestTimelineProvider.chatMessage("run"));
            DocumentProcessingResult result = blue.processDocument(initialized, event);
            List<String> triggeredEventsJson = new ArrayList<String>(result.events().size());
            for (Node triggered : result.events()) {
                triggeredEventsJson.add(blue.nodeToJson(triggered));
            }
            ResolvedSnapshot resultSnapshot =
                    ProcessingResultTestSupport.snapshot(blue, result);
            return new Outcome(result.document().clone(),
                    resultSnapshot != null
                            ? resultSnapshot.frozenCanonicalRoot().resolvedStructuralKey()
                            : null,
                    resultSnapshot != null
                            ? resultSnapshot.frozenResolvedRoot().resolvedStructuralKey()
                            : null,
                    ProcessingResultTestSupport.blueId(result),
                    triggeredEventsJson,
                    result.totalGas(),
                    result.status(),
                    ProcessingResultTestSupport.diagnosticCategory(result),
                    ProcessingResultTestSupport.diagnosticMessage(result),
                    metrics.snapshot());
        } finally {
            try {
                blue.close();
            } finally {
                runner.close();
            }
        }
    }

    private static SequentialWorkflowRunner legacyRunner(BexProcessingMetrics metrics) {
        return new SequentialWorkflowRunner(Arrays
                .<WorkflowStepExecutor<? extends SequentialWorkflowStep>>asList(
                        new TriggerEventStepExecutor(metrics),
                        new TerminateProcessingStepExecutor(metrics),
                        new LegacyMutableUpdateExecutor(metrics)));
    }

    private static void assertEquivalent(Outcome frozen, Outcome legacy) {
        assertTrue(frozen.totalGas > 0L,
                "the production path must report its actual admitted gas");
        assertTrue(legacy.totalGas > 0L,
                "the test-only oracle must report its own admitted gas");
        assertEquals(
                legacy.status,
                frozen.status,
                "status: " + frozen.failureReason);
        assertEquals(legacy.errorCategory, frozen.errorCategory, "failure category");
        assertEquals(legacy.failureReason, frozen.failureReason, "failure reason");
    }

    private static void assertEquivalentFailure(
            Outcome frozen,
            Outcome legacy) {
        assertEquals(legacy.status, frozen.status, "status");
        assertEquals(
                legacy.errorCategory,
                frozen.errorCategory,
                "failure category");
        assertEquals(
                legacy.failureReason,
                frozen.failureReason,
                "failure reason");
    }

    private static long metric(BexProcessingMetrics.Snapshot metrics, String name) {
        Long value = metrics.languageCounters.get(name);
        return value != null ? value.longValue() : 0L;
    }

    private static Node nodeAt(Node root, String path) {
        try {
            return root != null ? root.getAsNode(path) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private interface DocumentFactory {
        Node build(BlueRepository repository);
    }

    private static final class Outcome {
        private final Node document;
        private final Object canonicalKey;
        private final Object resolvedKey;
        private final String blueId;
        private final List<String> triggeredEventsJson;
        private final long totalGas;
        private final ProcessorStatus status;
        private final ProcessorErrorCategory errorCategory;
        private final String failureReason;
        private final BexProcessingMetrics.Snapshot metrics;

        private Outcome(Node document,
                        Object canonicalKey,
                        Object resolvedKey,
                        String blueId,
                        List<String> triggeredEventsJson,
                        long totalGas,
                        ProcessorStatus status,
                        ProcessorErrorCategory errorCategory,
                        String failureReason,
                        BexProcessingMetrics.Snapshot metrics) {
            this.document = document;
            this.canonicalKey = canonicalKey;
            this.resolvedKey = resolvedKey;
            this.blueId = blueId;
            this.triggeredEventsJson = Collections.unmodifiableList(
                    new ArrayList<String>(triggeredEventsJson));
            this.totalGas = totalGas;
            this.status = status;
            this.errorCategory = errorCategory;
            this.failureReason = failureReason;
            this.metrics = metrics;
        }
    }

    /** Test-only reproduction of the legacy mutable Language handoff. */
    private static final class LegacyMutableUpdateExecutor
            implements WorkflowStepExecutor<UpdateDocument> {
        private final BexProcessingMetrics metrics;

        private LegacyMutableUpdateExecutor(BexProcessingMetrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public boolean supports(SequentialWorkflowStep step) {
            return step instanceof UpdateDocument;
        }

        @Override
        public WorkflowStepResult execute(UpdateDocument step, StepExecutionContext context) {
            StaticUpdatePlan plan = context.staticUpdatePlan();
            if (plan == null) {
                context.processorContext().throwFatal("Legacy differential lane requires a static plan");
                return WorkflowStepResult.none();
            }
            List<JsonPatch> patches = new ArrayList<JsonPatch>(plan.patches().size());
            for (StaticUpdatePlan.PatchTemplate template : plan.patches()) {
                FrozenJsonPatch frozen = template.bind(context.processorContext()
                        .resolvePointer(template.authoredPath()));
                if (frozen.getOp() == JsonPatch.Op.ADD) {
                    patches.add(JsonPatch.add(frozen.getPath(), frozen.getValue().toNode()));
                } else if (frozen.getOp() == JsonPatch.Op.REPLACE) {
                    patches.add(JsonPatch.replace(frozen.getPath(), frozen.getValue().toNode()));
                } else {
                    patches.add(JsonPatch.remove(frozen.getPath()));
                }
            }
            if (patches.isEmpty()) {
                return WorkflowStepResult.none();
            }
            WorkingDocument.Preview preview = null;
            boolean transferred = false;
            try {
                preview = context.advanceWorkingDocument(patches);
                if (preview == null) {
                    return WorkflowStepResult.none();
                }
                metrics.addMetric("mutablePatchesHandedToLanguage", patches.size());
                context.processorContext().applyPreviewedPatches(patches, preview);
                transferred = true;
                return WorkflowStepResult.none();
            } finally {
                if (!transferred && preview != null) {
                    preview.close();
                }
            }
        }
    }
}
