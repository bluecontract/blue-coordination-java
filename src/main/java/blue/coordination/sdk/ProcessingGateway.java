package blue.coordination.sdk;

import java.util.Objects;

/** Explicit canonical processing boundary for submitted work. */
public final class ProcessingGateway {
    private final SdkCoordinationRuntime runtime;

    ProcessingGateway(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Drains all currently eligible work to a safe frontier. */
    public DrainResult drain() {
        return runtime.drain();
    }

    /** Drains eligible work without exceeding the supplied deterministic limits. */
    public DrainResult drain(DrainBudget budget) {
        return runtime.drain(Objects.requireNonNull(budget, "budget"));
    }
}
