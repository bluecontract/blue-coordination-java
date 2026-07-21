package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.result.BexExecutionResult;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.RepositoryTypeAliasPreprocessor;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.BexWorkflowContextFactory;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFatalException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.repo.BlueRepository;
import blue.repo.coordination.Compute;
import blue.repo.coordination.SequentialWorkflowStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end differential for the frozen Compute patch handoff. */
class FrozenComputeDifferentialTest {

    @Test
    void computeChangesetEventsAndTerminationMatchTheLegacyMutableHandoff() {
        Outcome frozen = run(false);
        Outcome legacy = run(true);

        assertEquals(legacy.canonicalKey, frozen.canonicalKey, "final canonical document");
        assertEquals(legacy.resolvedKey, frozen.resolvedKey, "final resolved document");
        assertEquals(legacy.blueId, frozen.blueId, "final BlueId");
        assertEquals(legacy.documentUpdateEvents, frozen.documentUpdateEvents,
                "all Document Update events and order");
        assertEquals(legacy.triggeredEvents, frozen.triggeredEvents,
                "all triggered events and order");
        assertEquals(legacy.totalGas, frozen.totalGas, "gas");
        assertEquals(legacy.status, frozen.status, "status");
        assertEquals(legacy.errorCategory, frozen.errorCategory, "failure category");
        assertEquals(legacy.failureReason, frozen.failureReason, "failure reason");
        assertEquals(legacy.terminationMarker, frozen.terminationMarker,
                "termination marker");
        assertEquals(legacy.channelCheckpoint, frozen.channelCheckpoint,
                "channel checkpoint");

        assertEquals(ProcessorStatus.SUCCESS, frozen.status, frozen.failureReason);
        assertNull(frozen.failureReason);
        assertEquals("value", frozen.document.get("/added/nested"));
        assertEquals("final", frozen.document.get("/status"));
        assertFalse(hasPath(frozen.document, "/removeMe"));
        assertFalse(hasPath(frozen.document, "/mustNotRun"));
        assertEquals("graceful", frozen.document.get("/contracts/terminated/cause"));
        assertEquals("compute complete", frozen.document.get("/contracts/terminated/reason"));
        assertNull(frozen.channelCheckpoint,
                "graceful termination must not persist the source-channel checkpoint");
        assertEquals(Arrays.asList("first", "second"), selectedKinds(frozen.documentEvents));
        assertTrue(indexOfKind(frozen.documentEvents, "second")
                        < indexOfType(frozen.documentEvents,
                        RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED),
                "Compute events must remain ahead of the termination event");
        assertEquals(Arrays.asList(
                        "add:/added",
                        "replace:/status",
                        "replace:/status",
                        "remove:/removeMe"),
                primaryUpdateOrder(frozen.documentEvents));

        assertTrue(metricDelta(frozen, "frozenPatchesHandedToLanguage") > 0L);
        assertEquals(0L, metricDelta(frozen, "mutablePatchesHandedToLanguage"));
        assertTrue(metricDelta(frozen, "frozenPatchValuesAccepted") > 0L);
        assertEquals(0L, metricDelta(frozen, "mutablePatchValuesFrozen"),
                "initialization metrics must not be attributed to the Compute handoff");
        assertTrue(metricDelta(legacy, "mutablePatchesHandedToLanguage") > 0L);
        assertTrue(metricDelta(legacy, "mutablePatchValuesFrozen") > 0L);
    }

