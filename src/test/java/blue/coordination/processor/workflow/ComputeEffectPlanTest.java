package blue.coordination.processor.workflow;

import blue.bex.BexException;
import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLedger;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.result.BexChangeset;
import blue.bex.result.BexEvents;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.coordination.processor.bex.BexProcessingMetrics;
import blue.bex.contracts.ProcessorExecutionContextBexGasLedgerHost;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkSession;
import blue.language.processor.RuntimeWorkSessionTestSupport;
import blue.language.processor.FrozenJsonPatch;
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
    void shouldCopyAndFreezeEventContentInEffectPlan() {
        // given
        Node event = new Node().properties("kind", new Node().value("original"));
        List<Node> events = new ArrayList<Node>();
        events.add(event);

        // when
        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), events, true,
                "completed", "done", true);
        event.getProperties().get("kind").value("mutated");
        events.clear();

        // then
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
    void shouldDefensivelyCopyPatchListAndRetainImmutableFrozenPatches() {
        // given
        Node value = new Node().properties("status", new Node().value("original"));
        FrozenNode frozenValue = FrozenNode.fromNode(value);
        FrozenJsonPatch patch = FrozenJsonPatch.replace("/target", frozenValue);
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>();
        patches.add(patch);

        // when
        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false,
                null, null, true);

        value.getProperties().get("status").value("mutated-input");
        patches.clear();
        List<FrozenJsonPatch> firstRead = plan.patches();

        // then
        assertEquals(1, plan.patches().size());
        assertSame(patch, plan.patches().get(0),
                "immutable patches should be retained without rematerialization");
        assertSame(frozenValue, plan.patches().get(0).getValue());
        assertEquals("original",
                plan.patches().get(0).getValue().property("status").getValue());
        assertThrows(UnsupportedOperationException.class, firstRead::clear);
    }

    @Test
    void shouldPreserveEverySupportedPatchOperation() {
        // given
        FrozenNode value = FrozenNode.fromNode(new Node().value("value"));
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>();
        patches.add(FrozenJsonPatch.add("/added", value));
        patches.add(FrozenJsonPatch.replace("/replaced", value));
        patches.add(FrozenJsonPatch.remove("/removed"));

        // when
        ComputeEffectPlan plan = new ComputeEffectPlan(
                patches, Collections.emptyList(), false,
                null, null, true);

        // then
        assertEquals(blue.language.processor.model.JsonPatch.Op.ADD,
                plan.patches().get(0).getOp());
        assertEquals(blue.language.processor.model.JsonPatch.Op.REPLACE,
                plan.patches().get(1).getOp());
        assertEquals(blue.language.processor.model.JsonPatch.Op.REMOVE,
                plan.patches().get(2).getOp());
    }

    @Test
    void shouldRejectNullPatchInEffectPlan() {
        // given
        List<FrozenJsonPatch> patches = Collections.singletonList(null);

        // when
        Runnable construction = () -> new ComputeEffectPlan(
                patches, Collections.emptyList(), false,
                null, null, false);

        // then
        assertThrows(IllegalArgumentException.class,
                construction::run);
    }

    @Test
    void shouldRejectBufferingSameEffectPlanTwice() {
        // given
        ComputeEffectPlan plan = new ComputeEffectPlan(
                Collections.emptyList(), Collections.emptyList(), false,
                null, null, false);
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        // when
        emitter.buffer(plan, null);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> emitter.buffer(plan, null));

        // then
        assertEquals("Compute effect plan has already been buffered", failure.getMessage());
    }

    @Test
    void shouldRejectMissingComputeExecutionResult() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        // when
        ComputeResultValidationException missingResult = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(null, null, true));

        // then
        assertEquals("Compute execution result is required", missingResult.getMessage());
    }

    @Test
    void shouldRejectMissingEffectPlanDuringBuffering() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        // when
        IllegalArgumentException missingPlan = assertThrows(
                IllegalArgumentException.class,
                () -> emitter.buffer(null, null));

        // then
        assertEquals("plan must not be null", missingPlan.getMessage());
    }

    @Test
    void shouldRetainFrozenBexValuesAndMaterializeComputedValuesOnce() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeResultEmitter emitter = new ComputeResultEmitter(metrics);
        FrozenNode retained = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("retained")));

        // when
        FrozenNode direct = emitter.freezePatchValue(BexValues.frozen(retained));
        FrozenNode computed = emitter.freezePatchValue(BexValues.map(
                Collections.singletonMap("kind", BexValues.scalar("computed"))));

        // then
        assertSame(retained, direct,
                "strict BEX frozen values must cross the boundary by identity");
        assertTrue(computed.isStrictCanonical());
        assertEquals("computed", computed.property("kind").getValue());
        assertEquals(1L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(1L, metrics.bexPatchNodeMaterializations());
    }

    @Test
    void shouldPreserveSemanticContentForAdmittedExactPatchValues() {
        // given
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeResultEmitter emitter = new ComputeResultEmitter(metrics);
        FrozenNode admittedContent = FrozenNode.fromNode(
                new Node().value("admitted"));
        BexValue admitted = BexValues.admittedExact(
                admittedContent,
                admittedContent.blueId(),
                BexValues.scalar("admitted"));

        // when
        FrozenNode frozenPatchValue = emitter.freezePatchValue(admitted);

        // then
        assertTrue(frozenPatchValue.isStrictCanonical());
        assertFalse(frozenPatchValue.isReferenceOnly());
        assertEquals("admitted", frozenPatchValue.getValue());
        assertEquals(admittedContent.blueId(), frozenPatchValue.blueId());
        assertEquals(1L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(0L, metrics.bexPatchNodeMaterializations());
    }

    @Test
    void shouldPreserveAuthenticatedExactReferencesWithoutRematerializing() {
        BexProcessingMetrics metrics = new BexProcessingMetrics();
        ComputeResultEmitter emitter = new ComputeResultEmitter(metrics);
        FrozenNode exact = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("exact")));
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(exact.blueId()));
        BexValue resolvedCursor = BexValues.exact(
                reference, exact, exact.blueId());

        FrozenNode frozen = emitter.freezePatchValue(resolvedCursor);

        assertSame(reference, frozen);
        assertTrue(frozen.isReferenceOnly());
        assertEquals(exact.blueId(), frozen.getReferenceBlueId());
        assertEquals(1L, metrics.bexPatchFrozenDirectConversions());
        assertEquals(0L, metrics.bexPatchNodeMaterializations());
    }

    @Test
    void shouldRejectExactPatchContentWithAnUnrelatedAssertedIdentity() {
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        FrozenNode content = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("content")));
        FrozenNode other = FrozenNode.fromNode(new Node()
                .properties("kind", new Node().value("other")));
        BexValue mismatched = BexValues.exact(
                content, content, other.blueId());

        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.freezePatchValue(mismatched));

        assertTrue(failure.getMessage().contains(
                "mismatched authenticated content"));
    }

    @Test
    void shouldTreatMissingReturnedValueAsNoActiveEffects() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        // when
        ComputeEffectPlan plan = emitter.plan(executionResult(null), null, true);

        // then
        assertTrue(plan.patches().isEmpty());
        assertTrue(plan.events().isEmpty());
        assertFalse(plan.terminationRequested());
        assertFalse(plan.changesetHandled());
    }

    @Test
    void shouldPreserveComputeTerminationCauseAndOptionalReason() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> termination = new LinkedHashMap<String, BexValue>();
        termination.put("cause", BexValues.scalar("completed"));
        termination.put("reason", BexValues.scalar("all work applied"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("termination", BexValues.map(termination));

        // when
        ComputeEffectPlan plan = emitter.plan(
                executionResult(BexValues.map(resultValue)), null, true);

        // then
        assertTrue(plan.terminationRequested());
        assertEquals("completed", plan.terminationCause());
        assertEquals("all work applied", plan.terminationReason());
    }

    @Test
    void shouldRejectMissingEmptyOrModeStyleComputeTerminationCause() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> reasonOnly = new LinkedHashMap<String, BexValue>();
        reasonOnly.put("reason", BexValues.scalar("legacy"));
        Map<String, BexValue> emptyCause = new LinkedHashMap<String, BexValue>();
        emptyCause.put("cause", BexValues.scalar(""));
        Map<String, BexValue> unknownField = new LinkedHashMap<String, BexValue>();
        unknownField.put("cause", BexValues.scalar("completed"));
        unknownField.put("mode", BexValues.scalar("legacy-mode"));

        // when
        List<String> messages = Arrays.asList(
                terminationFailure(emitter, reasonOnly),
                terminationFailure(emitter, emptyCause),
                terminationFailure(emitter, unknownField));

        // then
        assertEquals(Arrays.asList(
                        "Compute result termination cause must be non-empty Text",
                        "Compute result termination cause must be non-empty Text",
                        "Compute result termination contains unsupported properties"),
                messages);
    }

    @Test
    void shouldBoundUnexpectedChangesetConversionDiagnostic() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        String longMessage = repeat('x', 200) + "\nnot-exposed";
        Map<String, BexValue> changesetResult = new LinkedHashMap<String, BexValue>();
        changesetResult.put("changeset", listThrowingOnSize(new IllegalStateException(longMessage)));

        // when
        ComputeResultValidationException changesetFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(changesetResult)), null, false));

        // then
        assertTrue(changesetFailure.getMessage().startsWith(
                "Compute result changeset could not be converted: "));
        assertFalse(changesetFailure.getMessage().contains("not-exposed"));
        assertTrue(changesetFailure.getCause() instanceof IllegalStateException);
    }

    @Test
    void shouldReportUnexpectedEventConversionDiagnosticByActiveField() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> eventResult = new LinkedHashMap<String, BexValue>();
        eventResult.put("events", listThrowingOnSize(new IllegalStateException("event failure")));

        // when
        ComputeResultValidationException eventFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(eventResult)), null, true));

        // then
        assertEquals("Compute result events could not be converted: event failure",
                eventFailure.getMessage());
    }

    @Test
    void shouldReportUnexpectedEffectConversionDiagnosticByActiveField() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();

        // when
        ComputeResultValidationException effectFailure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(valueThrowingOnGet(
                        new IllegalStateException())), null, true));

        // then
        assertEquals("Compute result effects could not be converted: IllegalStateException",
                effectFailure.getMessage());
    }

    @Test
    void shouldPreserveInvalidExecutionEvidenceFromNestedResultConversion() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        InvalidExecutionEvidenceException invalidEvidence =
                new InvalidExecutionEvidenceException(
                        "forged Compute output evidence");
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put(
                "changeset",
                listThrowingOnSize(invalidEvidence));

        // when
        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> emitter.plan(
                        executionResult(
                                BexValues.map(resultValue)),
                        null,
                        false));

        // then
        assertSame(invalidEvidence, failure);
    }

    @Test
    void shouldPreserveUnavailableEvidenceFromLazyResultConversion() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        ExecutionEvidenceUnavailableException unavailable =
                new ExecutionEvidenceUnavailableException(
                        "exact Compute output evidence is unavailable");
        Map<String, BexValue> resultValue =
                resultWithChangesetThrowing(unavailable);

        // when
        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        false));

        // then
        assertSame(unavailable, failure);
    }

    @Test
    void shouldPreservePortableLimitFromLazyResultConversion() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        PortableLimitExceededException portableLimit =
                new PortableLimitExceededException(
                        "maxDirectNodes",
                        2L,
                        1L);
        Map<String, BexValue> resultValue =
                resultWithChangesetThrowing(portableLimit);

        // when
        PortableLimitExceededException failure = assertThrows(
                PortableLimitExceededException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        false));

        // then
        assertSame(portableLimit, failure);
    }

    @Test
    void shouldLetOuterProcessorFailureWinOverNestedInvalidEvidence() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        InvalidExecutionEvidenceException invalidEvidence =
                new InvalidExecutionEvidenceException(
                        "nested invalid evidence");
        ProcessorFailureException processorFailure =
                new ProcessorFailureException(
                        ProcessorErrorCategory.RuntimeExecutionFailure,
                        "authoritative processor failure",
                        invalidEvidence);
        Map<String, BexValue> resultValue =
                resultWithChangesetThrowing(processorFailure);

        // when
        ProcessorFailureException failure = assertThrows(
                ProcessorFailureException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        false));

        // then
        assertSame(processorFailure, failure);
    }

    @Test
    void shouldLookThroughGenericBexWrapperForUnavailableEvidence() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        ExecutionEvidenceUnavailableException unavailable =
                new ExecutionEvidenceUnavailableException(
                        "provider result is not available yet");
        BexException wrapper =
                new BexException("BEX value access failed", unavailable);
        Map<String, BexValue> resultValue =
                resultWithChangesetThrowing(wrapper);

        // when
        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        false));

        // then
        assertSame(unavailable, failure);
    }

    @Test
    void shouldRecoverInvalidExecutionEvidenceWrappedForExecutorHandling() {
        // given
        InvalidExecutionEvidenceException invalidEvidence =
                new InvalidExecutionEvidenceException(
                        "invalid verified provider result");
        ComputeResultValidationException converted =
                new ComputeResultValidationException(
                        "converted result",
                        new IllegalStateException(
                                "writer boundary",
                                invalidEvidence));

        // when
        RuntimeException recovered =
                ComputeStepExecutor.classifiedBoundaryFailure(converted);

        // then
        assertSame(invalidEvidence, recovered);
    }

    @Test
    void shouldPreserveDirectLanguageGasExhaustion() {
        // given
        Map<String, Long> weights =
                Collections.singletonMap("unit", 1L);
        GasMeter.ChildGasLedger ledger =
                new GasMeter(GasSchedule.contracts10(), 0L)
                        .childLedger("compute-test", weights);

        // when
        GasLimitExceededException exhaustion = assertThrows(
                GasLimitExceededException.class,
                () -> ledger.charge("unit", 1L));
        RuntimeException classified =
                ComputeStepExecutor.classifiedBoundaryFailure(exhaustion);

        // then
        assertSame(exhaustion, classified);
    }

    @Test
    void shouldPreserveHostedBexGasExhaustionAsLanguageBoundary() {
        // given
        BexGasSchedule schedule = BexGasSchedule.defaults();
        long hostBudget =
                schedule.weight(BexGasCounter.EXPRESSION_EVALUATED);
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(), hostBudget);
        RuntimeWorkSession session =
                RuntimeWorkSessionTestSupport.processing(parent);
        BexGasLedgerCapability hostLedger =
                new ProcessorExecutionContextBexGasLedgerHost(
                        session, "compute-test")
                        .open(
                                BexGasCounter.NAMESPACE,
                                BexGasMeter.childLedgerWeights(
                                        schedule,
                                        Collections.<String, Long>emptyMap()));
        BexGasMeter meter = new BexGasMeter(schedule, hostLedger);
        hostLedger.charge(
                BexGasCounter.EXPRESSION_EVALUATED.canonicalName(),
                1L);

        // when
        BexGasLimitExceededException exhaustion = assertThrows(
                BexGasLimitExceededException.class,
                () -> meter.charge(
                        BexGasCounter.EXPRESSION_EVALUATED,
                        1L));
        RuntimeException classified =
                ComputeStepExecutor.classifiedBoundaryFailure(exhaustion);

        // then
        assertSame(
                exhaustion.hostGasExhaustion().hostFailure(),
                classified);
    }

    @Test
    void shouldMapLocalBexGasExhaustionToProcessorFailure() {
        // given
        BexGasMeter meter =
                new BexGasMeter(BexGasSchedule.defaults(), 0L);

        // when
        BexGasLimitExceededException exhaustion = assertThrows(
                BexGasLimitExceededException.class,
                () -> meter.charge(
                        BexGasCounter.EXPRESSION_EVALUATED,
                        1L));
        RuntimeException classified =
                ComputeStepExecutor.classifiedBoundaryFailure(exhaustion);

        // then
        assertTrue(classified instanceof ProcessorFailureException);
        ProcessorFailureException processorFailure =
                (ProcessorFailureException) classified;
        assertEquals(
                ProcessorErrorCategory.GasLimitExceeded,
                processorFailure.errorCategory());
        assertSame(exhaustion, processorFailure.getCause());
    }

    @Test
    void shouldReportEventNodeConversionFailureWithoutBuffering() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> malformedEvent = new LinkedHashMap<String, BexValue>();
        malformedEvent.put("properties", BexValues.scalar("internal"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("events", BexValues.list(Collections.singletonList(
                BexValues.map(malformedEvent))));

        // when
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(resultValue)), null, true));

        // then
        assertEquals("Compute result event entry could not be converted", failure.getMessage());
        assertTrue(failure.getCause() instanceof RuntimeException);
    }

    @Test
    void shouldPreserveScalarAndListEventNodes() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        List<BexValue> events = Arrays.asList(
                BexValues.scalar("scalar-event"),
                BexValues.list(Arrays.asList(
                        BexValues.scalar("first"),
                        BexValues.scalar("second"))));
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("events", BexValues.list(events));

        // when
        ComputeEffectPlan plan = emitter.plan(
                executionResult(BexValues.map(resultValue)),
                null,
                true);

        // then
        assertEquals("scalar-event", plan.events().get(0).getValue());
        assertEquals("first",
                plan.events().get(1).getItems().get(0).getValue());
        assertEquals("second",
                plan.events().get(1).getItems().get(1).getValue());
    }

    @Test
    void shouldRetainLocallyVerifiedExactEventContentForSameInvocationRouting() {
        // given
        ComputeResultEmitter emitter =
                new ComputeResultEmitter();
        FrozenNode exactEvent =
                FrozenNode.fromResolvedNode(
                        new Node().properties(
                                "kind",
                                new Node().value(
                                        "nested-compute-event")));
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put(
                "events",
                BexValues.list(
                        Collections.singletonList(
                                BexValues.frozen(
                                        exactEvent))));

        // when
        ComputeEffectPlan plan = emitter.plan(
                executionResult(
                        BexValues.map(
                                resultValue)),
                null,
                true);

        // then
        assertFalse(
                plan.events().get(0).isReferenceOnly(),
                "same-invocation routing needs the locally verified event body");
        assertEquals(
                "nested-compute-event",
                plan.events().get(0)
                        .property("kind")
                        .getValue());
    }

    @Test
    void shouldRejectMalformedAccumulatedPatchesBeforePointerResolution() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        List<BexPatchEntry> malformed = new ArrayList<BexPatchEntry>();
        malformed.add(null);
        malformed.add(new BexPatchEntry("add", "/target", "/target", BexValues.undefined()));
        String[] expected = {
                "Compute result accumulated patch is incomplete",
                "Compute result patch value is required"
        };

        // when
        List<String> messages = new ArrayList<String>();
        for (int i = 0; i < malformed.size(); i++) {
            BexExecutionResult result = executionResult(null,
                    new BexChangeset(Collections.singletonList(malformed.get(i))));
            ComputeResultValidationException failure = assertThrows(
                    ComputeResultValidationException.class,
                    () -> emitter.plan(result, null, false));
            messages.add(failure.getMessage());
        }

        // then
        assertEquals(Arrays.asList(expected), messages);
    }

    @Test
    void shouldRejectNonTextPatchFieldsAndRemoveValuesBeforeBuffering() {
        // given
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

        // when
        List<String> messages = Arrays.asList(
                changesetFailure(emitter, nonTextOp),
                changesetFailure(emitter, nonTextPath),
                changesetFailure(emitter, removeWithValue));

        // then
        assertEquals(Arrays.asList(
                        "Compute result changeset entry 0 field 'op' must be Text",
                        "Compute result changeset entry 0 field 'path' must be Text",
                        "Compute result changeset entry 0 val must be absent for remove"),
                messages);
    }

    @Test
    void shouldNotTreatExplicitNullRemoveValueAsAccumulatedChangeset() {
        // given
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

        // when
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(
                        executionResult(
                                BexValues.map(resultValue),
                                accumulated),
                        null,
                        false));

        // then
        assertEquals(
                "Compute result changeset entry 0 val must be absent for remove",
                failure.getMessage());
    }

    @Test
    void shouldWrapPatchPointerResolutionFailure() {
        // given
        ComputeResultEmitter emitter = new ComputeResultEmitter();
        Map<String, BexValue> patch = new LinkedHashMap<String, BexValue>();
        patch.put("op", BexValues.scalar("replace"));
        patch.put("path", BexValues.scalar("/target"));
        patch.put("val", BexValues.scalar("value"));
        Map<String, BexValue> resultValue = new LinkedHashMap<String, BexValue>();
        resultValue.put("changeset", BexValues.list(Collections.singletonList(
                BexValues.map(patch))));

        // when
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(executionResult(BexValues.map(resultValue)), null, false));

        // then
        assertEquals("Compute result patch path is invalid", failure.getMessage());
        assertTrue(failure.getCause() instanceof NullPointerException);
    }

    @Test
    void shouldCreateEmptyNonTerminalWorkflowStepResult() {
        // given
        WorkflowStepResult none = WorkflowStepResult.none();

        // when
        Object value = none.value();

        // then
        assertFalse(none.hasValue());
        assertFalse(none.changesetHandled());
        assertFalse(none.isTerminal());
        assertNull(value);
    }

    @Test
    void shouldPreserveValueMetadataInWorkflowStepResult() {
        // given
        WorkflowStepResult result = WorkflowStepResult.value(null, true);

        // when
        Object value = result.value();

        // then
        assertTrue(result.hasValue());
        assertNull(value);
        assertTrue(result.changesetHandled());
        assertFalse(result.isTerminal());
    }

    @Test
    void shouldCreateTerminalWorkflowStepResultsWithOptionalValues() {
        // given
        WorkflowStepResult terminal = WorkflowStepResult.terminal();
        WorkflowStepResult terminalValueWithoutChangeset = WorkflowStepResult.terminalValue("plain");
        WorkflowStepResult terminalValue = WorkflowStepResult.terminalValue("result", true);

        // when
        List<Boolean> terminalFlags = Arrays.asList(
                terminal.isTerminal(),
                terminalValueWithoutChangeset.isTerminal(),
                terminalValue.isTerminal());

        // then
        assertEquals(Arrays.asList(true, true, true), terminalFlags);
        assertFalse(terminal.hasValue());
        assertFalse(terminal.changesetHandled());

        assertEquals("plain", terminalValueWithoutChangeset.value());
        assertFalse(terminalValueWithoutChangeset.changesetHandled());

        assertTrue(terminalValue.hasValue());
        assertEquals("result", terminalValue.value());
        assertTrue(terminalValue.changesetHandled());
    }

    @Test
    void shouldPreserveValidationExceptionMessageAndCause() {
        // given
        IllegalStateException cause = new IllegalStateException("cause");

        // when
        ComputeResultValidationException failure =
                new ComputeResultValidationException("invalid", cause);

        // then
        assertEquals("invalid", failure.getMessage());
        assertEquals(cause, failure.getCause());
    }

    @Test
    void shouldSupportTerminateProcessingWithDefaultExecutor() {
        // given
        TerminateProcessingStepExecutor executor = new TerminateProcessingStepExecutor();

        // when
        boolean supported = executor.supports(new TerminateProcessing());

        // then
        assertTrue(supported);
    }

    private static BexExecutionResult executionResult(BexValue value) {
        return executionResult(value, new BexChangeset(Collections.emptyList()));
    }

    private static BexExecutionResult executionResult(BexValue value, BexChangeset changeset) {
        return new BexExecutionResult(value,
                changeset,
                new BexEvents(Collections.emptyList()),
                BexGasLedger.empty(),
                null);
    }

    private static String terminationFailure(
            ComputeResultEmitter emitter,
            Map<String, BexValue> termination) {
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put("termination", BexValues.map(termination));
        ComputeResultValidationException failure = assertThrows(
                ComputeResultValidationException.class,
                () -> emitter.plan(
                        executionResult(BexValues.map(resultValue)),
                        null,
                        true));
        return failure.getMessage();
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

    private static Map<String, BexValue> resultWithChangesetThrowing(
            RuntimeException failure) {
        Map<String, BexValue> resultValue =
                new LinkedHashMap<String, BexValue>();
        resultValue.put(
                "changeset",
                listThrowingOnSize(failure));
        return resultValue;
    }

    private static String changesetFailure(
            ComputeResultEmitter emitter,
            Map<String, BexValue> patch) {
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
        return failure.getMessage();
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
