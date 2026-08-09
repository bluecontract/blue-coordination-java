package blue.coordination.basic;

import blue.coordination.basic.engine.BasicCoordinationEngine;
import blue.coordination.basic.engine.BasicOperation;
import blue.coordination.basic.engine.EngineMetrics;
import blue.coordination.basic.engine.ExactTimelineEntry;
import blue.coordination.basic.engine.Timeline;
import blue.language.api.BlueCacheStats;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.basic.BasicEngineTestSupport.delta;
import static blue.coordination.basic.BasicEngineTestSupport.integer;
import static blue.coordination.basic.BasicEngineTestSupport.payloadRequest;
import static blue.coordination.basic.BasicEngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dedicated, same-source mechanics and runtime evidence campaign. */
@Tag("runtimeCampaign")
final class BasicRuntimeCampaignTest {
    private static final int MICRO_WARMUPS = 50;
    private static final int MICRO_SAMPLES = 200;
    private static final int PROCESS_WARMUPS = 5;
    private static final int PROCESS_SAMPLES = 30;
    private static final int DOCUMENT_SAMPLES = Integer.getInteger(
            "basic.runtime.documentSamples", 30);
    private static final String BASELINE = "not measured (supplied archive)";

    @Test
    void writesCompleteSameSourceMarkdownAndJsonEvidence() throws Exception {
        List<RuntimeComparisonWriter.Row> rows = new ArrayList<>();
        List<String> hardFailures = new ArrayList<>();

        progress("append parity");
        AppendEvidence append = measureAppend();
        boolean tinyPass = append.tiny().p95Nanos() <= 5_000_000L;
        boolean payNotePass = append.payNote().p95Nanos() <= 15_000_000L;
        double appendRatio = (double) append.payNote().p95Nanos()
                / append.tiny().p95Nanos();
        boolean ratioPass = appendRatio <= 5.0;
        rows.add(RuntimeComparisonWriter.row(
                "Append tiny Counter request, no matching Root",
                BASELINE,
                append.tiny(),
                "p50 <= 2 ms; p95 <= 5 ms; max <= 15 ms",
                tinyPass,
                append.counters(),
                "One exact request reuse, one structurally shared entry template, and one journal append per sample; no route target or PROCESS."));
        rows.add(RuntimeComparisonWriter.row(
                "Append PayNote-sized request, no matching Root",
                BASELINE,
                append.payNote(),
                "p50 <= 5 ms; p95 <= 15 ms; max <= 40 ms",
                payNotePass,
                append.counters(),
                "The original whole PayNote payload follows the identical operation shape; byte hashing is the only permitted size-dependent work."));
        rows.add(singletonRow(
                "PayNote/tiny append p95 ratio",
                appendRatio,
                "x",
                "preferred <= 3x; hard <= 5x",
                ratioPass,
                append.counters(),
                "Ratio of the two alternating 200-sample p95 values."));

        progress("route lookup");
        RouteEvidence route = measureRouteLookup();
        boolean routePass = route.samples().p95Nanos() <= 1_000_000L;
        rows.add(RuntimeComparisonWriter.row(
                "Route one matching Root",
                BASELINE,
                route.samples(),
                "p50 <= 0.25 ms; p95 <= 1 ms; max <= 3 ms",
                routePass,
                route.counters(),
                "One precompiled exact-key lookup and one target; semantic execution is deliberately outside this span."));

        progress("counter PROCESS");
        ProcessEvidence process = measureCounterProcess();
        rows.add(RuntimeComparisonWriter.row(
                "Counter frozen PROCESS",
                BASELINE,
                process.frozen(),
                "report frozen floor; exactly one call",
                true,
                process.counters(),
                "Frozen Language/Contracts/BEX time only, measured from the real production work site."));
        boolean hostPass = process.host().p95Nanos() <= 25_000_000L;
        rows.add(RuntimeComparisonWriter.row(
                "Host overhead around one PROCESS",
                BASELINE,
                process.host(),
                "preferred p95 <= 10 ms; hard p95 <= 25 ms",
                hostPass,
                process.counters(),
                "Complete dispatch minus route lookup and the single frozen PROCESS; includes exact-state publication and commit-companion delta handling."));

        progress("one versus 61 workflows");
        WorkflowInheritanceScalingTest.Measurement one =
                WorkflowInheritanceScalingTest.measure(false);
        collectClosedEnvironments();
        WorkflowInheritanceScalingTest.Measurement sixtyOne =
                WorkflowInheritanceScalingTest.measure(true);
        double workflowDeltaMillis = Math.abs(
                sixtyOne.host().p95Nanos() - one.host().p95Nanos())
                / 1_000_000.0;
        boolean workflowPass = workflowDeltaMillis <= 60.0;
        Map<String, Long> workflowCounters = new LinkedHashMap<>();
        workflowCounters.put("oneWorkflowFrozenCalls", (long) one.processCalls());
        workflowCounters.put("sixtyOneWorkflowFrozenCalls", (long) sixtyOne.processCalls());
        workflowCounters.put("workflowBodiesScannedOnHotPath",
                one.hostWork().counter("workflowBodiesScannedOnHotPath")
                        + sixtyOne.hostWork().counter(
                        "workflowBodiesScannedOnHotPath"));
        rows.add(singletonRow(
                "One workflow versus 61 inherited workflows: host p95 delta",
                workflowDeltaMillis,
                "ms",
                "preferred p95 delta <= 30 ms; hard <= 60 ms",
                workflowPass,
                workflowCounters,
                "Both route tables are compiled at admission; frozen time is excluded from this delta."));

        progress("existing child");
        ScenarioEvidence existing = measureExistingChild();
        boolean existingPass = existing.host().p95Nanos() <= 150_000_000L;
        rows.add(RuntimeComparisonWriter.row(
                "Existing child catch-up, 20 stored revisions",
                BASELINE,
                existing.host(),
                "preferred p95 <= 80 ms; hard <= 150 ms",
                existingPass,
                existing.counters(),
                "Host time excludes the single parent attachment PROCESS; child source replay remains zero and every historical child revision is published to the parent in exact order."));

        progress("late child");
        ScenarioEvidence late = measureLateChild();
        rows.add(RuntimeComparisonWriter.row(
                "Late child admission, 20 source entries",
                BASELINE,
                late.host(),
                "frozen calls + p95 <= 25 ms host; max <= 75 ms host",
                late.host().p95Nanos() <= 25_000_000L,
                late.counters(),
                "Twenty bounded Timeline entries are read directly and each unseen child source entry crosses frozen PROCESS exactly once."));

        progress("nested catch-up");
        ScenarioEvidence nested = measureNestedCatchUp();
        rows.add(RuntimeComparisonWriter.row(
                "Nested Root -> Emb1 -> Emb2 catch-up",
                BASELINE,
                nested.host(),
                "p50 <= 10 ms host; p95 <= 30 ms; max <= 100 ms",
                nested.host().p95Nanos() <= 30_000_000L,
                nested.counters(),
                "Existing middle/leaf sessions are reused through numeric revision cursors; frozen envelope calls are excluded."));

        progress("NBA catch-up");
        ScenarioEvidence nba = measureNbaCatchUp();
        boolean nbaPass = nba.host().p95Nanos() <= 200_000_000L;
        rows.add(RuntimeComparisonWriter.row(
                "NBA historical game catch-up, 5 revisions",
                BASELINE,
                nba.host(),
                "preferred p95 <= 120 ms; hard <= 200 ms",
                nbaPass,
                nba.counters(),
                "The existing Game is never replayed; Statistics consumes five ordered revision envelopes. Frozen envelope processing is excluded."));

        progress("live two-parent fan-out");
        ScenarioEvidence fanout = measureLiveFanout();
        rows.add(RuntimeComparisonWriter.row(
                "Live child revision propagated to two parents",
                BASELINE,
                fanout.host(),
                "p50 <= 5 ms host; p95 <= 15 ms; max <= 50 ms",
                fanout.host().p95Nanos() <= 15_000_000L,
                fanout.counters(),
                "One child PROCESS produces one revision and two state-only parent applications through the inverse parent index."));

        recordFailure(hardFailures, "tiny append", tinyPass);
        recordFailure(hardFailures, "PayNote append", payNotePass);
        recordFailure(hardFailures, "append ratio", ratioPass);
        recordFailure(hardFailures, "route lookup", routePass);
        recordFailure(hardFailures, "host overhead", hostPass);
        recordFailure(hardFailures, "workflow host delta", workflowPass);
        recordFailure(hardFailures, "existing child", existingPass);
        recordFailure(hardFailures, "NBA catch-up", nbaPass);

        Path report = Path.of(System.getProperty(
                "blue.basic.runtimeReport",
                "build/reports/basicTest/runtime-comparison.md"));
        RuntimeComparisonWriter.write(report, rows);
        assertTrue(java.nio.file.Files.isRegularFile(report));
        assertTrue(java.nio.file.Files.isRegularFile(
                report.resolveSibling("runtime-comparison.json")));
        if (Boolean.getBoolean("basic.strictPerformance")) {
            assertTrue(hardFailures.isEmpty(),
                    () -> "Failed runtime gates: " + hardFailures
                            + "; evidence was written to " + report);
        }
    }

