package blue.coordination.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic JSON serialization for selective Coordination processing
 * evidence.
 *
 * <p>This test-support writer deliberately excludes timestamps, elapsed-time
 * measurements, absolute paths, and machine-specific values. Fixture tests
 * supply declared exact baseline identities and counts from their own
 * run.</p>
 */
final class SelectiveProcessingReportWriter {
    static final String FILE_NAME = "report.json";
    static final String SCHEMA_ID =
            "urn:blue:coordination:selective-processing-report:1";
    static final int SCHEMA_VERSION = 1;

    private static final Set<String> REPORT_STATUSES =
            immutableSet("passed", "partial", "failed");
    private static final Set<String> SECTION_STATUSES =
            immutableSet("passed", "blocked", "failed", "not-run");

    private SelectiveProcessingReportWriter() {
    }

    static void write(Path reportDirectory, Report report) throws IOException {
        if (reportDirectory == null) {
            throw new IllegalArgumentException(
                    "reportDirectory must not be null");
        }
        if (report == null) {
            throw new IllegalArgumentException(
                    "report must not be null");
        }
        Files.createDirectories(reportDirectory);
        writeAtomically(
                reportDirectory.resolve(FILE_NAME),
                json(report));
    }

    private static byte[] json(Report report) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        return (mapper.writeValueAsString(report.toJson()) + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void writeAtomically(
            Path target,
            byte[] content) throws IOException {
        Path temporary = target.resolveSibling(
                target.getFileName().toString() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class Report {
        private final String status;
        private final Map<String, String> identities;
        private final String testCountScope;
        private final TestCounts testCounts;
        private final List<Section> sections;
        private final List<UnavailableSuite> unavailableSuites;

        Report(
                String status,
                Map<String, String> identities,
                String testCountScope,
                TestCounts testCounts,
                Collection<Section> sections,
                Collection<UnavailableSuite> unavailableSuites) {
            this.status = oneOf(
                    status, "status", REPORT_STATUSES);
            this.identities = immutableStringMap(
                    identities, "identities");
            if (this.identities.isEmpty()) {
                throw new IllegalArgumentException(
                        "identities must not be empty");
            }
            this.testCountScope = requiredText(
                    testCountScope, "testCountScope");
            this.testCounts = required(
                    testCounts, "testCounts");
            this.sections = orderedUniqueSections(sections);
            if (this.sections.isEmpty()) {
                throw new IllegalArgumentException(
                        "sections must not be empty");
            }
            this.unavailableSuites =
                    orderedUniqueUnavailableSuites(
                            unavailableSuites);
            if ("passed".equals(status)) {
                if (this.testCounts.failed != 0) {
                    throw new IllegalArgumentException(
                            "A passed report cannot contain failed tests");
                }
                if (!this.unavailableSuites.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A passed report cannot name unavailable suites");
                }
                for (Section section : this.sections) {
                    if (!"passed".equals(section.status)) {
                        throw new IllegalArgumentException(
                                "A passed report cannot contain a "
                                        + section.status
                                        + " section: "
                                        + section.id);
                    }
                }
            }
        }

        private Map<String, Object> toJson() {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            result.put("schema", SCHEMA_ID);
            result.put(
                    "schemaVersion",
                    Integer.valueOf(SCHEMA_VERSION));
            result.put("status", status);
            result.put("identities", identities);
            result.put("testCountScope", testCountScope);
            result.put("testCounts", testCounts.toJson());

            List<Map<String, Object>> serializedSections =
                    new ArrayList<Map<String, Object>>(
                            sections.size());
            for (Section section : sections) {
                serializedSections.add(section.toJson());
            }
            result.put("sections", serializedSections);

            List<Map<String, Object>> serializedUnavailable =
                    new ArrayList<Map<String, Object>>(
                            unavailableSuites.size());
            for (UnavailableSuite suite : unavailableSuites) {
                serializedUnavailable.add(suite.toJson());
            }
            result.put(
                    "unavailableSuites",
                    serializedUnavailable);
            return result;
        }
    }

    static final class TestCounts {
        private final int total;
        private final int passed;
        private final int failed;
        private final int skipped;

        TestCounts(
                int total,
                int passed,
                int failed,
                int skipped) {
            this.total = nonNegative(total, "total");
            this.passed = nonNegative(passed, "passed");
            this.failed = nonNegative(failed, "failed");
            this.skipped = nonNegative(skipped, "skipped");
            if (total != passed + failed + skipped) {
                throw new IllegalArgumentException(
                        "total must equal passed + failed + skipped");
            }
        }

        private Map<String, Object> toJson() {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            result.put("total", Integer.valueOf(total));
            result.put("passed", Integer.valueOf(passed));
            result.put("failed", Integer.valueOf(failed));
            result.put("skipped", Integer.valueOf(skipped));
            return result;
        }
    }

    /**
     * One independently understandable proof section.
     *
     * <p>Case IDs and identity sets are sorted. Every list in
     * {@code orderedStreams} retains caller order so causal, gas, semantic
     * demand, and provider-request streams can be reported without inventing a
     * merged chronology.</p>
     */
    static final class Section {
        private final String id;
        private final String status;
        private final List<String> cases;
        private final Map<String, String> facts;
        private final Map<String, Long> metrics;
        private final Map<String, List<String>> orderedStreams;
        private final Map<String, List<String>> identitySets;

        Section(
                String id,
                String status,
                Collection<String> cases,
                Map<String, String> facts,
                Map<String, Long> metrics,
                Map<String, ? extends Collection<String>>
                        orderedStreams,
                Map<String, ? extends Collection<String>>
                        identitySets) {
            this.id = requiredText(id, "section id");
            this.status = oneOf(
                    status,
                    "section status",
                    SECTION_STATUSES);
            this.cases = immutableSortedStrings(
                    cases, "section cases");
            this.facts = immutableStringMap(
                    facts, "section facts");
            this.metrics = immutableLongMap(
                    metrics, "section metrics");
            this.orderedStreams =
                    immutableOrderedStreams(
                            orderedStreams);
            this.identitySets =
                    immutableIdentitySets(
                            identitySets);
        }

        private Map<String, Object> toJson() {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("status", status);
            result.put(
                    "caseCount",
                    Integer.valueOf(cases.size()));
            result.put("cases", cases);
            result.put("facts", facts);
            result.put("metrics", metrics);
            result.put(
                    "orderedStreams",
                    orderedStreams);
            result.put("identitySets", identitySets);
            return result;
        }
    }

    static final class UnavailableSuite {
        private final String id;
        private final String reason;

        UnavailableSuite(String id, String reason) {
            this.id = requiredText(
                    id, "unavailable suite id");
            this.reason = requiredText(
                    reason, "unavailable suite reason");
        }

        private Map<String, Object> toJson() {
            Map<String, Object> result =
                    new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("reason", reason);
            return result;
        }
    }

    private static List<Section> orderedUniqueSections(
            Collection<Section> source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "sections must not be null");
        }
        List<Section> result =
                new ArrayList<Section>(source);
        for (Section section : result) {
            if (section == null) {
                throw new IllegalArgumentException(
                        "sections must not contain null");
            }
        }
        Collections.sort(
                result,
                new Comparator<Section>() {
                    @Override
                    public int compare(
                            Section left,
                            Section right) {
                        return left.id.compareTo(right.id);
                    }
                });
        String previous = null;
        for (Section section : result) {
            if (section.id.equals(previous)) {
                throw new IllegalArgumentException(
                        "Duplicate section id: " + section.id);
            }
            previous = section.id;
        }
        return Collections.unmodifiableList(result);
    }

    private static List<UnavailableSuite>
    orderedUniqueUnavailableSuites(
            Collection<UnavailableSuite> source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "unavailableSuites must not be null");
        }
        List<UnavailableSuite> result =
                new ArrayList<UnavailableSuite>(source);
        for (UnavailableSuite suite : result) {
            if (suite == null) {
                throw new IllegalArgumentException(
                        "unavailableSuites must not contain null");
            }
        }
        Collections.sort(
                result,
                new Comparator<UnavailableSuite>() {
                    @Override
                    public int compare(
                            UnavailableSuite left,
                            UnavailableSuite right) {
                        return left.id.compareTo(right.id);
                    }
                });
        String previous = null;
        for (UnavailableSuite suite : result) {
            if (suite.id.equals(previous)) {
                throw new IllegalArgumentException(
                        "Duplicate unavailable suite id: "
                                + suite.id);
            }
            previous = suite.id;
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, String> immutableStringMap(
            Map<String, String> source,
            String label) {
        if (source == null) {
            throw new IllegalArgumentException(
                    label + " must not be null");
        }
        Map<String, String> result =
                new TreeMap<String, String>();
        for (Map.Entry<String, String> entry
                : source.entrySet()) {
            result.put(
                    requiredText(
                            entry.getKey(),
                            label + " key"),
                    requiredText(
                            entry.getValue(),
                            label + " value"));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> immutableLongMap(
            Map<String, Long> source,
            String label) {
        if (source == null) {
            throw new IllegalArgumentException(
                    label + " must not be null");
        }
        Map<String, Long> result =
                new TreeMap<String, Long>();
        for (Map.Entry<String, Long> entry
                : source.entrySet()) {
            String key = requiredText(
                    entry.getKey(), label + " key");
            Long value = entry.getValue();
            if (value == null || value.longValue() < 0L) {
                throw new IllegalArgumentException(
                        label + " value for "
                                + key
                                + " must be non-negative");
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>>
    immutableOrderedStreams(
            Map<String, ? extends Collection<String>> source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "orderedStreams must not be null");
        }
        Map<String, List<String>> result =
                new TreeMap<String, List<String>>();
        for (Map.Entry<String,
                ? extends Collection<String>> entry
                : source.entrySet()) {
            String key = requiredText(
                    entry.getKey(),
                    "orderedStreams key");
            Collection<String> values = entry.getValue();
            if (values == null) {
                throw new IllegalArgumentException(
                        "orderedStreams value for "
                                + key
                                + " must not be null");
            }
            List<String> ordered =
                    new ArrayList<String>(values.size());
            for (String value : values) {
                ordered.add(requiredText(
                        value,
                        "orderedStreams value"));
            }
            result.put(
                    key,
                    Collections.unmodifiableList(ordered));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>>
    immutableIdentitySets(
            Map<String, ? extends Collection<String>> source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "identitySets must not be null");
        }
        Map<String, List<String>> result =
                new TreeMap<String, List<String>>();
        for (Map.Entry<String,
                ? extends Collection<String>> entry
                : source.entrySet()) {
            String key = requiredText(
                    entry.getKey(),
                    "identitySets key");
            Collection<String> values = entry.getValue();
            if (values == null) {
                throw new IllegalArgumentException(
                        "identitySets value for "
                                + key
                                + " must not be null");
            }
            Set<String> ordered =
                    new TreeSet<String>();
            for (String value : values) {
                ordered.add(requiredText(
                        value,
                        "identitySets value"));
            }
            result.put(
                    key,
                    Collections.unmodifiableList(
                            new ArrayList<String>(ordered)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> immutableSortedStrings(
            Collection<String> source,
            String label) {
        if (source == null) {
            throw new IllegalArgumentException(
                    label + " must not be null");
        }
        Set<String> ordered = new TreeSet<String>();
        for (String value : source) {
            ordered.add(requiredText(value, label + " value"));
        }
        return Collections.unmodifiableList(
                new ArrayList<String>(ordered));
    }

    private static String oneOf(
            String value,
            String label,
            Set<String> allowed) {
        String checked = requiredText(value, label);
        if (!allowed.contains(checked)) {
            throw new IllegalArgumentException(
                    label + " must be one of " + allowed);
        }
        return checked;
    }

    private static String requiredText(
            String value,
            String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    label + " must be non-empty");
        }
        return value;
    }

    private static int nonNegative(
            int value,
            String label) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    label + " must be non-negative");
        }
        return value;
    }

    private static <T> T required(
            T value,
            String label) {
        if (value == null) {
            throw new IllegalArgumentException(
                    label + " must not be null");
        }
        return value;
    }

    private static Set<String> immutableSet(
            String... values) {
        return Collections.unmodifiableSet(
                new TreeSet<String>(
                        Arrays.asList(values)));
    }
}
