package blue.coordination.internal;

import blue.coordination.api.DocumentId;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ResultingDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Frozen input provenance for a rooted terminal decision, including noncommitting failures. */
final class RootedTerminalEvidence {
    private final ClosureInvocationInput input;
    private final ContractsManagedDraftPlan managedDraftPlan;
    private final RootedInvocationEvidence rooted;
    private final String executedInvocationIdentity;
    private final String historicalWorkIdentity;
    private final blue.coordination.api.ManagedEpochApplicationWork historicalWork;
    private final java.util.Set<String> requiredTimelineIds;

    private RootedTerminalEvidence(ContractsClosureAdapter.CohortInvocation invocation,
            blue.coordination.api.ManagedEpochApplicationWork work) {
        this.input = invocation.input();
        this.managedDraftPlan = invocation.managedDraftPlan();
        this.rooted = Objects.requireNonNull(invocation.rootedEvidence(), "rooted evidence");
        this.executedInvocationIdentity = invocation.executionInvocationIdentity();
        var selectedWork = work == null ? rooted.historicalWork() : work;
        this.historicalWorkIdentity = selectedWork == null ? null : selectedWork.workIdentity();
        this.historicalWork = selectedWork;
        this.requiredTimelineIds = requiredTimelines(invocation);
        if (selectedWork != null) requireHistoricalCause(selectedWork);
    }

    static RootedTerminalEvidence capture(ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result) {
        if (invocation.rootedEvidence() == null) return null;
        RootedTerminalEvidence retained = new RootedTerminalEvidence(invocation, null);
        retained.requireResult(result, retained.rooted.terminalKey());
        return retained;
    }

    static RootedTerminalEvidence captureHistorical(ContractsClosureAdapter.CohortInvocation invocation,
            ClosureProcessResult result, blue.coordination.api.ManagedEpochApplicationWork work) {
        if (invocation.rootedEvidence() == null) return null;
        RootedTerminalEvidence retained = new RootedTerminalEvidence(invocation, work);
        retained.requireResult(result, retained.rooted.terminalKey());
        return retained;
    }

    boolean identifiesHistoricalWork(blue.coordination.api.ManagedEpochApplicationWork work) {
        return historicalWorkIdentity != null && historicalWorkIdentity.equals(work.workIdentity());
    }

    private void requireHistoricalCause(blue.coordination.api.ManagedEpochApplicationWork work) {
        String occurrence;
        String source;
        long epoch;
        if (input.cause() instanceof blue.language.processor.closure.ManagedRevisionCause revision) {
            occurrence = revision.targetOccurrenceIdentity(); source = revision.childDocumentId().value(); epoch = revision.toEpoch();
            if (work.isRepresentationApplication()
                    || !work.successorRepresentationCause().map(value -> value.causeIdentity())
                        .equals(revision.successorRepresentationCause().map(value -> value.causeIdentity()))) {
                throw new IllegalArgumentException("Numbered terminal changed its separately captured successor");
            }
        } else if (input.cause() instanceof blue.language.processor.closure.ManagedRepresentationCause representation) {
            occurrence = representation.targetOccurrenceIdentity(); source = representation.childDocumentId().value(); epoch = representation.fromEpoch();
            if (!work.representationCause().map(cause -> cause.causeIdentity().equals(representation.causeIdentity())).orElse(false)) {
                throw new IllegalArgumentException("Historical terminal has a different representation transition");
            }
        } else throw new IllegalArgumentException("Historical terminal requires an exact managed cause");
        if (!occurrence.equals(work.targetOccurrenceIdentity()) || !source.equals(work.sourceDocumentId().value())
                || epoch != work.sourceEpoch() || !input.snapshot().occurrences().stream().anyMatch(row ->
                        row.occurrenceIdentity().equals(occurrence)
                                && row.sourceDocumentId().value().equals(work.consumerDocumentId().value()))) {
            throw new IllegalArgumentException("Historical terminal does not identify its exact consumer/source work");
        }
    }

    void requireResult(ClosureProcessResult result, String terminalKey) {
        if (!rooted.terminalKey().equals(terminalKey)
                || !executedInvocationIdentity.equals(result.invocationIdentity())
                || !input.snapshot().closureIdentity().equals(result.inputClosureIdentity())) {
            throw new IllegalArgumentException("Rooted terminal decision differs from its frozen invocation");
        }
        if (result.commits()) {
            RootedResultScope.require(result, rooted);
            if (historicalWork != null && historicalWork.successorRepresentationCause().isPresent()) {
                var work = historicalWork;
                var successor = work.successorRepresentationCause().orElseThrow();
                var occurrence = result.occurrenceBindings().stream().filter(row ->
                        row.occurrenceIdentity().equals(work.targetOccurrenceIdentity())).findFirst().orElseThrow();
                var expected = new blue.language.processor.closure.ManagedRepresentationCursor(
                        work.sourceReceiptIdentity(), work.sourceReceiptIdentity(), successor.targetPositionIdentity(), null);
                if (occurrence.active() || !Objects.equals(occurrence.pendingHistoricalEpoch(), work.sourceEpoch())
                        || !expected.equals(occurrence.pendingRepresentationCursor())) {
                    throw new IllegalArgumentException("Numbered terminal must publish only its unconsumed anchor cursor");
                }
            }
        }
        else if (!result.rollbackToInput() || result.commitCompanion() != null) {
            throw new IllegalArgumentException("Rooted failure must retain exact rollback evidence without a companion");
        }
    }

