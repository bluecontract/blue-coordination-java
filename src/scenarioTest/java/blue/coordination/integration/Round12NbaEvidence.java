package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.coordination.internal.CoordinationTestControl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes the exact single-run Round 12 NBA span and structural evidence. */
final class Round12NbaEvidence {
    /**
     * These production-backed phases are intentionally sparse across the
     * heterogeneous admission, external-entry, and embedded-transition spans
     * collected by this historical report. An absent entry means that phase
     * did not run for that span; every other timer remains fail-closed through
     * {@link EngineTestSupport.MetricDelta#nanos(String)}.
     */
    private static final Set<String> OPTIONAL_SPAN_PHASES = Set.of(
            "process.routeLookup",
            "temporal.graphPublication",
            "process.embeddedInputPreparation",
            "process.commitReadinessPublication");
    private static final String HOST_ONE_ADMISSION = "Host 1 admission";
    private static final String HOST_ONE_ATTACHMENT = "Host 1 attachment";
    private static final String GAME_START = "Game start";
    private static final String FIRST_SCORE = "Game first scoring operation";
    private static final String HOST_TWO_ADMISSION = "Host 2 admission";
    private static final String HOST_TWO_ATTACHMENT =
            "Host 2 attachment/catch-up";
    private static final String SECOND_SCORE =
            "Game second scoring operation";
    private static final String GAME_END = "Game end";
    private static final List<String> FORBIDDEN_COUNTERS = List.of(
            "REQUEST_FRAGMENTS",
            "TIMELINE_ENTRY_FRAGMENTS",
            "ORDINARY_NODE_FRAGMENTS",
            "FULL_ENVIRONMENT_SCANS",
            "SOURCE_REPLAYS_PER_PARENT",
            "POST_PROCESS_FULL_PROJECTIONS");
    private static final List<String> CAMPAIGN_SOURCES = List.of(
            "src/main/java/blue/coordination/internal/DefaultCoordinationEngine.java",
            "src/main/java/blue/coordination/internal/DocumentTransitionProcessor.java",
            "src/main/java/blue/coordination/internal/SequentialDrainCoordinator.java",
            "src/testFixtures/java/blue/coordination/internal/CoordinationTestControl.java",
            "src/integrationTest/java/blue/coordination/integration/EngineMetrics.java",
            "src/integrationTest/java/blue/coordination/integration/EngineTestSupport.java",
            "src/integrationTest/java/blue/coordination/integration/Round12NbaFixtures.java",
            "src/integrationTest/java/blue/coordination/integration/TestEngine.java",
            "src/integrationTest/resources/examples/round12/nba-lifecycle-game.yaml",
            "src/integrationTest/resources/examples/round12/nba-lifecycle-host.yaml",
            "src/scenarioTest/java/blue/coordination/integration/"
                    + "NbaSharedGameLifecycleAcrossHostsTest.java",
            "src/scenarioTest/java/blue/coordination/integration/Round12NbaEvidence.java");

    private final TestEngine engine;
    private final Map<String, Step> steps = new LinkedHashMap<>();

    private Round12NbaEvidence(TestEngine engine) {
        this.engine = engine;
        engine.beginTransitionTrace();
    }

    static Round12NbaEvidence begin(TestEngine engine) {
        return new Round12NbaEvidence(engine);
    }

    void admit(String name, String documentId, Runnable admission) {
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
        long started = System.nanoTime();
        admission.run();
        steps.put(name, new Step(
                documentId,
                null,
                0L,
                System.nanoTime() - started,
                EngineTestSupport.delta(before, engine.metricsSnapshot())));
    }

    TimelineEntry dispatch(
            String name,
            String documentId,
            Timeline timeline,
            Operation operation) {
        EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
        long started = System.nanoTime();
        long appendStarted = System.nanoTime();
        TimelineEntry entry = engine.append(timeline, operation);
        long appendNanos = System.nanoTime() - appendStarted;
        engine.dispatch(entry);
        steps.put(name, new Step(
                documentId,
                entry.blueId(),
                appendNanos,
                System.nanoTime() - started,
                EngineTestSupport.delta(before, engine.metricsSnapshot())));
        return entry;
    }

