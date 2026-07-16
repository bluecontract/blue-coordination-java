package blue.coordination.processor.workflow;

import blue.bex.result.BexChangeset;
import blue.bex.result.BexEvents;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.repo.coordination.TerminateProcessing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeEffectPlanTest {
    @Test
    void planCopiesAndFreezesEventContent() {
        Node event = new Node().properties("kind", new Node().value("original"));
        List<Node> events = new ArrayList<Node>();
        events.add(event);

        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), events, true, "done", true);
        event.getProperties().get("kind").value("mutated");
        events.clear();

        assertTrue(plan.patches().isEmpty());
        assertEquals(1, plan.events().size());
        assertEquals("original", plan.events().get(0).toNode().get("/kind"));
        assertTrue(plan.terminationRequested());
        assertEquals("done", plan.terminationReason());
        assertTrue(plan.changesetHandled());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.events().clear());
    }

    @Test
    void planCopiesAndFreezesPatchContent() {
        Node value = new Node().properties("status", new Node().value("original"));
        List<JsonPatch> patches = new ArrayList<JsonPatch>();
        patches.add(JsonPatch.replace("/target", value));
        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false, null, true);

        value.getProperties().get("status").value("mutated-input");
        patches.clear();
        List<JsonPatch> firstRead = plan.patches();
        firstRead.get(0).getVal().getProperties().get("status").value("mutated-output");

        assertEquals(1, plan.patches().size());
        assertEquals("original", plan.patches().get(0).getVal().get("/status"));
        assertThrows(UnsupportedOperationException.class, firstRead::clear);
    }

    @Test
    void planPreservesEverySupportedPatchOperationAndRejectsNullPatches() {
        List<JsonPatch> patches = new ArrayList<JsonPatch>();
        patches.add(JsonPatch.add("/added", new Node().value("value")));
        patches.add(JsonPatch.replace("/replaced", new Node().value("value")));
        patches.add(JsonPatch.remove("/removed"));

        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false, null, true);

        assertEquals(JsonPatch.Op.ADD, plan.patches().get(0).getOp());
        assertEquals(JsonPatch.Op.REPLACE, plan.patches().get(1).getOp());
        assertEquals(JsonPatch.Op.REMOVE, plan.patches().get(2).getOp());
        assertThrows(IllegalArgumentException.class,
                () -> new ComputeEffectPlan(Collections.singletonList(null),
                        Collections.emptyList(), false, null, false));
    }

    @Test
    void planCannotBeBufferedTwice() {
        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), Collections.emptyList(), false, null, false);
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
    void emitterTreatsMissingReturnedValueAsNoActiveEffects() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        ComputeEffectPlan plan = emitter.plan(executionResult(null), null, true);

        assertTrue(plan.patches().isEmpty());
        assertTrue(plan.events().isEmpty());
        assertFalse(plan.terminationRequested());
        assertFalse(plan.changesetHandled());
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