    private static Outcome run(boolean legacyMutableHandoff) {
        BlueRepository repository = BlueRepository.latest();
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        BexEngine engine = BexEngine.builder().build();
        SequentialWorkflowRunner runner = legacyMutableHandoff
                ? legacyRunner(engine, metrics)
                : SequentialWorkflowRunner.withBexEngine(engine, 100_000L, metrics);
        Blue blue = CoordinationTestResources.configuredBlue(repository);
        try {
            CoordinationProcessors.registerWith(blue, CoordinationProcessorOptions.builder()
                    .bexEngine(engine)
                    .sequentialWorkflowRunner(runner)
                    .defaultComputeGasLimit(100_000L)
                    .processingMetrics(metrics)
                    .build());
            Node authored = blue.parseSourceYaml(documentYaml());
            authored.blue(repository.typeAliasBlue());
            Node aliasesResolved = new RepositoryTypeAliasPreprocessor(
                    CoordinationTestResources.testTypeAliases(repository)).preprocess(authored);
            Node initialized = blue.initializeDocument(blue.preprocess(aliasesResolved)).document();
            Map<String, Long> languageCountersBeforeRun = metrics.languageCounters();
            Node event = TestTimelineProvider.timelineEntry(blue,
                    repository,
                    "owner",
                    1,
                    TestTimelineProvider.chatMessage("run"));

            DocumentProcessingResult result = blue.processDocument(initialized, event);
            List<Node> documentEvents = immutableClones(result.triggeredEvents());
            return new Outcome(result.document().clone(),
                    result.snapshot() != null
                            ? result.snapshot().frozenCanonicalRoot().resolvedStructuralKey()
                            : null,
                    result.snapshot() != null
                            ? result.snapshot().frozenResolvedRoot().resolvedStructuralKey()
                            : null,
                    result.blueId(),
                    jsonEvents(blue, documentEvents, false),
                    jsonEvents(blue, documentEvents, true),
                    result.totalGas(),
                    result.status(),
                    result.errorCategory(),
                    result.failureReason(),
                    jsonAt(blue, result.document(), "/contracts/terminated"),
                    jsonAt(blue,
                            result.document(),
                            "/contracts/checkpoint/lastEvents/ownerChannel"),
                    documentEvents,
                    metrics,
                    languageCountersBeforeRun);
        } finally {
            try {
                blue.close();
            } finally {
                runner.close();
            }
        }
    }

    private static SequentialWorkflowRunner legacyRunner(BexEngine engine,
                                                          BexProcessingMetrics metrics) {
        return new SequentialWorkflowRunner(Arrays
                .<WorkflowStepExecutor<? extends SequentialWorkflowStep>>asList(
                        new TriggerEventStepExecutor(metrics),
                        new LegacyMutableComputeExecutor(engine, 100_000L, metrics),
                        new TerminateProcessingStepExecutor(metrics),
                        new UpdateDocumentStepExecutor(metrics)));
    }

    private static String documentYaml() {
        return String.join("\n",
                "name: Frozen Compute Differential",
                "status: idle",
                "removeMe: old",
                "contracts:",
                CoordinationTestResources.simpleTimelineChannelYaml("ownerChannel", "owner", 2),
                "  addedUpdates:",
                "    type: Document Update Channel",
                "    path: /added",
                "  statusUpdates:",
                "    type: Document Update Channel",
                "    path: /status",
                "  removedUpdates:",
                "    type: Document Update Channel",
                "    path: /removeMe",
                documentUpdateObserver("observeAdded", "addedUpdates", "add", "/added"),
                documentUpdateObserver("observeStatus", "statusUpdates", "replace", "/status"),
                documentUpdateObserver("observeRemoved", "removedUpdates", "remove", "/removeMe"),
                "  run:",
                "    type: Coordination/Sequential Workflow",
                "    channel: ownerChannel",
                "    steps:",
                "      - name: Return ordered effects",
                "        type: Coordination/Compute",
                "        do:",
                "          - $return:",
                "              changeset:",
                "                - op: add",
                "                  path: /added",
                "                  val:",
                "                    nested: value",
                "                - op: replace",
                "                  path: /status",
                "                  val: intermediate",
                "                - op: replace",
                "                  path: /status",
                "                  val: final",
                "                - op: remove",
                "                  path: /removeMe",
                "              events:",
                "                - type: Coordination/Event",
                "                  kind: first",
                "                - type: Coordination/Event",
                "                  kind: second",
                "              termination:",
                "                reason: compute complete",
                "      - name: Must not run after termination",
                "        type: Coordination/Update Document",
                "        changeset:",
                "          - op: add",
                "            path: /mustNotRun",
                "            val: true");
    }

