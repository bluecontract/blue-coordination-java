package blue.language.processor;

import blue.coordination.processor.CoordinationRuntimeGas;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact integration checks between the Coordination gas adapter and the
 * processor-owned runtime work session.
 */
final class CoordinationRuntimeGasIntegrationTest {

    @Test
    void shouldEmitEveryPortableCoordinationCounterInManifestOrder() {
        // Given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        Map<String, Long> catalog =
                CoordinationRuntimeGas.counterWeights();

        // When
        int index = 0;
        for (Map.Entry<String, Long> counter
                : catalog.entrySet()) {
            CoordinationRuntimeGas.charge(
                    session,
                    counter.getKey(),
                    1L,
                    GasChargeContext.of(
                            "/",
                            "gas-fixture",
                            null,
                            counter.getKey()));
            index++;
        }
        session.complete();

        // Then
        assertEquals(14, index);
        assertEquals(catalog.size(), parent.trace().size());
        long expectedTotal = 0L;
        int traceIndex = 0;
        for (Map.Entry<String, Long> counter
                : catalog.entrySet()) {
            GasTraceEntry entry =
                    parent.trace().get(traceIndex);
            assertEquals(
                    String.format(
                            java.util.Locale.ROOT,
                            "coordination.%08d",
                            Integer.valueOf(traceIndex)),
                    entry.namespace());
            assertEquals(counter.getKey(), entry.counter());
            assertEquals(1L, entry.quantity());
            assertEquals(
                    counter.getValue().longValue(),
                    entry.weight());
            assertEquals(
                    counter.getValue().longValue(),
                    entry.subtotal());
            assertEquals("/", entry.scopePath());
            assertEquals(
                    "gas-fixture",
                    entry.contractKey());
            assertEquals(
                    counter.getKey(),
                    entry.reason());
            expectedTotal += counter.getValue().longValue();
            traceIndex++;
        }
        assertEquals(expectedTotal, parent.totalGas());
    }

    @Test
    void shouldRetainAdmittedPrefixAndOmitRejectedCoordinationCharge() {
        // Given
        GasMeter parent =
                new GasMeter(
                        GasSchedule.contracts10(),
                        1L);
        RuntimeWorkSession session = processing(parent);
        CoordinationRuntimeGas.charge(
                session,
                "timelineHeaderRead",
                1L,
                GasChargeContext.reason("admitted"));

        // When
        GasLimitExceededException rejected =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> CoordinationRuntimeGas.charge(
                                session,
                                "timelineBindingCompared",
                                1L,
                                GasChargeContext.reason(
                                        "must-not-appear")));
        GasLimitExceededException propagated =
                assertThrows(
                        GasLimitExceededException.class,
                        () -> session.propagateGasExhaustion(
                                rejected));

        // Then
        assertSame(rejected, propagated);
        assertEquals(1L, parent.totalGas());
        assertEquals(1, parent.trace().size());
        assertEquals(
                "timelineHeaderRead",
                parent.trace().get(0).counter());
        assertEquals(
                "admitted",
                parent.trace().get(0).reason());
    }

    @Test
    void shouldDiscardStagedCoordinationGasWhenEvidenceIsUnavailable() {
        // Given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        CoordinationRuntimeGas.charge(
                session,
                "operationRequestFieldRead",
                3L,
                GasChargeContext.reason(
                        "transient-attempt"));
        List<GasTraceEntry> staged =
                session.stagedTrace();

        // When
        session.suspend();

        // Then
        assertEquals(1, staged.size());
        assertEquals(0L, parent.totalGas());
        assertTrue(parent.trace().isEmpty());
    }

    @Test
    void shouldProduceTheSameLogicalTraceForEquivalentRuntimeSessions() {
        // Given
        GasMeter inlineParent = new GasMeter();
        GasMeter referencedParent = new GasMeter();

        // When
        runCompositeWork(processing(inlineParent));
        runCompositeWork(processing(referencedParent));

        // Then
        assertEquals(
                fingerprint(inlineParent.trace()),
                fingerprint(referencedParent.trace()));
        assertEquals(
                inlineParent.totalGas(),
                referencedParent.totalGas());
    }

    @Test
    void shouldRejectUnknownCounterBeforeAnyGasIsAdmitted() {
        // Given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        CoordinationRuntimeGas.Ledger ledger =
                CoordinationRuntimeGas.open(session);

        // When
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ledger.charge(
                                "not-a-coordination-counter",
                                1L,
                                GasChargeContext.empty()));
        session.suspend();

        // Then
        assertTrue(
                failure.getMessage().contains(
                        "Unknown Coordination gas counter"));
        assertEquals(0L, parent.totalGas());
        assertTrue(parent.trace().isEmpty());
    }

    private static void runCompositeWork(
            RuntimeWorkSession session) {
        CoordinationRuntimeGas.Ledger workflow =
                CoordinationRuntimeGas.open(session);
        workflow.charge(
                "workflowStepVisited",
                2L,
                GasChargeContext.reason("visit"));
        workflow.charge(
                "workflowStepExecuted",
                2L,
                GasChargeContext.reason("execute"));
        workflow.charge(
                "triggerEventStep",
                1L,
                GasChargeContext.reason("trigger"));
        workflow.submit();
        CoordinationRuntimeGas.charge(
                session,
                "operationCandidateTested",
                3L,
                GasChargeContext.reason("route"));
        session.complete();
    }

    private static List<String> fingerprint(
            List<GasTraceEntry> trace) {
        List<String> result =
                new ArrayList<String>(trace.size());
        for (GasTraceEntry entry : trace) {
            result.add(
                    entry.namespace()
                            + "|" + entry.counter()
                            + "|" + entry.quantity()
                            + "|" + entry.weight()
                            + "|" + entry.subtotal()
                            + "|" + entry.reason());
        }
        return result;
    }

    private static RuntimeWorkSession processing(
            GasMeter parent) {
        return new RuntimeWorkSession(
                parent,
                RuntimeWorkSession.Mode.PROCESSING);
    }
}
