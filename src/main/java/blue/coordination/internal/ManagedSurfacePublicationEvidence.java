package blue.coordination.internal;

import blue.coordination.api.ExactValue;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Non-identity-bearing host evidence retained with a durable closure receipt.
 *
 * <p>Contracts already retains every committed graph, component,
 * subscription, and document transition in {@code ClosureProcessResult}.
 * Coordination therefore retains only the automatic resolver classification
 * and the exact pre-publication component partition which are otherwise lost
 * after a replay.</p>
 */
record ManagedSurfacePublicationEvidence(
        List<ResolvedOccurrence> resolvedOccurrences,
        List<ComponentSnapshot> inputComponents,
        List<OperationRouteIndex.OperationRouteChange>
                operationRouteChanges) {

    /** Compatibility constructor for evidence captured before route prepare. */
    ManagedSurfacePublicationEvidence(
            List<ResolvedOccurrence> resolvedOccurrences,
            List<ComponentSnapshot> inputComponents) {
        this(resolvedOccurrences, inputComponents, List.of());
    }

    ManagedSurfacePublicationEvidence {
        resolvedOccurrences = List.copyOf(Objects.requireNonNull(
                resolvedOccurrences, "resolvedOccurrences"));
        inputComponents = List.copyOf(Objects.requireNonNull(
                inputComponents, "inputComponents"));
        operationRouteChanges = List.copyOf(Objects.requireNonNull(
                operationRouteChanges, "operationRouteChanges"));
    }

    static ManagedSurfacePublicationEvidence empty() {
        return new ManagedSurfacePublicationEvidence(
                List.of(), List.of(), List.of());
    }

    static ManagedSurfacePublicationEvidence committed(
            ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result) {
        ContractsClosureAdapter.CohortInvocation selected =
                Objects.requireNonNull(invocation, "invocation");
        ClosureProcessResult committed = Objects.requireNonNull(
                result, "result");
        if (!committed.commits()) {
            throw new IllegalArgumentException(
                    "Managed publication evidence requires a commit");
        }
        ArrayList<ResolvedOccurrence> resolutions = new ArrayList<>();
        AutomaticManagedOccurrenceExpansion expansion =
                selected.automaticExpansion();
        if (expansion != null) {
            for (ManagedOccurrenceResolver.ResolvedOccurrence resolved
                    : expansion.occurrences()) {
                ManagedOccurrenceBinding binding = committed
                        .occurrenceBindings()
                        .stream()
                        .filter(row -> row.sourceDocumentId().equals(
                                resolved.demand().sourceDocumentId())
                                && row.sourcePath().equals(
                                        resolved.demand().sourcePath()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Resolved occurrence is absent from the "
                                        + "committing input "
                                        + resolved.demand().demandIdentity()));
                ExactValue authoredInitial = resolved.newDraft() == null
                        ? null : resolved.newDraft().initial();
                resolutions.add(new ResolvedOccurrence(
                        resolved.demand().demandIdentity(),
                        binding,
                        resolved.targetKind(),
                        authoredInitial));
            }
        }
        return new ManagedSurfacePublicationEvidence(
                resolutions,
                selected.input().snapshot().components(),
                List.of());
    }

    /** Retains exact route-index changes after replacement preparation. */
    ManagedSurfacePublicationEvidence withOperationRouteChanges(
            List<OperationRouteIndex.OperationRouteChange> changes) {
        if (!operationRouteChanges.isEmpty()) {
            throw new IllegalStateException(
                    "Operation route changes were already retained");
        }
        return new ManagedSurfacePublicationEvidence(
                resolvedOccurrences,
                inputComponents,
                changes);
    }

    boolean present() {
        return !resolvedOccurrences.isEmpty()
                || !inputComponents.isEmpty()
                || !operationRouteChanges.isEmpty();
    }

    record ResolvedOccurrence(
            String demandIdentity,
            ManagedOccurrenceBinding occurrence,
            ManagedOccurrenceResolver.TargetKind targetKind,
            ExactValue authoredInitial) {
        ResolvedOccurrence {
            demandIdentity = requireText(demandIdentity, "demandIdentity");
            occurrence = Objects.requireNonNull(occurrence, "occurrence");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            if ((targetKind == ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED)
                    != (authoredInitial != null)) {
                throw new IllegalArgumentException(
                        "Only NEW_AUTHORED evidence retains authored initial "
                                + "content");
            }
        }
    }

    private static String requireText(String value, String label) {
        String selected = Objects.requireNonNull(value, label);
        if (selected.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return selected;
    }
}
