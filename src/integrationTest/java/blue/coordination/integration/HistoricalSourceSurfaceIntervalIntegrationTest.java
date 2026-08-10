package blue.coordination.integration;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dynamic nested history must refresh routing from exact active intervals. */
final class HistoricalSourceSurfaceIntervalIntegrationTest {
    private static final long T0 = 1_736_000_000_000_000L;
    private static final String ROOT = "historical-surface-root";
    private static final String CONTROLLER =
            "historical-surface-controller";
    private static final String LEAF = "historical-surface-leaf";

    @Test
    void historicalAttachmentActivatesOnlyItsExactNestedSourceInterval()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String leaf = leafSource();
            String controller = parentSource(
                    CONTROLLER,
                    "examples/historical-surface/controller",
                    "controller-owner");
            Timeline leafTimeline = engine.timeline(
                    "examples/historical-surface/leaf", "leaf-owner");
            Timeline controllerTimeline = engine.timeline(
                    "examples/historical-surface/controller",
                    "controller-owner");
            Timeline rootTimeline = engine.timeline(
                    "examples/historical-surface/root", "root-owner");

            TimelineEntry excluded = engine.appendAt(
                    leafTimeline, increment(100L), T0 + 100L);
            TimelineEntry activation = engine.appendAt(
                    controllerTimeline,
                    attach(engine, leaf),
                    T0 + 200L);
            engine.configureEmbeddedAdmission(
                    LEAF,
                    ActivationMode.IMPORT_FROM_FRONTIER,
                    activation.sourceOrderKey());
            TimelineEntry historical = engine.appendAt(
                    leafTimeline, increment(3L), T0 + 300L);

            assertTrue(excluded.sourceOrderKey().compareTo(
                    activation.sourceOrderKey()) < 0);
            assertTrue(activation.sourceOrderKey().compareTo(
                    historical.sourceOrderKey()) < 0);

            engine.start(
                    ROOT,
                    parentSource(
                            ROOT,
                            "examples/historical-surface/root",
                            "root-owner"));
            TimelineEntry rootAttachment = engine.appendAt(
                    rootTimeline,
                    attach(engine, controller),
                    T0 + 1_000L);

            engine.dispatch(rootAttachment);

            assertEquals(0, engine.routeTargetCount(excluded),
                    "the lower-exclusive frontier must reject older facts");
            assertEquals(1, engine.routeTargetCount(historical),
                    "the newly active leaf surface must admit later history");
            assertEquals(3L, integer(engine, LEAF, "/counter"));
            assertEquals(3L, integer(
                    engine, CONTROLLER, "/child/counter"));
            assertEquals(3L, integer(
                    engine, ROOT, "/child/child/counter"));
            assertEquals(activation.blueId(), engine.history(LEAF).get(0)
                    .causalEntryBlueId().orElseThrow(),
                    "the historical controller transition owns admission");
            assertEquals(
                    List.of(historical.blueId()),
                    processedTimelineEntries(engine));

            TimelineEntry live = engine.appendAt(
                    leafTimeline, increment(2L), T0 + 1_100L);
            assertEquals(1, engine.routeTargetCount(live));
            engine.dispatch(live);

            assertEquals(5L, integer(engine, LEAF, "/counter"));
            assertEquals(5L, integer(
                    engine, CONTROLLER, "/child/counter"));
            assertEquals(5L, integer(
                    engine, ROOT, "/child/child/counter"));
            assertEquals(
                    List.of(historical.blueId(), live.blueId()),
                    processedTimelineEntries(engine),
                    "excluded history must never leak into the live interval");

            int leafHistorySize = engine.history(LEAF).size();
            engine.dispatch(live);
            assertEquals(leafHistorySize, engine.history(LEAF).size(),
                    "re-draining the live cutoff must be idempotent");
        }
    }

    private static Operation increment(long amount) {
        return Operation.yaml(
                "increment", "ownerChannel", "amount: " + amount);
    }

    private static Operation attach(TestEngine engine, String document) {
        return Operation.exact(
                "attachChild",
                "ownerChannel",
                engine.embeddedDocumentRequest(document));
    }

    private static List<String> processedTimelineEntries(TestEngine engine) {
        return engine.history(LEAF).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.TIMELINE_ENTRY)
                .map(revision -> revision.sourceEntry()
                        .orElseThrow().blueId())
                .toList();
    }

    private static String leafSource() throws Exception {
        return resource("examples/clean/embedded-counter.yaml")
                .replace("documentId: embedded-counter-A",
                        "documentId: " + LEAF)
                .replace("timelineId: examples/embedded/A",
                        "timelineId: examples/historical-surface/leaf")
                .replace("accountId: alice", "accountId: leaf-owner");
    }

    private static String parentSource(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return resource("examples/clean/embedded-state-parent.yaml")
                .replace("documentId: embedded-state-parent",
                        "documentId: " + documentId)
                .replace("coordination/internal/embedded-state-parent",
                        "coordination/internal/" + documentId)
                .replace("timelineId: examples/embedded/state-parent",
                        "timelineId: " + timelineId)
                .replace("accountId: bob", "accountId: " + actorId);
    }
}
