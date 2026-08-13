package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.indent;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A dynamic collection member publishes its own dynamic fixed-path graph. */
@Tag("scenario")
final class NestedDynamicProcessEmbeddedActivationTest {
    private static final String HOST_ID =
            "nested-dynamic-collection-host";
    private static final String CHILD_ID =
            "nested-dynamic-collection-child";
    private static final String GAME_ID = "round12-nested-dynamic-game";

    @Test
    void childInitializationRecursivelySettlesGrandchildBeforeHostReady()
            throws Exception {
        String gameYaml = Round12NbaFixtures.game(
                GAME_ID,
                "examples/round12/dynamic/nested/game",
                "nested-game-feed");
        String hostYaml = resource(
                "examples/round12/nested-dynamic-collection-host.yaml")
                .replace(
                        "    game: __GAME_DOCUMENT__",
                        "    game:\n" + indent(gameYaml.strip(), 6));

        try (TestEngine engine = TestEngine.create()) {
            Timeline owner = engine.timeline(
                    "examples/round12/dynamic/nested/host",
                    "nested-host-owner");
            engine.timeline(
                    "examples/round12/dynamic/nested/game",
                    "nested-game-feed");

            engine.start(HOST_ID, hostYaml);
            assertEquals(1, engine.documentCount());
            assertEquals(Map.of(), engine.embeddedDocuments(HOST_ID));

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry activation = engine.append(
                    owner,
                    Operation.yaml(
                            "activateChildren", "ownerChannel", "{}"));
            engine.dispatch(activation);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(3, engine.documentCount());
            assertEquals(Map.of("/children/alpha", CHILD_ID),
                    engine.embeddedDocuments(HOST_ID));
            assertEquals(Map.of("/game", GAME_ID),
                    engine.embeddedDocuments(CHILD_ID));
            assertEquals(SessionStatus.READY,
                    engine.session(HOST_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.session(CHILD_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.session(GAME_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(HOST_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(CHILD_ID).status());
            assertEquals(SessionStatus.READY,
                    engine.readyDocument(GAME_ID).status());

            assertEquals(1L, integer(
                    engine, CHILD_ID, "/childInitializationCount"));
            assertEquals(1L, integer(
                    engine, GAME_ID, "/initializationCount"));
            assertEquals(1L, integer(
                    engine, CHILD_ID, "/grandchildRevisionApplications"));
            assertEquals(1L, integer(
                    engine, CHILD_ID,
                    "/grandchildInitializationEventCount"));
            assertEquals(2L, integer(
                    engine, HOST_ID, "/childRevisionApplications"));
            assertEquals(1L, integer(
                    engine, HOST_ID, "/childInitializationEventCount"));

            List<DocumentRevision> gameHistory = engine.history(GAME_ID);
            List<DocumentRevision> childHistory = engine.history(CHILD_ID);
            List<DocumentRevision> hostHistory = engine.history(HOST_ID);
            assertEquals(List.of(DocumentRevision.Kind.INITIALIZATION),
                    kinds(gameHistory));
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    kinds(childHistory));
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    kinds(hostHistory));
            assertTrue(gameHistory.stream()
                    .allMatch(revision -> activation.blueId().equals(
                            revision.causalEntryBlueId().orElseThrow())));
            assertTrue(childHistory.stream()
                    .allMatch(revision -> activation.blueId().equals(
                            revision.causalEntryBlueId().orElseThrow())));
            assertTrue(hostHistory.subList(1, hostHistory.size()).stream()
                    .allMatch(revision -> activation.blueId().equals(
                            revision.causalEntryBlueId().orElseThrow())));

            List<DocumentRevision> hostApplications = hostHistory.stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind
                                    .EMBEDDED_REVISION_APPLICATION)
                    .toList();
            assertEquals(List.of(0L, 1L), hostApplications.stream()
                    .map(revision -> number(
                            revision.after(),
                            "/children/alpha/"
                                    + "grandchildRevisionApplications"))
                    .toList(),
                    "the Host must retain child initialization before the "
                            + "child's settled grandchild application");
            assertEquals(List.of(0L, 1L), hostApplications.stream()
                    .map(revision -> number(
                            revision.after(),
                            "/children/alpha/game/initializationCount"))
                    .toList());

            Map<String, CatchUpPlan> plans = engine.catchUpPlans().stream()
                    .collect(Collectors.toMap(
                            plan -> plan.link().parentDocumentId().value()
                                    + "->"
                                    + plan.link().childDocumentId().value(),
                            Function.identity()));
            assertEquals(2, plans.size());
            assertComplete(plans.get(HOST_ID + "->" + CHILD_ID), 1L);
            assertComplete(plans.get(CHILD_ID + "->" + GAME_ID), 0L);

            assertEquals(2L, work.counter(
                    "embedding.childSessionsCreated"));
            assertEquals(2L, work.counter(
                    "temporal.initializationEpochApplications"));
            assertEquals(2L, work.counter(
                    "temporal.initializationEventOccurrencesForwarded"));
            assertEquals(3L, work.counter(
                    "temporal.parentEpochApplications"));
            assertNoGenericSplitting(work);
        }
    }

    private static List<DocumentRevision.Kind> kinds(
            List<DocumentRevision> revisions) {
        return revisions.stream().map(DocumentRevision::kind).toList();
    }

    private static void assertComplete(
            CatchUpPlan plan,
            long appliedEpoch) {
        assertNotNull(plan);
        assertEquals(CatchUpPlan.Status.COMPLETE, plan.status());
        assertEquals(appliedEpoch, plan.link().appliedChildEpoch());
    }

    private static long number(
            ExactValue state,
            String path) {
        FrozenNode selected = state.canonicalAt(path);
        if (selected == null || selected.getValue() == null) {
            throw new AssertionError("Missing Integer at " + path);
        }
        Object value = selected.getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected Integer at " + path
                + " but got " + value);
    }
}
