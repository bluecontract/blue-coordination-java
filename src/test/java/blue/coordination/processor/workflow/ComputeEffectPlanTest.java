package blue.coordination.processor.workflow;

import blue.bex.result.BexChangeset;
import blue.bex.result.BexEvents;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.repo.coordination.TerminateProcessing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeEffectPlanTest {
    @Test
    void planCopiesAndFreezesEventContent() {
        Node event = new Node().properties("kind", new Node().value("original"));
        List<Node> events = new ArrayList<Node>();
        events.add(event);

        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), events, true,
                "completed", "done", true);
        event.getProperties().get("kind").value("mutated");
        events.clear();

        assertTrue(plan.patches().isEmpty());
        assertEquals(1, plan.events().size());
        assertEquals("original", plan.events().get(0).toNode().get("/kind"));
        assertTrue(plan.terminationRequested());
        assertEquals("completed", plan.terminationCause());
        assertEquals("done", plan.terminationReason());
        assertTrue(plan.changesetHandled());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.events().clear());
    }

    @Test
    void planDefensivelyCopiesAndRetainsImmutableFrozenPatches() {
        Node value = new Node().properties("status", new Node().value("original"));
        FrozenNode frozenValue = FrozenNode.fromNode(value);
        FrozenJsonPatch patch = FrozenJsonPatch.replace("/target", frozenValue);
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>();
        patches.add(patch);
        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false,
                null, null, true);

        value.getProperties().get("status").value("mutated-input");
        patches.clear();
        List<FrozenJsonPatch> firstRead = plan.patches();

        assertEquals(1, plan.patches().size());
        assertSame(patch, plan.patches().get(0),
                "immutable patches should be retained without rematerialization");
        assertSame(frozenValue, plan.patches().get(0).getValue());
        assertEquals("original",
                plan.patches().get(0).getValue().property("status").getValue());
        assertThrows(UnsupportedOperationException.class, firstRead::clear);
    }

    @Test
    void planPreservesEverySupportedPatchOperationAndRejectsNullPatches() {
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>();
        patches.add(FrozenJsonPatch.add("/added", value));
        patches.add(FrozenJsonPatch.replace("/replaced", value));
        patches.add(FrozenJsonPatch.remove("/removed"));

        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false,
                null, null, true);

        assertEquals(blue.language.processor.model.JsonPatch.Op.ADD,
                plan.patches().get(0).getOp());
        assertEquals(blue.language.processor.model.JsonPatch.Op.REPLACE,
                plan.patches().get(1).getOp());
        assertEquals(blue.language.processor.model.JsonPatch.Op.REMOVE,
                plan.patches().get(2).getOp());
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeEffectPlan(Collections.singletonList(null),
                        Collections.emptyList(), false,
                        null, null, false));
    }

    @Test
    void planCannotBeBufferedTwice() {
        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), Collections.emptyList(), false,
                null, null, false);
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        emitter.buffer(plan, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> emitter.buffer(plan, null));
        assertEquals("Compute effect plan has already been buffered", failure.getMessage());
    }

    @Test
    void emitterRejectsMissingExecutionResultOrPlan() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        ComputeResultValidationException missingResult = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(null, null, true));
        IllegalArgumentException missingPlan = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.buffer(null, null));

        assertEquals("Compute execution result is required", missingResult.getMessage());
        assertEquals("plan must not be null", missingPlan.getMessage());
    }

    @Test
    void emitterRetainsStrictFrozenBexValuesAndMaterializesComputedValuesOnce() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeResultEmitter emitter = new ComputeResultEmitter(metrics);
        FrozenNode retained = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("retained")));

        FrozenNode direct = emitter.freezePatchValue(BexValues.frozen(retained));
        FrozenNode computed = emitter.freezePatchValue(BexValues.map(
                Collections.singletonMap("kind", BexValues.scalar("computed"))));

        assertSame(retained, direct,
                "strict BEX frozen values must cross the boundary by identity");
        assertTrue(computed.isStrictCanonical());
        assertEquals("computed", computed.property("kind").getValue());
        assertEquals(1L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(1L, metrics.bexPatchNodeMaterializations());
    }

    @Test
    void emitterTreatsMissingReturnedValueAsNoActiveEffects() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        ComputeEffectPlan plan = emitter.plan(executionResult(null), null, true);

        assertTrue(plan.patches().isEmpty());
        assertTrue(plan.events().isEmpty());
        assertFalse(plan.terminationRequested());
        assertFalse(plan.changesetHandled());
    }

    @Test
    void emitterPreservesApplicationTerminationCauseAndOptionalReason() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> termination = new LinkedHashMap<String, BexValue>();
        termination.put("cause", BexValues.scalar("completed"));
        termination.put("reason", BexValues.scalar("all work applied"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("termination", BexValues.map(termination));

        ComputeEffectPlan plan = emitter.plan(
                executionResult(BexValues.map(resultValue)), null, true);

        assertTrue(plan.terminationRequested());
        assertEquals("completed", plan.terminationCause());
        assertEquals("all work applied", plan.terminationReason());
    }

    @Test
    void emitterRejectsMissingOrModeStyleTerminationCause() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> reasonOnly = new LinkedHashMap<String, BexValue>();
        reasonOnly.put("reason", BexValues.scalar("legacy"));
        Map<String, BexValue> emptyCause = new LinkedHashMap<String, BexValue>();
        emptyCause.put("cause", BexValues.scalar(""));
        Map<String, BexValue> unknownField = new LinkedHashMap<String, BexValue>();
        unknownField.put("cause", BexValues.scalar("completed"));
        unknownField.put("mode", BexValues.scalar("legacy-mode"));

        assertTerminationFailure(emitter, reasonOnly,
                "Compute result termination cause must be non-empty Text");
        assertTerminationFailure(emitter, emptyCause,
                "Compute result termination cause must be non-empty Text");
        assertTerminationFailure(emitter, unknownField,
                "Compute result termination contains unsupported properties");
    }

    @Test
    void emitterBoundsUnexpectedConversionDiagnosticsByActiveField() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        String longMessage = repeat('x', 200) + "\nnot-exposed";
        Map<String, BexValue> changesetResult = new LinkedHashMap<String, BexValue>();
        changesetResult.put("changeset", listThrowingOnSize(new IllegalStateException(longMessage)));

        ComputeResultValidationException changesetFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(changesetResult)), null, false));

        assertTrue(changesetFailure.getMessage().startsWith(
                "Compute result changeset could not be converted: "));
        assertFalse(changesetFailure.getMessage().contains("not-exposed"));
        assertTrue(changesetFailure.getCause() instanceof IllegalStateException);

        Map<String, BexValue> eventResult = new LinkedHashMap<String, BexValue>();
        eventResult.put("events", listThrowingOnSize(new IllegalStateException("event failure")));
        ComputeResultValidationException eventFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(eventResult)), null, true));
        assertEquals("Compute result events could not be converted: event failure",
                eventFailure.getMessage());

        ComputeResultValidationException effectFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(valueThrowingOnGet(
                        new IllegalStateException())), null, true));
        assertEquals("Compute result effects could not be converted: IllegalStateException",
                effectFailure.getMessage());
    }

    @Test
    void emitterReportsEventNodeConversionWithoutBuffering() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> malformedEvent = new LinkedHashMap<String, BexValue>();
        malformedEvent.put("properties", BexValues.scalar("internal"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("events", BexValues.list(Collections.singletonList(
                BexValues.map(malformedEvent))));

        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(resultValue)), null, true));

        assertEquals("Compute result event entry could not be converted", failure.getMessage());
        assertTrue(failure.getCause() instanceof RuntimeException);
    }

    @Test
    void emitterPreservesScalarAndListEventNodes() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        List<BexValue> events = Arrays.asList(
                BexValues.scalar("scalar-event"),
                BexValues.list(Arrays.asList(
                        BexValues.scalar("first"),
                        BexValues.scalar("second"))));
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("events", BexValues.list(events));

        ComputeEffectPlan plan = emitter.plan(
                executionResult(BexValues.map(resultValue)),
                null,
                true);

        assertEquals("scalar-event", plan.events().get(0).getValue());
        assertEquals("first",
                plan.events().get(1).getItems().get(0).getValue());
        assertEquals("second",
                plan.events().get(1).getItems().get(1).getValue());
    }

    @Test
    void emitterRejectsMalformedAccumulatedPatchesBeforePointerResolution() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        List<BexPatchEntry> malformed = new ArrayList<BexPatchEntry>();
        malformed.add(null);
        malformed.add(new BexPatchEntry("add", "/target", "/target", BexValues.undefined()));
        String[] expected = {
                "Compute result accumulated patch is incomplete",
                "Compute result patch value is required"
        };

        for (int i = 0; i < malformed.size(); i++) {
            BexExecutionResult result = executionResult(null,
                    new BexChangeset(Collections.singletonList(malformed.get(i))));
            ComputeResultValidationException failure = assertThrows(
                    ComputeResultValidationException.class,
                    () -> emitter.plan(result, null, false));
            assertEquals(expected[i], failure.getMessage());
        }
    }

    @Test
    void emitterRejectsNonTextPatchFieldsAndRemoveValuesBeforeBuffering() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> nonTextOp = patchValue(
                BexValues.scalar(7), BexValues.scalar("/target"),
                BexValues.scalar("value"));
        Map<String, BexValue> nonTextPath = patchValue(
                BexValues.scalar("replace"), BexValues.scalar(true),
                BexValues.scalar("value"));
        Map<String, BexValue> removeWithValue = patchValue(
                BexValues.scalar("remove"), BexValues.scalar("/target"),
                BexValues.scalar("forbidden"));

        assertChangesetFailure(emitter, nonTextOp,
                "Compute result changeset entry 0 field 'op' must be Text");
        assertChangesetFailure(emitter, nonTextPath,
                "Compute result changeset entry 0 field 'path' must be Text");
        assertChangesetFailure(emitter, removeWithValue,
                "Compute result changeset entry 0 val must be absent for remove");
    }

    @Test
    void explicitNullRemoveValueCannotMasqueradeAsAccumulatedChangeset() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> returnedRemove = patchValue(
                BexValues.scalar("remove"),
                BexValues.scalar("/target"),
                BexValues.nullValue());
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("changeset", BexValues.list(
                Collections.singletonList(
                        BexValues.map(returnedRemove))));
        BexChangeset accumulated = new BexChangeset(
                Collections.singletonList(
                        new BexPatchEntry(
                                "remove",
                                "/target",
                                "/target",
                                BexValues.undefined())));

        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(
                        executionResult(
                                BexValues.map(resultValue),
                                accumulated),
                        null,
                        false));

        assertEquals(
                "Compute result changeset entry 0 val must be absent for remove",
                failure.getMessage());
    }

    @Test
    void emitterWrapsPatchPointerResolutionFailures() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> patch = new LinkedHashMap<String, BexValue>();
        patch.put("op", BexValues.scalar("replace"));
        patch.put("path", BexValues.scalar("/target"));
        patch.put("val", BexValues.scalar("value"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("changeset", BexValues.list(Collections.singletonList(
                BexValues.map(patch))));

        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(resultValue)), null, false));

        assertEquals("Compute result patch path is invalid", failure.getMessage());
        assertTrue(failure.getCause() instanceof NullPointerException);
    }

    @Test
    void workflowStepResultFactoriesPreserveValueMetadataAndTerminalControl() {
        WorkflowStepResult none = WorkflowStepResult.none();
        WorkflowStepResult value = WorkflowStepResult.value(null, true);
        WorkflowStepResult terminal = WorkflowStepResult.terminal();
        WorkflowStepResult terminalValueWithoutChangeset = WorkflowStepResult.terminalValue("plain");
        WorkflowStepResult terminalValue = WorkflowStepResult.terminalValue("result", true);

        assertFalse(none.hasValue());
        assertFalse(none.changesetHandled());
        assertFalse(none.isTerminal());
        assertNull(none.value());

        assertTrue(value.hasValue());
        assertNull(value.value());
        assertTrue(value.changesetHandled());
        assertFalse(value.isTerminal());

        assertFalse(terminal.hasValue());
        assertFalse(terminal.changesetHandled());
        assertTrue(terminal.isTerminal());

        assertEquals("plain", terminalValueWithoutChangeset.value());
        assertFalse(terminalValueWithoutChangeset.changesetHandled());
        assertTrue(terminalValueWithoutChangeset.isTerminal());

        assertTrue(terminalValue.hasValue());
        assertEquals("result", terminalValue.value());
        assertTrue(terminalValue.changesetHandled());
        assertTrue(terminalValue.isTerminal());
    }

    @Test
    void validationExceptionAndDefaultTerminateExecutorRetainSimpleContracts() {
        IllegalStateException cause = new IllegalStateException("cause");
        ComputeResultValidationException failure =
                new ComputeResultValidationException("invalid", cause);
        TerminateProcessingStepExecutor executor = new TerminateProcessingStepExecutor();

        assertEquals("invalid", failure.getMessage());
        assertEquals(cause, failure.getCause());
        assertTrue(executor.supports(new TerminateProcessing()));
    }

    private static BexExecutionResult executionResult(BexValue value) {
        return executionResult(value, new BexChangeset(Collections.emptyList()));
    }

    private static BexExecutionResult executionResult(BexValue value, BexChangeset changeset) {
        return new BexExecutionResult(value,
                changeset,
                new BexEvents(Collections.emptyList()),
                0L,
                new BexMetrics());
    }

    private static void assertTerminationFailure(
            ComputeResultEmitter emitter,
            Map<String, BexValue> termination,
            String expectedMessage) {
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("termination", BexValues.map(termination));
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        true));
        assertEquals(expectedMessage, failure.getMessage());
    }

    private static Map<String, BexValue> patchValue(
            BexValue op,
            BexValue path,
            BexValue val) {
        Map<String, BexValue> patch = new LinkedHashMap<String, BexValue>();
        patch.put("op", op);
        patch.put("path", path);
        if (val != null) {
            patch.put("val", val);
        }
        return patch;
    }

    private static void assertChangesetFailure(
            ComputeResultEmitter emitter,
            Map<String, BexValue> patch,
            String expectedMessage) {
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("changeset", BexValues.list(
                Collections.singletonList(BexValues.map(patch))));
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        false));
        assertEquals(expectedMessage, failure.getMessage());
    }

    private static BexValue listThrowingOnSize(final RuntimeException failure) {
        return proxyValue(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("size".equals(method.getName())) {
                    throw failure;
                }
                if ("isList".equals(method.getName())) {
                    return true;
                }
                if (method.getReturnType() == Boolean.TYPE) {
                    return false;
                }
                if ("get".equals(method.getName())) {
                    return BexValues.undefined();
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static BexValue valueThrowingOnGet(final RuntimeException failure) {
        return proxyValue(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("get".equals(method.getName())) {
                    throw failure;
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static BexValue proxyValue(InvocationHandler handler) {
        return (BexValue) Proxy.newProxyInstance(
                BexValue.class.getClassLoader(), new Class<?>[]{BexValue.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        return null;
    }

    private static String repeat(char character, int count) {
        char[] value = new char[count];
        java.util.Arrays.fill(value, character);
        return new String(value);
    }
}
