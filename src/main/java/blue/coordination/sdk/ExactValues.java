package blue.coordination.sdk;

import java.util.Objects;

/** Resolves and retains immutable whole exact Blue values. */
public final class ExactValues {
    private final SdkCoordinationRuntime runtime;

    ExactValues(SdkCoordinationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /** Resolves source YAML using the runtime's pinned Language release. */
    public ExactBlueValue yaml(String sourceYaml) {
        return runtime.exactValue(Objects.requireNonNull(
                sourceYaml, "sourceYaml"));
    }
}
