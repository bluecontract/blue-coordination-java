package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ManagedDocumentBirth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Matches explicit creation declarations to actual processor-emitted demands. */
final class RootedManagedBirths {
    private RootedManagedBirths() { }

    static ManagedOccurrenceResolver.Resolution bindDeclarations(ContractsClosureAdapter.CohortInvocation current,
            ManagedOccurrenceResolver.Resolution resolution, InMemoryDocumentStore documents) {
        var plan = current.managedDraftPlan();
        if (plan == null) return resolution;
        List<ManagedOccurrenceResolver.ResolvedOccurrence> resolved = new ArrayList<>();
        for (var occurrence : resolution.resolvedOccurrences()) {
            var expected = plan.expectedOccurrences().stream().filter(row ->
                    occurrence.demand().sourceDocumentId().value().equals(plan.targetDocumentId().value())
                            && row.path().equals(occurrence.demand().sourcePath())).findFirst().orElse(null);
            if (expected == null) {
                resolved.add(occurrence);
                continue;
            }
            var draft = plan.drafts().get(expected.targetDocumentId());
            boolean fresh = occurrence.targetKind() == ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED
                    && draft.initial().sameExactValue(occurrence.newDraft().initial());
            boolean localReplay = occurrence.targetKind() == ManagedOccurrenceResolver.TargetKind.EXISTING_AUTHORED_INITIAL
                    && occurrence.targetDocumentId().equals(draft.documentId())
                    && isRetainedLocalBirth(current, plan, draft, documents);
            if ((!fresh && !localReplay)
                    || !draft.initial().blueId().equals(occurrence.demand().suppliedValueBlueId())) {
                throw new IllegalArgumentException("Declared birth does not match the exact new authored demand");
            }
            resolved.add(new ManagedOccurrenceResolver.ResolvedOccurrence(occurrence.demand(),
                    draft.documentId(), draft.initial().blueId(), ManagedOccurrenceResolver.TargetKind.NEW_AUTHORED, -1L, draft));
        }
        return new ManagedOccurrenceResolver.Resolution(resolution.demands(), resolved,
                resolution.resolvedExactNodes(), resolution.unresolvedDemands(), resolution.resolvedSelectorPaths());
    }

    static boolean isRetainedLocalBirth(ContractsClosureAdapter.CohortInvocation current,
            ContractsManagedDraftPlan plan, ContractsManagedDraftPlan.ManagedDraft draft,
            InMemoryDocumentStore documents) {
        if (current.rootedEvidence() == null || current.rootedEvidence().context().entryOwners()
                .contains(ContractsClosureAdapter.closureId(plan.targetDocumentId()))) return false;
        var session = documents.find(draft.documentId()).orElse(null);
        if (session == null) return false;
        var history = session.requireRootedHistory();
        var admission = (Map<?, ?>) history.descriptor().get("admission");
        var birth = session.revision(0).managedEpochReceipt().orElseThrow();
        var expected = plan.expectedOccurrences().stream().filter(row -> row.targetDocumentId().equals(draft.documentId()))
                .findFirst().orElseThrow();
        var occurrence = blue.language.processor.closure.ManagedOccurrenceBinding.derived(
                current.input().environment().managedBindingPolicyIdentity(),
                ContractsClosureAdapter.closureId(plan.targetDocumentId()),
                blue.language.processor.closure.ScopeAddress.embedded(expected.path(), 1L),
                ContractsClosureAdapter.closureId(draft.documentId()), draft.initial().blueId(), false, null);
        return "CREATED_IN_OPERATION".equals(admission.get("mode"))
                && draft.initial().blueId().equals(history.descriptor().get("initialDocumentBlueId"))
                && occurrence.occurrenceIdentity().equals(admission.get("birthOccurrenceIdentity"))
                && birth.originalCauseIdentity().equals(current.input().cause().causeIdentity())
                && birth.commitCompanionIdentity().equals(history.admissionCompanionIdentity());
    }

    static ClosureInvocationInput authenticate(ClosureInvocationInput input, ContractsManagedDraftPlan plan,
            ManagedOccurrenceResolver.Resolution resolution) {
        if (plan == null) return input;
        Map<DocumentId, ManagedDocumentBirth> births = new LinkedHashMap<>();
        for (var occurrence : resolution.resolvedOccurrences()) {
            var draft = plan.drafts().get(occurrence.targetDocumentId());
            if (draft == null || input.snapshot().contains(ContractsClosureAdapter.closureId(draft.documentId()))) continue;
            births.putIfAbsent(draft.documentId(), new ManagedDocumentBirth(occurrence.demand(),
                    ContractsClosureAdapter.closureId(draft.documentId()), draft.initial().copyNode()));
        }
        return births.isEmpty() ? input
                : ClosureEvidenceFactory.withProspectiveBirths(input, List.copyOf(births.values()));
    }
}