    /**
     * Re-emits a stable trace from each exact-path Document Update channel. This
     * makes every internally delivered update and its order observable through
     * DocumentProcessingResult without depending on Language implementation
     * internals.
     */
    private static String documentUpdateObserver(String key,
                                                 String channel,
                                                 String op,
                                                 String path) {
        return String.join("\n",
                "  " + key + ":",
                "    type: Coordination/Sequential Workflow",
                "    channel: " + channel,
                "    event:",
                "      type: Document Update",
                "    steps:",
                "      - type: Coordination/Trigger Event",
                "        event:",
                "          type: Coordination/Event",
                "          kind: document-update",
                "          op: " + op,
                "          path: " + path);
    }

    private static List<Node> immutableClones(List<Node> events) {
        List<Node> clones = new ArrayList<Node>(events.size());
        for (Node event : events) {
            clones.add(event.clone());
        }
        return Collections.unmodifiableList(clones);
    }

    private static List<String> jsonEvents(Blue blue,
                                           List<Node> events,
                                           boolean documentUpdatesOnly) {
        List<String> json = new ArrayList<String>();
        for (Node event : events) {
            if (!documentUpdatesOnly || isDocumentUpdateTrace(event)) {
                json.add(blue.nodeToJson(event));
            }
        }
        return Collections.unmodifiableList(json);
    }

    private static String jsonAt(Blue blue, Node document, String pointer) {
        Node node = nodeAt(document, pointer);
        return node != null ? blue.nodeToJson(node) : null;
    }

    private static List<String> selectedKinds(List<Node> events) {
        List<String> kinds = new ArrayList<String>();
        for (Node event : events) {
            Object kind = valueAt(event, "/kind");
            if ("first".equals(kind) || "second".equals(kind)) {
                kinds.add((String) kind);
            }
        }
        return kinds;
    }

    private static List<String> primaryUpdateOrder(List<Node> events) {
        List<String> updates = new ArrayList<String>();
        for (Node event : events) {
            if (!isDocumentUpdateTrace(event)) {
                continue;
            }
            Object path = valueAt(event, "/path");
            if ("/added".equals(path)
                    || "/status".equals(path)
                    || "/removeMe".equals(path)) {
                updates.add(String.valueOf(valueAt(event, "/op")) + ":" + path);
            }
        }
        return updates;
    }

    private static boolean isDocumentUpdateTrace(Node event) {
        return "document-update".equals(valueAt(event, "/kind"));
    }

