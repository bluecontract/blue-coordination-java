package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.internal.CoordinationTestControl;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Round 13 duplicate-occurrence correctness proofs. */
final class PlaygroundFiveOccurrenceCorrectnessIntegrationTest {
    private static final String HOST =
            PlaygroundFiveOccurrenceFixtures.HOST_ID;
    private static final String ALPHA_FIRST_PATH = "/children/alphaFirst";
    private static final String ALPHA_SECOND_PATH = "/children/alphaSecond";

    @Test
    void conflictingStatesAreRejectedAndCoherentRetryWorksOnSameEngine()
            throws Exception {
        // given
        String alphaYaml = Round12NbaFixtures.game(
                PlaygroundFiveOccurrenceFixtures.ALPHA_ID,
                PlaygroundFiveOccurrenceFixtures.alphaTimeline(),
                "playground-feed-alpha");
        String conflictingAlphaYaml = alphaYaml.replace(
                "homeScore: 0", "homeScore: 99");

        try (TestEngine engine = initializedHost()) {
            Timeline owner = owner(engine);
            ExactValue alpha = engine.exactValue(alphaYaml);
            ExactValue conflictingAlpha = engine.exactValue(
                    conflictingAlphaYaml);
            long hostEpochBefore = engine.session(HOST).epoch();
            String hostBlueIdBefore = engine.session(HOST).current().blueId();
            int hostHistoryBefore = engine.history(HOST).size();
            var rejected = engine.append(owner, attachment(
                    engine,
                    orderedDocuments(
                            PlaygroundFiveOccurrenceFixtures.ALPHA_FIRST,
                            alpha,
                            PlaygroundFiveOccurrenceFixtures.ALPHA_SECOND,
                            conflictingAlpha)));

            // when
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> engine.dispatch(rejected));

            assertTrue(failure.getMessage().contains(
                    "Invalid admission evidence: conflicting supplied states"),
                    failure::getMessage);
            assertEquals(1, engine.documentCount(),
                    "the conflicting child must not leave any managed session");
            assertEquals(hostEpochBefore, engine.session(HOST).epoch(),
                    "invalid attachment evidence cannot commit a Host epoch");
            assertEquals(hostBlueIdBefore,
                    engine.session(HOST).current().blueId());
            assertEquals(SessionStatus.READY,
                    engine.session(HOST).status());
            assertEquals(hostBlueIdBefore,
                    engine.readyDocument(HOST).current().blueId(),
                    "only the prior coherent Host epoch remains READY");
            assertTrue(engine.embeddedDocuments(HOST).isEmpty());
            assertEquals(hostHistoryBefore, engine.history(HOST).size(),
                    "the rejected attachment is atomic at the Host revision");

            engine.appendAndDispatch(owner, attachment(
                    engine,
                    orderedDocuments(
                            PlaygroundFiveOccurrenceFixtures.ALPHA_FIRST,
                            alpha,
                            PlaygroundFiveOccurrenceFixtures.ALPHA_SECOND,
                            alpha)));

            // then
            assertEquals(2, engine.documentCount());
            assertEquals(Map.of(
                            ALPHA_FIRST_PATH,
                            PlaygroundFiveOccurrenceFixtures.ALPHA_ID,
                            ALPHA_SECOND_PATH,
                            PlaygroundFiveOccurrenceFixtures.ALPHA_ID),
                    engine.embeddedDocuments(HOST));
            assertEquals(1L, engine.history(
                            PlaygroundFiveOccurrenceFixtures.ALPHA_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST).status());
            assertEquals(hostHistoryBefore + 3, engine.history(HOST).size(),
                    "one coherent external transition and two occurrence "
                            + "applications commit exactly once");
            assertEquals(2, engine.journalSize(),
                    "the immutable rejected entry and coherent retry are both "
                            + "retained");
        }
    }

    @Test
    void removingAndReaddingOneDuplicateUsesOneSourceProcessAndFreshCursor()
            throws Exception {
        try (TestEngine engine = initializedHostWithAlphaReattachment()) {
            // given
            Timeline owner = owner(engine);
            engine.appendAndDispatch(
                    owner, PlaygroundFiveOccurrenceFixtures.attachFive(engine));

            CoordinationTestControl.EmbeddedOccurrenceEvidence oldSecond =
                    occurrence(engine, ALPHA_SECOND_PATH);
            CoordinationTestControl.EmbeddedOccurrenceEvidence stableFirst =
                    occurrence(engine, ALPHA_FIRST_PATH);
            assertEquals(0L, oldSecond.appliedChildEpoch());
            assertEquals(1, oldSecond.committedReceiptIds().size());

            Timeline gamma = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.gammaTimeline(),
                    "playground-feed-gamma");
            engine.appendAndDispatch(gamma, Round12NbaFixtures.startGame());

            assertTrue(engine.embeddedOccurrenceEvidence().stream()
                    .noneMatch(evidence -> evidence.bindingId().equals(
                            oldSecond.bindingId())),
                    "the retired cursor must leave the active graph");
            assertEquals(stableFirst.bindingId(),
                    occurrence(engine, ALPHA_FIRST_PATH).bindingId());

            EngineMetrics.MetricsSnapshot beforeAdvance =
                    engine.metricsSnapshot();
            Timeline alpha = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.alphaTimeline(),
                    "playground-feed-alpha");

            // when
            engine.appendAndDispatch(alpha, Round12NbaFixtures.startGame());
            EngineTestSupport.MetricDelta advance = delta(
                    beforeAdvance, engine.metricsSnapshot());

            assertEquals(1L, advance.counter(
                    "temporal.externalProcessCalls"));
            assertEquals(1L, advance.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(1L, advance.counter(
                    "temporal.parentEpochApplications"));
            assertEquals(1L, occurrence(
                    engine, ALPHA_FIRST_PATH).appliedChildEpoch());
            assertTrue(engine.embeddedOccurrenceEvidence().stream()
                    .noneMatch(evidence -> evidence.occurrencePath().equals(
                            ALPHA_SECOND_PATH)));
            assertEquals("Live", text(engine, HOST,
                    ALPHA_FIRST_PATH + "/status"));
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST).status());

            int parentHistoryBeforeReadd = engine.history(HOST).size();
            engine.appendAndDispatch(
                    gamma, Round12NbaFixtures.homeScores(1));

            CoordinationTestControl.EmbeddedOccurrenceEvidence newSecond =
                    occurrence(engine, ALPHA_SECOND_PATH);

            // then
            assertNotEquals(oldSecond.bindingId(), newSecond.bindingId());
            assertTrue(newSecond.activationGeneration()
                    > oldSecond.activationGeneration());
            assertEquals(1L, newSecond.appliedChildEpoch());
            assertEquals(2, newSecond.committedReceiptIds().size(),
                    "the fresh occurrence catches initialization and the missing "
                            + "live epoch exactly once each");
            assertTrue(newSecond.committedReceiptIds().stream()
                    .allMatch(receipt -> receipt.contains(
                            newSecond.bindingId())));
            assertEquals(stableFirst.bindingId(),
                    occurrence(engine, ALPHA_FIRST_PATH).bindingId(),
                    "the continuously active occurrence retains its cursor");
            assertEquals(parentHistoryBeforeReadd + 3,
                    engine.history(HOST).size(),
                    "one external replacement plus exactly two alphaSecond "
                            + "catch-up applications");
            assertEquals("Live", text(engine, HOST,
                    ALPHA_SECOND_PATH + "/status"));
            assertEquals(1L, engine.history(
                            PlaygroundFiveOccurrenceFixtures.ALPHA_ID).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST).status());
        }
    }

    @Test
    void oneInitializationEventBlueIdHasDistinctOccurrenceReceipts()
            throws Exception {
        try (TestEngine engine = initializedHost()) {
            // given
            Timeline owner = owner(engine);

            // when
            engine.appendAndDispatch(
                    owner,
                    PlaygroundFiveOccurrenceFixtures.attachFive(engine));

            DocumentRevision alphaInitialization = engine.history(
                    PlaygroundFiveOccurrenceFixtures.ALPHA_ID).get(0);

            // then
            assertEquals(1, alphaInitialization.emittedEvents().size());
            String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                    alphaInitialization.emittedEvents().get(0));
            assertTrue(!eventBlueId.isBlank());
            assertEquals(2L, integer(engine, HOST,
                    "/alphaInitializationEventCount"),
                    "the Host observes the one exact initialization event at "
                            + "both alpha paths");
            assertEquals(eventBlueId, text(engine, HOST,
                    "/alphaFirstInitializationEventBlueId"));
            assertEquals(eventBlueId, text(engine, HOST,
                    "/alphaSecondInitializationEventBlueId"));

            List<String> alphaPaths = engine.history(HOST).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                            .EMBEDDED_REVISION_APPLICATION)
                    .map(revision -> revision.catchUpCause()
                            .orElseThrow().occurrencePath())
                    .filter(path -> path.equals(ALPHA_FIRST_PATH)
                            || path.equals(ALPHA_SECOND_PATH))
                    .toList();
            assertEquals(List.of(
                    ALPHA_FIRST_PATH, ALPHA_SECOND_PATH), alphaPaths);

            CoordinationTestControl.EmbeddedOccurrenceEvidence first =
                    occurrence(engine, ALPHA_FIRST_PATH);
            CoordinationTestControl.EmbeddedOccurrenceEvidence second =
                    occurrence(engine, ALPHA_SECOND_PATH);
            assertNotEquals(first.bindingId(), second.bindingId());
            assertEquals(1, first.committedReceiptIds().size());
            assertEquals(1, second.committedReceiptIds().size());
            assertNotEquals(first.committedReceiptIds().get(0),
                    second.committedReceiptIds().get(0),
                    "each occurrence commits its own parent application receipt");
        }
    }

    private static TestEngine initializedHost() throws Exception {
        TestEngine engine = TestEngine.create();
        PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
        engine.start(HOST, resource(
                PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE));
        return engine;
    }

    private static TestEngine initializedHostWithAlphaReattachment()
            throws Exception {
        TestEngine engine = TestEngine.create();
        PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
        ExactValue alpha = PlaygroundFiveOccurrenceFixtures.alpha(engine);
        String host = resource(
                PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE).replace(
                "alphaReattachment: None",
                "alphaReattachment:\n  blueId: " + alpha.blueId());
        engine.start(HOST, host);
        return engine;
    }

    private static Timeline owner(TestEngine engine) {
        return engine.timeline(
                PlaygroundFiveOccurrenceFixtures.HOST_TIMELINE,
                PlaygroundFiveOccurrenceFixtures.HOST_ACTOR);
    }

    private static Operation attachment(
            TestEngine engine,
            Map<String, ExactValue> documents) {
        StringBuilder request = new StringBuilder("documents:\n");
        documents.forEach((key, value) -> request
                .append("  ").append(key).append(":\n")
                .append("    blueId: ").append(value.blueId()).append('\n'));
        return Operation.exact(
                "attachFiveDocuments",
                "ownerChannel",
                engine.exactRequest(request.toString()));
    }

    private static Map<String, ExactValue> orderedDocuments(
            String firstKey,
            ExactValue first,
            String secondKey,
            ExactValue second) {
        Map<String, ExactValue> result = new LinkedHashMap<>();
        result.put(firstKey, first);
        result.put(secondKey, second);
        return result;
    }

    private static CoordinationTestControl.EmbeddedOccurrenceEvidence occurrence(
            TestEngine engine,
            String path) {
        return engine.embeddedOccurrenceEvidence().stream()
                .filter(evidence -> evidence.parentDocumentId().equals(HOST))
                .filter(evidence -> evidence.occurrencePath().equals(path))
                .findFirst()
                .orElseThrow();
    }
}
