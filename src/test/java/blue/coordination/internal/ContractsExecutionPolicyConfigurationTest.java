package blue.coordination.internal;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.sdk.BlueCoordination;
import blue.language.processor.closure.ExecutionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Focused pass-through coverage for the SDK Contracts execution policy. */
final class ContractsExecutionPolicyConfigurationTest {
    private static final long RELEASE_DEFAULT_SHARED_GAS = 100_000L;
    private static final long INTERACTIVE_LAB_SHARED_GAS = 5_000L;

    @Test
    void sdkDefaultRetainsReleaseSharedGasExactly() {
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            assertEquals(RELEASE_DEFAULT_SHARED_GAS,
                    selectedPolicy(coordination).sharedLimit());
        }
    }

    @Test
    void sdkBuilderHonorsExplicitExactSharedGasPolicy() {
        try (BlueCoordination defaultCoordination =
                     BlueCoordination.inMemory();
                BlueCoordination interactiveCoordination =
                     BlueCoordination.builder()
                             .contractsExecutionPolicy(
                                     ContractsExecutionPolicy.exactSharedGas(
                                             INTERACTIVE_LAB_SHARED_GAS,
                                             "myos-interactive-lab-5000"))
                             .build()) {
            ExecutionPolicy release = selectedPolicy(defaultCoordination);
            ExecutionPolicy interactive = selectedPolicy(
                    interactiveCoordination);

            assertEquals(RELEASE_DEFAULT_SHARED_GAS,
                    release.sharedLimit());
            assertEquals(INTERACTIVE_LAB_SHARED_GAS,
                    interactive.sharedLimit());
            assertNotEquals(release.identity(), interactive.identity());
        }
    }

    @Test
    void explicitPolicyRejectsNonPositiveGasAndBlankLabel() {
        assertEquals("sharedGasLimit must be positive", assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        0L, "interactive-zero")).getMessage());
        assertEquals("sharedGasLimit must be positive", assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        -1L, "interactive-negative")).getMessage());
        assertEquals("label must not be blank", assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        INTERACTIVE_LAB_SHARED_GAS, "  ")).getMessage());
    }

    private static ExecutionPolicy selectedPolicy(
            BlueCoordination coordination) {
        DefaultCoordinationEngine engine = (DefaultCoordinationEngine)
                coordination.advanced().rawEngine();
        return engine.contractsClosureAdmissionAdapter().executionPolicy();
    }
}
