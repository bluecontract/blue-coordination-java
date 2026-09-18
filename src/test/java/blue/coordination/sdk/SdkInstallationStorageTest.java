package blue.coordination.sdk;

import blue.coordination.api.ContractsExecutionPolicy;
import blue.coordination.api.storage.CoordinationObjectStorageException;
import blue.coordination.internal.BundledContracts10Release;
import blue.coordination.internal.DefaultCoordinationEngine;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exact owner/configuration constructor seam; complete physical engine qualification is separate. */
final class SdkInstallationStorageTest {
    @Test void restoresTheSuppliedEngineAndNewOwnerWithoutReplayingRegistrations() {
        // given
        var owner = new Object(); var calls = new AtomicInteger();
        var original = runtime(new Object());
        var configuration = original.storageConfiguration();
        // when
        original.close();
        try (var restored = SdkCoordinationRuntime.restore(owner, configuration, ExactNodeProvider.empty(), provider -> {
            calls.incrementAndGet();
            return DefaultCoordinationEngine.createContracts10Sdk(configuration.language(), configuration.contracts(), provider, configuration.policy());
        })) {
            // then
            assertEquals(1, calls.get()); assertEquals(configuration, restored.storageConfiguration());
            var maps = new SdkCoordinationRuntime.StoredMaps(new LinkedHashMap<>(), new LinkedHashMap<>(),
                    new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
            restored.installPointMaps(maps);
            assertSame(maps.timelines(), restored.storedMaps().timelines());
            assertSame(maps.entries(), restored.storedMaps().entries());
            assertSame(maps.sourceResults(), restored.storedMaps().sourceResults());
        }
    }

    @Test void aDifferentActualCorePolicyCannotBeRelabeledByStoredSdkConfiguration() {
        // given
        try (var original = runtime(new Object())) {
            // when
            var configuration = original.storageConfiguration();
            // then
            assertThrows(CoordinationObjectStorageException.class, () -> SdkCoordinationRuntime.restore(new Object(), configuration,
                    ExactNodeProvider.empty(), provider -> DefaultCoordinationEngine.createContracts10Sdk(configuration.language(),
                            configuration.contracts(), provider, ContractsExecutionPolicy.exactSharedGas(9_000, "not-the-stored-policy"))));
        }
    }

    private static SdkCoordinationRuntime runtime(Object owner) {
        var bundled = BundledContracts10Release.manifest();
        return SdkCoordinationRuntime.create(owner, bundled.blueLanguageSpecification(), bundled.contractsSpecification(),
                ExactNodeProvider.empty(), true, ContractsExecutionPolicy.releaseDefault());
    }
}
