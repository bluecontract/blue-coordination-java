package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.ExactValue;
import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic indexed resolver for Contracts resource demands. */
final class ManagedOccurrenceResolver {
    static final String INDEX_LOOKUPS =
            "contracts.occurrenceResolver.indexLookups";
    static final String EXACT_NODE_LOOKUPS =
            "contracts.occurrenceResolver.exactNodeLookups";
    static final String RESOLVED_CURRENT =
            "contracts.occurrenceResolver.currentExactResolved";
    static final String RESOLVED_NEW =
            "contracts.occurrenceResolver.newAuthoredResolved";

    private final NodeProvider exactNodes;
    private final EngineMetrics metrics;

    ManagedOccurrenceResolver(
            NodeProvider exactNodes,
            EngineMetrics metrics) {
        this.exactNodes = Objects.requireNonNull(
                exactNodes, "exactNodes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    Resolution resolve(ResolutionRequest request) {
        ResolutionRequest selected = Objects.requireNonNull(
                request, "request");
        ArrayList<ResolvedOccurrence> occurrences = new ArrayList<>();
        ArrayList<ResolvedExactNode> exactNodes = new ArrayList<>();
        ArrayList<UnresolvedDemand> unresolved = new ArrayList<>();
        LinkedHashMap<String, ContractsManagedDraftPlan.ManagedDraft>
                pendingDrafts = new LinkedHashMap<>();
        for (ClosureResourceDemand demand : selected.demands()) {
            if (demand instanceof ExactNodeDemand exact) {
                metrics.increment(EXACT_NODE_LOOKUPS);
                Node exactNode = exactBody(exact.blueId(), exact);
                if (exactNode == null) {
                    unresolved.add(new UnresolvedDemand(
                            exact,
                            ResolutionStatus.MISSING_EXACT_CONTENT,
                            "Exact node content is unavailable"));
                } else {
                    exactNodes.add(new ResolvedExactNode(
                            exact,
                            ExactValue.verified(
                                    exact.blueId(), exactNode)));
                }
                continue;
            }
            if (!(demand instanceof ManagedOccurrenceEvidenceDemand
                    occurrence)) {
                throw new IllegalArgumentException(
                        "Unsupported closed demand kind " + demand.kind());
            }
            Node exactBody = suppliedBody(occurrence);
            if (exactBody == null) {
                unresolved.add(new UnresolvedDemand(
                        occurrence,
                        ResolutionStatus.MISSING_EXACT_CONTENT,
                        "Exact occurrence content is unavailable"));
                continue;
            }
            resolveOccurrence(
                    selected.storeState(),
                    occurrence,
                    exactBody,
                    pendingDrafts).fold(occurrences::add, unresolved::add);
        }
        return new Resolution(
                selected.demands(), occurrences, exactNodes, unresolved);
    }

    private Node suppliedBody(ManagedOccurrenceEvidenceDemand demand) {
        if (demand.suppliedExactValue().isPresent()) {
            return demand.suppliedExactValue().orElseThrow();
        }
        return exactBody(demand.suppliedValueBlueId(), demand);
    }

    private Node exactBody(
            String blueId,
            ClosureResourceDemand demand) {
        NodeProviderResult result;
        try {
            result = exactNodes.fetchResultByBlueId(blueId);
        } catch (CoordinationException failure) {
            LinkedHashMap<String, String> details = new LinkedHashMap<>(
                    failure.details());
            details.putIfAbsent("sourceDocumentId",
                    demand.sourceDocumentId().value());
            details.putIfAbsent("sourcePath", demand.sourcePath());
            details.putIfAbsent("blueId", demand.suppliedValueBlueId());
            throw new CoordinationException(
                    failure.code(),
                    failure.getMessage(),
                    failure,
                    details);
        }
        if (result.outcome() != NodeProviderOutcome.FOUND
                || result.nodes().size() != 1) {
            return null;
        }
        Node supplied = result.nodes().get(0);
        ExactValue.verified(blueId, supplied);
        return supplied;
    }

    private FoldedResolution resolveOccurrence(
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState,
            ManagedOccurrenceEvidenceDemand demand,
            Node suppliedBody,
            Map<String, ContractsManagedDraftPlan.ManagedDraft>
                    pendingDrafts) {
        ManagedLineageIndex index = storeState.lineageIndex();
        metrics.increment(INDEX_LOOKUPS);
        String suppliedBlueId = demand.suppliedValueBlueId();

        ManagedOccurrenceBinding retained = storeState
                .occurrenceInventory()
                .find(DocumentId.of(demand.sourceDocumentId().value()),
                        demand.sourcePath())
                .orElse(null);
        if (retained != null && (!retained.active()
                || retained.expectedTargetBlueId().equals(suppliedBlueId))) {
            ManagedLineageIndex.Lineage stable = index.byDocumentId(
                    DocumentId.of(retained.targetDocumentId().value()));
            if (stable == null) {
                return FoldedResolution.unresolved(new UnresolvedDemand(
                        demand,
                        ResolutionStatus.EXACT_STATE_MISMATCH,
                        "Stable occurrence evidence names an absent lineage "
                                + retained.targetDocumentId().value()));
            }
            return resolveSelectedLineage(
                    index, demand, suppliedBody, stable, true);
        }

        LinkedHashMap<DocumentId, ManagedLineageIndex.Lineage> candidates =
                candidates(index, suppliedBlueId);
        if (candidates.size() > 1) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.AMBIGUOUS_LINEAGE,
                    "Exact state matches more than one managed lineage: "
                            + candidates.keySet()));
        }
        if (candidates.size() == 1) {
            return resolveSelectedLineage(
                    index,
                    demand,
                    suppliedBody,
                    candidates.values().iterator().next(),
                    false);
        }

        ContractsManagedDraftPlan.ManagedDraft draft = pendingDrafts.get(
                suppliedBlueId);
        if (draft == null) {
            ExactValue authored;
            try {
                authored = ExactValue.verified(suppliedBlueId, suppliedBody);
            } catch (IllegalArgumentException invalid) {
                return FoldedResolution.unresolved(new UnresolvedDemand(
                        demand,
                        ResolutionStatus.INVALID_AUTHORED_DOCUMENT,
                        invalid.getMessage()));
            }
            DocumentId newDocumentId = DocumentId.of(suppliedBlueId);
            if (index.byDocumentId(newDocumentId) != null) {
                return FoldedResolution.unresolved(new UnresolvedDemand(
                        demand,
                        ResolutionStatus.EXACT_STATE_MISMATCH,
                        "Content-derived runtime identity already names a "
                                + "different exact lineage"));
            }
            try {
                draft = new ContractsManagedDraftPlan.ManagedDraft(
                        newDocumentId,
                        authored,
                        null,
                        true);
            } catch (RuntimeException invalid) {
                return FoldedResolution.unresolved(new UnresolvedDemand(
                        demand,
                        ResolutionStatus.INVALID_AUTHORED_DOCUMENT,
                        invalid.getMessage()));
            }
            pendingDrafts.put(suppliedBlueId, draft);
        }
        metrics.increment(RESOLVED_NEW);
        return FoldedResolution.resolved(new ResolvedOccurrence(
                demand,
                draft.documentId(),
                suppliedBlueId,
                TargetKind.NEW_AUTHORED,
                draft));
    }

    private FoldedResolution resolveSelectedLineage(
            ManagedLineageIndex index,
            ManagedOccurrenceEvidenceDemand demand,
            Node suppliedBody,
            ManagedLineageIndex.Lineage lineage,
            boolean stableIdentitySelected) {
        String suppliedBlueId = demand.suppliedValueBlueId();
        ExactValue.verified(suppliedBlueId, suppliedBody);
        if (lineage.currentBlueId().equals(suppliedBlueId)) {
            metrics.increment(RESOLVED_CURRENT);
            return FoldedResolution.resolved(new ResolvedOccurrence(
                    demand,
                    lineage.documentId(),
                    suppliedBlueId,
                    TargetKind.CURRENT_EXISTING,
                    null));
        }
        List<Long> retainedEpochs = index.retainedMatches(suppliedBlueId)
                .stream()
                .filter(state -> state.documentId().equals(
                        lineage.documentId()))
                .map(ManagedLineageIndex.RetainedState::epoch)
                .toList();
        if (retainedEpochs.size() > 1) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.AMBIGUOUS_EPOCH,
                    "Exact state occurs at multiple retained epochs "
                            + retainedEpochs + " in " + lineage.documentId()));
        }
        if (!retainedEpochs.isEmpty()
                || lineage.authoredInitialBlueId().equals(suppliedBlueId)
                || lineage.initializedBlueId().equals(suppliedBlueId)) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.UNPROVEN_PROGRESSED_HISTORY,
                    "Historical state cannot be attached without complete "
                            + "history evidence for " + lineage.documentId()));
        }
        String reason = stableIdentitySelected
                ? "Stable occurrence identity does not establish the "
                        + "supplied exact state"
                : "Indexed lineage does not establish the supplied exact state";
        return FoldedResolution.unresolved(new UnresolvedDemand(
                demand,
                ResolutionStatus.EXACT_STATE_MISMATCH,
                reason));
    }

    private static LinkedHashMap<DocumentId, ManagedLineageIndex.Lineage>
            candidates(ManagedLineageIndex index, String suppliedBlueId) {
        LinkedHashMap<DocumentId, ManagedLineageIndex.Lineage> candidates =
                new LinkedHashMap<>();
        addCandidates(candidates, index.currentMatches(suppliedBlueId));
        addCandidates(candidates, index.initializedMatches(suppliedBlueId));
        addCandidates(candidates,
                index.authoredInitialMatches(suppliedBlueId));
        for (ManagedLineageIndex.RetainedState retained
                : index.retainedMatches(suppliedBlueId)) {
            ManagedLineageIndex.Lineage lineage = index.byDocumentId(
                    retained.documentId());
            if (lineage == null) {
                throw new IllegalStateException(
                        "Retained state has no indexed lineage "
                                + retained.documentId());
            }
            candidates.putIfAbsent(lineage.documentId(), lineage);
        }
        return candidates;
    }

    private static void addCandidates(
            Map<DocumentId, ManagedLineageIndex.Lineage> candidates,
            Collection<ManagedLineageIndex.Lineage> lineages) {
        for (ManagedLineageIndex.Lineage lineage : lineages) {
            candidates.putIfAbsent(lineage.documentId(), lineage);
        }
    }

    enum ResolutionStatus {
        MISSING_EXACT_CONTENT,
        AMBIGUOUS_LINEAGE,
        AMBIGUOUS_EPOCH,
        UNPROVEN_PROGRESSED_HISTORY,
        EXACT_STATE_MISMATCH,
        INVALID_AUTHORED_DOCUMENT
    }

    enum TargetKind {
        CURRENT_EXISTING,
        NEW_AUTHORED
    }

    record ResolutionRequest(
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            Set<DocumentId> inputMembers,
            List<ClosureResourceDemand> demands,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState) {
        ResolutionRequest {
            logicalCauseIdentity = requireText(
                    logicalCauseIdentity, "logicalCauseIdentity");
            inputClosureIdentity = requireText(
                    inputClosureIdentity, "inputClosureIdentity");
            MultiDocumentPublicationTransaction.requireSafeInteger(
                    inputGraphGeneration, "inputGraphGeneration");
            inputMembers = Collections.unmodifiableSet(new LinkedHashSet<>(
                    Objects.requireNonNull(inputMembers, "inputMembers")));
            if (inputMembers.isEmpty()) {
                throw new IllegalArgumentException(
                        "Resolution input must retain closure members");
            }
            demands = canonicalDemands(demands);
            storeState = Objects.requireNonNull(storeState, "storeState");
            for (ClosureResourceDemand demand : demands) {
                if (demand instanceof ManagedOccurrenceEvidenceDemand
                        occurrence) {
                    if (!occurrence.logicalCauseIdentity().equals(
                            logicalCauseIdentity)
                            || !occurrence.inputClosureIdentity().equals(
                            inputClosureIdentity)
                            || occurrence.inputGraphGeneration()
                                    != inputGraphGeneration
                            || !inputMembers.contains(DocumentId.of(
                                    occurrence.sourceDocumentId().value()))) {
                        throw new IllegalArgumentException(
                                "Occurrence demand escaped its frozen cause, "
                                        + "closure, graph generation or source");
                    }
                }
            }
        }

        static ResolutionRequest from(
                ClosureInvocationInput input,
                Collection<? extends ClosureResourceDemand> demands,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot
                        storeState) {
            ClosureInvocationInput selected = Objects.requireNonNull(
                    input, "input");
            return new ResolutionRequest(
                    selected.cause().causeIdentity(),
                    selected.snapshot().closureIdentity(),
                    selected.snapshot().graphGeneration(),
                    selected.snapshot().managedDocuments().stream()
                            .map(document -> DocumentId.of(
                                    document.documentId().value()))
                            .collect(java.util.stream.Collectors.toCollection(
                                    LinkedHashSet::new)),
                    List.copyOf(Objects.requireNonNull(demands, "demands")),
                    storeState);
        }

        private static List<ClosureResourceDemand> canonicalDemands(
                Collection<? extends ClosureResourceDemand> supplied) {
            ArrayList<ClosureResourceDemand> selected = new ArrayList<>(
                    Objects.requireNonNull(supplied, "demands"));
            if (selected.isEmpty()) {
                throw new IllegalArgumentException(
                        "Resolver requires at least one typed demand");
            }
            selected.replaceAll(demand -> Objects.requireNonNull(
                    demand, "demand"));
            ArrayList<ClosureResourceDemand> canonical = new ArrayList<>(
                    selected);
            canonical.sort(Comparator.naturalOrder());
            Set<String> identities = new LinkedHashSet<>();
            for (ClosureResourceDemand demand : canonical) {
                if (!identities.add(demand.demandIdentity())) {
                    throw new IllegalArgumentException(
                            "Resolver demands repeat canonical identity "
                                    + demand.demandIdentity());
                }
            }
            if (!canonical.equals(selected)) {
                throw new IllegalArgumentException(
                        "Resolver demands are not in canonical order");
            }
            return List.copyOf(canonical);
        }
    }

    record ResolvedOccurrence(
            ManagedOccurrenceEvidenceDemand demand,
            DocumentId targetDocumentId,
            String expectedTargetBlueId,
            TargetKind targetKind,
            ContractsManagedDraftPlan.ManagedDraft newDraft) {
        ResolvedOccurrence {
            demand = Objects.requireNonNull(demand, "demand");
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            expectedTargetBlueId = requireText(
                    expectedTargetBlueId, "expectedTargetBlueId");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            if ((targetKind == TargetKind.NEW_AUTHORED)
                    != (newDraft != null)) {
                throw new IllegalArgumentException(
                        "Only a new authored target retains a draft");
            }
        }
    }

    record UnresolvedDemand(
            ClosureResourceDemand demand,
            ResolutionStatus status,
            String diagnostic) {
        UnresolvedDemand {
            demand = Objects.requireNonNull(demand, "demand");
            status = Objects.requireNonNull(status, "status");
            diagnostic = requireText(diagnostic, "diagnostic");
        }
    }

    record ResolvedExactNode(
            ExactNodeDemand demand,
            ExactValue exactValue) {
        ResolvedExactNode {
            demand = Objects.requireNonNull(demand, "demand");
            exactValue = Objects.requireNonNull(exactValue, "exactValue");
            if (!demand.blueId().equals(exactValue.blueId())) {
                throw new IllegalArgumentException(
                        "Resolved exact-node evidence has the wrong BlueId");
            }
        }
    }

    record Resolution(
            List<ClosureResourceDemand> demands,
            List<ResolvedOccurrence> resolvedOccurrences,
            List<ResolvedExactNode> resolvedExactNodes,
            List<UnresolvedDemand> unresolvedDemands) {
        Resolution {
            demands = List.copyOf(Objects.requireNonNull(
                    demands, "demands"));
            resolvedOccurrences = List.copyOf(Objects.requireNonNull(
                    resolvedOccurrences, "resolvedOccurrences"));
            resolvedExactNodes = List.copyOf(Objects.requireNonNull(
                    resolvedExactNodes, "resolvedExactNodes"));
            unresolvedDemands = List.copyOf(Objects.requireNonNull(
                    unresolvedDemands, "unresolvedDemands"));
            if (demands.isEmpty()
                    || resolvedOccurrences.size() + resolvedExactNodes.size()
                            + unresolvedDemands.size() != demands.size()) {
                throw new IllegalArgumentException(
                        "Resolution must classify every typed demand once");
            }
        }

        boolean complete() {
            return unresolvedDemands.isEmpty();
        }

        Map<DocumentId, ContractsManagedDraftPlan.ManagedDraft> newDrafts() {
            LinkedHashMap<DocumentId,
                    ContractsManagedDraftPlan.ManagedDraft> drafts =
                    new LinkedHashMap<>();
            for (ResolvedOccurrence occurrence : resolvedOccurrences) {
                if (occurrence.newDraft() != null) {
                    ContractsManagedDraftPlan.ManagedDraft duplicate =
                            drafts.putIfAbsent(
                                    occurrence.targetDocumentId(),
                                    occurrence.newDraft());
                    if (duplicate != null
                            && !duplicate.initial().sameExactValue(
                                    occurrence.newDraft().initial())) {
                        throw new IllegalStateException(
                                "One new lineage resolved to conflicting "
                                        + "authored bodies "
                                        + occurrence.targetDocumentId());
                    }
                }
            }
            return Collections.unmodifiableMap(drafts);
        }

        Set<DocumentId> existingTargets() {
            LinkedHashSet<DocumentId> targets = new LinkedHashSet<>();
            for (ResolvedOccurrence occurrence : resolvedOccurrences) {
                if (occurrence.targetKind() == TargetKind.CURRENT_EXISTING) {
                    targets.add(occurrence.targetDocumentId());
                }
            }
            return Collections.unmodifiableSet(targets);
        }
    }

    private record FoldedResolution(
            ResolvedOccurrence resolved,
            UnresolvedDemand unresolved) {
        private FoldedResolution {
            if ((resolved == null) == (unresolved == null)) {
                throw new IllegalArgumentException(
                        "Folded resolution must contain exactly one branch");
            }
        }

        static FoldedResolution resolved(ResolvedOccurrence occurrence) {
            return new FoldedResolution(
                    Objects.requireNonNull(occurrence, "occurrence"), null);
        }

        static FoldedResolution unresolved(UnresolvedDemand demand) {
            return new FoldedResolution(
                    null, Objects.requireNonNull(demand, "demand"));
        }

        void fold(
                java.util.function.Consumer<ResolvedOccurrence> onResolved,
                java.util.function.Consumer<UnresolvedDemand> onUnresolved) {
            if (resolved != null) {
                onResolved.accept(resolved);
            } else {
                onUnresolved.accept(unresolved);
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
