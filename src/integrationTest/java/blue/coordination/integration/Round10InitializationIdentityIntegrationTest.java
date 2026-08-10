package blue.coordination.integration;

import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.DocumentRevision;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.SessionStatus;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static blue.coordination.integration.EngineTestSupport.delta;
import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused Round 10 ordering and historical-state identity acceptance. */
final class Round10InitializationIdentityIntegrationTest {
    private static final long T0 = 1_730_000_000_000_000L;

    @Test
    void authoredManagedChildGetsItsOwnEpochZeroBeforeParentApplication()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            engine.start("initial-embedded-parent", resource(
                    "examples/clean/initial-embedded-parent.yaml"));

            List<DocumentRevision> parent = engine.history(
                    "initial-embedded-parent");
            List<DocumentRevision> child = engine.history(
                    "initial-embedded-child");
            assertEquals(List.of(
                            DocumentRevision.Kind.INITIALIZATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    parent.stream().map(DocumentRevision::kind).toList());
            assertEquals(List.of(DocumentRevision.Kind.INITIALIZATION),
                    child.stream().map(DocumentRevision::kind).toList());
            assertEquals(child.get(0).before().orElseThrow().blueId(),
                    parent.get(0).after().canonicalBlueIdAt("/child"));
            assertEquals(child.get(0).after().blueId(),
                    parent.get(1).after().canonicalBlueIdAt("/child"));
            assertEquals(SessionStatus.READY,
                    engine.session("initial-embedded-parent").status());
        }
    }

    @Test
    void unavailableInitialChildHistoryCannotPublishReadyState()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/initial-parent", "bob");
            engine.appendAt(
                    rootTimeline,
                    Operation.yaml(
                            "incrementRoot", "ownerChannel", "amount: 4"),
                    T0 + 50L);
            engine.makeHistoricalUnavailable("provider unavailable");
            engine.start(
                    "initial-embedded-parent",
                    resource("examples/clean/initial-embedded-parent.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("initial-embedded-parent").status());
            engine.restartFromStores();
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("initial-embedded-parent").status());
            engine.makeHistoricalAvailable();
            assertTrue(engine.drain().quiescent());
            assertEquals(SessionStatus.READY,
                    engine.session("initial-embedded-parent").status());
            assertEquals(4L, integer(
                    engine, "initial-embedded-parent", "/rootCounter"));
        }
    }

    @Test
    void initialChildAndRootHistoryMergeByGlobalSourceOrder()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/initial-parent", "bob");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/initial", "alice");
            TimelineEntry rootFirst = engine.appendAt(
                    rootTimeline,
                    Operation.yaml(
                            "incrementRoot", "ownerChannel", "amount: 4"),
                    T0 + 100L);
            TimelineEntry childSecond = engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 7"),
                    T0 + 200L);

            engine.start(
                    "initial-embedded-parent",
                    resource("examples/clean/initial-embedded-parent.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            List<DocumentRevision> history = engine.history(
                    "initial-embedded-parent");
            assertEquals(List.of(
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION),
                    history.subList(1, history.size()).stream()
                            .map(DocumentRevision::kind)
                            .toList());
            assertEquals(List.of(
                            "admission|initial-embedded-parent",
                            rootFirst.blueId(),
                            childSecond.blueId()),
                    history.subList(1, history.size()).stream()
                            .map(revision -> revision.causalEntryBlueId()
                                    .orElseThrow())
                            .toList());
            DocumentRevision rootRevision = history.get(2);
            assertEquals(0L, childCounter(
                    rootRevision.before().orElseThrow()),
                    "the earlier Root entry cannot observe future child history");
            assertEquals(7L, childCounter(history.get(3)));
        }
    }

    @Test
    void failedHistoricalStartRetainsCommittedAdmissionAndRestartResumes()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/initial-parent", "bob");
            engine.appendAt(
                    rootTimeline,
                    Operation.yaml(
                            "incrementRoot", "ownerChannel", "amount: 4"),
                    T0 + 100L);
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STATE_SWAP_BEFORE_RETURN);

            assertThrows(RuntimeException.class, () -> engine.start(
                    "initial-embedded-parent",
                    resource("examples/clean/initial-embedded-parent.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null));
            assertEquals(2, engine.documentCount(),
                    "the committed Root and initialized child must be retained");
            assertEquals(SessionStatus.CATCHING_UP,
                    engine.session("initial-embedded-parent").status());

            engine.clearFailureInjection();
            engine.restartFromStores();
            assertTrue(engine.drain().quiescent());
            assertEquals(SessionStatus.READY,
                    engine.session("initial-embedded-parent").status());
            assertEquals(4L, integer(
                    engine, "initial-embedded-parent", "/rootCounter"));
            assertEquals(1L, engine.history("initial-embedded-parent").stream()
                    .filter(revision -> revision.kind()
                            == DocumentRevision.Kind.TIMELINE_ENTRY)
                    .count(), "restart must reconcile, not rerun, Root PROCESS");
        }
    }

    @Test
    void failedHistoricalStartBeforeFirstCommitIsDeltaCleanAndRetryable()
            throws Exception {
        String authored = resource(
                "examples/clean/initial-embedded-parent.yaml");
        try (TestEngine engine = TestEngine.create()) {
            engine.failOnceAt(TestEngine.FailurePoint
                    .AFTER_STAGING_CHILD_SESSION);

            assertThrows(RuntimeException.class, () -> engine.start(
                    "initial-embedded-parent",
                    authored,
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null));
            assertEquals(0, engine.documentCount(),
                    "an admission with no committed transition must vanish");
            assertEquals(0, engine.routeRowCount());

            engine.clearFailureInjection();
            engine.start(
                    "initial-embedded-parent",
                    authored,
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);
            assertEquals(SessionStatus.READY,
                    engine.session("initial-embedded-parent").status());
            assertEquals(2, engine.documentCount());
        }
    }

    @Test
    void invalidTopLevelCompletenessEvidenceBlocksPendingAdmission()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/initial-parent", "bob");
            engine.appendAt(
                    rootTimeline,
                    Operation.yaml(
                            "incrementRoot", "ownerChannel", "amount: 4"),
                    T0 + 100L);
            engine.makeHistoricalUnavailable("provider unavailable");
            engine.start(
                    "initial-embedded-parent",
                    resource("examples/clean/initial-embedded-parent.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            engine.invalidateHistoricalEvidence("invalid provider cursor");
            assertThrows(RuntimeException.class, engine::drain);
            assertEquals(SessionStatus.BLOCKED,
                    engine.session("initial-embedded-parent").status());
            engine.restartFromStores();
            assertEquals(SessionStatus.BLOCKED,
                    engine.session("initial-embedded-parent").status());
            assertThrows(RuntimeException.class, engine::drain);
        }
    }

    @Test
    void initializationEpochPrecedesHistoricalProcessEvenWhenHistoryIsEarlier()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            var firstHistorical = engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 1"),
                    T0 + 100L);
            engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"),
                    T0 + 200L);

            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            var attachment = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)),
                    T0 + 1_000L);
            assertTrue(firstHistorical.sourceOrderKey().compareTo(
                    attachment.sourceOrderKey()) < 0,
                    "the child history must be strictly before attachment");

            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.dispatch(attachment);
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            List<DocumentRevision> applications = embeddedApplications(
                    engine, "embedded-state-parent");
            assertEquals(List.of(0L, 1L, 3L), applications.stream()
                    .map(Round10InitializationIdentityIntegrationTest
                            ::childCounter)
                    .toList(),
                    "initialization must cross the parent PROCESS boundary "
                            + "before any H<T child entry");
            assertEquals(
                    DocumentRevision.Kind.INITIALIZATION,
                    engine.history("embedded-counter-A").get(0).kind());
            assertEquals(2L, work.counter("childHistoricalProcessCalls"));
            assertEquals(3L, work.counter("childRevisionApplications"));
            assertEquals(3L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
        }
    }

    @Test
    void topLevelHistoryAdvancesNewChildBeforeTheRootsNextEntry()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline rootTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");

            TimelineEntry r1 = engine.appendAt(
                    rootTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(childInitial)),
                    T0 + 100L);
            TimelineEntry r2 = engine.appendAt(
                    rootTimeline,
                    Operation.yaml("detachChild", "ownerChannel", "{}"),
                    T0 + 300L);
            TimelineEntry c1 = engine.appendAt(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 7"),
                    T0 + 200L);

            assertTrue(r1.sourceOrderKey().compareTo(
                    c1.sourceOrderKey()) < 0);
            assertTrue(c1.sourceOrderKey().compareTo(
                    r2.sourceOrderKey()) < 0);

            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            List<DocumentRevision> rootHistory = engine.history(
                    "embedded-state-parent");
            assertEquals(List.of(
                            DocumentRevision.Kind.TIMELINE_ENTRY,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION,
                            DocumentRevision.Kind.TIMELINE_ENTRY),
                    rootHistory.subList(1, rootHistory.size()).stream()
                            .map(DocumentRevision::kind)
                            .toList());
            assertEquals(List.of(
                            r1.blueId(),
                            r1.blueId(),
                            c1.blueId(),
                            r2.blueId()),
                    rootHistory.subList(1, rootHistory.size()).stream()
                            .map(revision -> revision.causalEntryBlueId()
                                    .orElseThrow())
                            .toList());

            DocumentRevision r2Revision = rootHistory.get(
                    rootHistory.size() - 1);
            assertEquals(7L, childCounter(
                    r2Revision.before().orElseThrow()),
                    "R2 must PROCESS the exact Root state containing C1");
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());
            assertEquals(7L, integer(
                    engine, "embedded-counter-A", "/counter"));
            assertEquals(List.of(c1.blueId()), engine.history(
                    "embedded-counter-A").stream()
                    .flatMap(revision -> revision.sourceEntry().stream())
                    .map(TimelineEntry::blueId)
                    .toList(),
                    "the child must not join its introducing R1 frame");
        }
    }

    @Test
    void knownHistoricalStateAppliesOnlyEpochsAfterTheSuppliedState()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            incrementAt(engine, childTimeline, T0 + 100L, 1);
            ExactValue knownEpochOne = engine.history(
                    "embedded-counter-A").get(1).after();
            incrementAt(engine, childTimeline, T0 + 200L, 2);
            incrementAt(engine, childTimeline, T0 + 300L, 3);
            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();

            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();
            engine.dispatch(engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(knownEpochOne)),
                    T0 + 1_000L));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            List<DocumentRevision> parentHistory = engine.history(
                    "embedded-state-parent");
            assertEquals(1L, childCounter(parentHistory.get(1)),
                    "the attachment commit must retain the supplied epoch");
            assertEquals(List.of(3L, 6L), embeddedApplications(
                    engine, "embedded-state-parent").stream()
                    .map(Round10InitializationIdentityIntegrationTest
                            ::childCounter)
                    .toList(),
                    "only epochs two and three are missing from epoch one");
            assertEquals(2L, work.counter("childRevisionApplications"));
            assertEquals(0L, work.counter("childHistoricalProcessCalls"));
            assertEquals(childHistoryBefore,
                    engine.history("embedded-counter-A").size());
            assertEquals(6L, integer(
                    engine, "embedded-state-parent", "/child/counter"));
        }
    }

    @Test
    void unknownDivergentStateRejectsBeforeParentOrTopologyCommit()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childInitial = resource(
                    "examples/clean/embedded-counter.yaml");
            Timeline childTimeline = engine.timeline(
                    "examples/embedded/A", "alice");
            engine.start("embedded-counter-A", childInitial);
            engine.appendAndDispatch(
                    childTimeline,
                    Operation.yaml(
                            "increment", "ownerChannel", "amount: 2"));

            engine.start(
                    "embedded-state-parent",
                    resource("examples/clean/embedded-state-parent.yaml"));
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/state-parent", "bob");
            int parentHistoryBefore = engine.history(
                    "embedded-state-parent").size();
            int childHistoryBefore = engine.history(
                    "embedded-counter-A").size();
            String parentStateBefore = engine.history(
                    "embedded-state-parent").get(parentHistoryBefore - 1)
                    .after().blueId();
            EngineMetrics.MetricsSnapshot before = engine.metricsSnapshot();

            String divergent = childInitial.replace(
                    "counter: 0", "counter: 99");
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> engine.appendAndDispatch(
                            parentTimeline,
                            Operation.exact(
                                    "attachChild",
                                    "ownerChannel",
                                    engine.embeddedDocumentRequest(divergent))));
            EngineTestSupport.MetricDelta work = delta(
                    before, engine.metricsSnapshot());

            assertTrue(failure.getMessage().contains(
                    "Invalid admission evidence: unknown state"),
                    failure::getMessage);
            assertEquals(0L, engine.session(
                    "embedded-state-parent").epoch());
            assertEquals(SessionStatus.READY, engine.session(
                    "embedded-state-parent").status());
            assertEquals(parentHistoryBefore,
                    engine.history("embedded-state-parent").size());
            assertEquals(parentStateBefore, engine.history(
                    "embedded-state-parent").get(parentHistoryBefore - 1)
                    .after().blueId());
            assertTrue(engine.embeddedDocuments(
                    "embedded-state-parent").isEmpty());
            assertEquals(childHistoryBefore,
                    engine.history("embedded-counter-A").size());
            assertEquals(0L, work.counter("deliveryReceiptsCommitted"));
            assertEquals(0L, work.counter(
                    "temporal.graphGenerationsPublished"));
        }
    }

    private static void incrementAt(
            TestEngine engine,
            Timeline timeline,
            long timestamp,
            int amount) {
        engine.dispatch(engine.appendAt(
                timeline,
                Operation.yaml(
                        "increment", "ownerChannel", "amount: " + amount),
                timestamp));
    }

    private static List<DocumentRevision> embeddedApplications(
            TestEngine engine,
            String parentDocumentId) {
        return engine.history(parentDocumentId).stream()
                .filter(revision -> revision.kind()
                        == DocumentRevision.Kind.EMBEDDED_REVISION_APPLICATION)
                .toList();
    }

    private static long childCounter(DocumentRevision revision) {
        return childCounter(revision.after());
    }

    private static long childCounter(ExactValue state) {
        FrozenNode value = state.canonicalAt("/child/counter");
        if (value == null || value.getValue() == null) {
            throw new AssertionError("Missing /child/counter in parent revision");
        }
        Object scalar = value.getValue();
        if (scalar instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (scalar instanceof Number number) {
            return number.longValue();
        }
        throw new AssertionError("Expected numeric child counter, got " + scalar);
    }
}
