package blue.coordination.integration;

import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.coordination.integration.EngineTestSupport.indent;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Global source ordering across one sibling and one nested catch-up branch. */
final class NestedSiblingGlobalCatchUpOrderingTest {
    private static final long T0 = 1_735_000_000_000_000L;

    @Test
    void earlierSiblingHistoryPrecedesLaterNestedHistoryUnderOneRootBarrier()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String a11Source = leafSource();
            ExactValue a1 = engine.registerType(middleSource(a11Source));
            ExactValue a2 = engine.registerType(a2Source());
            Timeline a11Timeline = engine.timeline(
                    "examples/nested-sibling/a11", "a11-owner");
            Timeline a2Timeline = engine.timeline(
                    "examples/nested-sibling/a2", "a2-owner");
            Timeline rootTimeline = engine.timeline(
                    "examples/nested-sibling/root", "root-owner");

            // Deliberately append the later nested entry first. The barrier
            // must use canonical source order, never append/caller order.
            TimelineEntry a11Entry = engine.appendAt(
                    a11Timeline,
                    Operation.yaml(
                            "advance", "sharedChannel", "amount: 11"),
                    T0 + 200L);
            TimelineEntry a2Entry = engine.appendAt(
                    a2Timeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"),
                    T0 + 100L);
            assertTrue(a2Entry.sourceOrderKey().compareTo(
                    a11Entry.sourceOrderKey()) < 0);

            engine.start(
                    "nested-sibling-history-root",
                    resource("examples/clean/nested-sibling-history-root.yaml"));
            TimelineEntry attachment = engine.appendAt(
                    rootTimeline,
                    Operation.exact(
                            "attachBoth",
                            "ownerChannel",
                            attachmentRequest(a1, a2)),
                    T0 + 1_000L);
            engine.dispatch(attachment);

            assertEquals(List.of(
                            trace(DocumentRevision.Kind.INITIALIZATION,
                                    attachment),
                            trace(DocumentRevision.Kind.TIMELINE_ENTRY,
                                    a2Entry)),
                    trace(engine.history("nested-sibling-a2")));
            assertEquals(List.of(
                            trace(DocumentRevision.Kind.INITIALIZATION,
                                    attachment),
                            trace(DocumentRevision.Kind.TIMELINE_ENTRY,
                                    a11Entry)),
                    trace(engine.history("nested-sibling-a11")));
            assertEquals(List.of(
                            trace(DocumentRevision.Kind.INITIALIZATION,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    a11Entry)),
                    trace(engine.history("nested-sibling-a1")));

