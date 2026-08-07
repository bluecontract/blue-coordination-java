package blue.coordination.processor;

import blue.coordination.processor.mandate.DocumentResponderMandateEligibility;
import blue.coordination.processor.mandate.MandateEligibilityDecision;
import blue.coordination.processor.mandate.OperationMandateEligibility;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.processor.CoordinationFragmentationCatalogHarness;
import blue.language.runtime.BlueLanguage;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the closed host-quota fixture inventory against production entry
 * points. These diagnostics are intentionally separate from portable
 * {@code PROCESS} gas.
 */
final class CoordinationHostQuotaFixtureTest {
    private static final Path FIXTURES =
            Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath()
                    .normalize()
                    .resolve(
                            "src/test/resources/coordination/conformance"
                                    + "/fixtures/host-quota");
    private static final Set<String> TOP_LEVEL_FIELDS =
            immutableSet(
                    "fixtureSchema",
                    "id",
                    "operation",
                    "input",
                    "expected");
    private static final Set<String> INPUT_FIELDS =
            immutableSet(
                    "counter",
                    "quantity",
                    "limit");
    private static final Set<String> PASSED_EXPECTED_FIELDS =
            immutableSet(
                    "portableProcessGas",
                    "outcome");
    private static final Set<String> INELIGIBLE_EXPECTED_FIELDS =
            immutableSet(
                    "portableProcessGas",
                    "outcome",
                    "reason",
                    "traceQuantity");
    private static final Set<String> QUOTA_EXPECTED_FIELDS =
            immutableSet(
                    "portableProcessGas",
                    "outcome",
                    "limitName",
                    "attemptedQuantity",
                    "admittedQuantity",
                    "rejectedObservationRecorded");
    private static final Set<String> COUNTERS =
            immutableSet(
                    CoordinationHostQuotaSchedule
                            .SPLITTER_CATALOG_ENTRY_VISITED,
                    CoordinationHostQuotaSchedule
                            .SPLITTER_FRAGMENT_ADMITTED,
                    CoordinationHostQuotaSchedule
                            .SPLITTER_CUT_VALIDATED,
                    CoordinationHostQuotaSchedule
                            .MANDATE_PREDICATE_EVALUATED,
                    CoordinationHostQuotaSchedule
                            .RESPONDER_MANDATE_CANDIDATE_TESTED);

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostQuotaFixtures")
    void shouldExecuteHostQuotaFixtureAgainstProductionApi(
            Fixture fixture) {
        // given
        CoordinationHostQuotaSchedule schedule =
                CoordinationHostQuotaTestSupport.schedule(
                        fixture.input.limit,
                        fixture.input.limit);
        CoordinationHostQuotaSession session =
                CoordinationHostQuotaSession.observing(
                        schedule);

        // when
        Observed observed = execute(fixture, session);

        // then
        assertFalse(fixture.expected.portableProcessGas);
        assertTrue(
                schedule.supportsCounter(
                        fixture.input.counter));
        assertEquals(
                fixture.expected.outcome,
                observed.outcome);
        assertEquals(
                expectedTraceQuantity(fixture),
                session.quantity(
                        fixture.input.counter));
        assertEquals(
                expectedSelectedTrace(fixture),
                selectedTrace(
                        session.trace(),
                        fixture.input.counter));
        assertExactSequence(session.trace());
        assertApiOutcome(fixture, observed);
        assertQuotaOutcome(fixture, observed, session);
    }

