package blue.coordination.sdk;

import blue.coordination.internal.CoordinationTestControl;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real selection/execution with an independently armed optional-readiness fault. */
final class RootedCompletedStageBoundaryTest {
    @Test void incompleteStageFailureDoesNotReturnCompletedEvidence() throws Exception {
        try (var fixture = new RootedSdkFixture()) {
            var document = fixture.start("source.yaml", "rcp2/source", Map.of());
            fixture.append(document, "rcp2/source", "setCounter", 10, "counterValue: 11");
            var before = fixture.history(document);
            var engine = (DefaultCoordinationEngine) fixture.blue.advanced().rawEngine();
            fixture.control.failPublicationAt("BEFORE_SWAP");
            var failure = assertThrows(RuntimeException.class,
                    () -> engine.processNextRootStage(document.id(), null));
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

            // when: this selects and executes the real pending protocol stage.
            var completed = engine.processNextRootStage(document.id(), null);

            // then: exact current work is available despite the still-armed fault.
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

            // then: this is the old negative control, not a durable publication.
            assertTrue(control.isInjectedFailure(fault));
            assertEquals(9, document.snapshot().longAt("/counter"));
        }
    }
}
