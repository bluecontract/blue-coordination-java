package blue.language.processor.closure;

import blue.language.processor.ProcessorStatus;
import java.lang.reflect.*;
import java.util.*;

/** Standalone review-only diagnostic against the unchanged compiled actual-runtime fixture. */
public final class TerminationGasSweep {
    public static void main(String[] args) throws Exception {
        Class<?> fixtureType = Class.forName("blue.language.processor.closure.FreshTerminationObservationTest$Fixture");
        Class<?> modeType = Class.forName("blue.language.processor.closure.FreshTerminationObservationTest$Mode");
        Constructor<?> constructor = fixtureType.getDeclaredConstructor(modeType); constructor.setAccessible(true);
        Field inputField = fixtureType.getDeclaredField("input"); inputField.setAccessible(true);
        Field contractsField = fixtureType.getDeclaredField("contracts"); contractsField.setAccessible(true);
        int cases = 0, escapes = 0;
        for (Object mode : modeType.getEnumConstants()) {
            Object fixture = constructor.newInstance(mode);
            try {
                ClosureInvocationInput input = (ClosureInvocationInput) inputField.get(fixture);
                BlueClosureContracts contracts = (BlueClosureContracts) contractsField.get(fixture);
                SameOriginProcessAttempt baseline = contracts.processSameOrigin(input);
                if (!baseline.complete()) throw new AssertionError("Baseline needs evidence");
                int modeCases = 0, modeEscapes = 0;
                for (SameOriginOperationResult group : baseline.operations()) for (DocumentId owner : group.ownedDocumentIds()) {
                    TreeSet<Long> limits = new TreeSet<>(); long total = 0;
                    for (GasTraceEntry entry : group.gasTrace()) if (owner.equals(entry.documentId())) {
                        if (entry.subtotal() > 0) limits.add(total);
                        total += entry.subtotal();
                    }
                    limits.add(total);
                    for (long limit : limits) {
                        cases++; modeCases++;
                        ClosureInvocationInput capped = ClosureEvidenceFactory.processClosure(input.snapshot(), input.cause(), input.directDeliveries(),
                                ClosureEvidenceFactory.executionPolicy(100000L, Collections.singletonMap(owner, limit), "termination-policy"), input.environment());
                        try {
                            SameOriginProcessAttempt actual = contracts.processSameOrigin(capped);
                            if (!actual.complete()) throw new AssertionError("Unexpected missing evidence " + actual.resourceDemands());
                            for (SameOriginOperationResult result : actual.operations()) {
                                if (result.status() != ProcessorStatus.SUCCESS) for (ResultingDocument document : result.resultingDocuments()) {
                                    ManagedDocumentSnapshot before = input.snapshot().managedDocument(document.documentId());
                                    if (!before.blueId().equals(document.afterBlueId()) || before.epoch() != document.epoch()
                                            || before.terminated() != document.terminated()) throw new AssertionError("Failed group changed authority");
                                }
                            }
                        } catch (Throwable failure) {
                            escapes++; modeEscapes++;
                            System.out.println("ESCAPE mode=" + mode + " owner=" + owner + " cap=" + limit + " " + failure);
                            if (escapes <= 10) failure.printStackTrace(System.out);
                        }
                    }
                }
                System.out.println("MODE " + mode + " cases=" + modeCases + " escapes=" + modeEscapes);
            } finally { ((AutoCloseable) fixture).close(); }
        }
        System.out.println("TOTAL cases=" + cases + " escapes=" + escapes);
        if (escapes != 0) System.exit(1);
    }
}
