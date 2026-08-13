package blue.coordination.processor.workflow;

import blue.bex.gas.BexGasCounter;
import blue.bex.gas.BexGasLedgerCapability;
import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.gas.BexSharedGasBudget;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasMeter;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.RuntimeWorkSession;
import blue.language.processor.RuntimeWorkSessionTestSupport;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorkflowBexGasLedgerHostTest {

    @Test
    void shouldPropagateParentBoundExhaustionAfterEarlierCompute() {
        // given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                10L);
        RuntimeWorkSession session =
                RuntimeWorkSessionTestSupport.processing(parent);
        WorkflowBexGasLedgerHost host =
                new WorkflowBexGasLedgerHost(session);
        BexGasSchedule schedule = BexGasSchedule.defaults();
        BexSharedGasBudget firstBudget =
                host.openSharedBudget(100L);
        BexGasLedgerCapability firstLedger =
                host.open(
                        BexGasCounter.NAMESPACE,
                        schedule.counterWeights(),
                        firstBudget);
        BexGasMeter first =
                BexGasMeter.hostedWithSharedLocalLimit(
                        schedule,
                        Collections.singletonMap(
                                BexGasCounter.NAMESPACE,
                                firstLedger),
                        100L,
                        Collections.<String, Long>emptyMap());
        first.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                4L);
        first.submitHostLedger(host::submit);

        BexSharedGasBudget secondBudget =
                host.openSharedBudget(100L);
        BexGasLedgerCapability secondLedger =
                host.open(
                        BexGasCounter.NAMESPACE,
                        schedule.counterWeights(),
                        secondBudget);
        BexGasMeter second =
                BexGasMeter.hostedWithSharedLocalLimit(
                        schedule,
                        Collections.singletonMap(
                                BexGasCounter.NAMESPACE,
                                secondLedger),
                        100L,
                        Collections.<String, Long>emptyMap());
        second.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                4L);
        BexGasLimitExceededException local =
                assertThrows(
                        BexGasLimitExceededException.class,
                        () -> second.charge(
                                BexGasCounter.EXPRESSION_EVALUATED,
                                3L));
        // when
        GasLimitExceededException propagated =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> second.propagateHostGasExhaustion(
                                local.hostGasExhaustion(),
                                host::failedDeterministically,
                                host::propagateGasExhaustion));
        host.submitToParent();

        // then
        assertNotSame(firstLedger, secondLedger);
        assertEquals(
                "bex.workflow.00000000.compute.00000001",
                propagated.namespace());
        assertEquals(
                BexGasCounter.EXPRESSION_EVALUATED
                        .canonicalName(),
                propagated.counter());
        assertEquals(3L, propagated.quantity());
        assertEquals(4L, propagated.admittedGas());
        assertEquals(6L, propagated.effectiveBudget());
        assertEquals(8L, parent.totalGas());
        assertEquals(2, parent.trace().size());
        assertEquals(4L, parent.trace().get(0).quantity());
        assertEquals(4L, parent.trace().get(1).quantity());
    }

    @Test
    void shouldPropagateSharedLocalExhaustionAfterIntrinsicGas() {
        // given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                100L);
        RuntimeWorkSession session =
                RuntimeWorkSessionTestSupport.processing(parent);
        WorkflowBexGasLedgerHost host =
                new WorkflowBexGasLedgerHost(session);
        BexGasSchedule schedule = BexGasSchedule.defaults();
        BexSharedGasBudget sharedBudget =
                host.openSharedBudget(10L);
        BexGasLedgerCapability primary =
                host.open(
                        BexGasCounter.NAMESPACE,
                        schedule.counterWeights(),
                        sharedBudget);
        Map<String, Long> intrinsicWeights =
                Collections.singletonMap(
                        "operation",
                        Long.valueOf(1L));
        BexGasLedgerCapability intrinsic =
                host.open(
                        "test-intrinsic",
                        intrinsicWeights,
                        sharedBudget);
        Map<String, BexGasLedgerCapability> children =
                new LinkedHashMap<
                        String,
                        BexGasLedgerCapability>();
        children.put(BexGasCounter.NAMESPACE, primary);
        children.put("test-intrinsic", intrinsic);
        Map<String, Long> registered =
                Collections.singletonMap(
                        BexGasMeter.qualifiedCounterName(
                                "test-intrinsic",
                                "operation"),
                        Long.valueOf(1L));
        BexGasMeter meter =
                BexGasMeter.hostedWithSharedLocalLimit(
                        schedule,
                        children,
                        10L,
                        registered);
        meter.chargeNamed(
                "test-intrinsic",
                "operation",
                4L);
        meter.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                4L);
        BexGasLimitExceededException local =
                assertThrows(
                        BexGasLimitExceededException.class,
                        () -> meter.charge(
                                BexGasCounter.EXPRESSION_EVALUATED,
                                3L));
        // when
        GasLimitExceededException propagated =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> meter.propagateHostGasExhaustion(
                                local.hostGasExhaustion(),
                                host::failedDeterministically,
                                host::propagateGasExhaustion));
        host.submitToParent();

        // then
        assertEquals(
                "bex.workflow.00000000.compute.00000000",
                propagated.namespace());
        assertEquals(8L, propagated.admittedGas());
        assertEquals(10L, propagated.effectiveBudget());
        assertEquals(8L, parent.totalGas());
        assertEquals(2, parent.trace().size());
        assertEquals(
                "bex.workflow.00000000.compute.00000000",
                parent.trace().get(0).namespace());
        assertEquals(
                "bex.workflow.00000000.compute.00000000/test-intrinsic",
                parent.trace().get(1).namespace());
        assertEquals(4L, parent.trace().get(0).quantity());
        assertEquals(4L, parent.trace().get(1).quantity());
    }

    @Test
    void shouldKeepStrictLocalBexLimitAsDeterministicFailure() {
        // given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                100L);
        RuntimeWorkSession session =
                RuntimeWorkSessionTestSupport.processing(parent);
        WorkflowBexGasLedgerHost host =
                new WorkflowBexGasLedgerHost(session);
        BexGasSchedule schedule = BexGasSchedule.defaults();
        BexGasLedgerCapability ledger =
                host.open(
                        BexGasCounter.NAMESPACE,
                        schedule.counterWeights());
        BexGasMeter meter =
                new BexGasMeter(
                        schedule,
                        ledger,
                        5L);
        meter.charge(
                BexGasCounter.EXPRESSION_EVALUATED,
                4L);
        BexGasLimitExceededException local =
                assertThrows(
                        BexGasLimitExceededException.class,
                        () -> meter.charge(
                                BexGasCounter.EXPRESSION_EVALUATED,
                                2L));
        meter.failHostLedger(
                host::failedDeterministically);

        // when
        RuntimeException mapped =
                host.localGasLimitExceeded(
                        local,
                        local);
        host.submitToParent();
        RuntimeWorkSessionTestSupport
                .failDeterministically(session);

        // then
        assertTrue(mapped instanceof ProcessorFailureException);
        ProcessorFailureException failure =
                (ProcessorFailureException) mapped;
        assertEquals(
                ProcessorErrorCategory.GasLimitExceeded,
                failure.errorCategory());
        assertSame(local, failure.getCause());
        assertEquals(4L, parent.totalGas());
        assertEquals(1, parent.trace().size());
    }
}
