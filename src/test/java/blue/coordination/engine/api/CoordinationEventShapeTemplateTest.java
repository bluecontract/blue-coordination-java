package blue.coordination.engine.api;

import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.coordination.round4.Round4ParityReceipt;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoordinationEventShapeTemplateTest {

    @Test
    void shapeInstanceEqualsFullCompilerWithPreviousEntry() {
        CoordinationEventAdmissionMetrics authoritativeMetrics =
                new CoordinationEventAdmissionMetrics();
        CoordinationEventAdmissionCompiler authoritative =
                new CoordinationEventAdmissionCompiler(
                        "shape-test-environment",
                        "shape-test-language",
                        "shape-test-provider",
                        CoordinationDocumentSplitter.forEventSplitting(),
                        16,
                        256,
                        authoritativeMetrics);
        CoordinationEventShapeMetrics shapeMetrics =
                new CoordinationEventShapeMetrics();
        CoordinationEventShapeTemplate shape =
                new CoordinationEventShapeCompiler(
                        authoritative, shapeMetrics)
                        .compile(
                                "timeline/attach-pay-note/with-prev",
                                prototype(),
                                Arrays.asList("/timestamp", "/prevEntry"));

        String nextPrevious = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("next-previous"));
        CoordinationEventShapeInstance instance = shape.instantiate(Arrays.asList(
                new CoordinationEventShapePatch(
                        "/timestamp", new Node().value(9_000_001L)),
                new CoordinationEventShapePatch(
                        "/prevEntry", new Node().blueId(nextPrevious))));

        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(instance.exactEvent()),
                instance.eventBlueId());
        assertTrue(instance.changedLocalFragmentCount()
                        < instance.admission().fragments().size(),
                "the stable operation/request subtree must be reused");
        assertTrue(instance.reusedLocalFragmentCount() > 0);
        assertEquals(1L, authoritativeMetrics.snapshot().fullEventSplits(),
                "only the sentinel shape is fully split");

        shape.requireAuthoritativeParity(
                instance,
                CoordinationDocumentSplitter.forEventSplitting());
        CoordinationEventShapeMetrics.Snapshot metrics =
                shapeMetrics.snapshot();
        assertEquals(1L, metrics.templatesCompiled());
        assertEquals(1L, metrics.instancesCompiled());
        assertEquals(1L, metrics.fullSplitterOracleRuns());
        assertEquals(0L, metrics.oracleFailures());
    }

    @Test
    void shapeInstanceEqualsFullCompilerWithoutPreviousEntry() {
        CoordinationEventShapeMetrics shapeMetrics =
                new CoordinationEventShapeMetrics();
        CoordinationEventShapeTemplate shape = new CoordinationEventShapeCompiler(
                authoritative("shape-test-no-prev"), shapeMetrics)
                .compile(
                        "timeline/attach-pay-note/no-prev",
                        prototypeWithoutPrevious(),
                        Arrays.asList("/timestamp"));

        CoordinationEventShapeInstance instance = shape.instantiate(
                Arrays.asList(CoordinationEventShapePatch.scalar(
                        "/timestamp", Long.MIN_VALUE)));

        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(instance.exactEvent()),
                instance.eventBlueId());
        assertNull(instance.exactEvent().getProperties().get("prevEntry"));
        assertTrue(instance.reusedLocalFragmentCount() > 0);
        shape.requireAuthoritativeParity(
                instance,
                CoordinationDocumentSplitter.forEventSplitting());
        CoordinationEventShapeMetrics.Snapshot metrics =
                shapeMetrics.snapshot();
        assertEquals(1L, metrics.templatesCompiled());
        assertEquals(1L, metrics.instancesCompiled());
        assertEquals(1L, metrics.fullSplitterOracleRuns());
        assertEquals(0L, metrics.oracleFailures());
    }

    @Test
    void twoExactInstancesShareStaticFragmentsButNotEventIdentity() {
        CoordinationEventShapeTemplate shape = shape();
        String firstPrevious = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("first"));
        String secondPrevious = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("second"));
        CoordinationEventShapeInstance first = shape.instantiate(Arrays.asList(
                new CoordinationEventShapePatch(
                        "/timestamp", new Node().value(11L)),
                new CoordinationEventShapePatch(
                        "/prevEntry", new Node().blueId(firstPrevious))));
        CoordinationEventShapeInstance second = shape.instantiate(Arrays.asList(
                new CoordinationEventShapePatch(
                        "/timestamp", new Node().value(12L)),
                new CoordinationEventShapePatch(
                        "/prevEntry", new Node().blueId(secondPrevious))));

        assertNotEquals(first.eventBlueId(), second.eventBlueId());
        assertEquals(
                first.reusedLocalFragmentCount(),
                second.reusedLocalFragmentCount());
        assertEquals(
                first.admission().fragments().keySet().stream()
                        .filter(second.admission().fragments()::containsKey)
                        .count(),
                first.reusedLocalFragmentCount());
    }

    @Test
    void tenThousandExactInstancesMatchTheAuthoritativeSplitter() {
        CoordinationEventShapeMetrics metrics =
                new CoordinationEventShapeMetrics();
        CoordinationEventShapeTemplate shape = shape(metrics);
        CoordinationEventShapeInstance sentinel = null;
        Set<String> exactEventBlueIds = new HashSet<String>();
        String memoBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value(memoValue()));
        CoordinationCanonicalFragment sharedMemo = null;
        long changedTotal = 0L;
        long reusedTotal = 0L;
        long[] boundaries = new long[] {
                Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE
        };
        CoordinationDocumentSplitter oracle =
                CoordinationDocumentSplitter.forEventSplitting();

        for (int index = 0; index < 10_000; index++) {
            long timestamp = index < boundaries.length
                    ? boundaries[index]
                    : 9_000_000_000L + index;
            String previous = DirectBlueIdCalculator.calculateBlueId(
                    new Node().value("previous-" + index));
            CoordinationEventShapeInstance instance = shape.instantiate(
                    Arrays.asList(
                            CoordinationEventShapePatch.scalar(
                                    "/timestamp", Long.valueOf(timestamp)),
                            CoordinationEventShapePatch.reference(
                                    "/prevEntry", previous)));
            assertTrue(exactEventBlueIds.add(instance.eventBlueId()),
                    "every exact timestamp/previous pair must be first-seen");
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            instance.exactEvent()),
                    instance.eventBlueId());
            changedTotal += instance.changedLocalFragmentCount();
            reusedTotal += instance.reusedLocalFragmentCount();
            assertTrue(instance.reusedLocalFragmentCount() > 0);
            if (sentinel == null) {
                sentinel = instance;
                sharedMemo = instance.admission().fragments().get(memoBlueId);
                assertNotNull(sharedMemo);
            } else {
                assertSame(sharedMemo,
                        instance.admission().fragments().get(memoBlueId),
                        "the unchanged large request fragment must be shared");
            }
            shape.requireAuthoritativeParity(
                    instance,
                    oracle);
        }

        CoordinationEventShapeMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(1L, snapshot.templatesCompiled());
        assertEquals(10_000, exactEventBlueIds.size());
        assertEquals(10_000L, snapshot.instancesCompiled());
        assertEquals(10_000L, snapshot.exactGraphsMaterialized());
        assertEquals(changedTotal, snapshot.directFragmentsRehashed());
        assertEquals(reusedTotal, snapshot.staticFragmentsReused());
        assertEquals(10_000L, snapshot.fullSplitterOracleRuns());
        assertEquals(0L, snapshot.oracleFailures());
        Round4ParityReceipt.write(
                "eventShapeComparisons", 10_000L, 0L);
    }

    @Test
    void inactiveStaticDecoysDoNotExpandTheChangedRehashFrontier() {
        CoordinationEventShapeTemplate baseline =
                new CoordinationEventShapeCompiler(
                        authoritative("shape-test-decoy-baseline"),
                        new CoordinationEventShapeMetrics())
                        .compile(
                                "timeline/increment/baseline",
                                prototypeWithInactiveDecoys(0),
                                Arrays.asList("/timestamp", "/prevEntry"));
        CoordinationEventShapeTemplate decoyHeavy =
                new CoordinationEventShapeCompiler(
                        authoritative("shape-test-decoy-heavy"),
                        new CoordinationEventShapeMetrics())
                        .compile(
                                "timeline/increment/decoy-heavy",
                                prototypeWithInactiveDecoys(256),
                                Arrays.asList("/timestamp", "/prevEntry"));
        String previous = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("decoy-test-previous"));
        CoordinationEventShapeInstance baselineInstance =
                baseline.instantiate(Arrays.asList(
                        CoordinationEventShapePatch.scalar(
                                "/timestamp", Long.valueOf(71L)),
                        CoordinationEventShapePatch.reference(
                                "/prevEntry", previous)));
        CoordinationEventShapeInstance decoyInstance =
                decoyHeavy.instantiate(Arrays.asList(
                        CoordinationEventShapePatch.scalar(
                                "/timestamp", Long.valueOf(71L)),
                        CoordinationEventShapePatch.reference(
                                "/prevEntry", previous)));

        assertTrue(decoyHeavy.approximateRetainedWeightBytes()
                > baseline.approximateRetainedWeightBytes());
        assertEquals(
                baselineInstance.changedLocalFragmentCount(),
                decoyInstance.changedLocalFragmentCount(),
                "inactive static branches must not enter the changed spine");
        assertTrue(decoyInstance.reusedLocalFragmentCount()
                > baselineInstance.reusedLocalFragmentCount());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        decoyInstance.exactEvent()),
                decoyInstance.eventBlueId());
        baseline.requireAuthoritativeParity(
                baselineInstance,
                CoordinationDocumentSplitter.forEventSplitting());
        decoyHeavy.requireAuthoritativeParity(
                decoyInstance,
                CoordinationDocumentSplitter.forEventSplitting());
    }

    @Test
    void undeclaredOrIncompleteMutationFailsClosed() {
        CoordinationEventShapeTemplate shape = shape();
        assertThrows(IllegalArgumentException.class, () -> shape.instantiate(
                Arrays.asList(new CoordinationEventShapePatch(
                        "/timestamp", new Node().value(13L)))));
        assertThrows(IllegalArgumentException.class, () -> shape.instantiate(
                Arrays.asList(
                        new CoordinationEventShapePatch(
                                "/timestamp", new Node().value(13L)),
                        new CoordinationEventShapePatch(
                                "/prevEntry", new Node().blueId(
                                        DirectBlueIdCalculator.calculateBlueId(
                                                new Node().value("prior")))),
                        new CoordinationEventShapePatch(
                                "/request/amount", new Node().value(7L)))));
    }

    @Test
    void topologyChangingPatchAndAuthoredReferenceOriginFailClosed() {
        CoordinationEventShapeTemplate shape = shape();
        String previous = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("valid-previous"));

        assertThrows(IllegalArgumentException.class, () -> shape.instantiate(
                Arrays.asList(
                        new CoordinationEventShapePatch(
                                "/timestamp",
                                new Node().properties(
                                        "nested", new Node().value(1L))),
                        CoordinationEventShapePatch.reference(
                                "/prevEntry", previous))));
        assertThrows(IllegalArgumentException.class, () -> shape.instantiate(
                Arrays.asList(
                        CoordinationEventShapePatch.scalar(
                                "/timestamp", Long.valueOf(17L)),
                        CoordinationEventShapePatch.scalar(
                                "/prevEntry", "not-a-reference"))));
        assertThrows(IllegalArgumentException.class, () -> shape.instantiate(
                Arrays.asList(
                        CoordinationEventShapePatch.reference(
                                "/timestamp", previous),
                        CoordinationEventShapePatch.reference(
                                "/prevEntry", previous))));

        CoordinationEventShapeCompiler compiler =
                new CoordinationEventShapeCompiler(
                        authoritative("shape-test-topology"),
                        new CoordinationEventShapeMetrics());
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                "timeline/non-leaf",
                prototype(),
                Arrays.asList("/message/request")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
                "timeline/absent",
                prototype(),
                Arrays.asList("/not-present")));
    }

    private static CoordinationEventShapeTemplate shape() {
        return shape(new CoordinationEventShapeMetrics());
    }

    private static CoordinationEventShapeTemplate shape(
            CoordinationEventShapeMetrics metrics) {
        return new CoordinationEventShapeCompiler(
                authoritative("shape-test-environment-2"),
                metrics)
                .compile(
                        "timeline/increment/with-prev",
                        prototype(),
                        Arrays.asList("/timestamp", "/prevEntry"));
    }

    private static CoordinationEventAdmissionCompiler authoritative(
            String environmentIdentity) {
        return new CoordinationEventAdmissionCompiler(
                environmentIdentity,
                "shape-test-language",
                "shape-test-provider",
                CoordinationDocumentSplitter.forEventSplitting(),
                16,
                256,
                new CoordinationEventAdmissionMetrics());
    }

    private static Node prototype() {
        String previous = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("prototype-previous"));
        return new Node().properties(
                "timestamp", new Node().value(1L),
                "prevEntry", new Node().blueId(previous),
                "timeline", new Node().value("alice"),
                "actor", new Node().value("alice"))
                .properties("message", new Node().properties(
                        "operation", new Node().value("attachPayNote"),
                        "channel", new Node().value("customerChannel"),
                        "request", new Node().properties(
                                "amount", new Node().value(1L),
                                "currency", new Node().value("EUR"),
                                "memo", new Node().value(memoValue()))));
    }

    private static Node prototypeWithoutPrevious() {
        Node prototype = prototype();
        prototype.getProperties().remove("prevEntry");
        return prototype;
    }

    private static Node prototypeWithInactiveDecoys(int count) {
        Node result = prototype();
        Node request = result.getProperties()
                .get("message")
                .getProperties()
                .get("request");
        for (int index = 0; index < count; index++) {
            request.getProperties().put(
                    "inactiveDecoy" + index,
                    new Node().value(
                            "decoy-" + index + "-" + largePayload(128)));
        }
        return result;
    }

    private static String largePayload(int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            result.append((char) ('a' + (index % 26)));
        }
        return result.toString();
    }

    private static String memoValue() {
        return "Zażółć 🌍 — " + largePayload(4_096);
    }
}
