package blue.coordination.processor.workflow;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.processor.CoordinationTestRuntime;
import blue.coordination.sdk.ActivationPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.coordination.sdk.DocumentHandle;
import blue.coordination.sdk.EntryDisposition;
import blue.coordination.sdk.EntryHandle;
import blue.coordination.sdk.EntryResult;
import blue.coordination.sdk.ExactBlueValue;
import blue.coordination.sdk.ManagedEpochReceipt;
import blue.coordination.sdk.TimelineHandle;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.ProcessorFatalException;
import blue.language.processor.WorkingDocument;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import blue.repo.BlueRepository;
import blue.repo.coordination.SequentialWorkflow;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Published-dependency regression for failures raised inside Update Document previews. */
final class IndependentT04RegressionTest {
    @ParameterizedTest
    @CsvSource({"false,unavailable", "true,unavailable", "false,missing", "true,missing",
            "false,invalid", "true,invalid"})
    void mutableAndFrozenPreviewsPreservePublishedProviderFailure(boolean frozen, String evidence) {
        NodeProviderResult response = switch (evidence) {
            case "missing" -> NodeProviderResult.notFound();
            case "invalid" -> NodeProviderResult.found(List.of(new Node().properties("count", new Node().value(999))));
            default -> NodeProviderResult.unavailable("T04 exact content unavailable");
        };
        withContext(response, context -> {
            List<JsonPatch> mutable = List.of(JsonPatch.replace("/payload/count", new Node().value(1)));
            List<FrozenJsonPatch> patches = List.of(FrozenJsonPatch.replace(
                    "/payload/count", FrozenNode.fromNode(new Node().value(1))));
            RuntimeException original;
            try (WorkingDocument direct = context.newWorkingDocument()) {
                original = assertThrows(RuntimeException.class, () -> {
                    if (frozen) direct.previewAndApplyFrozenPatches(patches);
                    else direct.previewAndApplyPatches(mutable);
                });
            }
            System.out.println("T04 published preview frozen=" + frozen + " evidence=" + evidence + " exception="
                    + original.getClass().getName());
            if (evidence.equals("unavailable")) {
                assertInstanceOf(ExecutionEvidenceUnavailableException.class, original);
            } else {
                // rc.25 distinguishes definitive NOT_FOUND from retryable UNAVAILABLE.
                assertInstanceOf(InvalidExecutionEvidenceException.class, original);
            }
            try (WorkingDocument working = context.newWorkingDocument()) {
                StepExecutionContext step = step(context, working);
                RuntimeException forwarded = assertThrows(RuntimeException.class, () -> {
                    if (frozen) step.advanceWorkingDocumentFrozen(patches);
                    else step.advanceWorkingDocument(mutable);
                });
                assertEquals(original.getClass(), forwarded.getClass());
                assertEquals(original.getMessage(), forwarded.getMessage());
                if (original instanceof ExecutionEvidenceUnavailableException missing) {
                    assertEquals(missing.requiredExactBlueIds(),
                            ((ExecutionEvidenceUnavailableException) forwarded).requiredExactBlueIds());
                }
            }
        });
    }

    @Test
    void updateSuspendsWhenPreviewNeedsExactEvidence() {
        // given
        boolean compute = false;
        // when
        EntryResult result = verifyMissingEvidence(compute);
        // then
        assertEquals(EntryDisposition.NEEDS_RESOURCES, result.disposition());
    }

    @Test
    void computeControlSuspendsForTheSamePatch() {
        // given
        boolean compute = true;
        // when
        EntryResult result = verifyMissingEvidence(compute);
        // then
        assertEquals(EntryDisposition.NEEDS_RESOURCES, result.disposition());
    }

    @Test
    void updateAcceptsTheSamePatchWithAvailableEvidence() {
        // given
        try (Scenario scenario = new Scenario(false)) {
            scenario.available = true;
            // when
            EntryResult result = scenario.process();
            // then
            assertEquals(EntryDisposition.APPLIED, result.disposition(), result.diagnostic().toString());
            assertTrue(scenario.previewRead(), scenario.reads.toString());
            assertEquals(1L, scenario.document.snapshot().longAt("/payload/count"));
        }
    }