            List<String> rootTrace = trace(engine.history(
                    "nested-sibling-history-root"));
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION
                                    + "|admission|nested-sibling-history-root",
                            trace(DocumentRevision.Kind.TIMELINE_ENTRY,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    attachment),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    a2Entry),
                            trace(
                                    DocumentRevision.Kind
                                            .EMBEDDED_REVISION_APPLICATION,
                                    a11Entry)),
                    rootTrace,
                    "every initialization application must precede the "
                            + "globally ordered A2 then A11 history");

            assertEquals(2L, integer(
                    engine, "nested-sibling-a2", "/counter"));
            assertEquals(11L, integer(
                    engine, "nested-sibling-a11", "/directCount"));
            assertEquals(2L, integer(
                    engine, "nested-sibling-a1", "/childApplications"));
            assertEquals(11L, integer(
                    engine,
                    "nested-sibling-history-root",
                    "/children/a1/child/directCount"));
            assertEquals(2L, integer(
                    engine,
                    "nested-sibling-history-root",
                    "/children/a2/counter"));
        }
    }

    @Test
    void unavailableHistoryDefersTheEntryFrameAndResumesWithoutOvertaking()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            ExactValue a1 = engine.registerType(middleSource(leafSource()));
            ExactValue a2 = engine.registerType(a2Source());
            Timeline a11Timeline = engine.timeline(
                    "examples/nested-sibling/a11", "a11-owner");
            Timeline a2Timeline = engine.timeline(
                    "examples/nested-sibling/a2", "a2-owner");
            Timeline rootTimeline = engine.timeline(
                    "examples/nested-sibling/root", "root-owner");
            engine.appendAt(a11Timeline, Operation.yaml(
                    "advance", "sharedChannel", "amount: 11"), T0 + 200L);
            engine.appendAt(a2Timeline, Operation.yaml(
                    "increment", "ownerChannel", "amount: 2"), T0 + 100L);
            engine.start(
                    "nested-sibling-history-root",
                    resource("examples/clean/nested-sibling-history-root.yaml"));
            TimelineEntry attachment = engine.appendAt(
                    rootTimeline,
                    Operation.exact(
                            "attachBoth",
                            "ownerChannel",
                            attachmentRequest(a1, a2)),
                    T0 + 1_000L);

            engine.makeHistoricalUnavailable("provider temporarily offline");
            ProcessingDrainReceipt deferred = engine.dispatch(attachment);

            assertFalse(deferred.processedEntries().contains(attachment));
            assertEquals(2, deferred.processedEntries().size(),
                    "earlier source entries remain globally drainable even "
                            + "before their managed documents exist");
            assertFalse(deferred.quiescent());
            assertTrue(deferred.blocked());
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("nested-sibling-history-root").status());
            assertEquals(1, engine.history("nested-sibling-a2").size());
            assertEquals(1, engine.history("nested-sibling-a11").size());

            engine.makeHistoricalAvailable();
            ProcessingDrainReceipt resumed = engine.dispatch(attachment);

            assertEquals(List.of(attachment), resumed.processedEntries());
            assertTrue(resumed.quiescent());
            assertEquals(2L, integer(
                    engine, "nested-sibling-a2", "/counter"));
            assertEquals(11L, integer(
                    engine, "nested-sibling-a11", "/directCount"));
            assertEquals(SessionStatus.READY,
                    engine.session("nested-sibling-history-root").status());
        }
    }

    private static ExactValue attachmentRequest(
            ExactValue a1,
            ExactValue a2) {
        Map<String, Node> fields = new LinkedHashMap<>();
        fields.put("a1", a1.referenceNode());
        fields.put("a2", a2.referenceNode());
        return ExactValue.verified(new Node().properties(fields));
    }

    private static String leafSource() throws Exception {
        return resource("examples/clean/deep-same-entry-a11.yaml")
                .replace("deep-same-entry-a11", "nested-sibling-a11")
                .replace("examples/deep-same-entry/shared",
                        "examples/nested-sibling/a11")
                .replace("shared-actor", "a11-owner");
    }

    private static String middleSource(String leaf) throws Exception {
        String middle = resource("examples/clean/deep-same-entry-a1.yaml")
                .replace("deep-same-entry-a1", "nested-sibling-a1")
                .replace("examples/deep-same-entry/shared",
                        "examples/nested-sibling/a1-unused")
                .replace("shared-actor", "a1-unused");
        return middle.stripTrailing() + "\nchild:\n"
                + indent(leaf.strip(), 2) + "\n";
    }

    private static String a2Source() throws Exception {
        return resource("examples/clean/embedded-counter-B.yaml")
                .replace("embedded-counter-B", "nested-sibling-a2")
                .replace("examples/embedded/B",
                        "examples/nested-sibling/a2")
                .replace("beatrice", "a2-owner");
    }

    private static List<String> trace(List<DocumentRevision> revisions) {
        return revisions.stream()
                .map(revision -> revision.kind() + "|"
                        + revision.causalEntryBlueId().orElseThrow())
                .toList();
    }

    private static String trace(
            DocumentRevision.Kind kind,
            TimelineEntry cause) {
        return kind + "|" + cause.blueId();
    }
}
