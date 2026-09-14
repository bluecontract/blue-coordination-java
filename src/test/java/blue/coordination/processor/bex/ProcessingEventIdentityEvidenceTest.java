package blue.coordination.processor.bex;

import blue.language.model.Node;
import blue.language.processor.BlueContracts;
import blue.language.processor.ExactEventIdentityEvidence;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingEventIdentityEvidenceTest {

    @Test
    void shouldKeepEmptyEvidenceExplicitlyUnobserved() {
        // given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();

        // when
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // then
        assertFalse(snapshot.observed());
        assertNull(snapshot.admittedBlueId());
        assertEquals(0L, snapshot.workflowObservations());
        assertEquals(0L, snapshot.bexBindingObservations());
    }

    @Test
    void shouldProveSameIdentityAcrossWorkflowAndBexBoundaries() {
        // given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode processingEvent =
                event("original");
        String admittedBlueId =
                processingEvent.blueId();

        // when
        evidence.observe(
                processingEvent,
                admittedBlueId,
                ProcessingEventIdentityObserver.Boundary
                        .WORKFLOW);
        evidence.observe(
                processingEvent,
                admittedBlueId,
                ProcessingEventIdentityObserver.Boundary
                        .BEX_BINDING);
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // then
        assertTrue(snapshot.observed());
        assertTrue(snapshot.stable());
        assertEquals(
                admittedBlueId,
                snapshot.admittedBlueId());
        assertEquals(1L, snapshot.workflowObservations());
        assertEquals(1L, snapshot.bexBindingObservations());
    }

    @Test
    void shouldRejectIdentityDifferentFromExposedBexBinding() {
        // given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode processingEvent =
                event("original");

        evidence.observe(processingEvent, processingEvent.blueId(),
                ProcessingEventIdentityObserver.Boundary.WORKFLOW);

        // when
        evidence.observe(
                processingEvent,
                event("different").blueId(),
                ProcessingEventIdentityObserver.Boundary
                        .BEX_BINDING);
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // then
        assertTrue(snapshot.observed());
        assertFalse(snapshot.stable());
    }

    @Test
    void shouldAcceptVerifiedIdentityDifferentFromTheCursorRepresentationHash() {
        // given
        ProcessingEventIdentityEvidence evidence = new ProcessingEventIdentityEvidence();
        Node original = new Node().type(new Node().name("Inline original event type"))
                .properties("values", new Node().items(java.util.Arrays.asList(
                        new Node().value("first"), new Node().value("second"))));
        try (BlueLanguage language = BlueLanguage.builder().build();
             BlueContracts contracts = BlueContracts.builder(language.processing()).build()) {
            String admittedBlueId = contracts.runtimeAccess().languageRuntime()
                    .calculateSourceDocumentBlueId(original.clone());
            ExactEventIdentityEvidence admitted = ExactEventIdentityEvidence.verify(
                    contracts.runtimeAccess(), original, admittedBlueId, null);
            FrozenNode cursor = admitted.frozenEvent();
            assertNotEquals(admittedBlueId, cursor.blueId());

            // when
            evidence.observe(cursor, admitted.eventBlueId(), ProcessingEventIdentityObserver.Boundary.WORKFLOW);
            evidence.observe(cursor, admitted.eventBlueId(), ProcessingEventIdentityObserver.Boundary.BEX_BINDING);

            // then
            assertTrue(evidence.snapshot().stable());
            assertEquals(admittedBlueId, evidence.snapshot().admittedBlueId());
        }
    }

    @Test
    void shouldRejectChangedContentEvenWhenTheExposedIdentityIsUnchanged() {
        // given
        ProcessingEventIdentityEvidence evidence = new ProcessingEventIdentityEvidence();
        FrozenNode original = event("original");

        // when
        evidence.observe(original, original.blueId(), ProcessingEventIdentityObserver.Boundary.WORKFLOW);
        evidence.observe(event("changed"), original.blueId(), ProcessingEventIdentityObserver.Boundary.BEX_BINDING);

        // then
        assertFalse(evidence.snapshot().stable());
    }

    @Test
    void shouldRejectChangedProcessingEventAcrossWorkflowInvocations() {
        // given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode original =
                event("original");
        FrozenNode changed =
                event("changed");

        // when
        evidence.observe(
                original,
                original.blueId(),
                ProcessingEventIdentityObserver.Boundary
                        .WORKFLOW);
        evidence.observe(
                changed,
                changed.blueId(),
                ProcessingEventIdentityObserver.Boundary
                        .WORKFLOW);
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // then
        assertTrue(snapshot.observed());
        assertFalse(snapshot.stable());
    }

    private static FrozenNode event(String marker) {
        return FrozenNode.fromResolvedNode(
                new Node().properties(
                        "marker",
                        new Node().value(marker)));
    }
}
