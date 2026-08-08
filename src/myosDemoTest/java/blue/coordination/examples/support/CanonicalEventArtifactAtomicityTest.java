package blue.coordination.examples.support;

import blue.coordination.engine.api.CoordinationEventAdmissionCompiler;
import blue.coordination.engine.api.CoordinationVerifiedEventAdmission;
import blue.coordination.engine.memory.CoordinationEventAdmissionMetrics;
import blue.coordination.engine.memory.InMemoryCoordinationFragmentStore;
import blue.coordination.examples.documents.BasicsCounterDocuments;
import blue.coordination.processor.CoordinationDocumentSplitter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Atomic publication and evidence-domain acceptance tests for event append. */
final class CanonicalEventArtifactAtomicityTest {

    @Test
    void shouldRemainAtomicBeforeFragmentAdmission() {
        // given
        MyOsDemoRuntime.AppendFailureBoundary boundary =
                MyOsDemoRuntime.AppendFailureBoundary
                        .BEFORE_FRAGMENT_ADMISSION;

        // when
        assertAtomicFailureAt(
                boundary,
                "before-fragment-admission");

        // then
        // The shared oracle asserts that no partial publication escaped.
    }

    @Test
    void shouldRemainAtomicAfterPreparedFragmentAdmission() {
        // given
        MyOsDemoRuntime.AppendFailureBoundary boundary =
                MyOsDemoRuntime.AppendFailureBoundary
                        .AFTER_FRAGMENT_ADMISSION;

        // when
        assertAtomicFailureAt(
                boundary,
                "after-fragment-admission");

        // then
        // The shared oracle asserts that staged fragments remain invisible.
    }

    @Test
    void shouldRemainAtomicAfterJournalStagingBeforePublication() {
        assertAtomicFailureAt(
                MyOsDemoRuntime.AppendFailureBoundary
                        .AFTER_JOURNAL_STAGING,
                "after-journal-staging");
    }

    private static void assertAtomicFailureAt(
            MyOsDemoRuntime.AppendFailureBoundary boundary,
            String caseId) {
        // given
        MyOsDemoOperation operation = increment();
        MyOsDemoEntry retried;
        try (MyOsDemoRuntime demo = MyOsDemoRuntime.create(
                "canonical-event-atomicity", caseId)) {
            demo.addDocument("counter", BasicsCounterDocuments.COUNTER);
            MyOsDemoTimeline timeline = timeline(demo);
            String stateBefore = demo.stateFingerprint();
            int fragmentsBefore = demo.physicalFragmentCount();
            long timestampBefore = demo.peekNextTimelineTimestampMicros();
            Set<MyOsTimelineBinding> routesBefore =
                    demo.timelinesForDocument("counter");
            MyOsTimelineCheckpoint timelineBefore = timeline.checkpoint();
            CoordinationEventAdmissionMetrics.Snapshot metricsBefore =
                    demo.eventAdmissionMetrics();
            demo.failNextAppendAtForTest(
                    boundary,
                    new InjectedAdmissionFailure());

            // when
            assertThrows(InjectedAdmissionFailure.class,
                    () -> demo.append(timeline, operation));

            // then
            assertEquals(0, demo.journalEntryCount());
            assertEquals(0, demo.storedEventInventoryCount());
            assertEquals(0, demo.canonicalStoredEventCount());
            assertEquals(0, demo.authoredEntries().size());
            assertEquals(fragmentsBefore, demo.physicalFragmentCount());
            assertEquals(timestampBefore,
                    demo.peekNextTimelineTimestampMicros());
            assertEquals(routesBefore,
                    demo.timelinesForDocument("counter"));
            assertEquals(timelineBefore, timeline.checkpoint());
            assertFalse(timeline.hasBinding());
            assertEquals(stateBefore, demo.stateFingerprint());
            long expectedSplits = boundary
                    == MyOsDemoRuntime.AppendFailureBoundary
                            .BEFORE_FRAGMENT_ADMISSION
                    ? metricsBefore.fullEventSplits()
                    : Math.addExact(metricsBefore.fullEventSplits(), 1L);
            assertEquals(expectedSplits,
                    demo.eventAdmissionMetrics().fullEventSplits(),
                    "failed staging may populate derived evidence but must "
                            + "report that work exactly");

            retried = demo.append(timeline, operation);
            assertEquals(timestampBefore, retried.timestampMicros());
            assertEquals(1, demo.journalEntryCount());
            assertEquals(1, demo.storedEventInventoryCount());
            assertEquals(1, demo.canonicalStoredEventCount());
        }

        try (MyOsDemoRuntime fresh = MyOsDemoRuntime.create(
                "canonical-event-atomicity", caseId + "-fresh-control")) {
            fresh.addDocument("counter", BasicsCounterDocuments.COUNTER);
            MyOsDemoEntry control = fresh.append(
                    timeline(fresh), operation);
            assertEquals(control.timestampMicros(),
                    retried.timestampMicros());
            assertEquals(control.blueId(), retried.blueId());
        }
    }

    @Test
    void shouldRejectAnArtifactFromAnotherEnvironmentOrProfileAtomically() {
        // given
        Node event = MyOsDemoKernel.runtime().parseSourceYaml(
                "type: acceptance-event\nmessage:\n  sequence: 1\n");
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        CoordinationVerifiedEventAdmission first = compiler("environment-a")
                .compile(eventBlueId, event);
        InMemoryCoordinationFragmentStore store =
                new InMemoryCoordinationFragmentStore(
                        CoordinationDocumentSplitter
                                .FRAGMENTATION_PROFILE_ID);
        store.admitVerifiedEvent(first);
        int fragmentsBefore = store.physicalFragmentCount();
        int inventoriesBefore = store.inventoryCount();
        CoordinationVerifiedEventAdmission foreign =
                compiler("environment-b").compile(eventBlueId, event);

        // when
        assertThrows(IllegalArgumentException.class,
                () -> store.admitVerifiedEvent(foreign));

        // then
        assertEquals(fragmentsBefore, store.physicalFragmentCount());
        assertEquals(inventoriesBefore, store.inventoryCount());
        assertEquals(first.inventory().toMap(),
                store.requireInventory(
                        first.inventory().inventoryIdentity()).toMap());

        InMemoryCoordinationFragmentStore wrongProfile =
                new InMemoryCoordinationFragmentStore(
                        "blue.coordination/fragmentation/foreign");
        assertThrows(IllegalArgumentException.class,
                () -> wrongProfile.admitVerifiedEvent(first));
        assertEquals(0, wrongProfile.physicalFragmentCount());
        assertEquals(0, wrongProfile.inventoryCount());
    }

    private static CoordinationEventAdmissionCompiler compiler(
            String environmentIdentity) {
        return new CoordinationEventAdmissionCompiler(
                environmentIdentity,
                "acceptance-language-generation",
                "acceptance-provider-generation",
                CoordinationDocumentSplitter.forEventSplitting(),
                4,
                64,
                new CoordinationEventAdmissionMetrics());
    }

    private static MyOsDemoTimeline timeline(MyOsDemoRuntime demo) {
        return demo.timeline(
                "acceptance/canonical-event-atomicity/alice",
                MyOsDemoActor.principal("alice"));
    }

    private static MyOsDemoOperation increment() {
        return MyOsDemoOperation.operation("increment")
                .through("ownerChannel")
                .request("amount: 1")
                .build();
    }

    private static final class InjectedAdmissionFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
