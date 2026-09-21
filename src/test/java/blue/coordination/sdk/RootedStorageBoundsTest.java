package blue.coordination.sdk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RootedStorageBoundsTest {
    @Test
    void publicConfigurationPreservesEveryExistingCapacity() {
        // given
        var existing = RootedCoordinationStorageTest.LIMITS;
        var e = existing.engine(); var s = existing.sdk(); var i = s.indexes();
        // when
        var actual = new RootedStorageBounds(new RootedStorageBounds.Engine(e.indexNodeBytes(), e.keyBytes(),
                e.valueBytes(), e.descriptorBytes(), e.cachedNodes(), e.maximumRecordBytes(), e.maximumDepth(),
                e.maximumScopeBytes(), e.maximumSelectedSessions(), e.maximumSelectedBuckets(), e.maximumPendingEntries(),
                e.maximumPendingBytes(), e.logChunkBytes(), e.logValueBytes(), e.cachedLogChunks()),
                new RootedStorageBounds.Sdk(new RootedStorageBounds.Index(i.nodeBytes(), i.keyBytes(), i.indexValueBytes(),
                        i.descriptorBytes(), i.cachedNodes(), i.recordBytes(), i.pinnedBytes(), i.pinnedEntries()),
                        s.maximumRowBytes(), s.maximumDescriptorBytes(), s.maximumMemoBytes(), s.maximumMemoEntries(),
                        s.maximumCodecBytes())).toLimits();
        // then
        assertEquals(existing, actual);
    }

    @Test
    void invalidEngineAndSdkCapacitiesStillFailBeforeOpeningStorage() {
        // given
        var invalidIndex = new RootedStorageBounds.Index(0, 1024, 1024, 4096, 0, 4096, 0, 0);
        // when
        var engineFailure = assertThrows(IllegalArgumentException.class, () -> new RootedStorageBounds.Engine(
                0, 1024, 1024, 4096, 0, 4096, 64, 4096, 1, 1, 0, 0, 4096, 1024, 0));
        var sdkFailure = assertThrows(IllegalArgumentException.class,
                () -> new RootedStorageBounds.Sdk(invalidIndex, 4096, 4096, 0, 0, 4096));
        // then
        assertNotNull(engineFailure.getMessage());
        assertEquals("Invalid SDK storage bounds", sdkFailure.getMessage());
    }
}
