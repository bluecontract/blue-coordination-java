package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Determinism proofs for request order and resumable bounded draining. */
@Tag("scenario")
final class PlaygroundFiveOccurrenceDeterminismScenarioTest {

    @Test
    void reverseRequestInsertionOrderPreservesBlueIdsHistoriesAndEvents()
            throws Exception {
        // given
        List<String> deliberatelyUnorderedKeys =
                PlaygroundFiveOccurrenceFixtures.DELIBERATELY_UNORDERED_KEYS;
        List<String> reverseKeys =
                PlaygroundFiveOccurrenceFixtures.REVERSE_KEYS;

        // when
        RunResult deliberatelyUnordered = run(
                deliberatelyUnorderedKeys,
                false);
        RunResult reversed = run(
                reverseKeys,
                false);

        // then
        assertFalse(deliberatelyUnorderedKeys.equals(reverseKeys));
        assertEquals(deliberatelyUnordered.outcome(), reversed.outcome(),
                "map authoring order cannot affect Host/child exact identity, "
                        + "application order, history, or event multiplicity");
    }

    @Test
    void boundedDrainPauseAndResumeMatchesUnlimitedCommittedHistory()
            throws Exception {
        // given
        List<String> requestOrder = PlaygroundFiveOccurrenceFixtures
                .DELIBERATELY_UNORDERED_KEYS;

        // when
        RunResult unlimited = run(
                requestOrder, false);
        RunResult bounded = run(
                requestOrder, true);

        // then
        assertTrue(bounded.pausedAtLeastOnce(),
                "the deterministic one-transition budget must force a pause");
        assertFalse(unlimited.pausedAtLeastOnce());
        assertEquals(unlimited.outcome(), bounded.outcome(),
                "resume must not reinitialize a child, duplicate a parent event "
                        + "occurrence, or alter any committed exact history");
    }

    private static RunResult run(
            List<String> requestOrder,
            boolean bounded) throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    PlaygroundFiveOccurrenceFixtures.HOST_TIMELINE,
                    PlaygroundFiveOccurrenceFixtures.HOST_ACTOR);
            PlaygroundFiveOccurrenceFixtures.registerChildTimelines(engine);
            engine.start(
                    PlaygroundFiveOccurrenceFixtures.HOST_ID,
                    resource(PlaygroundFiveOccurrenceFixtures.HOST_RESOURCE));
            TimelineEntry attachment = engine.append(owner,
                    PlaygroundFiveOccurrenceFixtures.replaceDocuments(
                            engine, requestOrder));

            boolean paused = false;
            if (bounded) {
                int attempts = 0;
                while (true) {
                    ProcessingDrainReceipt receipt = engine.drain(
                            new CoordinationEngine.DrainBudget(1L, 100L));
                    paused |= receipt.paused();
                    if (receipt.quiescent()) {
                        break;
                    }
                    assertTrue(receipt.paused(),
                            "the closed fixture has no unavailable prerequisite");
                    assertTrue(++attempts < 32,
                            "bounded drain failed to make deterministic progress");
                }
            } else {
                ProcessingDrainReceipt receipt = engine.drain();
                assertTrue(receipt.quiescent());
            }

            return new RunResult(
                    outcome(engine, attachment.timestampMicros()), paused);
        }
    }

    private static Outcome outcome(
            TestEngine engine,
            long attachmentTimestampMicros) {
        String host = PlaygroundFiveOccurrenceFixtures.HOST_ID;
        List<String> children = List.of(
                PlaygroundFiveOccurrenceFixtures.ALPHA_ID,
                PlaygroundFiveOccurrenceFixtures.BETA_ID,
                PlaygroundFiveOccurrenceFixtures.GAMMA_ID);
        Map<String, String> finalBlueIds = new LinkedHashMap<>();
        finalBlueIds.put(host, engine.readyDocument(host).current().blueId());
        for (String child : children) {
            finalBlueIds.put(
                    child, engine.readyDocument(child).current().blueId());
        }

        Map<String, List<RevisionTrace>> histories = new LinkedHashMap<>();
        histories.put(host, trace(engine.history(host)));
        for (String child : children) {
            histories.put(child, trace(engine.history(child)));
            assertEquals(1L, engine.history(child).stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.INITIALIZATION)
                    .count(), child);
        }

        List<DocumentRevision.CatchUpCause> applicationCauses =
                engine.history(host).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                .map(revision -> revision.catchUpCause()
                        .orElseThrow())
                .toList();
        assertTrue(applicationCauses.stream().allMatch(cause ->
                        cause.attachmentTimestampMicros()
                                == attachmentTimestampMicros),
                "each application must retain the attachment timestamp, "
                        + "not its occurrence-specific application time");
        List<String> applicationPaths = applicationCauses.stream()
                .map(DocumentRevision.CatchUpCause::occurrencePath)
                .toList();
        assertEquals(PlaygroundFiveOccurrenceFixtures
                .expectedApplicationPaths(), applicationPaths);

        return new Outcome(
                Map.copyOf(finalBlueIds),
                Map.copyOf(histories),
                applicationPaths,
                integer(engine, host, "/initializationEventCount"),
                integer(engine, host, "/alphaInitializationEventCount"),
                integer(engine, host, "/betaInitializationEventCount"),
                integer(engine, host, "/gammaInitializationEventCount"),
                integer(engine, host, "/revisionApplications"));
    }

    private static List<RevisionTrace> trace(
            List<DocumentRevision> revisions) {
        return revisions.stream()
                .map(revision -> new RevisionTrace(
                        revision.epoch(),
                        revision.rootApplicationOrder(),
                        revision.kind(),
                        revision.before().map(value -> value.blueId())
                                .orElse(null),
                        revision.after().blueId(),
                        revision.causalEntryBlueId().orElse(null),
                        revision.catchUpCause()
                                .map(DocumentRevision.CatchUpCause
                                        ::occurrencePath)
                                .orElse(null),
                        revision.emittedEvents().stream()
                                .map(DirectBlueIdCalculator::calculateBlueId)
                                .toList()))
                .toList();
    }

    private record RunResult(
            Outcome outcome,
            boolean pausedAtLeastOnce) {
    }

    private record Outcome(
            Map<String, String> finalBlueIds,
            Map<String, List<RevisionTrace>> histories,
            List<String> parentApplicationPaths,
            long initializationEventCount,
            long alphaEventCount,
            long betaEventCount,
            long gammaEventCount,
            long parentApplications) {
    }

    private record RevisionTrace(
            long epoch,
            long rootApplicationOrder,
            DocumentRevision.Kind kind,
            String beforeBlueId,
            String afterBlueId,
            String causalEntryBlueId,
            String occurrencePath,
            List<String> emittedEventBlueIds) {
    }
}
