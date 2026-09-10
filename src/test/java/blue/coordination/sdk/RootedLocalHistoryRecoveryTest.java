package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.RootedCalculationFixture;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local retained publication, shared-gas rollback and response-loss recovery. */
final class RootedLocalHistoryRecoveryTest {
    @Test void retainedSourceEventReachesItsCalculatedConsumer() throws Exception {
        // given
        try (var scenario = new Scenario(100_000L)) {
            // when
            var input = scenario.f.control.captureLocalHistory(scenario.root.id());
            System.out.println("LOCAL_CAUSE=" + input.cause().getClass().getSimpleName());
            System.out.println("LOCAL_VIEW=" + scenario.f.control.localHistoryDescription(scenario.root.id()));

            var reference = RootedCalculationFixture.materializedReference(input);
            System.out.println("REFERENCE_RESULT=" + reference.status() + ":" + reference.totalGas());
            var result = scenario.f.blue.processing().processNext(scenario.root);

            // then
            assertTrue(result.quiescent());
            assertEquals(1L, result.stats().committedTransitions(), "Calculated dependencies do not commit independent revisions");
            assertEquals(25, scenario.localConsumerTouches());
        }
    }

    @Test void gasBoundariesRetainExactLocalHistoryAndIndependentSourcesOnRestart() throws Exception {
        // given
        long gas;
        // when
        try (var scenario = new Scenario(100_000L)) {
            var reference = RootedCalculationFixture.materializedReference(scenario.f.control.captureLocalHistory(scenario.root.id()));
            // then
            assertTrue(reference.commits(), String.valueOf(reference.diagnostic())); gas = reference.totalGas();
            System.out.println("LOCAL_RETAINED_GAS_REFERENCE=" + gas);
        }
        for (long delta : List.of(-1L, 0L, 1L)) {
            try (var scenario = new Scenario(gas + delta)) {
                var f = scenario.f;
                var heads = scenario.heads(); var histories = scenario.histories();
                var selected = f.control.selectedView(scenario.root.id()).closureIdentity();
                var input = f.control.captureLocalHistory(scenario.root.id());
                var reference = RootedCalculationFixture.materializedReference(input);
                var drained = f.blue.processing().processNext(scenario.root);
                assertTrue(drained.entries().isEmpty()); assertTrue(drained.managedEpochApplications().isEmpty());
                assertEquals(1, drained.rootedRetainedResults().size());
                var actual = drained.rootedRetainedResults().get(0);
                assertEquals(delta < 0 ? EntryDisposition.GAS_LIMIT_EXCEEDED : EntryDisposition.APPLIED, actual.disposition());
                var retained = f.blue.advanced().closureExecution(actual.closureId()).orElseThrow();
                assertEquals(reference.status(), retained.status());
                assertEquals(reference.totalGas(), retained.totalGas());
                assertEquals(reference.gasTraceIdentity(), retained.gasTraceIdentity());
                assertEquals(retained.totalGas(), drained.stats().gas());
                assertEquals(delta < 0 ? 0L : 1L, drained.stats().committedTransitions());
                assertEquals(heads.subList(1, 3), scenario.heads().subList(1, 3));
                assertEquals(histories.subList(1, 3), scenario.histories().subList(1, 3));
                if (delta < 0) {
                    assertTrue(retained.rollbackToInput()); assertNull(retained.commitCompanion());
                    assertEquals(reference.rejectedCharge().rejectedChargeIdentity(), retained.rejectedCharge().rejectedChargeIdentity());
                    assertTrue(retained.checkpointWrites().isEmpty()); assertTrue(retained.managedTransitionReceipts().isEmpty());
                    assertTrue(actual.changes().isEmpty()); assertTrue(actual.publicEvents().isEmpty());
                    assertEquals(heads, scenario.heads()); assertEquals(histories, scenario.histories());
                    assertEquals(selected, f.control.selectedView(scenario.root.id()).closureIdentity());
                    assertFalse(drained.quiescent());
                } else {
                    assertEquals(List.of(scenario.root.id()), actual.changes().stream().map(DocumentChange::documentId).toList());
                    assertTrue(drained.quiescent());
                }
                var afterHeads = scenario.heads(); var afterHistories = scenario.histories();
                CoordinationTestControl.attach(f.blue.advanced().rawEngine()).restartFromStores();
                var repeated = f.blue.processing().processNext(scenario.root);
                assertEquals(0L, repeated.stats().committedTransitions());
                if (delta < 0) {
                    assertFalse(repeated.quiescent());
                    assertEquals(actual.closureId(), repeated.rootedRetainedResults().get(0).closureId());
                    assertEquals(EntryDisposition.GAS_LIMIT_EXCEEDED, repeated.rootedRetainedResults().get(0).disposition());
                } else assertTrue(repeated.quiescent());
                assertEquals(afterHeads, scenario.heads()); assertEquals(afterHistories, scenario.histories());
            }
        }
    }

