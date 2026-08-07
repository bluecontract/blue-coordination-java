package blue.coordination.examples.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * File-backed evidence publication with one immutable shard per runtime.
 *
 * <p>Runtime close writes only that runtime's records. Suite publication then
 * reads every shard exactly once, validates ownership and identity uniqueness,
 * and atomically publishes the canonical combined report.</p>
 */
final class MyOsEvidencePublisher {

    static final String SHARD_SCHEMA =
            "blue.coordination/myos-demo-runtime-shard/1.1";
    static final String COMBINED_SCHEMA =
            "blue.coordination/myos-demo-runtime-evidence/1.1";

    private static final TypeReference<Map<String, Object>> REPORT_TYPE =
            new TypeReference<>() { };
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Comparator<Map<String, Object>> ADMISSION_ORDER =
            Comparator.comparing(MyOsEvidencePublisher::runtimeId)
                    .thenComparing(record -> text(record, "documentKey"))
                    .thenComparing(record -> text(record, "sessionId"));
    private static final Comparator<Map<String, Object>> TRANSITION_ORDER =
            Comparator.comparing(MyOsEvidencePublisher::runtimeId)
                    .thenComparingLong(record -> ordinal(record))
                    .thenComparing(record -> text(record, "documentKey"))
                    .thenComparing(record -> text(record, "entryBlueId"));
    private static final Comparator<Map<String, Object>> OBSERVATION_ORDER =
            Comparator.comparing(MyOsEvidencePublisher::runtimeId)
                    .thenComparing(record -> text(record, "kind"))
                    .thenComparing(record -> text(record, "observationId"));

    private final Path shardDirectory;
    private final Path combinedDestination;
    private final Set<String> writtenRuntimeIds = new HashSet<>();

    private boolean combinedPublished;
    private long shardWriteCount;
    private long shardBytesWritten;
    private long aggregationShardReads;
    private long aggregationRecordVisits;
    private long shardBytesRead;
    private long combinedWriteCount;
    private long combinedBytesWritten;

    MyOsEvidencePublisher(
            Path shardDirectory,
            Path combinedDestination) {
        this.shardDirectory = normalized(shardDirectory, "shardDirectory");
        this.combinedDestination = normalized(
                combinedDestination, "combinedDestination");
        if (this.combinedDestination.getParent() == null) {
            throw new IllegalArgumentException(
                    "combinedDestination must have a parent");
        }
    }

    synchronized boolean writeShard(
            String exampleId,
            String caseId,
            String runtimeId,
            List<Map<String, Object>> admissions,
            List<Map<String, Object>> transitions,
            List<Map<String, Object>> observations) {
        if (combinedPublished) {
            throw new IllegalStateException(
                    "Cannot write a shard after combined publication");
        }
        String checkedExampleId = requireText(exampleId, "exampleId");
        String checkedCaseId = requireText(caseId, "caseId");
        String checkedRuntimeId = requireText(runtimeId, "runtimeId");
        List<Map<String, Object>> checkedAdmissions = copyRecords(
                admissions, "admissions");
        List<Map<String, Object>> checkedTransitions = copyRecords(
                transitions, "transitions");
        List<Map<String, Object>> checkedObservations = copyRecords(
                observations, "observations");
        validateRecords(
                checkedExampleId,
                checkedCaseId,
                checkedRuntimeId,
                checkedAdmissions,
                checkedTransitions,
                checkedObservations);
        if (!writtenRuntimeIds.add(checkedRuntimeId)) {
            throw new IllegalStateException(
                    "Duplicate runtime evidence: " + checkedRuntimeId);
        }

        Map<String, Object> shard = new LinkedHashMap<>();
        shard.put("schema", SHARD_SCHEMA);
        shard.put("exampleId", checkedExampleId);
        shard.put("caseId", checkedCaseId);
        shard.put("runtimeId", checkedRuntimeId);
        shard.put("admissions", checkedAdmissions);
        shard.put("transitions", checkedTransitions);
        shard.put("observations", checkedObservations);

        Path destination = shardDirectory.resolve(
                digest(checkedRuntimeId) + ".json");
        byte[] encoded = encode(shard);
        if (Files.exists(destination)) {
            throw new IllegalStateException(
                    "Runtime evidence shard already exists: " + destination);
        }
        writeAtomically(destination, encoded, false);
        shardWriteCount++;
        shardBytesWritten = Math.addExact(
                shardBytesWritten, encoded.length);
        return true;
    }