    /** Binds a host rejection to the exact plan retained before the input was executed. */
    void requireRejectedDraftPlan(ContractsManagedDraftPlan plan, ClosureProcessResult result, String key) {
        requireResult(result, key);
        if (plan == null || plan != managedDraftPlan || !entryOwners().contains(plan.targetDocumentId())
                || !plan.missingExpectedOccurrence(result)) {
            throw new IllegalArgumentException("Rooted rejection differs from its captured managed draft plan");
        }
    }

    void requireSameObligation(RootedInvocationEvidence current) {
        if (current == null || !rooted.context().identity().equals(current.context().identity())
                || !rooted.deliveryBasisIdentity().equals(current.deliveryBasisIdentity())) {
            throw new IllegalArgumentException("Rooted terminal receipt belongs to another logical obligation");
        }
    }

    List<DocumentId> entryOwners() {
        return rooted.context().entryOwners().stream().map(ContractsClosureAdapter::coordinationId).toList();
    }

    ClosureInvocationInput input() { return input; }

    /** The immutable view must name its actual typed publication before its original cause can authorize a join. */
    static blue.language.processor.closure.ExternalEventCause originalLocalCause(RootedDocumentView view, InMemoryDocumentStore documents) {
        var projection = view.result().rootedProjection();
        var key = projection.context().terminalKey(projection.deliveryBasisIdentity());
        var receipt = documents.closurePublicationReceipt(key).orElse(null);
        if (receipt == null || receipt.rootedTerminalEvidence() == null) return null;
        var retained = receipt.rootedTerminalEvidence();
        retained.requireResult(view.result(), key);
        return retained.originalLocalCause(documents);
    }