    Path write(
            String gameId,
            String hostOne,
            String hostTwo,
            EngineTestSupport.MetricDelta twoHostTotal) throws Exception {
        Step firstAttachment = requireStep(HOST_ONE_ATTACHMENT);
        Step gameEnd = requireStep(GAME_END);
        List<Span> spans = List.of(
                admissionSpan(HOST_ONE_ADMISSION, hostOne),
                stepSpan(HOST_ONE_ATTACHMENT),
                transitionSpan("Game initialization", trace(
                        gameId, "INITIALIZATION", firstAttachment.entryBlueId())),
                transitionSpan("Host 1 initialization application", trace(
                        hostOne, "EMBEDDED_REVISION_APPLICATION",
                        firstAttachment.entryBlueId())),
                stepSpan(GAME_START),
                stepSpan(FIRST_SCORE),
                admissionSpan(HOST_TWO_ADMISSION, hostTwo),
                stepSpan(HOST_TWO_ATTACHMENT),
                stepSpan(SECOND_SCORE),
                stepSpan(GAME_END),
                transitionSpan("Host 1 end application", trace(
                        hostOne, "EMBEDDED_REVISION_APPLICATION",
                        gameEnd.entryBlueId())),
                transitionSpan("Host 2 end application", trace(
                        hostTwo, "EMBEDDED_REVISION_APPLICATION",
                        gameEnd.entryBlueId())));
        Path repository = Path.of("").toAbsolutePath().normalize();
        Manifest main = manifest(repository, productionSources(repository));
        Manifest campaign = manifest(repository, CAMPAIGN_SOURCES.stream()
                .map(repository::resolve).toList());
        String json = json(
                spans, gameId, hostOne, hostTwo, twoHostTotal,
                main, campaign);
        Path configured = Path.of(System.getProperty(
                "blue.coordination.round12.nbaEvidence",
                "build/reports/round12/nba-shared-game-lifecycle.json"));
        Path output = configured.isAbsolute()
                ? configured : repository.resolve(configured);
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
        return output;
    }

    private Span admissionSpan(String name, String documentId) {
        Step step = requireStep(name);
        DocumentRevision revision = engine.history(documentId).get(0);
        return span(
                name,
                documentId,
                revision.epoch(),
                revision.kind().name(),
                null,
                revision.causalEntryBlueId().orElse(null),
                step.appendNanos(),
                step.totalNanos(),
                step.metrics(),
                true);
    }

    private Span stepSpan(String name) {
        Step step = requireStep(name);
        CoordinationTestControl.TransitionTrace trace = trace(
                step.documentId(), "TIMELINE_ENTRY", step.entryBlueId());
        return span(
                name,
                trace.documentId(),
                trace.epoch(),
                trace.kind(),
                trace.sourceEntryBlueId(),
                trace.causalEntryBlueId(),
                step.appendNanos(),
                step.totalNanos(),
                step.metrics(),
                false);
    }

    private Span transitionSpan(
            String name,
            CoordinationTestControl.TransitionTrace trace) {
        boolean initialization = "INITIALIZATION".equals(trace.kind());
        return span(
                name,
                trace.documentId(),
                trace.epoch(),
                trace.kind(),
                trace.sourceEntryBlueId(),
                trace.causalEntryBlueId(),
                0L,
                trace.totalNanos(),
                new EngineTestSupport.MetricDelta(
                        Map.of(), trace.phaseNanos()),
                initialization);
    }

