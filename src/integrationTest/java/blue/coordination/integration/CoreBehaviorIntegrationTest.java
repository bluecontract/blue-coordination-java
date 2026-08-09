package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static blue.coordination.integration.EngineTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end semantic contracts that every release must execute. */
final class CoreBehaviorIntegrationTest {
    private static final long T0 = 1_700_000_000_000_000L;

    @Test
    void counterRoutesAliceAndBobExactlyOnceWithoutGenericSplitting()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            Timeline bob = engine.timeline(
                    "examples/clean-counter/bob", "bob");
            engine.start("counter", resource("examples/clean/counter.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.appendAndDispatch(alice, Operation.yaml(
                    "increment", "aliceChannel", "amount: 3"));
            engine.appendAndDispatch(bob, Operation.yaml(
                    "decrement", "bobChannel", "amount: 1"));

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            assertEquals(2L, integer(engine, "counter", "/counter"));
            assertEquals(2L, engine.session("counter").epoch());
            assertEquals(2, engine.journalSize());
            assertEquals(2L, work.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(2L, work.counter("routing.targetsSelected"));
            assertEquals(2L, work.counter(
                    "process.commitCompanionDeltasApplied"));
            assertEquals(2L, work.counter("process.routingSurfaceReused"));
            assertEquals(0L, work.counter("process.routingSurfaceChanges"));
            assertNoGenericSplitting(work);
        }
    }

    @Test
    void ordinaryPayNoteIsOneWholeInlineValue() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/whole-request/alice", "alice");
            engine.start("whole-request-sink", resource(
                    "examples/clean/whole-request-sink.yaml"));
            String payNoteYaml = resource(
                    "examples/clean/package-paynote.yaml");
            var request = engine.referencedValueRequest(
                    "payload", payNoteYaml);
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            engine.appendAndDispatch(alice, Operation.exact(
                    "storePayload", "aliceChannel", request));

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            Node stored = engine.value("whole-request-sink", "/payload");
            String storedBlueId = stored.isReferenceOnly()
                    ? stored.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(stored);
            Node expected = engine.exactRequest(payNoteYaml).copyNode();
            assertEquals(DirectBlueIdCalculator.calculateBlueId(expected),
                    storedBlueId);
            assertEquals(1, engine.session("whole-request-sink")
                    .layout().physicalObjectCount());
            assertEquals(0, engine.session("whole-request-sink")
                    .layout().embeddedDocumentCount());
            assertEquals(1L, work.counter(
                    "process.frozenContractsInvocations"));
            assertEquals(0L, work.counter("layout.catalogCompilations"));
            assertNoGenericSplitting(work);
        }
    }

    @Test
    void existingChildRevisionsCatchParentUpWithoutSourceReplay()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline child = engine.timeline("examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            increment(engine, child, T0 + 100L, 1);
            increment(engine, child, T0 + 200L, 2);
            increment(engine, child, T0 + 300L, 3);
            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();

            Timeline parent = engine.timeline("examples/embedded/B", "bob");
            engine.start("embedded-parent-B", resource(
                    "examples/clean/embedded-parent.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.dispatch(engine.appendAt(parent, Operation.exact(
                    "attachChild", "ownerChannel",
                    engine.embeddedDocumentRequest(childInitial)),
                    T0 + 1_000L));

            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());
            assertEquals(SessionStatus.READY,
                    engine.session("embedded-parent-B").status());
            assertEquals(6L, integer(
                    engine, "embedded-parent-B", "/child/counter"));
            assertEquals(childHistoryBefore,
                    engine.history("embedded-counter-A").size());
            assertEquals(Set.of("examples/embedded/A", "examples/embedded/B"),
                    engine.effectiveTimelineIds("embedded-parent-B"));
            assertEquals(1L, work.counter("embedding.childSessionsReused"));
            assertEquals(0L, work.counter("catchUp.childEntriesProcessed"));
            assertEquals(4L, work.counter(
                    "catchUp.parentRevisionApplications"));
            assertNoGenericSplitting(work);
        }
    }

    @Test
    void completedNbaGameCatchesUpAndContinuesLiveWithoutReplay()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String gameInitial = resource("examples/clean/nba-game.yaml");
            Timeline gameFeed = engine.timeline(
                    "examples/nba/game-2016-lal-min", "nba-feed");
            engine.start("nba-game-2016-lal-min", gameInitial);
            game(engine, gameFeed, T0 + 100L, "startGame", "{}");
            game(engine, gameFeed, T0 + 200L, "homeScores", "points: 2");
            game(engine, gameFeed, T0 + 300L, "awayScores", "points: 3");
            game(engine, gameFeed, T0 + 400L, "endGame", "{}");
            List<DocumentRevision> gameHistory = engine.history(
                    "nba-game-2016-lal-min");

            Timeline commissioner = engine.timeline(
                    "examples/nba/statistics", "commissioner");
            engine.start("nba-statistics", resource(
                    "examples/clean/nba-statistics.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.dispatch(engine.appendAt(commissioner, Operation.exact(
                    "attachGame", "commissionerChannel",
                    engine.embeddedDocumentRequest(gameInitial)),
                    T0 + 1_000L));

            EngineTestSupport.MetricDelta catchUp = delta(
                    before, engine.metricsSnapshot());
            assertEquals("Final", text(
                    engine, "nba-statistics", "/observedStatus"));
            assertEquals(2L, integer(
                    engine, "nba-statistics", "/observedHomeScore"));
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedAwayScore"));
            assertEquals(gameHistory.size(),
                    engine.history("nba-game-2016-lal-min").size());
            assertEquals(0L, catchUp.counter("catchUp.childEntriesProcessed"));
            assertEquals(5L, catchUp.counter(
                    "catchUp.parentRevisionApplications"));

            EngineMetrics.MetricsSnapshot beforeLive = engine.metricsSnapshot();
            game(engine, gameFeed, T0 + 2_000L,
                    "homeScores", "points: 1");
            EngineTestSupport.MetricDelta live = delta(
                    beforeLive, engine.metricsSnapshot());
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedHomeScore"));
            assertEquals(3L, integer(
                    engine, "nba-statistics", "/observedPlayCount"));
            assertEquals(2L, live.counter(
                    "process.frozenContractsInvocations"));
            assertFalse(engine.history("nba-statistics").get(
                    engine.history("nba-statistics").size() - 1)
                    .catchUpCause().isEmpty());
            assertNoGenericSplitting(live);
        }
    }

    private static void increment(
            TestEngine engine,
            Timeline timeline,
            long timestamp,
            int amount) {
        engine.dispatch(engine.appendAt(timeline, Operation.yaml(
                "increment", "ownerChannel", "amount: " + amount),
                timestamp));
    }

    private static void game(
            TestEngine engine,
            Timeline timeline,
            long timestamp,
            String operation,
            String request) {
        engine.dispatch(engine.appendAt(timeline, Operation.yaml(
                operation, "gameFeed", request), timestamp));
    }
}
