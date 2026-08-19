package blue.coordination.sdk;

import blue.coordination.api.DocumentId;

import java.util.Objects;
import java.util.OptionalLong;

/** Exact managed-child candidate bound to one Coordination instance. */
public final class ManagedDocumentDraft {
    private final Object owner;
    private final DocumentId id;
    private final ExactBlueValue initial;
    private final Long knownEpoch;

    ManagedDocumentDraft(
            Object owner,
            DocumentId id,
            ExactBlueValue initial) {
        this(owner, id, initial, null);
    }

    private ManagedDocumentDraft(
            Object owner,
            DocumentId id,
            ExactBlueValue initial,
            Long knownEpoch) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = Objects.requireNonNull(id, "id");
        this.initial = Objects.requireNonNull(initial, "initial");
        this.knownEpoch = knownEpoch;
        if (knownEpoch != null && knownEpoch < 0L) {
            throw new IllegalArgumentException(
                    "knownEpoch must be non-negative");
        }
    }

    /** Returns imported-state evidence pinned to the supplied known epoch. */
    public ManagedDocumentDraft atEpoch(long epoch) {
        return new ManagedDocumentDraft(owner, id, initial,
                SdkPreconditions.requireNonNegative(epoch, "epoch"));
    }

    /** Stable managed lineage identity. */
    public DocumentId id() {
        return id;
    }

    /** Exact initial or imported state. */
    public ExactBlueValue initial() {
        return initial;
    }

    /** Known imported epoch when the caller explicitly pinned one. */
    public OptionalLong knownEpoch() {
        return knownEpoch == null
                ? OptionalLong.empty()
                : OptionalLong.of(knownEpoch);
    }

    Object owner() {
        return owner;
    }
}
