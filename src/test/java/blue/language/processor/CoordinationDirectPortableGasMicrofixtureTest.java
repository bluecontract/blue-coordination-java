package blue.language.processor;

import blue.coordination.processor.CoordinationRuntimeGas;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable, fail-closed microfixtures for every portable Coordination gas
 * counter. These fixtures exercise the real processor-owned runtime session
 * and Coordination child-ledger adapter.
 */
final class CoordinationDirectPortableGasMicrofixtureTest {
    private static final String ROOT =
            "coordination/conformance/fixtures/gas-micro/";
    private static final String SCHEMA =
            "blue.coordination/direct-portable-gas-fixture/1.0";
    private static final String OPERATION =
            "direct-portable-gas";
    private static final List<String> RESOURCES =
            Collections.unmodifiableList(Arrays.asList(
                    ROOT + "timelineHeaderRead.yaml",
                    ROOT + "timelineBindingCompared.yaml",
                    ROOT + "compositeMemberVisited.yaml",
                    ROOT + "allTimelinesMemberVisited.yaml",
                    ROOT + "operationRequestFieldRead.yaml",
                    ROOT + "operationTargetLookup.yaml",
                    ROOT + "operationCandidateTested.yaml",
                    ROOT + "workflowStepVisited.yaml",
                    ROOT + "workflowStepExecuted.yaml",
                    ROOT + "updateDocumentStep.yaml",
                    ROOT + "triggerEventStep.yaml",
                    ROOT + "terminateProcessingStep.yaml",
                    ROOT + "computeStepEntered.yaml",
                    ROOT + "computeDefinitionResolved.yaml"));

    @ParameterizedTest(
            name = "shouldExecuteDirectPortableGasMicrofixture[{index}] {0}")
    @MethodSource("portableGasFixtureResources")
    void shouldExecuteEveryDirectPortableGasMicrofixture(
            String resource) {
        // given
        Node fixtureNode = load(resource);
        Fixture fixture = decodeInput(
                fixtureNode, resource);
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = new RuntimeWorkSession(
                parent,
                RuntimeWorkSession.Mode.PROCESSING);

        // when
        CoordinationRuntimeGas.Ledger ledger =
                CoordinationRuntimeGas.open(session);
        ledger.charge(
                fixture.counter,
                fixture.quantity,
                GasChargeContext.of(
                        fixture.scopePath,
                        fixture.contractKey,
                        fixture.logicalPath,
                        fixture.reason));
        ledger.submit();
        session.complete();

        // then
        Expected expected = decodeExpected(
                fixtureNode,
                fixture,
                resource);
        assertEquals(expected.totalGas, parent.totalGas());
        assertExactTrace(expected.trace, parent.trace());
    }

    @Test
    void shouldCoverEveryPortableCounterExactlyOnce() {
        // given
        Set<String> expected = new LinkedHashSet<String>(
                CoordinationRuntimeGas.counterWeights().keySet());
        List<String> decoded = new ArrayList<String>();

        // when
        for (String resource : RESOURCES) {
            decoded.add(decodeInput(
                    load(resource),
                    resource).counter);
        }

        // then
        assertEquals(14, RESOURCES.size());
        assertEquals(RESOURCES.size(),
                new LinkedHashSet<String>(decoded).size());
        assertEquals(expected, new LinkedHashSet<String>(decoded));
    }

    @Test
    void shouldRejectUnknownFixtureFields() {
        // given
        Node fixture = load(RESOURCES.get(0));
        fixture.properties(
                "unexpected",
                new Node().value("must-fail"));

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> decodeInput(
                                fixture,
                                "unknown-field"));

