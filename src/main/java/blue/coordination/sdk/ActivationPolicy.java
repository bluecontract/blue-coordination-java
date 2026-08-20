package blue.coordination.sdk;

import blue.coordination.api.ActivationMode;

import java.util.Objects;
import java.util.Optional;

/** Temporal admission policy for a managed document occurrence. */
public final class ActivationPolicy {
    /** Stable SDK vocabulary for the supported Contracts 1.0 policies. */
    public enum Kind {
        /** A new managed lineage begins when it is attached. */
        FROM_NOW,
        /** An imported lineage replays its complete source history. */
        IMPORT_FULL_HISTORY,
        /** An imported lineage starts after an exact persisted frontier. */
        IMPORT_FROM_FRONTIER,
        /** An existing lineage is already current through attachment. */
        ATTACH_CURRENT_STATE,
        /** An immutable value is evidence only and is not a live process. */
        PASSIVE_SNAPSHOT
    }

    private static final ActivationPolicy FROM_NOW = new ActivationPolicy(
            Kind.FROM_NOW, null);
    private static final ActivationPolicy IMPORT_FULL_HISTORY =
            new ActivationPolicy(Kind.IMPORT_FULL_HISTORY, null);
    private static final ActivationPolicy ATTACH_CURRENT_STATE =
            new ActivationPolicy(Kind.ATTACH_CURRENT_STATE, null);
    private static final ActivationPolicy PASSIVE_SNAPSHOT =
            new ActivationPolicy(Kind.PASSIVE_SNAPSHOT, null);

    private final Kind kind;
    private final ExactBlueValue frontierEvidence;

    private ActivationPolicy(
            Kind kind,
            ExactBlueValue frontierEvidence) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.frontierEvidence = frontierEvidence;
        if ((kind == Kind.IMPORT_FROM_FRONTIER)
                != (frontierEvidence != null)) {
            throw new IllegalArgumentException(
                    "Only IMPORT_FROM_FRONTIER carries frontier evidence");
        }
    }

    /** Starts a new managed lineage at its attachment/admission point. */
    public static ActivationPolicy fromNow() {
        return FROM_NOW;
    }

    /** Imports all available source history before the document is READY. */
    public static ActivationPolicy importFullHistory() {
        return IMPORT_FULL_HISTORY;
    }

    /** Imports state strictly after the supplied exact frontier evidence. */
    public static ActivationPolicy importFromFrontier(
            ExactBlueValue frontierEvidence) {
        return new ActivationPolicy(
                Kind.IMPORT_FROM_FRONTIER,
                Objects.requireNonNull(frontierEvidence,
                        "frontierEvidence"));
    }

    /** Attaches a managed lineage proven current through the cutoff. */
    public static ActivationPolicy attachCurrentState() {
        return ATTACH_CURRENT_STATE;
    }

    /** Retains exact immutable evidence without creating a live lineage. */
    public static ActivationPolicy passiveSnapshot() {
        return PASSIVE_SNAPSHOT;
    }

    /** Returns the stable SDK policy kind. */
    public Kind kind() {
        return kind;
    }

    /** Returns exact frontier evidence when this is a frontier import. */
    public Optional<ExactBlueValue> frontierEvidence() {
        return Optional.ofNullable(frontierEvidence);
    }

    ActivationMode activationMode() {
        return switch (kind) {
            case FROM_NOW -> ActivationMode.BIRTH_AT_ATTACHMENT;
            case IMPORT_FULL_HISTORY -> ActivationMode.IMPORT_FULL_HISTORY;
            case IMPORT_FROM_FRONTIER -> ActivationMode.IMPORT_FROM_FRONTIER;
            case ATTACH_CURRENT_STATE -> ActivationMode.ATTACH_CURRENT_STATE;
            case PASSIVE_SNAPSHOT -> ActivationMode.PASSIVE_SNAPSHOT;
        };
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ActivationPolicy policy
                && kind == policy.kind
                && Objects.equals(frontierEvidence,
                        policy.frontierEvidence);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, frontierEvidence);
    }

    @Override
    public String toString() {
        return kind.name();
    }
}
