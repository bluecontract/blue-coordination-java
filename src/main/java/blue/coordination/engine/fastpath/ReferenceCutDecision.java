package blue.coordination.engine.fastpath;

import java.util.Objects;

/** Final fail-closed decision for one compiled sparse Root. */
public final class ReferenceCutDecision {
    private final boolean useSparseRoot;
    private final String reason;
    private final ReferenceCutRootArtifact artifact;

    public ReferenceCutDecision(
            boolean useSparseRoot,
            String reason,
            ReferenceCutRootArtifact artifact) {
        this.useSparseRoot = useSparseRoot;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
    }

    public boolean useSparseRoot() { return useSparseRoot; }
    public String reason() { return reason; }
    public ReferenceCutRootArtifact artifact() { return artifact; }

    public static ReferenceCutDecision evaluate(
            ReferenceCutConfiguration configuration,
            ReferenceCutRootArtifact artifact) {
        ReferenceCutConfiguration checked = Objects.requireNonNull(
                configuration, "configuration");
        ReferenceCutRootArtifact compiled = Objects.requireNonNull(
                artifact, "artifact");
        if (!checked.enabled()) {
            return new ReferenceCutDecision(false, "disabled", compiled);
        }
        if (compiled.cuts().isEmpty()) {
            return new ReferenceCutDecision(false, "no-safe-cuts", compiled);
        }
        if (compiled.cuts().size() > checked.maximumCuts()) {
            return new ReferenceCutDecision(
                    false, "cut-count-exceeds-policy", compiled);
        }
        if (compiled.verifiedReductionFraction()
                < checked.minimumNodeReduction()) {
            return new ReferenceCutDecision(
                    false, "insufficient-node-reduction", compiled);
        }
        return new ReferenceCutDecision(true, "verified", compiled);
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ReferenceCutDecision)) return false;
        ReferenceCutDecision other = (ReferenceCutDecision) value;
        return useSparseRoot == other.useSparseRoot
                && reason.equals(other.reason)
                && artifact.equals(other.artifact);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Boolean.valueOf(useSparseRoot), reason, artifact);
    }

    @Override
    public String toString() {
        return "ReferenceCutDecision{useSparseRoot=" + useSparseRoot
                + ", reason='" + reason + "'}";
    }
}