    private static Span span(
            String name,
            String documentId,
            long epoch,
            String kind,
            String sourceEntryBlueId,
            String causalEntryBlueId,
            long append,
            long total,
            EngineTestSupport.MetricDelta metrics,
            boolean initialization) {
        String prefix = initialization ? "documentStart." : "process.";
        return new Span(
                name,
                documentId,
                epoch,
                kind,
                sourceEntryBlueId,
                causalEntryBlueId,
                append,
                optionalSpanNanos(metrics, "process.routeLookup"),
                metrics.nanos(prefix + "hostBeforeFrozen"),
                metrics.nanos(initialization
                        ? "documentStart.contractsInitialize"
                        : "process.frozen"),
                metrics.nanos(prefix + "hostAfterFrozen"),
                optionalSpanNanos(metrics, "temporal.graphPublication"),
                optionalSpanNanos(metrics,
                        "process.embeddedInputPreparation"),
                optionalSpanNanos(metrics,
                        "process.commitReadinessPublication"),
                total);
    }

    private static long optionalSpanNanos(
            EngineTestSupport.MetricDelta metrics,
            String phase) {
        if (!OPTIONAL_SPAN_PHASES.contains(phase)) {
            throw new AssertionError(
                    "Unknown optional Round 12 span phase '" + phase + "'");
        }
        return metrics.phaseNanos().getOrDefault(phase, 0L);
    }

    private CoordinationTestControl.TransitionTrace trace(
            String documentId,
            String kind,
            String causalOrSourceEntryBlueId) {
        return engine.transitionTrace().stream()
                .filter(candidate -> documentId.equals(candidate.documentId()))
                .filter(candidate -> kind.equals(candidate.kind()))
                .filter(candidate -> causalOrSourceEntryBlueId.equals(
                        "TIMELINE_ENTRY".equals(kind)
                                ? candidate.sourceEntryBlueId()
                                : candidate.causalEntryBlueId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing transition trace for " + documentId
                                + " " + kind + " "
                                + causalOrSourceEntryBlueId));
    }

    private Step requireStep(String name) {
        Step step = steps.get(name);
        if (step == null) {
            throw new IllegalStateException("Missing NBA evidence step " + name);
        }
        return step;
    }

