package blue.coordination.internal;

import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.WorkKind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact rejected-owner assertions independent of which metered loop charge exhausts the cap. */
final class ContractsGasFailureAssertions {
    private ContractsGasFailureAssertions() { }

    static void assertEnteredLoopAndRejectedExactOwner(ClosureProcessResult result,
            ClosureImplementationEvidence evidence, long sharedLimit) {
        assertTrue(result.gasTrace().stream().anyMatch(entry -> "handlerCall".equals(entry.counter())
                && "startLoop".equals(entry.contractKey())), "The loop-start workflow must actually execute");
        assertTrue(evidence.documentStepTrace().stream().anyMatch(step -> evidence.workTrace()
                .get(Math.toIntExact(step.workOrdinal())).kind() == WorkKind.EMBEDDED_EVENT),
                "A loop feedback step must complete; initial admission exhaustion is not this witness");
        RejectedCharge charge = result.rejectedCharge();
        assertNotNull(charge);
        assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED, charge.applicableCap().kind());
        assertEquals(sharedLimit - result.totalGas(), charge.remainingBeforeCharge());
        assertEquals(Math.multiplyExact(charge.quantity(), charge.weight()), charge.subtotal());
        assertTrue(charge.subtotal() > charge.remainingBeforeCharge());
        assertNull(result.platformCommitCompanion());
        assertTrue(result.graphChanges().isEmpty());
        assertTrue(result.subscriptionDeltas().isEmpty());
        assertTrue(result.checkpointWrites().isEmpty());
        assertTrue(result.publicEvents().isEmpty());
        RejectedCharge.Owner owner = charge.owner();
        switch (owner.kind()) {
            case WORK -> {
                assertNotNull(result.rejectedWorkOccurrence());
                assertEquals(owner.workOccurrenceIdentity(), result.rejectedWorkOccurrence().workIdentity());
                assertNull(owner.finalizationOrdinal());
                assertNull(owner.componentIdentity());
                assertNull(owner.componentGeneration());
            }
            case FINALIZATION -> {
                assertNull(result.rejectedWorkOccurrence());
                assertNull(owner.workOccurrenceIdentity());
                assertNotNull(owner.finalizationOrdinal());
                assertNotNull(owner.componentIdentity());
                assertNotNull(owner.componentGeneration());
            }
            case INVOCATION -> {
                assertNull(result.rejectedWorkOccurrence());
                assertNull(owner.workOccurrenceIdentity());
                assertNull(owner.finalizationOrdinal());
                assertNull(owner.componentIdentity());
                assertNull(owner.componentGeneration());
            }
        }
    }
}
