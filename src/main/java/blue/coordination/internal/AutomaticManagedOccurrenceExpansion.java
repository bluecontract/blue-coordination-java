package blue.coordination.internal;

import blue.coordination.api.DocumentId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable accumulated evidence retained across bounded Contracts retries. */
record AutomaticManagedOccurrenceExpansion(
        Map<DocumentId, ContractsManagedDraftPlan.ManagedDraft> drafts,
        List<ManagedOccurrenceResolver.ResolvedOccurrence> occurrences,
        Set<String> prospectiveOccurrenceIdentities) {

    AutomaticManagedOccurrenceExpansion {
        ArrayList<Map.Entry<DocumentId,
                ContractsManagedDraftPlan.ManagedDraft>> draftEntries =
                new ArrayList<>(Objects.requireNonNull(
                        drafts, "drafts").entrySet());
        draftEntries.sort(Map.Entry.comparingByKey(
                EmbeddingBinding.DOCUMENT_ORDER));
        LinkedHashMap<DocumentId,
                ContractsManagedDraftPlan.ManagedDraft> canonicalDrafts =
                new LinkedHashMap<>();
        for (Map.Entry<DocumentId,
                ContractsManagedDraftPlan.ManagedDraft> entry : draftEntries) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "draft documentId");
            ContractsManagedDraftPlan.ManagedDraft draft =
                    Objects.requireNonNull(entry.getValue(), "draft");
            if (!documentId.equals(draft.documentId())) {
                throw new IllegalArgumentException(
                        "Automatic draft is stored under the wrong DocumentId");
            }
            canonicalDrafts.put(documentId, draft);
        }
        drafts = Collections.unmodifiableMap(canonicalDrafts);

        ArrayList<ManagedOccurrenceResolver.ResolvedOccurrence>
                canonicalOccurrences = new ArrayList<>(Objects.requireNonNull(
                        occurrences, "occurrences"));
        canonicalOccurrences.replaceAll(occurrence -> Objects.requireNonNull(
                occurrence, "occurrence"));
        canonicalOccurrences.sort(Comparator.comparing(
                occurrence -> occurrence.demand().demandIdentity(),
                EmbeddingBinding.TEXT_ORDER));
        LinkedHashSet<String> demandIdentities = new LinkedHashSet<>();
        for (ManagedOccurrenceResolver.ResolvedOccurrence occurrence
                : canonicalOccurrences) {
            if (!demandIdentities.add(
                    occurrence.demand().demandIdentity())) {
                throw new IllegalArgumentException(
                        "Automatic expansion repeats a resolved demand");
            }
        }
        occurrences = List.copyOf(canonicalOccurrences);

        ArrayList<String> identities = new ArrayList<>(Objects.requireNonNull(
                prospectiveOccurrenceIdentities,
                "prospectiveOccurrenceIdentities"));
        identities.replaceAll(identity -> requireText(
                identity, "prospectiveOccurrenceIdentity"));
        identities.sort(EmbeddingBinding.TEXT_ORDER);
        prospectiveOccurrenceIdentities = Collections.unmodifiableSet(
                new LinkedHashSet<>(identities));
    }

    static AutomaticManagedOccurrenceExpansion empty() {
        return new AutomaticManagedOccurrenceExpansion(
                Map.of(), List.of(), Set.of());
    }

    AutomaticManagedOccurrenceExpansion merge(
            ManagedOccurrenceResolver.Resolution resolution,
            Set<String> newProspectiveIdentities) {
        ManagedOccurrenceResolver.Resolution selected = Objects.requireNonNull(
                resolution, "resolution");
        if (!selected.complete()) {
            throw new IllegalArgumentException(
                    "Only a complete resolution can expand a retry input");
        }
        LinkedHashMap<DocumentId,
                ContractsManagedDraftPlan.ManagedDraft> mergedDrafts =
                new LinkedHashMap<>(drafts);
        selected.newDrafts().forEach((documentId, draft) -> {
            ContractsManagedDraftPlan.ManagedDraft prior = mergedDrafts
                    .putIfAbsent(documentId, draft);
            if (prior != null && !prior.initial().sameExactValue(
                    draft.initial())) {
                throw new IllegalStateException(
                        "Automatic retry changed an accumulated draft "
                                + documentId);
            }
        });
        LinkedHashMap<String, ManagedOccurrenceResolver.ResolvedOccurrence>
                mergedOccurrences = new LinkedHashMap<>();
        occurrences.forEach(occurrence -> mergedOccurrences.put(
                occurrence.demand().demandIdentity(), occurrence));
        selected.resolvedOccurrences().forEach(occurrence -> {
            ManagedOccurrenceResolver.ResolvedOccurrence prior =
                    mergedOccurrences.putIfAbsent(
                            occurrence.demand().demandIdentity(), occurrence);
            if (prior != null && (!prior.targetDocumentId().equals(
                    occurrence.targetDocumentId())
                    || !prior.expectedTargetBlueId().equals(
                            occurrence.expectedTargetBlueId()))) {
                throw new IllegalStateException(
                        "Automatic retry changed a resolved occurrence demand");
            }
        });
        LinkedHashSet<String> prospective = new LinkedHashSet<>(
                prospectiveOccurrenceIdentities);
        prospective.addAll(Objects.requireNonNull(
                newProspectiveIdentities, "newProspectiveIdentities"));
        return new AutomaticManagedOccurrenceExpansion(
                mergedDrafts,
                new ArrayList<>(mergedOccurrences.values()),
                prospective);
    }

    private static String requireText(String value, String label) {
        String selected = Objects.requireNonNull(value, label);
        if (selected.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return selected;
    }
}
