package blue.coordination.examples.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that live runtime evidence publication is linear and fail-closed. */
final class MyOsEvidenceShardingTest {

    private static final int RUNTIME_COUNT = 128;
    private static final int TRANSITIONS_PER_RUNTIME = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> REPORT_TYPE =
            new TypeReference<>() { };

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldWriteBoundedRuntimeShardsAndAggregateThemExactlyOnce()
            throws IOException {
        // given
        Path shards = temporaryDirectory.resolve("runtime-shards");
        Path combined = temporaryDirectory.resolve("runtime-evidence.json");
        MyOsEvidencePublisher publisher = new MyOsEvidencePublisher(
                shards, combined);

        // when
        for (int index = 0; index < RUNTIME_COUNT; index++) {
            publisher.writeShard(
                    "evidence-example",
                    "case-" + index,
                    "runtime-" + index,
                    admissions(index),
                    transitions(index),
                    observations(index));
        }
        boolean combinedWrittenDuringRuntimeClose = Files.exists(combined);
        long shardCount = countJsonFiles(shards);
        long largestShard = largestJsonFile(shards);
        MyOsEvidencePublisher.PublicationMetrics beforeAggregation =
                publisher.metrics();
        MyOsEvidencePublisher.PublicationMetrics firstPublication =
                publisher.publishCombinedOnce();
        MyOsEvidencePublisher.PublicationMetrics secondPublication =
                publisher.publishCombinedOnce();
        Map<String, Object> report = JSON.readValue(
                combined.toFile(), REPORT_TYPE);

        // then
        long expectedRecords = (long) RUNTIME_COUNT
                * (TRANSITIONS_PER_RUNTIME + 2L);
        assertFalse(combinedWrittenDuringRuntimeClose);
        assertEquals(RUNTIME_COUNT, shardCount);
        assertTrue(largestShard < 8_192L, "each runtime shard stays bounded");
        assertEquals(RUNTIME_COUNT, beforeAggregation.shardWriteCount());
        assertEquals(0L, beforeAggregation.combinedWriteCount());
        assertEquals(RUNTIME_COUNT, firstPublication.aggregationShardReads());
        assertEquals(expectedRecords,
                firstPublication.aggregationRecordVisits());
        assertEquals(firstPublication.shardBytesWritten(),
                firstPublication.shardBytesRead());
        assertEquals(1L, firstPublication.combinedWriteCount());
        assertEquals(firstPublication, secondPublication);
        assertTrue(
                firstPublication.totalIoBytes()
                        <= firstPublication.shardBytesWritten() * 3L + 2_048L,
                "total evidence I/O must remain linear in shard bytes");
        assertEquals(MyOsEvidencePublisher.COMBINED_SCHEMA,
                report.get("schema"));
        assertEquals(RUNTIME_COUNT,
                ((List<?>) report.get("admissions")).size());
        assertEquals(RUNTIME_COUNT * TRANSITIONS_PER_RUNTIME,
                ((List<?>) report.get("transitions")).size());
        assertEquals(RUNTIME_COUNT,
                ((List<?>) report.get("observations")).size());
    }

    @Test
    void shouldRejectDuplicateRuntimeAndTransitionIdentities() {
        // given
        Path shards = temporaryDirectory.resolve("duplicate-shards");
        Path combined = temporaryDirectory.resolve("duplicate-report.json");
        MyOsEvidencePublisher publisher = new MyOsEvidencePublisher(
                shards, combined);
        publisher.writeShard(
                "evidence-example",
                "case-1",
                "runtime-1",
                admissions(1),
                transitions(1),
                observations(1));
        List<Map<String, Object>> duplicateTransitions = new ArrayList<>();
        duplicateTransitions.add(transition(2, 1));
        duplicateTransitions.add(transition(2, 1));

        // when
        IllegalStateException duplicateRuntime = assertThrows(
                IllegalStateException.class,
                () -> publisher.writeShard(
                        "evidence-example",
                        "case-1",
                        "runtime-1",
                        admissions(1),
                        transitions(1),
                        observations(1)));
        IllegalStateException duplicateTransition = assertThrows(
                IllegalStateException.class,
                () -> publisher.writeShard(
                        "evidence-example",
                        "case-2",
                        "runtime-2",
                        admissions(2),
                        duplicateTransitions,
                        observations(2)));
        List<Map<String, Object>> duplicateObservations = new ArrayList<>(
                observations(3));
        duplicateObservations.add(new LinkedHashMap<>(
                duplicateObservations.get(0)));
        IllegalStateException duplicateObservation = assertThrows(
                IllegalStateException.class,
                () -> publisher.writeShard(
                        "evidence-example",
                        "case-3",
                        "runtime-3",
                        admissions(3),
                        transitions(3),
                        duplicateObservations));
        IllegalStateException missingSummary = assertThrows(
                IllegalStateException.class,
                () -> publisher.writeShard(
                        "evidence-example",
                        "case-4",
                        "runtime-4",
                        admissions(4),
                        transitions(4),
                        List.of()));

        // then
        assertTrue(duplicateRuntime.getMessage().contains("Duplicate runtime"));
        assertTrue(duplicateTransition.getMessage().contains(
                "Duplicate transition ordinal"));
        assertTrue(duplicateObservation.getMessage().contains(
                "Duplicate observation identity"));
        assertTrue(missingSummary.getMessage().contains(
                "exactly one runtime-summary"));
        assertEquals(1L, publisher.metrics().shardWriteCount());
    }

