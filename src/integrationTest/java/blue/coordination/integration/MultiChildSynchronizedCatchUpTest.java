package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Three newly attached histories share one source-ordered parent barrier. */
final class MultiChildSynchronizedCatchUpTest {
    private static final long T0 = 1_710_000_000_000_000L;

    @Test
    void threeHistoriesMergeByCanonicalOrderUnderOneBarrier()
            throws Exception {
        Outcome canonical = run(AppendOrder.CANONICAL);
        Outcome shuffled = run(AppendOrder.SHUFFLED);

        assertNotEquals(canonical.appendSequences(),
                shuffled.appendSequences(),
                "the two runs must use genuinely different insertion order");
        assertEquals(canonical.sourceEntryBlueIds(),
                shuffled.sourceEntryBlueIds(),
                "canonical source facts must retain the same identities");
        assertEquals(canonical.parentBlueId(), shuffled.parentBlueId());
        assertEquals(canonical.parentState(), shuffled.parentState());
        assertEquals(canonical.parentTrace(), shuffled.parentTrace());
    }

    private static Outcome run(AppendOrder appendOrder) throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline aTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            Timeline bTimeline = engine.timeline(
                    "examples/embedded/B", "brenda");
            Timeline cTimeline = engine.timeline(
                    "examples/embedded/C", "carol");
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/three-child-parent", "bob");
            ExactValue a = engine.registerType(child(
                    "child-a", "examples/embedded/A", "alice"));
            ExactValue b = engine.registerType(child(
                    "child-b", "examples/embedded/B", "brenda"));
            ExactValue c = engine.registerType(child(
                    "child-c", "examples/embedded/C", "carol"));

            Entries entries = appendHistory(
                    engine, appendOrder, aTimeline, bTimeline, cTimeline);

            engine.start(
                    "three-child-history-parent",
                    resource("examples/clean/three-child-history-parent.yaml"));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            TimelineEntry attachment = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachAll",
                            "ownerChannel",
                            referencedRequest(a, b, c)),
                    T0 + 1_000L);
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(3L, integer(engine, "child-a", "/counter"));
            assertEquals(30L, integer(engine, "child-b", "/counter"));
            assertEquals(100L, integer(engine, "child-c", "/counter"));
            assertEquals(3L, integer(
                    engine, "three-child-history-parent",
                    "/children/a/counter"));
            assertEquals(30L, integer(
                    engine, "three-child-history-parent",
                    "/children/b/counter"));
            assertEquals(100L, integer(
                    engine, "three-child-history-parent",
                    "/children/c/counter"));

            List<DocumentRevision> parentHistory = engine.history(
                    "three-child-history-parent");
            List<String> causalOrder = parentHistory.stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                    .map(revision -> revision.causalEntryBlueId()
                            .orElseThrow())
                    .toList();
            assertEquals(List.of(
                    attachment.blueId(),
                    attachment.blueId(),
                    attachment.blueId(),
                    entries.a1().blueId(),
                    entries.b1().blueId(),
                    entries.a2().blueId(),
                    entries.c1().blueId(),
                    entries.b2().blueId()), causalOrder);
            assertEquals(1L, work.counter(
                    "temporal.catchUpBarriersCreated"));
            assertEquals(1L, work.counter(
                    "temporal.catchUpBarriersCompleted"));
            assertEquals(5L, work.counter(
                    "temporal.historicalEntriesReplayed"));
            assertEquals(5L, work.counter("childHistoricalProcessCalls"));
            assertEquals(8L, work.counter(
                    "temporal.parentEpochApplications"));
            assertEquals(2L, work.counter(
                    "process.embeddedInput.eventTemplatesCompiled"));
            assertEquals(6L, work.counter(
                    "process.embeddedInput.eventTemplateHits"));

            return new Outcome(
                    engine.session("three-child-history-parent")
                            .current().blueId(),
                    new ParentState(
                            integer(engine, "three-child-history-parent",
                                    "/children/a/counter"),
                            integer(engine, "three-child-history-parent",
                                    "/children/b/counter"),
                            integer(engine, "three-child-history-parent",
                                    "/children/c/counter")),
                    parentHistory.stream()
                            .map(revision -> new ParentTrace(
                                    revision.kind(),
                                    revision.causalEntryBlueId()
                                            .orElseThrow(),
                                    revision.rootApplicationOrder(),
                                    revision.after().blueId()))
                            .toList(),
                    entries.canonicalOrder().stream()
                            .map(TimelineEntry::blueId)
                            .toList(),
                    entries.canonicalOrder().stream()
                            .map(TimelineEntry::globalSequence)
                            .toList());
        }
    }

    private static Entries appendHistory(
            TestEngine engine,
            AppendOrder appendOrder,
            Timeline aTimeline,
            Timeline bTimeline,
            Timeline cTimeline) {
        return switch (appendOrder) {
            case CANONICAL -> new Entries(
                    engineAppend(engine, aTimeline, 1, 100L),
                    engineAppend(engine, bTimeline, 10, 150L),
                    engineAppend(engine, aTimeline, 2, 200L),
                    engineAppend(engine, cTimeline, 100, 250L),
                    engineAppend(engine, bTimeline, 20, 300L));
            case SHUFFLED -> {
                TimelineEntry c1 = engineAppend(
                        engine, cTimeline, 100, 250L);
                TimelineEntry b1 = engineAppend(
                        engine, bTimeline, 10, 150L);
                TimelineEntry a1 = engineAppend(
                        engine, aTimeline, 1, 100L);
                TimelineEntry b2 = engineAppend(
                        engine, bTimeline, 20, 300L);
                TimelineEntry a2 = engineAppend(
                        engine, aTimeline, 2, 200L);
                yield new Entries(a1, b1, a2, c1, b2);
            }
        };
    }

    private static TimelineEntry engineAppend(
            TestEngine engine,
            Timeline timeline,
            long amount,
            long timestampOffset) {
        return engine.appendAt(
                timeline, increment(amount), T0 + timestampOffset);
    }

    private static ExactValue referencedRequest(
            ExactValue a,
            ExactValue b,
            ExactValue c) {
        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("a", a.referenceNode());
        fields.put("b", b.referenceNode());
        fields.put("c", c.referenceNode());
        return ExactValue.verified(new Node().properties(fields));
    }

    private static Operation increment(long amount) {
        return Operation.yaml(
                "increment", "ownerChannel", "amount: " + amount);
    }

    private static String child(
            String documentId,
            String timelineId,
            String actorId) throws Exception {
        return resource("examples/clean/embedded-counter.yaml")
                .replace("documentId: embedded-counter-A",
                        "documentId: " + documentId)
                .replace("timelineId: examples/embedded/A",
                        "timelineId: " + timelineId)
                .replace("accountId: alice", "accountId: " + actorId);
    }

    private enum AppendOrder {
        CANONICAL,
        SHUFFLED
    }

    private record Entries(
            TimelineEntry a1,
            TimelineEntry b1,
            TimelineEntry a2,
            TimelineEntry c1,
            TimelineEntry b2) {
        private List<TimelineEntry> canonicalOrder() {
            return List.of(a1, b1, a2, c1, b2);
        }
    }

    private record ParentState(
            long childA,
            long childB,
            long childC) {
    }

    private record ParentTrace(
            DocumentRevision.Kind kind,
            String causalEntryBlueId,
            long applicationOrder,
            String afterBlueId) {
    }

    private record Outcome(
            String parentBlueId,
            ParentState parentState,
            List<ParentTrace> parentTrace,
            List<String> sourceEntryBlueIds,
            List<Long> appendSequences) {
    }
}
