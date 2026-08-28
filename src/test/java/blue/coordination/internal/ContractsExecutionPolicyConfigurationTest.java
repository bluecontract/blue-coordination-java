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
        // given
        try (BlueCoordination coordination = BlueCoordination.inMemory()) {
            // when
            long selected = selectedPolicy(coordination).sharedLimit();

            // then
            assertEquals(RELEASE_DEFAULT_SHARED_GAS, selected);
        }
    }

    @Test
    void sdkBuilderHonorsExplicitExactSharedGasPolicy() {
        // given
        try (BlueCoordination defaultCoordination =
                     BlueCoordination.inMemory();
                BlueCoordination interactiveCoordination =
                     BlueCoordination.builder()
                             .contractsExecutionPolicy(
                                     ContractsExecutionPolicy.exactSharedGas(
                                             INTERACTIVE_LAB_SHARED_GAS,
                                             "myos-interactive-lab-5000"))
                             .build()) {
            // when
            ExecutionPolicy release = selectedPolicy(defaultCoordination);
            ExecutionPolicy interactive = selectedPolicy(
                    interactiveCoordination);

            // then
            assertEquals(RELEASE_DEFAULT_SHARED_GAS,
                    release.sharedLimit());
            assertEquals(INTERACTIVE_LAB_SHARED_GAS,
                    interactive.sharedLimit());
            assertNotEquals(release.identity(), interactive.identity());
        }
    }

    @Test
    void explicitPolicyRejectsNonPositiveGasAndBlankLabel() {
        // given
        long zero = 0L;
        long negative = -1L;
        String blank = "  ";

        // when
        IllegalArgumentException zeroFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        zero, "interactive-zero"));
        IllegalArgumentException negativeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        negative, "interactive-negative"));
        IllegalArgumentException blankFailure = assertThrows(
                IllegalArgumentException.class,
                () -> ContractsExecutionPolicy.exactSharedGas(
                        INTERACTIVE_LAB_SHARED_GAS, blank));

        // then
        assertEquals("sharedGasLimit must be positive",
                zeroFailure.getMessage());
        assertEquals("sharedGasLimit must be positive",
                negativeFailure.getMessage());
        assertEquals("label must not be blank", blankFailure.getMessage());
    }

    private static ExecutionPolicy selectedPolicy(
            BlueCoordination coordination) {
        DefaultCoordinationEngine engine = (DefaultCoordinationEngine)
                coordination.advanced().rawEngine();
        return engine.contractsClosureAdmissionAdapter().executionPolicy();
    }
}
