package blue.coordination.api;

import java.util.List;
import java.util.Objects;

/** Tentative start from an authenticated declared basis; no semantic initialization is fabricated. */
public record DocumentInstanceStart(Status status, DocumentInstanceRef instance,
        DocumentInstancePosition basis, List<String> blockers) {
    /** PREPARED still requires the host's atomic conditional publication. */
    public enum Status { PREPARED, BLOCKED_BASIS }
    /** Requires the exact same semantic document and an explicit basis decision. */
    public DocumentInstanceStart {
        Objects.requireNonNull(status); Objects.requireNonNull(instance); Objects.requireNonNull(basis); blockers = List.copyOf(blockers);
        if (!instance.documentId().equals(basis.instance().documentId()) || (status == Status.PREPARED) != blockers.isEmpty())
            throw new IllegalArgumentException("Invalid starting instance/basis decision");
    }
}
