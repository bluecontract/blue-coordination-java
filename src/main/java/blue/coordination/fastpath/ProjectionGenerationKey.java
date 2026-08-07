package blue.coordination.fastpath;

import java.util.Objects;

/**
 * Collision-safe key for every derived value admitted for one exact Root
 * generation.  Equality includes the immutable content and projection
 * identities; the precomputed JVM hash is only a bucket accelerator.
 */
public final class ProjectionGenerationKey {
    private final String environmentIdentity;
    private final String sessionId;
    private final String rootBlueId;
    private final long rootRevision;
    private final String inventoryIdentity;
    private final String subscriptionDigest;
    private final String runtimeIdentity;
    private final int hashCode;

    public ProjectionGenerationKey(
            String environmentIdentity,
            String sessionId,
            String rootBlueId,
            long rootRevision,
            String inventoryIdentity,
            String subscriptionDigest,
            String runtimeIdentity) {
        this.environmentIdentity = text(environmentIdentity, "environmentIdentity");
        this.sessionId = text(sessionId, "sessionId");
        this.rootBlueId = text(rootBlueId, "rootBlueId");
        if (rootRevision < 0L) {
            throw new IllegalArgumentException("rootRevision must be non-negative");
        }
        this.rootRevision = rootRevision;
        this.inventoryIdentity = text(inventoryIdentity, "inventoryIdentity");
        this.subscriptionDigest = text(subscriptionDigest, "subscriptionDigest");
        this.runtimeIdentity = text(runtimeIdentity, "runtimeIdentity");
        this.hashCode = Objects.hash(
                this.environmentIdentity,
                this.sessionId,
                this.rootBlueId,
                this.rootRevision,
                this.inventoryIdentity,
                this.subscriptionDigest,
                this.runtimeIdentity);
    }

    public String environmentIdentity() { return environmentIdentity; }
    public String sessionId() { return sessionId; }
    public String rootBlueId() { return rootBlueId; }
    public long rootRevision() { return rootRevision; }
    public String inventoryIdentity() { return inventoryIdentity; }
    public String subscriptionDigest() { return subscriptionDigest; }
    public String runtimeIdentity() { return runtimeIdentity; }

    @Override
    public boolean equals(Object supplied) {
        if (this == supplied) return true;
        if (!(supplied instanceof ProjectionGenerationKey)) return false;
        ProjectionGenerationKey other = (ProjectionGenerationKey) supplied;
        return rootRevision == other.rootRevision
                && environmentIdentity.equals(other.environmentIdentity)
                && sessionId.equals(other.sessionId)
                && rootBlueId.equals(other.rootBlueId)
                && inventoryIdentity.equals(other.inventoryIdentity)
                && subscriptionDigest.equals(other.subscriptionDigest)
                && runtimeIdentity.equals(other.runtimeIdentity);
    }

    @Override
    public int hashCode() { return hashCode; }

    @Override
    public String toString() {
        return sessionId + "@" + rootRevision + ":" + rootBlueId;
    }

    private static String text(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
        return value;
    }
}
