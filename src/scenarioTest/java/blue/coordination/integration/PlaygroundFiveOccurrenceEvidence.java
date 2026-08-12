package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Manual Round 13 sample and candidate-campaign writer.
 *
 * <p>This is a scenario-source utility rather than a JUnit test so the repeated
 * campaign never enters {@code check}, {@code releaseCheck}, or the default edit
 * loop. The corresponding Gradle tasks launch it explicitly.</p>
 */
final class PlaygroundFiveOccurrenceEvidence {
    private static final String SAMPLE_PROPERTY =
            "blue.coordination.round13.sample";
    private static final String CAMPAIGN_PROPERTY =
            "blue.coordination.round13.campaign";
    private static final String WARMUPS_PROPERTY =
            "blue.coordination.round13.warmups";
    private static final String SAMPLES_PROPERTY =
            "blue.coordination.round13.samples";
    private static final int DEFAULT_WARMUPS = 3;
    private static final int DEFAULT_SAMPLES = 30;
    private static final RuntimeIdentity RUNTIME_IDENTITY =
            RuntimeIdentity.current();

    private PlaygroundFiveOccurrenceEvidence() {
    }

    /** Entry point used only by the focused {@code JavaExec} tasks. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one mode: sample or campaign");
        }
        int warmups = integerProperty(WARMUPS_PROPERTY, DEFAULT_WARMUPS, 0);
        switch (arguments[0]) {
            case "sample" -> writeSample(
                    requiredPathProperty(SAMPLE_PROPERTY), warmups);
            case "campaign" -> writeCampaign(
                    requiredPathProperty(CAMPAIGN_PROPERTY),
                    warmups,
                    integerProperty(SAMPLES_PROPERTY, DEFAULT_SAMPLES, 1));
            default -> throw new IllegalArgumentException(
                    "Unknown Round 13 evidence mode: " + arguments[0]);
        }
    }

    private static void writeSample(Path output, int warmups)
            throws Exception {
        Result result = warmedSample(warmups);
        createParent(output);
        Files.writeString(
                output,
                result.toJson(),
                StandardCharsets.UTF_8);
        System.out.println("ROUND13_PLAYGROUND_SAMPLE="
                + output.toAbsolutePath());
    }

    private static void writeCampaign(
            Path outputDirectory,
            int warmups,
            int sampleCount) throws Exception {
        for (int index = 0; index < warmups; index++) {
            runOnce();
        }
        List<Result> results = new ArrayList<>(sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            results.add(runOnce());
        }
        Campaign campaign = Campaign.from(warmups, results);
        Files.createDirectories(outputDirectory);
        Path json = outputDirectory.resolve(
                "five-occurrence-runtime.json");
        Path markdown = outputDirectory.resolve(
                "five-occurrence-runtime.md");
        Files.writeString(json, campaign.toJson(), StandardCharsets.UTF_8);
        Files.writeString(
                markdown,
                campaign.toMarkdown(),
                StandardCharsets.UTF_8);
        System.out.println("ROUND13_PLAYGROUND_CAMPAIGN="
                + outputDirectory.toAbsolutePath());
    }

    private static Result warmedSample(int warmups) throws Exception {
        for (int index = 0; index < warmups; index++) {
            runOnce();
        }
        return runOnce();
    }

    private static Result runOnce() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.HOST_TIMELINE,
                    PlaygroundFiveOccurrenceFixtures.HOST_ACTOR);
            PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
            engine.start(
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    EngineTestSupport.resource(
                            PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE));

            int wholeObjectsBefore = engine.wholeObjectCount();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            long totalStarted = System.nanoTime();
            long preparationStarted = System.nanoTime();
            Operation attachFive =
                    PlaygroundFiveOccurrenceFixtures.attachFive(engine);
            long preparationNanos = System.nanoTime() - preparationStarted;
            int wholeObjectsAdded =
                    engine.wholeObjectCount() - wholeObjectsBefore;

            long appendStarted = System.nanoTime();
            TimelineEntry entry = engine.append(owner, attachFive);
            long appendNanos = System.nanoTime() - appendStarted;
            engine.dispatch(entry);
            long totalNanos = System.nanoTime() - totalStarted;
            EngineTestSupport.MetricDelta delta = EngineTestSupport.delta(
                    before, engine.metricsSnapshot());
            long processFrozenNanos = delta.nanos("process.frozen");
            long initializationFrozenNanos = delta.nanos(
                    "documentStart.contractsInitialize");

            List<String> paths = applicationPaths(engine);
            long initializationRevisions = List.of(
                            "playground-game-alpha",
                            "playground-game-beta",
                            "playground-game-gamma").stream()
                    .mapToLong(id -> engine.history(id).stream()
                            .filter(revision -> revision.kind()
                                    == DocumentRevision.Kind.INITIALIZATION)
                            .count())
                    .sum();

            Result result = new Result(
                    entry.blueId(),
                    paths,
                    RUNTIME_IDENTITY,
                    preparationNanos,
                    appendNanos,
                    totalNanos,
                    delta.nanos("process.routeLookup"),
                    delta.nanos("process.embeddedInputPreparation"),
                    delta.nanos("process.hostBeforeFrozen"),
                    processFrozenNanos,
                    initializationFrozenNanos,
                    Math.addExact(
                            processFrozenNanos,
                            initializationFrozenNanos),
                    delta.nanos("process.hostAfterFrozen"),
                    delta.nanos("documentStart.hostBeforeFrozen"),
                    delta.nanos("documentStart.hostAfterFrozen"),
                    delta.nanos("temporal.graphPublication"),
                    wholeObjectsAdded,
                    delta.counter("wholeObjectStore.insertions"),
                    delta.counter("wholeObjectStore.duplicates"),
                    engine.journalSize(),
                    engine.documentCount(),
                    engine.routeRowCount(),
                    engine.embeddedDocuments(
                            PlaygroundFiveOccurrenceFixtures.HOST_ID).size(),
                    delta.counter("embedding.childSessionsCreated"),
                    delta.counter("embedding.childSessionsReused"),
                    delta.counter("DOCUMENT_INITIALIZATIONS"),
                    initializationRevisions,
                    delta.counter("EXTERNAL_PROCESS_CALLS"),
                    delta.counter("EMBEDDED_EPOCH_PROCESS_CALLS"),
                    delta.counter(
                            "temporal.initializationEpochApplications"),
                    delta.counter(
                            "temporal.initializationEventOccurrencesForwarded"),
                    delta.counter("PARENT_EPOCH_APPLICATIONS"),
                    delta.counter("frozenProcessCalls"),
                    integer(engine, "/initializationEventCount"),
                    integer(engine, "/alphaInitializationEventCount"),
                    integer(engine, "/betaInitializationEventCount"),
                    integer(engine, "/gammaInitializationEventCount"),
                    integer(engine, "/revisionApplications"),
                    forbidden(delta));
            result.verify();
            return result;
        }
    }

    private static long integer(TestEngine engine, String pointer) {
        Object value = engine.value(
                PlaygroundFiveOccurrenceFixtures.HOST_ID,
                pointer).getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException(
                "Expected integer Host value at " + pointer);
    }

    /**
     * Uses committed occurrence causes when the candidate exposes them. The
     * exact Round 13 baseline predates that audit field, so the measurement
     * harness may fall back to the read-only completed catch-up projection.
     * This keeps the A/B production trees exact while applying the same
     * five-occurrence correctness assertion to both sides.
     */
    private static List<String> applicationPaths(TestEngine engine) {
        List<String> committedPaths = engine.history(
                        PlaygroundFiveOccurrenceFixtures.HOST_ID).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind
                        .EMBEDDED_REVISION_APPLICATION)
                .flatMap(revision -> revision.catchUpCause().stream())
                .map(DocumentRevision.CatchUpCause::occurrencePath)
                .toList();
        if (committedPaths.size() == 5) {
            return committedPaths;
        }
        return engine.catchUpPlans().stream()
                .map(CatchUpPlan::link)
                .filter(link -> link.parentDocumentId().value().equals(
                        PlaygroundFiveOccurrenceFixtures.HOST_ID))
                .map(CatchUpPlan.Link::occurrencePath)
                .sorted()
                .toList();
    }

    private static Map<String, Long> forbidden(
            EngineTestSupport.MetricDelta delta) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : List.of(
                "UNRELATED_DOCUMENT_READS",
                "REQUEST_FRAGMENTS",
                "TIMELINE_ENTRY_FRAGMENTS",
                "ORDINARY_NODE_FRAGMENTS",
                "FULL_ENVIRONMENT_SCANS",
                "SOURCE_REPLAYS_PER_PARENT",
                "POST_PROCESS_FULL_PROJECTIONS",
                "PARENT_PROCESS_RERUNS_ON_GRAPH_RETRY",
                "CHILD_PROCESS_RERUNS_ON_PARENT_RETRY")) {
            result.put(name, delta.counter(name));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Path requiredPathProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required system property -D" + name);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static int integerProperty(
            String name,
            int defaultValue,
            int minimum) {
        String configured = System.getProperty(name);
        int value = configured == null
                ? defaultValue : Integer.parseInt(configured);
        if (value < minimum) {
            throw new IllegalArgumentException(
                    name + " must be at least " + minimum);
        }
        return value;
    }

    private static void createParent(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String quote(String value) {
        Objects.requireNonNull(value, "JSON string");
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\b' -> quoted.append("\\b");
                case '\f' -> quoted.append("\\f");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20
                            || character == '\u2028'
                            || character == '\u2029') {
                        appendUnicodeEscape(quoted, character);
                    } else if (Character.isHighSurrogate(character)) {
                        require(index + 1 < value.length()
                                        && Character.isLowSurrogate(
                                        value.charAt(index + 1)),
                                "JSON string contains an unpaired "
                                        + "high surrogate");
                        quoted.append(character)
                                .append(value.charAt(++index));
                    } else {
                        require(!Character.isLowSurrogate(character),
                                "JSON string contains an unpaired "
                                        + "low surrogate");
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static void appendUnicodeEscape(
            StringBuilder output,
            char value) {
        final String hex = "0123456789abcdef";
        output.append("\\u")
                .append(hex.charAt((value >>> 12) & 0x0f))
                .append(hex.charAt((value >>> 8) & 0x0f))
                .append(hex.charAt((value >>> 4) & 0x0f))
                .append(hex.charAt(value & 0x0f));
    }

    private static String requireNonBlank(
            String value,
            String name) {
        Objects.requireNonNull(value, name + " must be present");
        require(!value.isBlank(), name + " must not be blank");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    record RuntimeIdentity(
            String javaVersion,
            String javaVendor,
            String javaHome,
            String osName,
            String osVersion,
            String osArch,
            List<String> jvmInputArguments,
            int availableProcessors) {
        RuntimeIdentity {
            javaVersion = requireNonBlank(javaVersion, "java.version");
            javaVendor = requireNonBlank(javaVendor, "java.vendor");
            javaHome = requireNonBlank(javaHome, "java.home");
            osName = requireNonBlank(osName, "os.name");
            osVersion = requireNonBlank(osVersion, "os.version");
            osArch = requireNonBlank(osArch, "os.arch");
            Objects.requireNonNull(
                    jvmInputArguments,
                    "JVM input arguments must be present");
            List<String> checkedArguments = new ArrayList<>(
                    jvmInputArguments.size());
            for (int index = 0;
                    index < jvmInputArguments.size();
                    index++) {
                checkedArguments.add(requireNonBlank(
                        jvmInputArguments.get(index),
                        "JVM input argument " + index));
            }
            jvmInputArguments = List.copyOf(checkedArguments);
            require(availableProcessors > 0,
                    "available processors must be positive");
        }

        static RuntimeIdentity current() {
            return new RuntimeIdentity(
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    System.getProperty("java.home"),
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    ManagementFactory.getRuntimeMXBean()
                            .getInputArguments().stream()
                            .filter(argument -> !argument.startsWith(
                                    "-D" + SAMPLE_PROPERTY + "="))
                            .toList(),
                    Runtime.getRuntime().availableProcessors());
        }

        String toJson() {
            StringBuilder json = new StringBuilder(512);
            json.append("{\"javaVersion\": ")
                    .append(quote(javaVersion))
                    .append(", \"javaVendor\": ")
                    .append(quote(javaVendor))
                    .append(", \"javaHome\": ")
                    .append(quote(javaHome))
                    .append(", \"osName\": ")
                    .append(quote(osName))
                    .append(", \"osVersion\": ")
                    .append(quote(osVersion))
                    .append(", \"osArch\": ")
                    .append(quote(osArch))
                    .append(", \"jvmInputArguments\": [");
            for (int index = 0;
                    index < jvmInputArguments.size();
                    index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append(quote(jvmInputArguments.get(index)));
            }
            return json.append("], \"availableProcessors\": ")
                    .append(availableProcessors)
                    .append('}')
                    .toString();
        }
    }

    record Result(
            String entryBlueId,
            List<String> applicationPaths,
            RuntimeIdentity runtime,
            long requestPreparationNanos,
            long appendNanos,
            long totalNanos,
            long routeNanos,
            long embeddedInputPreparationNanos,
            long hostBeforeFrozenNanos,
            long processFrozenNanos,
            long initializationFrozenNanos,
            long frozenNanos,
            long hostAfterFrozenNanos,
            long initializationHostBeforeFrozenNanos,
            long initializationHostAfterFrozenNanos,
            long graphPublicationNanos,
            long wholeObjectsAdded,
            long wholeObjectInsertions,
            long wholeObjectDuplicates,
            long journalEntries,
            long documentCount,
            long routeRows,
            long embeddingBindings,
            long childSessionsCreated,
            long childSessionsReused,
            long childInitializationCalls,
            long childInitializationRevisions,
            long externalProcessCalls,
            long embeddedEpochProcessCalls,
            long initializationApplications,
            long initializationEventsForwarded,
            long parentApplications,
            long frozenProcessCalls,
            long hostInitializationEvents,
            long alphaInitializationEvents,
            long betaInitializationEvents,
            long gammaInitializationEvents,
            long hostRevisionApplications,
            Map<String, Long> forbiddenCounters) {
        Result {
            applicationPaths = List.copyOf(applicationPaths);
            runtime = Objects.requireNonNull(
                    runtime, "runtime identity must be present");
            forbiddenCounters = Collections.unmodifiableMap(
                    new LinkedHashMap<>(forbiddenCounters));
        }

        long coordinationHostNanos() {
            // The named host/graph timers overlap in a few nested paths. Use
            // the wall-clock remainder so the aggregate never double counts.
            return totalNanos - frozenNanos;
        }

        void verify() {
            require(totalNanos >= frozenNanos,
                    "frozen processing must be contained in total runtime");
            require(wholeObjectsAdded == 4L,
                    "request preparation must add four whole objects");
            require(journalEntries == 1L,
                    "one external Host entry must be journaled");
            require(documentCount == 4L,
                    "one Host plus three child sessions required");
            require(embeddingBindings == 5L,
                    "five embedding bindings required");
            require(childSessionsCreated == 3L,
                    "three child sessions must be created");
            require(childSessionsReused == 2L,
                    "two child occurrences must reuse sessions");
            require(childInitializationCalls == 3L,
                    "three child initialization calls required");
            require(childInitializationRevisions == 3L,
                    "each child must have one initialization revision");
            require(externalProcessCalls == 1L,
                    "one external Host PROCESS call required");
            require(embeddedEpochProcessCalls == 5L,
                    "five embedded-epoch PROCESS calls required");
            require(frozenProcessCalls == 6L,
                    "one external plus five embedded frozen PROCESS calls "
                            + "required");
            require(initializationApplications == 5L,
                    "five initialization applications required");
            require(initializationEventsForwarded == 5L,
                    "five forwarded initialization events required");
            require(parentApplications == 5L,
                    "five parent applications required");
            require(hostInitializationEvents == 5L
                            && alphaInitializationEvents == 2L
                            && betaInitializationEvents == 2L
                            && gammaInitializationEvents == 1L,
                    "Host event multiplicity must be 2/2/1");
            require(hostRevisionApplications == 5L,
                    "Host must commit five embedded revisions");
            require(applicationPaths.equals(
                            PlaygroundFiveOccurrenceFixtures
                                    .expectedApplicationPaths()),
                    "parent application paths must be canonical");
            require(forbiddenCounters.values().stream()
                            .allMatch(value -> value == 0L),
                    "produced forbidden-work counters must all be zero");
        }

        String toJson() {
            StringBuilder json = new StringBuilder(2_048);
            json.append("{\n")
                    .append("  \"schemaVersion\": \"round13-playground-sample-v3\",\n")
                    .append("  \"runtime\": ")
                    .append(runtime.toJson()).append(",\n")
                    .append("  \"entryBlueId\": ")
                    .append(quote(entryBlueId)).append(",\n")
                    .append("  \"applicationPaths\": [");
            for (int index = 0; index < applicationPaths.size(); index++) {
                if (index > 0) {
                    json.append(", ");
                }
                json.append(quote(applicationPaths.get(index)));
            }
            json.append("],\n");
            appendNumber(json, "requestPreparationNanos",
                    requestPreparationNanos);
            appendNumber(json, "appendNanos", appendNanos);
            appendNumber(json, "totalNanos", totalNanos);
            appendNumber(json, "routeNanos", routeNanos);
            appendNumber(json, "embeddedInputPreparationNanos",
                    embeddedInputPreparationNanos);
            appendNumber(json, "hostBeforeFrozenNanos",
                    hostBeforeFrozenNanos);
            appendNumber(json, "processFrozenNanos", processFrozenNanos);
            appendNumber(json, "initializationFrozenNanos",
                    initializationFrozenNanos);
            appendNumber(json, "frozenNanos", frozenNanos);
            appendNumber(json, "hostAfterFrozenNanos",
                    hostAfterFrozenNanos);
            appendNumber(json, "initializationHostBeforeFrozenNanos",
                    initializationHostBeforeFrozenNanos);
            appendNumber(json, "initializationHostAfterFrozenNanos",
                    initializationHostAfterFrozenNanos);
            appendNumber(json, "graphPublicationNanos",
                    graphPublicationNanos);
            appendNumber(json, "coordinationHostNanos",
                    coordinationHostNanos());
            appendNumber(json, "wholeObjectsAdded", wholeObjectsAdded);
            appendNumber(json, "wholeObjectInsertions",
                    wholeObjectInsertions);
            appendNumber(json, "wholeObjectDuplicates",
                    wholeObjectDuplicates);
            appendNumber(json, "journalEntries", journalEntries);
            appendNumber(json, "documentCount", documentCount);
            appendNumber(json, "routeRows", routeRows);
            appendNumber(json, "embeddingBindings", embeddingBindings);
            appendNumber(json, "childSessionsCreated",
                    childSessionsCreated);
            appendNumber(json, "childSessionsReused", childSessionsReused);
            appendNumber(json, "childInitializationCalls",
                    childInitializationCalls);
            appendNumber(json, "childInitializationRevisions",
                    childInitializationRevisions);
            appendNumber(json, "externalProcessCalls",
                    externalProcessCalls);
            appendNumber(json, "embeddedEpochProcessCalls",
                    embeddedEpochProcessCalls);
            appendNumber(json, "initializationApplications",
                    initializationApplications);
            appendNumber(json, "initializationEventsForwarded",
                    initializationEventsForwarded);
            appendNumber(json, "parentApplications", parentApplications);
            appendNumber(json, "frozenProcessCalls", frozenProcessCalls);
            appendNumber(json, "hostInitializationEvents",
                    hostInitializationEvents);
            appendNumber(json, "alphaInitializationEvents",
                    alphaInitializationEvents);
            appendNumber(json, "betaInitializationEvents",
                    betaInitializationEvents);
            appendNumber(json, "gammaInitializationEvents",
                    gammaInitializationEvents);
            appendNumber(json, "hostRevisionApplications",
                    hostRevisionApplications);
            json.append("  \"forbiddenCounters\": {");
            int index = 0;
            for (Map.Entry<String, Long> entry
                    : forbiddenCounters.entrySet()) {
                if (index++ > 0) {
                    json.append(", ");
                }
                json.append(quote(entry.getKey()))
                        .append(": ").append(entry.getValue());
            }
            return json.append("}\n}\n").toString();
        }

        private static void appendNumber(
                StringBuilder json,
                String name,
                long value) {
            json.append("  ").append(quote(name)).append(": ")
                    .append(value).append(",\n");
        }
    }

    record Distribution(long p50Nanos, long p95Nanos, long maximumNanos) {
        static Distribution from(
                List<Result> results,
                ToLongFunction<Result> measurement) {
            long[] values = results.stream()
                    .mapToLong(measurement)
                    .sorted()
                    .toArray();
            int p50Index = Math.max(0,
                    (int) Math.ceil(values.length * 0.50) - 1);
            int p95Index = Math.max(0,
                    (int) Math.ceil(values.length * 0.95) - 1);
            return new Distribution(
                    values[p50Index],
                    values[p95Index],
                    values[values.length - 1]);
        }
    }

    record Campaign(
            int warmups,
            int samples,
            Map<String, Distribution> distributions,
            RuntimeIdentity runtime,
            Result representative) {
        Campaign {
            distributions = Collections.unmodifiableMap(
                    new LinkedHashMap<>(distributions));
            runtime = Objects.requireNonNull(
                    runtime, "campaign runtime identity must be present");
        }

        static Campaign from(int warmups, List<Result> results) {
            require(!results.isEmpty(), "campaign requires samples");
            RuntimeIdentity runtime = results.get(0).runtime();
            require(results.stream().allMatch(
                            result -> result.runtime().equals(runtime)),
                    "campaign runtime identity must remain stable");
            Map<String, Distribution> distributions =
                    new LinkedHashMap<>();
            distributions.put("requestPreparationNanos",
                    Distribution.from(results,
                            Result::requestPreparationNanos));
            distributions.put("appendNanos",
                    Distribution.from(results, Result::appendNanos));
            distributions.put("routeNanos",
                    Distribution.from(results, Result::routeNanos));
            distributions.put("embeddedInputPreparationNanos",
                    Distribution.from(results,
                            Result::embeddedInputPreparationNanos));
            distributions.put("hostBeforeFrozenNanos",
                    Distribution.from(results,
                            Result::hostBeforeFrozenNanos));
            distributions.put("processFrozenNanos",
                    Distribution.from(results, Result::processFrozenNanos));
            distributions.put("initializationFrozenNanos",
                    Distribution.from(results,
                            Result::initializationFrozenNanos));
            distributions.put("frozenNanos",
                    Distribution.from(results, Result::frozenNanos));
            distributions.put("hostAfterFrozenNanos",
                    Distribution.from(results,
                            Result::hostAfterFrozenNanos));
            distributions.put("initializationHostBeforeFrozenNanos",
                    Distribution.from(results,
                            Result::initializationHostBeforeFrozenNanos));
            distributions.put("initializationHostAfterFrozenNanos",
                    Distribution.from(results,
                            Result::initializationHostAfterFrozenNanos));
            distributions.put("graphPublicationNanos",
                    Distribution.from(results,
                            Result::graphPublicationNanos));
            distributions.put("coordinationHostNanos",
                    Distribution.from(results,
                            Result::coordinationHostNanos));
            distributions.put("totalNanos",
                    Distribution.from(results, Result::totalNanos));
            return new Campaign(
                    warmups,
                    results.size(),
                    distributions,
                    runtime,
                    results.get(results.size() - 1));
        }

        String toJson() {
            StringBuilder json = new StringBuilder(4_096);
            json.append("{\n")
                    .append("  \"schemaVersion\": \"round13-playground-campaign-v3\",\n")
                    .append("  \"generatedAt\": ")
                    .append(quote(Instant.now().toString())).append(",\n")
                    .append("  \"runtime\": ")
                    .append(runtime.toJson()).append(",\n")
                    .append("  \"warmups\": ").append(warmups)
                    .append(",\n  \"samples\": ").append(samples)
                    .append(",\n  \"distributions\": {\n");
            int index = 0;
            for (Map.Entry<String, Distribution> entry
                    : distributions.entrySet()) {
                if (index++ > 0) {
                    json.append(",\n");
                }
                Distribution value = entry.getValue();
                json.append("    ").append(quote(entry.getKey()))
                        .append(": {\"p50Nanos\": ")
                        .append(value.p50Nanos())
                        .append(", \"p95Nanos\": ")
                        .append(value.p95Nanos())
                        .append(", \"maximumNanos\": ")
                        .append(value.maximumNanos()).append('}');
            }
            json.append("\n  },\n  \"structuralSample\": ")
                    .append(representative.toJson().indent(2).strip())
                    .append("\n}\n");
            return json.toString();
        }

        String toMarkdown() {
            StringBuilder markdown = new StringBuilder(2_048);
            markdown.append("# Round 13 five-occurrence runtime campaign\n\n")
                    .append("Warm-ups: ").append(warmups)
                    .append(". Measured samples: ").append(samples)
                    .append(".\n\n")
                    .append("| Span | p50 ms | p95 ms | max ms |\n")
                    .append("|---|---:|---:|---:|\n");
            distributions.forEach((name, value) -> markdown
                    .append("| ").append(name).append(" | ")
                    .append(millis(value.p50Nanos())).append(" | ")
                    .append(millis(value.p95Nanos())).append(" | ")
                    .append(millis(value.maximumNanos())).append(" |\n"));
            return markdown.append("\nAll measured samples passed the exact ")
                    .append("three-session/five-binding/five-event semantic ")
                    .append("invariants.\n")
                    .toString();
        }

        private static String millis(long nanos) {
            return "%.6f".formatted(nanos / 1_000_000.0);
        }
    }
}