    synchronized PublicationMetrics publishCombinedOnce() {
        if (combinedPublished) {
            return metrics();
        }

        List<Map<String, Object>> admissions = new ArrayList<>();
        List<Map<String, Object>> transitions = new ArrayList<>();
        List<Map<String, Object>> observations = new ArrayList<>();
        Set<String> runtimeIds = new HashSet<>();
        Set<String> runtimeCases = new HashSet<>();
        Set<String> transitionIdentities = new HashSet<>();
        Set<String> observationIdentities = new HashSet<>();
        for (Path shardPath : shardPaths()) {
            byte[] encoded = read(shardPath);
            aggregationShardReads++;
            shardBytesRead = Math.addExact(shardBytesRead, encoded.length);
            Map<String, Object> shard = decode(encoded, shardPath);
            RuntimeShard checked = validateShard(shard, shardPath);
            if (!runtimeIds.add(checked.runtimeId())) {
                throw new IllegalStateException(
                        "Duplicate runtimeId across evidence shards: "
                                + checked.runtimeId());
            }
            String runtimeCase = checked.runtimeId() + "\u0000"
                    + checked.exampleId() + "\u0000" + checked.caseId();
            if (!runtimeCases.add(runtimeCase)) {
                throw new IllegalStateException(
                        "Duplicate runtime/case evidence: "
                                + checked.runtimeId());
            }
            for (Map<String, Object> transition : checked.transitions()) {
                String identity = checked.runtimeId() + "\u0000"
                        + transitionIdentity(transition);
                if (!transitionIdentities.add(identity)) {
                    throw new IllegalStateException(
                            "Duplicate transition identity in runtime "
                                    + checked.runtimeId() + ": "
                                    + transitionIdentity(transition));
                }
            }
            for (Map<String, Object> observation : checked.observations()) {
                String identity = checked.runtimeId() + "\u0000"
                        + text(observation, "observationId");
                if (!observationIdentities.add(identity)) {
                    throw new IllegalStateException(
                            "Duplicate observation identity in runtime "
                                    + checked.runtimeId() + ": "
                                    + text(observation, "observationId"));
                }
            }
            admissions.addAll(checked.admissions());
            transitions.addAll(checked.transitions());
            observations.addAll(checked.observations());
            aggregationRecordVisits = Math.addExact(
                    aggregationRecordVisits,
                    Math.addExact(
                            Math.addExact(
                                    checked.admissions().size(),
                                    checked.transitions().size()),
                            checked.observations().size()));
        }

        admissions.sort(ADMISSION_ORDER);
        transitions.sort(TRANSITION_ORDER);
        observations.sort(OBSERVATION_ORDER);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", COMBINED_SCHEMA);
        report.put("admissions", admissions);
        report.put("transitions", transitions);
        report.put("observations", observations);
        byte[] encoded = encode(report);
        writeAtomically(combinedDestination, encoded, true);
        combinedWriteCount = 1L;
        combinedBytesWritten = encoded.length;
        combinedPublished = true;
        return metrics();
    }

    synchronized PublicationMetrics metrics() {
        return new PublicationMetrics(
                shardWriteCount,
                shardBytesWritten,
                aggregationShardReads,
                aggregationRecordVisits,
                shardBytesRead,
                combinedWriteCount,
                combinedBytesWritten);
    }

