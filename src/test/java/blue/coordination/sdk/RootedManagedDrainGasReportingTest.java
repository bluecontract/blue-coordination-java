package blue.coordination.sdk;

import blue.coordination.api.ContractsClosureDispatchAttempt;
import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.internal.DefaultCoordinationEngine;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ExactNodeDemand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Aggregate call gas versus the unchanged gas retained on individual result evidence. */
final class RootedManagedDrainGasReportingTest {
    @Test void actualRegisteredApplicationContributesChargedGasOnce() throws Exception {
        // given
        try (var s = new HistoryScenario(false)) {
            // when
            var raw = s.engine.processNextRoot(s.parent.id());
            var mapped = map(s.f, raw);
            // then
            assertEquals(1, raw.managedEpochApplicationAttempts().size());
            assertTrue(raw.contractsAttemptsByEntry().isEmpty());
            assertTrue(raw.rootedRetainedAttempts().isEmpty());
            var actual = raw.managedEpochApplicationAttempts().get(0);
            assertTrue(actual.published()); assertFalse(actual.replayed());
            assertTrue(actual.attempt().isComplete());
            var result = actual.attempt().processResult();
            assertTrue(result.commits()); assertTrue(result.totalGas() > 0L);
            assertEquals(result.totalGas(), mapped.stats().gas());
            assertEquals(result.totalGas(), mapped.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas());
            assertEquals(actual.receipt().orElseThrow().applicationReceiptIdentity(),
                    mapped.managedEpochApplications().get(0).applicationReceiptIdentity());
            assertEquals(1L, mapped.stats().committedTransitions());
            assertEquals(1L, ((Number) s.f.blue.advanced().auditDocument(s.parent.id())
                    .current().copyNode().get("/seen")).longValue());
        }
    }

    @Test void retainedReplayAndIncompleteEnvelopeAddNoNewCallGas() throws Exception {
        // given
        try (var s = new HistoryScenario(false)) {
            var raw = s.engine.processNextRoot(s.parent.id());
            var actual = raw.managedEpochApplicationAttempts().get(0);
            assertTrue(actual.published());
            long originalGas = actual.attempt().processResult().totalGas();
            var missing = s.f.blue.values().yaml("name: Unavailable gas-reporting value").blueId();
            var demand = ExactNodeDemand.derived(missing,
                    new blue.language.processor.closure.DocumentId(s.parent.id().value()), "/missing");
            var suspended = new blue.coordination.api.ManagedEpochApplicationAttempt(actual.work(),
                    ClosureAttemptResult.needsResources(List.of(demand)), false, false, Optional.empty());
            var incomplete = new ProcessingDrainReceipt(List.of(), Map.of(), Map.of(), null, false, false,
                    0L, 0L, List.of(), List.of(suspended));
            // when
            var replay = map(s.f, replay(raw));
            var pending = map(s.f, incomplete);
            // then
            assertTrue(originalGas > 0L);
            assertEquals(0L, replay.stats().gas());
            assertEquals(0L, replay.stats().committedTransitions());
            assertTrue(replay.managedEpochApplicationAttempts().get(0).replayed());
            assertEquals(originalGas, replay.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas(),
                    "Response-loss evidence retains the original result gas, but does not charge this call again");
            assertEquals(actual.receipt().orElseThrow().applicationReceiptIdentity(),
                    replay.managedEpochApplications().get(0).applicationReceiptIdentity());
            assertEquals(0L, pending.stats().gas());
            assertFalse(pending.managedEpochApplicationAttempts().get(0).attempt().isComplete());
            assertEquals(1, pending.managedEpochApplicationAttempts().get(0).attempt().resourceDemands().size());
        }
    }

    @Test void genuineRepeatedGasRollbacksEachContributeTheirChargedWork() throws Exception {
        // given
        try (var s = new HistoryScenario(true)) {
            var before = List.of(s.f.history(s.parent), s.f.history(s.source));
            // when
            var first = s.engine.processNextRoot(s.parent.id());
            var second = s.engine.processNextRoot(s.parent.id());
            var mapped = map(s.f, combine(List.of(first, second)));
            // then
            assertEquals(1, first.managedEpochApplicationAttempts().size());
            assertEquals(1, second.managedEpochApplicationAttempts().size());
            var a = first.managedEpochApplicationAttempts().get(0);
            var b = second.managedEpochApplicationAttempts().get(0);
            assertFalse(a.published()); assertFalse(b.published());
            assertFalse(a.replayed()); assertFalse(b.replayed());
            assertTrue(a.attempt().isComplete()); assertTrue(b.attempt().isComplete());
            var result = a.attempt().processResult();
            assertEquals("gas-limit-exceeded", result.status().wireValue());
            assertTrue(result.rollbackToInput()); assertTrue(result.totalGas() > 0L);
            assertEquals(result.invocationIdentity(), b.attempt().processResult().invocationIdentity());
            assertEquals(result.gasTraceIdentity(), b.attempt().processResult().gasTraceIdentity());
            assertEquals(Math.addExact(result.totalGas(), b.attempt().processResult().totalGas()), mapped.stats().gas(),
                    "Two real deterministic retries are not deduplicated by equal invocation identity");
            assertEquals(0L, mapped.stats().committedTransitions());
            assertTrue(mapped.managedEpochApplications().isEmpty());
            assertEquals(before, List.of(s.f.history(s.parent), s.f.history(s.source)));
        }
    }

