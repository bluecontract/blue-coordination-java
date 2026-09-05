package blue.coordination.internal;

import blue.language.model.Node;
import blue.language.processor.ExactEventIdentityEvidence;

/** Reconstructs real event identity evidence with the selected runtime provider. */
final class IntegrationEventEvidence {
    private IntegrationEventEvidence() {}

    static ExactEventIdentityEvidence verify(Node event, String blueId) {
        try (BlueRuntime providerRuntime = BlueRuntime.create(
                new WholeObjectStore(new EngineMetrics()));
             blue.language.BlueRuntime verifier = blue.language.BlueRuntime.builder()
                     .nodeProvider(providerRuntime.nodeProvider()).build()) {
            return ExactEventIdentityEvidence.verify(
                    verifier.contracts().runtimeAccess(), event, blueId, null);
        }
    }
}