    private static Stream<Fixture> hostQuotaFixtures() {
        List<Path> resources =
                new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(FIXTURES)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path
                            .getFileName()
                            .toString()
                            .endsWith(".yaml"))
                    .forEach(resources::add);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot inventory host-quota fixtures",
                    failure);
        }
        Collections.sort(
                resources,
                Comparator.comparing(
                        Path::toString));
        if (resources.size() != 7) {
            throw new IllegalArgumentException(
                    "Expected exactly seven host-quota fixtures, found "
                            + resources.size());
        }
        List<Fixture> fixtures =
                new ArrayList<Fixture>();
        for (Path resource : resources) {
            fixtures.add(decode(resource));
        }
        return fixtures.stream();
    }

    private static Fixture decode(Path path) {
        String source = read(path);
        validateClosedYaml(path, source);
        Node root;
        try (BlueLanguage parser = BlueLanguage.builder().build()) {
            root = parser.codec().parseSource(source, BlueFormat.YAML);
        }
        String location = path.toString();
        requireFields(
                root,
                TOP_LEVEL_FIELDS,
                TOP_LEVEL_FIELDS,
                location);
        String schema = text(
                requiredProperty(
                        root, "fixtureSchema"),
                location + ".fixtureSchema");
        if (!"blue.coordination/direct-host-quota-fixture/1.0"
                .equals(schema)) {
            throw invalid(
                    location,
                    "unknown fixtureSchema " + schema);
        }
        String id = text(
                requiredProperty(root, "id"),
                location + ".id");
        String operation = text(
                requiredProperty(root, "operation"),
                location + ".operation");
        if (!"direct-host-quota".equals(operation)) {
            throw invalid(
                    location,
                    "unknown operation " + operation);
        }
        Input input = decodeInput(
                id,
                requiredProperty(root, "input"));
        Expected expected = decodeExpected(
                id,
                requiredProperty(root, "expected"));
        return new Fixture(
                FIXTURES.relativize(path)
                        .toString()
                        .replace(
                                java.io.File.separatorChar,
                                '/'),
                id,
                input,
                expected);
    }

    private static Input decodeInput(
            String id,
            Node input) {
        requireFields(
                input,
                INPUT_FIELDS,
                INPUT_FIELDS,
                id + ".input");
        String counter = text(
                requiredProperty(input, "counter"),
                id + ".input.counter");
        if (!COUNTERS.contains(counter)) {
            throw invalid(
                    id,
                    "unknown host counter " + counter);
        }
        int quantity = positiveInteger(
                requiredProperty(input, "quantity"),
                id + ".input.quantity");
        int limit = positiveInteger(
                requiredProperty(input, "limit"),
                id + ".input.limit");
        return new Input(counter, quantity, limit);
    }

    private static Expected decodeExpected(
            String id,
            Node expected) {
        String outcome = text(
                requiredProperty(expected, "outcome"),
                id + ".expected.outcome");
        Set<String> fields;
        if ("passed".equals(outcome)) {
            fields = PASSED_EXPECTED_FIELDS;
        } else if ("ineligible".equals(outcome)) {
            fields = INELIGIBLE_EXPECTED_FIELDS;
        } else if ("quota-exceeded".equals(outcome)) {
            fields = QUOTA_EXPECTED_FIELDS;
        } else {
            throw invalid(
                    id,
                    "unknown expected outcome " + outcome);
        }
        requireFields(
                expected,
                fields,
                fields,
                id + ".expected");
        boolean portableProcessGas = booleanValue(
                requiredProperty(
                        expected,
                        "portableProcessGas"),
                id + ".expected.portableProcessGas");
        if (portableProcessGas) {
            throw invalid(
                    id,
                    "host work cannot be portable PROCESS gas");
        }
        if ("passed".equals(outcome)) {
            return Expected.passed();
        }
        if ("ineligible".equals(outcome)) {
            return Expected.ineligible(
                    text(
                            requiredProperty(
                                    expected, "reason"),
                            id + ".expected.reason"),
                    nonNegativeInteger(
                            requiredProperty(
                                    expected,
                                    "traceQuantity"),
                            id + ".expected.traceQuantity"));
        }
        return Expected.quotaExceeded(
                text(
                        requiredProperty(
                                expected, "limitName"),
                        id + ".expected.limitName"),
                positiveInteger(
                        requiredProperty(
                                expected,
                                "attemptedQuantity"),
                        id + ".expected.attemptedQuantity"),
                nonNegativeInteger(
                        requiredProperty(
                                expected,
                                "admittedQuantity"),
                        id + ".expected.admittedQuantity"),
                booleanValue(
                        requiredProperty(
                                expected,
                                "rejectedObservationRecorded"),
                        id
                                + ".expected"
                                + ".rejectedObservationRecorded"));
    }

    private static Observed execute(
            Fixture fixture,
            CoordinationHostQuotaSession session) {
        String counter = fixture.input.counter;
        if (CoordinationHostQuotaSchedule
                .SPLITTER_CATALOG_ENTRY_VISITED
                .equals(counter)
                || CoordinationHostQuotaSchedule
                .SPLITTER_FRAGMENT_ADMITTED
                .equals(counter)) {
            return executeSplitter(
                    new Node(), session);
        }
        if (CoordinationHostQuotaSchedule
                .SPLITTER_CUT_VALIDATED
                .equals(counter)) {
            return executeSplitter(
                    CoordinationHostQuotaTestSupport
                            .embeddedRoot(
                                    fixture.input.quantity),
                    session);
        }
        if (CoordinationHostQuotaSchedule
                .MANDATE_PREDICATE_EVALUATED
                .equals(counter)) {
            MandateEligibilityDecision decision =
                    OperationMandateEligibility.evaluate(
                            null, session);
            return Observed.passed(
                    decision);
        }
        if (CoordinationHostQuotaSchedule
                .RESPONDER_MANDATE_CANDIDATE_TESTED
                .equals(counter)) {
            return executeResponderMandate(
                    fixture, session);
        }
        throw invalid(
                fixture.id,
                "counter has no production dispatcher "
                        + counter);
    }

    private static Observed executeSplitter(
            Node root,
            CoordinationHostQuotaSession session) {
        CoordinationDocumentSplitter splitter =
                CoordinationFragmentationCatalogHarness
                        .splitter(
                                root,
                                Collections
                                        .<String, List<String>>emptyMap());
        try {
            splitter.splitDocument(root, session);
            return Observed.passed(null);
        } catch (CoordinationHostQuotaExceededException failure) {
            return Observed.quotaExceeded(failure);
        }
    }

    private static Observed executeResponderMandate(
            Fixture fixture,
            CoordinationHostQuotaSession session) {
        DocumentResponderMandateEligibility.Candidate candidate =
                DocumentResponderMandateEligibility
                        .Candidate.incomplete(null);
        List<DocumentResponderMandateEligibility.Candidate>
                candidates =
                Collections.nCopies(
                        fixture.input.quantity,
                        candidate);
        DocumentResponderMandateEligibility.Evidence evidence =
                DocumentResponderMandateEligibility
                        .Evidence.builder()
                        .requestTimestamp(BigInteger.ZERO)
                        .providerActor(
                                new Node().value("provider"))
                        .requestingInitialDocument(
                                new Node().value("document"))
                        .request(
                                new Node().value("request"))
                        .candidates(candidates)
                        .build();
        MandateEligibilityDecision decision =
                DocumentResponderMandateEligibility.evaluate(
                        evidence, session);
        return decision.isIneligible()
                ? Observed.ineligible(decision)
                : Observed.passed(decision);
    }

    private static long expectedTraceQuantity(
            Fixture fixture) {
        if (fixture.expected.traceQuantity != null) {
            return fixture.expected.traceQuantity.longValue();
        }
        if (fixture.expected.admittedQuantity != null) {
            return fixture.expected
                    .admittedQuantity
                    .longValue();
        }
        return fixture.input.quantity;
    }

    private static List<String> expectedSelectedTrace(
            Fixture fixture) {
        List<String> result =
                new ArrayList<String>();
        String counter = fixture.input.counter;
        int count = Math.toIntExact(
                expectedTraceQuantity(fixture));
        for (int index = 0; index < count; index++) {
            if (CoordinationHostQuotaSchedule
                    .SPLITTER_CATALOG_ENTRY_VISITED
                    .equals(counter)) {
                result.add(signature(
                        counter,
                        "split-document",
                        "/",
                        "effective-scope"));
            } else if (CoordinationHostQuotaSchedule
                    .SPLITTER_FRAGMENT_ADMITTED
                    .equals(counter)) {
                result.add(signature(
                        counter,
                        "split-document",
                        "/",
                        "document-root"));
            } else if (CoordinationHostQuotaSchedule
                    .SPLITTER_CUT_VALIDATED
                    .equals(counter)) {
                result.add(signature(
                        counter,
                        "split-document",
                        "/child" + (index + 1),
                        "embedded-root"));
            } else if (CoordinationHostQuotaSchedule
                    .MANDATE_PREDICATE_EVALUATED
                    .equals(counter)) {
                result.add(signature(
                        counter,
                        "operation-mandate-eligibility",
                        "/evidence",
                        "evidence-present"));
            } else if (CoordinationHostQuotaSchedule
                    .RESPONDER_MANDATE_CANDIDATE_TESTED
                    .equals(counter)) {
                result.add(signature(
                        counter,
                        "document-responder-mandate-eligibility",
                        "/candidates/" + index,
                        "candidate"));
            }
        }
        return result;
    }

    private static List<String> selectedTrace(
            List<CoordinationHostQuotaTraceEntry> trace,
            String counter) {
        List<String> result =
                new ArrayList<String>();
        for (CoordinationHostQuotaTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                result.add(signature(
                        entry.counter(),
                        entry.operation(),
                        entry.logicalPath(),
                        entry.reason()));
            }
        }
        return result;
    }

    private static String signature(
            String counter,
            String operation,
            String path,
            String reason) {
        return counter
                + "|1|"
                + operation
                + "|"
                + path
                + "|"
                + reason;
    }

    private static void assertExactSequence(
            List<CoordinationHostQuotaTraceEntry> trace) {
        for (int index = 0; index < trace.size(); index++) {
            CoordinationHostQuotaTraceEntry entry =
                    trace.get(index);
            assertEquals((long) index, entry.sequence());
            assertEquals(1L, entry.quantity());
            assertNotNull(entry.operation());
            assertNotNull(entry.logicalPath());
            assertNotNull(entry.reason());
        }
    }

    private static void assertApiOutcome(
            Fixture fixture,
            Observed observed) {
        String counter = fixture.input.counter;
        if (CoordinationHostQuotaSchedule
                .MANDATE_PREDICATE_EVALUATED
                .equals(counter)) {
            assertNotNull(observed.decision);
            assertTrue(observed.decision.isSuspended());
            assertEquals(
                    "mandate-evidence-unavailable",
                    observed.decision.reason());
        } else if (CoordinationHostQuotaSchedule
                .RESPONDER_MANDATE_CANDIDATE_TESTED
                .equals(counter)
                && "passed".equals(
                        fixture.expected.outcome)) {
            assertNotNull(observed.decision);
            assertTrue(observed.decision.isSuspended());
            assertEquals(
                    "responder-mandate-history-incomplete",
                    observed.decision.reason());
        } else if ("ineligible".equals(
                fixture.expected.outcome)) {
            assertNotNull(observed.decision);
            assertTrue(observed.decision.isIneligible());
            assertEquals(
                    fixture.expected.reason,
                    observed.decision.reason());
        }
    }

    private static void assertQuotaOutcome(
            Fixture fixture,
            Observed observed,
            CoordinationHostQuotaSession session) {
        if (!"quota-exceeded".equals(
                fixture.expected.outcome)) {
            assertNull(observed.failure);
            return;
        }
        CoordinationHostQuotaExceededException failure =
                observed.failure;
        assertNotNull(failure);
        assertEquals(
                fixture.expected.limitName,
                failure.limitName());
        assertEquals(
                (long) fixture.input.limit,
                failure.limit());
        assertEquals(
                fixture.expected
                        .attemptedQuantity
                        .longValue(),
                failure.attemptedQuantity());
        assertEquals(
                fixture.expected
                        .admittedQuantity
                        .longValue(),
                failure.admittedQuantity());
        assertFalse(
                fixture.expected
                        .rejectedObservationRecorded
                        .booleanValue());
        assertFalse(
                selectedTrace(
                        session.trace(),
                        fixture.input.counter)
                        .contains(
                                signature(
                                        fixture.input.counter,
                                        "split-document",
                                        "/child"
                                                + fixture.expected
                                                .attemptedQuantity,
                                        "embedded-root")));
    }

    private static void validateClosedYaml(
            Path path,
            String source) {
        if (!source.endsWith("\n")) {
            throw invalid(
                    path.toString(),
                    "fixture must end with a newline");
        }
        String[] lines = source.split("\\n", -1);
        Set<String> topKeys =
                new LinkedHashSet<String>();
        Set<String> inputKeys =
                new LinkedHashSet<String>();
        Set<String> expectedKeys =
                new LinkedHashSet<String>();
        String section = null;
        for (int index = 0;
                index < lines.length - 1;
                index++) {
            String line = lines[index];
            int lineNumber = index + 1;
            if (line.isEmpty()
                    || line.indexOf('\t') >= 0
                    || line.indexOf('\r') >= 0
                    || line.endsWith(" ")) {
                throw invalid(
                        path.toString(),
                        "invalid whitespace at line "
                                + lineNumber);
            }
            int indentation =
                    line.startsWith("  ") ? 2 : 0;
            if (indentation == 0
                    && line.startsWith(" ")) {
                throw invalid(
                        path.toString(),
                        "invalid indentation at line "
                                + lineNumber);
            }
            String mapping =
                    line.substring(indentation);
            int separator = mapping.indexOf(':');
            if (separator <= 0) {
                throw invalid(
                        path.toString(),
                        "expected a mapping at line "
                                + lineNumber);
            }
            String key =
                    mapping.substring(0, separator);
            String value =
                    mapping.substring(separator + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            } else if (!value.isEmpty()) {
                throw invalid(
                        path.toString(),
                        "missing mapping separator space at line "
                                + lineNumber);
            }
            if (indentation == 0) {
                if (!topKeys.add(key)) {
                    throw invalid(
                            path.toString(),
                            "duplicate top-level key "
                                    + key);
                }
                if ("input".equals(key)
                        || "expected".equals(key)) {
                    if (!value.isEmpty()) {
                        throw invalid(
                                path.toString(),
                                key
                                        + " must be an object");
                    }
                    section = key;
                } else {
                    if (value.isEmpty()) {
                        throw invalid(
                                path.toString(),
                                key
                                        + " must be a scalar");
                    }
                    section = null;
                }
            } else {
                Set<String> fields;
                if ("input".equals(section)) {
                    fields = inputKeys;
                } else if ("expected".equals(section)) {
                    fields = expectedKeys;
                } else {
                    throw invalid(
                            path.toString(),
                            "nested control outside input or expected"
                                    + " at line "
                                    + lineNumber);
                }
                if (value.isEmpty()) {
                    throw invalid(
                            path.toString(),
                            "nested objects are forbidden at line "
                                    + lineNumber);
                }
                if (!fields.add(key)) {
                    throw invalid(
                            path.toString(),
                            "duplicate "
                                    + section
                                    + " key "
                                    + key);
                }
            }
        }
        if (!source.startsWith(
                "fixtureSchema: "
                        + "blue.coordination/"
                        + "direct-host-quota-fixture/1.0\n")) {
            throw invalid(
                    path.toString(),
                    "unknown or misplaced fixtureSchema");
        }
    }

    private static void requireFields(
            Node node,
            Set<String> allowed,
            Set<String> required,
            String location) {
        if (node == null
                || node.getProperties() == null) {
            throw invalid(
                    location,
                    "expected an object");
        }
        Set<String> actual =
                new LinkedHashSet<String>(
                        node.getProperties().keySet());
        addReservedFields(
                node, actual);
        if (!allowed.containsAll(actual)) {
            Set<String> unknown =
                    new LinkedHashSet<String>(actual);
            unknown.removeAll(allowed);
            throw invalid(
                    location,
                    "unknown controls " + unknown);
        }
        if (!actual.containsAll(required)) {
            Set<String> missing =
                    new LinkedHashSet<String>(required);
            missing.removeAll(actual);
            throw invalid(
                    location,
                    "missing controls " + missing);
        }
    }

    private static void addReservedFields(
            Node node,
            Set<String> actual) {
        if (node.getName() != null) {
            actual.add("name");
        }
        if (node.getDescription() != null) {
            actual.add("description");
        }
        if (node.getType() != null) {
            actual.add("type");
        }
        if (node.getItemType() != null) {
            actual.add("itemType");
        }
        if (node.getKeyType() != null) {
            actual.add("keyType");
        }
        if (node.getValueType() != null) {
            actual.add("valueType");
        }
        if (node.getRawValue() != null) {
            actual.add("value");
        }
        if (node.getItems() != null) {
            actual.add("items");
        }
        if (node.getContracts() != null) {
            actual.add("contracts");
        }
        if (node.getBlueId() != null) {
            actual.add("blueId");
        }
        if (node.getSchema() != null) {
            actual.add("schema");
        }
        if (node.getMergePolicy() != null) {
            actual.add("mergePolicy");
        }
        if (node.getPreviousBlueId() != null) {
            actual.add("$previous");
        }
        if (node.getPosition() != null) {
            actual.add("$pos");
        }
        if (node.getBlue() != null) {
            actual.add("blue");
        }
    }

    private static Node requiredProperty(
            Node node,
            String name) {
        Node value = node != null
                && node.getProperties() != null
                ? node.getProperties().get(name)
                : null;
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing property " + name);
        }
        return value;
    }

    private static String text(
            Node node,
            String location) {
        Object value = node != null
                ? node.getValue()
                : null;
        if (!(value instanceof String)
                || ((String) value).trim().isEmpty()) {
            throw invalid(
                    location,
                    "expected non-empty text");
        }
        return (String) value;
    }

    private static boolean booleanValue(
            Node node,
            String location) {
        Object value = node != null
                ? node.getValue()
                : null;
        if (!(value instanceof Boolean)) {
            throw invalid(
                    location,
                    "expected a boolean");
        }
        return ((Boolean) value).booleanValue();
    }

    private static int positiveInteger(
            Node node,
            String location) {
        int value = integer(node, location);
        if (value <= 0) {
            throw invalid(
                    location,
                    "expected a positive integer");
        }
        return value;
    }

    private static int nonNegativeInteger(
            Node node,
            String location) {
        int value = integer(node, location);
        if (value < 0) {
            throw invalid(
                    location,
                    "expected a non-negative integer");
        }
        return value;
    }

    private static int integer(
            Node node,
            String location) {
        Object value = node != null
                ? node.getValue()
                : null;
        BigInteger integer;
        if (value instanceof BigInteger) {
            integer = (BigInteger) value;
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            integer = BigInteger.valueOf(
                    ((Number) value).longValue());
        } else {
            throw invalid(
                    location,
                    "expected an integer");
        }
        try {
            return integer.intValueExact();
        } catch (ArithmeticException outOfRange) {
            throw invalid(
                    location,
                    "integer is outside the supported range");
        }
    }

    private static String read(Path path) {
        try {
            return new String(
                    Files.readAllBytes(path),
                    StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "Cannot read " + path,
                    failure);
        }
    }

    private static IllegalArgumentException invalid(
            String location,
            String message) {
        return new IllegalArgumentException(
                location + ": " + message);
    }

    private static Set<String> immutableSet(
            String... values) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<String>(
                        Arrays.asList(values)));
    }

    private static final class Fixture {
        private final String resource;
        private final String id;
        private final Input input;
        private final Expected expected;

        private Fixture(
                String resource,
                String id,
                Input input,
                Expected expected) {
            this.resource = resource;
            this.id = id;
            this.input = input;
            this.expected = expected;
        }

        @Override
        public String toString() {
            return id + " [" + resource + "]";
        }
    }

    private static final class Input {
        private final String counter;
        private final int quantity;
        private final int limit;

        private Input(
                String counter,
                int quantity,
                int limit) {
            this.counter = counter;
            this.quantity = quantity;
            this.limit = limit;
        }
    }

    private static final class Expected {
        private final boolean portableProcessGas;
        private final String outcome;
        private final String reason;
        private final Integer traceQuantity;
        private final String limitName;
        private final Integer attemptedQuantity;
        private final Integer admittedQuantity;
        private final Boolean rejectedObservationRecorded;

        private Expected(
                String outcome,
                String reason,
                Integer traceQuantity,
                String limitName,
                Integer attemptedQuantity,
                Integer admittedQuantity,
                Boolean rejectedObservationRecorded) {
            this.portableProcessGas = false;
            this.outcome = outcome;
            this.reason = reason;
            this.traceQuantity = traceQuantity;
            this.limitName = limitName;
            this.attemptedQuantity = attemptedQuantity;
            this.admittedQuantity = admittedQuantity;
            this.rejectedObservationRecorded =
                    rejectedObservationRecorded;
        }

        private static Expected passed() {
            return new Expected(
                    "passed",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        private static Expected ineligible(
                String reason,
                int traceQuantity) {
            return new Expected(
                    "ineligible",
                    reason,
                    Integer.valueOf(traceQuantity),
                    null,
                    null,
                    null,
                    null);
        }

        private static Expected quotaExceeded(
                String limitName,
                int attemptedQuantity,
                int admittedQuantity,
                boolean rejectedObservationRecorded) {
            return new Expected(
                    "quota-exceeded",
                    null,
                    null,
                    limitName,
                    Integer.valueOf(attemptedQuantity),
                    Integer.valueOf(admittedQuantity),
                    Boolean.valueOf(
                            rejectedObservationRecorded));
        }
    }

    private static final class Observed {
        private final String outcome;
        private final MandateEligibilityDecision decision;
        private final CoordinationHostQuotaExceededException failure;

        private Observed(
                String outcome,
                MandateEligibilityDecision decision,
                CoordinationHostQuotaExceededException failure) {
            this.outcome = outcome;
            this.decision = decision;
            this.failure = failure;
        }

        private static Observed passed(
                MandateEligibilityDecision decision) {
            return new Observed(
                    "passed",
                    decision,
                    null);
        }

        private static Observed ineligible(
                MandateEligibilityDecision decision) {
            return new Observed(
                    "ineligible",
                    decision,
                    null);
        }

        private static Observed quotaExceeded(
                CoordinationHostQuotaExceededException failure) {
            return new Observed(
                    "quota-exceeded",
                    null,
                    failure);
        }
    }
}
