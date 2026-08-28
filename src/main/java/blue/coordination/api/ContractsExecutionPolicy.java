package blue.coordination.api;

import java.util.Objects;

/**
 * Exact host-selected execution budget for the Contracts closure runtime.
 *
 * <p>The release default remains 100,000 shared gas. A lower explicit value is
 * intended for bounded interactive diagnostics and acceptance labs; selecting
 * it changes the closure execution-policy identity and therefore remains
 * visible in ordinary deterministic evidence.</p>
 *
 * @param sharedGasLimit positive shared gas available to one invocation
 * @param label non-blank deterministic policy label
 */
public record ContractsExecutionPolicy(
        long sharedGasLimit,
        String label) {
    public static final long RELEASE_DEFAULT_SHARED_GAS_LIMIT = 100_000L;
    public static final String RELEASE_DEFAULT_LABEL = "release-default";

    /** Validates an exact positive gas limit and deterministic policy label. */
    public ContractsExecutionPolicy {
        if (sharedGasLimit <= 0L) {
            throw new IllegalArgumentException(
                    "sharedGasLimit must be positive");
        }
        label = requireText(label, "label");
    }

    /** Returns the unchanged Contracts 1.0 release execution policy. */
    public static ContractsExecutionPolicy releaseDefault() {
        return new ContractsExecutionPolicy(
                RELEASE_DEFAULT_SHARED_GAS_LIMIT,
                RELEASE_DEFAULT_LABEL);
    }

    /** Creates an explicit exact shared-gas policy. */
    public static ContractsExecutionPolicy exactSharedGas(
            long sharedGasLimit,
            String label) {
        return new ContractsExecutionPolicy(sharedGasLimit, label);
    }

    private static String requireText(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