    @Test void genuineEntryLocalAndRegisteredLanesSumWithoutCountingTheirProjectionsTwice() throws Exception {
        // given
        try (var f = new RootedSdkFixture()) {
            var engine = (DefaultCoordinationEngine) f.blue.advanced().rawEngine();
            var roots = new LinkedHashMap<String, DocumentHandle>();
            var originals = new LinkedHashMap<String, String>();
            String template = RootedSdkFixture.resource("node-graph.template.json");
            for (String name : List.of("A", "B", "C")) {
                String authored = template.replace("<NODE>", name).replace("<NAMESPACE>", "gas-reporting-local")
                        .replace("<TIMELINE>", "gas/local/" + name);
                var exact = f.blue.values().yaml(authored);
                f.exact.put(exact.blueId(), exact.json()); originals.put(name, exact.blueId());
                roots.put(name, f.startYaml(authored, "gas/local/" + name));
            }
            var a = roots.get("A"); var b = roots.get("B");
            var first = f.append(a, "gas/local/A", "attach", 100,
                    "edge: b\nsource: {blueId: " + originals.get("B") + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(a, first).entry(first).disposition());
            settle(f, a);
            var second = f.append(b, "gas/local/B", "attach", 200,
                    "edge: c\nsource: {blueId: " + originals.get("C") + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.processing().process(b, second).entry(second).disposition());
            var managed = engine.processNextRoot(b.id());
            assertEquals(1, managed.managedEpochApplicationAttempts().size());
            assertTrue(managed.managedEpochApplicationAttempts().get(0).published());
            settle(f, b);
            var external = engine.processNextRoot(a.id());
            assertEquals(1, external.contractsAttemptsFor(second.blueId()).size());
            var local = engine.processNextRoot(a.id());
            assertEquals(1, local.rootedRetainedAttempts().size());
            assertTrue(local.contractsAttemptsByEntry().isEmpty());
            assertTrue(local.managedEpochApplicationAttempts().isEmpty());
            long entryGas = external.contractsAttemptsFor(second.blueId()).get(0).attempt().processResult().totalGas();
            long localGas = local.rootedRetainedAttempts().get(0).attempt().attempt().processResult().totalGas();
            long managedGas = managed.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas();
            var combined = combine(List.of(managed, external, local));
            // when
            var mapped = map(f, combined);
            var replay = map(f, replay(combined));
            // then
            assertTrue(entryGas > 0L); assertTrue(localGas > 0L); assertTrue(managedGas > 0L);
            assertEquals(Math.addExact(Math.addExact(entryGas, localGas), managedGas), mapped.stats().gas());
            assertEquals(3L, mapped.stats().committedTransitions());
            assertEquals(1, mapped.entries().size());
            assertEquals(1, mapped.rootedRetainedResults().size());
            assertEquals(1, mapped.managedEpochApplications().size());
            assertEquals(1, mapped.managedEpochApplicationAttempts().size());
            assertEquals(entryGas, mapped.entries().get(0).stats().gas());
            assertEquals(localGas, mapped.rootedRetainedResults().get(0).stats().gas());
            assertEquals(managedGas, mapped.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas());
            assertEquals(0L, replay.stats().gas());
            assertEquals(entryGas, replay.entries().get(0).stats().gas());
            assertEquals(localGas, replay.rootedRetainedResults().get(0).stats().gas());
            assertEquals(managedGas, replay.managedEpochApplicationAttempts().get(0).attempt().processResult().totalGas());
        }
    }

    private static final class HistoryScenario implements AutoCloseable {
        final RootedSdkFixture f;
        final DefaultCoordinationEngine engine;
        final DocumentHandle source;
        final DocumentHandle parent;

        HistoryScenario(boolean exhaust) throws Exception {
            f = new RootedSdkFixture(exhaust ? ContractsExecutionPolicy.exactSharedGas(3_000L, "managed-drain-gas-reporting")
                    : ContractsExecutionPolicy.releaseDefault());
            engine = (DefaultCoordinationEngine) f.blue.advanced().rawEngine();
            source = f.start("source.yaml", "rcp2/source", Map.of());
            String original = f.retain(source);
            String parentYaml = RootedSdkFixture.resource("parent.yaml");
            if (exhaust) {
                int steps = parentYaml.indexOf("    steps:\n", parentYaml.indexOf("  observe:")) + "    steps:\n".length();
                parentYaml = parentYaml.substring(0, steps) + parentYaml.substring(steps).repeat(60);
            }
            parent = f.startYaml(parentYaml, "rcp2/parent");
            var tick = f.append(source, "rcp2/source", "tick", 10, "{}");
            assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(source, tick,
                    ContractsExecutionPolicy.releaseDefault()).entry(tick).disposition());
            var attach = f.append(parent, "rcp2/parent", "attach", 20, "child: {blueId: " + original + "}");
            assertEquals(EntryDisposition.APPLIED, f.blue.advanced().process(parent, attach,
                    ContractsExecutionPolicy.releaseDefault()).entry(attach).disposition());
        }

