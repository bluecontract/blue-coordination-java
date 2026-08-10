package blue.coordination.integration;

import blue.coordination.api.DocumentDispatchOutcome;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.Operation;
import blue.coordination.api.ProcessingDrainReceipt;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.assertNoGenericSplitting;
import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact entry-frame ordering for Root -> A1 -> A11 direct delivery. */
final class DeepSameEntryOrderingIntegrationTest {

    @Test
    void oneEntryProcessesDeepestFirstAndSettlesEveryEpochBeforeItsParent()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String leafSource = resource(
                    "examples/clean/deep-same-entry-a11.yaml");
            String middleSource = resource(
                    "examples/clean/deep-same-entry-a1.yaml");
            String rootSource = resource(
                    "examples/clean/deep-same-entry-root.yaml");
            Timeline shared = engine.timeline(
                    "examples/deep-same-entry/shared", "shared-actor");
            Timeline middleSetup = engine.timeline(
                    "examples/deep-same-entry/a1-setup", "a1-owner");
            Timeline rootSetup = engine.timeline(
                    "examples/deep-same-entry/root-setup", "root-owner");

            engine.start("deep-same-entry-a11", leafSource);
            engine.start("deep-same-entry-a1", middleSource);
            engine.appendAndDispatch(middleSetup, Operation.exact(
                    "attachChild",
                    "setupChannel",
                    engine.embeddedDocumentRequest(leafSource)));
            engine.start("deep-same-entry-root", rootSource);
            engine.appendAndDispatch(rootSetup, Operation.exact(
                    "attachChild",
                    "setupChannel",
                    engine.embeddedDocumentRequest(middleSource)));

            long middleApplicationsBefore = integer(
                    engine, "deep-same-entry-a1", "/childApplications");
            long rootApplicationsBefore = integer(
                    engine, "deep-same-entry-root", "/childApplications");
            int leafHistoryBefore = engine.history(
                    "deep-same-entry-a11").size();
            int middleHistoryBefore = engine.history(
                    "deep-same-entry-a1").size();
            int rootHistoryBefore = engine.history(
                    "deep-same-entry-root").size();

            TimelineEntry entry = engine.append(shared, Operation.yaml(
                    "advance", "sharedChannel", "amount: 1"));
            assertEquals(3, engine.routeTargetCount(entry));
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            ProcessingDrainReceipt receipt = engine.dispatch(entry);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertEquals(List.of(entry), receipt.processedEntries());
            assertEquals(List.of(
                            "deep-same-entry-a11|TIMELINE_ENTRY",
                            "deep-same-entry-a1|EMBEDDED_REVISION_APPLICATION",
                            "deep-same-entry-a1|TIMELINE_ENTRY",
                            "deep-same-entry-root|EMBEDDED_REVISION_APPLICATION",
                            "deep-same-entry-root|EMBEDDED_REVISION_APPLICATION",
                            "deep-same-entry-root|TIMELINE_ENTRY"),
                    trace(receipt.outcomesFor(entry.blueId())));

            List<DocumentRevision> leafRevisions = tail(
                    engine.history("deep-same-entry-a11"), leafHistoryBefore);
            List<DocumentRevision> middleRevisions = tail(
                    engine.history("deep-same-entry-a1"), middleHistoryBefore);
            List<DocumentRevision> rootRevisions = tail(
                    engine.history("deep-same-entry-root"), rootHistoryBefore);
            assertKinds(leafRevisions,
                    DocumentRevision.Kind.TIMELINE_ENTRY);
            assertKinds(middleRevisions,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.TIMELINE_ENTRY);
            assertKinds(rootRevisions,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                    DocumentRevision.Kind.TIMELINE_ENTRY);

            assertCausalSegment(entry, leafRevisions);
            assertCausalSegment(entry, middleRevisions);
            assertCausalSegment(entry, rootRevisions);
            assertApplicationOrder(middleRevisions);
            assertApplicationOrder(rootRevisions);

            assertEquals(1L, number(
                    middleRevisions.get(0), "/child/directCount"));
            assertEquals(0L, number(
                    middleRevisions.get(0), "/directCount"));
            assertEquals(1L, number(
                    middleRevisions.get(1), "/childDirectCountSeenByDirect"));
            assertEquals(1L, number(
                    middleRevisions.get(1), "/directCount"));

            assertEquals(0L, number(
                    rootRevisions.get(0), "/child/directCount"));
            assertEquals(1L, number(
                    rootRevisions.get(0), "/child/child/directCount"));
            assertEquals(1L, number(
                    rootRevisions.get(1), "/child/directCount"));
            assertEquals(1L, number(
                    rootRevisions.get(2), "/a1DirectCountSeenByDirect"));
            assertEquals(1L, number(
                    rootRevisions.get(2), "/a11DirectCountSeenByDirect"));
            assertEquals(middleApplicationsBefore + 1L, number(
                    rootRevisions.get(2),
                    "/a1ChildApplicationsSeenByDirect"));

            assertEquals(1L, integer(
                    engine, "deep-same-entry-a11", "/directCount"));
            assertEquals(1L, integer(
                    engine, "deep-same-entry-a1", "/directCount"));
            assertEquals(1L, integer(
                    engine, "deep-same-entry-root", "/directCount"));
            assertEquals(middleApplicationsBefore + 1L, integer(
                    engine, "deep-same-entry-a1", "/childApplications"));
            assertEquals(rootApplicationsBefore + 2L, integer(
                    engine, "deep-same-entry-root", "/childApplications"));

            assertEquals(3L, work.counter("temporal.externalProcessCalls"));
            assertEquals(3L, work.counter(
                    "process.embeddedEpochProcessCalls"));
            assertEquals(6L, work.counter("frozenProcessCalls"));
            assertEquals(0L, work.counter(
                    "temporal.externalProcessDeduplicated"));
            assertNoGenericSplitting(work);
        }
    }

    private static List<String> trace(
            List<DocumentDispatchOutcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.documentId().value() + "|"
                        + outcome.revision().kind())
                .toList();
    }

    private static List<DocumentRevision> tail(
            List<DocumentRevision> history,
            int previousSize) {
        return history.subList(previousSize, history.size());
    }

    private static void assertKinds(
            List<DocumentRevision> revisions,
            DocumentRevision.Kind... expected) {
        assertEquals(List.of(expected), revisions.stream()
                .map(DocumentRevision::kind)
                .toList());
    }

    private static void assertCausalSegment(
            TimelineEntry entry,
            List<DocumentRevision> revisions) {
        for (DocumentRevision revision : revisions) {
            assertEquals(entry.sourceOrderKey(),
                    revision.sourceOrderKey().orElseThrow());
            assertEquals(entry.blueId(),
                    revision.causalEntryBlueId().orElseThrow());
            if (revision.kind() == DocumentRevision.Kind.TIMELINE_ENTRY) {
                assertEquals(entry,
                        revision.sourceEntry().orElseThrow());
            } else {
                assertFalse(revision.sourceEntry().isPresent());
            }
        }
    }

    private static void assertApplicationOrder(
            List<DocumentRevision> revisions) {
        for (int index = 1; index < revisions.size(); index++) {
            assertTrue(revisions.get(index - 1).rootApplicationOrder()
                    < revisions.get(index).rootApplicationOrder());
        }
    }

    private static long number(DocumentRevision revision, String path) {
        FrozenNode selected = revision.after().canonicalAt(path);
        if (selected == null || selected.getValue() == null) {
            throw new AssertionError("Missing numeric value at " + path);
        }
        Object value = selected.getValue();
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected numeric value at " + path
                + " but got " + value);
    }
}