        // then
        assertTrue(failure.getMessage().contains(
                "fixture fields"));
    }

    @Test
    void shouldRejectUnknownFixtureOperations() {
        // given
        Node fixture = load(RESOURCES.get(0));
        fixture.properties(
                "operation",
                new Node().value("not-an-operation"));

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> decodeInput(
                                fixture,
                                "unknown-operation"));

        // then
        assertTrue(failure.getMessage().contains(
                "operation"));
    }

    @Test
    void shouldRejectUnknownFixtureCounters() {
        // given
        Node fixture = load(RESOURCES.get(0));
        requiredObject(fixture, "input").properties(
                "counter",
                new Node().value("not-a-portable-counter"));

        // when
        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> decodeInput(
                                fixture,
                                "unknown-counter"));

        // then
        assertTrue(failure.getMessage().contains(
                "portable counter"));
    }

    private static Stream<String> portableGasFixtureResources() {
        return RESOURCES.stream();
    }

    private static Fixture decodeInput(
            Node fixture,
            String source) {
        requireKeys(
                fixture,
                setOf(
                        "fixtureSchema",
                        "id",
                        "operation",
                        "input",
                        "expected"),
                "fixture fields");
        requireEquals(
                SCHEMA,
                requiredText(fixture, "fixtureSchema"),
                "fixtureSchema");
        requiredText(fixture, "id");
        requireEquals(
                OPERATION,
                requiredText(fixture, "operation"),
                "operation");

        Node input = requiredObject(fixture, "input");
        requireKeys(
                input,
                setOf("counter", "quantity", "context"),
                "input fields");
        String counter = requiredText(input, "counter");
        if (!CoordinationRuntimeGas.counterWeights()
                .containsKey(counter)) {
            throw new IllegalArgumentException(
                    "Unknown portable counter "
                            + counter + " in " + source);
        }
        long quantity = requiredPositiveLong(
                input, "quantity");

        Node context = requiredObject(input, "context");
        requireKeys(
                context,
                setOf(
                        "scopePath",
                        "contractKey",
                        "logicalPath",
                        "reason"),
                "context fields");
        String scopePath = requiredText(
                context, "scopePath");
        String contractKey = requiredText(
                context, "contractKey");
        String logicalPath = requiredText(
                context, "logicalPath");
        String reason = requiredText(
                context, "reason");

        return new Fixture(
                counter,
                quantity,
                scopePath,
                contractKey,
                logicalPath,
                reason);
    }

    private static Expected decodeExpected(
            Node fixture,
            Fixture input,
            String source) {
        Node expected = requiredObject(
                fixture, "expected");
        requireKeys(
                expected,
                setOf("totalGas", "trace"),
                "expected fields");
        long totalGas = requiredNonNegativeLong(
                expected, "totalGas");
        Node traceNode = requiredProperty(
                expected, "trace");
        if (traceNode.getItems() == null
                || traceNode.getItems().size() != 1) {
            throw new IllegalArgumentException(
                    "Expected trace must contain exactly one entry in "
                            + source);
        }
        Trace trace = decodeTrace(
                traceNode.getItems().get(0),
                source);
        if (!input.counter.equals(trace.counter)
                || input.quantity != trace.quantity
                || totalGas != trace.subtotal) {
            throw new IllegalArgumentException(
                    "Input and expected trace disagree in "
                            + source);
        }
        long manifestWeight =
                CoordinationRuntimeGas.counterWeights()
                        .get(input.counter).longValue();
        if (trace.weight != manifestWeight
                || trace.subtotal
                != Math.multiplyExact(
                input.quantity, manifestWeight)) {
            throw new IllegalArgumentException(
                    "Expected trace does not match the portable schedule in "
                            + source);
        }
        return new Expected(totalGas, trace);
    }

    private static Trace decodeTrace(
            Node trace,
            String source) {
        requireKeys(
                trace,
                setOf(
                        "sequence",
                        "namespace",
                        "counter",
                        "quantity",
                        "weight",
                        "subtotal",
                        "scopePath",
                        "contractKey",
                        "logicalPath",
                        "reason"),
                "trace fields");
        return new Trace(
                requiredNonNegativeLong(
                        trace, "sequence"),
                requiredText(trace, "namespace"),
                requiredText(trace, "counter"),
                requiredPositiveLong(
                        trace, "quantity"),
                requiredNonNegativeLong(
                        trace, "weight"),
                requiredNonNegativeLong(
                        trace, "subtotal"),
                requiredText(trace, "scopePath"),
                requiredText(trace, "contractKey"),
                requiredText(trace, "logicalPath"),
                requiredText(trace, "reason"));
    }

    private static void assertExactTrace(
            Trace expected,
            List<GasTraceEntry> actual) {
        assertEquals(1, actual.size());
        GasTraceEntry entry = actual.get(0);
        assertEquals(expected.sequence, entry.sequence());
        assertEquals(expected.namespace, entry.namespace());
        assertEquals(expected.counter, entry.counter());
        assertEquals(expected.quantity, entry.quantity());
        assertEquals(expected.weight, entry.weight());
        assertEquals(expected.subtotal, entry.subtotal());
        assertEquals(expected.scopePath, entry.scopePath());
        assertEquals(expected.contractKey, entry.contractKey());
        assertEquals(expected.logicalPath, entry.logicalPath());
        assertEquals(expected.reason, entry.reason());
    }

    private static Node load(String resource) {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            return language.codec().parseSource(
                    readResource(resource), BlueFormat.YAML);
        }
    }

    private static String readResource(
            String resource) {
        InputStream input =
                CoordinationDirectPortableGasMicrofixtureTest
                        .class
                        .getClassLoader()
                        .getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalArgumentException(
                    "Missing gas microfixture "
                            + resource);
        }
        try (InputStream exact = input;
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = exact.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(
                    output.toByteArray(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not read gas microfixture "
                            + resource,
                    exception);
        }
    }

    private static Node requiredObject(
            Node parent,
            String field) {
        Node value = requiredProperty(
                parent, field);
        if (value.getProperties() == null) {
            throw new IllegalArgumentException(
                    field + " must be an object");
        }
        return value;
    }

    private static Node requiredProperty(
            Node parent,
            String field) {
        if (parent == null
                || parent.getProperties() == null
                || !parent.getProperties()
                .containsKey(field)) {
            throw new IllegalArgumentException(
                    "Missing required field "
                            + field);
        }
        Node value =
                parent.getProperties().get(field);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Required field is null "
                            + field);
        }
        return value;
    }

    private static String requiredText(
            Node parent,
            String field) {
        Object raw = requiredProperty(
                parent, field).getRawValue();
        if (!(raw instanceof String)
                || ((String) raw).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must be non-empty Text");
        }
        return (String) raw;
    }

    private static long requiredPositiveLong(
            Node parent,
            String field) {
        long value = requiredLong(
                parent, field);
        if (value <= 0L) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
        return value;
    }

    private static long requiredNonNegativeLong(
            Node parent,
            String field) {
        long value = requiredLong(
                parent, field);
        if (value < 0L) {
            throw new IllegalArgumentException(
                    field + " must be non-negative");
        }
        return value;
    }

    private static long requiredLong(
            Node parent,
            String field) {
        Object raw = requiredProperty(
                parent, field).getRawValue();
        if (!(raw instanceof BigInteger)) {
            throw new IllegalArgumentException(
                    field + " must be an exact Integer");
        }
        try {
            return ((BigInteger) raw).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    field + " is outside the signed 64-bit range",
                    exception);
        }
    }

    private static void requireKeys(
            Node node,
            Set<String> expected,
            String label) {
        Set<String> actual =
                node != null
                        && node.getProperties() != null
                        ? node.getProperties().keySet()
                        : Collections.<String>emptySet();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    label + " must be exactly "
                            + expected + " but were "
                            + actual);
        }
    }

    private static void requireEquals(
            String expected,
            String actual,
            String label) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "Unknown " + label + " "
                            + actual);
        }
    }

    private static Set<String> setOf(
            String... values) {
        return new LinkedHashSet<String>(
                Arrays.asList(values));
    }

    private static final class Fixture {
        private final String counter;
        private final long quantity;
        private final String scopePath;
        private final String contractKey;
        private final String logicalPath;
        private final String reason;

        private Fixture(
                String counter,
                long quantity,
                String scopePath,
                String contractKey,
                String logicalPath,
                String reason) {
            this.counter = counter;
            this.quantity = quantity;
            this.scopePath = scopePath;
            this.contractKey = contractKey;
            this.logicalPath = logicalPath;
            this.reason = reason;
        }
    }

    private static final class Expected {
        private final long totalGas;
        private final Trace trace;

        private Expected(
                long totalGas,
                Trace trace) {
            this.totalGas = totalGas;
            this.trace = trace;
        }
    }

    private static final class Trace {
        private final long sequence;
        private final String namespace;
        private final String counter;
        private final long quantity;
        private final long weight;
        private final long subtotal;
        private final String scopePath;
        private final String contractKey;
        private final String logicalPath;
        private final String reason;

        private Trace(
                long sequence,
                String namespace,
                String counter,
                long quantity,
                long weight,
                long subtotal,
                String scopePath,
                String contractKey,
                String logicalPath,
                String reason) {
            this.sequence = sequence;
            this.namespace = namespace;
            this.counter = counter;
            this.quantity = quantity;
            this.weight = weight;
            this.subtotal = subtotal;
            this.scopePath = scopePath;
            this.contractKey = contractKey;
            this.logicalPath = logicalPath;
            this.reason = reason;
        }
    }
}
