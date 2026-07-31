package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.result.BexExecutionResult;
import blue.coordination.processor.CoordinationProcessorOptions;
import blue.coordination.processor.CoordinationProcessors;
import blue.coordination.processor.CoordinationTestResources;
import blue.coordination.processor.ProcessingResultTestSupport;
import blue.coordination.processor.TestTimelineProvider;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.coordination.processor.bex.BexWorkflowContextFactory;
import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.GasMeter;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFatalException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.WorkingDocument;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
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
    void shouldMatchLegacyMutableHandoffForComputeEffectsAndMetrics() {
        // Given
        Outcome legacy = run(true);

        // When
        Outcome frozen = run(false);

        // Then
        assertEquivalentOutcome(frozen, legacy);
        assertAppliedEffects(frozen);
        assertEventOrder(frozen);
        assertHandoffMetrics(frozen, legacy);
    }

    private static void assertEquivalentOutcome(Outcome frozen, Outcome legacy) {
        assertEquals(legacy.canonicalKey, frozen.canonicalKey, "final canonical document");
        assertEquals(legacy.resolvedKey, frozen.resolvedKey, "final resolved document");
        assertEquals(legacy.blueId, frozen.blueId, "final BlueId");
        assertEquals(legacy.documentUpdateEvents, frozen.documentUpdateEvents,
                "all Document Update events and order");
        assertEquals(legacy.triggeredEvents, frozen.triggeredEvents,
                "all triggered events and order");
        assertEquals(legacy.status, frozen.status, "status");
        assertEquals(legacy.errorCategory, frozen.errorCategory, "failure category");
        assertEquals(legacy.failureReason, frozen.failureReason, "failure reason");
        assertEquals(legacy.terminationMarker, frozen.terminationMarker,
                "termination marker");
        assertEquals(legacy.channelCheckpoint, frozen.channelCheckpoint,
                "channel checkpoint");
    }

    private static void assertAppliedEffects(Outcome frozen) {
        assertEquals(ProcessorStatus.SUCCESS, frozen.status, frozen.failureReason);
        assertTrue(
                frozen.failureReason == null || frozen.failureReason.isEmpty(),
                "successful processing must not expose a diagnostic");
        assertEquals("value", frozen.document.get("/added/nested"));
        assertEquals("final", frozen.document.get("/status"));
        assertFalse(hasPath(frozen.document, "/removeMe"));
        assertFalse(hasPath(frozen.document, "/mustNotRun"));
        assertEquals("compute-effects-complete",
                frozen.document.get("/contracts/terminated/cause"));
        assertEquals("compute complete", frozen.document.get("/contracts/terminated/reason"));
        assertNull(frozen.channelCheckpoint,
                "application termination must not persist the source-channel checkpoint");
    }

    private static void assertEventOrder(Outcome frozen) {
        assertEquals(Arrays.asList("first", "second"), selectedKinds(frozen.documentEvents));
        assertEquals(
                -1,
                indexOfType(
                        frozen.documentEvents,
                        RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED),
                "processor lifecycle events remain internal");
        assertEquals(Arrays.asList(
                        "add:/added",
                        "replace:/status",
                        "replace:/status",
                        "remove:/removeMe"),
                primaryUpdateOrder(frozen.documentEvents));
    }

    private static void assertHandoffMetrics(Outcome frozen, Outcome legacy) {
        assertTrue(metricDelta(frozen, "frozenPatchesHandedToLanguage") > 0L);
        assertEquals(0L, metricDelta(frozen, "mutablePatchesHandedToLanguage"));
        assertTrue(metricDelta(frozen, "frozenPatchValuesAccepted") > 0L);
        assertEquals(0L, metricDelta(frozen, "mutablePatchValuesFrozen"),
                "initialization metrics must not be attributed to the Compute handoff");
        assertTrue(metricDelta(legacy, "mutablePatchesHandedToLanguage") > 0L);
        assertTrue(metricDelta(legacy, "mutablePatchValuesFrozen") > 0L);
        assertTrue(frozen.totalGas > 0L,
                "the production path must report its actual admitted gas");
        assertTrue(legacy.totalGas > 0L,
                "the test-only oracle must report its own admitted gas");
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
            Node initialized = blue.initializeDocument(
                    CoordinationTestResources
                            .preprocessWithFixedRepository(
                                    blue,
                                    repository,
                                    authored))
                    .document();
            BexProcessingMetrics.Snapshot metricsBeforeRun = metrics.snapshot();
            Node event = TestTimelineProvider.timelineEntry(blue,
                    repository,
                    "owner",
                    1,
                    TestTimelineProvider.chatMessage("run"));

            DocumentProcessingResult result = blue.processDocument(initialized, event);
            List<Node> documentEvents = immutableClones(result.events());
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
                    jsonEvents(blue, documentEvents, false),
                    jsonEvents(blue, documentEvents, true),
                    result.totalGas(),
                    result.status(),
                    ProcessingResultTestSupport.diagnosticCategory(result),
                    ProcessingResultTestSupport.diagnosticMessage(result),
                    jsonAt(blue, result.document(), "/contracts/terminated"),
                    jsonAt(blue,
                            result.document(),
                            "/contracts/checkpoint/entries/ownerChannel/subject"),
                    documentEvents,
                    metrics.snapshot(),
                    metricsBeforeRun);
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
                "                cause: compute-effects-complete",
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
        return metric(outcome.metrics.languageCounters, name)
                - metric(outcome.metricsBeforeRun.languageCounters, name);
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
        private final BexProcessingMetrics.Snapshot metrics;
        private final BexProcessingMetrics.Snapshot metricsBeforeRun;

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
                        BexProcessingMetrics.Snapshot metrics,
                        BexProcessingMetrics.Snapshot metricsBeforeRun) {
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
            this.metricsBeforeRun = metricsBeforeRun;
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
                GasMeter.ChildGasLedger legacyLedger =
                        context.processorContext().newRuntimeGasLedger(
                                "legacyMutableBexTest",
                                Collections.singletonMap(
                                        "aggregateExecutionUnit", 1L));
                legacyLedger.charge(
                        "aggregateExecutionUnit", execution.gasUsed());
                context.processorContext().submitRuntimeGasLedger(legacyLedger);
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
                context.processorContext().terminate(
                        effects.terminationCause(),
                        effects.terminationReason());
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
