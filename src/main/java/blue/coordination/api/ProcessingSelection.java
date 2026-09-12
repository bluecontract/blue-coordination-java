package blue.coordination.api;

import java.util.Objects;
import java.util.Optional;

/** Read-only exact lane selected for the next bounded processing turn. */
public record ProcessingSelection(
        Kind kind,
        Optional<ManagedEpochApplicationWork> managedEpochApplicationWork,
        Optional<DocumentId> rootedRetainedRoot) {

    /** Preserves ordinary journal and independently owned managed selection construction. */
    public ProcessingSelection(Kind kind, Optional<ManagedEpochApplicationWork> managedEpochApplicationWork) {
        this(kind, managedEpochApplicationWork, Optional.empty());
    }

    /** Validates that only a managed selection carries managed work. */
    public ProcessingSelection {
        kind = Objects.requireNonNull(kind, "kind");
        managedEpochApplicationWork = Objects.requireNonNull(
                managedEpochApplicationWork,
                "managedEpochApplicationWork");
        rootedRetainedRoot = Objects.requireNonNull(rootedRetainedRoot, "rootedRetainedRoot");
        if (rootedRetainedRoot.isPresent() && kind != Kind.MANAGED_EPOCH_APPLICATION)
            throw new IllegalArgumentException("A local retained root requires exact historical work");
        if ((kind == Kind.MANAGED_EPOCH_APPLICATION)
                != managedEpochApplicationWork.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a managed-epoch selection carries managed work");
        }
    }

    /** Returns no runnable selected lane; blocked work can also produce NONE, so this is not a completeness proof. */
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

    /** Selects an exact historical prerequisite inside a root's calculated dependency view. */
    public static ProcessingSelection rootedRetained(DocumentId root, ManagedEpochApplicationWork work) {
        return new ProcessingSelection(Kind.MANAGED_EPOCH_APPLICATION,
                Optional.of(Objects.requireNonNull(work, "work")), Optional.of(Objects.requireNonNull(root, "root")));
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
