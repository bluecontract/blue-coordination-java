package blue.coordination.sdk;

import blue.coordination.api.ManagedCatchUpStatus;
import blue.coordination.api.ManagedOccurrenceCatchUpPlan;
import blue.coordination.internal.CoordinationTestControl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public reconnect: retired source proof is retained, but cannot freeze a peer's causal acquisition. */
final class RootedDormantPeerReconnectTest {
    private static final List<String> NAMES = List.of("A", "B", "D", "C");
    private static final String ORIGINAL_NAMESPACE = "witness-forwarding";

    @Test
    void reconnectWithDormantSourceProofStillSettlesBothIndependentReceivers() throws Exception {
        // given
        // A real earlier C-to-A attachment was separately detached before
        // the acyclic diamond prefix; no row or snapshot is fabricated.
        // when
        // Normal public scheduling processes the original reconnect.
        var completed = run(ORIGINAL_NAMESPACE);
        // then
        // run requires every original reaction, frozen history, exact gas
        // receipt, and quiescent restart; BLOCKED is not an accepted outcome.
        assertEquals(4, completed.documents().size());
    }

    private static Outcome run(String namespace) throws Exception {
        boolean emitTokens = true;
        try (var s = new Scenario(namespace, emitTokens)) {
            var entry = s.f.append(s.root("C"), s.timeline, "attach", 300, attachment("a", s.root("A")), true);
            s.inspectDormantInput(entry);

            boolean quiet = false;
            for (int step = 0; step < 160; step++) {
                var next = s.f.blue.processing().drain(new DrainBudget(1, 1));
                s.observe(next);
                assertFalse(next.blocked(), "Normal diamond selection stalled at step " + step + ": " + next.diagnostic());
                if (next.quiescent()) { quiet = true; break; }
            }
            assertTrue(quiet, "The normal selector did not settle the original diamond");
            assertNotNull(s.frozenPlan, "The real C-to-A frozen history plan was never observed");
            assertEquals(emitTokens ? List.of(0L, 1L, 1L, 2L) : List.of(0L, 0L, 0L, 0L),
                    NAMES.stream().map(s::observed).toList());
            long tokenCount = emitTokens ? 1L : 0L;
            assertEquals(tokenCount, s.tokens("A", "C", "B"));
            assertEquals(tokenCount, s.tokens("A", "C", "D"));
            assertEquals(tokenCount, s.tokens("C", "B", "stop"));
            assertEquals(tokenCount, s.tokens("C", "D", "stop"));
            assertEquals(emitTokens ? List.of(2L, 0L, 0L, 2L) : List.of(0L, 0L, 0L, 0L),
                    NAMES.stream().map(s::eventCount).toList());
            for (String name : NAMES) {
                var history = s.f.history(s.root(name));
                var prefix = s.prefixes.get(name);
                assertEquals(prefix, history.subList(0, prefix.size()), "Retained prefix changed for " + name);
                assertTrue(s.f.blue.advanced().auditManagedDocumentReadiness(s.root(name).id()).orElseThrow().ready());
                assertTrue(s.plans(name).stream().allMatch(plan -> plan.status() == ManagedCatchUpStatus.COMPLETE));
                for (var receipt : s.f.blue.advanced().auditManagedEpochs(s.root(name).id())) {
                    if (prefix.contains(receipt.receiptIdentity())) continue;
                    assertTrue(!receipt.emittedEvents().isEmpty()
                                    || receipt.beforeBlueId().filter(before -> !before.equals(receipt.afterBlueId())).isPresent(),
                            "No numbered epoch may be fabricated just to fill the joint result: " + receipt.receiptIdentity());
                }
            }

            var settled = s.state();
            assertFalse(s.retainedGas.isEmpty(), "No exact successful publication gas was observed");
            var retainedGas = Map.copyOf(s.retainedGas);
            assertTrue(s.aggregateCallGas > 0L, "No successful drain call gas was observed");
            long aggregateCallGas = s.aggregateCallGas;
            CoordinationTestControl.attach(s.f.blue.advanced().rawEngine()).restartFromStores();
            assertEquals(settled, s.state());
            var restarted = s.f.blue.processing().drain(new DrainBudget(1, 1));
            assertTrue(restarted.quiescent(), String.valueOf(restarted.diagnostic()));
            assertEquals(0L, restarted.stats().committedTransitions());
            assertEquals(0L, restarted.stats().gas());
            s.observe(restarted);
            assertEquals(settled, s.state());
            assertEquals(retainedGas, s.retainedGas);
            assertEquals(aggregateCallGas, s.aggregateCallGas);
            Map<String, String> ids = new LinkedHashMap<>();
            NAMES.forEach(name -> ids.put(name, s.root(name).id().value()));
            return new Outcome(entry.blueId(), Map.copyOf(ids), settled, retainedGas, aggregateCallGas);
        }
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f = new RootedSdkFixture();
        final String timeline;
        final Map<String, DocumentHandle> roots = new LinkedHashMap<>();
        final Map<String, List<String>> prefixes = new LinkedHashMap<>();
        final Map<String, GasEvidence> retainedGas = new TreeMap<>();
        final long sourceEpoch;
        final String sourceReceipt;
        final String sourceBlueId;
        FrozenPlan frozenPlan;
        ManagedOccurrenceAudit retired;
        long aggregateCallGas;

        Scenario(String namespace, boolean emitTokens) throws Exception {
            timeline = namespace + "/alice";
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : NAMES) {
                String authored = authored(template, name, namespace);
                var exact = f.blue.values().yaml(authored);
                f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(authored, timeline));
            }
            prefix("C", "attach", 10, attachment("a", root("A")));
            prefix("C", "detach", 20, "edge: a");
            retired = f.blue.advanced().auditManagedOccurrence(root("C").id(), "/peers/a").orElseThrow();
            assertFalse(retired.active());
            assertEquals(root("A").id(), retired.targetDocumentId());
            prefix("D", "attach", 100, attachment("c", root("C")));
            prefix("B", "attach", 125, attachment("c", root("C")));
            prefix("A", "attach", 150, attachment("b", root("B")));
            prefix("A", "attach", 175, attachment("d", root("D")));
            if (emitTokens) {
                prefix("A", "emit", 200, "to: C\nnext: B");
                prefix("A", "emit", 210, "to: C\nnext: D");
            }
            prefix("A", "touch", 250, "{}");
            sourceEpoch = f.blue.advanced().auditDocument(root("A").id()).epoch();
            for (String name : NAMES) prefixes.put(name, f.history(root(name)));
            var source = f.blue.advanced().auditManagedEpoch(root("A").id(), sourceEpoch).orElseThrow();
            sourceReceipt = source.receiptIdentity(); sourceBlueId = source.afterBlueId();
        }

        void inspectDormantInput(EntryHandle entry) {
            // This is a read-only production capture, not a replacement input or
            // forced peer publication. Its identities describe the new case.
            var input = f.control.capture(root("B").id(), entry.blueId(), null);
            var a = input.snapshot().managedDocument(
                    new blue.language.processor.closure.DocumentId(root("A").id().value()));
            var d = input.snapshot().managedDocument(
                    new blue.language.processor.closure.DocumentId(root("D").id().value()));
            assertNotNull(a, "The reconnect control must carry the retired source's exact primary");
            assertNotNull(d, "The retired source proof must disclose the independent peer before PROCESS");
            var row = input.snapshot().occurrences().stream()
                    .filter(value -> value.sourceDocumentId().value().equals(root("C").id().value())
                            && value.sourcePath().equals("/peers/a")).findFirst().orElseThrow();
            assertFalse(row.active());
            assertNull(row.pendingHistoricalEpoch());
            assertEquals(retired.activationGeneration(), row.activationGeneration());
            assertEquals(sourceEpoch, a.epoch());
            assertEquals(sourceBlueId, a.blueId());
            System.out.println("DORMANT_RECONNECT_INPUT invocation=" + input.invocationIdentity()
                    + " source=" + a.epoch() + ":" + a.blueId()
                    + " peer=" + d.epoch() + ":" + d.blueId()
                    + " retirementGeneration=" + retired.activationGeneration());
        }

        DocumentHandle root(String name) { return roots.get(name); }

        void prefix(String name, String operation, long time, String request) {
            var entry = f.append(root(name), timeline, operation, time, request, true);
            var first = f.blue.processing().process(root(name), entry);
            assertEquals(EntryDisposition.APPLIED, first.entry(entry).disposition());
            for (int step = 0; step < 16; step++) {
                var next = f.blue.processing().processNext(root(name));
                assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
                if (next.quiescent()) return;
            }
            fail("Unchanged prefix did not settle for " + name + " at " + time);
        }

        void observe(DrainResult drain) {
            // Readiness describes remaining work; this call may have published
            // a genuine prefix before discovering that the next step is blocked.
            aggregateCallGas = Math.addExact(aggregateCallGas, drain.stats().gas());
            var source = f.blue.advanced().auditManagedEpoch(root("A").id(), sourceEpoch).orElseThrow();
            assertEquals(sourceReceipt, source.receiptIdentity());
            assertEquals(sourceBlueId, source.afterBlueId());
            for (var plan : plans("C")) if (plan.sourceDocumentId().equals(root("A").id())
                    && plan.activationGeneration() == retired.activationGeneration()) {
                var captured = FrozenPlan.from(plan);
                if (frozenPlan == null) frozenPlan = captured;
                assertEquals(frozenPlan, captured, "Scheduling changed C's original frozen source interval");
                assertEquals(sourceEpoch, plan.requiredThroughSourceEpoch());
                assertEquals(retired.activationGeneration(), plan.activationGeneration(),
                        "Reattachment uses the generation reserved by the separately committed detach");
                if (plan.status() != ManagedCatchUpStatus.COMPLETE) {
                    assertTrue(plan.nextSourceEpoch() <= sourceEpoch);
                    var head = f.blue.advanced().auditDocument(root("A").id());
                    assertEquals(sourceEpoch, head.epoch(), "The peer prerequisite must not execute a later A frontier");
                    assertEquals(sourceBlueId, head.blueId());
                }
            }
            var closures = new ArrayList<ClosureResult>(drain.rootedRetainedResults());
            drain.entries().forEach(entry -> closures.addAll(entry.closures()));
            for (var closure : closures) if (closure.applied()) {
                var result = f.blue.advanced().closureExecution(closure.closureId()).orElseThrow();
                assertTrue(result.commits());
                retain(result.invocationIdentity(), new GasEvidence(result.outputClosureIdentity(),
                        result.totalGas(), result.gasTraceIdentity()));
            }
            for (var application : drain.managedEpochApplicationAttempts()) if (application.published()) {
                var result = application.attempt().processResult();
                assertTrue(result.commits());
                retain(result.invocationIdentity(), new GasEvidence(result.outputClosureIdentity(),
                        result.totalGas(), result.gasTraceIdentity()));
            }
        }

        void retain(String invocation, GasEvidence evidence) {
            var prior = retainedGas.putIfAbsent(invocation, evidence);
            if (prior != null) assertEquals(prior, evidence, "A retained invocation changed its exact gas or output");
        }

        List<ManagedOccurrenceCatchUpPlan> plans(String name) {
            return f.blue.advanced().auditManagedCatchUpPlans(root(name).id());
        }

        Map<String, DocumentState> state() {
            Map<String, DocumentState> result = new LinkedHashMap<>();
            for (String name : NAMES) {
                var head = f.blue.advanced().auditDocument(root(name).id());
                var history = f.blue.advanced().auditManagedEpochs(root(name).id());
                result.put(name, new DocumentState(head.epoch(), head.blueId(),
                        history.stream().map(ManagedEpochReceipt::receiptIdentity).toList(),
                        history.stream().map(ManagedEpochReceipt::processingGas).toList(),
                        plans(name).stream().map(ManagedOccurrenceCatchUpPlan::snapshotIdentity).toList()));
            }
            return Map.copyOf(result);
        }

        long observed(String name) {
            return ((Number) ExactBlueValue.wrap(f.blue.advanced().auditDocument(root(name).id()).current())
                    .scalarAt("/observed")).longValue();
        }

        long tokens(String name, String to, String next) {
            return f.blue.advanced().auditManagedEpochs(root(name).id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream())
                    .filter(event -> to.equals(event.exactEvent().scalarAt("/to"))
                            && next.equals(event.exactEvent().scalarAt("/next"))).count();
        }

        long eventCount(String name) {
            return f.blue.advanced().auditManagedEpochs(root(name).id()).stream()
                    .flatMap(receipt -> receipt.emittedEvents().stream()).count();
        }

        public void close() { f.close(); }
    }

    private static String attachment(String edge, DocumentHandle source) {
        return "edge: " + edge + "\nsource: {blueId: " + source.id().value() + "}";
    }

    private static String authored(String template, String name, String namespace) {
        return template.replace("<NODE>", name).replace("<NAMESPACE>", namespace)
                .replace("<TIMELINE>", namespace + "/alice");
    }

    private record FrozenPlan(String identity, String barrier, String occurrence, long generation,
            String source, long admittedEpoch, String admittedBlueId, long requiredThroughSourceEpoch, String cause) {
        static FrozenPlan from(ManagedOccurrenceCatchUpPlan plan) {
            return new FrozenPlan(plan.planIdentity(), plan.barrierIdentity(), plan.targetOccurrenceIdentity(),
                    plan.activationGeneration(), plan.sourceDocumentId().value(), plan.admittedSourceEpoch(),
                    plan.admittedSourceBlueId(), plan.requiredThroughSourceEpoch(), plan.causedByIdentity());
        }
    }

    private record DocumentState(long epoch, String blueId, List<String> receipts,
            List<Long> processingGas, List<String> planSnapshots) { }
    private record GasEvidence(String outputClosureIdentity, long totalGas, String gasTraceIdentity) { }
    private record Outcome(String entryBlueId, Map<String, String> documentIds,
            Map<String, DocumentState> documents, Map<String, GasEvidence> gas, long aggregateCallGas) { }
}