    /** Follows only actual retained local publications to the original external cause, never a reconstructed event. */
    private blue.language.processor.closure.ExternalEventCause originalLocalCause(InMemoryDocumentStore documents) {
        RootedTerminalEvidence current = this;
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<RootedDocumentView, Boolean>());
        while (!(current.input.cause() instanceof blue.language.processor.closure.ExternalEventCause)) {
            var origin = current.rooted.historicalOrigin();
            if (origin == null) return null; // No original LIVE proof: retain the ordinary join fence.
            if (!visited.add(origin)) throw new IllegalArgumentException("Cyclic local publication provenance");
            var result = origin.result();
            var projection = Objects.requireNonNull(result.rootedProjection());
            String key = projection.context().terminalKey(projection.deliveryBasisIdentity());
            var receipt = documents.closurePublicationReceipt(key).orElse(null);
            if (receipt == null) return null;
            current = Objects.requireNonNull(receipt.rootedTerminalEvidence());
            current.requireResult(result, key);
        }
        return (blue.language.processor.closure.ExternalEventCause) current.input.cause();
    }

    blue.language.processor.ExternalOrderKey logicalBoundary(CatchUpPlanStore plans) {
        return rooted.historicalOrigin() == null ? RootedAttachmentCapture.logicalBoundary(input, plans)
                : rooted.historicalOrigin().logicalBoundary();
    }


    /** Authenticates indirect reference work whose direct lane is a read-only dependency. */
    boolean verifiesReadOnlyReferenceRebind(ClosureProcessResult result, DocumentId document,
            List<DocumentId> directTargets) {
        requireResult(result, rooted.terminalKey());
        var actualTargets = input.directDeliveries().stream()
                .map(delivery -> ContractsClosureAdapter.coordinationId(delivery.targetDocumentId()))
                .distinct().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList();
        var id = ContractsClosureAdapter.closureId(document);
        var before = input.snapshot().managedDocument(id);
        var after = result.resultingDocuments().stream().filter(value -> value.documentId().equals(id)).findFirst().orElse(null);
        if (!result.commits() || actualTargets.isEmpty() || !actualTargets.equals(directTargets)
                || actualTargets.contains(document) || !result.rootedProjection().owns(id)
                || before == null || after == null || before.epoch() != after.epoch()
                || !before.blueId().equals(after.beforeBlueId()) || before.blueId().equals(after.afterBlueId())
                || result.managedTransitionReceipts().stream().noneMatch(receipt -> receipt.documentId().equals(id)
                        && receipt.emittedRootEvents().isEmpty())) return false;
        Map<String, blue.language.model.Node> priorTargets = new java.util.LinkedHashMap<>();
        Map<String, blue.language.model.Node> nextTargets = new java.util.LinkedHashMap<>();
        input.snapshot().managedDocuments().forEach(value -> priorTargets.put(value.documentId().value(), value.document()));
        result.resultingDocuments().forEach(value -> nextTargets.put(value.documentId().value(), value.document()));
        return ManagedSourceReferenceRewrite.verifies(before.document(), after.document(),
                input.snapshot().occurrences().stream().filter(row -> row.sourceDocumentId().equals(id)).toList(),
                result.occurrenceBindings().stream().filter(row -> row.sourceDocumentId().equals(id)).toList(),
                priorTargets, nextTargets);
    }

    /** Authenticates the original rooted checkpoint class against its retained terminal authority. */
    void requireCheckpointReferencePosition(ClosureProcessResult result, DocumentId document, String expectedProof) {
        requireResult(result, rooted.terminalKey());
        var id = ContractsClosureAdapter.closureId(document);
        var directTargets = input.directDeliveries().stream()
                .map(delivery -> ContractsClosureAdapter.coordinationId(delivery.targetDocumentId()))
                .distinct().sorted(EmbeddingBinding.DOCUMENT_ORDER).toList();
        if (!entryOwners().contains(document) || directTargets.stream().anyMatch(target ->
                    result.rootedProjection().owns(ContractsClosureAdapter.closureId(target)))
                || !result.rootedProjection().checkpointReferenceProofIdentity(id).filter(expectedProof::equals).isPresent()
                || !verifiesReadOnlyReferenceRebind(result, document, directTargets)) {
            throw new IllegalArgumentException("Historical checkpoint position lacks its exact original rooted authority");
        }
    }

    /** Proves the existing pending-target exception against the current immutable history and complete result. */
    boolean verifiesLocalHistoricalRebind(ClosureProcessResult result, DocumentId document,
            blue.coordination.api.ManagedEpochApplicationWork work, InMemoryDocumentStore documents) {
        if (rooted.historicalOrigin() == null || rooted.historicalWork() == null || work == null
                || !identifiesHistoricalWork(work) || !work.sourceDocumentId().equals(document)
                || work.sourceDocumentId().equals(work.consumerDocumentId())) return false;
        requireResult(result, rooted.terminalKey());
        var id = ContractsClosureAdapter.closureId(document);
        var before = input.snapshot().managedDocument(id);
        var after = result.resultingDocuments().stream().filter(value -> value.documentId().equals(id)).findFirst().orElse(null);
        var transition = result.managedTransitionReceipts().stream().filter(value -> value.documentId().equals(id)).findFirst().orElse(null);
        if (!result.commits() || !result.rootedProjection().owns(id) || before == null || after == null || transition == null
                || before.epoch() != after.epoch() || before.blueId().equals(after.afterBlueId())) return false;
        var current = documents.require(document);
        if (current.epoch() != before.epoch() || !current.currentRepresentation().blueId().equals(before.blueId())) return false;
        var chain = new ManagedRepresentationHistory(documents).at(document, before.epoch());
        // The chain was reauthenticated against actual retained publications. This constructor
        // additionally verifies lifecycle, the selected pending row, complete reference-only
        // body/binding equality, original invocation, transition receipt and commit companion.
        new blue.language.processor.closure.ManagedRepresentationTransition(id, before.epoch(),
                chain.anchor().receiptIdentity(), chain.targetPositionIdentity(), input, result,
                transition.transitionReceiptIdentity());
        return true;
    }

    java.util.Set<String> requiredTimelineIds() { return requiredTimelineIds; }

    /** Frozen provider surface, not the ambient registry or a checkpoint-derived claim. */
    private static java.util.Set<String> requiredTimelines(ContractsClosureAdapter.CohortInvocation invocation) {
        java.util.Set<String> timelines = new java.util.LinkedHashSet<>();
        java.util.Set<blue.language.processor.closure.DocumentId> visited = new java.util.LinkedHashSet<>();
        var pending = new java.util.ArrayDeque<>(invocation.rootedEvidence().context().entryOwners());
        while (!pending.isEmpty()) {
            var id = pending.removeFirst();
            if (!visited.add(id)) continue;
            var document = Objects.requireNonNull(invocation.documents().get(ContractsClosureAdapter.coordinationId(id)),
                    "Required provider document is absent from frozen capture");
            timelines.addAll(document.layout().routingSurface().externalTimelineIds());
            invocation.input().snapshot().occurrences().stream()
                    .filter(row -> row.active() && row.sourceDocumentId().equals(id))
                    .forEach(row -> pending.addLast(row.targetDocumentId()));
        }
        return java.util.Set.copyOf(timelines);
    }

    Map<DocumentId, ResultingDocument> publicationDocuments(ClosureProcessResult result) {
        List<DocumentId> owners = result.commits() ? RootedResultScope.members(result) : entryOwners();
        Map<DocumentId, ResultingDocument> documents = new TreeMap<>(EmbeddingBinding.DOCUMENT_ORDER);
        for (ResultingDocument document : result.resultingDocuments()) {
            DocumentId id = ContractsClosureAdapter.coordinationId(document.documentId());
            if (owners.contains(id)) documents.put(id, document);
        }
        if (documents.size() != owners.size()) throw new IllegalArgumentException("Rooted result omits an entry owner");
        return java.util.Collections.unmodifiableMap(documents);
    }
}