    private List<Path> shardPaths() {
        if (!Files.isDirectory(shardDirectory)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                shardDirectory, "*.json")) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    result.add(path);
                }
            }
        } catch (IOException failure) {
            throw publicationFailure(
                    "Could not enumerate evidence shards in "
                            + shardDirectory,
                    failure);
        }
        result.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return result;
    }

    private static RuntimeShard validateShard(
            Map<String, Object> shard,
            Path source) {
        if (!SHARD_SCHEMA.equals(shard.get("schema"))) {
            throw new IllegalStateException(
                    "Unsupported evidence shard schema in " + source);
        }
        String exampleId = text(shard, "exampleId");
        String caseId = text(shard, "caseId");
        String runtimeId = text(shard, "runtimeId");
        List<Map<String, Object>> admissions = records(
                shard.get("admissions"), "admissions", source);
        List<Map<String, Object>> transitions = records(
                shard.get("transitions"), "transitions", source);
        List<Map<String, Object>> observations = records(
                shard.get("observations"), "observations", source);
        validateRecords(
                exampleId,
                caseId,
                runtimeId,
                admissions,
                transitions,
                observations);
        return new RuntimeShard(
                exampleId,
                caseId,
                runtimeId,
                admissions,
                transitions,
                observations);
    }

    private static void validateRecords(
            String exampleId,
            String caseId,
            String runtimeId,
            List<Map<String, Object>> admissions,
            List<Map<String, Object>> transitions,
            List<Map<String, Object>> observations) {
        Set<String> admissionIdentities = new HashSet<>();
        for (Map<String, Object> admission : admissions) {
            validateOwnership(admission, exampleId, caseId, runtimeId);
            String identity = text(admission, "documentKey") + "\u0000"
                    + text(admission, "sessionId");
            if (!admissionIdentities.add(identity)) {
                throw new IllegalStateException(
                        "Duplicate admission identity in runtime " + runtimeId
                                + ": " + identity.replace('\u0000', '/'));
            }
        }

        Set<Long> ordinals = new HashSet<>();
        Set<String> transitionIdentities = new HashSet<>();
        for (Map<String, Object> transition : transitions) {
            validateOwnership(transition, exampleId, caseId, runtimeId);
            long ordinal = ordinal(transition);
            if (!ordinals.add(ordinal)) {
                throw new IllegalStateException(
                        "Duplicate transition ordinal in runtime " + runtimeId
                                + ": " + ordinal);
            }
            String identity = transitionIdentity(transition);
            if (!transitionIdentities.add(identity)) {
                throw new IllegalStateException(
                        "Duplicate transition identity in runtime " + runtimeId
                                + ": " + identity);
            }
        }

        Set<String> observationIds = new HashSet<>();
        long runtimeSummaries = 0L;
        for (Map<String, Object> observation : observations) {
            validateOwnership(observation, exampleId, caseId, runtimeId);
            String kind = text(observation, "kind");
            if (!Set.of(
                    "runtime-summary",
                    "checkpoint",
                    "physical-slice").contains(kind)) {
                throw new IllegalStateException(
                        "Unsupported observation kind in runtime "
                                + runtimeId + ": " + kind);
            }
            String observationId = text(observation, "observationId");
            if (!observationIds.add(observationId)) {
                throw new IllegalStateException(
                        "Duplicate observation identity in runtime "
                                + runtimeId + ": " + observationId);
            }
            if ("runtime-summary".equals(kind)) {
                runtimeSummaries = Math.addExact(runtimeSummaries, 1L);
                validateRuntimeSummary(observation);
            } else if ("checkpoint".equals(kind)) {
                validateCheckpoint(observation);
            } else {
                validatePhysicalSlice(observation);
            }
        }
        if (runtimeSummaries != 1L) {
            throw new IllegalStateException(
                    "Runtime evidence requires exactly one runtime-summary: "
                            + runtimeId);
        }
    }

    private static void validateRuntimeSummary(
            Map<String, Object> observation) {
        Map<?, ?> work = object(observation, "work", "runtime-summary");
        Map<?, ?> host = object(work, "host", "runtime-summary.work");
        requireNumbers(
                host,
                "runtime-summary.work.host",
                "sourceParses",
                "documentInitializations",
                "eventPreparations",
                "eventSplits",
                "routeIndexProbes",
                "fanoutPages");
        Map<?, ?> engine = object(
                work, "engine", "runtime-summary.work");
        requireNumbers(
                engine,
                "runtime-summary.work.engine",
                "plans",
                "bundleLoads",
                "bundleBatches",
                "loadedFragmentIdentities",
                "loadedBytes",
                "processCompletions",
                "commitAttempts",
                "committed",
                "alreadyCommitted",
                "conflicts");
        Map<?, ?> store = object(work, "store", "runtime-summary.work");
        requireNumbers(
                store,
                "runtime-summary.work.store",
                "singleReads",
                "batchReads",
                "requestedIdentities");
        Map<?, ?> state = object(
                observation, "state", "runtime-summary");
        requireNumbers(
                state,
                "runtime-summary.state",
                "documentCount",
                "timelineCount",
                "journalEntryCount",
                "storedEventInventoryCount");
    }

    private static void validateCheckpoint(Map<String, Object> observation) {
        text(observation, "name");
        text(observation, "stateFingerprint");
        requireNumbers(
                observation,
                "checkpoint",
                "documentCount",
                "timelineCount",
                "journalEntryCount",
                "physicalFragmentCount");
    }

    private static void validatePhysicalSlice(
            Map<String, Object> observation) {
        text(observation, "rootDocumentKey");
        text(observation, "absolutePath");
        text(observation, "owningRootSessionId");
        text(observation, "selectedLogicalDocumentId");
        String expected = text(observation, "expectedSelectedRootBlueId");
        String actual = text(observation, "actualSelectedRootBlueId");
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Physical-slice logical and physical Roots differ");
        }
        Object chainValue = observation.get("relationshipChain");
        if (!(chainValue instanceof List<?> chain)) {
            throw new IllegalStateException(
                    "physical-slice requires relationshipChain");
        }
        for (Object value : chain) {
            if (!(value instanceof Map<?, ?> link)) {
                throw new IllegalStateException(
                        "physical-slice relationshipChain requires objects");
            }
            nestedText(link, "parentLogicalId", "physical-slice link");
            nestedText(link, "relativePath", "physical-slice link");
            nestedText(link, "childLogicalId", "physical-slice link");
        }
        List<String> selected = textList(
                observation,
                "selectedFragmentBlueIds",
                "physical-slice");
        if (selected.isEmpty()
                || new HashSet<>(selected).size() != selected.size()) {
            throw new IllegalStateException(
                    "physical-slice selected identities must be non-empty and unique");
        }
        long loaded = number(
                observation, "loadedFragmentCount", "physical-slice");
        long full = number(
                observation, "fullFragmentCount", "physical-slice");
        if (loaded <= 0L || loaded != selected.size() || full < loaded) {
            throw new IllegalStateException(
                    "physical-slice fragment counts are inconsistent");
        }
        Map<?, ?> store = object(observation, "store", "physical-slice");
        requireNumbers(
                store,
                "physical-slice.store",
                "singleReads",
                "batchReads",
                "requestedIdentities");
    }

    private static void validateOwnership(
            Map<String, Object> record,
            String exampleId,
            String caseId,
            String runtimeId) {
        if (!exampleId.equals(text(record, "exampleId"))
                || !caseId.equals(text(record, "caseId"))
                || !runtimeId.equals(runtimeId(record))) {
            throw new IllegalStateException(
                    "Evidence record does not belong to runtime " + runtimeId);
        }
    }

    private static String transitionIdentity(Map<String, Object> transition) {
        Object casValue = transition.get("cas");
        if (!(casValue instanceof Map<?, ?> cas)) {
            throw new IllegalStateException(
                    "Transition evidence requires a cas object");
        }
        Object identity = cas.get("transitionIdentity");
        if (!(identity instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Transition evidence requires cas.transitionIdentity");
        }
        return text;
    }

    private static long ordinal(Map<String, Object> transition) {
        Object value = transition.get("transitionOrdinal");
        if (!(value instanceof Number number) || number.longValue() <= 0L) {
            throw new IllegalStateException(
                    "Transition evidence requires a positive ordinal");
        }
        return number.longValue();
    }

    private static String runtimeId(Map<String, Object> record) {
        return text(record, "runtimeId");
    }

    private static String text(Map<String, Object> record, String field) {
        Object value = record.get(field);
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Evidence field must be non-blank: " + field);
        }
        return text;
    }

    private static String nestedText(
            Map<?, ?> record,
            String field,
            String context) {
        Object value = record.get(field);
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalStateException(
                    context + " field must be non-blank: " + field);
        }
        return text;
    }

    private static Map<?, ?> object(
            Map<?, ?> record,
            String field,
            String context) {
        Object value = record.get(field);
        if (!(value instanceof Map<?, ?> result)) {
            throw new IllegalStateException(
                    context + " requires an object: " + field);
        }
        return result;
    }

    private static void requireNumbers(
            Map<?, ?> record,
            String context,
            String... fields) {
        for (String field : fields) {
            number(record, field, context);
        }
    }

    private static long number(
            Map<?, ?> record,
            String field,
            String context) {
        Object value = record.get(field);
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0.0d
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalStateException(
                    context + " field must be a non-negative integer: "
                            + field);
        }
        return number.longValue();
    }

    private static List<String> textList(
            Map<?, ?> record,
            String field,
            String context) {
        Object value = record.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(
                    context + " requires a list: " + field);
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.trim().isEmpty()) {
                throw new IllegalStateException(
                        context + " list values must be non-blank: " + field);
            }
            result.add(text);
        }
        return result;
    }

    private static List<Map<String, Object>> records(
            Object value,
            String field,
            Path source) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(
                    "Evidence shard requires " + field + " in " + source);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException(
                        "Evidence shard " + field
                                + " must contain objects in " + source);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalStateException(
                            "Evidence record keys must be strings in " + source);
                }
                record.put(key, entry.getValue());
            }
            result.add(record);
        }
        return result;
    }

    private static List<Map<String, Object>> copyRecords(
            List<Map<String, Object>> source,
            String label) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> record
                : Objects.requireNonNull(source, label)) {
            result.add(new LinkedHashMap<>(
                    Objects.requireNonNull(record, label + " record")));
        }
        return result;
    }

    private static byte[] encode(Map<String, Object> report) {
        try {
            return JSON.writeValueAsBytes(report);
        } catch (IOException failure) {
            throw publicationFailure(
                    "Could not encode MyOS evidence", failure);
        }
    }

    private static Map<String, Object> decode(byte[] encoded, Path source) {
        try {
            return JSON.readValue(encoded, REPORT_TYPE);
        } catch (IOException failure) {
            throw publicationFailure(
                    "Could not decode MyOS evidence shard " + source,
                    failure);
        }
    }

    private static byte[] read(Path source) {
        try {
            return Files.readAllBytes(source);
        } catch (IOException failure) {
            throw publicationFailure(
                    "Could not read MyOS evidence shard " + source,
                    failure);
        }
    }

    private static void writeAtomically(
            Path destination,
            byte[] encoded,
            boolean replaceExisting) {
        Path parent = destination.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Evidence destination must have a parent: " + destination);
        }
        Path temporary = null;
        IOException writeFailure = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(
                    parent, ".myos-evidence-", ".json.tmp");
            Files.write(
                    temporary,
                    encoded,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            move(temporary, destination, replaceExisting);
            temporary = null;
        } catch (IOException failure) {
            writeFailure = failure;
            throw publicationFailure(
                    "Could not publish MyOS evidence to " + destination,
                    failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    if (writeFailure != null) {
                        writeFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw publicationFailure(
                                "Could not remove temporary evidence "
                                        + temporary,
                                cleanupFailure);
                    }
                }
            }
        }
    }

    private static void move(
            Path temporary,
            Path destination,
            boolean replaceExisting) throws IOException {
        try {
            if (replaceExisting) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException unsupported) {
            if (replaceExisting) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, destination);
            }
        }
    }

    private static String digest(String runtimeId) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    runtimeId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path normalized(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }

    private static IllegalStateException publicationFailure(
            String message,
            IOException cause) {
        return new IllegalStateException(message, cause);
    }

    record PublicationMetrics(
            long shardWriteCount,
            long shardBytesWritten,
            long aggregationShardReads,
            long aggregationRecordVisits,
            long shardBytesRead,
            long combinedWriteCount,
            long combinedBytesWritten) {

        long totalIoBytes() {
            return Math.addExact(
                    Math.addExact(shardBytesWritten, shardBytesRead),
                    combinedBytesWritten);
        }
    }

    private record RuntimeShard(
            String exampleId,
            String caseId,
            String runtimeId,
            List<Map<String, Object>> admissions,
            List<Map<String, Object>> transitions,
            List<Map<String, Object>> observations) { }
}