    private String json(
            List<Span> spans,
            String gameId,
            String hostOne,
            String hostTwo,
            EngineTestSupport.MetricDelta total,
            Manifest main,
            Manifest campaign) {
        StringBuilder out = new StringBuilder(12_000);
        out.append("{\n")
                .append("  \"schemaVersion\": \"round12-nba-evidence-v1\",\n")
                .append("  \"status\": \"MEASURED_SINGLE_SCENARIO\",\n")
                .append("  \"spanSemantics\": ")
                .append(quote("Logical operation spans are inclusive; nested "
                        + "initialization and parent-application spans are "
                        + "reported independently and must not be summed."))
                .append(",\n")
                .append("  \"sourceState\": {\n")
                .append("    \"mainSourceManifestAlgorithm\": ")
                .append(quote("SHA-256 of LF-joined sorted repository path, "
                        + "ASCII space, file SHA-256; no trailing LF"))
                .append(",\n")
                .append("    \"mainSourceManifestSha256\": ")
                .append(quote(main.sha256())).append(",\n")
                .append("    \"mainSourceFiles\": ").append(main.files())
                .append(",\n")
                .append("    \"mainSourceLines\": ").append(main.lines())
                .append(",\n")
                .append("    \"campaignSourcesSha256\": ")
                .append(quote(campaign.sha256())).append(",\n")
                .append("    \"campaignSourceFiles\": ")
                .append(campaign.files()).append("\n")
                .append("  },\n")
                .append("  \"runtime\": {\n")
                .append("    \"javaVersion\": ")
                .append(quote(System.getProperty("java.version"))).append(",\n")
                .append("    \"javaVendor\": ")
                .append(quote(System.getProperty("java.vendor"))).append(",\n")
                .append("    \"osName\": ")
                .append(quote(System.getProperty("os.name"))).append(",\n")
                .append("    \"osArch\": ")
                .append(quote(System.getProperty("os.arch"))).append("\n")
                .append("  },\n")
                .append("  \"spans\": [\n");
        for (int i = 0; i < spans.size(); i++) {
            appendSpan(out, spans.get(i));
            out.append(i + 1 == spans.size() ? "\n" : ",\n");
        }
        out.append("  ],\n")
                .append("  \"hardEvidence\": {\n")
                .append("    \"gameSessionsCreated\": ")
                .append(total.counter("embedding.childSessionsCreated"))
                .append(",\n")
                .append("    \"gameSessionsReused\": ")
                .append(total.counter("embedding.childSessionsReused"))
                .append(",\n")
                .append("    \"gameInitializationRevisions\": ")
                .append(engine.history(gameId).stream().filter(revision ->
                        revision.kind() == DocumentRevision.Kind.INITIALIZATION)
                        .count()).append(",\n")
                .append("    \"gameInitializationCount\": ")
                .append(EngineTestSupport.integer(
                        engine, gameId, "/initializationCount"))
                .append(",\n")
                .append("    \"host1InitializationEventCount\": ")
                .append(EngineTestSupport.integer(
                        engine, hostOne, "/gameInitializationEventCount"))
                .append(",\n")
                .append("    \"host2InitializationEventCount\": ")
                .append(EngineTestSupport.integer(
                        engine, hostTwo, "/gameInitializationEventCount"))
                .append(",\n")
                .append("    \"host1EndingEventCount\": ")
                .append(EngineTestSupport.integer(
                        engine, hostOne, "/gameEndedEventCount"))
                .append(",\n")
                .append("    \"host2EndingEventCount\": ")
                .append(EngineTestSupport.integer(
                        engine, hostTwo, "/gameEndedEventCount"))
                .append(",\n")
                .append("    \"gameSourceProcessCalls\": {\n")
                .append("      \"start\": ")
                .append(requireStep(GAME_START).metrics().counter(
                        "EXTERNAL_PROCESS_CALLS")).append(",\n")
                .append("      \"firstScore\": ")
                .append(requireStep(FIRST_SCORE).metrics().counter(
                        "EXTERNAL_PROCESS_CALLS")).append(",\n")
                .append("      \"secondScore\": ")
                .append(requireStep(SECOND_SCORE).metrics().counter(
                        "EXTERNAL_PROCESS_CALLS")).append(",\n")
                .append("      \"end\": ")
                .append(requireStep(GAME_END).metrics().counter(
                        "EXTERNAL_PROCESS_CALLS")).append("\n")
                .append("    },\n")
                .append("    \"initializationEpochApplications\": ")
                .append(total.counter(
                        "temporal.initializationEpochApplications"))
                .append(",\n")
                .append("    \"initializationEventOccurrencesForwarded\": ")
                .append(total.counter(
                        "temporal.initializationEventOccurrencesForwarded"))
                .append(",\n")
                .append("    \"parentEpochApplications\": ")
                .append(total.counter("temporal.parentEpochApplications"))
                .append(",\n")
                .append("    \"gameRevisions\": ")
                .append(engine.history(gameId).size()).append("\n")
                .append("  },\n")
                .append("  \"forbiddenCounters\": {\n");
        for (int i = 0; i < FORBIDDEN_COUNTERS.size(); i++) {
            String counter = FORBIDDEN_COUNTERS.get(i);
            out.append("    ").append(quote(counter)).append(": ")
                    .append(total.counter(counter))
                    .append(i + 1 == FORBIDDEN_COUNTERS.size()
                            ? "\n" : ",\n");
        }
        out.append("  },\n")
                .append("  \"repeatedThreeScenarioP95\": {\n")
                .append("    \"status\": \"PENDING_VERIFICATION\",\n")
                .append("    \"historicalBaselineHostMs\": {\n")
                .append("      \"warmOrdinary\": 15.571,\n")
                .append("      \"warmPayNotePlusParent\": 46.645,\n")
                .append("      \"threeLayerRestaurantProduct\": 54.696\n")
                .append("    },\n")
                .append("    \"round12CandidateP95Ms\": null,\n")
                .append("    \"regressionPercent\": null,\n")
                .append("    \"reason\": ")
                .append(quote("This artifact is one NBA lifecycle run, not "
                        + "the required repeated same-source three-scenario "
                        + "host p95 campaign."))
                .append("\n  }\n")
                .append("}\n");
        return out.toString();
    }

