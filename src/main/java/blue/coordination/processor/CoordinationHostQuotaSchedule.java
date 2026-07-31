package blue.coordination.processor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict, manifest-backed schedule for nonportable Coordination host work.
 *
 * <p>The schedule is deliberately independent of portable {@code PROCESS}
 * gas. It defines diagnostic counter vocabulary and safety limits for
 * preparation and feeder/provider helpers only.</p>
 */
public final class CoordinationHostQuotaSchedule {
    public static final String RESOURCE =
            "blue/coordination/processor/coordination-host-quotas-1.0.yaml";
    public static final String SCHEDULE_ID =
            "blue-coordination/host-quotas/1.0";

    public static final String SPLITTER_CATALOG_ENTRY_VISITED =
            "splitterCatalogEntryVisited";
    public static final String SPLITTER_FRAGMENT_ADMITTED =
            "splitterFragmentAdmitted";
    public static final String SPLITTER_CUT_VALIDATED =
            "splitterCutValidated";
    public static final String MANDATE_PREDICATE_EVALUATED =
            "mandatePredicateEvaluated";
    public static final String RESPONDER_MANDATE_CANDIDATE_TESTED =
            "responderMandateCandidateTested";
    public static final String SUBSCRIPTION_OCCURRENCE_PROJECTED =
            "subscriptionOccurrenceProjected";
    public static final String INDEXED_CANDIDATE_VALIDATED =
            "indexedCandidateValidated";
    public static final String PREFETCH_IDENTITY_CONSTRUCTED =
            "prefetchIdentityConstructed";
    public static final String FRAGMENT_EDGE_METADATA_PRODUCED =
            "fragmentEdgeMetadataProduced";

