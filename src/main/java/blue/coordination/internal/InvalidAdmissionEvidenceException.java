package blue.coordination.internal;

/** Deterministic supplied-state evidence that cannot identify one child epoch. */
final class InvalidAdmissionEvidenceException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    InvalidAdmissionEvidenceException(String diagnostic) {
        super("Invalid admission evidence: " + diagnostic);
    }
}