    private static void appendSpan(StringBuilder out, Span span) {
        out.append("    {\n")
                .append("      \"name\": ").append(quote(span.name()))
                .append(",\n      \"documentId\": ")
                .append(quote(span.documentId()))
                .append(",\n      \"epoch\": ").append(span.epoch())
                .append(",\n      \"transitionKind\": ")
                .append(quote(span.kind()))
                .append(",\n      \"sourceEntryBlueId\": ")
                .append(nullable(span.sourceEntryBlueId()))
                .append(",\n      \"causalEntryBlueId\": ")
                .append(nullable(span.causalEntryBlueId()))
                .append(",\n      \"nanos\": {\n")
                .append("        \"append\": ").append(span.append())
                .append(",\n        \"routeLookup\": ")
                .append(span.routeLookup())
                .append(",\n        \"hostBeforeFrozen\": ")
                .append(span.hostBeforeFrozen())
                .append(",\n        \"frozenProcess\": ")
                .append(span.frozenProcess())
                .append(",\n        \"hostAfterFrozen\": ")
                .append(span.hostAfterFrozen())
                .append(",\n        \"graphPublication\": ")
                .append(span.graphPublication())
                .append(",\n        \"embeddedInputPreparation\": ")
                .append(span.embeddedInputPreparation())
                .append(",\n        \"commitReadinessPublication\": ")
                .append(span.commitReadinessPublication())
                .append(",\n        \"total\": ").append(span.total())
                .append("\n      }\n    }");
    }

    private static List<Path> productionSources(Path repository)
            throws IOException {
        try (var files = Files.walk(repository.resolve("src/main/java"))) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> portable(
                            repository.relativize(path))))
                    .toList();
        }
    }

    private static Manifest manifest(Path repository, List<Path> paths)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest manifest = MessageDigest.getInstance("SHA-256");
        long lines = 0L;
        List<Path> sorted = new ArrayList<>(paths);
        sorted.sort(Comparator.comparing(path -> portable(
                repository.relativize(path))));
        for (int index = 0; index < sorted.size(); index++) {
            Path path = sorted.get(index);
            byte[] bytes = Files.readAllBytes(path);
            String relative = portable(repository.relativize(path));
            String fileHash = hex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
            if (index > 0) {
                manifest.update((byte) '\n');
            }
            manifest.update(relative.getBytes(StandardCharsets.UTF_8));
            manifest.update((byte) ' ');
            manifest.update(fileHash.getBytes(StandardCharsets.US_ASCII));
            for (byte value : bytes) {
                if (value == '\n') {
                    lines++;
                }
            }
        }
        return new Manifest(hex(manifest.digest()), sorted.size(), lines);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String nullable(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2)
                .append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.append('"').toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private record Step(
            String documentId,
            String entryBlueId,
            long appendNanos,
            long totalNanos,
            EngineTestSupport.MetricDelta metrics) {
    }

    private record Span(
            String name,
            String documentId,
            long epoch,
            String kind,
            String sourceEntryBlueId,
            String causalEntryBlueId,
            long append,
            long routeLookup,
            long hostBeforeFrozen,
            long frozenProcess,
            long hostAfterFrozen,
            long graphPublication,
            long embeddedInputPreparation,
            long commitReadinessPublication,
            long total) {
    }

    private record Manifest(String sha256, int files, long lines) {
    }
}