    @Test
    void failedMultiPatchPreviewRollsBackAndSameEntryResumesExactlyOnceAfterRestart() {
        // given
        try (Scenario scenario = new Scenario(false, true, "/payload/count")) {
            String before = scenario.document.snapshot().exact().json();
            List<String> history = scenario.history();
            var routes = scenario.blue.advanced().auditOperationRoutes(scenario.document.id());
            // when
            EntryResult suspended = scenario.process();
            // then
            assertEquals(EntryDisposition.NEEDS_RESOURCES, suspended.disposition());
            scenario.assertUnchanged(before, history, suspended);
            assertEquals(routes, scenario.blue.advanced().auditOperationRoutes(scenario.document.id()));
            String closureId = suspended.closures().get(0).closureId();

            scenario.available = true;
            CoordinationTestControl.attach(scenario.blue.advanced().rawEngine()).restartFromStores();
            EntryResult resumed = scenario.blue.processing().drain().entry(scenario.entry);
            assertEquals(EntryDisposition.APPLIED, resumed.disposition(), resumed.diagnostic().toString());
            assertEquals(closureId, resumed.closures().get(0).closureId());
            assertEquals(1L, scenario.document.snapshot().longAt("/count"));
            assertEquals(1L, scenario.document.snapshot().longAt("/payload/count"));
            assertEquals(1, resumed.publicEvents().size());
            assertEquals(history.size() + 1, scenario.history().size());
            assertEquals(1, scenario.blue.advanced().auditTimeline("t04/owner").size());
            String committed = scenario.document.snapshot().blueId();
            List<String> committedHistory = scenario.history();
            scenario.process();
            assertEquals(committed, scenario.document.snapshot().blueId());
            assertEquals(committedHistory, scenario.history());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidEvidenceIsRejectedWithoutPublishing(boolean compute) {
        try (Scenario scenario = new Scenario(compute)) {
            scenario.invalid = true;
            String before = scenario.document.snapshot().exact().json();
            List<String> history = scenario.history();
            EntryResult result = scenario.process();
            assertTrue(scenario.previewRead());
            assertEquals(EntryDisposition.REJECTED, result.disposition());
            assertTrue(result.closures().stream().allMatch(c -> c.resourceDemands().isEmpty()));
            scenario.assertUnchanged(before, history, result);
            System.out.println("T04 invalid evidence compute=" + compute + " diagnostic=" + result.diagnostic());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void semanticPatchErrorRetainsItsCategoryAndRollsBackEarlierPatches(boolean compute) {
        try (Scenario scenario = new Scenario(compute, true, "/absent/count")) {
            String before = scenario.document.snapshot().exact().json();
            List<String> history = scenario.history();
            EntryResult result = scenario.process();
            assertEquals(EntryDisposition.REJECTED, result.disposition());
            assertEquals("INVALID_PATCH", result.diagnostic().code());
            scenario.assertUnchanged(before, history, result);
        }
    }

    @Test
    void exactlySufficientGasCommitsAndOneShortRollsBack() {
        // given
        long required;
        long mutationBudget = -1L;
        // when
        try (Scenario scenario = new Scenario(false)) {
            scenario.available = true;
            EntryResult result = scenario.process();
            assertEquals(EntryDisposition.APPLIED, result.disposition());
            required = scenario.execution(result).totalGas();
            long prefix = 0L;
            for (var charge : scenario.execution(result).gasTrace()) {
                if (charge.counter().equals("patchAddOrReplace") && mutationBudget < 0) {
                    mutationBudget = prefix + charge.subtotal() - 1L;
                }
                prefix += charge.subtotal();
            }
        }
        // then
        assertTrue(mutationBudget > 0L);
        for (long budget : List.of(mutationBudget, required - 1, required)) {
            try (Scenario scenario = new Scenario(false)) {
                scenario.available = true;
                String before = scenario.document.snapshot().exact().json();
                List<String> history = scenario.history();
                EntryResult result = scenario.blue.advanced().process(scenario.document, scenario.entry,
                        ContractsExecutionPolicy.exactSharedGas(budget, "t04-gas-boundary")).entry(scenario.entry);
                ClosureProcessResult execution = scenario.execution(result);
                assertTrue(scenario.previewRead(), "The preview must be reached before rejection");
                if (budget == required) {
                    assertEquals(EntryDisposition.APPLIED, result.disposition());
                    assertEquals(required, execution.totalGas());
                    assertEquals(1L, scenario.document.snapshot().longAt("/payload/count"));
                } else {
                    assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, result.disposition());
                    assertTrue(execution.rollbackToInput());
                    assertNull(execution.commitCompanion());
                    assertNull(execution.rootedProjection());
                    assertNotNull(execution.rejectedCharge());
                    if (budget == mutationBudget) {
                        assertEquals("patchAddOrReplace", execution.rejectedCharge().counter());
                    }
                    assertTrue(execution.checkpointWrites().isEmpty());
                    scenario.assertUnchanged(before, history, result);
                }
                System.out.println("T04 budget=" + budget + " status=" + result.disposition()
                        + " admitted=" + execution.totalGas() + " trace=" + execution.gasTraceIdentity());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyPreviewsAreNoOpsAndFailedSequencesDoNotAdvanceWorkingState(boolean frozen) {
        withContext(context -> {
            try (WorkingDocument working = context.newWorkingDocument()) {
                StepExecutionContext step = step(context, working);
                FrozenNode before = working.canonicalRoot();
                assertNull(step.advanceWorkingDocument(null));
                assertNull(step.advanceWorkingDocument(List.of()));
                assertNull(step.advanceWorkingDocumentFrozen(null));
                assertNull(step.advanceWorkingDocumentFrozen(List.of()));
                assertThrows(ExecutionEvidenceUnavailableException.class, () -> {
                    if (frozen) step.advanceWorkingDocumentFrozen(List.of(
                            FrozenJsonPatch.replace("/count", FrozenNode.fromNode(new Node().value(1))),
                            FrozenJsonPatch.replace("/payload/count", FrozenNode.fromNode(new Node().value(1)))));
                    else step.advanceWorkingDocument(List.of(JsonPatch.replace("/count", new Node().value(1)),
                            JsonPatch.replace("/payload/count", new Node().value(1))));
                });
                assertEquals(before.blueId(), working.canonicalRoot().blueId());
            }
        });
    }

    /** Fault injection covers adapter policy; actual reachability is established by the SDK tests above. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void typedPreviewFailuresKeepTheirIdentityAndPrecedence(boolean frozen) {
        GasMeter.ChildGasLedger ledger = new GasMeter(GasSchedule.contracts10(), 0L)
                .childLedger("t04", Map.of("unit", 1L));
        RuntimeException gas = assertThrows(GasLimitExceededException.class, () -> ledger.charge("unit", 1L));
        RuntimeException invalid = new InvalidExecutionEvidenceException("T04 invalid evidence");
        for (RuntimeException failure : List.of(gas, invalid,
                new ExecutionEvidenceUnavailableException("T04 missing", List.of("required-exact-id")),
                new PortableLimitExceededException("maxPatches", 2, 1),
                new ProcessorFailureException(ProcessorErrorCategory.InvalidPatch, "T04 invalid patch", invalid))) {
            withContext(context -> {
                try (WorkingDocument working = context.newWorkingDocument()) {
                    StepExecutionContext step = step(context, working);
                    RuntimeException forwarded = assertThrows(RuntimeException.class, () -> {
                        if (frozen) step.advanceWorkingDocumentFrozen(failingPatchList(failure));
                        else step.advanceWorkingDocument(failingPatchList(failure));
                    });
                    assertSame(failure, forwarded);
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unexpectedPreviewFailuresRemainRuntimeFatal(boolean frozen) {
        withContext(context -> {
            try (WorkingDocument working = context.newWorkingDocument()) {
                StepExecutionContext step = step(context, working);
                IllegalStateException unexpected = new IllegalStateException("T04 unexpected preview failure");
                ProcessorFatalException failure = assertThrows(ProcessorFatalException.class, () -> {
                    if (frozen) step.advanceWorkingDocumentFrozen(failingPatchList(unexpected));
                    else step.advanceWorkingDocument(failingPatchList(unexpected));
                });
                assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure, failure.errorCategory());
                assertTrue(failure.getMessage().contains(unexpected.getMessage()));
            }
        });
    }

    private static <T> List<T> failingPatchList(RuntimeException failure) {
        return new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public T get(int index) { throw failure; }
        };
    }

    private EntryResult verifyMissingEvidence(boolean compute) {
        try (Scenario scenario = new Scenario(compute)) {
            String before = scenario.document.snapshot().blueId();
            EntryResult result = scenario.process();
            assertTrue(scenario.previewRead(), scenario.reads.toString());
            System.out.println("T04 compute=" + compute + " disposition=" + result.disposition()
                    + " diagnostic=" + result.diagnostic());
            assertEquals(before, scenario.document.snapshot().blueId());
            assertTrue(result.publicEvents().isEmpty());
            assertEquals(EntryDisposition.NEEDS_RESOURCES, result.disposition(), result.diagnostic().toString());
            assertEquals(scenario.payload.blueId(),
                    result.closures().get(0).resourceDemands().get(0).blueId());
            return result;
        }
    }

    private static StepExecutionContext step(ProcessorExecutionContext context, WorkingDocument working) {
        return new StepExecutionContext(context, new SequentialWorkflow(), null,
                (FrozenNode) null, null, 0, Map.of(), working);
    }

    /** Executes assertions inside a real rc.25 handler context, with a lazy unavailable business value. */
    private static void withContext(Consumer<ProcessorExecutionContext> action) {
        withContext(NodeProviderResult.unavailable("T04 exact content unavailable"), action);
    }

    private static void withContext(NodeProviderResult payloadResponse, Consumer<ProcessorExecutionContext> action) {
        Node type = new Node().name("T04 boundary probe handler");
        String typeId = DirectBlueIdCalculator.calculateBlueId(type);
        String payloadId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties("count", new Node().value(0)));
        try (CoordinationTestRuntime runtime = CoordinationTestRuntime.create(BlueRepository.current())) {
            runtime.addNodeProvider(new NodeProvider() {
                @Override public List<Node> fetchByBlueId(String id) {
                    return typeId.equals(id) ? List.of(type.clone()) : List.of();
                }
                @Override public NodeProviderResult fetchResultByBlueId(String id) {
                    return payloadId.equals(id) ? payloadResponse
                            : NodeProvider.super.fetchResultByBlueId(id);
                }
            });
            int[] calls = {0};
            runtime.registerExternalContractType(typeId, type, new HandlerProcessor<ProbeHandler>() {
                @Override public Class<ProbeHandler> contractType() { return ProbeHandler.class; }
                @Override public void execute(ProbeHandler contract, ProcessorExecutionContext context) {
                    calls[0]++;
                    action.accept(context);
                }
            });
            runtime.initializeDocument(new Node().properties("payload", new Node().blueId(payloadId))
                    .properties("count", new Node().value(0))
                    .contracts(new Node()
                            .properties("lifecycle", new Node().type(new Node().blueId(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                            .properties("probe", new Node().type(new Node().blueId(typeId))
                                    .properties("channel", new Node().value("lifecycle")))));
            assertEquals(1, calls[0], "The probe must reach the admitted handler");
        }
    }

    public static final class ProbeHandler extends HandlerContract { }

    private static final class Scenario implements AutoCloseable {
        final ExactBlueValue payload;
        final List<String> reads = new ArrayList<>();
        final BlueCoordination blue;
        final DocumentHandle document;
        final EntryHandle entry;
        boolean available;
        boolean invalid;

        Scenario(boolean compute) {
            this(compute, false, "/payload/count");
        }

        Scenario(boolean compute, boolean multiple, String path) {
            try (BlueCoordination verifier = BlueCoordination.inMemory()) {
                payload = verifier.values().providerContentYaml("count: 0\n");
            }
            blue = BlueCoordination.builder().contentDerivedDocumentIds()
                    .exactNodeProvider(id -> {
                        if (!payload.blueId().equals(id)) return Optional.empty();
                        reads.add(Arrays.toString(Thread.currentThread().getStackTrace()));
                        if (invalid) return Optional.of("{\"count\": 999}");
                        return available ? Optional.of(payload.json()) : Optional.empty();
                    }).build();
            TimelineHandle timeline = blue.timelines().register("t04/owner", "alice");
            String step = compute ? """
                    - type: Coordination/Compute
                      do:
                      - $appendChange:
                          op: replace
                          path: /payload/count
                          val: 1
                      - $return: true
                    """ : """
                    - type: Coordination/Update Document
                      changeset:
                      - op: replace
                        path: /payload/count
                        val: 1
                    """;
            step = step.replace("/payload/count", path);
            if (multiple) {
                String firstPatch = compute ? """
                        - $appendChange:
                            op: replace
                            path: /count
                            val: 1
                        """.indent(2) : """
                        - op: replace
                          path: /count
                          val: 1
                        """.indent(2);
                step = step.replace(compute ? "  do:\n" : "  changeset:\n",
                        (compute ? "  do:\n" : "  changeset:\n") + firstPatch);
                step = """
                        - type: Coordination/Trigger Event
                          event:
                            type: Coordination/Event
                            kind: T04 prior event
                        """ + step;
            }
            document = blue.documents().admitStaticProcessEmbedded("""
                    name: T04 preview failure
                    count: 0
                    payload:
                      blueId: %s
                    contracts:
                      owner:
                        type: Coordination/Timeline Channel
                        timeline:
                          type: MyOS/MyOS Timeline
                          timelineId: t04/owner
                        actor:
                          type: MyOS/Principal Actor
                          accountId: alice
                      update:
                        type: Coordination/Sequential Workflow Operation
                        channel: owner
                        request: {}
                        steps:
                    %s
                    """.formatted(payload.blueId(), step.indent(6)),
                    ActivationPolicy.importFullHistory()).document("root");
            assertTrue(reads.isEmpty(), "Business reference must stay lazy during admission");
            entry = blue.operations().on(document).from(timeline).call("update")
                    .through("owner").requestYaml("{}").submit();
        }

        EntryResult process() {
            return blue.processing().process(document, entry).entry(entry);
        }

        boolean previewRead() {
            return reads.stream().anyMatch(stack -> stack.contains("WorkingDocument.previewAndApplyFrozenPatches"));
        }

        List<String> history() {
            return blue.advanced().auditManagedEpochs(document.id()).stream()
                    .map(ManagedEpochReceipt::receiptIdentity).toList();
        }

        ClosureProcessResult execution(EntryResult result) {
            return blue.advanced().closureExecution(result.closures().get(0).closureId()).orElseThrow();
        }

        void assertUnchanged(String before, List<String> history, EntryResult result) {
            assertEquals(before, document.snapshot().exact().json(), "Includes checkpoint and processor-owned state");
            assertEquals(history, history(), "No managed epoch or transition receipt may commit");
            assertTrue(result.publicEvents().isEmpty());
            assertTrue(result.closures().stream().allMatch(c -> c.changes().isEmpty() && c.publicEvents().isEmpty()));
        }

        @Override public void close() { blue.close(); }
    }
}