        @Override public void close() { f.close(); }
    }

    private static DrainResult map(RootedSdkFixture f, ProcessingDrainReceipt receipt) throws Exception {
        var field = BlueCoordination.class.getDeclaredField("runtime");
        field.setAccessible(true);
        var runtime = (SdkCoordinationRuntime) field.get(f.blue);
        return new SdkDrainResultMapper(runtime, (DefaultCoordinationEngine) f.blue.advanced().rawEngine()).map(receipt);
    }

    private static void settle(RootedSdkFixture f, DocumentHandle root) {
        for (int step = 0; step < 32; step++) {
            var next = f.blue.processing().processNext(root);
            assertFalse(next.blocked(), String.valueOf(next.diagnostic()));
            if (next.quiescent()) return;
        }
        fail("Finite reporting prefix did not settle in 32 selections");
    }

    /** Only combines actual completed slices; no result, work or receipt is manufactured. */
    private static ProcessingDrainReceipt combine(List<ProcessingDrainReceipt> slices) {
        var entries = new ArrayList<blue.coordination.api.TimelineEntry>();
        var attempts = new LinkedHashMap<String, List<ContractsClosureDispatchAttempt>>();
        var applications = new ArrayList<blue.coordination.api.ManagedEpochApplicationReceipt>();
        var managed = new ArrayList<blue.coordination.api.ManagedEpochApplicationAttempt>();
        var local = new ArrayList<ProcessingDrainReceipt.RootedRetainedAttempt>();
        long transitions = 0L;
        for (var slice : slices) {
            entries.addAll(slice.processedEntries());
            slice.contractsAttemptsByEntry().forEach((key, value) -> attempts.merge(key, value, (a, b) -> {
                var joined = new ArrayList<>(a); joined.addAll(b); return List.copyOf(joined);
            }));
            applications.addAll(slice.managedEpochApplications()); managed.addAll(slice.managedEpochApplicationAttempts());
            local.addAll(slice.rootedRetainedAttempts());
            transitions = Math.addExact(transitions, slice.committedProcessTransitions());
        }
        var last = slices.get(slices.size() - 1);
        return new ProcessingDrainReceipt(entries, Map.of(), attempts, last.processedThrough().orElse(null),
                last.quiescent(), last.paused(), transitions, 0L,
                applications, managed).withRootedRetainedAttempts(local);
    }

    /** Reporting-only replay envelope around actual published results; never submitted for execution. */
    private static ProcessingDrainReceipt replay(ProcessingDrainReceipt actual) {
        var entries = new LinkedHashMap<String, List<ContractsClosureDispatchAttempt>>();
        actual.contractsAttemptsByEntry().forEach((key, attempts) -> entries.put(key,
                attempts.stream().map(RootedManagedDrainGasReportingTest::replay).toList()));
        var managed = actual.managedEpochApplicationAttempts().stream().map(attempt ->
                new blue.coordination.api.ManagedEpochApplicationAttempt(attempt.work(), attempt.attempt(),
                        attempt.published(), true, attempt.receipt(), attempt.automaticRetryCount(),
                        attempt.automaticResolutionStopReason(), attempt.managedOccurrenceResolutionIssues(),
                        attempt.publicationFailure(), attempt.managedOccurrenceResolutions(), attempt.inputComponents(),
                        attempt.operationRouteChanges())).toList();
        return new ProcessingDrainReceipt(actual.processedEntries(), actual.outcomesByEntry(), entries,
                actual.processedThrough().orElse(null), true, false, 0L, 0L, actual.managedEpochApplications(), managed)
                .withRootedRetainedAttempts(actual.rootedRetainedAttempts().stream().map(retained ->
                        new ProcessingDrainReceipt.RootedRetainedAttempt(retained.rootDocumentId(), retained.work(),
                                replay(retained.attempt()))).toList());
    }

    private static ContractsClosureDispatchAttempt replay(ContractsClosureDispatchAttempt attempt) {
        assertTrue(attempt.published());
        return new ContractsClosureDispatchAttempt(attempt.entryBlueId(), attempt.documentIds(), attempt.attempt(), true,
                attempt.publicationIdentity(), true, attempt.automaticRetryCount(), attempt.managedOccurrenceResolutions(),
                attempt.inputComponents(), attempt.operationRouteChanges(), attempt.managedOccurrenceResolutionIssues());
    }
}
