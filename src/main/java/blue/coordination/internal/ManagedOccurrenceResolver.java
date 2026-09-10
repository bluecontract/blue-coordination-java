package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.coordination.api.CoordinationErrorCode;
import blue.coordination.api.CoordinationException;
import blue.coordination.api.ExactValue;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureResourceDemand;
import blue.language.processor.closure.ExactNodeDemand;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedOccurrenceEvidenceDemand;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.VerifyingNodeProvider;

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
    static final String RESOLVED_AUTHORED_INITIAL =
            "contracts.occurrenceResolver.authoredInitialResolved";
    static final String RESOLVED_EPOCH_ZERO =
            "contracts.occurrenceResolver.initializedEpochZeroResolved";
    static final String RESOLVED_RETAINED =
            "contracts.occurrenceResolver.retainedEpochResolved";

    private final NodeProvider exactNodeSource;
    private final EngineMetrics metrics;
    private final ManagedRepresentationHistory representationHistory;

    ManagedOccurrenceResolver(
            NodeProvider exactNodes,
            EngineMetrics metrics) {
        this(exactNodes, metrics, null);
    }

    ManagedOccurrenceResolver(NodeProvider exactNodes, EngineMetrics metrics,
            ManagedRepresentationHistory representationHistory) {
        this.representationHistory = representationHistory;
        this.exactNodeSource = Objects.requireNonNull(
                exactNodes, "exactNodes");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    private boolean isReplayable(ManagedLineageIndex.Lineage lineage, long epoch) {
        return lineage.isReplayableHistoricalPosition(epoch) || (representationHistory != null
                && representationHistory.provesReplayable(lineage, epoch));
    }

    Resolution resolve(ResolutionRequest request) {
        ResolutionRequest selected = Objects.requireNonNull(
                request, "request");
        ArrayList<ResolvedOccurrence> occurrences = new ArrayList<>();
        ArrayList<ResolvedExactNode> exactNodes = new ArrayList<>();
        ArrayList<UnresolvedDemand> unresolved = new ArrayList<>();
        LinkedHashSet<String> resolvedSelectorPaths = new LinkedHashSet<>();
        LinkedHashMap<String, ContractsManagedDraftPlan.ManagedDraft>
                pendingDrafts = new LinkedHashMap<>();
        for (ClosureResourceDemand demand : selected.demands()) {
            if (demand instanceof ExactNodeDemand exact) {
                metrics.increment(EXACT_NODE_LOOKUPS);
                FetchedExactEvidence evidence = exactEvidence(
                        exact.blueId(), exact);
                if (evidence == null) {
                    unresolved.add(new UnresolvedDemand(
                            exact,
                            ResolutionStatus.MISSING_EXACT_CONTENT,
                            "Exact node content is unavailable"));
                } else {
                    exactNodes.add(new ResolvedExactNode(
                            exact,
                            evidence.toExactValue(exact.blueId()),
                            evidence.body(),
                            evidence.cyclicProof()));
                }
                continue;
            }
            if (!(demand instanceof ManagedOccurrenceEvidenceDemand
                    occurrence)) {
                throw new IllegalArgumentException(
                        "Unsupported closed demand kind " + demand.kind());
            }
            FetchedExactEvidence exactEvidence = suppliedEvidence(occurrence);
            if (exactEvidence == null) {
                unresolved.add(new UnresolvedDemand(
                        occurrence,
                        ResolutionStatus.MISSING_EXACT_CONTENT,
                        "Exact occurrence content is unavailable"));
                continue;
            }
            resolveOccurrence(
                    selected.storeState(),
                    occurrence,
                    exactEvidence,
                    pendingDrafts,
                    selected.selectionPlan(),
                    selected.managedRevisionCause(),
                    resolvedSelectorPaths).fold(
                            occurrences::add, unresolved::add);
        }
        return new Resolution(
                selected.demands(),
                occurrences,
                exactNodes,
                unresolved,
                resolvedSelectorPaths);
    }

    private FetchedExactEvidence suppliedEvidence(
            ManagedOccurrenceEvidenceDemand demand) {
        if (demand.suppliedExactValue().isPresent()) {
            return new FetchedExactEvidence(
                    demand.suppliedExactValue().orElseThrow(), null);
        }
        return exactEvidence(demand.suppliedValueBlueId(), demand);
    }

    private FetchedExactEvidence exactEvidence(
            String blueId,
            ClosureResourceDemand demand) {
        ProofCapturingProvider provider = new ProofCapturingProvider(
                exactNodeSource);
        NodeProviderResult result;
        try {
            result = new VerifyingNodeProvider(provider)
                    .fetchResultByBlueId(blueId);
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
            if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
                String diagnostic = result.diagnostic().orElse(
                        "Provider returned " + result.outcome()
                                + " for exact evidence " + blueId);
                if (!BlueIds.hasCyclicMemberSeparator(blueId)) {
                    throw new IllegalArgumentException(diagnostic);
                }
                throw evidenceFailure(
                        cyclicProofErrorCode(provider.proofResult(blueId)),
                        demand,
                        diagnostic);
            }
            return null;
        }
        Node supplied = result.nodes().get(0);
        if (!BlueIds.hasCyclicMemberSeparator(blueId)) {
            return new FetchedExactEvidence(supplied, null);
        }
        CyclicSetProofResult proofResult = provider.proofResult(blueId);
        if (proofResult == null
                || proofResult.outcome() != NodeProviderOutcome.FOUND) {
            throw evidenceFailure(
                    proofResult == null
                            || proofResult.outcome()
                            == NodeProviderOutcome.NOT_FOUND
                            ? CoordinationErrorCode.MISSING_EXACT_VALUE_PROOF
                            : CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                    demand,
                    "Verified cyclic provider result omitted its complete "
                            + "proof for " + blueId);
        }
        CyclicSetProof proof = proofResult.proof().orElseThrow(
                () -> evidenceFailure(
                        CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF,
                        demand,
                        "Found cyclic proof result omitted proof for "
                                + blueId));
        return new FetchedExactEvidence(
                supplied,
                proof);
    }

    private static CoordinationErrorCode cyclicProofErrorCode(
            CyclicSetProofResult proofResult) {
        return proofResult == null
                || proofResult.outcome() == NodeProviderOutcome.NOT_FOUND
                ? CoordinationErrorCode.MISSING_EXACT_VALUE_PROOF
                : CoordinationErrorCode.INVALID_EXACT_VALUE_PROOF;
    }

    private static CoordinationException evidenceFailure(
            CoordinationErrorCode code,
            ClosureResourceDemand demand,
            String diagnostic) {
        return new CoordinationException(
                code,
                diagnostic,
                null,
                Map.of(
                        "sourceDocumentId",
                        demand.sourceDocumentId().value(),
                        "sourcePath",
                        demand.sourcePath(),
                        "blueId",
                        demand.suppliedValueBlueId()));
    }

    /**
     * Only an authenticated receipt-event target may discover a replacement
     * lineage while its input occurrence is still catching up. The original
     * row remains the authority until Contracts reconciles the successful
     * event; ordinary predecessor and installed-successor values stay on the
     * stable lineage lane.
     */
    static boolean isVerifiedPendingReceiptEventReplacement(
            ManagedRevisionCause revision,
            ManagedOccurrenceBinding retained,
            String suppliedBlueId,
            ManagedLineageIndex index) {
        if (revision == null || retained == null || retained.active()
                || retained.pendingHistoricalEpoch() == null
                || revision.sourceTransitionReceipt().isEmpty()
                || revision.sourceTransitionReceipt().orElseThrow()
                        .emittedRootEvents().isEmpty()
                || !retained.occurrenceIdentity().equals(
                        revision.targetOccurrenceIdentity())
                || !retained.targetDocumentId().equals(
                        revision.childDocumentId())
                || retained.pendingHistoricalEpoch().longValue()
                        != revision.fromEpoch()
                || !retained.expectedTargetBlueId().equals(
                        revision.beforeBlueId())
                || suppliedBlueId.equals(revision.beforeBlueId())
                || suppliedBlueId.equals(revision.afterBlueId())) {
            return false;
        }
        ManagedLineageIndex.Lineage child = index.byDocumentId(
                DocumentId.of(revision.childDocumentId().value()));
        return child != null && revision.toEpoch() <= child.currentEpoch()
                && (revision.toEpoch() != child.currentEpoch()
                        || !suppliedBlueId.equals(child.currentBlueId()));
    }

    private FoldedResolution resolveOccurrence(
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState,
            ManagedOccurrenceEvidenceDemand demand,
            FetchedExactEvidence suppliedEvidence,
            Map<String, ContractsManagedDraftPlan.ManagedDraft>
                    pendingDrafts,
            ContractsManagedEpochSelectionPlan selectionPlan,
            ManagedRevisionCause managedRevisionCause,
            Set<String> resolvedSelectorPaths) {
        ManagedLineageIndex index = storeState.lineageIndex();
        metrics.increment(INDEX_LOOKUPS);
        String suppliedBlueId = demand.suppliedValueBlueId();
        Node suppliedBody = suppliedEvidence.body();

        ContractsManagedEpochSelectionPlan.Selection explicit =
                selectionPlan == null
                        ? null : selectionPlan.selectionFor(
                                DocumentId.of(
                                        demand.sourceDocumentId().value()),
                                demand.sourcePath());
        if (explicit != null) {
            resolvedSelectorPaths.add(explicit.targetOccurrencePath());
            return resolveExplicitSelection(
                    index, demand, suppliedBody, explicit);
        }

        ManagedOccurrenceBinding retained = storeState
                .occurrenceInventory()
                .find(DocumentId.of(demand.sourceDocumentId().value()),
                        demand.sourcePath())
                .orElse(null);
        if (retained != null && ((!retained.active()
                && !isVerifiedPendingReceiptEventReplacement(
                        managedRevisionCause, retained, suppliedBlueId, index))
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
                    ResolutionStatus.AMBIGUOUS_MANAGED_LINEAGE,
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

        if (hasKnownNonReplayablePosition(index, suppliedBlueId)) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.UNPROVEN_MANAGED_HISTORY,
                    "Exact state belongs to immutable managed history but "
                            + "has no contiguous receipt path from its "
                            + "component representation"));
        }

        if (lifecycleMarkerTrue(suppliedBody,
                ProcessorContractConstants.KEY_INITIALIZED)
                || lifecycleMarkerTrue(suppliedBody,
                        ProcessorContractConstants.KEY_TERMINATED)) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.UNPROVEN_MANAGED_HISTORY,
                    "Unknown initialized or progressed exact state cannot "
                            + "create a new managed lineage"));
        }

        ContractsManagedDraftPlan.ManagedDraft draft = pendingDrafts.get(
                suppliedBlueId);
        if (draft == null) {
            ExactValue authored;
            try {
                authored = suppliedEvidence.toExactValue(suppliedBlueId);
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
                -1L,
                draft));
    }

    private FoldedResolution resolveExplicitSelection(
            ManagedLineageIndex index,
            ManagedOccurrenceEvidenceDemand demand,
            Node suppliedBody,
            ContractsManagedEpochSelectionPlan.Selection selection) {
        String suppliedBlueId = demand.suppliedValueBlueId();
        if (!selection.expectedSourceBlueId().equals(suppliedBlueId)) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.EXACT_STATE_MISMATCH,
                    "Managed epoch selector expected "
                            + selection.expectedSourceBlueId()
                            + " but the occurrence supplied "
                            + suppliedBlueId));
        }
        requirePlainBodyIdentity(suppliedBlueId, suppliedBody);
        ManagedLineageIndex.Lineage lineage = index.byDocumentId(
                selection.sourceDocumentId());
        if (lineage == null) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.EXACT_STATE_MISMATCH,
                    "Managed epoch selector names an absent lineage "
                            + selection.sourceDocumentId()));
        }
        String selectedBlueId = blueIdAt(
                lineage, selection.sourceEpoch());
        if (selectedBlueId == null
                || !selectedBlueId.equals(
                        selection.expectedSourceBlueId())) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.EXACT_STATE_MISMATCH,
                    "Managed epoch selector does not match "
                            + selection.sourceDocumentId()
                            + " at epoch " + selection.sourceEpoch()));
        }

        TargetKind kind;
        if (selection.sourceEpoch() == -1L) {
            kind = TargetKind.EXISTING_AUTHORED_INITIAL;
            metrics.increment(RESOLVED_AUTHORED_INITIAL);
        } else if (selection.sourceEpoch() == lineage.currentEpoch()) {
            kind = TargetKind.CURRENT_EXISTING;
            metrics.increment(RESOLVED_CURRENT);
        } else if (selection.sourceEpoch() == 0L) {
            kind = TargetKind.EXISTING_INITIALIZED_EPOCH_ZERO;
            metrics.increment(RESOLVED_EPOCH_ZERO);
        } else {
            kind = TargetKind.EXISTING_RETAINED_EPOCH;
            metrics.increment(RESOLVED_RETAINED);
        }
        return FoldedResolution.resolved(new ResolvedOccurrence(
                demand,
                lineage.documentId(),
                suppliedBlueId,
                kind,
                selection.sourceEpoch(),
                null));
    }

    private String blueIdAt(
            ManagedLineageIndex.Lineage lineage,
            long sourceEpoch) {
        if (sourceEpoch == -1L) {
            return isReplayable(lineage, sourceEpoch)
                    ? lineage.authoredInitialBlueId() : null;
        }
        if (sourceEpoch == lineage.currentEpoch()) {
            return lineage.currentBlueId();
        }
        if (!isReplayable(lineage, sourceEpoch)) {
            return null;
        }
        for (ManagedLineageIndex.RetainedState state
                : lineage.retainedStates()) {
            if (state.epoch() == sourceEpoch) {
                return state.blueId();
            }
        }
        return null;
    }

    private FoldedResolution resolveSelectedLineage(
            ManagedLineageIndex index,
            ManagedOccurrenceEvidenceDemand demand,
            Node suppliedBody,
            ManagedLineageIndex.Lineage lineage,
            boolean stableIdentitySelected) {
        String suppliedBlueId = demand.suppliedValueBlueId();
        requirePlainBodyIdentity(suppliedBlueId, suppliedBody);
        if (lineage.currentBlueId().equals(suppliedBlueId)) {
            metrics.increment(RESOLVED_CURRENT);
            return FoldedResolution.resolved(new ResolvedOccurrence(
                    demand,
                    lineage.documentId(),
                    suppliedBlueId,
                    TargetKind.CURRENT_EXISTING,
                    lineage.currentEpoch(),
                    null));
        }
        List<Long> retainedEpochs = index.retainedMatches(suppliedBlueId)
                .stream()
                .filter(state -> state.documentId().equals(
                        lineage.documentId()))
                .filter(state -> isReplayable(lineage, 
                        state.epoch()))
                .map(ManagedLineageIndex.RetainedState::epoch)
                .distinct()
                .toList();
        LinkedHashSet<Long> matchingPositions = new LinkedHashSet<>();
        if (lineage.authoredInitialBlueId().equals(suppliedBlueId)
                && isReplayable(lineage, -1L)) {
            matchingPositions.add(-1L);
        }
        if (lineage.initializedBlueId().equals(suppliedBlueId)
                && isReplayable(lineage, 0L)) {
            matchingPositions.add(0L);
        }
        matchingPositions.addAll(retainedEpochs);
        if (matchingPositions.size() > 1) {
            return FoldedResolution.unresolved(new UnresolvedDemand(
                    demand,
                    ResolutionStatus.AMBIGUOUS_MANAGED_EPOCH,
                    "Exact state occurs at multiple managed positions "
                            + matchingPositions + " in "
                            + lineage.documentId()));
        }
        if (!matchingPositions.isEmpty()) {
            long admittedEpoch = matchingPositions.iterator().next();
            TargetKind kind;
            if (admittedEpoch == -1L) {
                kind = TargetKind.EXISTING_AUTHORED_INITIAL;
                metrics.increment(RESOLVED_AUTHORED_INITIAL);
            } else if (admittedEpoch == 0L) {
                kind = TargetKind.EXISTING_INITIALIZED_EPOCH_ZERO;
                metrics.increment(RESOLVED_EPOCH_ZERO);
            } else {
                kind = TargetKind.EXISTING_RETAINED_EPOCH;
                metrics.increment(RESOLVED_RETAINED);
            }
            return FoldedResolution.resolved(new ResolvedOccurrence(
                    demand,
                    lineage.documentId(),
                    suppliedBlueId,
                    kind,
                    admittedEpoch,
                    null));
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

    /**
     * Plain values are independently rehashed here. Cyclic member bodies have
     * already crossed {@link VerifyingNodeProvider}, which authenticates the
     * complete placeholder set and compares the selected resolved member. A
     * direct hash of that resolved body is intentionally not its
     * {@code MASTER#n} identity.
     */
    private static void requirePlainBodyIdentity(
            String suppliedBlueId,
            Node suppliedBody) {
        if (!BlueIds.hasCyclicMemberSeparator(suppliedBlueId)) {
            ExactValue.verified(suppliedBlueId, suppliedBody);
        }
    }

    private LinkedHashMap<DocumentId, ManagedLineageIndex.Lineage>
            candidates(ManagedLineageIndex index, String suppliedBlueId) {
        LinkedHashMap<DocumentId, ManagedLineageIndex.Lineage> candidates =
                new LinkedHashMap<>();
        addCandidates(candidates, index.currentMatches(suppliedBlueId));
        addReplayableCandidates(
                candidates, index.initializedMatches(suppliedBlueId), 0L);
        addReplayableCandidates(
                candidates, index.authoredInitialMatches(suppliedBlueId), -1L);
        for (ManagedLineageIndex.RetainedState retained
                : index.retainedMatches(suppliedBlueId)) {
            ManagedLineageIndex.Lineage lineage = index.byDocumentId(
                    retained.documentId());
            if (lineage == null) {
                throw new IllegalStateException(
                        "Retained state has no indexed lineage "
                                + retained.documentId());
            }
            if (isReplayable(lineage, retained.epoch())) {
                candidates.putIfAbsent(lineage.documentId(), lineage);
            }
        }
        return candidates;
    }

    private boolean hasKnownNonReplayablePosition(
            ManagedLineageIndex index,
            String suppliedBlueId) {
        for (ManagedLineageIndex.Lineage lineage
                : index.authoredInitialMatches(suppliedBlueId)) {
            if (!isReplayable(lineage, -1L)) {
                return true;
            }
        }
        for (ManagedLineageIndex.Lineage lineage
                : index.initializedMatches(suppliedBlueId)) {
            if (!isReplayable(lineage, 0L)) {
                return true;
            }
        }
        for (ManagedLineageIndex.RetainedState retained
                : index.retainedMatches(suppliedBlueId)) {
            ManagedLineageIndex.Lineage lineage = index.byDocumentId(
                    retained.documentId());
            if (lineage == null) {
                throw new IllegalStateException(
                        "Retained state has no indexed lineage "
                                + retained.documentId());
            }
            if (!isReplayable(lineage, retained.epoch())) {
                return true;
            }
        }
        return false;
    }

    private void addReplayableCandidates(
            Map<DocumentId, ManagedLineageIndex.Lineage> candidates,
            Collection<ManagedLineageIndex.Lineage> lineages,
            long historicalPosition) {
        for (ManagedLineageIndex.Lineage lineage : lineages) {
            if (isReplayable(lineage, historicalPosition)) {
                candidates.putIfAbsent(lineage.documentId(), lineage);
            }
        }
    }

    private static void addCandidates(
            Map<DocumentId, ManagedLineageIndex.Lineage> candidates,
            Collection<ManagedLineageIndex.Lineage> lineages) {
        for (ManagedLineageIndex.Lineage lineage : lineages) {
            candidates.putIfAbsent(lineage.documentId(), lineage);
        }
    }

    private static boolean lifecycleMarkerTrue(Node document, String key) {
        Map<String, Node> properties = Objects.requireNonNull(
                document, "document").getProperties();
        if (properties == null) {
            return false;
        }
        Node marker = properties.get(key);
        return marker != null && Boolean.TRUE.equals(marker.getValue());
    }

    enum ResolutionStatus {
        MISSING_EXACT_CONTENT,
        AMBIGUOUS_MANAGED_LINEAGE,
        AMBIGUOUS_MANAGED_EPOCH,
        UNPROVEN_MANAGED_HISTORY,
        EXACT_STATE_MISMATCH,
        REJECTED_MANAGED_DECLARATION,
        INVALID_AUTHORED_DOCUMENT
    }

    enum TargetKind {
        CURRENT_EXISTING,
        NEW_AUTHORED,
        EXISTING_AUTHORED_INITIAL,
        EXISTING_INITIALIZED_EPOCH_ZERO,
        EXISTING_RETAINED_EPOCH
    }

    record ResolutionRequest(
            String logicalCauseIdentity,
            String inputClosureIdentity,
            long inputGraphGeneration,
            Set<DocumentId> inputMembers,
            List<ClosureResourceDemand> demands,
            InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState,
            ContractsManagedEpochSelectionPlan selectionPlan,
            ManagedRevisionCause managedRevisionCause) {
        ResolutionRequest(
                String logicalCauseIdentity,
                String inputClosureIdentity,
                long inputGraphGeneration,
                Set<DocumentId> inputMembers,
                List<ClosureResourceDemand> demands,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot
                        storeState) {
            this(
                    logicalCauseIdentity,
                    inputClosureIdentity,
                    inputGraphGeneration,
                    inputMembers,
                    demands,
                    storeState,
                    null,
                    null);
        }

        ResolutionRequest(
                String logicalCauseIdentity,
                String inputClosureIdentity,
                long inputGraphGeneration,
                Set<DocumentId> inputMembers,
                List<ClosureResourceDemand> demands,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot storeState,
                ContractsManagedEpochSelectionPlan selectionPlan) {
            this(logicalCauseIdentity, inputClosureIdentity,
                    inputGraphGeneration, inputMembers, demands, storeState,
                    selectionPlan, null);
        }

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
            if (managedRevisionCause != null
                    && (!managedRevisionCause.causeIdentity().equals(
                            logicalCauseIdentity)
                        || !inputMembers.contains(DocumentId.of(
                                managedRevisionCause.childDocumentId()
                                        .value())))) {
                throw new IllegalArgumentException(
                        "Managed revision evidence escaped its frozen cause "
                                + "or source member");
            }
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
            return from(input, demands, storeState, null);
        }

        static ResolutionRequest from(
                ClosureInvocationInput input,
                Collection<? extends ClosureResourceDemand> demands,
                InMemoryDocumentStore.OccurrenceResolutionSnapshot
                        storeState,
                ContractsManagedEpochSelectionPlan selectionPlan) {
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
                    storeState,
                    selectionPlan,
                    selected.cause() instanceof ManagedRevisionCause revision
                            ? revision : null);
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
            long admittedSourceEpoch,
            ContractsManagedDraftPlan.ManagedDraft newDraft) {
        ResolvedOccurrence {
            demand = Objects.requireNonNull(demand, "demand");
            targetDocumentId = Objects.requireNonNull(
                    targetDocumentId, "targetDocumentId");
            expectedTargetBlueId = requireText(
                    expectedTargetBlueId, "expectedTargetBlueId");
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            if (admittedSourceEpoch < -1L) {
                throw new IllegalArgumentException(
                        "admittedSourceEpoch must be -1 or non-negative");
            }
            if ((targetKind == TargetKind.NEW_AUTHORED)
                    != (newDraft != null)) {
                throw new IllegalArgumentException(
                        "Only a new authored target retains a draft");
            }
            if ((targetKind == TargetKind.NEW_AUTHORED
                            || targetKind
                                    == TargetKind.EXISTING_AUTHORED_INITIAL)
                    != (admittedSourceEpoch == -1L)) {
                throw new IllegalArgumentException(
                        "Only authored-initial targets use epoch -1");
            }
            if (targetKind == TargetKind
                            .EXISTING_INITIALIZED_EPOCH_ZERO
                    && admittedSourceEpoch != 0L) {
                throw new IllegalArgumentException(
                        "Initialized target must select source epoch zero");
            }
        }

        boolean historicalExisting() {
            return targetKind == TargetKind.EXISTING_AUTHORED_INITIAL
                    || targetKind
                            == TargetKind.EXISTING_INITIALIZED_EPOCH_ZERO
                    || targetKind == TargetKind.EXISTING_RETAINED_EPOCH;
        }

        Long pendingHistoricalEpoch() {
            return historicalExisting()
                    ? Long.valueOf(admittedSourceEpoch) : null;
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
            ExactValue exactValue,
            Node providerBody,
            CyclicSetProof cyclicProof) {
        ResolvedExactNode(
                ExactNodeDemand demand,
                ExactValue exactValue) {
            this(demand, exactValue, exactValue.copyNode(), null);
        }

        ResolvedExactNode {
            demand = Objects.requireNonNull(demand, "demand");
            exactValue = Objects.requireNonNull(exactValue, "exactValue");
            providerBody = Objects.requireNonNull(
                    providerBody, "providerBody").clone();
            if (!demand.blueId().equals(exactValue.blueId())) {
                throw new IllegalArgumentException(
                        "Resolved exact-node evidence has the wrong BlueId");
            }
            boolean cyclic = BlueIds.hasCyclicMemberSeparator(
                    demand.blueId());
            if (cyclic != exactValue.isCyclicMember()
                    || cyclic != (cyclicProof != null)) {
                throw new IllegalArgumentException(
                        "Resolved exact-node cyclic evidence is incomplete");
            }
            ExactValue authenticated = cyclic
                    ? ExactValue.fromVerifiedProviderEvidence(
                            demand.blueId(), providerBody, cyclicProof)
                    : ExactValue.verified(demand.blueId(), providerBody);
            if (!authenticated.sameExactValue(exactValue)) {
                throw new IllegalArgumentException(
                        "Resolved exact-node provider body changed after "
                                + "verification");
            }
        }

        @Override
        public Node providerBody() {
            return providerBody.clone();
        }
    }

    private record FetchedExactEvidence(
            Node body,
            CyclicSetProof cyclicProof) {
        private FetchedExactEvidence {
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public Node body() {
            return body.clone();
        }

        private ExactValue toExactValue(String blueId) {
            return cyclicProof == null
                    ? ExactValue.verified(blueId, body)
                    : ExactValue.fromVerifiedProviderEvidence(
                            blueId, body, cyclicProof);
        }
    }

    /** Captures the same typed proof consumed by the verifying boundary. */
    private static final class ProofCapturingProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final NodeProvider delegate;
        private final Map<String, CyclicSetProofResult> proofResults =
                new LinkedHashMap<>();

        private ProofCapturingProvider(NodeProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return delegate.fetchResultByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            CyclicSetProofResult result = delegate
                    instanceof CyclicAwareNodeProvider cyclic
                    ? cyclic.cyclicSetProofFor(blueId)
                    : CyclicSetProofResult.notFound();
            if (result == null) {
                throw new IllegalArgumentException(
                        "Cyclic-aware provider returned no typed proof result "
                                + "for " + blueId);
            }
            proofResults.put(blueId, result);
            return result;
        }

        private CyclicSetProofResult proofResult(String blueId) {
            return proofResults.get(blueId);
        }
    }

    record Resolution(
            List<ClosureResourceDemand> demands,
            List<ResolvedOccurrence> resolvedOccurrences,
            List<ResolvedExactNode> resolvedExactNodes,
            List<UnresolvedDemand> unresolvedDemands,
            Set<String> resolvedSelectorPaths) {
        Resolution(
                List<ClosureResourceDemand> demands,
                List<ResolvedOccurrence> resolvedOccurrences,
                List<ResolvedExactNode> resolvedExactNodes,
                List<UnresolvedDemand> unresolvedDemands) {
            this(
                    demands,
                    resolvedOccurrences,
                    resolvedExactNodes,
                    unresolvedDemands,
                    Set.of());
        }

        Resolution {
            demands = List.copyOf(Objects.requireNonNull(
                    demands, "demands"));
            resolvedOccurrences = List.copyOf(Objects.requireNonNull(
                    resolvedOccurrences, "resolvedOccurrences"));
            resolvedExactNodes = List.copyOf(Objects.requireNonNull(
                    resolvedExactNodes, "resolvedExactNodes"));
            unresolvedDemands = List.copyOf(Objects.requireNonNull(
                    unresolvedDemands, "unresolvedDemands"));
            resolvedSelectorPaths = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            resolvedSelectorPaths,
                            "resolvedSelectorPaths")));
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
                if (occurrence.targetKind() != TargetKind.NEW_AUTHORED) {
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
