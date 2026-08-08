package blue.coordination.engine.fastpath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReferenceCutConfigurationTest {
    @Test
    void keepsTheGeneralEngineDisabledUntilAHostOptsIn() {
        assertFalse(ReferenceCutConfiguration.disabled().enabled());
        assertTrue(ReferenceCutConfiguration.verifiedDefaults().enabled());
    }

    @Test
    void rejectsUnboundedOrNonsensicalPolicies() {
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCutConfiguration(
                        ReferenceCutMode.VERIFIED, 0L, 0.2d, 10));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCutConfiguration(
                        ReferenceCutMode.VERIFIED, 1L, 1.0d, 10));
        assertThrows(IllegalArgumentException.class, () ->
                new ReferenceCutConfiguration(
                        ReferenceCutMode.VERIFIED, 1L, 0.2d, 0));
    }
}