    private static List<Map<String, Object>> admissions(int runtimeIndex) {
        Map<String, Object> admission = ownedRecord(runtimeIndex);
        admission.put("documentKey", "document-" + runtimeIndex);
        admission.put("sessionId", "session-" + runtimeIndex);
        return List.of(admission);
    }

    private static List<Map<String, Object>> transitions(int runtimeIndex) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int ordinal = 1; ordinal <= TRANSITIONS_PER_RUNTIME; ordinal++) {
            result.add(transition(runtimeIndex, ordinal));
        }
        return result;
    }

    private static Map<String, Object> transition(
            int runtimeIndex,
            int ordinal) {
        Map<String, Object> transition = ownedRecord(runtimeIndex);
        transition.put("transitionOrdinal", ordinal);
        transition.put("documentKey", "document-" + runtimeIndex);
        transition.put("entryBlueId", "entry-" + ordinal);
        transition.put(
                "cas",
                Map.of(
                        "transitionIdentity",
                        "transition-" + runtimeIndex + "-" + ordinal));
        return transition;
    }

    private static List<Map<String, Object>> observations(int runtimeIndex) {
        Map<String, Object> observation = ownedRecord(runtimeIndex);
        observation.put("kind", "runtime-summary");
        observation.put("observationId", "runtime-summary#1");

        Map<String, Object> host = new LinkedHashMap<>();
        host.put("sourceParses", 0L);
        host.put("documentInitializations", 0L);
        host.put("eventPreparations", 0L);
        host.put("eventSplits", 0L);
        host.put("routeIndexProbes", 0L);
        host.put("fanoutPages", 0L);

        Map<String, Object> engine = new LinkedHashMap<>();
        engine.put("plans", 0L);
        engine.put("bundleLoads", 0L);
        engine.put("bundleBatches", 0L);
        engine.put("loadedFragmentIdentities", 0L);
        engine.put("loadedBytes", 0L);
        engine.put("processCompletions", 0L);
        engine.put("commitAttempts", 0L);
        engine.put("committed", 0L);
        engine.put("alreadyCommitted", 0L);
        engine.put("conflicts", 0L);

        Map<String, Object> store = new LinkedHashMap<>();
        store.put("singleReads", 0L);
        store.put("batchReads", 0L);
        store.put("requestedIdentities", 0L);

        Map<String, Object> work = new LinkedHashMap<>();
        work.put("host", host);
        work.put("engine", engine);
        work.put("store", store);
        observation.put("work", work);
        observation.put(
                "state",
                Map.of(
                        "documentCount", 1L,
                        "timelineCount", 1L,
                        "journalEntryCount", TRANSITIONS_PER_RUNTIME,
                        "storedEventInventoryCount",
                        TRANSITIONS_PER_RUNTIME));
        return List.of(observation);
    }

    private static Map<String, Object> ownedRecord(int runtimeIndex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exampleId", "evidence-example");
        result.put("caseId", "case-" + runtimeIndex);
        result.put("runtimeId", "runtime-" + runtimeIndex);
        return result;
    }

    private static long countJsonFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString()
                    .endsWith(".json")).count();
        }
    }

    private static long largestJsonFile(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString()
                            .endsWith(".json"))
                    .mapToLong(path -> fileSize(path))
                    .max()
                    .orElse(0L);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not inspect evidence shard " + path,
                    failure);
        }
    }
}
