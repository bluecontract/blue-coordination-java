package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real selection/execution with an independently armed optional-readiness fault. */
final class RootedCompletedStageBoundaryTest {
    @Test void waitingConsumerAndSeparateSourceAdmissionAndLiveStageAvoidReadiness() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var parent = fixture.start("parent.yaml", "rcp2/parent", Map.of());
            var originalParent = parent.snapshot().blueId();
            var sourceBody = fixture.blue.values().yaml(RootedSdkFixture.resource("source.yaml"));
            fixture.exact.put(sourceBody.blueId(), sourceBody.json());
            fixture.timelines.put("rcp2/source", fixture.blue.timelines().register("rcp2/source", "alice"));
            fixture.appendReference(sourceBody.blueId(), "rcp2/source", "setCounter", 10, "counterValue: 19", false);
            var attachment = fixture.append(parent, "rcp2/parent", "attach", 20, "child:\n  blueId: " + sourceBody.blueId());
            var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);
            // when
            var waiting = fixture.blue.processing().processNextStage(parent);
            // then
            assertEquals(ProcessingStageResult.Disposition.WAITING, waiting.disposition());
            assertEquals(EntryDisposition.NEEDS_RESOURCES, waiting.entry(attachment).disposition());
            var admission = fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            assertEquals(blue.coordination.api.SourceHistoryPrerequisite.Kind.ADMISSION, admission.kind());
            assertTrue(fixture.blue.advanced().processSourceHistoryPrerequisite(admission).admission().orElseThrow().published());
            var live = fixture.blue.advanced().sourceHistoryPrerequisites(parent).get(0);
            var completed = fixture.blue.advanced().processSourceHistoryPrerequisite(live).processing().orElseThrow();
            assertEquals(1, completed.committedProcessTransitions());
            assertEquals(originalParent, parent.snapshot().blueId(), "Separate source work never resumes the waiting consumer");
            var source = fixture.blue.documents().require(live.sourceDocumentId());
            assertEquals(19, source.snapshot().longAt("/counter"));
            var fault = assertThrows(RuntimeException.class, () -> fixture.blue.processing().processNext(source));
            assertTrue(control.isInjectedFailure(fault), "Both source stages must leave optional readiness untouched");
        }
    }

    @Test void publicStageRetainsExactResultWithoutReportingFutureQuiescence() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            var entry = fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 13");
            var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);
            // when
            var result = fixture.blue.processing().processStage(document, entry);
            // then
            assertEquals(ProcessingStageResult.Disposition.COMPLETED, result.disposition());
            assertTrue(result.entry(entry).applied());
            assertEquals(result.entry(entry), fixture.blue.runtimeForStorage().storedMaps().results().get(entry.blueId()));
            assertEquals(13, document.snapshot().longAt("/counter"));
            assertEquals(ProcessingStageResult.Disposition.NO_WORK, fixture.blue.processing().processNextStage(document).disposition());
            var fault = assertThrows(RuntimeException.class, () -> fixture.blue.processing().processNext(document));
            assertTrue(control.isInjectedFailure(fault));
            assertFalse(java.util.Arrays.stream(ProcessingStageResult.class.getMethods()).anyMatch(m -> m.getName().equals("quiescent")));
        }
    }

    @Test void publicStageRejectsForeignAndClosedOwners() throws Exception {
        // given
        try (var owner = new RootedSdkFixture(); var foreign = new RootedSdkFixture()) {
            var document = owner.start("source.yaml", "rcp2/source", Map.of());
            var other = foreign.start("source.yaml", "rcp2/source", Map.of());
            var entry = foreign.append(other, "rcp2/source", "setCounter", 10, "counterValue: 17");
            // when
            assertThrows(IllegalArgumentException.class, () -> owner.blue.processing().processStage(document, entry));
            // then
            assertThrows(IllegalArgumentException.class, () -> owner.blue.processing().processNextStage(other));
            owner.blue.close();
            assertThrows(IllegalStateException.class, () -> owner.blue.processing().processNextStage(document));
        }
    }

    @Test void incompleteStageFailureDoesNotReturnCompletedEvidence() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 11");
            var before = fixture.history(document);
            var engine = (DefaultCoordinationEngine) fixture.blue.advanced().rawEngine();
            fixture.control.failPublicationAt("BEFORE_SWAP");
            // when
            var failure = assertThrows(RuntimeException.class,
                    () -> engine.processNextRootStage(document.id(), null));
            // then
            assertTrue(failure.getMessage().contains("Injected rooted publication failure"));
            assertEquals(0, document.snapshot().longAt("/counter"));
            assertEquals(before, fixture.history(document));
            // The failed mutable runtime is discarded when this fixture closes.
        }
    }

    @Test void selectedStageReturnsBeforeOptionalReadinessCanFail() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 5");
            var engine = (DefaultCoordinationEngine) fixture.blue.advanced().rawEngine();
            var control = CoordinationTestControl.attach(engine);
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);

            // when
            // this selects and executes the real pending protocol stage.
            var completed = engine.processNextRootStage(document.id(), null);

            // then
            // exact current work is available despite the still-armed fault.
            assertEquals(1, completed.committedProcessTransitions());
            assertEquals(5, document.snapshot().longAt("/counter"));
            var history = fixture.history(document);
            assertFalse(completed.contractsAttemptsByEntry().isEmpty());
            var fault = assertThrows(RuntimeException.class,
                    () -> fixture.blue.processing().processNext(document));
            assertTrue(control.isInjectedFailure(fault), "The optional-readiness fault must actually be reached");
            assertEquals(history, fixture.history(document));
            assertEquals(5, document.snapshot().longAt("/counter"));
        }
    }

    @Test void suppliedInputStageAlsoAvoidsOptionalReadiness() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            var entry = fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 7");
            var engine = (DefaultCoordinationEngine) fixture.blue.advanced().rawEngine();
            var exactInput = engine.auditTimelineEntry(entry.blueId()).orElseThrow();
            var control = CoordinationTestControl.attach(engine);
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);

            // when
            var completed = engine.processRootInputStage(document.id(), exactInput, null);

            // then
            assertEquals(1, completed.committedProcessTransitions());
            assertEquals(7, document.snapshot().longAt("/counter"));
            assertFalse(completed.contractsAttemptsFor(entry.blueId()).isEmpty());
            var fault = assertThrows(RuntimeException.class,
                    () -> fixture.blue.processing().processNext(document));
            assertTrue(control.isInjectedFailure(fault));
        }
    }

    @Test void legacyConvenienceCallStillPerformsLookaheadAfterExecuting() throws Exception {
        // given
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 9");
            var control = CoordinationTestControl.attach(fixture.blue.advanced().rawEngine());
            control.failOnceAt(CoordinationTestControl.FailurePoint.BEFORE_ROOTED_READINESS);

            // when
            var fault = assertThrows(RuntimeException.class,
                    () -> fixture.blue.processing().processNext(document));

            // then
            // this is the old negative control, not a durable publication.
            assertTrue(control.isInjectedFailure(fault));
            assertEquals(9, document.snapshot().longAt("/counter"));
        }
    }
}