    private static final String MAX_SPLITTER_CUTS =
            "maxSplitterCuts";
    private static final String MAX_MANDATE_CANDIDATES =
            "maxMandateCandidatesPerDecision";
    private static final String MAX_SPLITTER_CATALOG_ENTRIES =
            "maxSplitterCatalogEntriesPerSplit";
    private static final String MAX_SPLITTER_FRAGMENTS =
            "maxSplitterFragmentsPerSplit";
    private static final String MAX_FRAGMENT_EDGE_OCCURRENCES =
            "maxFragmentEdgeOccurrencesPerSplit";
    private static final String MAX_SUBSCRIPTION_OCCURRENCES =
            "maxSubscriptionOccurrencesPerProjection";
    private static final String MAX_INDEXED_CANDIDATES =
            "maxIndexedCandidatesPerPlan";
    private static final String MAX_PREFETCH_IDENTITIES =
            "maxPrefetchIdentitiesPerPlan";
    private static final List<String> REQUIRED_COUNTERS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            SPLITTER_CATALOG_ENTRY_VISITED,
                            SPLITTER_FRAGMENT_ADMITTED,
                            SPLITTER_CUT_VALIDATED,
                            MANDATE_PREDICATE_EVALUATED,
                            RESPONDER_MANDATE_CANDIDATE_TESTED,
                            SUBSCRIPTION_OCCURRENCE_PROJECTED,
                            INDEXED_CANDIDATE_VALIDATED,
                            PREFETCH_IDENTITY_CONSTRUCTED,
                            FRAGMENT_EDGE_METADATA_PRODUCED));
    private static final List<String> REQUIRED_LIMITS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            MAX_SPLITTER_CUTS,
                            MAX_MANDATE_CANDIDATES,
                            MAX_SPLITTER_CATALOG_ENTRIES,
                            MAX_SPLITTER_FRAGMENTS,
                            MAX_FRAGMENT_EDGE_OCCURRENCES,
                            MAX_SUBSCRIPTION_OCCURRENCES,
                            MAX_INDEXED_CANDIDATES,
                            MAX_PREFETCH_IDENTITIES));
    private static final CoordinationHostQuotaSchedule DEFAULT =
            loadDefault();

    private final Map<String, String> counterUnits;
    private final Map<String, Integer> limits;
    private final String manifestSha256;

    private CoordinationHostQuotaSchedule(
            Map<String, String> counterUnits,
            Map<String, Integer> limits,
            String manifestSha256) {
        this.counterUnits =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, String>(
                                counterUnits));
        this.limits =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, Integer>(
                                limits));
        this.manifestSha256 = manifestSha256;
    }

    /**
     * Returns the immutable schedule loaded from the bundled manifest.
     *
     * @return shared bundled host quota schedule
     */
    public static CoordinationHostQuotaSchedule defaults() {
        return DEFAULT;
    }

    /**
     * Returns the supported counters in manifest order.
     *
     * @return immutable counter names in manifest order
     */
    public List<String> counterNames() {
        return Collections.unmodifiableList(
                new ArrayList<String>(
                        counterUnits.keySet()));
    }

    /**
     * Returns the declared unit for one supported counter.
     *
     * @param counter supported counter name
     * @return unit declared for the counter
     */
    public String counterUnit(String counter) {
        String unit = counterUnits.get(counter);
        if (unit == null) {
            throw new IllegalArgumentException(
                    "Unknown Coordination host counter "
                            + counter);
        }
        return unit;
    }

    /**
     * Returns whether the counter belongs to this schedule.
     *
     * @param counter counter name to test
     * @return whether the counter is declared by this schedule
     */
    public boolean supportsCounter(String counter) {
        return counterUnits.containsKey(counter);
    }

    /**
     * Returns the maximum admitted splitter cuts per split operation.
     *
     * @return maximum splitter cuts admitted per split operation
     */
    public int maxSplitterCuts() {
        return limits.get(MAX_SPLITTER_CUTS).intValue();
    }

    /**
     * Returns the maximum responder Mandate candidates per decision.
     *
     * @return maximum responder Mandate candidates admitted per decision
     */
    public int maxMandateCandidatesPerDecision() {
        return limits.get(MAX_MANDATE_CANDIDATES).intValue();
    }

    /**
     * Returns the maximum catalog entries inspected per split operation.
     *
     * @return maximum catalog entries inspected per split operation
     */
    public int maxSplitterCatalogEntriesPerSplit() {
        return limits.get(MAX_SPLITTER_CATALOG_ENTRIES).intValue();
    }

    /**
     * Returns the maximum physical fragments admitted per split operation.
     *
     * @return maximum fragments admitted per split operation
     */
    public int maxSplitterFragmentsPerSplit() {
        return limits.get(MAX_SPLITTER_FRAGMENTS).intValue();
    }

    /**
     * Returns the maximum edge occurrences produced per split operation.
     *
     * @return maximum edge occurrences produced per split operation
     */
    public int maxFragmentEdgeOccurrencesPerSplit() {
        return limits.get(MAX_FRAGMENT_EDGE_OCCURRENCES).intValue();
    }

    /**
     * Returns the maximum occurrences admitted by one projection.
     *
     * @return maximum occurrences admitted by one projection
     */
    public int maxSubscriptionOccurrencesPerProjection() {
        return limits.get(MAX_SUBSCRIPTION_OCCURRENCES).intValue();
    }

    /**
     * Returns the maximum indexed candidates validated by one plan.
     *
     * @return maximum indexed candidates validated by one plan
     */
    public int maxIndexedCandidatesPerPlan() {
        return limits.get(MAX_INDEXED_CANDIDATES).intValue();
    }

    /**
     * Returns the maximum unique prefetch identities produced by one plan.
     *
     * @return maximum prefetch identities produced by one plan
     */
    public int maxPrefetchIdentitiesPerPlan() {
        return limits.get(MAX_PREFETCH_IDENTITIES).intValue();
    }

    /**
     * Returns the SHA-256 digest of the exact loaded manifest bytes.
     *
     * @return lowercase hexadecimal SHA-256 manifest digest
     */
    public String manifestSha256() {
        return manifestSha256;
    }

    static CoordinationHostQuotaSchedule load(
            InputStream input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "Coordination host quota manifest input is required");
        }
        byte[] bytes;
        try {
            bytes = readAll(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Could not read Coordination host quota manifest",
                    exception);
        }
        return parse(bytes);
    }

    private static CoordinationHostQuotaSchedule loadDefault() {
        InputStream input =
                CoordinationHostQuotaSchedule.class
                        .getClassLoader()
                        .getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new ExceptionInInitializerError(
                    "Missing Coordination host quota manifest "
                            + RESOURCE);
        }
        try (InputStream closeable = input) {
            return load(closeable);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        } catch (RuntimeException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static CoordinationHostQuotaSchedule parse(
            byte[] bytes) {
        Map<String, String> headers =
                new LinkedHashMap<String, String>();
        Map<String, String> counterUnits =
                new LinkedHashMap<String, String>();
        Map<String, Integer> limits =
                new LinkedHashMap<String, Integer>();
        Section section = Section.HEADERS;
        String pendingCounter = null;
        String source =
                new String(bytes, StandardCharsets.UTF_8);
        String[] lines = source.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int lineNumber = index + 1;
            if (line.isEmpty()) {
                continue;
            }
            if (line.indexOf('\t') >= 0
                    || !line.equals(trimTrailing(line))) {
                throw invalid(
                        lineNumber,
                        "tabs and trailing whitespace are forbidden");
            }
            if ("counters:".equals(line)) {
                requireSection(
                        section,
                        Section.HEADERS,
                        lineNumber,
                        "counters");
                section = Section.COUNTERS;
                continue;
            }
            if ("limits:".equals(line)) {
                requireSection(
                        section,
                        Section.COUNTERS,
                        lineNumber,
                        "limits");
                if (pendingCounter != null) {
                    throw invalid(
                            lineNumber,
                            "counter "
                                    + pendingCounter
                                    + " has no unit");
                }
                section = Section.LIMITS;
                continue;
            }
            if (section == Section.HEADERS) {
                KeyValue value = topLevelValue(
                        line, lineNumber);
                if (!Arrays.asList(
                        "schedule",
                        "status",
                        "portableProcessGas",
                        "description")
                        .contains(value.key)) {
                    throw invalid(
                            lineNumber,
                            "unknown header " + value.key);
                }
                putUnique(
                        headers,
                        value,
                        lineNumber,
                        "header");
            } else if (section == Section.COUNTERS) {
                if (line.startsWith("- name: ")) {
                    if (pendingCounter != null) {
                        throw invalid(
                                lineNumber,
                                "counter "
                                        + pendingCounter
                                        + " has no unit");
                    }
                    pendingCounter = requiredText(
                            line.substring("- name: ".length()),
                            lineNumber,
                            "counter name");
                    if (counterUnits.containsKey(
                            pendingCounter)) {
                        throw invalid(
                                lineNumber,
                                "duplicate counter "
                                        + pendingCounter);
                    }
                } else if (line.startsWith("  unit: ")) {
                    if (pendingCounter == null) {
                        throw invalid(
                                lineNumber,
                                "counter unit has no name");
                    }
                    counterUnits.put(
                            pendingCounter,
                            requiredText(
                                    line.substring(
                                            "  unit: ".length()),
                                    lineNumber,
                                    "counter unit"));
                    pendingCounter = null;
                } else {
                    throw invalid(
                            lineNumber,
                            "unknown counter field");
                }
            } else {
                if (!line.startsWith("  ")
                        || line.startsWith("   ")) {
                    throw invalid(
                            lineNumber,
                            "limit must use exactly two spaces");
                }
                KeyValue value = keyValue(
                        line.substring(2),
                        lineNumber);
                if (!REQUIRED_LIMITS.contains(value.key)) {
                    throw invalid(
                            lineNumber,
                            "unknown limit " + value.key);
                }
                if (limits.containsKey(value.key)) {
                    throw invalid(
                            lineNumber,
                            "duplicate limit " + value.key);
                }
                int parsed;
                try {
                    parsed = Integer.parseInt(value.value);
                } catch (NumberFormatException exception) {
                    throw invalid(
                            lineNumber,
                            "limit "
                                    + value.key
                                    + " must be an integer");
                }
                if (parsed <= 0) {
                    throw invalid(
                            lineNumber,
                            "limit "
                                    + value.key
                                    + " must be positive");
                }
                limits.put(
                        value.key,
                        Integer.valueOf(parsed));
            }
        }
        if (section != Section.LIMITS) {
            throw new IllegalArgumentException(
                    "Coordination host quota manifest has no limits section");
        }
        requireHeader(
                headers,
                "schedule",
                SCHEDULE_ID);
        requireHeader(
                headers,
                "status",
                "nonportable-diagnostic");
        requireHeader(
                headers,
                "portableProcessGas",
                "false");
        requiredHeader(
                headers,
                "description");
        if (!new ArrayList<String>(
                counterUnits.keySet())
                .equals(REQUIRED_COUNTERS)) {
            throw new IllegalArgumentException(
                    "Coordination host quota counters must be exactly "
                            + REQUIRED_COUNTERS
                            + ", found "
                            + counterUnits.keySet());
        }
        if (!new ArrayList<String>(
                limits.keySet())
                .equals(REQUIRED_LIMITS)) {
            throw new IllegalArgumentException(
                    "Coordination host quota limits must be exactly "
                            + REQUIRED_LIMITS
                            + ", found "
                            + limits.keySet());
        }
        return new CoordinationHostQuotaSchedule(
                counterUnits,
                limits,
                sha256(bytes));
    }

    private static void requireHeader(
            Map<String, String> headers,
            String key,
            String expected) {
        String actual = requiredHeader(
                headers, key);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Coordination host quota manifest "
                            + key
                            + " must be "
                            + expected
                            + ", found "
                            + actual);
        }
    }

    private static String requiredHeader(
            Map<String, String> headers,
            String key) {
        String value = headers.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    "Coordination host quota manifest is missing "
                            + key);
        }
        return value;
    }

    private static KeyValue topLevelValue(
            String line,
            int lineNumber) {
        if (line.startsWith(" ")) {
            throw invalid(
                    lineNumber,
                    "header must not be indented");
        }
        return keyValue(line, lineNumber);
    }

    private static KeyValue keyValue(
            String line,
            int lineNumber) {
        int separator = line.indexOf(':');
        if (separator <= 0
                || separator + 1 >= line.length()
                || line.charAt(separator + 1) != ' ') {
            throw invalid(
                    lineNumber,
                    "expected key: value");
        }
        String key = requiredText(
                line.substring(0, separator),
                lineNumber,
                "key");
        String value = requiredText(
                line.substring(separator + 2),
                lineNumber,
                key);
        return new KeyValue(key, value);
    }

    private static void putUnique(
            Map<String, String> target,
            KeyValue value,
            int lineNumber,
            String label) {
        if (target.put(value.key, value.value) != null) {
            throw invalid(
                    lineNumber,
                    "duplicate "
                            + label
                            + " "
                            + value.key);
        }
    }

    private static String requiredText(
            String value,
            int lineNumber,
            String label) {
        String exact = value != null
                ? value.trim()
                : "";
        if (exact.isEmpty()) {
            throw invalid(
                    lineNumber,
                    label + " must be non-empty");
        }
        return exact;
    }

    private static void requireSection(
            Section actual,
            Section expected,
            int lineNumber,
            String section) {
        if (actual != expected) {
            throw invalid(
                    lineNumber,
                    section + " section is out of order");
        }
    }

    private static String trimTrailing(String value) {
        int end = value.length();
        while (end > 0
                && Character.isWhitespace(
                        value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static byte[] readAll(
            InputStream input) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(bytes);
            StringBuilder result =
                    new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(
                        Locale.ROOT,
                        "%02x",
                        value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    private static IllegalArgumentException invalid(
            int lineNumber,
            String message) {
        return new IllegalArgumentException(
                "Invalid Coordination host quota manifest at line "
                        + lineNumber
                        + ": "
                        + message);
    }

    private enum Section {
        HEADERS,
        COUNTERS,
        LIMITS
    }

    private static final class KeyValue {
        private final String key;
        private final String value;

        private KeyValue(
                String key,
                String value) {
            this.key = key;
            this.value = value;
        }
    }
}