    @Test void failuresBeforeAndAfterStoreSwapNeverPublishAnIndependentSourceOrDuplicateTheRoot() throws Exception {
        // given
        for (var point : List.of("AFTER_CAS_CHECKS", "BEFORE_SWAP", "AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH")) {
            try (var scenario = new Scenario(100_000L)) {
                // when
                var f = scenario.f; var heads = scenario.heads(); var histories = scenario.histories();
                var controls = CoordinationTestControl.attach(f.blue.advanced().rawEngine());
                f.control.failPublicationAt(point);
                // then
                assertThrows(RuntimeException.class, () -> f.blue.processing().processNext(scenario.root), point);
                assertEquals(heads.subList(1, 3), scenario.heads().subList(1, 3));
                assertEquals(histories.subList(1, 3), scenario.histories().subList(1, 3));
                if (!point.equals("AFTER_STORE_COMMIT_BEFORE_ROUTE_PUBLISH")) {
                    assertEquals(heads, scenario.heads()); assertEquals(histories, scenario.histories());
                }
                f.control.clearPublicationFailure(); controls.restartFromStores();
                assertTrue(f.blue.processing().processNext(scenario.root).quiescent());
                assertEquals(histories.get(0).size() + 1, scenario.histories().get(0).size());
                assertEquals(histories.subList(1, 3), scenario.histories().subList(1, 3));
                var committed = scenario.histories();
                controls.restartFromStores(); assertTrue(f.blue.processing().processNext(scenario.root).quiescent());
                assertEquals(committed, scenario.histories());
            }
        }
    }

    private static final class Scenario implements AutoCloseable {
        final RootedSdkFixture f;
        final LinkedHashMap<String, DocumentHandle> roots = new LinkedHashMap<>();
        final DocumentHandle root;
        Scenario(long gas) throws Exception {
            f = new RootedSdkFixture(ContractsExecutionPolicy.exactSharedGas(gas, "rooted-local-history-boundary"));
            String template;
            try (var in = getClass().getResourceAsStream("/rooted/node-graph.template.json")) {
                template = new String(java.util.Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            }
            var originals = new LinkedHashMap<String, ExactBlueValue>();
            for (String name : List.of("A", "B", "C")) {
                String yaml = template.replace("<NODE>", name).replace("<NAMESPACE>", "rooted-local-gas")
                        .replace("<TIMELINE>", "rcp/local-gas/" + name);
                if (name.equals("B")) yaml = withUpdateBatch(yaml);
                if (name.equals("C")) yaml = withSourceEvent(yaml);
                var exact = f.blue.values().yaml(yaml); originals.put(name, exact); f.exact.put(exact.blueId(), exact.json());
                roots.put(name, f.startYaml(yaml, "rcp/local-gas/" + name));
            }
            root = roots.get("A"); int ordinal = 0; EntryHandle last = null;
            var changedSource = f.append(roots.get("C"), "rcp/local-gas/C", "touch", 150L, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(roots.get("C"), changedSource,
                    ContractsExecutionPolicy.releaseDefault()).entry(changedSource).disposition());
            for (String[] edge : new String[][]{{"A", "b", "B"}, {"B", "c", "C"}}) {
                var owner = roots.get(edge[0]);
                last = f.append(owner, "rcp/local-gas/" + edge[0], "attach", ++ordinal * 100L,
                        "edge: " + edge[1] + "\nsource: {blueId: " + originals.get(edge[2]).blueId() + "}");
                assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(owner, last,
                        ContractsExecutionPolicy.releaseDefault()).entry(last).disposition());
                boolean done = false;
                for (int step = 0; step < 32; step++) {
                    var progress = f.blue.processing().processNext(owner);
                    if (progress.quiescent()) { done = true; break; }
                    assertFalse(progress.blocked(), progress.diagnostic().toString());
                }
                assertTrue(done);
            }
            var trigger = f.blue.advanced().process(root, last, ContractsExecutionPolicy.releaseDefault());
            assertEquals(EntryDisposition.APPLIED, trigger.entry(last).disposition());
            assertFalse(trigger.quiescent());
            // Settle the eventless C initialization before measuring the real C1 source event and calculated B's reaction batch.
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().processNext(root).rootedRetainedResults().get(0).disposition());
            assertNotNull(f.control.captureLocalHistory(root.id()));
        }
        private static String withSourceEvent(String json) throws Exception {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.readTree(json);
            var actions = (com.fasterxml.jackson.databind.node.ArrayNode) tree.path("contracts").path("touch")
                    .path("steps").get(0).path("do");
            actions.insert(1, mapper.readTree("{\"$appendEvent\":{\"type\":\"Coordination/Event\",\"kind\":\"Rooted/Local Gas\"}}"));
            return mapper.writeValueAsString(tree);
        }
        private static String withUpdateBatch(String json) {
            String step = "{\"type\":\"Coordination/Compute\",\"do\":[{\"$appendChange\":{\"op\":\"replace\",\"path\":\"/touches\",\"val\":{\"$add\":[{\"$document\":\"/touches\"},1]}}},{\"$return\":true}]}";
            String handlers = "\"updates\":{\"type\":\"Embedded Node Channel\",\"sourcePath\":\"/peers/c\",\"event\":{\"type\":\"Coordination/Event\",\"kind\":\"Rooted/Local Gas\"}},"
                    + "\"updateBatch\":{\"type\":\"Coordination/Sequential Workflow\",\"channel\":\"updates\",\"steps\":["
                    + String.join(",", java.util.Collections.nCopies(25, step)) + "]},";
            return json.replace("\"contracts\": {", "\"contracts\": {" + handlers);
        }
        int localConsumerTouches() throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    f.selected(root, "/peers/b").json()).path("touches").path("value").asInt();
        }
        List<String> heads() { return roots.values().stream().map(value -> value.snapshot().blueId()).toList(); }
        List<List<String>> histories() { return roots.values().stream().map(f::history).toList(); }
        @Override public void close() { f.close(); }
    }
}