    private static int indexOfKind(List<Node> events, String kind) {
        for (int index = 0; index < events.size(); index++) {
            if (kind.equals(valueAt(events.get(index), "/kind"))) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfType(List<Node> events, String blueId) {
        for (int index = 0; index < events.size(); index++) {
            if (isType(events.get(index), blueId)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isType(Node node, String blueId) {
        return node != null
                && node.getType() != null
                && blueId.equals(node.getType().getBlueId());
    }

    private static Object valueAt(Node node, String pointer) {
        try {
            return node.get(pointer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasPath(Node node, String pointer) {
        return nodeAt(node, pointer) != null;
    }

    private static Node nodeAt(Node node, String pointer) {
        try {
            return node != null ? node.getAsNode(pointer) : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static long metricDelta(Outcome outcome, String name) {
        return metric(outcome.metrics.languageCounters(), name)
                - metric(outcome.languageCountersBeforeRun, name);
    }

    private static long metric(Map<String, Long> counters, String name) {
        Long value = counters.get(name);
        return value != null ? value.longValue() : 0L;
    }

    private static final class Outcome {
        private final Node document;
        private final Object canonicalKey;
        private final Object resolvedKey;
        private final String blueId;
        private final List<String> triggeredEvents;
        private final List<String> documentUpdateEvents;
        private final long totalGas;
        private final ProcessorStatus status;
        private final ProcessorErrorCategory errorCategory;
        private final String failureReason;
        private final String terminationMarker;
        private final String channelCheckpoint;
        private final List<Node> documentEvents;
        private final BexProcessingMetrics metrics;
        private final Map<String, Long> languageCountersBeforeRun;

        private Outcome(Node document,
                        Object canonicalKey,
                        Object resolvedKey,
                        String blueId,
                        List<String> triggeredEvents,
                        List<String> documentUpdateEvents,
                        long totalGas,
                        ProcessorStatus status,
                        ProcessorErrorCategory errorCategory,
                        String failureReason,
                        String terminationMarker,
                        String channelCheckpoint,
                        List<Node> documentEvents,
                        BexProcessingMetrics metrics,
                        Map<String, Long> languageCountersBeforeRun) {
            this.document = document;
            this.canonicalKey = canonicalKey;
            this.resolvedKey = resolvedKey;
            this.blueId = blueId;
            this.triggeredEvents = triggeredEvents;
            this.documentUpdateEvents = documentUpdateEvents;
            this.totalGas = totalGas;
            this.status = status;
            this.errorCategory = errorCategory;
            this.failureReason = failureReason;
            this.terminationMarker = terminationMarker;
            this.channelCheckpoint = channelCheckpoint;
            this.documentEvents = documentEvents;
            this.metrics = metrics;
            this.languageCountersBeforeRun = languageCountersBeforeRun;
        }
    }

    /**
     * Test-only reproduction of the legacy mutable Language patch handoff.
     * Planning and BEX execution stay shared so the differential isolates the
     * mutable-versus-frozen boundary under test.
     */
    private static final class LegacyMutableComputeExecutor
            implements WorkflowStepExecutor<Compute> {
        private final BexEngine bexEngine;
        private final long defaultGasLimit;
        private final ComputeDefinitionResolver definitionResolver;
        private final BexWorkflowContextFactory contextFactory;
        private final ComputeResultEmitter resultPlanner;
        private final ComputeProgramNormalizer normalizer;
        private final BexProcessingMetrics metrics;

        private LegacyMutableComputeExecutor(BexEngine bexEngine,
                                             long defaultGasLimit,
                                             BexProcessingMetrics metrics) {
            this.bexEngine = bexEngine;
            this.defaultGasLimit = defaultGasLimit;
            this.definitionResolver = new ComputeDefinitionResolver(metrics);
            this.contextFactory = new BexWorkflowContextFactory(metrics);
            this.resultPlanner = new ComputeResultEmitter(metrics);
            this.normalizer = new ComputeProgramNormalizer(metrics);
            this.metrics = metrics;
        }

        @Override
        public boolean supports(SequentialWorkflowStep step) {
            return step instanceof Compute;
        }

        @Override
        public WorkflowStepResult execute(Compute step, StepExecutionContext context) {
            long stepStart = System.nanoTime();
            try {
                metrics.incrementComputeStepsExecuted();
                FrozenNode rawStep = context.stepFrozenNode();
                if (rawStep == null) {
                    Node mutableStep = context.stepNodeRef();
                    if (mutableStep == null) {
                        context.processorContext().throwFatal(
                                "Compute step must have a raw step node");
                        return WorkflowStepResult.none();
                    }
                    rawStep = FrozenNode.fromResolvedNode(mutableStep);
                }
                FrozenNode resolvedDefinition = definitionResolver.resolve(rawStep,
                        context,
                        metrics);
                FrozenNode program = normalizer.program(rawStep);
                FrozenNode definition = resolvedDefinition != null
                        ? normalizer.definition(resolvedDefinition)
                        : null;
                String authoredEntry = FrozenNodeUtil.textProperty(rawStep, "entry");
                String normalizedEntry = FrozenNodeUtil.textProperty(program, "entry");
                if (!Objects.equals(authoredEntry, normalizedEntry)) {
                    throw new BexException("Compute entry changed during normalization");
                }
                BexProgramSource source = definition != null
                        ? BexProgramSource.withDefinition(program, definition, normalizedEntry)
                        : BexProgramSource.inline(program);
                long gasLimit = gasLimit(program);
                BexExecutionContext bexContext = contextFactory.create(context, gasLimit);
                BexExecutionResult execution = bexEngine.compileAndExecute(source, bexContext);
                metrics.addBexMetrics(execution.metrics());
                if (execution.gasUsed() > 0L) {
                    context.processorContext().consumeGas(execution.gasUsed());
                }
                ComputeEffectPlan effects = resultPlanner.plan(execution,
                        context,
                        FrozenNodeUtil.booleanProperty(program, "emitEvents", true));
                bufferThroughLegacyMutableApi(effects, context);
                if (effects.terminationRequested()) {
                    return FrozenNodeUtil.booleanProperty(program, "returnResult", true)
                            ? WorkflowStepResult.terminalValue(execution,
                            effects.changesetHandled())
                            : WorkflowStepResult.terminal();
                }
                return FrozenNodeUtil.booleanProperty(program, "returnResult", true)
                        ? WorkflowStepResult.value(execution, effects.changesetHandled())
                        : WorkflowStepResult.none();
            } catch (ComputeResultValidationException ex) {
                metrics.incrementComputeResultValidationFailures();
                context.processorContext().throwFatal(
                        "Invalid Compute result: " + ex.getMessage());
                return WorkflowStepResult.none();
            } catch (ProcessorFatalException ex) {
                throw ex;
            } catch (BexException ex) {
                context.processorContext().throwFatal("Compute failed: " + ex.getMessage());
                return WorkflowStepResult.none();
            } catch (RuntimeException ex) {
                context.processorContext().throwFatal("Compute failed: " + ex.getMessage());
                return WorkflowStepResult.none();
            } finally {
                metrics.addComputeStepNanos(System.nanoTime() - stepStart);
            }
        }

        private long gasLimit(FrozenNode program) {
            Long configured = FrozenNodeUtil.integer(
                    FrozenNodeUtil.property(program, "gasLimit"));
            if (configured == null) {
                return defaultGasLimit;
            }
            if (configured.longValue() <= 0L) {
                throw new BexException("Compute gasLimit must be positive");
            }
            return configured.longValue();
        }

        private void bufferThroughLegacyMutableApi(ComputeEffectPlan effects,
                                                   StepExecutionContext context) {
            effects.claimForBuffering();
            List<JsonPatch> patches = mutablePatches(effects.patches());
            if (!patches.isEmpty()) {
                WorkingDocument.Preview preview = null;
                boolean transferred = false;
                try {
                    preview = context.advanceWorkingDocument(patches);
                    if (preview != null) {
                        metrics.addMetric("mutablePatchesHandedToLanguage", patches.size());
                        context.processorContext().applyPreviewedPatches(patches, preview);
                        transferred = true;
                        metrics.addPatchesApplied(patches.size());
                        metrics.incrementUpdateBatchPatchApplications();
                    }
                } finally {
                    if (!transferred && preview != null) {
                        preview.close();
                    }
                }
            }
            for (FrozenNode event : effects.events()) {
                context.processorContext().emitEvent(event.toNode());
                metrics.incrementEventsEmitted();
            }
            if (effects.terminationRequested()) {
                context.processorContext().terminateGracefully(effects.terminationReason());
                metrics.incrementSuccessfulComputeTerminationRequests();
            }
        }

        private List<JsonPatch> mutablePatches(List<FrozenJsonPatch> frozenPatches) {
            List<JsonPatch> mutable = new ArrayList<JsonPatch>(frozenPatches.size());
            for (FrozenJsonPatch patch : frozenPatches) {
                if (patch.getOp() == JsonPatch.Op.ADD) {
                    mutable.add(JsonPatch.add(patch.getPath(), patch.getValue().toNode()));
                } else if (patch.getOp() == JsonPatch.Op.REPLACE) {
                    mutable.add(JsonPatch.replace(patch.getPath(), patch.getValue().toNode()));
                } else {
                    mutable.add(JsonPatch.remove(patch.getPath()));
                }
            }
            return mutable;
        }
    }
}
