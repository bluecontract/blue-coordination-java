package blue.coordination.integration;

import blue.coordination.api.ActivationMode;
import blue.coordination.api.CoordinationEngine;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.ExactValue;
import blue.coordination.api.Operation;
import blue.coordination.api.Timeline;
import blue.coordination.api.TimelineEntry;
import blue.language.processor.ExternalOrderKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blue.coordination.integration.EngineTestSupport.integer;
import static blue.coordination.integration.EngineTestSupport.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Host-owned admission policies; Process Embedded remains paths-only. */
final class TemporalAdmissionPolicyIntegrationTest {
    private static final long T0 = 1_690_000_000_000_000L;

    @Test
    void rejectsFrontiersWithoutExactJournalEvidence() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            ExternalOrderKey forged = ExternalOrderKey.of(
                    List.of(T0 + 999L, "forged-frontier"));
            String counter = resource("examples/clean/counter.yaml");

            assertThrows(IllegalArgumentException.class, () -> engine.start(
                    "counter-forged",
                    document(counter, "counter-forged"),
                    CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                    forged));
            assertThrows(IllegalArgumentException.class,
                    () -> engine.configureEmbeddedAdmission(
                            "child-forged",
                            ActivationMode.IMPORT_FROM_FRONTIER,
                            forged));
        }
    }

    @Test
    void topLevelHistoryPoliciesUseExclusiveVerifiedFrontiers()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline alice = engine.timeline(
                    "examples/clean-counter/alice", "alice");
            var one = engine.appendAt(alice, counterIncrement(1), T0 + 100L);
            engine.appendAt(alice, counterIncrement(2), T0 + 200L);
            engine.appendAt(alice, counterIncrement(3), T0 + 300L);
            String counter = resource("examples/clean/counter.yaml");

            engine.start(
                    "counter-full",
                    document(counter, "counter-full"),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);
            engine.start(
                    "counter-frontier",
                    document(counter, "counter-frontier"),
                    CoordinationEngine.AdmissionPolicy.FROM_FRONTIER,
                    one.sourceOrderKey());
            engine.start(
                    "counter-now",
                    document(counter, "counter-now"),
                    CoordinationEngine.AdmissionPolicy.FROM_NOW,
                    null);

            assertEquals(6L, integer(engine, "counter-full", "/counter"));
            assertEquals(5L, integer(
                    engine, "counter-frontier", "/counter"));
            assertEquals(0L, integer(engine, "counter-now", "/counter"));
            assertEquals(3L, engine.session("counter-full").epoch());
            assertEquals(2L, engine.session("counter-frontier").epoch());
            assertEquals(0L, engine.session("counter-now").epoch());
        }
    }

    @Test
    void embeddedBirthFrontierFullAndPassivePoliciesAreHostMetadata()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline feed = engine.timeline("examples/embedded/A", "alice");
            var one = engine.appendAt(feed, increment(1), T0 + 100L);
            engine.appendAt(feed, increment(2), T0 + 200L);
            engine.appendAt(feed, increment(3), T0 + 300L);

            engine.configureEmbeddedAdmission(
                    "child-birth", ActivationMode.BIRTH_AT_ATTACHMENT, null);
            attachVariant(engine, "birth", "child-birth", 0L,
                    T0 + 1_000L);

            attachVariant(engine, "full", "child-full", 0L,
                    T0 + 1_100L);

            engine.configureEmbeddedAdmission(
                    "child-frontier",
                    ActivationMode.IMPORT_FROM_FRONTIER,
                    one.sourceOrderKey());
            attachVariant(engine, "frontier", "child-frontier", 1L,
                    T0 + 1_200L);

            engine.configureEmbeddedAdmission(
                    "child-passive", ActivationMode.PASSIVE_SNAPSHOT, null);
            attachVariant(engine, "passive", "child-passive", 0L,
                    T0 + 1_300L);

            assertEquals(0L, integer(
                    engine, "parent-birth", "/child/counter"));
            assertEquals(6L, integer(
                    engine, "parent-full", "/child/counter"));
            assertEquals(6L, integer(
                    engine, "parent-frontier", "/child/counter"));
            assertEquals(0L, integer(
                    engine, "parent-passive", "/child/counter"));
            assertEquals(1, engine.history("child-birth").size());
            assertEquals(4, engine.history("child-full").size());
            assertEquals(3, engine.history("child-frontier").size());
            assertFalse(engine.embeddedDocuments("parent-passive")
                    .containsKey("/child"));
            assertThrows(CoordinationException.class,
                    () -> engine.session("child-passive"));
        }
    }

    @Test
    void attachCurrentRequiresExistingCurrentStateAndCompletenessThroughT()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline feed = engine.timeline("examples/embedded/A", "alice");
            engine.appendAt(feed, increment(1), T0 + 100L);
            engine.appendAt(feed, increment(2), T0 + 200L);
            String child = child("child-current", 0L);
            engine.start(
                    "child-current",
                    child,
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY,
                    null);

            String parentId = "parent-current";
            Timeline parentTimeline = engine.timeline(
                    "examples/embedded/parent-current", "bob-current");
            engine.start(parentId, parent(
                    parentId,
                    "examples/embedded/parent-current",
                    "bob-current"));
            var attachment = engine.appendAt(
                    parentTimeline,
                    Operation.exact(
                            "attachChild",
                            "ownerChannel",
                            engine.embeddedDocumentRequest(
                                    engine.session("child-current").current())),
                    T0 + 1_000L);
            engine.configureEmbeddedAdmission(
                    "child-current",
                    ActivationMode.ATTACH_CURRENT_STATE,
                    attachment.sourceOrderKey());
            engine.dispatch(attachment);

            assertEquals(3L, integer(
                    engine, parentId, "/child/counter"));
            assertEquals(1L, engine.session(parentId).epoch());
            assertEquals(2L, engine.session("child-current").epoch());
        }
    }

    @Test
    void exactOccurrencePlansSelectIndependentEpochs()
            throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            Timeline feed = engine.timeline("examples/embedded/A", "alice");
            engine.appendAt(feed, increment(1), T0 + 100L);
            engine.appendAt(feed, increment(-1), T0 + 200L);
            engine.appendAt(feed, increment(1), T0 + 300L);
            String childId = "child-recurring";
            engine.start(childId, child(childId, 0L),
                    CoordinationEngine.AdmissionPolicy.FULL_HISTORY, null);
            ExactValue first = engine.history(childId).get(1).after();
            ExactValue latest = engine.history(childId).get(3).after();

            attachAtEpoch(engine, "first", childId, first, 1L,
                    T0 + 1_000L, true);
            attachAtEpoch(engine, "latest", childId, latest, 3L,
                    T0 + 1_100L, true);

            assertEquals(3L, engine.session("parent-first").epoch());
            assertEquals(1L, engine.session("parent-latest").epoch());
            assertEquals(1L, integer(engine, "parent-first", "/child/counter"));
            assertEquals(1L, integer(engine, "parent-latest", "/child/counter"));
        }
    }

    @Test
    void failedPublicationRetainsExactOccurrencePlan() throws Exception {
        try (TestEngine engine = TestEngine.create()) {
            String childId = "child-retry-plan";
            ExactValue child = engine.registerType(child(childId, 0L));
            TimelineEntry attachment = attachAtEpoch(
                    engine, "retry-plan", childId, child, null,
                    T0 + 2_000L, false);
            engine.failOnceAt(TestEngine.FailurePoint.AFTER_STAGING_CHILD_SESSION);
            assertThrows(TestEngine.InjectedFailureException.class,
                    () -> engine.dispatch(attachment));
            engine.clearFailureInjection();

            engine.dispatch(attachment);

            assertEquals(2L, engine.session("parent-retry-plan").epoch());
            assertEquals(0L, integer(
                    engine, "parent-retry-plan", "/child/counter"));
            assertEquals(1L, engine.metricsSnapshot().counters().getOrDefault(
                    "embedding.exactAdmissionPlansConsumed", 0L));
        }
    }

    private static TimelineEntry attachAtEpoch(
            TestEngine engine,
            String suffix,
            String childId,
            ExactValue childState,
            Long admittedEpoch,
            long timestamp,
            boolean dispatch) throws Exception {
        String parentId = "parent-" + suffix;
        Timeline timeline = engine.timeline(
                "examples/embedded/" + parentId, "bob-" + suffix);
        engine.start(parentId, parent(parentId,
                "examples/embedded/" + parentId, "bob-" + suffix));
        TimelineEntry attachment = engine.appendAt(timeline,
                Operation.exact("attachChild", "ownerChannel",
                        engine.embeddedDocumentRequest(childState)), timestamp);
        engine.configureEmbeddedAdmission(
                parentId, "/child", childId, childState.blueId(),
                admittedEpoch, ActivationMode.IMPORT_FULL_HISTORY, null,
                "test-proof|" + parentId, attachment.blueId());
        if (dispatch) {
            engine.dispatch(attachment);
        }
        return attachment;
    }

    private static void attachVariant(
            TestEngine engine,
            String suffix,
            String childId,
            long initialCounter,
            long timestamp) throws Exception {
        String parentId = "parent-" + suffix;
        String timelineId = "examples/embedded/parent-" + suffix;
        String actorId = "bob-" + suffix;
        Timeline parentTimeline = engine.timeline(timelineId, actorId);
        engine.start(parentId, parent(parentId, timelineId, actorId));
        var attachment = engine.appendAt(
                parentTimeline,
                Operation.exact(
                        "attachChild",
                        "ownerChannel",
                        engine.embeddedDocumentRequest(
                                child(childId, initialCounter))),
                timestamp);
        engine.dispatch(attachment);
    }

    private static Operation increment(long amount) {
        return Operation.yaml(
                "increment", "ownerChannel", "amount: " + amount);
    }

    private static Operation counterIncrement(long amount) {
        return Operation.yaml(
                "increment", "aliceChannel", "amount: " + amount);
    }

    private static String document(String yaml, String documentId) {
        return yaml.replace("documentId: counter",
                "documentId: " + documentId);
    }

    private static String child(String documentId, long counter)
            throws Exception {
        return resource("examples/clean/embedded-counter.yaml")
                .replace("documentId: embedded-counter-A",
                        "documentId: " + documentId)
                .replace("counter: 0", "counter: " + counter);
    }

    private static String parent(
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
