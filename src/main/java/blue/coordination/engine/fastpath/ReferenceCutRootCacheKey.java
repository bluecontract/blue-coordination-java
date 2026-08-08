package blue.coordination.engine.fastpath;

import java.util.List;
import java.util.Objects;

/** Semantic key: no session identity, so immutable fixture forks share it. */
public final class ReferenceCutRootCacheKey {
    private final String rootBlueId;
    private final String inventoryIdentity;
    private final List<String> activePaths;
    private final String environmentIdentity;
    private final String gasScheduleIdentity;
    private final String subscriptionDigest;
    private final String runtimeIdentity;
    private final String providerStorageGenerationAuthority;
    private final String algorithmVersion;

    public ReferenceCutRootCacheKey(
            String rootBlueId,
            String inventoryIdentity,
            List<String> activePaths,
            String environmentIdentity,
            String gasScheduleIdentity,
            String subscriptionDigest,
            String runtimeIdentity,
            String providerStorageGenerationAuthority,
            String algorithmVersion) {
        this.rootBlueId = Objects.requireNonNull(rootBlueId, "rootBlueId");
        this.inventoryIdentity = Objects.requireNonNull(
                inventoryIdentity, "inventoryIdentity");
        this.activePaths = ActivePathSet.canonicalPaths(
                Objects.requireNonNull(activePaths, "activePaths"));
        this.environmentIdentity = Objects.requireNonNull(
                environmentIdentity, "environmentIdentity");
        this.gasScheduleIdentity = Objects.requireNonNull(
                gasScheduleIdentity, "gasScheduleIdentity");
        this.subscriptionDigest = requireText(
                subscriptionDigest, "subscriptionDigest");
        this.runtimeIdentity = requireText(
                runtimeIdentity, "runtimeIdentity");
        this.providerStorageGenerationAuthority = requireText(
                providerStorageGenerationAuthority,
                "providerStorageGenerationAuthority");
        this.algorithmVersion = requireText(
                algorithmVersion, "algorithmVersion");
    }

    public String rootBlueId() { return rootBlueId; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public List<String> activePaths() { return activePaths; }
    public String environmentIdentity() { return environmentIdentity; }
    public String gasScheduleIdentity() { return gasScheduleIdentity; }
    public String subscriptionDigest() { return subscriptionDigest; }
    public String runtimeIdentity() { return runtimeIdentity; }
    public String providerStorageGenerationAuthority() {
        return providerStorageGenerationAuthority;
    }
    public String algorithmVersion() { return algorithmVersion; }

    /** Conservative retained heap estimate, including every key string. */
    public long approximateRetainedWeightBytes() {
        long weight = 96L;
        weight = addWeight(weight, stringWeight(rootBlueId));
        weight = addWeight(weight, stringWeight(inventoryIdentity));
        weight = addWeight(weight, listWeight(activePaths.size()));
        for (String path : activePaths) {
            weight = addWeight(weight, stringWeight(path));
        }
        weight = addWeight(weight, stringWeight(environmentIdentity));
        weight = addWeight(weight, stringWeight(gasScheduleIdentity));
        weight = addWeight(weight, stringWeight(subscriptionDigest));
        weight = addWeight(weight, stringWeight(runtimeIdentity));
        weight = addWeight(
                weight,
                stringWeight(providerStorageGenerationAuthority));
        return addWeight(weight, stringWeight(algorithmVersion));
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ReferenceCutRootCacheKey)) return false;
        ReferenceCutRootCacheKey other = (ReferenceCutRootCacheKey) value;
        return rootBlueId.equals(other.rootBlueId)
                && inventoryIdentity.equals(other.inventoryIdentity)
                && activePaths.equals(other.activePaths)
                && environmentIdentity.equals(other.environmentIdentity)
                && gasScheduleIdentity.equals(other.gasScheduleIdentity)
                && subscriptionDigest.equals(other.subscriptionDigest)
                && runtimeIdentity.equals(other.runtimeIdentity)
                && providerStorageGenerationAuthority.equals(
                        other.providerStorageGenerationAuthority)
                && algorithmVersion.equals(other.algorithmVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                rootBlueId,
                inventoryIdentity,
                activePaths,
                environmentIdentity,
                gasScheduleIdentity,
                subscriptionDigest,
                runtimeIdentity,
                providerStorageGenerationAuthority,
                algorithmVersion);
    }

    @Override
    public String toString() {
        return "ReferenceCutRootCacheKey{rootBlueId='" + rootBlueId
                + "', inventoryIdentity='" + inventoryIdentity
                + "', activePaths=" + activePaths
                + ", environmentIdentity='" + environmentIdentity
                + "', gasScheduleIdentity='" + gasScheduleIdentity
                + "', subscriptionDigest='" + subscriptionDigest
                + "', runtimeIdentity='" + runtimeIdentity
                + "', providerStorageGenerationAuthority='"
                + providerStorageGenerationAuthority
                + "', algorithmVersion='" + algorithmVersion + "'}";
    }

    private static String requireText(String value, String label) {
        String checked = Objects.requireNonNull(value, label);
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        return checked;
    }

    static long stringWeight(String value) {
        String checked = Objects.requireNonNull(value, "value");
        return alignEight(addWeight(40L, checked.length() * 2L));
    }

    static long listWeight(int size) {
        return alignEight(addWeight(40L, size * 8L));
    }

    static long addWeight(long left, long right) {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long alignEight(long value) {
        if (value > Long.MAX_VALUE - 7L) return Long.MAX_VALUE;
        return (value + 7L) & ~7L;
    }
}
