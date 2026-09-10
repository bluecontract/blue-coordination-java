package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import blue.language.processor.closure.ClosureProcessResult;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

/** Production SDK gas boundaries calibrated by a fresh, fully materialized base processor. */
final class RootedGasBoundaryTest {
    @Test
    void twelveLiteralBudgetSchedulesMatchTheMaterializedReference() throws IOException {
        // given
        long gas;
        // when
        try (Scenario scenario = new Scenario()) {
            var input = scenario.control.capture(scenario.parent.id(), scenario.entry.blueId(), null);
            var reference = RootedCalculationFixture.materializedReference(input);
            // then
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic()));
            assertNull(reference.rootedProjection(), "Reference has no optimized ownership projection");
            gas = reference.totalGas();
            RootedGasEvidence.write("calibration", input, reference);
            assertTrue(gas > 1 && gas <= input.executionPolicy().sharedLimit());
            System.out.println("RCP-RUN-020 independently materialized calibration G=" + gas
                    + " trace=" + reference.gasTraceIdentity() + " charges=" + reference.gasTrace().size());
        }
        for (long delta : List.of(-1L, 0L, 1L)) {
            Boundary baseline = verify("cold", gas + delta, delta >= 0);
            for (String schedule : List.of("cached", "evicted", "source-committed")) {
                assertEquals(baseline, verify(schedule, gas + delta, delta >= 0), schedule + " delta=" + delta);
            }
        }
    }

    @Test
    void parentGasFailureAfterCommittedSourceCannotUndoSourceOrAdvanceParent() throws IOException {
        // given
        long budget = 1L;
        // when
        var result = verify("source-committed", budget, false);
        // then
        assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, result.disposition());
    }

    private static Boundary verify(String schedule, long budget, boolean succeeds) throws IOException {
        try (Scenario scenario = new Scenario()) {
            BlueCoordination blue = scenario.blue;
            String parentBefore = scenario.parent.snapshot().blueId();
            List<String> parentHistory = scenario.history(scenario.parent);
            String sourceBefore = scenario.source.snapshot().blueId();
            List<String> sourceOriginalHistory = scenario.history(scenario.source);
            if (schedule.equals("cached") || schedule.equals("evicted")) {
                var calculated = scenario.control.precompute(scenario.source.id(), scenario.entry.blueId());
                assertTrue(calculated.commits());
                assertEquals(sourceBefore, scenario.source.snapshot().blueId());
                assertEquals(sourceOriginalHistory, scenario.history(scenario.source));
                if (schedule.equals("evicted")) scenario.control.evictSnapshotCaches();
            } else if (schedule.equals("source-committed")) {
                assertEquals(EntryDisposition.APPLIED, blue.processing().process(scenario.source, scenario.entry)
                        .entry(scenario.entry).disposition());
            }
            String sourceHead = scenario.source.snapshot().blueId();
            List<String> sourceHistory = scenario.history(scenario.source);
            var policy = ContractsExecutionPolicy.exactSharedGas(budget, "rooted-tight-budget");
            var captured = scenario.control.capture(scenario.parent.id(), scenario.entry.blueId(), policy);
            var reference = RootedCalculationFixture.materializedReference(captured);
            var result = blue.advanced().process(scenario.parent, scenario.entry, policy).entry(scenario.entry);
            assertEquals(succeeds ? EntryDisposition.APPLIED : EntryDisposition.GAS_LIMIT_EXCEEDED,
                    result.disposition(), schedule + " " + budget + " " + result.diagnostic());
            String terminal = result.closures().get(0).closureId();
            var actual = blue.advanced().closureExecution(terminal).orElseThrow();
            var retainedInput = blue.advanced().closureInvocation(terminal).orElseThrow();
            assertEquals(captured.invocationIdentity(), retainedInput.invocationIdentity());
            assertEquals(captured.cause().getClass(), retainedInput.cause().getClass());
            RootedGasEvidence.write(schedule + "-" + budget + "-reference", captured, reference);
            RootedGasEvidence.write(schedule + "-" + budget + "-actual", retainedInput, actual);
            assertEquals(reference.status(), actual.status());
            assertEquals(reference.totalGas(), actual.totalGas());
            assertEquals(reference.gasTraceIdentity(), actual.gasTraceIdentity(), "Complete independently executed trace");
            assertEquals(rejected(reference), rejected(actual), "Exact rejected next charge");
            assertEquals(reference.outputClosureIdentity(), actual.outputClosureIdentity());
            assertEquals(sourceHead, scenario.source.snapshot().blueId());
            assertEquals(sourceHistory, scenario.history(scenario.source));
            if (succeeds) {
                assertEquals(1L, scenario.parent.snapshot().longAt("/seen"));
                assertEquals(1L, ((Number) blue.values().retained(scenario.parent.snapshot().valueAt("/child")
                        .blueId()).orElseThrow().scalarAt("/counter")).longValue());
                assertEquals(List.of(scenario.parent.id()), result.closures().stream()
                        .flatMap(c -> c.changes().stream()).map(DocumentChange::documentId).toList());
            } else {
                assertTrue(actual.rollbackToInput());
                assertNull(actual.commitCompanion());
                assertNull(actual.rootedProjection());
                assertNotNull(actual.rejectedCharge());
                assertTrue(actual.checkpointWrites().isEmpty());
                assertTrue(result.publicEvents().isEmpty());
                assertTrue(result.closures().stream().allMatch(c -> c.changes().isEmpty()));
                assertEquals(parentBefore, scenario.parent.snapshot().blueId());
                assertEquals(parentHistory, scenario.history(scenario.parent));
            }
            String parentHead = scenario.parent.snapshot().blueId();
            List<String> parentAfterHistory = scenario.history(scenario.parent);
            CoordinationTestControl.attach(blue.advanced().rawEngine()).restartFromStores();
            assertEquals(sourceHead, scenario.source.snapshot().blueId());
            assertEquals(sourceHistory, scenario.history(scenario.source));
            assertEquals(parentHead, scenario.parent.snapshot().blueId());
            assertEquals(parentAfterHistory, scenario.history(scenario.parent));
            assertEquals(actual.gasTraceIdentity(), blue.advanced().closureExecution(terminal).orElseThrow().gasTraceIdentity());
            System.out.println("RCP-RUN-020 schedule=" + schedule + " budget=" + budget + " result=" + result.disposition()
                    + " gas=" + actual.totalGas() + " trace=" + actual.gasTraceIdentity());
            return new Boundary(result.disposition(), actual.totalGas(), actual.gasTraceIdentity(), rejected(actual),
                    terminal, parentHead, parentAfterHistory);
        }
    }

    private static String rejected(ClosureProcessResult result) {
        return result.rejectedCharge() == null ? null : result.rejectedCharge().rejectedChargeIdentity();
    }

    private record Boundary(EntryDisposition disposition, long gas, String trace, String rejected,
                            String terminal, String parentHead, List<String> parentHistory) { }

    private static final class Scenario implements AutoCloseable {
        final BlueCoordination blue;
        final DocumentHandle source;
        final DocumentHandle parent;
        final EntryHandle entry;
        final RootedCalculationFixture control;
        Scenario() throws IOException {
            Map<String, String> exact = new LinkedHashMap<>();
            blue = BlueCoordination.builder().contentDerivedDocumentIds()
                    .release(BundledContracts10Release.manifest().blueLanguageSpecification(),
                            BundledContracts10Release.manifest().contractsSpecification())
                    .exactNodeProvider(id -> Optional.ofNullable(exact.get(id))).build();
            var timeline = blue.timelines().register("rcp2/source", "alice");
            blue.timelines().register("rcp2/parent", "alice");
            source = blue.documents().admitStaticProcessEmbedded(resource("source.yaml"),
                    ActivationPolicy.importFullHistory()).document("root");
            exact.put(source.snapshot().blueId(), source.snapshot().exact().json());
            parent = blue.documents().admitStaticProcessEmbedded(resource("parent.yaml")
                    + "\nchild:\n  blueId: " + source.snapshot().blueId() + "\n",
                    ActivationPolicy.importFullHistory()).document("root");
            entry = blue.events().from(timeline).exact(blue.values().yaml("""
                    type: Coordination/Timeline Entry
                    timeline:
                      type: MyOS/MyOS Timeline
                      timelineId: rcp2/source
                    timestamp: 100
                    actor:
                      type: MyOS/Principal Actor
                      accountId: alice
                    message:
                      type: Coordination/Operation Request
                      document:
                        blueId: %s
                      requireExactDocumentVersion: false
                      operation: tick
                      channel: owner
                      request: {}
                    """.formatted(source.snapshot().blueId()))).submit();
            control = new RootedCalculationFixture(blue.advanced().rawEngine());
        }
        List<String> history(DocumentHandle document) {
            return blue.advanced().auditManagedEpochs(document.id()).stream().map(ManagedEpochReceipt::receiptIdentity).toList();
        }
        @Override public void close() { blue.close(); }
    }

    private static String resource(String name) throws IOException {
        try (var input = RootedGasBoundaryTest.class.getResourceAsStream("/rooted/" + name)) {
            if (input == null) throw new IOException("Missing rooted input " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
