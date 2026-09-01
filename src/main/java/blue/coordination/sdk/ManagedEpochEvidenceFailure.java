package blue.coordination.sdk;

import java.util.Objects;

/** Typed SDK evidence for one immutable managed source-epoch failure. */
public record ManagedEpochEvidenceFailure(
        ManagedEpochApplicationWork work,
        Status status,
        String code,
        String diagnostic) {

    /** Validates the independent host-persistable SDK projection. */
    public ManagedEpochEvidenceFailure {
        work = Objects.requireNonNull(work, "work");
        status = Objects.requireNonNull(status, "status");
        code = requireCode(code);
        diagnostic = SdkPreconditions.requireText(diagnostic, "diagnostic");
    }

    /** Closed fail-closed outcome before Contracts processing begins. */
    public enum Status {
        /** The exact next immutable source receipt is unavailable. */
        WAITING_FOR_HISTORY,
        /** Available immutable evidence is incomplete or inconsistent. */
        BLOCKED
    }

    private static String requireCode(String value) {
        String selected = Objects.requireNonNull(value, "code");
        if (!selected.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Managed epoch evidence code must use upper snake case");
        }
        return selected;
    }
}
