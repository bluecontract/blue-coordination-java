package blue.coordination.api;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fast validation, immutability, and typed-error contracts for the API. */
final class PublicValueContractTest {
    @Test
    void documentIdsAreValidatedOrderedAndStableAsText() {
        DocumentId first = DocumentId.of("a");
        DocumentId second = DocumentId.of("b");

        assertTrue(first.compareTo(second) < 0);
        assertEquals("a", first.toString());
        assertThrows(IllegalArgumentException.class,
                () -> DocumentId.of("  "));
        assertThrows(NullPointerException.class,
                () -> DocumentId.of(null));
    }

    @Test
    void timelinesRequireBothAuthenticatedIdentities() {
        assertEquals("feed", new Timeline("feed", "alice").timelineId());
        assertThrows(IllegalArgumentException.class,
                () -> new Timeline("", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeline("feed", " "));
    }

    @Test
    void operationsHaveExactlyOneNormalizedRequestRepresentation() {
        Operation empty = Operation.yaml("touch", "owner", "  ");
        ExactValue exact = ExactValue.verified(new Node().value("request"));
        Operation reused = Operation.exact("touch", "owner", exact);

        assertEquals("{}", empty.requestYaml().orElseThrow());
        assertTrue(empty.exactRequest().isEmpty());
        assertEquals(exact, reused.exactRequest().orElseThrow());
        assertTrue(reused.requestYaml().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> Operation.yaml(" ", "owner", "{}"));
        assertThrows(NullPointerException.class,
                () -> Operation.exact("touch", "owner", null));
    }

    @Test
    void exactValuesDetachMutableNodesAndVerifyIdentity() {
        Node source = new Node().properties(
                "value", new Node().value("original"));
        ExactValue exact = ExactValue.verified(source);
        source.getProperties().get("value").value("mutated");
        Node firstCopy = exact.copyNode();
        firstCopy.getProperties().get("value").value("copy-mutated");

        assertEquals("original", exact.copyNode()
                .getProperties().get("value").getValue());
        assertEquals(exact.blueId(), exact.referenceNode().getBlueId());
        assertTrue(exact.sameExactValue(ExactValue.verified(
                exact.copyNode())));
        assertThrows(IllegalArgumentException.class,
                () -> ExactValue.verified("wrong", exact.copyNode()));
    }

    @Test
    void frontiersDefensivelyCopyAndValidateEveryCursor() {
        Map<String, Long> cursors = new LinkedHashMap<>();
        cursors.put("alice", 2L);
        EnvironmentFrontier frontier = new EnvironmentFrontier(3L, cursors);
        cursors.put("alice", 99L);

        assertEquals(2L, frontier.sequenceFor("alice"));
        assertEquals(0L, frontier.sequenceFor("missing"));
        assertThrows(UnsupportedOperationException.class,
                () -> frontier.timelineSequences().put("bob", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentFrontier(-1L, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentFrontier(1L, Map.of("", 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvironmentFrontier(1L, Map.of("alice", -1L)));
    }

    @Test
    void metricsAreStableDefensiveSnapshots() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("work", 2L);
        CoordinationMetrics metrics = new CoordinationMetrics(
                counters, Map.of("phase", 2_500_000L),
                1, 2, 3, 4, 5L);
        counters.put("work", 99L);

        assertEquals(2L, metrics.counter("work"));
        assertEquals(0L, metrics.counter("absent"));
        assertEquals(2.5, metrics.millis("phase"));
        assertThrows(UnsupportedOperationException.class,
                () -> metrics.counters().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new CoordinationMetrics(Map.of(), Map.of(),
                        -1, 0, 0, 0, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new CoordinationMetrics(Map.of(), Map.of(),
                        0, 0, 0, 0, 0L));
    }

    @Test
    void typedFailuresPreserveCauseAndImmutableDetails() {
        RuntimeException cause = new RuntimeException("root cause");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("documentId", "counter");
        CoordinationException failure = new CoordinationException(
                CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                "missing", cause, details);
        details.put("documentId", "changed");

        assertEquals(CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                failure.code());
        assertEquals(cause, failure.getCause());
        assertEquals("counter", failure.details().get("documentId"));
        assertThrows(UnsupportedOperationException.class,
                () -> failure.details().clear());
    }

    @Test
    void builderFailsClosedUntilInMemoryModeIsSelected() {
        CoordinationException failure = assertThrows(
                CoordinationException.class,
                () -> CoordinationEngine.builder().build());

        assertEquals(CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                failure.code());
    }

    @Test
    void appendProducesSelfContainedExactImmutableEvidence() {
        try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
            Timeline timeline = engine.registerTimeline("feed", "alice");
            TimelineEntry entry = engine.append(timeline, Operation.yaml(
                    "touch", "owner", "value: 1"));

            assertEquals(1L, entry.globalSequence());
            assertEquals(1L, entry.timelineSequence());
            assertTrue(entry.appendFrontier().includes(entry));
            assertFalse(entry.processorManaged());
            assertTrue(entry.target().isEmpty());
            assertEquals(entry.blueId(), entry.exactEvent().blueId());
            assertNotNull(entry.sourceOrderKey());

            TimelineEntry.CatchUpCause cause = new TimelineEntry.CatchUpCause(
                    DocumentId.of("parent"), entry.blueId(), "/child",
                    entry.timestampMicros());
            TimelineEntry enriched = entry.withCatchUpCause(cause);
            assertEquals(cause, enriched.cause().orElseThrow());
            assertEquals(entry.blueId(), enriched.blueId());
            assertThrows(IllegalArgumentException.class,
                    () -> new TimelineEntry.CatchUpCause(
                            DocumentId.of("parent"), entry.blueId(),
                            "/child", 0L));
        }
    }

    @Test
    void zeroTargetDispatchIsImmutableAndOnlyOutcomeFailsClearly() {
        try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
            Timeline timeline = engine.registerTimeline("feed", "alice");
            DispatchResult result = engine.appendAndDispatch(
                    timeline, Operation.yaml("unknown", "owner", "{}"));

            assertTrue(result.outcomes().isEmpty());
            assertTrue(result.elapsedNanos() >= 0L);
            assertThrows(UnsupportedOperationException.class,
                    () -> result.outcomes().clear());
            CoordinationException failure = assertThrows(
                    CoordinationException.class, result::onlyOutcome);
            assertEquals(CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                    failure.code());
        }
    }

    @Test
    void missingDocumentsUseTheStableTypedErrorModel() {
        try (CoordinationEngine engine = CoordinationEngine.inMemory()) {
            CoordinationException failure = assertThrows(
                    CoordinationException.class,
                    () -> engine.document(DocumentId.of("missing")));

            assertEquals(CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                    failure.code());
            assertEquals("missing", failure.details().get("documentId"));
        }
    }

    @Test
    void closeIsIdempotentAndFurtherMutationFails() {
        CoordinationEngine engine = CoordinationEngine.inMemory();
        engine.close();
        engine.close();

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> engine.registerTimeline("feed", "alice"));
        assertNotEquals("", failure.getMessage());
    }
}