    private static AppendEvidence measureAppend() throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline timeline = engine.timeline("runtime/append", "alice");
            var tiny = engine.exactRequest("amount: 1");
            var payNote = engine.exactRequest(payloadRequest(
                    resource("examples/clean/package-paynote.yaml")));
            BasicOperation tinyOperation = BasicOperation.exact(
                    "ignored", "ownerChannel", tiny);
            BasicOperation payNoteOperation = BasicOperation.exact(
                    "ignored", "ownerChannel", payNote);
            for (int index = 0; index < MICRO_WARMUPS; index++) {
                engine.append(timeline, (index & 1) == 0
                        ? tinyOperation : payNoteOperation);
                engine.append(timeline, (index & 1) == 0
                        ? payNoteOperation : tinyOperation);
            }
            LatencySeries tinySamples = new LatencySeries();
            LatencySeries payNoteSamples = new LatencySeries();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            for (int index = 0; index < MICRO_SAMPLES; index++) {
                if ((index & 1) == 0) {
                    sampleAppend(engine, timeline, tinyOperation, tinySamples);
                    sampleAppend(engine, timeline, payNoteOperation, payNoteSamples);
                } else {
                    sampleAppend(engine, timeline, payNoteOperation, payNoteSamples);
                    sampleAppend(engine, timeline, tinyOperation, tinySamples);
                }
            }
            var work = delta(before, engine.metricsSnapshot());
            return new AppendEvidence(
                    tinySamples,
                    payNoteSamples,
                    counters(work,
                            "requestsStoredWhole",
                            "append.exactRequestsReused",
                            "append.eventTemplateHits",
                            "append.entriesBuilt",
                            "append.journalOperations",
                            "routeTargets",
                            "frozenProcessCalls",
                            "requestSplitterCalls",
                            "entrySplitterCalls"));
        }
    }

    private static RouteEvidence measureRouteLookup() throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline timeline = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start("runtime-route-counter", counter(
                    resource("examples/clean/counter.yaml"),
                    "counter", "runtime-route-counter"));
            BasicOperation operation = BasicOperation.of(
                    "increment", "aliceChannel", "amount: 1");
            for (int index = 0; index < MICRO_WARMUPS; index++) {
                assertEquals(1, engine.routeTargetCount(
                        engine.append(timeline, operation)));
            }
            LatencySeries samples = new LatencySeries();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            for (int index = 0; index < MICRO_SAMPLES; index++) {
                ExactTimelineEntry entry = engine.append(timeline, operation);
                long started = System.nanoTime();
                assertEquals(1, engine.routeTargetCount(entry));
                samples.add(System.nanoTime() - started);
            }
            var work = delta(before, engine.metricsSnapshot());
            return new RouteEvidence(samples, counters(
                    work, "routeLookups", "routeTargets",
                    "workflowBodiesScannedOnHotPath"));
        }
    }

    private static ProcessEvidence measureCounterProcess() throws Exception {
        try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
            Timeline timeline = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            engine.start("runtime-process-counter", counter(
                    resource("examples/clean/counter.yaml"),
                    "counter", "runtime-process-counter"));
            for (int index = 0; index < PROCESS_WARMUPS; index++) {
                engine.appendAndDispatch(timeline, BasicOperation.of(
                        "increment", "aliceChannel", "amount: 1"));
            }
            LatencySeries frozen = new LatencySeries();
            LatencySeries host = new LatencySeries();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            for (int index = 0; index < PROCESS_SAMPLES; index++) {
                ExactTimelineEntry entry = engine.append(timeline,
                        BasicOperation.of(
                                "increment", "aliceChannel", "amount: 1"));
                EngineMetrics.MetricsSnapshot sampleBefore =
                        engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.dispatch(entry);
                long elapsed = System.nanoTime() - started;
                var work = delta(sampleBefore, engine.metricsSnapshot());
                frozen.add(work.nanos("process.frozen"));
                host.add(Math.max(0L, elapsed
                        - work.nanos("process.frozen")
                        - work.nanos("process.routeLookup")));
            }
            var work = delta(before, engine.metricsSnapshot());
            Map<String, Long> evidence = counters(
                    work, "frozenProcessCalls", "processedOccurrencePaths",
                    "deliveryReceiptsCommitted", "rootOwnershipEscapes",
                    "process.concreteOwnershipRootInputs",
                    "process.referenceOnlyRootInputs",
                    "process.referenceOnlyEventInputs",
                    "process.concreteSubscriptionProjections",
                    "process.commitCompanionDeltasApplied",
                    "process.subscriptionIntervalsReused");
            evidence.put("layoutNanos",
                    work.nanos("layout.retainEmbeddedOnly"));
            evidence.put("commitCompanionDeltaNanos",
                    work.nanos("process.applyCommitCompanionDelta"));
            addLanguageCacheEvidence(evidence, engine.languageCacheStats());
            return new ProcessEvidence(frozen, host, evidence);
        }
    }

    private static ScenarioEvidence measureExistingChild() throws Exception {
        LatencySeries host = new LatencySeries();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int sample = 0; sample < DOCUMENT_SAMPLES; sample++) {
            try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
                String child = resource("examples/clean/embedded-counter.yaml");
                Timeline childTimeline = engine.timeline(
                        "examples/embedded/A", "alice");
                engine.start("embedded-counter-A", child);
                for (int index = 0; index < 20; index++) {
                    engine.appendAndDispatch(childTimeline, BasicOperation.of(
                            "increment", "ownerChannel", "amount: 1"));
                }
                engine.start("embedded-state-parent", resource(
                        "examples/clean/embedded-state-parent.yaml"));
                Timeline parent = engine.timeline(
                        "examples/embedded/state-parent", "bob");
                EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.appendAndDispatch(parent, BasicOperation.exact(
                        "attachChild", "ownerChannel",
                        engine.embeddedDocumentRequest(child)));
                long elapsed = System.nanoTime() - started;
                var work = delta(before, engine.metricsSnapshot());
                host.add(Math.max(0L, elapsed - work.nanos("process.frozen")));
                merge(totals, work,
                        "frozenProcessCalls", "sessionsReused",
                        "childHistoricalProcessCalls",
                        "childRevisionApplications",
                        "revisionApplicationReceiptsCommitted");
                assertEquals(20L, integer(
                        engine, "embedded-state-parent", "/child/counter"));
            }
        }
        return new ScenarioEvidence(host, totals);
    }

    private static ScenarioEvidence measureLateChild() throws Exception {
        LatencySeries host = new LatencySeries();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int sample = 0; sample < DOCUMENT_SAMPLES; sample++) {
            try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
                String child = resource("examples/clean/embedded-counter.yaml");
                Timeline childTimeline = engine.timeline(
                        "examples/embedded/A", "alice");
                for (int index = 0; index < 20; index++) {
                    engine.append(childTimeline, BasicOperation.of(
                            "increment", "ownerChannel", "amount: 1"));
                }
                engine.start("embedded-state-parent", resource(
                        "examples/clean/embedded-state-parent.yaml"));
                Timeline parent = engine.timeline(
                        "examples/embedded/state-parent", "bob");
                EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.appendAndDispatch(parent, BasicOperation.exact(
                        "attachChild", "ownerChannel",
                        engine.embeddedDocumentRequest(child)));
                long elapsed = System.nanoTime() - started;
                var work = delta(before, engine.metricsSnapshot());
                host.add(Math.max(0L, elapsed - work.nanos("process.frozen")));
                merge(totals, work,
                        "frozenProcessCalls", "sessionsCreated",
                        "childHistoricalEntriesRead",
                        "childHistoricalProcessCalls",
                        "childRevisionApplications");
                assertEquals(20L, integer(
                        engine, "embedded-state-parent", "/child/counter"));
            }
        }
        return new ScenarioEvidence(host, totals);
    }

    private static ScenarioEvidence measureNestedCatchUp() throws Exception {
        LatencySeries host = new LatencySeries();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int sample = 0; sample < DOCUMENT_SAMPLES; sample++) {
            try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
                String leaf = resource("examples/clean/embedded-counter.yaml");
                String middle = resource("examples/clean/embedded-middle.yaml");
                engine.start("embedded-counter-A", leaf);
                Timeline leafTimeline = engine.timeline(
                        "examples/embedded/A", "alice");
                engine.appendAndDispatch(leafTimeline, BasicOperation.of(
                        "increment", "ownerChannel", "amount: 2"));
                engine.start("embedded-middle-A", middle);
                Timeline middleTimeline = engine.timeline(
                        "examples/embedded/middle", "middle-owner");
                engine.appendAndDispatch(middleTimeline, BasicOperation.exact(
                        "attachChild", "ownerChannel",
                        engine.embeddedDocumentRequest(leaf)));
                engine.start("embedded-root-B", resource(
                        "examples/clean/embedded-root.yaml"));
                Timeline root = engine.timeline(
                        "examples/embedded/root", "root-owner");
                EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.appendAndDispatch(root, BasicOperation.exact(
                        "attachChild", "ownerChannel",
                        engine.embeddedDocumentRequest(middle)));
                long elapsed = System.nanoTime() - started;
                var work = delta(before, engine.metricsSnapshot());
                host.add(Math.max(0L, elapsed - work.nanos("process.frozen")));
                merge(totals, work,
                        "frozenProcessCalls", "sessionsReused",
                        "childHistoricalProcessCalls",
                        "childRevisionApplications");
                assertEquals(2L, integer(
                        engine, "embedded-root-B", "/leafCounter"));
            }
        }
        return new ScenarioEvidence(host, totals);
    }

    private static ScenarioEvidence measureNbaCatchUp() throws Exception {
        LatencySeries host = new LatencySeries();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int sample = 0; sample < DOCUMENT_SAMPLES; sample++) {
            try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
                String game = resource("examples/clean/nba-game.yaml");
                engine.start("nba-game-2016-lal-min", game);
                Timeline feed = engine.timeline(
                        "examples/nba/game-2016-lal-min", "nba-feed");
                engine.appendAndDispatch(feed, BasicOperation.of(
                        "startGame", "gameFeed", "{}"));
                engine.appendAndDispatch(feed, BasicOperation.of(
                        "homeScores", "gameFeed", "points: 2"));
                engine.appendAndDispatch(feed, BasicOperation.of(
                        "awayScores", "gameFeed", "points: 3"));
                engine.appendAndDispatch(feed, BasicOperation.of(
                        "endGame", "gameFeed", "{}"));
                engine.start("nba-statistics", resource(
                        "examples/clean/nba-statistics.yaml"));
                Timeline commissioner = engine.timeline(
                        "examples/nba/statistics", "commissioner");
                EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.appendAndDispatch(commissioner, BasicOperation.exact(
                        "attachGame", "commissionerChannel",
                        engine.embeddedDocumentRequest(game)));
                long elapsed = System.nanoTime() - started;
                var work = delta(before, engine.metricsSnapshot());
                host.add(Math.max(0L, elapsed - work.nanos("process.frozen")));
                merge(totals, work,
                        "frozenProcessCalls", "sessionsReused",
                        "childHistoricalProcessCalls",
                        "childRevisionApplications");
                assertEquals(5L, integer(
                        engine, "nba-statistics", "/revisionApplications"));
            }
        }
        return new ScenarioEvidence(host, totals);
    }

    private static ScenarioEvidence measureLiveFanout() throws Exception {
        LatencySeries host = new LatencySeries();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (int sample = 0; sample < DOCUMENT_SAMPLES; sample++) {
            try (BasicCoordinationEngine engine = BasicCoordinationEngine.create()) {
                String child = resource("examples/clean/embedded-counter.yaml");
                engine.start("embedded-counter-A", child);
                Timeline childTimeline = engine.timeline(
                        "examples/embedded/A", "alice");
                String template = resource(
                        "examples/clean/embedded-state-parent.yaml");
                startStateParent(engine, template, "1", child);
                startStateParent(engine, template, "2", child);
                EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
                long started = System.nanoTime();
                engine.appendAndDispatch(childTimeline, BasicOperation.of(
                        "increment", "ownerChannel", "amount: 1"));
                long elapsed = System.nanoTime() - started;
                var work = delta(before, engine.metricsSnapshot());
                host.add(Math.max(0L, elapsed - work.nanos("process.frozen")));
                merge(totals, work,
                        "frozenProcessCalls", "childRevisionApplications",
                        "revisionApplicationReceiptsCommitted");
                assertEquals(1L, integer(engine,
                        "embedded-state-parent-1", "/child/counter"));
                assertEquals(1L, integer(engine,
                        "embedded-state-parent-2", "/child/counter"));
            }
        }
        return new ScenarioEvidence(host, totals);
    }

    private static void startStateParent(
            BasicCoordinationEngine engine,
            String template,
            String suffix,
            String child) {
        String documentId = "embedded-state-parent-" + suffix;
        String timelineId = "examples/embedded/state-parent-" + suffix;
        engine.start(documentId, template
                .replace("embedded-state-parent", documentId)
                .replace("examples/embedded/state-parent", timelineId)
                .replace("accountId: bob", "accountId: bob-" + suffix));
        Timeline timeline = engine.timeline(timelineId, "bob-" + suffix);
        engine.appendAndDispatch(timeline, BasicOperation.exact(
                "attachChild", "ownerChannel",
                engine.embeddedDocumentRequest(child)));
    }

    private static RuntimeComparisonWriter.Row singletonRow(
            String scenario,
            double value,
            String unit,
            String target,
            boolean pass,
            Map<String, Long> counters,
            String note) {
        return RuntimeComparisonWriter.scalar(
                scenario, BASELINE, value, unit, target, pass, counters, note);
    }

    private static void sampleAppend(
            BasicCoordinationEngine engine,
            Timeline timeline,
            BasicOperation operation,
            LatencySeries samples) {
        long started = System.nanoTime();
        engine.append(timeline, operation);
        samples.add(System.nanoTime() - started);
    }

    private static String counter(
            String yaml,
            String oldId,
            String newId) {
        return yaml.replace("documentId: " + oldId,
                "documentId: " + newId);
    }

    private static Map<String, Long> counters(
            BasicEngineTestSupport.MetricDelta work,
            String... names) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, work.counter(name));
        }
        return result;
    }

    private static void addLanguageCacheEvidence(
            Map<String, Long> evidence,
            BlueCacheStats cache) {
        long highWaterBytes = 0L;
        long hits = 0L;
        long misses = 0L;
        long evictions = 0L;
        long oversizedRejections = 0L;
        long pinnedRegions = 0L;
        for (BlueCacheStats.Region region : cache.regions().values()) {
            highWaterBytes = Math.addExact(
                    highWaterBytes, region.highWaterWeightBytes());
            hits = Math.addExact(hits, region.hits());
            misses = Math.addExact(misses, region.misses());
            evictions = Math.addExact(evictions, region.evictions());
            oversizedRejections = Math.addExact(
                    oversizedRejections, region.oversizedRejections());
            if (region.isPinned()) {
                pinnedRegions++;
            }
        }
        evidence.put("languageCacheRegions", (long) cache.regions().size());
        evidence.put("languageCacheEntries", (long) cache.entries());
        evidence.put("languageCacheWeightBytes", cache.currentWeightBytes());
        evidence.put("languageCacheHighWaterBytes", highWaterBytes);
        evidence.put("languageCacheHits", hits);
        evidence.put("languageCacheMisses", misses);
        evidence.put("languageCacheEvictions", evictions);
        evidence.put("languageCacheOversizedRejections", oversizedRejections);
        evidence.put("languageCachePinnedRegions", pinnedRegions);
    }

    private static void merge(
            Map<String, Long> totals,
            BasicEngineTestSupport.MetricDelta work,
            String... names) {
        for (String name : names) {
            totals.merge(name, work.counter(name), Math::addExact);
        }
    }

    private static void recordFailure(
            List<String> failures,
            String name,
            boolean passed) {
        if (!passed) {
            failures.add(name);
        }
    }

    private static void progress(String scenario) {
        collectClosedEnvironments();
        System.out.println("runtime campaign: " + scenario);
    }

    private static void collectClosedEnvironments() {
        System.gc();
        System.runFinalization();
    }

    private record AppendEvidence(
            LatencySeries tiny,
            LatencySeries payNote,
            Map<String, Long> counters) {
    }

    private record RouteEvidence(
            LatencySeries samples,
            Map<String, Long> counters) {
    }

    private record ProcessEvidence(
            LatencySeries frozen,
            LatencySeries host,
            Map<String, Long> counters) {
    }

    private record ScenarioEvidence(
            LatencySeries host,
            Map<String, Long> counters) {
    }
}
