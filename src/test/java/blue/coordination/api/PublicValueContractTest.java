package blue.coordination.api;

import blue.language.model.Node;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
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
        // given
        DocumentId first = DocumentId.of("a");
        DocumentId second = DocumentId.of("b");

        // when
        int comparison = first.compareTo(second);

        // then
        assertTrue(comparison < 0);
        assertEquals("a", first.toString());
        assertThrows(IllegalArgumentException.class,
                () -> DocumentId.of("  "));
        assertThrows(NullPointerException.class,
                () -> DocumentId.of(null));
    }

    @Test
    void timelinesRequireBothAuthenticatedIdentities() {
        // given
        String timelineId = "feed";
        String accountId = "alice";

        // when
        Timeline timeline = new Timeline(timelineId, accountId);

        // then
        assertEquals("feed", timeline.timelineId());
        assertThrows(IllegalArgumentException.class,
                () -> new Timeline("", "alice"));
        assertThrows(IllegalArgumentException.class,
                () -> new Timeline("feed", " "));
    }

    @Test
    void operationsHaveExactlyOneNormalizedRequestRepresentation() {
        // given
        ExactValue exact = ExactValue.verified(new Node().value("request"));

        // when
        Operation empty = Operation.yaml("touch", "owner", "  ");
        Operation reused = Operation.exact("touch", "owner", exact);

        // then
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
        // given
        Node source = new Node().properties(
                "value", new Node().value("original"));

        // when
        ExactValue exact = ExactValue.verified(source);
        source.getProperties().get("value").value("mutated");
        Node firstCopy = exact.copyNode();
        firstCopy.getProperties().get("value").value("copy-mutated");

        // then
        assertEquals("original", exact.copyNode()
                .getProperties().get("value").getValue());
        assertEquals(exact.blueId(), exact.referenceNode().getBlueId());
        assertTrue(exact.sameExactValue(ExactValue.verified(
                exact.copyNode())));
        assertThrows(IllegalArgumentException.class,
                () -> ExactValue.verified("wrong", exact.copyNode()));
    }

    @Test
    void metricsAreStableDefensiveSnapshots() {
        // given
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("ENTRIES_STORED_WHOLE", 2L);

        // when
        CoordinationMetrics metrics = new CoordinationMetrics(
                counters, Map.of("append.total", 2_500_000L),
                1, 2, 3, 4, 5L);
        counters.put("ENTRIES_STORED_WHOLE", 99L);

        // then
        for (CoordinationMetrics.Counter counter
                : CoordinationMetrics.Counter.values()) {
            long expected = counter
                    == CoordinationMetrics.Counter.ENTRIES_STORED_WHOLE
                    ? 2L
                    : 0L;
            assertEquals(expected, metrics.counter(counter.name()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> metrics.counter("work"));
        assertEquals(2.5, metrics.millis("append.total"));
        assertThrows(IllegalArgumentException.class,
                () -> metrics.millis("phase"));
        assertThrows(IllegalArgumentException.class,
                () -> new CoordinationMetrics(
                        Map.of("work", 1L), Map.of(),
                        0, 0, 0, 0, 1L));
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
        // given
        RuntimeException cause = new RuntimeException("root cause");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("documentId", "counter");

        // when
        CoordinationException failure = new CoordinationException(
                CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                "missing", cause, details);
        details.put("documentId", "changed");

        // then
        assertEquals(CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                failure.code());
        assertEquals(cause, failure.getCause());
        assertEquals("counter", failure.details().get("documentId"));
        assertThrows(UnsupportedOperationException.class,
                () -> failure.details().clear());
        assertTrue(failure.processorStatus().isEmpty());
        assertTrue(failure.processorCategory().isEmpty());
        assertTrue(failure.processorMessage().isEmpty());
        assertTrue(failure.processorDetails().isEmpty());
    }

    @Test
    void typedFailuresSnapshotFrozenProcessorDiagnosticsSeparately() {
        // given
        ProcessorDiagnostic diagnostic = ProcessorDiagnostic.builder(
                        ProcessorErrorCategory.SubscriptionSurfaceInvalid)
                .message("invalid subscription surface")
                .detail("scopePath", "/")
                .detail("contractKey", "embedded")
                .build();

        // when
        CoordinationException failure = new CoordinationException(
                CoordinationErrorCode.FROZEN_PROCESSING_FAILED,
                "admission rejected",
                null,
                Map.of("processorStatus", "host-metadata"),
                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                diagnostic);

        // then
        assertEquals(ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                failure.processorStatus().orElseThrow());
        assertEquals(ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                failure.processorCategory().orElseThrow());
        assertEquals("invalid subscription surface",
                failure.processorMessage().orElseThrow());
        assertEquals(Map.of(
                        "scopePath", "/",
                        "contractKey", "embedded"),
                failure.processorDetails());
        assertEquals("host-metadata",
                failure.details().get("processorStatus"));
        assertThrows(UnsupportedOperationException.class,
                () -> failure.processorDetails().clear());
    }

    @Test
    void builderFailsClosedUntilInMemoryModeIsSelected() {
        // given
        CoordinationEngine.Builder builder = CoordinationEngine.builder();

        // when
        CoordinationException failure = assertThrows(
                CoordinationException.class,
                builder::build);

        // then
        assertEquals(CoordinationErrorCode.ATOMIC_COMMIT_FAILED,
                failure.code());
    }

    @Test
    void processingAvailabilityFactoriesExposeOnlyHostAdmission() {
        // given
        ProcessingAvailability none = ProcessingAvailability.none();

        // when
        ProcessingAvailability unavailable = ProcessingAvailability.of(false);
        ProcessingAvailability available = ProcessingAvailability.of(true);

        // then
        assertFalse(none.journalAdmissionAvailable());
        assertFalse(unavailable.journalAdmissionAvailable());
        assertTrue(available.journalAdmissionAvailable());
    }

    @Test
    void appendProducesSelfContainedExactImmutableEvidence() {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline timeline = engine.registerTimeline("feed", "alice");

            // when
            TimelineEntry entry = engine.append(timeline, Operation.yaml(
                    "touch", "owner", "value: 1"));

            // then
            assertEquals(1L, entry.globalSequence());
            assertEquals(1L, entry.timelineSequence());
            assertEquals(entry.blueId(), entry.exactEvent().blueId());
            assertNotNull(entry.sourceOrderKey());
            assertTrue(engine.drain().processedEntries().contains(entry));
        }
    }

    @Test
    void zeroTargetDispatchIsImmutableAndOnlyOutcomeFailsClearly() {
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            Timeline timeline = engine.registerTimeline("feed", "alice");

            // when
            TimelineEntry entry = engine.append(
                    timeline, Operation.yaml("unknown", "owner", "{}"));
            ProcessingDrainReceipt result = engine.drainThrough(
                    entry.sourceOrderKey());

            // then
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
        try (CoordinationEngine engine = CoordinationEngine.legacyInMemory()) {
            // given
            DocumentId missing = DocumentId.of("missing");

            // when
            CoordinationException failure = assertThrows(
                    CoordinationException.class,
                    () -> engine.document(missing));

            // then
            assertEquals(CoordinationErrorCode.DOCUMENT_NOT_FOUND,
                    failure.code());
            assertEquals("missing", failure.details().get("documentId"));
        }
    }

    @Test
    void closeIsIdempotentAndFurtherMutationFails() {
        // given
        CoordinationEngine engine = CoordinationEngine.legacyInMemory();

        // when
        engine.close();
        engine.close();

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> engine.registerTimeline("feed", "alice"));

        // then
        assertNotEquals("", failure.getMessage());
    }
}
