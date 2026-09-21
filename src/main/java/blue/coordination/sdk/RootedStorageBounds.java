package blue.coordination.sdk;

import blue.coordination.internal.InsertionOrderedStorage;
import blue.coordination.internal.RootedEngineStorage;
import java.util.Objects;

/**
 * Public host configuration for bounded rooted storage, without implementation types.
 * All values are physical capacities, not semantic gas or execution policy.
 * Existing storage constructors and their validation remain available unchanged.
 *
 * @param engine engine storage capacities
 * @param sdk SDK storage capacities
 */
public record RootedStorageBounds(Engine engine, Sdk sdk) {
    /** Requires both validated physical capacity families. */
    public RootedStorageBounds {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(sdk, "sdk");
    }

    /** Converts to the existing rooted-storage configuration without changing any bound. */
    public RootedCoordinationStorage.Limits toLimits() {
        return new RootedCoordinationStorage.Limits(engine.convert(), sdk.convert());
    }

    /** Explicit engine index, session, pending-work and log capacities. */
    public record Engine(int indexNodeBytes, int keyBytes, int valueBytes, int descriptorBytes,
            int cachedNodes, int maximumRecordBytes, int maximumDepth, long maximumScopeBytes,
            int maximumSelectedSessions, int maximumSelectedBuckets, int maximumPendingEntries,
            long maximumPendingBytes, int logChunkBytes, int logValueBytes, int cachedLogChunks) {
        /** Applies the existing engine's complete physical-capacity validation. */
        public Engine {
            new RootedEngineStorage.Limits(indexNodeBytes, keyBytes, valueBytes, descriptorBytes,
                    cachedNodes, maximumRecordBytes, maximumDepth, maximumScopeBytes,
                    maximumSelectedSessions, maximumSelectedBuckets, maximumPendingEntries,
                    maximumPendingBytes, logChunkBytes, logValueBytes, cachedLogChunks);
        }

        private RootedEngineStorage.Limits convert() {
            return new RootedEngineStorage.Limits(indexNodeBytes, keyBytes, valueBytes, descriptorBytes,
                    cachedNodes, maximumRecordBytes, maximumDepth, maximumScopeBytes,
                    maximumSelectedSessions, maximumSelectedBuckets, maximumPendingEntries,
                    maximumPendingBytes, logChunkBytes, logValueBytes, cachedLogChunks);
        }
    }

    /** Explicit SDK insertion index and pinned-row capacities. */
    public record Index(int nodeBytes, int keyBytes, int indexValueBytes, int descriptorBytes,
            int cachedNodes, int recordBytes, long pinnedBytes, int pinnedEntries) {
        private InsertionOrderedStorage.Limits convert() {
            return new InsertionOrderedStorage.Limits(nodeBytes, keyBytes, indexValueBytes,
                    descriptorBytes, cachedNodes, recordBytes, pinnedBytes, pinnedEntries);
        }
    }

    /** Explicit SDK row, descriptor, memo and codec capacities. */
    public record Sdk(Index indexes, int maximumRowBytes, int maximumDescriptorBytes,
            long maximumMemoBytes, int maximumMemoEntries, int maximumCodecBytes) {
        /** Applies the existing SDK's complete physical-capacity validation. */
        public Sdk {
            Objects.requireNonNull(indexes, "indexes");
            new RootedCoordinationStorage.SdkLimits(indexes.convert(), maximumRowBytes,
                    maximumDescriptorBytes, maximumMemoBytes, maximumMemoEntries, maximumCodecBytes);
        }

        private RootedCoordinationStorage.SdkLimits convert() {
            return new RootedCoordinationStorage.SdkLimits(indexes.convert(), maximumRowBytes,
                    maximumDescriptorBytes, maximumMemoBytes, maximumMemoEntries, maximumCodecBytes);
        }
    }
}
