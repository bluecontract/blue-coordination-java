package blue.coordination.api;

import java.util.Objects;

/**
 * Typed fail-closed evidence from opening one immutable managed source epoch.
 *
 * <p>The failure occurs before Contracts processing. It therefore owns no
 * processor attempt, but retains the exact selected work and the plan status
 * published with the unchanged occurrence cursor.</p>
 */
public record ManagedEpochEvidenceFailure(
        ManagedEpochApplicationWork work,
        ManagedCatchUpStatus status,
        String code,
        String diagnostic) {

    /** Validates portable host-persistable immutable-evidence failure data. */
    public ManagedEpochEvidenceFailure {
        work = Objects.requireNonNull(work, "work");
        status = Objects.requireNonNull(status, "status");
        if (status != ManagedCatchUpStatus.WAITING_FOR_HISTORY
                && status != ManagedCatchUpStatus.BLOCKED) {
            throw new IllegalArgumentException(
                    "Managed epoch evidence must wait for history or block");
        }
        code = requireCode(code);
        diagnostic = requireText(diagnostic, "diagnostic");
    }

    private static String requireCode(String value) {
        String selected = Objects.requireNonNull(value, "code");
        if (!selected.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Managed epoch evidence code must use upper snake case");
        }
        return selected;
    }

    private static String requireText(String value, String label) {
        String selected = Objects.requireNonNull(value, label);
        if (selected.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return selected;
    }
}
