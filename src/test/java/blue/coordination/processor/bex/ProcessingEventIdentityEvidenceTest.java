package blue.coordination.processor.bex;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingEventIdentityEvidenceTest {

    @Test
    void shouldKeepEmptyEvidenceExplicitlyUnobserved() {
        // Given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();

        // When
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // Then
        assertFalse(snapshot.observed());
        assertNull(snapshot.admittedBlueId());
        assertEquals(0L, snapshot.workflowObservations());
        assertEquals(0L, snapshot.bexBindingObservations());
    }

    @Test
    void shouldProveSameIdentityAcrossWorkflowAndBexBoundaries() {
        // Given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode processingEvent =
                event("original");
        String admittedBlueId =
                processingEvent.blueId();

        // When
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

        // Then
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
        // Given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode processingEvent =
                event("original");

        // When
        evidence.observe(
                processingEvent,
                event("different").blueId(),
                ProcessingEventIdentityObserver.Boundary
                        .BEX_BINDING);
        ProcessingEventIdentityEvidence.Snapshot snapshot =
                evidence.snapshot();

        // Then
        assertTrue(snapshot.observed());
        assertFalse(snapshot.stable());
    }

    @Test
    void shouldRejectChangedProcessingEventAcrossWorkflowInvocations() {
        // Given
        ProcessingEventIdentityEvidence evidence =
                new ProcessingEventIdentityEvidence();
        FrozenNode original =
                event("original");
        FrozenNode changed =
                event("changed");

        // When
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

        // Then
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
