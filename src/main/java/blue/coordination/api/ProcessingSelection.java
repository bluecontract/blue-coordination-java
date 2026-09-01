package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/** Read-only exact lane selected for the next bounded processing turn. */
public record ProcessingSelection(
        Kind kind,
        Optional<ManagedEpochApplicationWork> managedEpochApplicationWork) {

    /** Validates that only a managed selection carries managed work. */
    public ProcessingSelection {
        kind = Objects.requireNonNull(kind, "kind");
        managedEpochApplicationWork = Objects.requireNonNull(
                managedEpochApplicationWork,
                "managedEpochApplicationWork");
        if ((kind == Kind.MANAGED_EPOCH_APPLICATION)
                != managedEpochApplicationWork.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a managed-epoch selection carries managed work");
        }
    }

    /** Returns a quiescent selection. */
    public static ProcessingSelection none() {
        return new ProcessingSelection(Kind.NONE, Optional.empty());
    }

    /** Returns the ordinary non-managed journal lane. */
    public static ProcessingSelection journal() {
        return new ProcessingSelection(Kind.JOURNAL, Optional.empty());
    }

    /** Returns one exact canonical managed-epoch work item. */
    public static ProcessingSelection managedEpochApplication(
            ManagedEpochApplicationWork work) {
        return new ProcessingSelection(
                Kind.MANAGED_EPOCH_APPLICATION,
                Optional.of(Objects.requireNonNull(work, "work")));
    }

    /** Closed processing-lane vocabulary. */
    public enum Kind {
        /** No journal or managed work is currently available. */
        NONE,
        /** The ordinary journal lane owns the next fair bounded turn. */
        JOURNAL,
        /** One exact managed source-epoch application owns the next turn. */
        MANAGED_EPOCH_APPLICATION
    }
}
