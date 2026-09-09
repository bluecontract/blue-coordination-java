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

    /**
     * Reads a retained exact body, including a committed root-local child view.
     * This lookup never substitutes the current head of the body's managed lineage.
     * @param blueId exact content identity
     * @return retained immutable body, or empty when its exact content is unavailable
     */
    public java.util.Optional<ExactBlueValue> retained(String blueId) {
        return runtime.retainedExactValue(Objects.requireNonNull(blueId, "blueId"));
    }

    /**
     * Parses one whole YAML value for direct exact-provider storage.
     *
     * <p>The runtime preprocesses its pinned aliases and calculates the direct
     * BlueId of that result. It deliberately does not resolve the root's
     * declared type as though the supplied value were an instance. This makes
     * schema-bearing application type definitions safe to identify before
     * supplying {@link ExactBlueValue#json()} through an
     * {@link ExactNodeProvider}. The value is returned detached and is not
     * installed in the runtime's object store.</p>
     *
     * @param providerYaml complete provider content in YAML form
     * @return immutable preprocessed content with its direct exact BlueId
     */
    public ExactBlueValue providerContentYaml(String providerYaml) {
        return runtime.exactProviderValue(Objects.requireNonNull(
                providerYaml, "providerYaml"));
    }
}
